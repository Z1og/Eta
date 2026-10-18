package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.components.RoleplayChatUi

@Immutable
internal data class AgentChatUiState(
    val messages: List<AgentChatMessageUi>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    // 完整脱敏历史独立于模型投影；摘要替换 history 时不覆盖 journal。
    val journal: List<AgentModelClient.ConversationMessage> = emptyList(),
    val input: String,
    val isStreaming: Boolean,
    val isCompacting: Boolean = false,
    val thinkingEnabled: Boolean,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.fromLegacy(thinkingEnabled),
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val appliedRuntimeRunIds: List<String> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
    val roleplay: RoleplayBinding? = null,
    val roleplayMessages: RoleplayMessageState = RoleplayMessageState(),
    /** 会话最近一次发送使用的本地 Model.id；null 表示尚未绑定，跟随默认模型。 */
    val modelId: String? = null,
    /**
     * 运行中提交的消息。它们还没进入模型上下文：可以「引导」进当前这轮，
     * 也可以删掉；本轮结束后队首会自动作为普通消息发出。
     */
    val queuedMessages: List<QueuedMessageUi> = emptyList(),
    /** 当前会话的持续目标；null 表示没有开启目标模式。 */
    val goal: String? = null,
    /** 目标模式被用户暂停：run 挂在下一个检查点，等继续。 */
    val goalPaused: Boolean = false,
    /** 子任务清单进度；null 表示还没有拆解过。 */
    val taskProgress: TaskProgressUi? = null,
    /** 完整清单，供目标详情展开；空表示没拆过。 */
    val taskItems: List<TaskItemUi> = emptyList(),
    /** 目标已经收工：不再注入、不再续跑，等用户重新设定或清除。 */
    val goalCompleted: Boolean = false,
) {
    val canCompactContext: Boolean get() = !isStreaming && messageEdit == null && history.any {
        !it.contextSummary && (it.role == "assistant" || it.role == "tool")
    }
}

/** 一条等待发送的消息。[content] 是用户原始输入，不含任何内部前缀。 */@Immutable
data class QueuedMessageUi(
    val id: String,
    val content: String,
) {
    /** 输入区那一行卡片只展示首行，避免整段提示词把输入框顶掉。 */
    val preview: String
        get() = content.lineSequence().firstOrNull()?.trim()?.take(160).orEmpty()
}

/** 目标拆解进度：界面只需要这两个数，完整清单在会话文件里。 */
@Immutable
data class TaskProgressUi(
    val done: Int,
    val total: Int,
) {
    val label: String get() = "$done/$total"

    val allDone: Boolean get() = total > 0 && done >= total
}

/** 清单里的一条，只给界面渲染用。 */
@Immutable
internal data class TaskItemUi(
    val id: String,
    val title: String,
    val status: io.github.mangi.eta.data.repository.AgentTaskStatus,
    val note: String = "",
)

@Immutable
sealed interface AgentChatMessageUi {
    val id: String
}

@Immutable
data class UserMessageUi(
    override val id: String,
    val content: String,
    val images: List<String> = emptyList(),
    val isEdited: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class AgentMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean = false,
    val renderMarkdown: Boolean = true,
    val usage: TokenUsageUi? = null,
    val characterEditable: Boolean = false,
    val candidateCount: Int = 1,
    val selectedCandidate: Int = 0,
) : AgentChatMessageUi

enum class SystemNoticeCode(val wireValue: String) {
    Stopped("stopped"),
    EmptyResult("empty_result"),
    RuntimeFailed("runtime_failed"),
    ModelRetry("model_retry"),
    ContextCompaction("context_compaction"),
    Interrupted("interrupted");

    companion object {
        fun fromWireValue(value: String): SystemNoticeCode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** Eta 自己生成的消息只保存稳定状态码，展示时再按当前语言解析。 */
@Immutable
data class SystemNoticeMessageUi(
    override val id: String,
    val code: SystemNoticeCode,
    val detail: String? = null,
    val contextTokens: Int? = null,
    /** 仅运行期存在的进行中标记，不随消息持久化；恢复的历史通知始终视为已结束。 */
    val running: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class TokenUsageUi(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null
}

@Immutable
data class ThinkingMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean,
    val elapsedSeconds: Int? = null,
    val collapsed: Boolean = false,
) : AgentChatMessageUi

/**
 * 首页的 Run trace 入口卡片：展示 Agent 当前可调用的能力分组。
 */
@Immutable
data class RunTraceMessageUi(
    override val id: String,
    val capabilities: List<CapabilityUi>,
) : AgentChatMessageUi

@Immutable
data class CapabilityUi(
    val title: String,
    val items: List<String>,
)

/**
 * 工具调用摘要：出现在消息流中，显示当前/最近一步调用了哪些工具。
 */
@Immutable
data class ToolSummaryMessageUi(
    override val id: String,
    val tools: List<String>,
) : AgentChatMessageUi

@Immutable
data class ToolActivityMessageUi(
    override val id: String,
    val toolName: String,
    val status: ToolActivityStatusUi,
    val argumentsSummary: String,
    val command: String? = null,
    val resultSummary: String? = null,
    val imageCount: Int = 0,
) : AgentChatMessageUi

enum class ToolActivityStatusUi {
    Running,
    Success,
    Failed,
    Unknown,
}

/**
 * 建议语 chip 行。
 */
@Immutable
data class SuggestionChipsMessageUi(
    override val id: String,
    val prompts: List<String>,
) : AgentChatMessageUi

@Immutable
data class PendingImageUi(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
)

@Immutable
data class PendingFileReferenceUi(
    val id: String,
    val reference: AgentFileReference,
)

@Immutable
data class MessageEditUiState(
    val targetMessageId: String,
    val previousInput: String,
    val previousImages: List<PendingImageUi>,
    val previousFileReferences: List<PendingFileReferenceUi>,
    val hasLaterTurns: Boolean,
    val preserveFollowingMessages: Boolean = false,
)

/** 角色对话在界面上的身份投影；普通对话返回 null。 */
internal fun AgentChatHomeUiState.roleplayChatUi(): RoleplayChatUi? = roleplay?.let {
    RoleplayChatUi(characterName = it.characterName, avatarPath = it.avatarPath, userName = it.userName)
}
