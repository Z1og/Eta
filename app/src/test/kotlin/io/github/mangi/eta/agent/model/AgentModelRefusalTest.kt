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
    fun detectsColloquialShortRefusals() {
        assertTrue(AgentModelRefusal.isRefusal("这个我不能做。"))
        assertTrue(AgentModelRefusal.isRefusal("这个我干不了，换别的吧。"))
        assertTrue(AgentModelRefusal.isRefusal("我帮不了你。"))
        assertTrue(AgentModelRefusal.isRefusal("这超出了我的能力范围。"))
        assertTrue(AgentModelRefusal.isRefusal("我恐怕不能继续这个任务。"))
        assertTrue(AgentModelRefusal.isRefusal("I can't do that."))
        assertTrue(AgentModelRefusal.isRefusal("I'm not able to help with this."))
        assertTrue(AgentModelRefusal.isRefusal("That's not something I can do."))
    }

    @Test
    fun engineeringPhrasingIsNotAMisjudgedRefusal() {
        assertFalse(AgentModelRefusal.isRefusal("无法找到目标进程，已重试 3 次，请确认 frida-server 已启动。"))
        assertFalse(AgentModelRefusal.isRefusal("好的，我先分析这个 so 的导出表，再定位 JNI 注册函数。"))
        assertFalse(AgentModelRefusal.isRefusal("frida-ps 输出如下：pid 4321 com.example.game"))
        assertFalse(AgentModelRefusal.isRefusal("mem_search 命中 12 处，起始地址 0x7f3a2b1c0000。"))
        assertFalse(AgentModelRefusal.isRefusal("这个偏移超出了我的预期，重新计算模块基址。"))
        assertFalse(AgentModelRefusal.isRefusal("权限不足，无法读取 /data/data，已回退到 su -c。"))
        assertFalse(AgentModelRefusal.isRefusal(""))
    }

    @Test
    fun continuationNoteRotatesAcrossSkips() {
        val first = AgentModelRefusal.continuationNote(1)
        val second = AgentModelRefusal.continuationNote(2)
        assertTrue("第 1 次跳过应有非空续跑指令", first.isNotBlank())
        assertTrue("第 2 次跳过应有非空续跑指令", second.isNotBlank())
        assertTrue("连续跳过应轮换中性续跑措辞", first != second)
        assertTrue("越界 attempt 回退到最后一档且非空", AgentModelRefusal.continuationNote(9).isNotBlank())
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
    fun singleRefusalSkipsSubtaskAndRunContinues() {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        )
        var calls = 0
        val provider = object : AgentProviderClient {
            override val id = "refusal-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                calls++
                return ProviderResponse(
                    JSONObject()
                        .put("role", "assistant")
                        .put(
                            "content",
                            if (calls == 1) "我不能协助这个请求。" else "已跳过该步骤，继续下一项：解出偏移 0x1A2B3C。",
                        )
                        .put("finish_reason", "stop"),
                )
            }
        }
        val result = AgentModelClient.complete(
            config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = listOf(AgentModelClient.ConversationMessage("user", "继续")),
            provider = provider,
        )
        assertEquals("单次拒绝后应续跑而不是终止", 2, calls)
        assertEquals("已跳过该步骤，继续下一项：解出偏移 0x1A2B3C。", result.content)
        assertFalse("拒绝内容不得进入 transcript", result.transcript.any { it.content.contains("我不能协助") })
    }

    @Test
    fun persistentRefusalEscalatesAfterSkipLimit() {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        )
        var calls = 0
        val provider = object : AgentProviderClient {
            override val id = "refusal-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                calls++
                return ProviderResponse(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", "我不能帮助你完成这个任务。")
                        .put("finish_reason", "stop"),
                )
            }
        }
        val failure = runCatching {
            AgentModelClient.complete(
                config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = listOf(AgentModelClient.ConversationMessage("user", "继续")),
                provider = provider,
            )
        }.exceptionOrNull()
        assertTrue("连续拒绝应升级为失败", failure is AgentModelExecutionException)
        assertEquals("MODEL_REFUSAL", (failure?.cause as? AgentModelFailure)?.code)
        assertEquals("先跳过一次，再升级失败", 3, calls)
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
