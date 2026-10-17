package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AgentGoalStore
import io.github.mangi.eta.ui.model.AgentGoalControls
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 输入框上方的目标行。
 *
 * 只有真的设了目标才显示：目标模式是主动开启的能力，不该在每次普通对话里都占一行。
 * 点正文进编辑；暂停/继续只在有 run 在跑时出现——没有 run 时那两个按钮点了也没意义。
 */
@Composable
internal fun AgentGoalRow(
    controls: AgentGoalControls,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!controls.hasGoal) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chat_goal_title),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = controls.preview,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onEdit),
        )
        if (controls.running) {
            GoalRowAction(
                text = stringResource(
                    if (controls.paused) R.string.chat_goal_resume else R.string.chat_goal_pause,
                ),
                onClick = if (controls.paused) controls.onResume else controls.onPause,
            )
        }
        GoalRowAction(
            text = stringResource(R.string.chat_goal_clear),
            onClick = controls.onClear,
        )
    }
}

@Composable
private fun GoalRowAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 目标编辑框：保存后从下一个 run 起注入；清空等于取消目标。 */
@Composable
internal fun AgentGoalDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(initial) }
    WindowDialog(
        show = true,
        cornerRadius = DialogDefaults.CornerRadius,
        title = stringResource(R.string.chat_goal_dialog_title),
        summary = stringResource(R.string.chat_goal_dialog_hint),
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = draft,
                onValueChange = { if (it.length <= AgentGoalStore.MAX_CHARS) draft = it },
                label = stringResource(R.string.chat_goal_hint),
                useLabelAsPlaceholder = true,
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            if (initial.isNotBlank()) {
                GoalRowAction(
                    text = stringResource(R.string.chat_goal_clear),
                    onClick = onClear,
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.chat_goal_save),
                confirmEnabled = draft.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = { onSave(draft) },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
