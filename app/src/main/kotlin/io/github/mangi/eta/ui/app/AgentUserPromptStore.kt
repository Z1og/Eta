package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.skill.ReverseSkillCatalog
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentUserPromptMode
import io.github.mangi.eta.data.repository.AgentUserPromptRepository
import io.github.mangi.eta.ui.model.AgentUserPromptUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 作战协议页的装载、编辑与开关状态。
 *
 * 与 [AgentMemoryStore] 的区别：这里的正文是**用户指令**（注入为独立 system 消息），
 * 不是背景资料；因此档位默认关闭，且文件超限时只读——不做"静默截断后回写"这种会毁文件的事。
 */
internal class AgentUserPromptStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var promptState by mutableStateOf(AgentUserPromptUiState())
        private set

    fun refreshPrompt() {
        promptState = promptState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentUserPromptRepository.snapshot()
                val mode = AgentUserPromptRepository.mode()
                val reverseMode = runCatching {
                    val reverseInstalled = SkillRuntime.createIndexService(appContext)
                        .listSkillsForManagement()
                        .filter { it.installed && it.id in ReverseSkillCatalog.REVERSE_SKILL_IDS }
                    reverseInstalled.isNotEmpty() && reverseInstalled.all { it.enabled }
                }.onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_reverse_state_failed") {
                        "Reverse mode state detection failed: type=${throwable.safeLogType()}"
                    }
                }.getOrDefault(false)
                Triple(snapshot, mode, reverseMode)
            }.fold(
                onSuccess = { (snapshot, mode, reverseMode) ->
                    withContext(Dispatchers.Main) {
                        promptState = AgentUserPromptUiState(
                            isLoading = false,
                            mode = mode,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            sourceFileName = snapshot.fileName,
                            charCount = snapshot.charCount,
                            truncated = snapshot.truncated,
                            reverseModeEnabled = reverseMode,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_ui_load_failed") {
                        "User prompt UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            isLoading = false,
                            notice = throwable.message ?: "读取作战协议失败，请稍后重试。",
                        )
                    }
                },
            )
        }
    }

    fun setMode(mode: AgentUserPromptMode) {
        if (promptState.mode == mode) return
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.setMode(mode) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(mode = mode, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_mode_failed") {
                            "User prompt mode update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(notice = "生效范围保存失败，请稍后重试。")
                        }
                    },
                )
        }
    }

    fun updateDraft(content: String) {
        if (promptState.editingLocked) return
        promptState = promptState.copy(draft = content, notice = null)
    }

    fun savePrompt() {
        if (!promptState.canSave) return
        val target = promptState.draft
        promptState = promptState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (promptState.draft == target) snapshot.content else promptState.draft,
                                sourceFileName = snapshot.fileName,
                                charCount = snapshot.charCount,
                                truncated = snapshot.truncated,
                                notice = "已保存；下个会话生效。",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_ui_save_failed") {
                            "User prompt UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                notice = throwable.message ?: "保存作战协议失败，请稍后重试。",
                            )
                        }
                    },
                )
        }
    }

    fun clearPrompt() {
        if (promptState.isSaving) return
        promptState = promptState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentUserPromptRepository.clearActive() }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                draft = snapshot.content,
                                savedContent = snapshot.content,
                                sourceFileName = snapshot.fileName,
                                charCount = snapshot.charCount,
                                truncated = snapshot.truncated,
                                notice = "已清空（原文件已备份到 prompts/.backup/）。",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_ui_clear_failed") {
                            "User prompt UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isSaving = false,
                                notice = throwable.message ?: "清空作战协议失败，请稍后重试。",
                            )
                        }
                    },
                )
        }
    }

    fun dismissNotice() {
        promptState = promptState.copy(notice = null)
    }
}
