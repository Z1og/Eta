package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentChatMemoryRecallTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun oldMemoryMarkdownIsRetrievableWithoutRetainNodes() {
        val existing = buildString {
            appendLine("# 核心记忆")
            appendLine("你叫小布。")
            appendLine("## 医考帮本地服务端")
            appendLine("该项目在本地服务器上用于协议测试和开发。")
            appendLine("## 论文科研")
            appendLine("项目研究气象因素与死亡率，并在论文中使用统计模型。")
        }
        val chunks = AgentChatMemoryCandidates.coreChunks(existing)
        assertTrue(chunks.size >= 3)
        val selected = AgentChatMemoryCandidates.select(
            "医考帮的本地服务端有哪些信息", existing,
            notes = emptyList(), memories = emptyList(),
        )
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.all { it.source == "core" })
        assertTrue(selected.any { it.title.contains("服务端") || it.text.contains("服务端") })
    }

    @Test fun longMarkdownIsSplitWithBoundedCandidates() {
        val markdown = "# 大型记忆\n" + "项目进展记录和常用工具列表".repeat(5500)
        val chunks = AgentChatMemoryCandidates.coreChunks(markdown)
        assertTrue(chunks.size > 50)
        assertTrue(chunks.all { it.text.length <= 1000 })
        val selected = AgentChatMemoryCandidates.select(
            "哪个工具列表更常用", markdown, emptyList(), emptyList(),
        )
        assertTrue(selected.size <= 24)
        assertTrue(selected.map { it.id }.distinct().size == selected.size)
    }

    @Test fun existingChatProviderDoesSemanticRankingWithoutEmbeddings() {
        val chunks = listOf(
            AgentChatMemoryCandidates.Entry("m1", "core", "记忆", "Prefer to answer in Chinese"),
            AgentChatMemoryCandidates.Entry("m2", "core", "其他", "The weather forecast last week"),
        )
        var called = false
        val provider = object : AgentProviderClient {
            override val id = "fake-test-chat-provider"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, false, false, false, false, false, false,
            )
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                called = true
                assertEquals(ProviderRequestPurpose.TRANSCRIPT_REFINE, request.purpose)
                assertEquals(0, request.tools.length())
                assertTrue(request.messages.getJSONObject(1).getString("content").contains("汉语"))
                val content = JSONObject().put("ranked", JSONArray().put(
                    JSONObject().put("id", "m1").put("score", 0.98),
                )).toString()
                return ProviderResponse(JSONObject()
                    .put("content", content).put("finish_reason", "stop"))
            }
        }
        val result = AgentChatMemoryReranker { provider }.rerank(
            "请用汉语回答", chunks,
            AgentModelClient.ModelConfig(
                baseUrl = "https://example.test/v1", apiKey = "unused",
                model = "existing-chat-model", systemPrompt = "test",
            ), AgentRunController(),
        )
        assertTrue(called)
        assertEquals("m1", result.selected.single().id)
        assertTrue(result.context().orEmpty().contains("Chinese"))
    }

    @Test fun fabricatedAndLowScoreIdsAreFiltered() {
        val known = listOf(AgentChatMemoryCandidates.Entry("known", "knowledge", "Note", "Valid content"))
        val result = AgentChatMemoryReranker.validate(
            JSONObject().put("ranked", JSONArray()
                .put(JSONObject().put("id", "nonexistent").put("score", 0.99))
                .put(JSONObject().put("id", "known").put("score", 0.10))
                .put(JSONObject().put("id", "known").put("score", 0.98))),
            known,
        )
        assertEquals(1, result.selected.size)
        assertEquals("known", result.selected.single().id)
    }

    @Test fun diagnosticStatePersistsWithoutConversationText() {
        val store = AgentMemoryRetrievalHealthStore(temp.newFolder())
        val original = AgentMemoryRetrievalReport(
            mode = "chat", candidateCount = 17, hitCount = 3,
            coreChunks = 72, retainedCount = 0,
            detail = "已检索核心记忆",
        )
        store.write(original)
        val loaded = store.read()!!
        assertEquals(17, loaded.candidateCount)
        assertTrue(loaded.shortSummary().contains("当前聊天模型"))
        assertFalse(loaded.shortSummary().contains("my private query"))
    }
}
