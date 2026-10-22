package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.KnowledgeDocument
import io.github.mangi.eta.data.repository.RetainedMemoryNode

@Immutable
internal data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val autoExperienceEnabled: Boolean = false,
    val smartRetainEnabled: Boolean = false,
    val semanticMemoryEnabled: Boolean = false,
    val semanticUseEmbeddings: Boolean = false,
    val semanticModel: String = "text-embedding-3-small",
    val semanticCoreChunks: Int = 0,
    val semanticIndexedNodes: Int = 0,
    val semanticRetrievalReport: String? = null,
    val chatRetrievalTestBusy: Boolean = false,
    val chatRetrievalTestStatus: String? = null,
    val embeddingTestBusy: Boolean = false,
    val embeddingTestStatus: String? = null,
    val semanticIndexBusy: Boolean = false,
    val semanticIndexStatus: String? = null,
    val graphMemoryEnabled: Boolean = false,
    val graphClassifierBusy: Boolean = false,
    val graphClassifierStatus: String? = null,
    val retainedNodes: List<RetainedMemoryNode> = emptyList(),
    val experienceCount: Int = 0,
    val knowledgeDocuments: List<KnowledgeDocument> = emptyList(),
    val syncEndpoint: String = "",
    val syncUsername: String = "",
    val syncHasPassword: Boolean = false,
    val syncHasPassphrase: Boolean = false,
    val syncBusy: Boolean = false,
    val syncAutoEnabled: Boolean = false,
    val syncMemoryConflict: Boolean = false,
    val syncRemoteConflictContent: String? = null,
    val syncStatus: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes
}
