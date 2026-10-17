package io.github.mangi.eta.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.components.AgentChatBody
import io.github.mangi.eta.ui.components.LocalRoleplayChat
import io.github.mangi.eta.ui.components.chatConversationCompositionKey
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentGoalControls
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.roleplayChatUi

/**
 * AgentChatHome：首屏为聊天主舞台。
 *
 * 顶部入口统一由 [io.github.mangi.eta.ui.app.AgentAppShell] 提供，
 * 本 Screen 只负责消息流、Run trace、工具摘要和底部输入框。
 */
@Composable
internal fun AgentHomeScreen(
    state: AgentChatHomeUiState,
    modelPickerState: AgentModelPickerUiState,
    conversationKey: String?,
    onAction: (AgentHomeAction) -> Unit,
    isDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    key(chatConversationCompositionKey(conversationKey)) {
        CompositionLocalProvider(LocalRoleplayChat provides state.roleplayChatUi()) {
        AgentChatBody(
            messages = state.messages,
            modelPickerState = modelPickerState,
            isCompacting = state.isCompacting,
            input = state.input,
            isStreaming = state.isStreaming,
            reasoningEffort = state.reasoningEffort,
            availableReasoningEfforts = state.availableReasoningEfforts,
            pendingImages = state.pendingImages,
            pendingFileReferences = state.pendingFileReferences,
            messageEdit = state.messageEdit,
            onReasoningEffortChange = { onAction(AgentHomeAction.ReasoningEffortChanged(it)) },
            onCompactContext = { onAction(AgentHomeAction.CompactContext) },
            canCompactContext = state.canCompactContext,
            onModelSelected = { onAction(AgentHomeAction.ModelSelected(it)) },
            onSubmit = { text -> onAction(AgentHomeAction.SubmitMessage(text)) },
            queuedMessages = state.queuedMessages,
            onSteerQueuedMessage = { id -> onAction(AgentHomeAction.SteerQueuedMessage(id)) },
            onRemoveQueuedMessage = { id -> onAction(AgentHomeAction.RemoveQueuedMessage(id)) },
            goal = AgentGoalControls(
                goal = state.goal,
                paused = state.goalPaused,
                running = state.isStreaming,
                taskProgress = state.taskProgress,
                onSave = { text -> onAction(AgentHomeAction.SaveGoal(text)) },
                onPause = { onAction(AgentHomeAction.PauseGoal) },
                onResume = { onAction(AgentHomeAction.ResumeGoal) },
                onClear = { onAction(AgentHomeAction.ClearGoal) },
            ),
            onStop = { onAction(AgentHomeAction.StopRun) },
            onAttachImage = { uri -> onAction(AgentHomeAction.ImageAttached(uri)) },
            onRemoveImage = { id -> onAction(AgentHomeAction.RemoveImage(id)) },
            onAttachFiles = { uris -> onAction(AgentHomeAction.FilesAttached(uris)) },
            onAttachFolder = { uri -> onAction(AgentHomeAction.FolderAttached(uri)) },
            onAttachFilePath = { path -> onAction(AgentHomeAction.FilePathAttached(path)) },
            onRemoveFileReference = { id -> onAction(AgentHomeAction.RemoveFileReference(id)) },
            onEditMessage = { id -> onAction(AgentHomeAction.EditMessage(id)) },
            onCancelMessageEdit = { onAction(AgentHomeAction.CancelMessageEdit) },
            onDeleteMessage = { id -> onAction(AgentHomeAction.DeleteMessage(id)) },
            onRegenerateMessage = { id -> onAction(AgentHomeAction.RegenerateMessage(id)) },
            onSelectReplyCandidate = { id, index -> onAction(AgentHomeAction.SelectReplyCandidate(id, index)) },
            onSuggestionClick = { prompt ->
                onAction(AgentHomeAction.SubmitMessage(prompt))
            },
            onRunTraceClick = { onAction(AgentHomeAction.ExpandRunTrace) },
            onOpenBrowser = { onAction(AgentHomeAction.OpenBrowser) },
            isDrawerOpen = isDrawerOpen,
            modifier = modifier,
        )
        }
    }
}
