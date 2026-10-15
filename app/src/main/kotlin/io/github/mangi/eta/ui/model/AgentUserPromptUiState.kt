package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentUserPromptMode
import io.github.mangi.eta.data.repository.AgentUserPromptStore

/**
 * 作战协议页状态。
 *
 * [mode] 是生效范围（默认关闭）；[savedContent] 是磁盘上的正文，[draft] 是编辑缓冲。
 * 文件存在但档位是关闭时 [pendingActivation] 为真——用于提示“已检测到但未启用”。
 */
@Immutable
internal data class AgentUserPromptUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val mode: AgentUserPromptMode = AgentUserPromptMode.Off,
    val draft: String = "",
    val savedContent: String = "",
    val sourceFileName: String? = null,
    val charCount: Int = 0,
    val truncated: Boolean = false,
    val reverseModeEnabled: Boolean = false,
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent

    val draftBytes: Int get() = draft.toByteArray(Charsets.UTF_8).size

    /**
     * 文件超出注入上限时，编辑区里放的是截断版——此时禁止保存，
     * 否则一保存就把用户的原文件覆盖成截断版本。改文件请走外部（文件工具 / root），然后重新载入。
     */
    val editingLocked: Boolean get() = truncated

    val canSave: Boolean
        get() = !isLoading && !isSaving && !editingLocked && hasUnsavedChanges &&
            draftBytes <= AgentUserPromptStore.MAX_FILE_BYTES

    /** 文件里已有可用正文，但当前档位不会注入它。 */
    val pendingActivation: Boolean
        get() = mode == AgentUserPromptMode.Off && savedContent.isNotBlank()

    /** 文件超出注入上限，实际发给模型的是截断版。 */
    val overBudget: Boolean get() = charCount > AgentUserPromptStore.MAX_CHARS
}
