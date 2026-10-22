package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.AgentMemoryRelationClassifier
import io.github.mangi.eta.agent.memory.AgentChatMemoryCandidates
import io.github.mangi.eta.agent.memory.AgentChatMemoryReranker
import io.github.mangi.eta.agent.memory.AgentMemoryRetrievalHealthStore
import io.github.mangi.eta.agent.memory.AgentMemoryRetrievalReport
import io.github.mangi.eta.agent.memory.EtaOpenAiEmbeddingProvider
import io.github.mangi.eta.agent.memory.AgentSemanticMemorySearch
import io.github.mangi.eta.agent.memory.EtaEmbeddingEndpoint
import io.github.mangi.eta.agent.memory.EtaMemoryVectorIndex
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentExperienceRepository
import io.github.mangi.eta.data.repository.AgentKnowledgeRepository
import io.github.mangi.eta.data.repository.AgentRetainedMemoryRepository
import io.github.mangi.eta.data.repository.EtaWebDavSyncRepository
import io.github.mangi.eta.data.repository.EtaWebDavSyncScheduler
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                Triple(snapshot, enabled, AgentMemoryContextBuilder.coreBudgetChars(contextWindow))
            }.fold(
                onSuccess = { (snapshot, enabled, coreBudget) ->
                    val autoExperienceEnabled = SettingsDataStore.autoExperienceEnabled()
                    val smartRetainEnabled = SettingsDataStore.smartRetainEnabled()
                    val semanticEnabled = SettingsDataStore.semanticMemoryEnabled()
                    val embeddingEnabled = SettingsDataStore.semanticMemoryUseEmbeddings()
                    val semanticModel = SettingsDataStore.semanticMemoryModel()
                    val graphEnabled = SettingsDataStore.graphMemoryEnabled()
                    val experienceCount = AgentExperienceRepository.count()
                    val retainedNodes = AgentRetainedMemoryRepository.visible(400)
                    val activeNodes = retainedNodes.filter { it.active }
                    val knowledgeDocuments = AgentKnowledgeRepository.snapshot().filter { !it.deleted }
                    val coreChunks = AgentChatMemoryCandidates.coreChunks(snapshot.content).size
                    val report = runCatching {
                        AgentMemoryRetrievalHealthStore(appContext.filesDir).read()?.shortSummary()
                    }.getOrNull()
                    val indexed = runCatching {
                        val config = RuntimeConfigRepository.currentRuntimeConfig()
                        if (config != null) {
                            val endpoint = EtaEmbeddingEndpoint.fromConfig(config, semanticModel)
                            EtaMemoryVectorIndex(appContext.filesDir).readValid(
                                EtaMemoryVectorIndex.fingerprint(endpoint), activeNodes,
                            ).vectors.size
                        } else 0
                    }.getOrDefault(0)
                    val syncConfig = EtaWebDavSyncRepository.config()
                    val autoSync = SettingsDataStore.autoWebDavSyncEnabled()
                    val memoryConflict = EtaWebDavSyncRepository.hasMemoryConflict()
                    val remoteConflictContent = if (memoryConflict) {
                        EtaWebDavSyncRepository.remoteConflictContent()
                    } else null
                    withContext(Dispatchers.Main) {
                        memoryState = AgentMemoryUiState(
                            enabled = enabled,
                            autoExperienceEnabled = autoExperienceEnabled,
                            smartRetainEnabled = smartRetainEnabled,
                            semanticMemoryEnabled = semanticEnabled,
                            semanticUseEmbeddings = embeddingEnabled,
                            semanticModel = semanticModel,
                            semanticCoreChunks = coreChunks,
                            semanticIndexedNodes = indexed,
                            semanticRetrievalReport = report,
                            graphMemoryEnabled = graphEnabled,
                            retainedNodes = retainedNodes,
                            experienceCount = experienceCount,
                            knowledgeDocuments = knowledgeDocuments,
                            syncEndpoint = syncConfig.endpoint,
                            syncUsername = syncConfig.username,
                            syncHasPassword = syncConfig.hasPassword,
                            syncHasPassphrase = syncConfig.hasPassphrase,
                            syncAutoEnabled = autoSync,
                            syncMemoryConflict = memoryConflict,
                            syncRemoteConflictContent = remoteConflictContent,
                            isLoading = false,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            draftBytes = snapshot.byteSize,
                            coreBudgetChars = coreBudget,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun setAutoExperienceEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setAutoExperienceEnabled(enabled) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(autoExperienceEnabled = enabled)
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = "无法保存自动经验记忆开关")
                    }
                }
        }
    }

    fun clearExperiences() {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentExperienceRepository.clear() }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(experienceCount = 0, notice = "任务经验已清空")
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = "无法清空任务经验")
                    }
                }
        }
    }

    fun setSmartRetainEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setSmartRetainEnabled(enabled) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(smartRetainEnabled = enabled)
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = "无法保存智能记忆开关")
                    }
                }
        }
    }

    fun setSemanticMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setSemanticMemoryEnabled(enabled) }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(semanticMemoryEnabled = enabled)
                    }
                },
                onFailure = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = "无法更新语义召回开关")
                    }
                },
            )
        }
    }

    fun testChatSemanticRetrieval() {
        if (memoryState.chatRetrievalTestBusy) return
        memoryState = memoryState.copy(
            chatRetrievalTestBusy = true,
            chatRetrievalTestStatus = "正在调用当前聊天模型测试语义检索…",
        )
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val config = RuntimeConfigRepository.currentRuntimeConfig()
                    ?: error("请先选择可用的聊天模型")
                val core = AgentMemoryRepository.snapshot().content
                val nodes = AgentRetainedMemoryRepository.active(400)
                val docs = AgentKnowledgeRepository.snapshot().filter { !it.deleted }
                val query = "我之前保存了哪些项目、长期偏好和重要资料"
                val chunks = AgentChatMemoryCandidates.coreChunks(core).size
                val candidates = AgentChatMemoryCandidates.select(query, core, docs, nodes)
                if (candidates.isEmpty()) error("MEMORY.md、知识库和结构化记忆都是空的")
                val selection = AgentChatMemoryReranker().rerank(
                    query, candidates, config, AgentRunController(),
                )
                val health = AgentMemoryRetrievalReport(
                    "chat", candidates.size, selection.selected.size,
                    chunks, nodes.size, "手动测试成功",
                )
                AgentMemoryRetrievalHealthStore(appContext.filesDir).write(health)
                val message = "当前聊天模型可用；筛选 ${candidates.size} 条候选、命中 ${selection.selected.size} 条。" +
                    (if (selection.selected.isEmpty()) {
                        "测试问题没有匹配结果，可换一个具体问题在会话中验证"
                    } else " 来源：${selection.selected.map { it.source }.distinct().joinToString("、")}")
                message to health.shortSummary()
            }
            withContext(Dispatchers.Main) {
                memoryState = memoryState.copy(
                    chatRetrievalTestBusy = false,
                    chatRetrievalTestStatus = result.fold(
                        onSuccess = { it.first },
                        onFailure = { "当前模型检索失败：${it.message ?: it.javaClass.simpleName}" },
                    ),
                    semanticRetrievalReport = result.getOrNull()?.second
                        ?: memoryState.semanticRetrievalReport,
                )
            }
        }
    }

    fun setEmbeddingAcceleratorEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setSemanticMemoryUseEmbeddings(enabled) }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            semanticUseEmbeddings = enabled,
                            embeddingTestStatus = if (enabled) {
                                "尚未验证 Embeddings 接口，请先点击测试"
                            } else "使用当前聊天模型检索；已关闭独立向量接口",
                        )
                    }
                },
                onFailure = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = "无法保存嵌入模型选项")
                    }
                },
            )
        }
    }

    fun testEmbeddingProvider() {
        if (memoryState.embeddingTestBusy) return
        memoryState = memoryState.copy(
            embeddingTestBusy = true,
            embeddingTestStatus = "正在真实请求 Embeddings API…",
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val config = RuntimeConfigRepository.currentRuntimeConfig()
                    ?: error("未配置当前模型 Provider")
                val endpoint = EtaEmbeddingEndpoint.fromConfig(
                    config, SettingsDataStore.semanticMemoryModel(),
                )
                val embedding = EtaOpenAiEmbeddingProvider(endpoint)
                    .embed(listOf("Eta embedding compatibility test"), AgentRunController())
                "连接成功，向量维度 ${embedding.first().size}；当前 Provider 支持 ${endpoint.model}"
            }
            withContext(Dispatchers.Main) {
                memoryState = memoryState.copy(
                    embeddingTestBusy = false,
                    embeddingTestStatus = outcome.fold(
                        onSuccess = { it },
                        onFailure = {
                            "不可用：${it.message ?: it.javaClass.simpleName}。默认聊天模型语义检索不受影响"
                        },
                    ),
                )
            }
        }
    }

    fun setSemanticMemoryModel(model: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setSemanticMemoryModel(model.trim()) }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            semanticModel = model.trim(),
                            semanticIndexStatus = "嵌入模型已更新，需要重新建立本地索引",
                        )
                    }
                },
                onFailure = { failure ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            notice = failure.message ?: "模型名称格式无效",
                        )
                    }
                },
            )
        }
    }

    fun buildSemanticIndex() {
        if (memoryState.semanticIndexBusy || !memoryState.semanticMemoryEnabled ||
            !memoryState.semanticUseEmbeddings) return
        if (memoryState.retainedNodes.none { it.active }) {
            memoryState = memoryState.copy(
                semanticIndexStatus = "结构化记忆为 0，无法建立向量索引；仍可用当前聊天模型检索 MEMORY.md",
            )
            return
        }
        memoryState = memoryState.copy(
            semanticIndexBusy = true, semanticIndexStatus = "正在生成本地语义向量…",
        )
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val model = RuntimeConfigRepository.currentRuntimeConfig()
                    ?: error("请先在模型设置中配置支持 Embeddings 的服务商")
                val endpoint = EtaEmbeddingEndpoint.fromConfig(
                    model, SettingsDataStore.semanticMemoryModel(),
                )
                AgentSemanticMemorySearch(EtaMemoryVectorIndex(appContext.filesDir))
                    .buildIndex(
                        AgentRetainedMemoryRepository.active(400), endpoint, AgentRunController(),
                        maximumBatches = 4,
                    )
            }
            withContext(Dispatchers.Main) {
                memoryState = memoryState.copy(
                    semanticIndexBusy = false,
                    semanticIndexedNodes = result.getOrNull()?.first ?: memoryState.semanticIndexedNodes,
                    semanticIndexStatus = result.fold(
                        onSuccess = { (done, total) ->
                            "已索引 ${done}/${total} 条记忆；${if (done < total) "可再次点击继续" else "索引完成"}，仅保存在本机"
                        },
                        onFailure = { "语义索引失败：${it.message ?: it.javaClass.simpleName}（词法检索可继续使用）" },
                    ),
                )
            }
        }
    }

    fun setGraphMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setGraphMemoryEnabled(enabled) }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(graphMemoryEnabled = enabled)
                    }
                },
                onFailure = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = "无法保存网状记忆开关")
                    }
                },
            )
        }
    }

    fun classifyExistingRelations() {
        if (memoryState.graphClassifierBusy || !memoryState.graphMemoryEnabled) return
        memoryState = memoryState.copy(
            graphClassifierBusy = true,
            graphClassifierStatus = "正在对最近的记忆进行模型关联分类…",
        )
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val config = RuntimeConfigRepository.currentRuntimeConfig()
                    ?: error("请先配置可用的聊天模型")
                val nodes = AgentRetainedMemoryRepository.active(400)
                val sources = nodes.sortedByDescending { it.updatedAt }
                    .filter { it.links.none { relation -> relation.status == "confirmed" } }
                    .take(12)
                var linked = 0
                sources.chunked(3).forEach { chunk ->
                    val remaining = nodes.filterNot { node -> chunk.any { it.id == node.id } }
                    val results = AgentMemoryRelationClassifier().classify(
                        config, chunk, remaining, AgentRunController(),
                    )
                    results.forEach { (id, links) ->
                        linked += AgentRetainedMemoryRepository.proposeLinks(id, links)
                    }
                }
                linked to AgentRetainedMemoryRepository.visible(400)
            }
            withContext(Dispatchers.Main) {
                memoryState = memoryState.copy(
                    graphClassifierBusy = false,
                    retainedNodes = result.getOrNull()?.second ?: memoryState.retainedNodes,
                    graphClassifierStatus = result.fold(
                        onSuccess = { (count, _) ->
                            "本次新增/更新 ${count} 条关联建议；需要人工审核"
                        },
                        onFailure = { "关系分类失败：${it.message ?: it.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    fun reviewMemoryLink(sourceId: String, targetId: String, type: String, status: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                check(AgentRetainedMemoryRepository.reviewLink(sourceId, targetId, type, status)) {
                    "关系不存在，或者相关记忆已失效"
                }
                AgentRetainedMemoryRepository.visible(400)
            }.fold(
                onSuccess = { nodes ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            retainedNodes = nodes,
                            notice = if (status == "confirmed") "已确认关联" else "已拒绝关联",
                        )
                    }
                },
                onFailure = { failure ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = failure.message ?: "关联审核失败")
                    }
                },
            )
        }
    }

    fun deleteRetainedMemory(id: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                AgentRetainedMemoryRepository.delete(id)
                AgentRetainedMemoryRepository.visible(400)
            }.fold(
                onSuccess = { nodes ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(retainedNodes = nodes, notice = "记忆节点已删除")
                    }
                },
                onFailure = { error ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = error.message ?: "删除记忆节点失败")
                    }
                },
            )
        }
    }

    fun clearRetainedMemory() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                AgentRetainedMemoryRepository.clear()
                AgentRetainedMemoryRepository.visible(400)
            }.fold(
                onSuccess = { nodes ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(retainedNodes = nodes, notice = "结构化记忆已清空")
                    }
                },
                onFailure = { error ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = error.message ?: "清空失败")
                    }
                },
            )
        }
    }

    fun saveWebDav(endpoint: String, username: String, password: String, passphrase: String) {
        if (memoryState.syncBusy) return
        memoryState = memoryState.copy(syncBusy = true)
        scope.launch(Dispatchers.IO) {
            runCatching { EtaWebDavSyncRepository.configure(endpoint, username, password, passphrase) }
                .fold(
                    onSuccess = { config ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                syncBusy = false, syncEndpoint = config.endpoint,
                                syncUsername = config.username,
                                syncHasPassword = config.hasPassword,
                                syncHasPassphrase = config.hasPassphrase,
                                syncStatus = "WebDAV 配置已保存；点击立即同步",
                            )
                        }
                    },
                    onFailure = { failure ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                syncBusy = false,
                                notice = failure.message ?: "保存 WebDAV 配置失败",
                            )
                        }
                    },
                )
        }
    }

    fun runWebDavSync(resolveReviewedMemoryConflict: Boolean = false) {
        if (memoryState.syncBusy || !memoryState.syncHasPassphrase) return
        if (memoryState.hasUnsavedChanges) {
            memoryState = memoryState.copy(notice = "请先保存 MEMORY.md 的修改，再开始同步")
            return
        }
        memoryState = memoryState.copy(syncBusy = true, syncStatus = "正在同步…")
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                EtaWebDavSyncRepository.sync(resolveReviewedMemoryConflict)
            }
            val memoryConflict = EtaWebDavSyncRepository.hasMemoryConflict()
            val remoteConflictContent = if (memoryConflict) {
                EtaWebDavSyncRepository.remoteConflictContent()
            } else null
            val refreshedMemory = runCatching { AgentMemoryRepository.snapshot() }.getOrNull()
            val refreshedKnowledge = runCatching { AgentKnowledgeRepository.snapshot() }.getOrNull()
            val experiences = runCatching { AgentExperienceRepository.count() }.getOrNull()
            val retained = runCatching { AgentRetainedMemoryRepository.visible(400) }.getOrNull()
            withContext(Dispatchers.Main) {
                val status = outcome.fold(
                    onSuccess = { result ->
                        if (result.success) {
                            "${result.message}：知识 ${result.knowledgeCount} 条，任务经验 ${result.experienceCount} 条，" +
                                "结构化记忆 ${result.retainedCount} 条，知识冲突副本 ${result.knowledgeConflicts} 条"
                        } else result.message
                    },
                    onFailure = { failure -> failure.message ?: "WebDAV 同步失败" },
                )
                memoryState = memoryState.copy(
                    syncBusy = false,
                    syncMemoryConflict = memoryConflict,
                    syncRemoteConflictContent = remoteConflictContent,
                    syncStatus = status,
                    savedContent = refreshedMemory?.content ?: memoryState.savedContent,
                    draft = if (!memoryState.hasUnsavedChanges) {
                        refreshedMemory?.content ?: memoryState.draft
                    } else memoryState.draft,
                    draftBytes = if (!memoryState.hasUnsavedChanges) {
                        refreshedMemory?.byteSize ?: memoryState.draftBytes
                    } else memoryState.draftBytes,
                    knowledgeDocuments = refreshedKnowledge?.filter { !it.deleted }
                        ?: memoryState.knowledgeDocuments,
                    experienceCount = experiences ?: memoryState.experienceCount,
                    retainedNodes = retained ?: memoryState.retainedNodes,
                )
            }
        }
    }

    fun setWebDavAutoSyncEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                SettingsDataStore.setAutoWebDavSyncEnabled(enabled)
                EtaWebDavSyncScheduler.configure(appContext, enabled)
            }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(syncAutoEnabled = enabled)
                    }
                },
                onFailure = { failure ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            notice = failure.message ?: "无法设置自动同步",
                        )
                    }
                },
            )
        }
    }

    fun clearWebDavConfig() {
        if (memoryState.syncBusy) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                SettingsDataStore.setAutoWebDavSyncEnabled(false)
                EtaWebDavSyncScheduler.configure(appContext, false)
                EtaWebDavSyncRepository.clearConfig()
            }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            syncEndpoint = "", syncUsername = "", syncHasPassword = false,
                            syncHasPassphrase = false, syncAutoEnabled = false,
                            syncMemoryConflict = false,
                            syncRemoteConflictContent = null,
                            syncStatus = "WebDAV 连接信息已移除，本地内容保留",
                        )
                    }
                },
                onFailure = { failure ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = failure.message ?: "无法移除 WebDAV 配置")
                    }
                },
            )
        }
    }

    fun saveKnowledge(
        title: String, content: String, kind: String, source: String, tags: List<String>,
        id: String?, revision: String?,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                AgentKnowledgeRepository.save(title, content, kind, source, tags, id, revision)
                AgentKnowledgeRepository.snapshot().filter { !it.deleted }
            }.fold(
                onSuccess = { records ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(knowledgeDocuments = records, notice = "知识条目已保存")
                    }
                },
                onFailure = { failure ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = failure.message ?: "知识条目保存失败")
                    }
                },
            )
        }
    }

    fun deleteKnowledge(id: String, revision: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                AgentKnowledgeRepository.delete(id, revision)
                AgentKnowledgeRepository.snapshot().filter { !it.deleted }
            }.fold(
                onSuccess = { records ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(knowledgeDocuments = records, notice = "知识条目已删除")
                    }
                },
                onFailure = { failure ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(notice = failure.message ?: "删除失败")
                    }
                },
            )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }
}
