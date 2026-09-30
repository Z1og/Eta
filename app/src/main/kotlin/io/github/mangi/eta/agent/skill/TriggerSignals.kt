package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.agent.model.AgentModelClient

/**
 * 触发信号拼装——把「本轮消息 + 最近若干轮对话尾」合并为一段匹配文本。
 *
 * 目的：让「接着搞」「换个 id 试试」这类没有关键词的续问，
 * 也能借助上一轮里出现过的 `.so` / `frida` / 域名等信号命中对应技能。
 */
internal object SkillTriggerSignals {

    private const val MAX_HISTORY_MESSAGES = 6
    private const val MAX_PER_MESSAGE_CHARS = 1_500

    fun buildText(
        prompt: String,
        history: List<AgentModelClient.ConversationMessage>,
    ): String {
        if (history.isEmpty()) return prompt
        return buildString {
            append(prompt)
            history.takeLast(MAX_HISTORY_MESSAGES).forEach { message ->
                val content = message.content
                if (content.isNotBlank()) {
                    append('\n')
                    append(content.take(MAX_PER_MESSAGE_CHARS))
                }
            }
        }
    }
}

/**
 * 结构化触发兜底路由——消息里出现文件扩展名或常见逆向/安全工具名时，
 * 直接路由到对应内置技能，无需该技能在 frontmatter 里声明 triggers。
 *
 * 用于「用户只丢了一个 `xx.apk` / `xx.so` 路径，或只说用 `frida`」这类
 * 字面关键词命中率为零、但意图明确的逆向/渗透场景。
 * 只路由到当前已安装且启用的技能，命中不到就不产出。
 */
internal object ReverseSignalRouter {

    /** 扩展名 → 技能 id（按优先级排列）。 */
    private val EXT_HINTS: Map<String, List<String>> = mapOf(
        "apk" to listOf("apk-reverse", "mobile-reverse"),
        "dex" to listOf("apk-reverse"),
        "so" to listOf("binary-analysis", "apk-reverse"),
        "elf" to listOf("binary-analysis"),
        "exe" to listOf("binary-analysis", "dotnet-reverse"),
        "dll" to listOf("dotnet-reverse", "binary-analysis"),
        "sys" to listOf("windows-kernel-security"),
        "ipa" to listOf("mobile-reverse"),
        "img" to listOf("firmware-pentest", "digital-forensics"),
        "pcap" to listOf("protocol-reversing", "digital-forensics"),
        "har" to listOf("reverse-engineering-api"),
        "mq5" to listOf("reverse-engineering"),
        "ex5" to listOf("reverse-engineering"),
    )

    /** 工具名/手法 token → 技能 id（按优先级排列）。 */
    private val TOOL_HINTS: Map<String, List<String>> = mapOf(
        "frida" to listOf("dynamic-instrumentation", "mobile-reverse"),
        "ghidra" to listOf("ghidra-reverse"),
        "jadx" to listOf("apk-reverse"),
        "apktool" to listOf("apk-reverse"),
        "radare2" to listOf("radare2"),
        "objdump" to listOf("binary-analysis"),
        "il2cpp" to listOf("il2cpp-dump", "game-cheat"),
        "objection" to listOf("capture-mitm", "dynamic-instrumentation"),
        "mitmproxy" to listOf("capture-mitm"),
        "tcpdump" to listOf("capture-mitm"),
        "il2cppdumper" to listOf("il2cpp-dump"),
        "ollvm" to listOf("unpack-reverse", "binary-analysis"),
        "upx" to listOf("unpack-reverse"),
        "nmap" to listOf("network-pentest"),
        "sqlmap" to listOf("network-pentest", "api-security"),
        "burpsuite" to listOf("api-security"),
        "ffuf" to listOf("network-pentest"),
        "hydra" to listOf("network-pentest"),
        "coresight" to listOf("hw-trace"),
        "cs_etm" to listOf("hw-trace"),
        "trbe" to listOf("hw-trace"),
    )

    fun route(
        text: String,
        skills: List<SkillIndexEntry>,
        maxMatches: Int,
    ): List<SkillIndexEntry> {
        if (text.isBlank() || skills.isEmpty() || maxMatches <= 0) return emptyList()
        val lower = text.lowercase()
        val extensions = SkillTriggerMatcher.detectExtensions(text)
        val byId = skills.associateBy { it.id }
        val ordered = LinkedHashSet<SkillIndexEntry>()

        extensions.forEach { ext ->
            EXT_HINTS[ext]?.forEach { id -> byId[id]?.let(ordered::add) }
        }
        TOOL_HINTS.forEach { (token, ids) ->
            if (lower.contains(token)) {
                ids.forEach { id -> byId[id]?.let(ordered::add) }
            }
        }
        return ordered.take(maxMatches)
    }
}
