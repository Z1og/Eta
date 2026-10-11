package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型能力探测：用极小请求判定「这个端点到底支持什么」。
 *
 * 存在的理由：上下文窗口与思考能力此前只来自外部元数据（官方目录 / 社区目录 / OpenRouter）
 * 或用户手填，模型名对不上的中转与自定义端点常常两者皆空——于是换模型后自动压缩失效、
 * 思考参数报错。探测只认**确定结论**：明确拒绝的参数才算不支持；被静默忽略的参数
 * 只能记为「未知」，绝不据此推断（很多端点会直接丢弃未知参数）。
 */
internal class AgentModelCapabilityProbe(
    private val providerFor: (AgentModelClient.ModelConfig) -> AgentProviderClient = ProviderClientFactory::getClient,
) {
    data class Outcome(
        /** 基线请求（无任何可选参数）是否成功——模型名/端点/密钥是否真的可用。 */
        val reachable: Boolean,
        val baselineLatencyMillis: Long,
        /** true=接受思考参数；false=明确拒绝；null=未知（静默忽略或与思考无关的失败）。 */
        val reasoning: Boolean?,
        /** 报错里解析出的真实上下文上限（服务商常在超限提示中写出数字）。 */
        val contextLimitFromError: Int?,
        val detail: String,
    )

    suspend fun probe(config: AgentModelClient.ModelConfig): Outcome {
        val baseline = attempt(config, withReasoning = false)
        baseline.failure?.let { failure ->
            return Outcome(
                reachable = false,
                baselineLatencyMillis = baseline.latencyMillis,
                reasoning = null,
                contextLimitFromError = parseContextLimit(failure.message.orEmpty()),
                detail = failure.message.orEmpty(),
            )
        }
        val effort = attempt(config, withReasoning = true)
        val reasoning = when {
            effort.failure == null -> true
            looksUnsupportedParameter(effort.failure) -> false
            else -> null
        }
        return Outcome(
            reachable = true,
            baselineLatencyMillis = baseline.latencyMillis,
            reasoning = reasoning,
            contextLimitFromError = null,
            detail = when (reasoning) {
                true -> "端点接受了思考等级参数"
                false -> "端点拒绝了思考等级参数：${effort.failure?.message.orEmpty().take(160)}"
                else -> "思考参数未被拒绝，但无法确认是否生效：${effort.failure?.message.orEmpty().take(160)}"
            },
        )
    }

    private data class Attempt(
        val failure: AgentModelFailure?,
        val latencyMillis: Long,
    )

    private suspend fun attempt(
        config: AgentModelClient.ModelConfig,
        withReasoning: Boolean,
    ): Attempt = coroutineScope {
        val controller = AgentRunController()
        val probeConfig = if (withReasoning) {
            config.copy(thinkingEnabled = true, reasoningEffort = ReasoningEffort.MINIMAL)
        } else {
            config.copy(thinkingEnabled = false, reasoningEffort = null, reasoningCapabilities = null)
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", PROBE_SYSTEM))
            .put(AgentConversationCodec.userTextMessage(PROBE_USER))
        val started = System.currentTimeMillis()
        // Provider 调用阻塞在 HTTP 上、不响应协程取消：等待方超时/取消时由这里取消底层调用。
        val work = async(Dispatchers.IO) {
            providerFor(probeConfig).complete(
                ProviderRequest(
                    probeConfig,
                    messages,
                    JSONArray(),
                    sessionId = "eta-capability-probe",
                    purpose = ProviderRequestPurpose.TRANSCRIPT_REFINE,
                ),
                controller,
            ) { }
        }
        try {
            withTimeout(TIMEOUT_MS) { work.await() }
            Attempt(failure = null, latencyMillis = System.currentTimeMillis() - started)
        } catch (failure: Exception) {
            Attempt(
                failure = AgentModelFailure.transport(failure)
                    ?: AgentModelFailure("PROBE_FAILED", false, failure.message ?: failure::class.java.simpleName),
                latencyMillis = System.currentTimeMillis() - started,
            )
        }
    }

    companion object {
        private const val TIMEOUT_MS = 20_000L
        private const val PROBE_SYSTEM = "你是连通性探测，按要求只回一个字符。"
        private const val PROBE_USER = "回 1"

        private val UPPER_BOUND = 1_000 to 20_000_000

        private val LIMIT_PATTERNS = listOf(
            Regex("""(?i)prompt is too long:\s*\d+\s*tokens?\s*>\s*(\d{4,9})"""),
            Regex("""(?i)(\d{4,9})\s*tokens?\s*>\s*(\d{4,9})"""),
            Regex("""(?i)maximum context length is\s*(\d{4,9})"""),
            Regex("""(?i)context length[^\d]{0,24}(\d{4,9})"""),
            Regex("""(?i)input token count exceeds[^\d]{0,20}(\d{4,9})"""),
            Regex("""(?i)max(?:imum)?\s*tokens?[^\d]{0,12}(\d{4,9})"""),
        )

        private val UNSUPPORTED_PATTERNS = listOf(
            Regex("""(?i)unsupported"""),
            Regex("""(?i)not\s+supported"""),
            Regex("""(?i)unrecognized|unknown\s+(parameter|field|argument|parameter)"""),
            Regex("""(?i)unexpected\s+(parameter|keyword|argument)"""),
            Regex("""(?i)extra\s+fields?\s+not\s+permitted"""),
            Regex("""(?i)invalid\s+(parameter|request|request body)"""),
        )

        /** 从超限报错里提取服务商写明的上下文上限；解析不出返回 null。 */
        fun parseContextLimit(message: String): Int? {
            for (pattern in LIMIT_PATTERNS) {
                val matched = pattern.find(message) ?: continue
                val candidate = matched.groupValues.drop(1).firstNotNullOfOrNull { value ->
                    value.toIntOrNull()?.takeIf { it in UPPER_BOUND.first..UPPER_BOUND.second }
                } ?: continue
                return candidate
            }
            return null
        }

        /** 只有「参数被拒」才算不支持思考等级；端点静默忽略时探测无法判定，交由调用方记为未知。 */
        fun looksUnsupportedParameter(failure: AgentModelFailure): Boolean {
            if (failure.code != "HTTP_400" && failure.code != "HTTP_404" && failure.code != "HTTP_422") return false
            return UNSUPPORTED_PATTERNS.any { it.containsMatchIn(failure.message.orEmpty()) }
        }
    }
}
