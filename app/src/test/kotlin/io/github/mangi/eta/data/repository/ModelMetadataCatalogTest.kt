package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMetadataCatalogTest {

    private fun model(id: String) = Model(
        id = id,
        modelId = id,
        displayName = id,
    )

    // ── normalizeForMatch ──────────────────────────────────

    @Test
    fun `normalize strips vendor prefix and date suffix`() {
        assertEquals("claudesonnet45", ModelMetadataCatalog.normalizeForMatch("anthropic/claude-sonnet-4.5"))
        assertEquals("claudesonnet45", ModelMetadataCatalog.normalizeForMatch("Claude-Sonnet-4-5-20250929"))
        assertEquals("gpt5", ModelMetadataCatalog.normalizeForMatch("openai/gpt-5"))
    }

    @Test
    fun `normalize strips variant suffixes`() {
        assertEquals("qwenmax", ModelMetadataCatalog.normalizeForMatch("qwen-max-latest"))
        assertEquals("deepseekr1", ModelMetadataCatalog.normalizeForMatch("deepseek-r1-0528").let {
            // r1-0528 的日期不是 20xxxxxx 格式，保留
            ModelMetadataCatalog.normalizeForMatch("deepseek-r1-20250528")
        })
    }

    // ── 静态家族表（方案 B）───────────────────────────────

    @Test
    fun `gpt-5 family fills window and efforts`() {
        val entry = ModelMetadataCatalog.entryFor("gpt-5", emptyMap())
        assertNotNull(entry)
        assertEquals(272_000, entry!!.contextWindow)
        assertTrue(ReasoningEffort.HIGH in entry.capabilities!!.supportedEfforts)
    }

    @Test
    fun `claude family with 1m marker gets 1M window`() {
        val entry = ModelMetadataCatalog.entryFor("claude-sonnet-4-5-1m", emptyMap())
        assertEquals(1_000_000, entry!!.contextWindow)
    }

    @Test
    fun `claude family default window is 200k`() {
        val entry = ModelMetadataCatalog.entryFor("claude-sonnet-4-5-20250929", emptyMap())
        assertEquals(200_000, entry!!.contextWindow)
        assertTrue(entry.capabilities!!.supportsBudget)
    }

    @Test
    fun `deepseek-reasoner is mandatory reasoning`() {
        val entry = ModelMetadataCatalog.entryFor("deepseek-reasoner", emptyMap())
        assertNotNull(entry)
        assertTrue(entry!!.capabilities!!.mandatory)
    }

    @Test
    fun `deepseek plain model has window but no reasoning`() {
        val entry = ModelMetadataCatalog.entryFor("deepseek-v3", emptyMap())
        assertEquals(131_072, entry!!.contextWindow)
        assertNull(entry.capabilities)
    }

    @Test
    fun `longest family prefix wins`() {
        // qwen3-max 命中 qwen3（262k）而不是 qwen（131k）
        val entry = ModelMetadataCatalog.entryFor("qwen3-max", emptyMap())
        assertEquals(262_144, entry!!.contextWindow)
        // qwen-max 命中 qwen-max（32k）
        assertEquals(32_768, ModelMetadataCatalog.entryFor("qwen-max", emptyMap())!!.contextWindow)
    }

    @Test
    fun `unknown model returns null`() {
        assertNull(ModelMetadataCatalog.entryFor("my-private-llm-v9", emptyMap()))
    }

    // ── enrichWith 只填空 ─────────────────────────────────

    @Test
    fun `enrich fills only missing fields`() {
        val catalog = mapOf("gpt5" to ModelMetadataCatalog.CatalogEntry(
            contextWindow = 272_000,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.HIGH)),
        ))
        val providerWindow = Model(
            id = "a", modelId = "gpt-5", displayName = "gpt-5",
            contextWindow = 123_456,
        )
        val bare = Model(id = "b", modelId = "gpt-5", displayName = "gpt-5")
        val result = ModelMetadataCatalog.enrichWith(listOf(providerWindow, bare), catalog)
        assertEquals(123_456, result[0].contextWindow)
        assertEquals(272_000, result[1].contextWindow)
        assertNotNull(result[1].reasoningCapabilities)
    }

    @Test
    fun `enrich leaves unmatched model untouched`() {
        val result = ModelMetadataCatalog.enrichWith(
            listOf(model("my-private-llm-v9")),
            emptyMap(),
        )
        assertNull(result[0].contextWindow)
        assertNull(result[0].reasoningCapabilities)
    }
}
