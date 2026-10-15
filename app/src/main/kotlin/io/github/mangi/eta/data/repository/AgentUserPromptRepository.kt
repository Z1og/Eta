package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户自备作战协议的生效范围。
 *
 * 默认 [Off]：文件存在不等于生效——用户显式开启前，会话行为不发生任何变化，
 * 避免"文件躺在那儿、行为已经变了、用户还不知道为什么"。
 */
internal enum class AgentUserPromptMode(val persistedValue: String) {
    /** 不注入。 */
    Off("off"),

    /** 所有会话都注入。 */
    Global("global"),

    /** 仅逆向模式（所有已安装逆向技能均启用）的会话注入。 */
    ReverseOnly("reverse");

    companion object {
        fun fromPersistedValue(value: String?): AgentUserPromptMode =
            values().firstOrNull { it.persistedValue == value } ?: Off
    }
}

/**
 * 一次装载的用户作战协议快照。
 *
 * [content] 已按 [AgentUserPromptStore.MAX_CHARS] 截断，[charCount] 是截断前的原始字符数。
 */
internal data class AgentUserPromptSnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val charCount: Int,
    val fileName: String?,
    val truncated: Boolean,
) {
    /** 存在可注入的正文。文件缺失或只有空白时都是 false。 */
    val available: Boolean get() = !fileName.isNullOrBlank() && content.isNotBlank()

    companion object {
        val EMPTY = AgentUserPromptSnapshot(
            content = "",
            revision = "",
            byteSize = 0,
            charCount = 0,
            fileName = null,
            truncated = false,
        )
    }
}

internal class AgentUserPromptException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * `filesDir/prompts/` 下用户自备提示词文件的装载、原子写入与清理。
 *
 * 装载规则与寒霜的提示词目录一致：
 * - 目录内按文件名排序，取**第一个非空** `.md`；
 * - `README.md` 固定忽略；
 * - 目录不存在、只有空文件时视为未配置——**不产生任何注入**，也不写半成品。
 *
 * 快照在会话开始时读取一次并在会话内保持不变；会话中途改文件不会改变正在进行的请求前缀，
 * 否则系统提示抖动会废掉整段 prompt 缓存。
 */
internal class AgentUserPromptStore(
    rootDir: File,
) {
    private val promptDir = File(rootDir, DIRECTORY_NAME)
    private val backupDir = File(promptDir, BACKUP_DIRECTORY_NAME)
    private val lock = Any()

    fun snapshot(): AgentUserPromptSnapshot = synchronized(lock) { snapshotLocked() }

    /** 写入当前生效的那份文件；还没有文件时写默认名。 */
    fun replaceAll(content: String): AgentUserPromptSnapshot = synchronized(lock) {
        writeLocked(content, resolveFile()?.name ?: DEFAULT_FILE_NAME)
        snapshotLocked()
    }

    /** 清空当前生效的那份文件；清空前先在同一目录的备份子目录留一份副本。 */
    fun clearActive(): AgentUserPromptSnapshot = synchronized(lock) {
        resolveFile()?.let { active ->
            runCatching {
                if (!backupDir.exists() && !backupDir.mkdirs() && !backupDir.isDirectory) {
                    error("无法创建备份目录")
                }
                val stamp = System.currentTimeMillis()
                active.copyTo(File(backupDir, "${active.name}.$stamp$BACKUP_SUFFIX"), overwrite = true)
            }.getOrElse { throwable ->
                throw AgentUserPromptException(
                    code = "PROMPT_BACKUP_FAILED",
                    message = "备份当前作战协议失败，已保留原文件",
                    cause = throwable,
                )
            }
            if (!active.delete() && active.exists()) {
                throw AgentUserPromptException(
                    code = "PROMPT_CLEAR_FAILED",
                    message = "无法删除作战协议文件",
                )
            }
        }
        snapshotLocked()
    }

    private fun snapshotLocked(): AgentUserPromptSnapshot {
        val file = resolveFile() ?: return AgentUserPromptSnapshot.EMPTY
        val bytes = try {
            file.readBytes()
        } catch (throwable: IOException) {
            throw AgentUserPromptException(
                code = "PROMPT_READ_FAILED",
                message = "无法读取作战协议文件 ${file.name}",
                cause = throwable,
            )
        }
        val raw = bytes.toString(Charsets.UTF_8)
        val truncated = raw.length > MAX_CHARS
        val content = if (truncated) {
            raw.take(MAX_CHARS) +
                "\n[作战协议超过 $MAX_CHARS 字符上限，已截断 ${raw.length - MAX_CHARS} 字符；请精简文件]"
        } else {
            raw
        }
        return AgentUserPromptSnapshot(
            content = content,
            revision = sha256(bytes),
            byteSize = bytes.size,
            charCount = raw.length,
            fileName = file.name,
            truncated = truncated,
        )
    }

    private fun writeLocked(content: String, fileName: String) {
        val name = sanitizeFileName(fileName)
        if (!promptDir.exists() && !promptDir.mkdirs() && !promptDir.isDirectory) {
            throw AgentUserPromptException(
                code = "PROMPT_WRITE_FAILED",
                message = "无法创建 $DIRECTORY_NAME 目录",
            )
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentUserPromptException(
                code = "PROMPT_TOO_LARGE",
                message = "作战协议不能超过 ${MAX_FILE_BYTES / 1024} KiB UTF-8 字节",
            )
        }
        val atomicFile = AtomicFile(File(promptDir, name))
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentUserPromptException(
                code = "PROMPT_WRITE_FAILED",
                message = "无法开始写入作战协议",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentUserPromptException(
                code = "PROMPT_WRITE_FAILED",
                message = "无法保存作战协议",
                cause = throwable,
            )
        }
    }

    /** 目录内按文件名排序，返回第一个非空 `.md`；跳过 README、空文件与超限文件。 */
    private fun resolveFile(): File? {
        if (!promptDir.isDirectory) return null
        val candidates = promptDir.listFiles { file ->
            file.isFile &&
                file.name.endsWith(EXTENSION, ignoreCase = true) &&
                !file.name.equals(IGNORED_FILE, ignoreCase = true)
        }?.sortedBy { it.name } ?: return null
        for (candidate in candidates) {
            val length = candidate.length()
            if (length <= 0L || length > MAX_FILE_BYTES) continue
            val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (text.isNotBlank()) return candidate
        }
        return null
    }

    /** 只取文件名，禁止路径穿越；非法输入回落到默认名。 */
    private fun sanitizeFileName(fileName: String): String {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty()) return DEFAULT_FILE_NAME
        val base = File(trimmed).name
        if (base.isEmpty() || base.startsWith(".") || !base.endsWith(EXTENSION, ignoreCase = true)) {
            return DEFAULT_FILE_NAME
        }
        return base
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val DIRECTORY_NAME = "prompts"
        const val DEFAULT_FILE_NAME = "破甲记忆.md"
        const val IGNORED_FILE = "README.md"

        /**
         * 注入上限：整篇注入，超限显式截断并留标记——提示词不拆 core/headings，每段都是指令。
         *
         * 取值依据：寒霜空投的 `memory/破甲记忆.md`（v5.2 全文）实测 81,044 字符 / 1,337 行。
         * 32k 会从尾部砍掉约六成，而尾部恰好是 Part II/III/IV playbook 与抗压缩常驻的 core block；
         * 96k 让整篇进得去，代价是系统提示占更多 token——UI 会实时显示占用与接近上限的警示。
         */
        const val MAX_CHARS = 96_000

        /** 单文件字节上限。96k 全 CJK 字符按 UTF-8 三字节计约 288 KiB，512 KiB 仍有余量。 */
        const val MAX_FILE_BYTES = 512 * 1024

        private const val EXTENSION = ".md"
        private const val BACKUP_SUFFIX = ".bak"
        private const val BACKUP_DIRECTORY_NAME = ".backup"
    }
}

/**
 * 作战协议的进程级入口。
 *
 * 与 [AgentMemoryRepository] 的分工：记忆是"用户可编辑的背景资料，不是指令"；
 * 作战协议是**用户指令本身**，因此走独立的 system 消息，绝不塞进 memory_core。
 */
internal object AgentUserPromptRepository {

    @Volatile
    private lateinit var store: AgentUserPromptStore

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentUserPromptStore(context.applicationContext.filesDir)
        }
    }

    fun snapshot(): AgentUserPromptSnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun replaceAll(content: String): AgentUserPromptSnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    fun clearActive(): AgentUserPromptSnapshot {
        ensureInitialized()
        return store.clearActive()
    }

    fun modeFlow(): Flow<AgentUserPromptMode> =
        SettingsDataStore.userPromptModeFlow().map { AgentUserPromptMode.fromPersistedValue(it) }

    suspend fun mode(): AgentUserPromptMode =
        AgentUserPromptMode.fromPersistedValue(SettingsDataStore.userPromptMode())

    suspend fun setMode(mode: AgentUserPromptMode) =
        SettingsDataStore.setUserPromptMode(mode.persistedValue)

    /** 纯判定：关闭 → 否；仅逆向模式 → 取决于当前是否逆向模式；全局 → 是。 */
    fun isActive(mode: AgentUserPromptMode, reverseModeEnabled: Boolean): Boolean = when (mode) {
        AgentUserPromptMode.Off -> false
        AgentUserPromptMode.Global -> true
        AgentUserPromptMode.ReverseOnly -> reverseModeEnabled
    }

    /** 运行期装载：未开启或文件为空时返回 [AgentUserPromptSnapshot.EMPTY]，调用方据此不注入。 */
    fun loadFor(mode: AgentUserPromptMode, reverseModeEnabled: Boolean): AgentUserPromptSnapshot =
        if (isActive(mode, reverseModeEnabled)) {
            runCatching { snapshot() }.getOrDefault(AgentUserPromptSnapshot.EMPTY)
        } else {
            AgentUserPromptSnapshot.EMPTY
        }

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentUserPromptRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
