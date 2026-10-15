package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelRefusalTest {

    @Test
    fun detectsChineseRefusalOpeners() {
        assertTrue(AgentModelRefusal.isRefusal("我不能帮助你破解这个应用。"))
        assertTrue(AgentModelRefusal.isRefusal("很抱歉，我无法协助完成该请求。"))
        assertTrue(AgentModelRefusal.isRefusal("作为AI语言模型，我不能提供此类帮助。"))
        assertTrue(AgentModelRefusal.isRefusal("我做不到，这违反我的使用政策。"))
        assertTrue(AgentModelRefusal.isRefusal("出于安全合规的考虑，我无法配合。"))
    }

    @Test
    fun detectsEnglishRefusalOpeners() {
        assertTrue(AgentModelRefusal.isRefusal("I can't help with that request."))
        assertTrue(AgentModelRefusal.isRefusal("I'm sorry, but I cannot assist with bypassing security."))
        assertTrue(AgentModelRefusal.isRefusal("As an AI, I won't perform this task."))
        assertTrue(AgentModelRefusal.isRefusal("I must decline this request."))
    }

    @Test
    fun engineeringPhrasingIsNotAMisjudgedRefusal() {
        assertFalse(AgentModelRefusal.isRefusal("无法找到目标进程，已重试 3 次，请确认 frida-server 已启动。"))
        assertFalse(AgentModelRefusal.isRefusal("好的，我先分析这个 so 的导出表，再定位 JNI 注册函数。"))
        assertFalse(AgentModelRefusal.isRefusal("frida-ps 输出如下：pid 4321 com.example.game"))
        assertFalse(AgentModelRefusal.isRefusal("mem_search 命中 12 处，起始地址 0x7f3a2b1c0000。"))
        assertFalse(AgentModelRefusal.isRefusal(""))
    }

    @Test
    fun refusalFailureCarriesCodeAndExcerpt() {
        val failure = AgentModelRefusal.failure("我不能帮助你破解这个应用。")
        assertEquals("MODEL_REFUSAL", failure.code)
        assertFalse(failure.retryable)
        assertTrue(failure.message.orEmpty().contains("不能帮助你"))
    }

    @Test
    fun refusalResponseFailsTheRunWithoutEnteringHistory() {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        )
        val provider = object : AgentProviderClient {
            override val id = "refusal-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse = ProviderResponse(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "我不能帮助你破解这个应用。")
                    .put("finish_reason", "stop"),
            )
        }
        val failure = runCatching {
            AgentModelClient.complete(
                config, "帮我分析这个目标", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = listOf(AgentModelClient.ConversationMessage("user", "帮我分析这个目标")),
                provider = provider,
            )
        }.exceptionOrNull()
        assertTrue("应抛出执行异常", failure is AgentModelExecutionException)
        assertEquals("MODEL_REFUSAL", (failure?.cause as? AgentModelFailure)?.code)
    }

    @Test
    fun normalAssistantAnswerStillPassesThrough() {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        )
        val provider = object : AgentProviderClient {
            override val id = "refusal-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse = ProviderResponse(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "无法找到目标进程，已重试 3 次。")
                    .put("finish_reason", "stop"),
            )
        }
        val result = AgentModelClient.complete(
            config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = listOf(AgentModelClient.ConversationMessage("user", "继续")),
            provider = provider,
        )
        assertEquals("无法找到目标进程，已重试 3 次。", result.content)
        assertTrue(result.transcript.isNotEmpty())
    }
}
