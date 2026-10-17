package io.github.mangi.eta.data.repository

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** 子任务状态。`blocked` 与 `done` 一样算"这条不用再推"，但语义是卡住了。 */
internal enum class AgentTaskStatus(val wireValue: String) {
    Pending("pending"),
    InProgress("in_progress"),
    Done("done"),
    Blocked("blocked"),
    ;

    companion object {
        fun fromWire(value: String?): AgentTaskStatus? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

internal data class AgentTaskItem(
    val id: String,
    val title: String,
    val status: AgentTaskStatus,
    val note: String = "",
)

/**
 * 目标下面的子任务清单。
 *
 * 为什么要落盘而不是留在上下文里：目标续跑可以跨十几轮，中途还会触发上下文压缩，
 * 只靠模型"记着"必然重复做或漏做。清单是外部进度，压缩不掉。
 */
internal data class AgentTaskPlan(
    val tasks: List<AgentTaskItem> = emptyList(),
) {
    val total: Int get() = tasks.size

    val doneCount: Int get() = tasks.count { it.status == AgentTaskStatus.Done }

    /** 还需要推进的条数（pending + in_progress）。blocked 不算活跃，但会拦住"全部完成"。 */
    val openCount: Int
        get() = tasks.count {
            it.status == AgentTaskStatus.Pending || it.status == AgentTaskStatus.InProgress
        }

    val blockedCount: Int get() = tasks.count { it.status == AgentTaskStatus.Blocked }

    /** 只有"有清单且没有待办"才算收工；空清单不代表完成，否则模型清空清单就能骗过续跑判定。 */
    val allDone: Boolean get() = tasks.isNotEmpty() && openCount == 0

    /** 下一条该做什么：优先进行中的，其次第一条待办。 */
    val current: AgentTaskItem?
        get() = tasks.firstOrNull { it.status == AgentTaskStatus.InProgress }
            ?: tasks.firstOrNull { it.status == AgentTaskStatus.Pending }

    /**
     * 注入用的精简清单。刻意压到很短：它每轮都会进上下文，
     * 写详细了等于用清单自己挤占工作空间。
     */
    fun summary(): String {
        if (tasks.isEmpty()) return ""
        return buildString {
            append("子任务 $doneCount/$total 完成")
            if (blockedCount > 0) append("，$blockedCount 条阻塞")
            appendLine()
            tasks.forEach { task ->
                append(
                    when (task.status) {
                        AgentTaskStatus.Done -> "[x] "
                        AgentTaskStatus.InProgress -> "[>] "
                        AgentTaskStatus.Blocked -> "[!] "
                        AgentTaskStatus.Pending -> "[ ] "
                    },
                )
                append(task.id).append(' ').append(task.title)
                if (task.note.isNotBlank()) append("（").append(task.note).append('）')
                appendLine()
            }
        }.trimEnd()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tasks", JSONArray().also { array ->
            tasks.forEach { task ->
                array.put(
                    JSONObject()
                        .put("id", task.id)
                        .put("title", task.title)
                        .put("status", task.status.wireValue)
                        .apply { if (task.note.isNotBlank()) put("note", task.note) },
                )
            }
        })
    }

    companion object {
        val EMPTY = AgentTaskPlan()

        /** 单条清单最多多少个子任务；再多就说明这个目标该拆成多个会话。 */
        const val MAX_TASKS = 24

        const val MAX_TITLE_CHARS = 200
        const val MAX_NOTE_CHARS = 300

        fun fromJson(raw: String): AgentTaskPlan? = runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("tasks") ?: JSONArray()
            val tasks = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
                AgentTaskItem(
                    id = id.take(64),
                    title = title.take(MAX_TITLE_CHARS),
                    status = AgentTaskStatus.fromWire(item.optString("status"))
                        ?: AgentTaskStatus.Pending,
                    note = item.optString("note").trim().take(MAX_NOTE_CHARS),
                )
            }
            AgentTaskPlan(tasks.take(MAX_TASKS))
        }.getOrNull()
    }
}

/**
 * 子任务清单的落盘：`filesDir/goals/<conversationId>.tasks.json`。
 *
 * 与目标正文同目录但不同文件：目标是一段要进系统提示的正文，清单是高频变更的结构化进度，
 * 混在一个文件里会让每次勾选都重写目标正文。
 */
internal class AgentTaskPlanStore(rootDir: File) {
    private val goalDir = File(rootDir, AgentGoalStore.DIRECTORY_NAME)
    private val lock = Any()

    fun load(conversationId: String): AgentTaskPlan =
        synchronized(lock) { readLocked(conversationId) }

    fun save(conversationId: String, plan: AgentTaskPlan): AgentTaskPlan = synchronized(lock) {
        val normalized = AgentTaskPlan(plan.tasks.take(AgentTaskPlan.MAX_TASKS))
        if (normalized.tasks.isEmpty()) {
            clearLocked(conversationId)
            return AgentTaskPlan.EMPTY
        }
        val target = fileFor(conversationId)
            ?: throw AgentGoalException("TASKS_INVALID_CONVERSATION", "无法识别该会话，清单未保存")
        if (!goalDir.exists() && !goalDir.mkdirs() && !goalDir.isDirectory) {
            throw AgentGoalException("TASKS_WRITE_FAILED", "无法创建 ${AgentGoalStore.DIRECTORY_NAME} 目录")
        }
        val atomicFile = AtomicFile(target)
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentGoalException("TASKS_WRITE_FAILED", "无法开始写入任务清单", throwable)
        }
        try {
            output.write(normalized.toJson().toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentGoalException("TASKS_WRITE_FAILED", "无法保存任务清单", throwable)
        }
        normalized
    }

    fun clear(conversationId: String) {
        synchronized(lock) { clearLocked(conversationId) }
    }

    private fun clearLocked(conversationId: String) {
        val target = fileFor(conversationId) ?: return
        if (target.exists() && !target.delete()) {
            throw AgentGoalException("TASKS_CLEAR_FAILED", "无法删除任务清单")
        }
    }

    private fun readLocked(conversationId: String): AgentTaskPlan {
        val target = fileFor(conversationId) ?: return AgentTaskPlan.EMPTY
        if (!target.isFile) return AgentTaskPlan.EMPTY
        val raw = runCatching { target.readText(Charsets.UTF_8) }.getOrNull() ?: return AgentTaskPlan.EMPTY
        return AgentTaskPlan.fromJson(raw) ?: AgentTaskPlan.EMPTY
    }

    private fun fileFor(conversationId: String): File? {
        val name = conversationId.trim()
        if (name.isEmpty()) return null
        val safe = name.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') char else '_'
        }.joinToString("")
        if (safe.isEmpty() || safe.all { it == '.' || it == '_' }) return null
        return File(goalDir, "$safe.tasks.json")
    }
}
