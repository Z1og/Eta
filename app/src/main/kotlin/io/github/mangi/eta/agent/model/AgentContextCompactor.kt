package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderTypes
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
    ): JSONArray {
        controller.throwIfCancelled()
        if (config.bindsAnthropicSignatures() &&
            AnthropicEphemeralState.hasPendingToolResponse(messages)
        ) {
            throw signedAnthropicToolRoundFailure()
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        // 最新用户请求及其后尚在进行的工具链必须可以继续；长任务允许压缩该请求之后的已完成批次。
        val end = lastSafeSplit(history)
            ?: throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
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
            .summarize(safe)
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
        // 同样逐条累加：两侧口径一致即可判定"是否变小"，不必各物化一次整段 JSON。
        if (jsonChars(result) >= jsonChars(messages)) {
            throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
        }
        return result
    }

    private fun jsonChars(source: JSONArray): Int {
        var chars = 0
        for (index in 0 until source.length()) {
            val message = source.optJSONObject(index) ?: continue
            chars += message.optString("content").length +
                message.optString("reasoning_content").length +
                message.optString("tool_calls").length +
                64
        }
        return chars
    }

    companion object {
        fun signedAnthropicToolRoundFailure() = failure(
            "ANTHROPIC_THINKING_CONTEXT_LOCKED",
            "当前 Anthropic 工具回合的思考签名绑定原始上下文，提交工具结果前无法压缩上下文。",
        )

        /**
         * 找最靠后的可切分点。
         *
         * 旧实现是 `(size downTo 1).firstOrNull { canSplit(...) }`，而 canSplit 每判断一次
         * 都要把前缀重扫一遍并逐条解析 tool_calls——最坏 O(n²) 次 JSON 解析。
         * 上下文一大（正是自动压缩会被触发的时候），这一步本身就足以让"发消息"卡住。
         *
         * 现在正向扫一趟：记录每个前缀的"未闭合 tool_call 集合是否为空"，再倒着取第一个可切点。
         * tool 消息只会关闭调用，所以只需要维护一个集合，整体 O(n)。
         */
        fun lastSafeSplit(history: List<JSONObject>): Int? {
            val closedAtPrefix = BooleanArray(history.size + 1)
            val open = linkedSetOf<String>()
            closedAtPrefix[0] = true
            for (index in history.indices) {
                val message = history[index]
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
                closedAtPrefix[index + 1] = open.isEmpty()
            }
            for (end in history.size downTo 1) {
                if (history[end - 1].optString("role") == "user") continue
                if (history.getOrNull(end)?.optString("role") == "tool") continue
                if (closedAtPrefix[end]) return end
            }
            return null
        }

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

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}

/**
 * 思考签名只绑定**原生 Anthropic 请求**：换到其他 provider 后这些 content block 不再随请求发送，
 * 因此不应再以"签名锁"为由跳过压缩（那会让换模型后的自动压缩永久静默失效）。
 */
internal fun AgentModelClient.ModelConfig.bindsAnthropicSignatures(): Boolean =
    providerType == ProviderTypes.ANTHROPIC
