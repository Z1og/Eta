package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
) {
    private val contextWindow = config.knownContextWindow

    /** 目录/服务商声明值：只用于提前触发，不作为硬上限（见 ModelConfig.declaredContextWindow）。 */
    private val declaredWindow = config.declaredContextWindow?.takeIf { it > 0 }
    private var inputTokens: Int? = null
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun observeInputTokens(tokens: Int?) {
        inputTokens = tokens?.takeIf { it >= 0 }
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false) {
        val before = effectiveInputTokens()
        if (config.bindsAnthropicSignatures() &&
            AnthropicEphemeralState.hasPendingToolResponse(messages)
        ) {
            if (force) {
                throw AgentContextCompactor.signedAnthropicToolRoundFailure()
            }
            return
        }
        if (!force && (!config.autoCompactionEnabled || before == null || before < triggerThreshold())) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before))
        try {
            val candidate = AgentContextCompactor(config, provider, runController, roleplay = roleplay).compact(
                messages, systemCount, sensitiveIds(),
            )
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            inputTokens = null
            onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_COMPLETED, before))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final) throw failure
            // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
            committedSnapshot = createSnapshot(messages)
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    /**
     * 触发判定用的输入量：provider 回报优先。
     *
     * **没有硬上限**时（人工覆盖缺失，目录声明值也不可靠）退化为按消息体积估算，
     * 否则换到这类模型后自动压缩会被整体关闭、只能撞服务端上限。
     * 有硬上限但 provider 不回报 usage 时维持旧语义（不主动压缩），避免无依据地改写用户上下文。
     */
    private fun effectiveInputTokens(): Int? {
        inputTokens?.let { return it }
        if (contextWindow != null) return null
        return messages.toString().length / CHARS_PER_TOKEN_ESTIMATE
    }

    /**
     * 触发阈值。人工覆盖的窗口是硬上限（85%）；只有目录声明值时按更大的余量提前压缩——
     * 声明数字不可信，宁可早压也不撞服务端上限；两者都没有才退回保守假定窗口。
     */
    private fun triggerThreshold(): Int = when {
        contextWindow != null -> (contextWindow * TRIGGER_RATIO).toInt()
        declaredWindow != null -> (declaredWindow * ADVISORY_TRIGGER_RATIO).toInt()
        else -> (FALLBACK_CONTEXT_WINDOW * TRIGGER_RATIO).toInt()
    }

    private companion object {
        const val TRIGGER_RATIO = 0.85

        /** 目录声明值可信度低于人工覆盖，留足 40% 余量。 */
        const val ADVISORY_TRIGGER_RATIO = 0.6

        /** 窗口未知时的保守假定窗口：宁可提前压缩，也不要撞服务端硬上限。 */
        const val FALLBACK_CONTEXT_WINDOW = 32_000
        const val CHARS_PER_TOKEN_ESTIMATE = 4
    }
}
