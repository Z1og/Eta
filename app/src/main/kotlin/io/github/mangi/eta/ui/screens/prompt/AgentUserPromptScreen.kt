package io.github.mangi.eta.ui.screens.prompt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.AgentUserPromptMode
import io.github.mangi.eta.data.repository.AgentUserPromptStore
import io.github.mangi.eta.ui.components.EtaPreference
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

    MiuixScaffoldPage(
        title = "作战协议",
        onBack = { onAction(AgentUserPromptAction.NavigateBack) },
        modifier = Modifier.imePadding(),
    ) {
        item(key = "intro") {
            Text(
                text = "把要常驻的提示词正文放进 prompts/ 目录下第一个非空 .md（默认 ${
                    AgentUserPromptStore.DEFAULT_FILE_NAME
                }）。它以独立 system 消息注入，与内建交付契约同等优先，两者冲突时以本协议为准。",
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
    val size = "${state.charCount} 字符"
    return if (state.truncated) {
        "prompts/$name · $size（超出 ${AgentUserPromptStore.MAX_CHARS} 上限，按截断版注入；编辑已锁定）"
    } else {
        "prompts/$name · $size"
    }
}
