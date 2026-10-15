package io.github.mangi.eta.ui.screens.prompt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.AgentUserPromptMode
import io.github.mangi.eta.data.repository.AgentUserPromptStore
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaRadioButtonPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentUserPromptAction
import io.github.mangi.eta.ui.model.AgentUserPromptUiState
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 作战协议页：正文来自 `filesDir/prompts/` 下第一个非空 `.md`。
 *
 * 页面顺序刻意是「先看到状态、再决定是否启用」——默认关闭，文件存在也不改变行为，
 * 避免用户没开就发现行为变了还找不到原因。
 */
@Composable
internal fun AgentUserPromptScreen(
    state: AgentUserPromptUiState,
    onAction: (AgentUserPromptAction) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    // .md/.txt 在部分文件提供方会被报成 application/octet-stream，与其猜 MIME 不如放开后按内容校验。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { onAction(AgentUserPromptAction.Import(it)) }
    }

    MiuixScaffoldPage(
        title = "作战协议",
        onBack = { onAction(AgentUserPromptAction.NavigateBack) },
        modifier = Modifier.imePadding(),
    ) {
        item(key = "intro") {
            Text(
                text = "把要常驻的提示词正文放进 prompts/ 目录下第一个非空 .md（默认 ${
                    AgentUserPromptStore.DEFAULT_FILE_NAME
                }），或直接从文件导入。它以独立 system 消息整篇注入，与内建交付契约同等优先，两者冲突时以本协议为准。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        item(key = "status") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = "生效范围",
                    summary = modeSummary(state),
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = "当前文件",
                    summary = fileSummary(state),
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = "内置版本",
                    summary = builtinSummary(state),
                )
            }
        }

        item(key = "mode-title") { EtaPreferenceGroupTitle("生效范围") }
        item(key = "mode") {
            EtaPreferenceGroup {
                EtaRadioButtonPreference(
                    title = "关闭",
                    summary = "不注入；文件存在也不影响任何行为（默认）",
                    selected = state.mode == AgentUserPromptMode.Off,
                    onClick = { onAction(AgentUserPromptAction.ModeChanged(AgentUserPromptMode.Off)) },
                )
                EtaPreferenceDivider()
                EtaRadioButtonPreference(
                    title = "全局常驻",
                    summary = "所有会话都注入这份作战协议",
                    selected = state.mode == AgentUserPromptMode.Global,
                    onClick = { onAction(AgentUserPromptAction.ModeChanged(AgentUserPromptMode.Global)) },
                )
                EtaPreferenceDivider()
                EtaRadioButtonPreference(
                    title = "仅逆向模式",
                    summary = if (state.reverseModeEnabled) {
                        "当前已是逆向模式，会注入"
                    } else {
                        "所有已安装逆向技能均启用时才注入（当前未处于逆向模式）"
                    },
                    selected = state.mode == AgentUserPromptMode.ReverseOnly,
                    onClick = { onAction(AgentUserPromptAction.ModeChanged(AgentUserPromptMode.ReverseOnly)) },
                )
            }
        }

        item(key = "editor-title") { EtaPreferenceGroupTitle("正文") }
        item(key = "editor") {
            if (state.showEditor) {
                TextField(
                    value = state.draft,
                    onValueChange = { onAction(AgentUserPromptAction.DraftChanged(it)) },
                    label = "作战协议正文",
                    enabled = !state.isLoading && !state.isSaving && !state.editingLocked,
                    singleLine = false,
                    minLines = 10,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            } else {
                LargeContentPreview(
                    state = state,
                    onOpenEditor = { onAction(AgentUserPromptAction.OpenEditor) },
                )
            }
        }

        item(key = "budget") {
            Text(
                text = budgetSummary(state),
                style = MiuixTheme.textStyles.footnote1,
                color = budgetColor(state),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        item(key = "sources-title") { EtaPreferenceGroupTitle("正文来源") }
        item(key = "sources") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                EtaTextButton(
                    text = "从文件导入",
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = !state.isLoading && !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.builtinAvailable) {
                    Spacer(Modifier.height(4.dp))
                    EtaTextButton(
                        text = if (state.builtinInstalled) "重新装配内置版本" else "装配内置版本",
                        onClick = { onAction(AgentUserPromptAction.InstallBuiltin) },
                        enabled = !state.isLoading && !state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EtaTextButton(
                    text = "重新载入",
                    onClick = { onAction(AgentUserPromptAction.Reload) },
                    enabled = !state.isLoading && !state.isSaving,
                    modifier = Modifier.weight(1f),
                )
                EtaTextButton(
                    text = "保存",
                    onClick = { onAction(AgentUserPromptAction.Save) },
                    enabled = state.canSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        item(key = "clear") {
            EtaTextButton(
                text = "清空（先备份）",
                onClick = { showClearDialog = true },
                enabled = !state.isLoading && !state.isSaving && state.savedContent.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        item(key = "footer") {
            Text(
                text = "改动会在下个会话生效；会话中途动文件不会改变正在进行的请求前缀（否则系统提示抖动会让整段缓存失效）。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (showClearDialog) {
        EtaWindowDialog(
            show = true,
            title = "清空作战协议",
            summary = "当前文件会先备份到 prompts/.backup/，再清空。清空后不再注入任何协议。",
            onDismissRequest = { showClearDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = "清空",
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    onAction(AgentUserPromptAction.Clear)
                },
            )
        }
    }

    state.pendingImport?.let { pending ->
        EtaWindowDialog(
            show = true,
            title = "导入作战协议",
            summary = buildString {
                appendLine("${pending.fileName}｜${formatNumber(pending.charCount)} 字符｜${formatBytes(pending.byteSize)}")
                if (pending.overLimit) {
                    appendLine("超出 ${formatNumber(AgentUserPromptStore.MAX_CHARS)} 字符上限，导入后会按截断版注入。")
                }
                append("确认后替换当前生效文件；原文件会先备份到 prompts/.backup/。")
            },
            onDismissRequest = { onAction(AgentUserPromptAction.CancelImport) },
        ) {
            MiuixDialogActions(
                confirmText = "导入",
                confirmEnabled = !state.isSaving,
                onCancel = { onAction(AgentUserPromptAction.CancelImport) },
                onConfirm = { onAction(AgentUserPromptAction.ConfirmImport) },
            )
        }
    }

    state.notice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = "作战协议",
            summary = notice,
            onDismissRequest = { onAction(AgentUserPromptAction.DismissNotice) },
        ) {
            EtaTextButton(
                text = "知道了",
                onClick = { onAction(AgentUserPromptAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 正文过长时的只读预览。
 *
 * 8 万字符塞进 TextField，每次重排都要整段测量，手机上会明显卡顿；而这条链路的真实用法是
 * 「导入整份提示词再启用」，不需要逐字编辑。所以默认只渲染前 [AgentUserPromptUiState.PREVIEW_CHARS]
 * 个字符供核对，要手改再显式展开全文。
 */
@Composable
private fun LargeContentPreview(
    state: AgentUserPromptUiState,
    onOpenEditor: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = state.previewText,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 24,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.previewTruncated) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "仅预览前 ${formatNumber(AgentUserPromptUiState.PREVIEW_CHARS)} 字符，全文 ${
                    formatNumber(state.draft.length)
                } 字符。在手机上展开编辑会明显卡顿，建议直接用文件导入覆盖。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(6.dp))
        EtaTextButton(
            text = "在编辑区打开全文",
            onClick = onOpenEditor,
            enabled = !state.isLoading && !state.isSaving && !state.editingLocked,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 内置版本状态：随包多少字符、是否就是当前生效文件——换版或手改过会显示「与当前不同」。 */
private fun builtinSummary(state: AgentUserPromptUiState): String {
    val chars = state.builtinCharCount ?: return "未随包内置；只能手动粘贴或从文件导入"
    val size = "${formatNumber(chars)} 字符"
    return when {
        state.builtinInstalled -> "随包 $size · 已装配为当前生效文件"
        state.sourceFileName.isNullOrBlank() -> "随包 $size · 尚未装配"
        else -> "随包 $size · 与当前生效文件不同（可点下方覆盖）"
    }
}

private fun modeSummary(state: AgentUserPromptUiState): String = when {
    state.mode == AgentUserPromptMode.Off && state.pendingActivation ->
        "已检测到正文，但当前关闭——不会注入"
    state.mode == AgentUserPromptMode.Off -> "关闭"
    state.mode == AgentUserPromptMode.Global -> "全局常驻"
    state.reverseModeEnabled -> "仅逆向模式（当前生效中）"
    else -> "仅逆向模式（当前不在逆向模式，未注入）"
}

private fun fileSummary(state: AgentUserPromptUiState): String {
    val name = state.sourceFileName
    if (name.isNullOrBlank()) {
        return "未检测到：prompts/ 下没有非空的 .md"
    }
    val size = "${formatNumber(state.charCount)} 字符"
    return if (state.truncated) {
        "prompts/$name · $size（超出 ${formatNumber(AgentUserPromptStore.MAX_CHARS)} 上限，按截断版注入；编辑已锁定）"
    } else {
        "prompts/$name · $size"
    }
}

/** 体积警告：实时字符数、与上限的关系以及模型上下文窗口——超限用红、接近上限用黄。 */
private fun budgetSummary(state: AgentUserPromptUiState): String = buildString {
    val limit = AgentUserPromptStore.MAX_CHARS
    append("${formatNumber(state.draftCharCount)} / ${formatNumber(limit)} 字符")
    when {
        state.truncated -> append("　磁盘文件超限，实际按截断版注入")
        state.draftCharCount > limit -> append("　超出上限，保存后会按截断版注入")
        state.nearBudget -> append("　已接近上限，注意上下文开销")
        else -> append("　每轮请求都随系统提示发送")
    }
    state.contextWindow?.let { append("　模型上下文窗口 ${formatNumber(it)} token") }
}

@Composable
private fun budgetColor(state: AgentUserPromptUiState) = when {
    state.truncated || state.draftCharCount > AgentUserPromptStore.MAX_CHARS -> MiuixTheme.colorScheme.error
    state.nearBudget -> EtaPreferenceColors.Yellow
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    bytes % (1_024 * 1_024) == 0 -> "${bytes / (1_024 * 1_024)} MiB"
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}
