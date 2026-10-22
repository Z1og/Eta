package io.github.mangi.eta.data.repository

import org.json.JSONObject

/** Versioned exchange format. No provider keys, raw chats, screenshots, or Root credentials. */
internal data class EtaSyncPayload(
    val memoryMarkdown: String,
    val knowledge: List<KnowledgeDocument>,
    val experiences: ExperienceSyncSnapshot,
    val retained: RetainedMemorySnapshot = RetainedMemorySnapshot.EMPTY,
) {
    fun json(): String = JSONObject()
        .put("schema", 3)
        .put("memory_md", memoryMarkdown)
        .put("knowledge", JSONObject(KnowledgeJson.encode(knowledge)))
        .put("experience", JSONObject(experiences.json()))
        .put("retained", JSONObject(retained.json()))
        .toString()

    companion object {
        val EMPTY = EtaSyncPayload("", emptyList(), ExperienceSyncSnapshot.EMPTY)
        fun fromJson(raw: String): EtaSyncPayload {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Sync data exceeds safety limit" }
            val root = JSONObject(raw)
            require(root.optInt("schema") in 1..3) { "Unsupported Eta sync schema" }
            val memory = root.optString("memory_md")
            require(memory.toByteArray(Charsets.UTF_8).size <= AgentMemoryStore.MAX_FILE_BYTES)
            val knowledge = KnowledgeJson.decode(
                root.optJSONObject("knowledge")?.toString() ?: KnowledgeJson.encode(emptyList()),
            )
            val experiences = ExperienceSyncSnapshot.fromJson(
                root.optJSONObject("experience")?.toString() ?: ExperienceSyncSnapshot.EMPTY.json(),
            )
            val retained = RetainedMemorySnapshot.fromJson(
                root.optJSONObject("retained")?.toString() ?: RetainedMemorySnapshot.EMPTY.json(),
            )
            return EtaSyncPayload(memory, knowledge, experiences, retained)
        }
        const val MAX_BYTES = 7 * 1024 * 1024
    }
}

internal sealed interface EtaSyncMerge {
    data class Merged(val payload: EtaSyncPayload, val knowledgeConflicts: Int) : EtaSyncMerge
    data object MemoryConflict : EtaSyncMerge

    companion object {
        fun compute(
            base: EtaSyncPayload?,
            local: EtaSyncPayload,
            remote: EtaSyncPayload?,
        ): EtaSyncMerge {
            val basePayload = base ?: EtaSyncPayload.EMPTY
            val remotePayload = remote ?: EtaSyncPayload.EMPTY
            val resolvedMemory = when {
                local.memoryMarkdown == remotePayload.memoryMarkdown -> local.memoryMarkdown
                local.memoryMarkdown == basePayload.memoryMarkdown -> remotePayload.memoryMarkdown
                remotePayload.memoryMarkdown == basePayload.memoryMarkdown -> local.memoryMarkdown
                else -> return MemoryConflict
            }
            val mergedKnowledge = KnowledgeMerge.merge(
                basePayload.knowledge, local.knowledge, remotePayload.knowledge,
            )
            val conflicts = mergedKnowledge.count { it.title.endsWith("(sync conflict copy)") }
            return Merged(EtaSyncPayload(
                memoryMarkdown = resolvedMemory,
                knowledge = mergedKnowledge,
                experiences = ExperienceSyncSnapshot.merge(local.experiences, remotePayload.experiences),
                retained = RetainedMemoryMerge.merge(local.retained, remotePayload.retained),
            ), conflicts)
        }
    }
}
