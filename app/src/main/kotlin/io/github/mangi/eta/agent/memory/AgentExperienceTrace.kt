package io.github.mangi.eta.agent.memory

import org.json.JSONObject

/**
 * A privacy-preserving account of a run. No tool arguments, tool outputs, chat text, or
 * screen contents are retained; only published tool names and machine-readable error codes.
 */
internal data class AgentExperienceEvidence(
    val toolNames: List<String>,
    val errorCodes: List<String>,
) {
    val hasTools: Boolean get() = toolNames.isNotEmpty()
}

internal class AgentExperienceTrace {
    private val lock = Any()
    private val tools = mutableListOf<String>()
    private val errors = linkedSetOf<String>()

    fun observe(toolName: String, resultContent: String) {
        if (!TOOL_NAME.matches(toolName) || toolName in EXCLUDED_TOOLS ||
            toolName.startsWith("character_memory_") || toolName.startsWith("skills_")
        ) return

        val code = if (resultContent.length <= MAX_ERROR_RESULT_CHARS &&
            resultContent.trimStart().startsWith("{")
        ) {
            runCatching {
                JSONObject(resultContent).let { body ->
                    if (body.optBoolean("ok", true)) null else body.optString("code")
                }
            }.getOrNull()?.takeIf { ERROR_CODE.matches(it) }
        } else null

        synchronized(lock) {
            if (tools.size < MAX_TOOLS_PER_RUN) tools += toolName
            if (code != null && errors.size < MAX_ERROR_CODES) errors += code
        }
    }

    fun snapshot(): AgentExperienceEvidence = synchronized(lock) {
        AgentExperienceEvidence(tools.toList(), errors.toList())
    }

    companion object {
        private const val MAX_TOOLS_PER_RUN = 16
        private const val MAX_ERROR_CODES = 8
        private const val MAX_ERROR_RESULT_CHARS = 2_048
        private val TOOL_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{2,63}")
        private val EXCLUDED_TOOLS = setOf(
            "memory_get", "memory_write", "task_write", "spawn_agents",
            "conversation_history", "read_conversation_history",
            "knowledge_search", "knowledge_get", "knowledge_save", "knowledge_delete",
        )
    }
}

/**
 * Task fingerprints are assembled exclusively from a controlled vocabulary.
 * This avoids storing names, messages, search terms, credentials, and other user input.
 * Match quality is deliberately conservative: generic verbs alone are not enough.
 */
internal object AgentExperienceVocabulary {
    private data class Tag(val text: String, val pattern: Regex, val specific: Boolean)

    private val tags = listOf(
        Tag("微信", Regex("微信|wechat", RegexOption.IGNORE_CASE), true),
        Tag("QQ", Regex("qq聊天|qq消息|腾讯qq", RegexOption.IGNORE_CASE), true),
        Tag("支付宝", Regex("支付宝|alipay", RegexOption.IGNORE_CASE), true),
        Tag("淘宝", Regex("淘宝|taobao", RegexOption.IGNORE_CASE), true),
        Tag("抖音", Regex("抖音|tiktok", RegexOption.IGNORE_CASE), true),
        Tag("浏览器", Regex("浏览器|网页|网站|chrome|browser|website", RegexOption.IGNORE_CASE), true),
        Tag("系统设置", Regex("系统设置|设置页|settings|设置", RegexOption.IGNORE_CASE), true),
        Tag("应用安装", Regex("apk|安装包|安装应用|install app", RegexOption.IGNORE_CASE), true),
        Tag("相册", Regex("相册|照片|photo|gallery", RegexOption.IGNORE_CASE), true),
        Tag("相机", Regex("相机|拍照|camera", RegexOption.IGNORE_CASE), true),
        Tag("通知", Regex("通知|notification", RegexOption.IGNORE_CASE), true),
        Tag("短信", Regex("短信|sms", RegexOption.IGNORE_CASE), true),
        Tag("电话", Regex("电话|拨号|通话|call", RegexOption.IGNORE_CASE), true),
        Tag("日历", Regex("日历|calendar|日程", RegexOption.IGNORE_CASE), true),
        Tag("文件", Regex("文件|目录|文件夹|file|folder", RegexOption.IGNORE_CASE), true),
        Tag("终端", Regex("root|shell|终端|命令行|adb|termux", RegexOption.IGNORE_CASE), true),
        Tag("音乐", Regex("音乐|歌曲|网易云|music|spotify", RegexOption.IGNORE_CASE), true),
        Tag("视频", Regex("视频|bilibili|哔哩哔哩|youtube|video", RegexOption.IGNORE_CASE), true),
        Tag("下载", Regex("下载|download", RegexOption.IGNORE_CASE), false),
        Tag("搜索", Regex("搜索|查找|检索|search|find", RegexOption.IGNORE_CASE), false),
        Tag("打开", Regex("打开|启动|进入|open|launch", RegexOption.IGNORE_CASE), false),
        Tag("发送", Regex("发送|发消息|回复消息|send|reply", RegexOption.IGNORE_CASE), false),
        Tag("输入", Regex("输入|填写|打字|type|enter text", RegexOption.IGNORE_CASE), false),
        Tag("读取", Regex("读取|查看|查询|read|inspect|查看一下", RegexOption.IGNORE_CASE), false),
        Tag("修改", Regex("修改|改成|调整|更改|change|edit", RegexOption.IGNORE_CASE), false),
        Tag("删除", Regex("删除|卸载|清理|移除|delete|uninstall", RegexOption.IGNORE_CASE), false),
        Tag("截图", Regex("截图|截屏|screenshot", RegexOption.IGNORE_CASE), false),
    )

    fun describe(prompt: String): List<String> =
        tags.asSequence().filter { it.pattern.containsMatchIn(prompt) }.map { it.text }.take(8).toList()

    fun relevance(query: List<String>, memory: List<String>): Int {
        val overlap = query.toSet().intersect(memory.toSet())
        if (overlap.isEmpty()) return 0
        val specific = tags.asSequence().filter { it.specific }.map { it.text }.toSet()
        val matchedSpecific = overlap.count { it in specific }
        if (matchedSpecific == 0 && overlap.size < 2) return 0
        return matchedSpecific * 5 + (overlap.size - matchedSpecific) * 2 -
            (memory.size - overlap.size).coerceAtMost(5)
    }
}
