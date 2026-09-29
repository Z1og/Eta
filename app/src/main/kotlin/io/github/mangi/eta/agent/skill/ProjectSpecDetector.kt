package io.github.mangi.eta.agent.skill

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * Trellis 项目规范探测（方案 A：零配置）。
 *
 * 从用户消息中提取文件路径，沿路径向上查找 `.trellis/spec/` 目录；找到后读取 spec 索引
 * （每个 markdown 文件的首个标题行）。探测只读，任何一步失败都静默降级为 null，
 * 不影响正常对话；未命中任何规范时本轮与普通对话完全一致。
 */
internal object ProjectSpecDetector {
    private const val SPEC_ROOT_DIR = ".trellis"
    private const val SPEC_SUB_DIR = "spec"
    private const val MAX_UPWARD_LEVELS = 8
    private const val MAX_CANDIDATE_PATHS = 6
    private const val MAX_SPEC_FILES = 8
    private const val MAX_TITLE_CHARS = 80
    private const val MAX_INDEX_CHARS = 1200
    private const val MAX_PROMPT_CHARS = 8192
    private const val MAX_PATH_CHARS = 512
    private const val MAX_TITLE_READ_BYTES = 4096

    /**
     * 匹配绝对路径或 ~/ 开头的路径（至少含一个目录分隔）。
     * 允许中文等非 ASCII 文件名，排除空白、引号与常见中英文标点，避免把句子切进路径。
     */
    private val PATH_REGEX = Regex("""(?:~/|/)[^\s"'`，。；：！？、（）【】《》<>|*?\\]+""")
    private val HEADING_REGEX = Regex("""#\s+(.+)""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun detect(prompt: String): ProjectSpecIndex? {
        if (prompt.isBlank() || prompt.length > MAX_PROMPT_CHARS) return null
        val candidates = PATH_REGEX.findAll(prompt)
            .map { expandTilde(it.value) }
            .filter { it.length in 4..MAX_PATH_CHARS }
            .distinct()
            .take(MAX_CANDIDATE_PATHS)
            .toList()
        if (candidates.isEmpty()) return null
        candidates.forEach { candidate ->
            val specDir = findSpecDir(candidate) ?: return@forEach
            return buildIndex(specDir)
        }
        return null
    }

    /** 从候选路径（文件或目录）向上最多 [MAX_UPWARD_LEVELS] 级查找 .trellis/spec 目录。 */
    private fun findSpecDir(candidate: String): File? {
        var dir = File(candidate)
        if (dir.isFile) dir = dir.parentFile ?: return null
        if (!dir.isDirectory) return null
        repeat(MAX_UPWARD_LEVELS) {
            val specDir = File(dir, "$SPEC_ROOT_DIR/$SPEC_SUB_DIR")
            if (specDir.isDirectory) return specDir
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun buildIndex(specDir: File): ProjectSpecIndex? {
        val files = runCatching {
            specDir.listFiles { file -> file.isFile && file.name.endsWith(".md") }
                ?.sortedBy { it.name }
                ?.take(MAX_SPEC_FILES)
                .orEmpty()
        }.getOrNull()
        if (files.isNullOrEmpty()) return null
        var budget = MAX_INDEX_CHARS
        val entries = files.mapNotNull { file ->
            if (budget <= 0) return@mapNotNull null
            val title = readTitle(file) ?: return@mapNotNull null
            budget -= title.length + file.name.length + 4
            ProjectSpecEntry(fileName = file.name, title = title)
        }
        if (entries.isEmpty()) return null
        val projectRoot = specDir.parentFile?.parentFile?.absolutePath ?: specDir.absolutePath
        return ProjectSpecIndex(
            projectRoot = projectRoot,
            specDirPath = specDir.absolutePath,
            entries = entries,
        )
    }

    /** 读取文件头部首个一级标题作为摘要；没有标题就用首个非空行。 */
    private fun readTitle(file: File): String? {
        val head = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(MAX_TITLE_READ_BYTES)
                val read = input.read(buffer)
                if (read <= 0) null else String(buffer, 0, read, Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        var fallback: String? = null
        for (raw in head.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            HEADING_REGEX.matchEntire(line)?.let { return cleanupTitle(it.groupValues[1]) }
            if (fallback == null) fallback = line
        }
        return fallback?.let(::cleanupTitle)
    }

    private fun cleanupTitle(title: String): String {
        val cleaned = title.replace(WHITESPACE_REGEX, " ").trim()
        if (cleaned.isEmpty()) return ""
        return if (cleaned.length <= MAX_TITLE_CHARS) cleaned else cleaned.take(MAX_TITLE_CHARS) + "..."
    }

    /** Eta 终端工具约定 ~/ 表示 /storage/emulated/0，探测前先展开。 */
    private fun expandTilde(path: String): String =
        if (path.startsWith("~/")) "/storage/emulated/0/${path.removePrefix("~/")}" else path
}

/** Trellis 项目规范索引——探测命中后注入系统提示词的数据载体。 */
@Immutable
data class ProjectSpecIndex(
    val projectRoot: String,
    val specDirPath: String,
    val entries: List<ProjectSpecEntry>,
)

/** 单个规范文件的索引条目。 */
@Immutable
data class ProjectSpecEntry(
    val fileName: String,
    val title: String,
)
