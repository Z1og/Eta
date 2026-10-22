package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.memory.AgentRetainExtractor
import io.github.mangi.eta.agent.memory.AgentRetainPrivacy
import io.github.mangi.eta.agent.memory.AgentRetainEligibility
import io.github.mangi.eta.agent.memory.AgentRetainedRecall
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRetainedMemoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun candidate(
        text: String = "User prefers Chinese replies in the Eta agent",
        subject: String = "user.language",
        evidence: String = "请用中文回答",
        supersedes: String? = null,
        links: List<RetainedRelation> = emptyList(),
    ) = RetainCandidate(
        text, "semantic", "preference", "global", subject, evidence,
        listOf("Eta"), links, supersedes, 0.92,
    )

    private fun runHash(text: String): String = AgentKnowledgeStore.sha256(text)

    @Test fun deduplicatesAcrossRunsAndDoesNotReplaySameRun() {
        val directory = temp.newFolder()
        val store = RetainedMemoryStore(directory)
        val once = store.retain(listOf(candidate()), runHash("run-1"), now = 1000)
        assertEquals(1, once.size)
        assertEquals(0, store.retain(listOf(candidate()), runHash("run-1"), now = 1001).size)
        assertEquals(1, store.retain(listOf(candidate()), runHash("run-2"), now = 1002).size)
        val fresh = RetainedMemoryStore(directory).snapshot()
        assertEquals(1, fresh.items.size)
        assertEquals(2, fresh.items.single().occurrences)
        assertEquals(2, fresh.items.single().sourceRuns.size)
    }

    @Test fun correctionInvalidatesOldFactWithoutRemovingHistory() {
        val store = RetainedMemoryStore(temp.newFolder())
        val old = store.retain(listOf(candidate()), runHash("old"), now = 1000).single()
        val replacement = candidate(
            text = "User now prefers English replies in the Eta agent",
            evidence = "现在改成英语回答",
            supersedes = old.id,
        )
        store.retain(listOf(replacement), runHash("new"), now = 2000)
        val memories = store.snapshot().items
        assertEquals(2, memories.size)
        assertTrue(memories.single { it.id == old.id }.invalidatedAt > 0L)
        assertTrue(memories.single { it.id != old.id }.active)
        assertEquals(1, store.count())
    }

    @Test fun deleteAndClearSurviveReopenedStoreAndRemoteMerge() {
        val root = temp.newFolder()
        val store = RetainedMemoryStore(root)
        val record = store.retain(listOf(candidate()), runHash("once"), now = 1000).single()
        val staleRemote = store.snapshot()
        assertTrue(store.delete(record.id, now = 2000))
        assertEquals(0, RetainedMemoryStore(root).count())
        val deleted = RetainedMemoryMerge.merge(store.snapshot(), staleRemote)
        assertFalse(deleted.items.single().active)
        store.clear(now = 3000)
        val cleared = RetainedMemoryMerge.merge(store.snapshot(), staleRemote)
        assertEquals(0, cleared.items.size)
        assertEquals(3000L, cleared.clearedAt)
    }

    @Test fun newMemoryAfterClearCannotBeDiscardedBySameMillisecondCutoff() {
        val store = RetainedMemoryStore(temp.newFolder())
        store.clear(now = 3000L)
        val retained = store.retain(listOf(candidate()), runHash("first-after-clear"), now = 3000L)
        assertEquals(1, retained.size)
        assertTrue(retained.single().updatedAt > 3000L)
        assertEquals(1, store.snapshot().items.size)
    }

    @Test fun relationProposalsOnlyLinkExistingActiveSameScopeNodes() {
        val store = RetainedMemoryStore(temp.newFolder())
        val first = store.retain(listOf(candidate()), runHash("one"), now = 1000).single()
        val second = store.retain(
            listOf(candidate(
                text = "The user wants the Eta Android app to remember project plans",
                subject = "eta.project",
                links = listOf(
                    RetainedRelation(first.id, "SAME_TOPIC", 0.91),
                    RetainedRelation("43e100f2-f455-43c4-88e8-f5ac99b8e431", "SAME_TOPIC", 0.93),
                ),
            )),
            runHash("two"), now = 2000,
        ).single()
        assertEquals(listOf(first.id), second.links.map { it.targetId })
        val recalled = AgentRetainedRecall.rank("Eta Android app", store.snapshot().items, 3)
        assertTrue(recalled.isNotEmpty())
        assertTrue(recalled.any { it.id == first.id || it.id == second.id })
    }

    @Test fun modelCandidatesNeedExactQuotedUserEvidenceAndSafeValues() {
        val user = "以后我希望 Eta 每次优先用中文回答"
        val data = JSONObject().put("items", JSONArray().put(
            JSONObject()
                .put("text", "User prefers Chinese responses in Eta")
                .put("evidence", "我希望 Eta 每次优先用中文回答")
                .put("layer", "semantic").put("category", "preference")
                .put("scope", "global").put("subject", "user.language")
                .put("confidence", 0.95).put("entities", JSONArray(listOf("Eta")))
                .put("links", JSONArray()),
        ))
        assertEquals(1, AgentRetainExtractor.validateModelOutput(data, user, emptyList()).size)
        data.getJSONArray("items").getJSONObject(0).put("evidence", "I never said that")
        assertTrue(AgentRetainExtractor.validateModelOutput(data, user, emptyList()).isEmpty())
        assertFalse(AgentRetainPrivacy.allow("记住我的密码是 SuperSecret123"))
        assertFalse(AgentRetainPrivacy.allow("我的电话号码是 13812345678"))
        assertFalse(AgentRetainEligibility.durableStatementPresent("打开微信给朋友发个消息"))
        assertTrue(AgentRetainEligibility.durableStatementPresent("我一直喜欢在 Eta 中使用中文回答"))
    }

    @Test fun fakeModelIsToollessAndMemoryIsNeverInferredFromAssistantResponse() {
        val user = "以后我希望 Eta 记住更喜欢中文回答"
        var observedUser: String? = null
        val provider = object : AgentProviderClient {
            override val id: String = "test"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS,
                streamingText = false, streamingToolCalls = false,
                imageInput = false, toolResultImages = false,
                strictTools = false, parallelToolCalls = false,
            )
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                assertEquals(ProviderRequestPurpose.TRANSCRIPT_REFINE, request.purpose)
                assertEquals(0, request.tools.length())
                observedUser = request.messages.getJSONObject(1).getString("content")
                return ProviderResponse(JSONObject().put("content",
                    JSONObject().put("items", JSONArray().put(JSONObject()
                        .put("text", "The user prefers Chinese responses in Eta")
                        .put("evidence", "更喜欢中文回答")
                        .put("layer", "semantic")
                        .put("category", "preference")
                        .put("scope", "global")
                        .put("subject", "user.language")
                        .put("confidence", 0.97))).toString(),
                ).put("finish_reason", "stop"))
            }
        }
        val extractor = AgentRetainExtractor { provider }
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.test/v1", apiKey = "test", model = "test-model",
            systemPrompt = "Test only",
        )
        val result = extractor.extract(config, user, emptyList())
        assertEquals(1, result.size)
        assertTrue(observedUser.orEmpty().contains("以后我希望 Eta"))
        assertFalse(observedUser.orEmpty().contains("assistant_response"))
        assertTrue(extractor.extract(config, "密码是 123456abc", emptyList()).isEmpty())
    }

    @Test fun schemaOneIsReadableWithoutRetainedFieldAndRoundTripWritesThree() {
        val original = JSONObject()
            .put("schema", 1).put("memory_md", "old")
            .put("knowledge", JSONObject(KnowledgeJson.encode(emptyList())))
            .put("experience", JSONObject(ExperienceSyncSnapshot.EMPTY.json()))
        val decoded = EtaSyncPayload.fromJson(original.toString())
        assertTrue(decoded.retained.items.isEmpty())
        assertEquals(3, JSONObject(decoded.json()).getInt("schema"))
        assertTrue(JSONObject(decoded.json()).has("retained"))
    }
}
