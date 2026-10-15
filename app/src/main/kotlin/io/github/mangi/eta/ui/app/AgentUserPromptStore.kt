package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.skill.ReverseSkillCatalog
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentUserPromptException
import io.github.mangi.eta.data.repository.AgentUserPromptMode
import io.github.mangi.eta.data.repository.AgentUserPromptRepository
import io.github.mangi.eta.data.repository.AgentUserPromptSnapshot
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentUserPromptUiState
import io.github.mangi.eta.ui.model.PendingImport
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本文件的类名叫 AgentUserPromptStore，与 data.repository 下同名类冲突，故常量走全限定名、不做 import。 */
private val PROMPT_MAX_FILE_BYTES =
    io.github.mangi.eta.data.repository.AgentUserPromptStore.MAX_FILE_BYTES

/**
 * 作战协议页的装载、编辑、导入与开关状态。
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

    /**
     * 待确认导入的正文。刻意不放 [AgentUserPromptUiState]：
     * 整份提示词可能有 8 万字符，进 Compose 状态会让每次重组都复制一遍。
     */
    private var pendingImportContent: String? = null

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
                val contextWindow = runCatching {
                    RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                }.onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_context_window_failed") {
                        "Context window lookup failed: type=${throwable.safeLogType()}"
                    }
                }.getOrNull()
                Loaded(snapshot, mode, reverseMode, contextWindow)
            }.fold(
                onSuccess = { loaded ->
                    pendingImportContent = null
                    withContext(Dispatchers.Main) {
                        promptState = AgentUserPromptUiState(
                            isLoading = false,
                            mode = loaded.mode,
                            draft = loaded.snapshot.content,
                            savedContent = loaded.snapshot.content,
                            sourceFileName = loaded.snapshot.fileName,
                            charCount = loaded.snapshot.charCount,
                            truncated = loaded.snapshot.truncated,
                            reverseModeEnabled = loaded.reverseMode,
                            contextWindow = loaded.contextWindow,
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
                                editorExpanded = false,
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
                                editorExpanded = false,
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

    /** 内容太长被降级成只读预览后，用户显式要求把全文放进编辑区。 */
    fun openEditor() {
        if (promptState.editingLocked) return
        promptState = promptState.copy(editorExpanded = true)
    }

    /**
     * 读入所选文件并停在待确认状态——**不直接落盘**。
     * 落盘写的是 8 万字符，先让用户看清文件名与体积（以及是否超限）再确认，是唯一可回退的时机。
     */
    fun prepareImport(uri: Uri) {
        if (promptState.isSaving || promptState.isLoading) return
        promptState = promptState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { readImport(uri) }
                .fold(
                    onSuccess = { (fileName, text, byteSize) ->
                        pendingImportContent = text
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isLoading = false,
                                pendingImport = PendingImport(
                                    fileName = fileName,
                                    charCount = text.length,
                                    byteSize = byteSize,
                                ),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("user_prompt_import_read_failed") {
                            "User prompt import read failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            promptState = promptState.copy(
                                isLoading = false,
                                notice = throwable.message ?: "导入失败，请换一个文件重试。",
                            )
                        }
                    },
                )
        }
    }

    fun confirmImport() {
        val content = pendingImportContent ?: return
        val pending = promptState.pendingImport ?: return
        if (promptState.isSaving) return
        promptState = promptState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                // 先走清空路径：已有文件会被备份到 prompts/.backup/，再写入导入的正文——
                // 导入是"替换生效文件"，不能把用户原来那份悄悄删掉。
                AgentUserPromptRepository.clearActive()
                AgentUserPromptRepository.replaceAll(content)
            }.fold(
                onSuccess = { snapshot ->
                    pendingImportContent = null
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            isSaving = false,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            sourceFileName = snapshot.fileName,
                            charCount = snapshot.charCount,
                            truncated = snapshot.truncated,
                            editorExpanded = false,
                            pendingImport = null,
                            notice = if (snapshot.truncated) {
                                "已导入 ${pending.fileName}，但超出上限被截断，请精简后重新导入。"
                            } else {
                                "已导入 ${pending.fileName}（${pending.charCount} 字符）；下个会话生效。"
                            },
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("user_prompt_ui_import_failed") {
                        "User prompt UI import failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        promptState = promptState.copy(
                            isSaving = false,
                            pendingImport = null,
                            notice = throwable.message ?: "导入作战协议失败，请稍后重试。",
                        )
                    }
                },
            )
        }
    }

    fun cancelImport() {
        pendingImportContent = null
        promptState = promptState.copy(pendingImport = null)
    }

    /**
     * 读取所选文件。三重拒绝：超字节上限、含 NUL 的二进制、读出来是空白。
     * 换行统一成 LF 并剥掉 UTF-8 BOM——否则 BOM 会作为正文第一个字符被注入。
     */
    private fun readImport(uri: Uri): Triple<String, String, Int> {
        val resolver = appContext.contentResolver
        val fileName = resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
            ?: "导入的协议.md"

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                if (buffer.size() + read > PROMPT_MAX_FILE_BYTES) {
                    throw AgentUserPromptException(
                        code = "PROMPT_TOO_LARGE",
                        message = "文件超过 ${PROMPT_MAX_FILE_BYTES / 1024} KiB，拒绝导入",
                    )
                }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: throw AgentUserPromptException(
            code = "PROMPT_IMPORT_FAILED",
            message = "无法读取所选文件",
        )

        if (bytes.isEmpty()) {
            throw AgentUserPromptException(code = "PROMPT_IMPORT_FAILED", message = "所选文件是空的")
        }
        if (bytes.any { it == 0.toByte() }) {
            throw AgentUserPromptException(code = "PROMPT_IMPORT_FAILED", message = "所选文件不是文本文件")
        }
        val text = bytes.toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (text.isBlank()) {
            throw AgentUserPromptException(code = "PROMPT_IMPORT_FAILED", message = "所选文件没有可注入的正文")
        }
        return Triple(fileName, text, bytes.size)
    }

    private data class Loaded(
        val snapshot: AgentUserPromptSnapshot,
        val mode: AgentUserPromptMode,
        val reverseMode: Boolean,
        val contextWindow: Int?,
    )
}
