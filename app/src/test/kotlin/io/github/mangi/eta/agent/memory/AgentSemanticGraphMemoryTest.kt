package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.AgentKnowledgeStore
import io.github.mangi.eta.data.repository.RetainCandidate
import io.github.mangi.eta.data.repository.RetainedMemoryNode
import io.github.mangi.eta.data.repository.RetainedMemoryStore
import io.github.mangi.eta.data.repository.RetainedRelation
import io.github.mangi.eta.data.repository.RetainedMemoryMerge
import io.github.mangi.eta.data.repository.RetainedMemorySnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentSemanticGraphMemoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun node(
        id: String,
        text: String,
        scope: String = "global",
        links: List<RetainedRelation> = emptyList(),
        invalidatedAt: Long = 0,
        deletedAt: Long = 0,
    ) = RetainedMemoryNode(
        id, text, "semantic", "fact", scope, "test.topic",
        emptyList(), "user_statement", "user evidence",
        listOf(AgentKnowledgeStore.sha256(id)), 0.95, links,
        1000, 2000, 1000, invalidatedAt, deletedAt,
    )

    private val a = "3a1b5c0d-b328-40cb-aef3-93e5c0240101"
    private val b = "3a1b5c0d-b328-40cb-aef3-93e5c0240102"
    private val c = "3a1b5c0d-b328-40cb-aef3-93e5c0240103"
    private val d = "3a1b5c0d-b328-40cb-aef3-93e5c0240104"
    private val e = "3a1b5c0d-b328-40cb-aef3-93e5c0240105"

    private fun config(baseUrl: String = "https://api.example.test/v1") =
        AgentModelClient.ModelConfig(
            baseUrl = baseUrl, apiKey = "not-a-real-key", model = "chat-model",
            systemPrompt = "test",
        )

    private fun vector(x: Float, y: Float): FloatArray =
        FloatArray(32).also { it[0] = x; it[1] = y }

    @Test fun embeddingEndpointRequiresHttpsAndSeparateModel() {
        val endpoint = EtaEmbeddingEndpoint.fromConfig(config(), "text-embedding-3-small")
        assertEquals("https://api.example.test/v1/embeddings", endpoint.url)
        assertEquals("text-embedding-3-small", endpoint.model)
        assertThrows(IllegalArgumentException::class.java) {
            EtaEmbeddingEndpoint.fromConfig(config("http://example.com/v1"), "embed-model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EtaEmbeddingEndpoint.fromConfig(config("https://user:pass@example.com/v1"), "embed-model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EtaEmbeddingEndpoint.fromConfig(config(), "bad embedding model")
        }
    }

    @Test fun providerParserEnforcesDimensionAndResponseIndices() {
        val rows = JSONArray()
            .put(JSONObject().put("index", 1)
                .put("embedding", JSONArray(vector(0f, 4f).map { it.toDouble() })))
            .put(JSONObject().put("index", 0)
                .put("embedding", JSONArray(vector(3f, 0f).map { it.toDouble() })))
        val normalized = EtaOpenAiEmbeddingProvider.parseVectors(
            JSONObject().put("data", rows).toString(), 2,
        )
        assertTrue(abs(normalized[0][0] - 1.0) < 1e-5)
        assertTrue(abs(normalized[1][1] - 1.0) < 1e-5)
        rows.getJSONObject(1).put("index", 1)
        assertThrows(IllegalArgumentException::class.java) {
            EtaOpenAiEmbeddingProvider.parseVectors(
                JSONObject().put("data", rows).toString(), 2,
            )
        }
        val invalid = JSONObject().put("data", JSONArray().put(JSONObject()
            .put("index", 0).put("embedding", JSONArray(listOf(1.0, 2.0)))))
        assertThrows(IllegalArgumentException::class.java) {
            EtaOpenAiEmbeddingProvider.parseVectors(invalid.toString(), 1)
        }
    }

    @Test fun localVectorCacheNeverReturnsStaleOrWrongModelVectors() {
        val store = EtaMemoryVectorIndex(temp.newFolder())
        val first = node(a, "Eta remembers that I prefer simple explanations")
        val modelA = EtaEmbeddingEndpoint("https://embed.example/v1/embeddings", "model-a", "secret")
        val fingerprintA = EtaMemoryVectorIndex.fingerprint(modelA)
        val stored = store.save(fingerprintA, listOf(first), mapOf(a to vector(10f, 0f)))
        assertEquals(1, stored.vectors.size)
        assertEquals(1, store.readValid(fingerprintA, listOf(first)).vectors.size)
        assertTrue(store.readValid(fingerprintA, listOf(first.copy(text = "changed text"))).vectors.isEmpty())
        val modelB = modelA.copy(model = "model-b", apiKey = "another-key")
        assertTrue(store.readValid(EtaMemoryVectorIndex.fingerprint(modelB), listOf(first)).vectors.isEmpty())
        assertEquals(fingerprintA, EtaMemoryVectorIndex.fingerprint(modelA.copy(apiKey = "different-key")))
        assertTrue(store.readValid(fingerprintA, listOf(first.copy(deletedAt = 3000))).vectors.isEmpty())
        store.save(fingerprintA, listOf(first), mapOf(a to FloatArray(48).also { it[0] = 1f }))
        assertEquals(48, store.readValid(fingerprintA, listOf(first)).dimension)
    }

    @Test fun semanticRetrievalFindsCrossLanguageMemoryWithoutLexicalOverlap() {
        val records = listOf(
            node(a, "The user prefers answers written in Chinese"),
            node(b, "The user's routine is morning exercise"),
        )
        val root = temp.newFolder()
        var requested: List<String>? = null
        val client = AgentSemanticMemorySearch(EtaMemoryVectorIndex(root)) { _ ->
            EtaEmbeddingProvider { texts, _ ->
                requested = texts
                texts.map { text ->
                    if (text.contains("汉语") || text.contains("Chinese")) {
                        vector(1f, 0f)
                    } else vector(0f, 1f)
                }
            }
        }
        val endpoint = EtaEmbeddingEndpoint("https://x.test/v1/embeddings", "m", "token")
        val query = "请用汉语回答以后所有的问题"
        val result = client.search(query, records, endpoint, AgentRunController())
        assertTrue(requested.orEmpty().size > 1)
        assertEquals(a, result.ranked.first().first)
        assertEquals(2, result.indexed)
        val second = client.search(query, records, endpoint, AgentRunController())
        assertEquals(1, requested.orEmpty().size) // cache means only the query is embedded
        assertEquals(a, second.ranked.first().first)
    }

    @Test fun confirmedTwoHopAndProposedOneHopNeverCrossScopes() {
        val entries = listOf(
            node(a, "Chinese language preferences",
                links = listOf(
                    RetainedRelation(b, "SAME_TOPIC", 0.99, "confirmed"),
                    RetainedRelation(d, "APPLIES_TO", 0.87, "proposed"),
                    RetainedRelation(e, "SAME_TOPIC", 0.99, "confirmed"),
                )),
            node(b, "Settings tutorial", links = listOf(
                RetainedRelation(c, "PREREQUISITE", 0.98, "confirmed"),
            )),
            node(c, "Relevant desktop configuration", links = listOf(
                RetainedRelation(a, "PREREQUISITE", 0.98, "confirmed"),
            )),
            node(d, "Secondary feature suggestion", links = listOf(
                RetainedRelation(c, "SUPPORTS", 0.96, "proposed"),
            )),
            node(e, "Other application's private profile", scope = "app:微信"),
        )
        val results = AgentGraphMemoryRecall.retrieve(
            "Chinese language preferences", entries,
            semantic = listOf(a to 0.97), enableGraph = true,
        )
        assertTrue(results.any { it.node.id == b })
        assertTrue(results.any { it.node.id == c && it.path.size == 2 })
        assertTrue(results.any { it.node.id == d && it.isUnreviewed })
        assertFalse(results.any { it.node.id == e })
        assertTrue(results.size <= 7)
    }

    @Test fun rejectedAndInvalidMemoryCannotTraverseOrBeSeeds() {
        val entries = listOf(
            node(a, "Chinese settings", links = listOf(
                RetainedRelation(b, "SAME_TOPIC", 0.99, "rejected"),
                RetainedRelation(c, "CONTRADICTS", 0.90, "proposed"),
                RetainedRelation(d, "SAME_TOPIC", 0.90, "confirmed"),
            )),
            node(b, "Unapproved remote note"),
            node(c, "Explicitly opposite user statement"),
            node(d, "Expired rule", invalidatedAt = 3000),
        )
        val results = AgentGraphMemoryRecall.retrieve(
            "Chinese settings", entries, semantic = listOf(a to 0.92),
        )
        assertFalse(results.any { it.node.id == b || it.node.id == d })
        assertTrue(results.any { it.node.id == c && it.isConflict })
        assertTrue(AgentGraphMemoryRecall.hint(results).orEmpty().contains("矛盾候选"))
    }

    @Test fun reviewIsPersistentAndCannotBeUndoneByNewModelProposal() {
        val repo = RetainedMemoryStore(temp.newFolder())
        fun make(text: String) = RetainCandidate(
            text, "semantic", "fact", "global", "topic",
            "user evidence", emptyList(), emptyList(),
        )
        val left = repo.retain(
            listOf(make("My project uses Chinese output")), AgentKnowledgeStore.sha256("one"), 1000,
        ).single()
        val right = repo.retain(
            listOf(make("Settings for Chinese language input")), AgentKnowledgeStore.sha256("two"), 1500,
        ).single()
        assertEquals(1, repo.proposeLinks(left.id, listOf(RetainedRelation(right.id, "SAME_TOPIC", 0.91,
            "proposed", "Both refer to configuring Chinese language")), 2000))
        assertTrue(repo.reviewLink(left.id, right.id, "SAME_TOPIC", "rejected", 3000))
        repo.proposeLinks(left.id, listOf(RetainedRelation(right.id, "SAME_TOPIC", 0.99,
            "proposed", "Model repeated earlier suggestion")), 4000)
        assertEquals("rejected", repo.snapshot().items.single { it.id == left.id }.links.single().status)
        val stale = repo.snapshot().items.single { it.id == left.id }.copy(
            links = listOf(RetainedRelation(right.id, "SAME_TOPIC", 0.91, "proposed")),
            updatedAt = 2000,
        )
        val merged = RetainedMemoryMerge.merge(
            RetainedMemorySnapshot(listOf(repo.snapshot().items.single { it.id == left.id })),
            RetainedMemorySnapshot(listOf(stale)),
        )
        assertEquals("rejected", merged.items.single().links.single().status)
        val laterProposal = stale.copy(
            links = listOf(RetainedRelation(
                right.id, "SAME_TOPIC", 0.99, "proposed",
                "A newer model suggestion cannot undo the user's decision", 9000,
            )),
            updatedAt = 9000,
        )
        val guarded = RetainedMemoryMerge.merge(
            RetainedMemorySnapshot(listOf(repo.snapshot().items.single { it.id == left.id })),
            RetainedMemorySnapshot(listOf(laterProposal)),
        )
        assertEquals("rejected", guarded.items.single().links.single().status)
    }

    @Test fun classifierCannotInventOrAutoConfirmLinks() {
        val source = node(a, "Chinese language preference")
        val matching = node(b, "Follow language preference")
        val otherScope = node(c, "Private app data", scope = "app:微信")
        val response = JSONObject().put("relations", JSONArray()
            .put(JSONObject().put("source_id", a).put("target_id", b)
                .put("type", "SAME_TOPIC").put("confidence", 0.92)
                .put("reason", "Both memories describe the same language preference"))
            .put(JSONObject().put("source_id", a).put("target_id", c)
                .put("type", "SAME_TOPIC").put("confidence", 0.99)
                .put("reason", "Cross scope not allowed"))
            .put(JSONObject().put("source_id", a).put("target_id", d)
                .put("type", "SAME_TOPIC").put("confidence", 0.99)
                .put("reason", "Invented memory ID"))
            .put(JSONObject().put("source_id", a).put("target_id", b)
                .put("type", "PREREQUISITE").put("confidence", 0.50)
                .put("reason", "Low confidence is filtered")))
        val result = AgentMemoryRelationClassifier.validate(
            response, listOf(source), listOf(matching, otherScope),
        )
        assertEquals(1, result[a]?.size)
        assertEquals("proposed", result[a]?.single()?.status)
        assertTrue(result[a]?.single()?.reason.orEmpty().contains("language"))
    }
}
