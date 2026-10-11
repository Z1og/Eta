package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelContextInferenceTest {

    private fun model(
        id: String,
        modelId: String,
        window: Int? = null,
        override: Int? = null,
    ) = Model(
        id = id,
        modelId = modelId,
        displayName = modelId,
        contextWindow = window,
        contextWindowOverride = override,
    )

    @Test
    fun normalizeDropsVendorPrefixAndTags() {
        assertEquals("claude-sonnet-4", ModelContextInference.normalize("anthropic/claude-sonnet-4"))
        assertEquals("deepseek-v3", ModelContextInference.normalize("DeepSeek-V3:free"))
        assertEquals("qwen-max", ModelContextInference.normalize("qwen-max@20250101"))
    }

    @Test
    fun familyStripsDateAndVersionSuffixes() {
        assertEquals("gpt-4o-mini", ModelContextInference.family("gpt-4o-mini-2024-07-18"))
        assertEquals("deepseek-v3", ModelContextInference.family("deepseek-v3-0324"))
        assertEquals("gpt-4o", ModelContextInference.family("openai/gpt-4o:free"))
        assertEquals("qwen3-coder", ModelContextInference.family("qwen3-coder-latest"))
    }

    @Test
    fun inheritsSmallestWindowFromSameFamily() {
        val base = model("base", "gpt-4o-mini", window = 128_000)
        val dated = model("dated", "gpt-4o-mini-2024-07-18")
        assertEquals(128_000, ModelContextInference.infer(listOf(base, dated), dated))
    }

    @Test
    fun overrideOfSiblingWins() {
        val base = model("base", "deepseek-v3", window = 64_000, override = 32_000)
        val sibling = model("sibling", "deepseek-v3-0324")
        assertEquals(32_000, ModelContextInference.infer(listOf(base, sibling), sibling))
    }

    @Test
    fun doesNotInferAcrossFamiliesOrUnknownMembers() {
        val other = model("other", "claude-sonnet-4", window = 200_000)
        val mine = model("mine", "gpt-4o-mini-2024-07-18")
        assertNull(ModelContextInference.infer(listOf(other, mine), mine))
        val stranger = model("stranger", "gpt-4o-mini-2024-07-18")
        assertNull(ModelContextInference.infer(listOf(other), stranger))
    }

    @Test
    fun ignoresSelfAndNonPositiveWindows() {
        val mine = model("mine", "gpt-4o-mini", window = 0)
        val dated = model("dated", "gpt-4o-mini-2024-07-18", override = 64_000)
        assertEquals(64_000, ModelContextInference.infer(listOf(mine, dated), dated))
    }
}
