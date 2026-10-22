package io.github.mangi.eta.agent.model

import org.json.JSONObject
import io.github.mangi.eta.data.model.ProviderSourceTypes
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    val providerError: String? = null,
) : IllegalStateException(message, cause) {
    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
        )

        fun http(
            status: Int,
            body: String,
            providerSourceType: String? = null,
        ): AgentModelFailure {
            if (providerSourceType == ProviderSourceTypes.OPENCODE && status == 503 &&
                (body.contains("free_tier_lane_paused", ignoreCase = true) ||
                    body.contains("anonymous free tier is paused", ignoreCase = true))
            ) {
                return AgentModelFailure(
                    code = "OPENCODE_FREE_LANE_PAUSED",
                    retryable = false,
                    message = "OpenCode 匿名免费线路暂时暂停（HTTP 503）；网关已收到上游冷却通知，请稍后再试。",
                )
            }
            if (providerSourceType == ProviderSourceTypes.OPENCODE && status == 429 &&
                !body.contains("Free usage limit reached", ignoreCase = true)
            ) {
                return AgentModelFailure(
                    code = "OPENCODE_RATE_LIMITED",
                    retryable = false,
                    message = "OpenCode 上游请求限流（HTTP 429），短时间重复重试通常无法恢复，请稍后再试。",
                )
            }
            if (providerSourceType == ProviderSourceTypes.OPENCODE && status == 429 &&
                body.contains("Free usage limit reached", ignoreCase = true)
            ) {
                return AgentModelFailure(
                    code = "OPENCODE_FREE_QUOTA_EXHAUSTED",
                    retryable = false,
                    message = "OpenCode 匿名免费额度已用尽（HTTP 429）；本地网关可以工作，但上游暂不接受更多免费请求。",
                )
            }
            if (providerSourceType == ProviderSourceTypes.OPENCODE && status == 403 &&
                (body.contains("FreeTierError", ignoreCase = true) ||
                    body.contains("free_tier_restricted", ignoreCase = true))
            ) {
                return AgentModelFailure(
                    code = "OPENCODE_FREE_TIER_RESTRICTED",
                    retryable = false,
                    message = "OpenCode 已返回免费模型目录，但上游拒绝当前推理请求（HTTP 403）。" +
                        "请检查 Zen/Go 上游 Key、账户的模型权限；匿名调用不一定被允许。",
                )
            }
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            val providerMessage = error?.optString("message")?.take(1_000)
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false,
                "模型上下文超过容量限制。可先执行「压缩上下文」再重试；若该模型未填写上下文窗口，请在模型设置中补上，以便自动压缩生效。",
                providerError = providerMessage,
            )
            val permanent = isPermanent(error, body)
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
                else when (status) {
                    400 -> "模型请求参数无效（HTTP 400），请检查模型配置。"
                    401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                    403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。"
                    else -> "模型接口返回 HTTP $status"
                },
                providerError = providerMessage,
            )
        }

        /** The *embedded* Go gateway already tried its available upstream routes.
         * Repeating the same entire agent request four times only amplifies latency
         * and can exhaust the anonymous free lane. External provider 502s still
         * use the standard retry policy from http().
         */
        fun embeddedOpenCode502(body: String, safeDiagnostic: String, proxyDescription: String): AgentModelFailure {
            val protocolError = body.contains("unsupported upstream response", ignoreCase = true)
            val readError = body.contains("failed to read upstream response", ignoreCase = true)
            val explanation = when {
                protocolError -> "上游响应无法被网关解析，可能是协议或响应格式不匹配"
                readError -> "网关读取上游响应失败，连接可能中途断开"
                else -> safeDiagnostic.take(160)
            }
            return AgentModelFailure(
                code = "OPENCODE_EMBEDDED_UPSTREAM_502",
                retryable = false,
                message = "内置 opencode2api 已启动，但上游推理失败（HTTP 502）。" +
                    "$explanation。当前连接：${proxyDescription.take(70)}。" +
                    "请检查手机代理/VPN 是否覆盖 Eta，或在 OpenCode 配置中填写可用的 HTTP/SOCKS5 代理。" +
                    "已停止重复向同一上游发送请求。",
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false,
                "模型上下文超过容量限制。可先执行「压缩上下文」再重试；若该模型未填写上下文窗口，请在模型设置中补上，以便自动压缩生效。",
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds")
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
