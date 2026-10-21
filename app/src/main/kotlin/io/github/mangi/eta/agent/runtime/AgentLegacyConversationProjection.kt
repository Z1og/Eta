package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅为不认识完整载荷字段的旧客户端提供内联预览；禁止用于存储或新模型请求。 */
internal object AgentLegacyConversationProjection {
    const val DIRECT_CHARS = 96_000
    const val DRAIN_CHARS = 16_000
    private val json = Json { encodeDefaults = false }
    private const val COMPACTION_NOTICE =
        "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]"

    /**
     * 生成有界预览。
     *
     * 旧实现先 `encodeTranscriptForStorage(全量历史)` 再判断是否超长、然后从头丢——
     * 历史一大，光"判断是否超长"这一步就物化出整段 JSON，直接 OOM
     * （表现为「Agent Runtime 请求发送失败（OutOfMemoryError）」）。
     * 现在先按字符估算从尾部往回收集，只有落进预算的候选才会被真正编码/净化。
     */
    fun encode(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        if (messages.isEmpty()) return "[]"

        // 估算不超预算才整段编码：完整保留且不加提示。
        if (messages.sumOf(::estimateChars) <= maxChars) {
            val encoded = json.encodeToString(sanitize(messages))
            if (encoded.length <= maxChars) return encoded
        }

        val notice = AgentModelClient.ConversationMessage(
            role = "system",
            content = COMPACTION_NOTICE,
        )
        var start = messages.size
        var used = 0
        while (start > 0) {
            val cost = estimateChars(messages[start - 1])
            if (used + cost > maxChars && start < messages.size) break
            used += cost
            start--
        }
        // 起点若落在 tool 结果上会变成孤儿结果，跳到下一个非 tool。
        while (start < messages.size && messages[start].role == "tool") start++
        var selected = sanitize(messages.subList(start, messages.size).toList())
        if (selected.isEmpty()) {
            val last = sanitize(listOfNotNull(messages.lastOrNull { it.role != "tool" })).firstOrNull()
                ?: return "[]"
            selected = listOf(last)
        }

        var encoded = json.encodeToString(listOf(notice) + selected)
        while (encoded.length > maxChars && selected.size > 1) {
            selected = dropLeadingBatch(selected)
            encoded = json.encodeToString(listOf(notice) + selected)
        }
        if (encoded.length <= maxChars) return encoded

        val last = selected.lastOrNull { it.role != "tool" } ?: return "[]"
        val compacted = last.copy(
            content = last.content.take(maxChars / 4),
            contentJson = "",
            reasoningContent = last.reasoningContent.take(maxChars / 4),
            toolCallsJson = "",
        )
        return json.encodeToString(listOf(notice, compacted))
            .takeIf { it.length <= maxChars }
            ?: json.encodeToString(listOf(notice)).takeIf { it.length <= maxChars }
            ?: "[]"
    }

    /** 丢掉最早的一批；顺带清掉紧随其后的孤儿 tool 结果。 */
    private fun dropLeadingBatch(
        messages: List<AgentModelClient.ConversationMessage>,
    ): List<AgentModelClient.ConversationMessage> {
        val rest = messages.drop(1).dropWhile { it.role == "tool" }
        return if (rest.isEmpty()) messages.takeLast(1) else rest
    }

    /** 只净化候选子集：净化本身就是一趟编解码，别让它碰到没入选的历史。 */
    private fun sanitize(messages: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> =
        AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(messages))

    private fun estimateChars(message: AgentModelClient.ConversationMessage): Int =
        message.content.length +
            message.contentJson.length +
            message.reasoningContent.length +
            message.toolCallsJson.length +
            message.toolCallId.length +
            32
}
