package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentUserPromptMode
import io.github.mangi.eta.data.repository.AgentUserPromptStore

/**
 * 待确认的导入。正文本身**不放在这里**——它可能有 8 万字符，
 * 进 Compose 状态会让每次重组都复制一遍；正文由 store 持有，这里只放展示用的元数据。
 */
@Immutable
internal data class PendingImport(
    val fileName: String,
    val charCount: Int,
    val byteSize: Int,
) {
    val overLimit: Boolean get() = charCount > AgentUserPromptStore.MAX_CHARS
}

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
    val contextWindow: Int? = null,
    val editorExpanded: Boolean = false,
    val pendingImport: PendingImport? = null,
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

    /** 磁盘上的文件超出注入上限，实际发给模型的是截断版。 */
    val overBudget: Boolean get() = charCount > AgentUserPromptStore.MAX_CHARS

    /** 编辑缓冲的实时字符数。与磁盘上的 [charCount] 区分：后者是文件原始长度，不被编辑影响。 */
    val draftCharCount: Int get() = draft.length

    /** 已进入「接近上限」区间，需要提醒体积代价。 */
    val nearBudget: Boolean
        get() = draftCharCount >= AgentUserPromptStore.MAX_CHARS * NEAR_BUDGET_PERCENT / 100

    /**
     * 已存正文过长时不把它塞进 TextField。
     *
     * 8 万字符的可编辑文本每次重排都很重，而这条链路的真实用法是「导入整份提示词再启用」，
     * 没人会在手机上逐字编辑 1,337 行。默认只给前 [PREVIEW_CHARS] 字符的只读预览，
     * 需要手改时再显式展开。
     */
    val largeContent: Boolean get() = savedContent.length > INLINE_EDITOR_LIMIT

    val showEditor: Boolean get() = !largeContent || editorExpanded

    val previewText: String get() = draft.take(PREVIEW_CHARS)

    val previewTruncated: Boolean get() = draft.length > PREVIEW_CHARS

    companion object {
        /** 超过这个长度就不再默认渲染可编辑文本框。 */
        const val INLINE_EDITOR_LIMIT = 8_000

        /** 只读预览展示的字符数。 */
        const val PREVIEW_CHARS = 3_000

        /** 到达上限的这个百分比即开始提示。 */
        const val NEAR_BUDGET_PERCENT = 80
    }
}
