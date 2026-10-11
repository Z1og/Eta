package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelCapabilityProbeTest {

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = null,
    )

    private fun client(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "probe-fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse = block(request)
    }

    private fun ok() = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", "1").put("finish_reason", "stop"),
    )

    private suspend fun probeWith(provider: AgentProviderClient) =
        AgentModelCapabilityProbe { provider }.probe(config)

    @Test
    fun parsesContextLimitFromRealProviderMessages() {
        assertEquals(
            128_000,
            AgentModelCapabilityProbe.parseContextLimit(
                "This model's maximum context length is 128000 tokens, however you requested 130012 tokens."
            ),
        )
        assertEquals(
            200_000,
            AgentModelCapabilityProbe.parseContextLimit("prompt is too long: 234358 tokens > 200000 maximum"),
        )
        assertEquals(
            8_192,
            AgentModelCapabilityProbe.parseContextLimit("Input token count exceeds the maximum: 8192"),
        )
        assertEquals(
            32_768,
            AgentModelCapabilityProbe.parseContextLimit("reduce the length of the messages (41000 > 32768)"),
        )
        assertNull(AgentModelCapabilityProbe.parseContextLimit("模型连接中断，请检查网络"))
    }

    @Test
    fun unsupportedParameterRequiresClientErrorAndParameterWording() {
        val unsupported = AgentModelFailure.http(400, """{"error":{"message":"Unsupported parameter: 'reasoning_effort'"}}""")
        assertEquals("HTTP_400", unsupported.code)
        assertTrue(AgentModelCapabilityProbe.looksUnsupportedParameter(unsupported))
        val quota = AgentModelFailure.http(400, """{"error":{"message":"invalid api key"}}""")
        assertFalse(AgentModelCapabilityProbe.looksUnsupportedParameter(quota))
        val serverError = AgentModelFailure.http(500, """{"error":{"message":"unsupported operation"}}""")
        assertFalse(AgentModelCapabilityProbe.looksUnsupportedParameter(serverError))
    }

    @Test
    fun rejectedEffortParameterMeansReasoningUnsupported() = runBlocking {
        var calls = 0
        val outcome = probeWith(client { request ->
            calls++
            val effort = request.config.effectiveReasoningEffort
            if (calls == 1 || effort == io.github.mangi.eta.data.model.ReasoningEffort.OFF) {
                ok()
            } else {
                throw AgentModelFailure.http(400, """{"error":{"message":"Unsupported parameter: 'reasoning_effort'"}}""")
            }
        })
        assertTrue(outcome.reachable)
        assertEquals(false, outcome.reasoning)
        assertEquals(2, calls)
    }

    @Test
    fun acceptedEffortParameterMeansReasoningSupported() = runBlocking {
        val outcome = probeWith(client { ok() })
        assertTrue(outcome.reachable)
        assertEquals(true, outcome.reasoning)
    }

    @Test
    fun unrelatedFailureKeepsReasoningUnknown() = runBlocking {
        var calls = 0
        val outcome = probeWith(client {
            calls++
            if (calls == 1) {
                ok()
            } else {
                throw AgentModelFailure.http(429, """{"error":{"message":"rate limited"}}""")
            }
        })
        assertTrue(outcome.reachable)
        assertNull(outcome.reasoning)
    }

    @Test
    fun unreachableBaselineReportsContextLimitFromError() = runBlocking {
        val outcome = probeWith(client {
            throw AgentModelFailure.http(400, """{"error":{"code":"context_length_exceeded","message":"This model's maximum context length is 128000 tokens"}}""")
        })
        assertFalse(outcome.reachable)
        assertNull(outcome.reasoning)
        assertEquals(128_000, outcome.contextLimitFromError)
    }

    @Test
    fun probeRequestIsMinimalAndToolless() = runBlocking {
        var seen: ProviderRequest? = null
        probeWith(client { request ->
            seen = request
            ok()
        })
        val request = checkNotNull(seen)
        assertEquals(0, request.tools.length())
        assertEquals(2, request.messages.length())
        assertEquals("probe-fixture", request.config.model)
        assertTrue(request.messages.toString().contains("回 1"))
    }
}
