package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.repository.AgentUserPromptStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体积预算与"大文本降级预览"的纯逻辑边界。
 *
 * 不挂 Robolectric：这里没有 Android 依赖，而设备上的全量测试在这台机器上跑不动。
 */
class AgentUserPromptUiStateTest {
    @Test
    fun nearBudgetStartsAtEightyPercentOfTheInjectionLimit() {
        val limit = AgentUserPromptStore.MAX_CHARS
        val threshold = limit * AgentUserPromptUiState.NEAR_BUDGET_PERCENT / 100

        assertFalse(stateOf(threshold - 1).nearBudget)
        assertTrue(stateOf(threshold).nearBudget)
    }

    @Test
    fun overBudgetFollowsTheStoredFileLengthNotTheDraft() {
        val limit = AgentUserPromptStore.MAX_CHARS

        // 草稿超限但磁盘上还是旧的短内容：这是"还没保存"，不该报磁盘超限。
        val dirty = AgentUserPromptUiState(
            isLoading = false,
            draft = "a".repeat(limit + 1),
            savedContent = "短文",
            charCount = 2,
        )
        assertFalse(dirty.overBudget)
        assertTrue(dirty.draftCharCount > limit)

        val stored = AgentUserPromptUiState(isLoading = false, charCount = limit + 1, truncated = true)
        assertTrue(stored.overBudget)
    }

    @Test
    fun largeStoredContentFallsBackToPreviewUntilExplicitlyExpanded() {
        val small = stateOf(AgentUserPromptUiState.INLINE_EDITOR_LIMIT)
        assertFalse(small.largeContent)
        assertTrue(small.showEditor)

        val large = stateOf(AgentUserPromptUiState.INLINE_EDITOR_LIMIT + 1)
        assertTrue(large.largeContent)
        assertFalse(large.showEditor)
        assertTrue(large.copy(editorExpanded = true).showEditor)
    }

    @Test
    fun previewIsClippedToThePreviewWindow() {
        val exact = stateOf(AgentUserPromptUiState.PREVIEW_CHARS)
        assertFalse(exact.previewTruncated)
        assertEquals(AgentUserPromptUiState.PREVIEW_CHARS, exact.previewText.length)

        val longer = stateOf(AgentUserPromptUiState.PREVIEW_CHARS + 1)
        assertTrue(longer.previewTruncated)
        assertEquals(AgentUserPromptUiState.PREVIEW_CHARS, longer.previewText.length)
    }

    @Test
    fun truncatedFileStillLocksEditingSoTheClippedDraftCannotOverwriteIt() {
        val truncated = AgentUserPromptUiState(
            isLoading = false,
            draft = "a".repeat(AgentUserPromptStore.MAX_CHARS),
            savedContent = "a".repeat(AgentUserPromptStore.MAX_CHARS),
            charCount = AgentUserPromptStore.MAX_CHARS + 500,
            truncated = true,
        )
        assertTrue(truncated.editingLocked)
        assertFalse(truncated.hasUnsavedChanges)
        assertFalse(truncated.canSave)
    }

    @Test
    fun builtinIsOnlyCountedAsInstalledWhenRevisionsMatch() {
        val none = AgentUserPromptUiState(isLoading = false)
        assertFalse(none.builtinAvailable)
        assertFalse(none.builtinInstalled)

        val matched = AgentUserPromptUiState(
            isLoading = false,
            revision = "aaa",
            builtinCharCount = 81_044,
            builtinRevision = "aaa",
        )
        assertTrue(matched.builtinAvailable)
        assertTrue(matched.builtinInstalled)

        // 用户手改过正文，或 App 升级换了内置版本：字符数还在，但 revision 对不上，应提示可重新装配。
        assertFalse(matched.copy(revision = "bbb").builtinInstalled)
        // 磁盘上还没有文件：EMPTY 快照的 revision 是空串，不能算"已装配"。
        assertFalse(matched.copy(revision = "").builtinInstalled)
    }

    private fun stateOf(length: Int) = AgentUserPromptUiState(
        isLoading = false,
        draft = "a".repeat(length),
        savedContent = "a".repeat(length),
        charCount = length,
    )
}
