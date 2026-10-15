package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ModelRepository {
    private val mutationMutex = Mutex()

    fun modelsByProviderFlow(providerId: String): Flow<List<Model>> =
        allModelsByProviderFlow(providerId).map { models ->
            models.filter { it.isEnabled }.sortedBy { it.sortOrder }
        }

    fun allModelsByProviderFlow(providerId: String): Flow<List<Model>> =
        ProviderRepository.providersFlow().map { providers ->
            providers.firstOrNull { it.id == providerId }
                ?.models
                ?.sortedBy { it.sortOrder }
                .orEmpty()
        }

    suspend fun modelsByProvider(providerId: String): List<Model> =
        ProviderRepository.providerById(providerId)
            ?.models
            ?.sortedBy { it.sortOrder }
            .orEmpty()

    suspend fun saveModel(providerId: String, draft: Model): Model = mutationMutex.withLock {
        val models = currentModels(providerId)
        val modelId = draft.modelId.trim()
        val displayName = draft.displayName.trim()
        require(modelId.isNotEmpty()) { "Model ID 不能为空" }
        require(displayName.isNotEmpty()) { "展示名称不能为空" }
        require(draft.contextWindowOverride == null || draft.contextWindowOverride > 0) {
            "上下文长度必须是正整数"
        }
        require(
            models.none { existing ->
                existing.id != draft.id && existing.modelId.trim().equals(modelId, ignoreCase = true)
            }
        ) { "Model ID 已存在" }

        val existing = models.firstOrNull { it.id == draft.id }
        val saved = if (existing == null) {
            draft.copy(
                id = draft.id.ifBlank(::newId),
                modelId = modelId,
                displayName = displayName,
                isBuiltIn = false,
                sortOrder = (models.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                source = ModelSource.MANUAL,
            )
        } else {
            draft.copy(
                id = existing.id,
                modelId = modelId,
                displayName = displayName,
                isBuiltIn = existing.isBuiltIn,
                sortOrder = existing.sortOrder,
                source = existing.source,
                createdAt = existing.createdAt,
            )
        }
        ProviderRepository.replaceModels(
            providerId,
            models.filterNot { it.id == saved.id } + saved,
        )
        saved
    }

    suspend fun deleteModel(providerId: String, modelId: String) = mutationMutex.withLock {
        ProviderRepository.replaceModels(
            providerId,
            currentModels(providerId).filterNot { it.id == modelId },
        )
        ProviderRepository.repairSelection()
    }

    suspend fun deleteModels(providerId: String, modelIds: Set<String>) {
        if (modelIds.isEmpty()) return
        mutationMutex.withLock {
            ProviderRepository.replaceModels(
                providerId,
                currentModels(providerId).filterNot { it.id in modelIds },
            )
            ProviderRepository.repairSelection()
        }
    }

    /** 拉取结果里尚未添加到该服务商的模型；聚合平台动辄上百个，由用户挑选后再加入。 */
    suspend fun newRemoteModels(providerId: String, fetched: List<Model>): List<Model> {
        val existing = currentModels(providerId).mapTo(mutableSetOf()) { it.modelId.normalizedModelId() }
        return fetched
            .filter { it.modelId.isNotBlank() }
            .distinctBy { it.modelId.normalizedModelId() }
            .filterNot { it.modelId.normalizedModelId() in existing }
    }

    /**
     * 已添加的模型用远端元数据刷新，远端不再返回的远端模型移除；
     * 新模型只加入 [adoptedModelIds] 中由用户选中的那些。
     *
     * 运行窗口只读保存的 contextWindowOverride。远端标注了窗口时在这里写入，
     * 等同于替用户填好，之后仍可在编辑页修改；远端未标注就保持空，不猜测。
     */
    suspend fun syncRemoteModels(
        providerId: String,
        fetched: List<Model>,
        adoptedModelIds: Set<String>,
    ): RemoteModelSyncResult =
        mutationMutex.withLock {
            val adopted = adoptedModelIds.mapTo(mutableSetOf()) { it.normalizedModelId() }
            val remoteByKey = fetched
                .asSequence()
                .filter { it.modelId.isNotBlank() }
                .distinctBy { it.modelId.normalizedModelId() }
                .associateBy { it.modelId.normalizedModelId() }
            if (remoteByKey.isEmpty()) {
                return@withLock RemoteModelSyncResult(applied = false)
            }

            val existing = currentModels(providerId)
            val consumed = mutableSetOf<String>()
            val merged = buildList {
                existing.forEach { stored ->
                    val key = stored.modelId.normalizedModelId()
                    val remote = remoteByKey[key]
                    when {
                        remote != null -> {
                            consumed += key
                            add(
                                remote.copy(
                                    id = stored.id,
                                    modelId = remote.modelId.trim(),
                                    displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                                    isEnabled = stored.isEnabled,
                                    isBuiltIn = stored.isBuiltIn || remote.isBuiltIn,
                                    customHeaders = stored.customHeaders,
                                    customBody = stored.customBody,
                                    // 用户填过的窗口优先；空着时用远端标注补上，省去手填。
                                    contextWindowOverride = stored.contextWindowOverride ?: remote.contextWindow,
                                    reasoningOverride = stored.reasoningOverride,
                                    reasoningCapabilitiesOverride = stored.reasoningCapabilitiesOverride,
                                    source = stored.source,
                                    createdAt = stored.createdAt,
                                )
                            )
                        }
                        stored.source != ModelSource.REMOTE -> add(stored)
                    }
                }
                remoteByKey.forEach { (key, remote) ->
                    if (key !in consumed && key in adopted) {
                        add(
                            remote.copy(
                                id = remote.id.ifBlank(::newId),
                                modelId = remote.modelId.trim(),
                                displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                                isBuiltIn = false,
                                contextWindowOverride = remote.contextWindow,
                                source = ModelSource.REMOTE,
                            )
                        )
                    }
                }
            }.mapIndexed { index, model -> model.copy(sortOrder = index) }

            ProviderRepository.replaceModels(providerId, merged)
            ProviderRepository.repairSelection()
            RemoteModelSyncResult(
                applied = true,
                fetchedCount = remoteByKey.size,
                addedCount = merged.count { model -> existing.none { it.id == model.id } },
                removedCount = existing.count { stored ->
                    stored.source == ModelSource.REMOTE &&
                        stored.modelId.normalizedModelId() !in remoteByKey
                },
            )
        }

    suspend fun reorderModels(providerId: String, ids: List<String>) {
        mutationMutex.withLock {
            val models = currentModels(providerId)
            val byId = models.associateBy { it.id }
            val orderedIds = ids.toSet()
            val reordered = ids.mapNotNull { byId[it] } + models.filterNot { it.id in orderedIds }
            ProviderRepository.replaceModels(
                providerId,
                reordered.mapIndexed { index, model -> model.copy(sortOrder = index) },
            )
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private suspend fun currentModels(providerId: String): List<Model> =
        requireNotNull(ProviderRepository.providerById(providerId)) { "Provider 不存在" }
            .models
            .sortedBy { it.sortOrder }

    private fun String.normalizedModelId(): String = trim().lowercase()
}

internal data class RemoteModelSyncResult(
    val applied: Boolean,
    val fetchedCount: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
)
