package io.github.mangi.eta.ui.screens.memory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.KnowledgeDocument
import io.github.mangi.eta.data.repository.RetainedRelation
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class MemoryViewMode { Preview, Edit }

/**
 * 记忆页：顶部是开关与预算，下方 MEMORY.md 以“文档”形式占满剩余高度。
 * 默认渲染 Markdown 便于阅读，切到编辑后是无边框的整页编辑区；只有存在未保存修改时才浮出保存栏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    val appContext = LocalContext.current
    val importScope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showMemoryConflictDialog by remember { mutableStateOf(false) }
    var showKnowledgeList by remember { mutableStateOf(false) }
    var showKnowledgeEditor by remember { mutableStateOf(false) }
    var showKnowledgeDelete by remember { mutableStateOf(false) }
    var showRetainedList by remember { mutableStateOf(false) }
    var showRetainedClear by remember { mutableStateOf(false) }
    var selectedRetainedId by remember { mutableStateOf<String?>(null) }
    var selectedRetainedLink by remember { mutableStateOf<RetainedRelation?>(null) }
    var showSemanticModelDialog by remember { mutableStateOf(false) }
    var semanticModelDraft by remember { mutableStateOf("") }
    var syncEndpoint by remember { mutableStateOf("") }
    var syncUsername by remember { mutableStateOf("") }
    var syncPassword by remember { mutableStateOf("") }
    var syncPassphrase by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    var knowledgeImportError by remember { mutableStateOf<String?>(null) }
    val knowledgeImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importScope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        val resolver = appContext.contentResolver
                        val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            } ?: "imported.md"
                        require(filename.endsWith(".md", true) || filename.endsWith(".markdown", true) ||
                            filename.endsWith(".txt", true)) {
                            "仅支持 Markdown 和纯文本文件；PDF/Word 需要专用文档解析功能"
                        }
                        val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(52_000) }
                            ?: error("文件无法读取")
                        require(bytes.size < 52_000 && 0.toByte() !in bytes) { "文件过大或不是纯文本" }
                        val content = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                        require(content.isNotBlank() && content.length <= 12_000) {
                            "单条知识最多 12000 字符"
                        }
                        filename.substringBeforeLast('.').take(160) to content
                    }
                }
                imported.fold(
                    onSuccess = { (title, content) ->
                        knowledgeImportError = null
                        onAction(AgentMemoryAction.SaveKnowledge(
                            title = title, content = content, kind = "reference",
                            source = "Android document import", tags = emptyList(),
                        ))
                    },
                    onFailure = { knowledgeImportError = it.message ?: "导入失败" },
                )
            }
        }
    }
    var editDocument by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var knowledgeTitle by remember { mutableStateOf("") }
    var knowledgeContent by remember { mutableStateOf("") }
    var knowledgeSource by remember { mutableStateOf("") }
    var knowledgeTags by remember { mutableStateOf("") }
    fun openKnowledgeEditor(document: KnowledgeDocument?) {
        editDocument = document
        knowledgeTitle = document?.title.orEmpty()
        knowledgeContent = document?.content.orEmpty()
        knowledgeSource = document?.source.orEmpty()
        knowledgeTags = document?.tags?.joinToString(",").orEmpty()
        showKnowledgeList = false
        showKnowledgeEditor = true
    }
    var mode by rememberSaveable { mutableStateOf<MemoryViewMode?>(null) }
    // 首次读到内容前不决定模式：空记忆直接进入编辑，已有内容先给出可读的预览。
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && mode == null) {
            mode = if (state.savedContent.isBlank()) MemoryViewMode.Edit else MemoryViewMode.Preview
        }
    }
    val currentMode = mode ?: MemoryViewMode.Preview
    val imeVisible = WindowInsets.isImeVisible

    MiuixScaffold(
        title = stringResource(R.string.ui_memory_b55ff5),
        onBack = { onAction(AgentMemoryAction.NavigateBack) },
        actions = {
            IconButton(
                onClick = { showClearDialog = true },
                enabled = !state.isLoading && !state.isSaving && state.draft.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.ui_clear_all_memory_a43bd3),
                    tint = if (!state.isLoading && state.draft.isNotEmpty()) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                )
            }
        },
    ) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = sidePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // 键盘弹出时收起设置区，把有限高度全部让给正在编辑的文档。
            AnimatedVisibility(
                visible = !imeVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                MemorySettingsGroup(
                    state = state, onAction = onAction,
                    onConfigureSync = {
                        syncEndpoint = state.syncEndpoint
                        syncUsername = state.syncUsername
                        syncPassword = ""
                        syncPassphrase = ""
                        showSyncDialog = true
                    },
                    onDisconnectSync = { showDisconnectDialog = true },
                    onOpenKnowledge = { showKnowledgeList = true },
                    onOpenRetained = { showRetainedList = true },
                    onConfigureSemanticModel = {
                        semanticModelDraft = state.semanticModel
                        showSemanticModelDialog = true
                    },
                    onResolveMemoryConflict = { showMemoryConflictDialog = true },
                )
            }
            MemoryDocumentCard(
                state = state,
                mode = currentMode,
                onModeChange = { mode = it },
                onDraftChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding),
            )
            MemorySaveBar(
                state = state,
                onDiscard = { onAction(AgentMemoryAction.DraftChanged(state.savedContent)) },
                onSave = { onAction(AgentMemoryAction.Save) },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showClearDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_clear_all_memory_a43bd3),
            summary = stringResource(R.string.ui_the_entire_contents_of_memory_md_will_be_deleted_and_83a8ac),
            onDismissRequest = { showClearDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_clear),
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    onAction(AgentMemoryAction.Clear)
                },
            )
        }
    }

    EtaWindowDialog(
        show = showSyncDialog,
        title = "WebDAV 加密同步",
        onDismissRequest = { if (!state.syncBusy) showSyncDialog = false },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "填写已有 WebDAV 目录地址（必须 HTTPS），Eta 会在其下建立 eta-sync-v1。不同设备输入相同的加密口令。",
                    style = MiuixTheme.textStyles.body2,
                )
                TextField(
                    value = syncEndpoint,
                    onValueChange = { syncEndpoint = it },
                    label = "WebDAV 目录 URL",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = syncUsername,
                    onValueChange = { syncUsername = it },
                    label = "用户名（可空）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = syncPassword,
                    onValueChange = { syncPassword = it },
                    label = if (state.syncHasPassword) "WebDAV 密码（留空沿用已保存）" else "WebDAV 密码",
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = syncPassphrase,
                    onValueChange = { syncPassphrase = it },
                    label = if (state.syncHasPassphrase) "加密口令（留空沿用已保存）" else "加密口令（至少 12 字符）",
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 12.dp),
                confirmText = "保存连接",
                confirmEnabled = syncEndpoint.isNotBlank() && !state.syncBusy &&
                    (state.syncHasPassphrase || syncPassphrase.length >= 12),
                onCancel = { showSyncDialog = false },
                onConfirm = {
                    onAction(AgentMemoryAction.SaveWebDav(
                        syncEndpoint, syncUsername, syncPassword, syncPassphrase,
                    ))
                    syncPassword = ""
                    syncPassphrase = ""
                    showSyncDialog = false
                },
            )
        }
    }

    EtaWindowDialog(
        show = showDisconnectDialog,
        title = "断开 WebDAV 同步",
        summary = "移除本机地址和密钥，不删除本地知识与远端加密文件。",
        onDismissRequest = { showDisconnectDialog = false },
    ) {
        MiuixDialogActions(
            confirmText = "断开",
            destructive = true,
            onCancel = { showDisconnectDialog = false },
            onConfirm = {
                showDisconnectDialog = false
                onAction(AgentMemoryAction.ClearWebDavConfig)
            },
        )
    }

    EtaWindowDialog(
        show = showMemoryConflictDialog,
        title = "确认解决记忆冲突",
        onDismissRequest = { showMemoryConflictDialog = false },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 510.dp)) {
            Text(
                "请先审查远端记忆，与本机 MEMORY.md 合并并保存。确认后会重新检查远端是否改变，再使用本机版本解决冲突；知识库和任务经验仍会合并。",
                style = MiuixTheme.textStyles.body2,
            )
            state.syncRemoteConflictContent?.let { remoteText ->
                Text(
                    "远端 MEMORY.md（预览）：",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MiuixTheme.textStyles.body1,
                )
                Box(
                    modifier = Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    StaticMarkdown(
                        content = remoteText.take(12_000),
                        tone = MarkdownTone.Answer,
                    )
                }
                EtaTextButton(
                    text = "复制远端记忆全文",
                    onClick = {
                        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Eta remote memory", remoteText))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 10.dp),
                confirmText = "已审查，使用本机版本",
                confirmEnabled = state.syncRemoteConflictContent != null &&
                    !state.hasUnsavedChanges && !state.syncBusy,
                onCancel = { showMemoryConflictDialog = false },
                onConfirm = {
                    showMemoryConflictDialog = false
                    onAction(AgentMemoryAction.ConfirmWebDavMemoryResolution)
                },
            )
        }
    }

    EtaWindowDialog(
        show = showKnowledgeList,
        title = "本地知识库",
        onDismissRequest = { showKnowledgeList = false },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = "搜索标题、来源或标签",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                state.knowledgeDocuments.filter {
                    searchText.isBlank() || it.title.contains(searchText, true) ||
                        it.source.contains(searchText, true) ||
                        it.content.contains(searchText, true) ||
                        it.tags.any { tag -> tag.contains(searchText, true) }
                }.take(50).forEach { document ->
                    EtaPreference(
                        title = document.title,
                        summary = "${document.kind} · ${document.source.take(80)}",
                        onClick = { openKnowledgeEditor(document) },
                    )
                    EtaPreferenceDivider(hasLeading = false)
                }
            }
            knowledgeImportError?.let { message ->
                Text(message, style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error)
            }
            EtaTextButton(
                text = "导入 Markdown / TXT",
                onClick = {
                    knowledgeImportError = null
                    knowledgeImporter.launch(arrayOf("text/*", "application/octet-stream"))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                modifier = Modifier.padding(top = 12.dp),
                confirmText = "新建知识",
                onCancel = { showKnowledgeList = false },
                onConfirm = { openKnowledgeEditor(null) },
            )
        }
    }

    EtaWindowDialog(
        show = showRetainedList,
        title = "结构化长期记忆",
        onDismissRequest = { showRetainedList = false },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Text(
                "分层存储由模型提取的用户陈述，证据未经独立核实；关联仅为未确认建议。",
                style = MiuixTheme.textStyles.body2,
            )
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                state.retainedNodes.forEach { node ->
                    EtaPreference(
                        title = node.text.take(85),
                        summary = (if (node.active) "有效" else "已被后续陈述替代") +
                            " · ${node.layer} · ${node.category} · 关联建议 ${node.links.size} 条",
                        onClick = {
                            selectedRetainedId = node.id
                            showRetainedList = false
                        },
                    )
                    EtaPreferenceDivider(hasLeading = false)
                }
            }
            MiuixDialogActions(
                confirmText = "清空结构化记忆",
                confirmEnabled = state.retainedNodes.isNotEmpty(),
                destructive = true,
                onCancel = { showRetainedList = false },
                onConfirm = {
                    showRetainedList = false
                    showRetainedClear = true
                },
            )
        }
    }

    state.retainedNodes.find { it.id == selectedRetainedId }?.let { node ->
        EtaWindowDialog(
            show = selectedRetainedId != null && selectedRetainedLink == null,
            title = "记忆节点详情",
            onDismissRequest = { selectedRetainedId = null; showRetainedList = true },
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(node.text, style = MiuixTheme.textStyles.body1)
                    Text(
                        "状态：${if (node.active) "有效" else "历史版本（已失效）"}\n" +
                            "层级：${node.layer} / ${node.category}\n" +
                            "范围：${node.scope}\n主体：${node.subject}\n" +
                            "用户证据：${node.evidence}\n" +
                            "实体：${node.entities.joinToString("、")}\n" +
                            "关联边：${node.links.size} 条（已确认关系允许两跳扩展，" +
                            "未审核建议仅探索一跳）",
                        style = MiuixTheme.textStyles.body2,
                    )
                    node.links.forEach { link ->
                        val related = state.retainedNodes.find { it.id == link.targetId }
                        EtaPreference(
                            title = "${link.type} → ${related?.text?.take(45) ?: "关联节点已失效"}",
                            summary = "${when(link.status) {
                                "confirmed" -> "已确认"
                                "rejected" -> "已拒绝"
                                else -> "待审核"
                            }} · 置信度 ${"%.2f".format(link.confidence)} · ${link.reason.take(75)}",
                            onClick = { selectedRetainedLink = link },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                    }
                }
                MiuixDialogActions(
                    modifier = Modifier.padding(top = 12.dp),
                    confirmText = "删除此条记忆",
                    destructive = true,
                    onCancel = { selectedRetainedId = null; showRetainedList = true },
                    onConfirm = {
                        onAction(AgentMemoryAction.DeleteRetainedMemory(node.id))
                        selectedRetainedId = null
                        showRetainedList = true
                    },
                )
            }
        }
    }

    selectedRetainedLink?.let { relation ->
        EtaWindowDialog(
            show = true,
            title = "审核网状记忆关联",
            summary = "${relation.type} / 模型置信度 ${"%.2f".format(relation.confidence)}\n" +
                "理由：${relation.reason.ifBlank { "由早期 Retain 自动提出；暂无解释" }}\n" +
                "目标：${state.retainedNodes.find { it.id == relation.targetId }?.text ?: "关联节点不可用"}",
            onDismissRequest = { selectedRetainedLink = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EtaTextButton(
                    text = "确认关联（可用于两跳扩展）",
                    onClick = {
                        selectedRetainedId?.let { id ->
                            onAction(AgentMemoryAction.ReviewMemoryLink(
                                id, relation.targetId, relation.type, "confirmed",
                            ))
                        }
                        selectedRetainedLink = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = "拒绝关联（之后不参与召回）",
                    onClick = {
                        selectedRetainedId?.let { id ->
                            onAction(AgentMemoryAction.ReviewMemoryLink(
                                id, relation.targetId, relation.type, "rejected",
                            ))
                        }
                        selectedRetainedLink = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    EtaWindowDialog(
        show = showSemanticModelDialog,
        title = "嵌入模型设置",
        onDismissRequest = { showSemanticModelDialog = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "只支持选定服务商的 HTTPS OpenAI 兼容 Embeddings 接口。" +
                    "不使用聊天模型名；向量保存在本机。修改模型后需要重建索引。",
                style = MiuixTheme.textStyles.body2,
            )
            TextField(
                value = semanticModelDraft,
                onValueChange = { semanticModelDraft = it },
                label = "Embedding 模型标识",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            MiuixDialogActions(
                modifier = Modifier.padding(top = 10.dp),
                confirmText = "保存模型",
                confirmEnabled = semanticModelDraft.matches(Regex("[a-zA-Z0-9._:/-]{1,100}")),
                onCancel = { showSemanticModelDialog = false },
                onConfirm = {
                    onAction(AgentMemoryAction.SaveSemanticModel(semanticModelDraft.trim()))
                    showSemanticModelDialog = false
                },
            )
        }
    }

    EtaWindowDialog(
        show = showRetainedClear,
        title = "确认清空结构化记忆",
        summary = "清空会同步到其他设备，但不会删除 MEMORY.md、任务经验或知识库。",
        onDismissRequest = { showRetainedClear = false },
    ) {
        MiuixDialogActions(
            confirmText = "全部清空",
            destructive = true,
            onCancel = { showRetainedClear = false; showRetainedList = true },
            onConfirm = {
                showRetainedClear = false
                onAction(AgentMemoryAction.ClearRetainedMemory)
            },
        )
    }

    EtaWindowDialog(
        show = showKnowledgeEditor,
        title = if (editDocument == null) "新增知识" else "编辑知识",
        onDismissRequest = { showKnowledgeEditor = false },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextField(
                    value = knowledgeTitle,
                    onValueChange = { knowledgeTitle = it },
                    label = "标题",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = knowledgeContent,
                    onValueChange = { knowledgeContent = it },
                    label = "Markdown 内容",
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = knowledgeSource,
                    onValueChange = { knowledgeSource = it },
                    label = "来源（网址、文件或用户提供）",
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = knowledgeTags,
                    onValueChange = { knowledgeTags = it },
                    label = "标签（逗号分隔）",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editDocument != null) {
                    EtaPreference(
                        title = "删除此条知识",
                        summary = "删除操作会同步到其他设备",
                        onClick = { showKnowledgeDelete = true },
                    )
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 12.dp),
                confirmText = "保存",
                confirmEnabled = knowledgeTitle.isNotBlank() &&
                    knowledgeContent.isNotBlank() && knowledgeContent.length <= 12_000,
                onCancel = { showKnowledgeEditor = false },
                onConfirm = {
                    onAction(AgentMemoryAction.SaveKnowledge(
                        title = knowledgeTitle,
                        content = knowledgeContent,
                        kind = editDocument?.kind ?: "reference",
                        source = knowledgeSource,
                        tags = knowledgeTags.split(',').map(String::trim)
                            .filter(String::isNotBlank).take(12),
                        id = editDocument?.id,
                        revision = editDocument?.revision,
                    ))
                    showKnowledgeEditor = false
                },
            )
        }
    }

    EtaWindowDialog(
        show = showKnowledgeDelete,
        title = "删除知识条目",
        summary = "确认后会标记删除，之后同步到其他设备。",
        onDismissRequest = { showKnowledgeDelete = false },
    ) {
        MiuixDialogActions(
            confirmText = "删除",
            destructive = true,
            onCancel = { showKnowledgeDelete = false },
            onConfirm = {
                editDocument?.let {
                    onAction(AgentMemoryAction.DeleteKnowledge(it.id, it.revision))
                }
                showKnowledgeDelete = false
                showKnowledgeEditor = false
            },
        )
    }

    state.notice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_memory_b55ff5),
            summary = notice,
            onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
        ) {
            EtaTextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { onAction(AgentMemoryAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MemorySettingsGroup(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
    onConfigureSync: () -> Unit,
    onDisconnectSync: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenRetained: () -> Unit,
    onConfigureSemanticModel: () -> Unit,
    onResolveMemoryConflict: () -> Unit,
) {
    EtaPreferenceGroup(
        modifier = Modifier
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(top = 8.dp, bottom = EtaPreferenceDefaults.GroupSpacing)
            .heightIn(max = 310.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        EtaSwitchPreference(
            title = stringResource(R.string.ui_enable_memory_4b69b7),
            summary = stringResource(R.string.ui_after_closing_no_memory_will_be_injected_and_the_mod_db3d23),
            checked = state.enabled,
            enabled = !state.isLoading,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaSwitchPreference(
            title = "自动积累任务经验",
            summary = "仅记录任务类别、工具名称和错误码；不存聊天原文、截图或工具参数，默认关闭。",
            checked = state.autoExperienceEnabled,
            enabled = state.enabled && !state.isLoading,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleAutoExperience(it)) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaSwitchPreference(
            title = "智能记住 · Retain",
            summary = "独立调用当前模型提取有证据的长期记忆；增加模型调用耗时与费用，默认关闭。",
            checked = state.smartRetainEnabled,
            enabled = state.enabled && !state.isLoading,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleSmartRetain(it)) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = "结构化记忆（${state.retainedNodes.size} 条）",
            summary = "查看语义事实、任务经历、步骤和模型关联建议，支持逐条删除",
            enabled = !state.isLoading,
            onClick = onOpenRetained,
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaSwitchPreference(
            title = "聊天模型语义检索",
            summary = "直接使用已配置的聊天模型语义排序；包括 MEMORY.md、知识库和 Retain 节点，无需单独 Embeddings 模型",
            checked = state.semanticMemoryEnabled,
            enabled = state.enabled && !state.isLoading,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleSemanticRecall(it)) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = "语义检索状态",
            summary = state.semanticRetrievalReport ?: (
                "MEMORY.md 可检索片段 ${state.semanticCoreChunks} 条，结构化记忆 " +
                    "${state.retainedNodes.count { it.active }} 条；还没有检索记录"
                ),
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = if (state.chatRetrievalTestBusy) "正在测试聊天模型…" else "测试当前聊天模型检索",
            summary = state.chatRetrievalTestStatus
                ?: "直接向当前聊天模型请求语义排序，不用配置 Embeddings 模型",
            enabled = state.semanticMemoryEnabled && !state.chatRetrievalTestBusy,
            onClick = { onAction(AgentMemoryAction.TestChatSemanticRetrieval) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaSwitchPreference(
            title = "高级：独立 Embeddings 辅助",
            summary = "默认关闭；仅当当前 Provider 确实提供 Embeddings API 时开启，额外产生模型请求",
            checked = state.semanticUseEmbeddings,
            enabled = state.semanticMemoryEnabled && state.enabled && !state.isLoading,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleEmbeddingAccelerator(it)) },
        )
        if (state.semanticUseEmbeddings) {
            EtaPreferenceDivider(hasLeading = false)
            EtaPreference(
                title = "嵌入模型：${state.semanticModel}",
                summary = "单独指定支持 Embeddings 的模型，与聊天模型并不相同",
                onClick = onConfigureSemanticModel,
            )
            EtaPreferenceDivider(hasLeading = false)
            EtaPreference(
                title = if (state.embeddingTestBusy) "正在测试接口…" else "测试 Embeddings 接口",
                summary = state.embeddingTestStatus ?: "点击实际请求当前 Provider 的 Embeddings API，返回向量维度或错误",
                enabled = !state.embeddingTestBusy,
                onClick = { onAction(AgentMemoryAction.TestEmbeddingProvider) },
            )
            EtaPreferenceDivider(hasLeading = false)
            EtaPreference(
                title = if (state.semanticIndexBusy) "正在生成向量…" else "建立 / 修复本地向量索引",
                summary = state.semanticIndexStatus ?: (
                    "结构化记忆 ${state.retainedNodes.count { it.active }} 条；已建立 ${state.semanticIndexedNodes} 条。" +
                        if (state.retainedNodes.none { it.active }) {
                            "当前无需建立索引，聊天模型仍可检索 MEMORY.md"
                        } else "点击建立索引"
                    ),
                enabled = state.semanticMemoryEnabled && !state.semanticIndexBusy &&
                    state.retainedNodes.any { it.active },
                onClick = { onAction(AgentMemoryAction.BuildSemanticIndex) },
            )
        }
        EtaPreferenceDivider(hasLeading = false)
        EtaSwitchPreference(
            title = "网状关联召回",
            summary = "模型生成候选关联；人工确认的边最多扩展两跳，未审核边仅做一跳探索",
            checked = state.graphMemoryEnabled,
            enabled = state.enabled && !state.isLoading,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleGraphRecall(it)) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = if (state.graphClassifierBusy) "正在分析关联…" else "模型整理已有记忆关联",
            summary = state.graphClassifierStatus ?: "重新分类最近最多 12 条记忆（额外模型调用，需人工审核）",
            enabled = state.graphMemoryEnabled && !state.graphClassifierBusy && state.retainedNodes.isNotEmpty(),
            onClick = { onAction(AgentMemoryAction.ClassifyExistingRelations) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = "知识库（${state.knowledgeDocuments.size} 条）",
            summary = "搜索、查看和编辑本机保存的知识条目",
            enabled = !state.isLoading,
            onClick = onOpenKnowledge,
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = "WebDAV 加密同步",
            summary = state.syncEndpoint.ifBlank { "未配置 · 可连接任意 HTTPS WebDAV 服务端" },
            enabled = !state.isLoading,
            onClick = onConfigureSync,
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = if (state.syncBusy) "正在同步…" else "立即同步知识与记忆",
            summary = state.syncStatus ?: "跨设备合并知识、核心记忆及自动任务经验",
            enabled = state.syncHasPassphrase && !state.syncBusy,
            onClick = { onAction(AgentMemoryAction.RunWebDavSync) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaSwitchPreference(
            title = "后台自动同步",
            summary = "由 Android 安排约每 15 分钟一次；确切时间受省电影响",
            checked = state.syncAutoEnabled,
            enabled = state.syncHasPassphrase && !state.syncBusy,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleWebDavAutoSync(it)) },
        )
        if (state.syncMemoryConflict) {
            EtaPreferenceDivider(hasLeading = false)
            EtaPreference(
                title = "解决 MEMORY.md 多端冲突",
                summary = "审查远端冲突副本并保存本机合并内容后，确认使用本机版本",
                enabled = !state.syncBusy,
                onClick = onResolveMemoryConflict,
            )
        }
        if (state.syncEndpoint.isNotBlank()) {
            EtaPreferenceDivider(hasLeading = false)
            EtaPreference(
                title = "断开 WebDAV 连接",
                summary = "仅清除本机连接配置，不删除远端文件",
                enabled = !state.syncBusy,
                onClick = onDisconnectSync,
            )
        }
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = "已保存任务经验：${state.experienceCount} 条",
            summary = "点击清空自动经验（不会清除 MEMORY.md）",
            onClick = { onAction(AgentMemoryAction.ClearExperiences) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = stringResource(R.string.ui_core_memory_injection_budget_48b5d5),
            summary = stringResource(R.string.memory_budget_summary, formatNumber(state.coreBudgetChars)),
        )
    }
}

/** 文档卡片：标题栏承载文件名、体积与预览/编辑切换，正文区占满卡片剩余高度并独立滚动。 */
@Composable
private fun MemoryDocumentCard(
    state: AgentMemoryUiState,
    mode: MemoryViewMode,
    onModeChange: (MemoryViewMode) -> Unit,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val overLimit = state.draftBytes > state.maxBytes
    EtaCard(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Notes,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.primary,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MEMORY.md",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = "${formatBytes(state.draftBytes)} / ${formatBytes(state.maxBytes)}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (overLimit) colors.error else colors.onSurfaceVariantSummary,
                )
            }
            MemoryModeSwitch(mode = mode, enabled = !state.isLoading, onModeChange = onModeChange)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.dividerLine),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
                else -> Crossfade(targetState = mode, label = "memory_mode") { current ->
                    when (current) {
                        MemoryViewMode.Preview -> MemoryPreview(
                            content = state.draft,
                            onStartEditing = { onModeChange(MemoryViewMode.Edit) },
                        )
                        MemoryViewMode.Edit -> MemoryEditor(
                            value = state.draft,
                            enabled = !state.isSaving,
                            onValueChange = onDraftChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryPreview(content: String, onStartEditing: () -> Unit) {
    if (content.isBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onStartEditing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.memory_empty_title),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        StaticMarkdown(content = content, tone = MarkdownTone.Answer, modifier = Modifier.fillMaxWidth())
    }
}

/** 无边框整页编辑：与卡片同色，不再嵌一层灰色输入框；点到空白处也能把光标落进正文。 */
@Composable
private fun MemoryEditor(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9),
                style = MemoryTextStyle(),
                color = colors.onSurfaceVariantSummary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MemoryTextStyle().copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun MemoryTextStyle() = MiuixTheme.textStyles.body2.copy(lineHeight = 22.sp)

/** 轻量分段切换：两个选项常驻可见，选中项用浮起的胶囊表示，比整行 TabRow 更贴合卡片标题栏。 */
@Composable
private fun MemoryModeSwitch(
    mode: MemoryViewMode,
    enabled: Boolean,
    onModeChange: (MemoryViewMode) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .squircleSurface(color = colors.surfaceContainerHigh, cornerRadius = 12.dp)
            .padding(3.dp),
    ) {
        MemoryViewMode.entries.forEach { option ->
            val selected = option == mode
            val background by animateColorAsState(
                if (selected) colors.surface else colors.surfaceContainerHigh,
                label = "memory_mode_tab",
            )
            Box(
                modifier = Modifier
                    .squircleSurface(color = background, cornerRadius = 9.dp)
                    .clickable(enabled = enabled && !selected) { onModeChange(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (option == MemoryViewMode.Preview) R.string.memory_mode_preview else R.string.memory_mode_edit,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) colors.onSurface else colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 有未保存修改或超限时才出现：左侧说明状态，右侧放弃与保存，避免常驻一个灰掉的保存按钮。 */
@Composable
private fun MemorySaveBar(
    state: AgentMemoryUiState,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val overLimit = state.draftBytes > state.maxBytes
    AnimatedVisibility(
        visible = !state.isLoading && (state.hasUnsavedChanges || state.isSaving),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(if (overLimit) R.string.memory_over_limit else R.string.memory_unsaved_changes),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            EtaTextButton(
                text = stringResource(R.string.memory_discard),
                enabled = !state.isSaving,
                onClick = onDiscard,
                minHeight = 40.dp,
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
            EtaTextButton(
                text = stringResource(if (state.isSaving) R.string.memory_saving else R.string.memory_save),
                enabled = state.canSave,
                onClick = onSave,
                minHeight = 40.dp,
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    bytes % (1_024 * 1_024) == 0 -> "${bytes / (1_024 * 1_024)} MiB"
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)
