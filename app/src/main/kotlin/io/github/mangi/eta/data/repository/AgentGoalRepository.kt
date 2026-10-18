package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** 目标读写失败时抛出；[code] 供 UI 区分提示。 */
internal class AgentGoalException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 每个会话一份持续目标：`filesDir/goals/<conversationId>.md`。
 *
 * 为什么不塞进会话状态：目标是一段会进系统提示的正文，独立文件才能原子写、单独清理，
 * 也不会因为会话 JSON 结构变动被顺手丢掉。
 *
 * 目标为空时直接删除文件——"没有目标"和"目标是空字符串"必须是同一种状态，
 * 否则注入侧要靠字符串判断，容易只生效一半。
 */
internal class AgentGoalStore(rootDir: File) {
    private val goalDir = File(rootDir, DIRECTORY_NAME)
    private val lock = Any()

    fun goal(conversationId: String): String = synchronized(lock) { read(conversationId) }

    /** 保存并返回落盘后的正文（已归一化换行、已截断到 [MAX_CHARS]）。空正文等于清除。 */
    fun save(conversationId: String, goal: String): String = synchronized(lock) {
        val text = normalize(goal)
        if (text.isEmpty()) {
            clearLocked(conversationId)
            return ""
        }
        val target = fileFor(conversationId)
            ?: throw AgentGoalException("GOAL_INVALID_CONVERSATION", "无法识别该会话，目标未保存")
        // 保存新目标意味着重新开始：旧的完成标记一并清掉。
        clearCompletedLocked(conversationId)
        if (!goalDir.exists() && !goalDir.mkdirs() && !goalDir.isDirectory) {
            throw AgentGoalException("GOAL_WRITE_FAILED", "无法创建 $DIRECTORY_NAME 目录")
        }
        val atomicFile = AtomicFile(target)
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentGoalException("GOAL_WRITE_FAILED", "无法开始写入目标", throwable)
        }
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentGoalException("GOAL_WRITE_FAILED", "无法保存目标", throwable)
        }
        text
    }

    fun clear(conversationId: String) {
        synchronized(lock) { clearLocked(conversationId) }
    }

    /**
     * 目标是否已经完成过。
     *
     * 为什么需要这个标记：完成状态以前只存在于单次 run 的内存里，
     * 用户下次随便说句话，新 run 又会重新注入目标并从头推进——这就是"用着怪"的来源。
     */
    fun isCompleted(conversationId: String): Boolean = synchronized(lock) {
        doneFileFor(conversationId)?.isFile == true
    }

    fun markCompleted(conversationId: String) {
        synchronized(lock) {
            val target = doneFileFor(conversationId) ?: return
            runCatching {
                target.parentFile?.mkdirs()
                if (!target.exists()) target.createNewFile()
            }
        }
    }

    /** 新目标从零开始：旧的完成标记必须一起清掉。 */
    private fun clearCompletedLocked(conversationId: String) {
        val target = doneFileFor(conversationId) ?: return
        runCatching { if (target.exists()) target.delete() }
    }

    private fun doneFileFor(conversationId: String): File? {
        val base = fileFor(conversationId) ?: return null
        return File(base.parentFile, base.nameWithoutExtension + COMPLETED_EXTENSION)
    }

    private fun clearLocked(conversationId: String) {
        val target = fileFor(conversationId) ?: return
        if (target.exists() && !target.delete()) {
            throw AgentGoalException("GOAL_CLEAR_FAILED", "无法删除目标文件")
        }
        clearCompletedLocked(conversationId)
    }

    private fun read(conversationId: String): String {
        val target = fileFor(conversationId) ?: return ""
        if (!target.isFile) return ""
        val text = runCatching { target.readText(Charsets.UTF_8) }.getOrElse { throwable ->
            throw AgentGoalException("GOAL_READ_FAILED", "无法读取目标", throwable)
        }
        return normalize(text)
    }

    private fun normalize(raw: String): String {
        val text = raw.replace("\r\n", "\n").trim()
        // 已经带截断标记就原样返回：否则每次读盘都会再截一次，标记越叠越长。
        if (text.length <= MAX_CHARS || text.contains(TRUNCATION_PREFIX)) return text
        return text.take(MAX_CHARS) + "\n$TRUNCATION_PREFIX ${text.length - MAX_CHARS} 字符]"
    }

    /** 会话 id 会被当成文件名，非安全字符一律替换，避免路径穿越与非法文件名。 */
    private fun fileFor(conversationId: String): File? {
        val name = conversationId.trim()
        if (name.isEmpty()) return null
        val safe = name.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') char else '_'
        }.joinToString("")
        if (safe.isEmpty() || safe.all { it == '.' || it == '_' }) return null
        return File(goalDir, "$safe$EXTENSION")
    }

    companion object {
        const val DIRECTORY_NAME = "goals"
        const val EXTENSION = ".md"

        /** 完成标记文件后缀：`<convId>.done`，存在即表示这个目标已经收工。 */
        const val COMPLETED_EXTENSION = ".done"

        /**
         * 目标上限：目标该是一条验收条件，不是第二份作战协议。
         * 4000 字符足够写清"做成什么样"，再长说明该拆成多个任务。
         */
        const val MAX_CHARS = 4_000

        /** 截断标记的稳定前缀：读盘时用它识别"这已经是截断过的内容"，避免二次截断。 */
        private const val TRUNCATION_PREFIX = "[目标超过 $MAX_CHARS 字符上限，已截断"
    }
}

internal object AgentGoalRepository {
    @Volatile
    private var store: AgentGoalStore? = null

    fun init(context: Context) {
        store = AgentGoalStore(context.filesDir)
    }

    private fun requireStore(): AgentGoalStore = store
        ?: throw AgentGoalException("GOAL_NOT_INITIALIZED", "目标存储尚未初始化")

    fun goal(conversationId: String): String =
        if (conversationId.isBlank()) "" else requireStore().goal(conversationId)

    fun save(conversationId: String, goal: String): String =
        requireStore().save(conversationId, goal)

    fun clear(conversationId: String) {
        if (conversationId.isBlank()) return
        requireStore().clear(conversationId)
    }

    fun isCompleted(conversationId: String): Boolean =
        if (conversationId.isBlank()) false else requireStore().isCompleted(conversationId)

    fun markCompleted(conversationId: String) {
        if (conversationId.isBlank()) return
        requireStore().markCompleted(conversationId)
    }
}
