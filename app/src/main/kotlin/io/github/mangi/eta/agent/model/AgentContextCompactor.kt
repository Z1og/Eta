package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/** 只在完整工具交换之间生成候选摘要；全部验证通过后由会话一次性提交。 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
) {
    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
        force: Boolean = false,
    ): JSONArray {
        controller.throwIfCancelled()
        if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
            throw signedAnthropicToolRoundFailure()
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        // 最新用户请求及其后尚在进行的工具链必须可以继续；长任务允许压缩该请求之后的已完成批次。
        val safeEnds = (1..history.size).filter { canSplit(history, it) }
        val recentLimit = config.contextWindow?.takeIf { it > 0 }?.let { (it * AgentContextBudget.RECENT_RATIO).toInt() }
        val initialEnd = if (force) {
            safeEnds.lastOrNull()
        } else {
            safeEnds.lastOrNull { it <= history.size - AgentContextBudget.RECENT_MESSAGES }
                ?: safeEnds.firstOrNull()
        }
        if (initialEnd == null || initialEnd <= 0) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
        var end: Int = initialEnd
        if (recentLimit != null) {
            while (AgentContextBudget.rawEstimate(JSONArray(history.drop(end))) > recentLimit) {
                end = safeEnds.firstOrNull { it > end } ?: break
            }
        }
        val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
        val source = JSONArray(history.take(end).filterNot { it === protectedUser })
        val durable = AgentConversationCodec.transcript(source, 0, sensitiveIds)
        if (durable.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可压缩的历史内容。")
        val safe = durable.map { message ->
            val content = AgentConversationCodec.toJsonObject(message).opt("content")
            val text = if (content is JSONArray) buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") in setOf("text", "input_text")) append(part.optString("text"))
                    else append("[图片观察已省略]")
                }
            } else message.content
            message.copy(content = text, contentJson = "", reasoningContent = "")
        }
        val summary = AgentContextSummarizer(config, provider, controller, roleplay)
            .summarize(completeGroups(safe))
        val covered = safe.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]\n$summary",
            contextSummary = true,
            compactedUserTurns = covered,
            summaryThroughUserTurn = covered + if (protectedUser != null) 1 else 0,
        )))
        protectedUser?.let(result::put)
        history.drop(end).forEach { message ->
            // 新摘要改变了前缀；旧 opaque items 不再代表同一份 Provider 上下文。
            val withoutResponsesItems = if (ResponsesEphemeralState.outputItems(message) != null) {
                AgentConversationCodec.toJsonObject(AgentConversationCodec.fromJsonObject(message))
            } else message
            result.put(AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems))
        }
        if (AgentContextBudget.rawEstimate(result) >= AgentContextBudget.rawEstimate(messages)) {
            throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
        }
        return result
    }

    companion object {
        fun signedAnthropicToolRoundFailure() = failure(
            "ANTHROPIC_THINKING_CONTEXT_LOCKED",
            "当前 Anthropic 工具回合的思考签名绑定原始上下文，提交工具结果前无法压缩上下文。",
        )

        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end <= 0 || end > history.size) return false
            val last = history[end - 1]
            if (last.optString("role") == "user" || history.getOrNull(end)?.optString("role") == "tool") return false
            val open = linkedSetOf<String>()
            history.take(end).forEach { message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            }
            return open.isEmpty()
        }

        private fun completeGroups(messages: List<AgentModelClient.ConversationMessage>): List<List<AgentModelClient.ConversationMessage>> {
            val json = messages.map(AgentConversationCodec::toJsonObject)
            val groups = mutableListOf<List<AgentModelClient.ConversationMessage>>()
            var start = 0
            for (end in 1..json.size) {
                if (canSplit(json, end)) {
                    groups += messages.subList(start, end)
                    start = end
                }
            }
            if (start < messages.size) groups += messages.subList(start, messages.size)
            return groups
        }

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
