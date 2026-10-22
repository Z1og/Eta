package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.RetainedMemoryNode

internal data class EtaSemanticResult(
    val ranked: List<Pair<String, Double>>,
    val indexed: Int,
    val total: Int,
)

/**
 * Optional real embeddings: query + up to 23 missing records in a single request.
 * A failed or unsupported endpoint causes the caller to fall back to local BM25.
 */
internal class AgentSemanticMemorySearch(
    private val index: EtaMemoryVectorIndex,
    private val embeddingProvider: (EtaEmbeddingEndpoint) -> EtaEmbeddingProvider =
        ::EtaOpenAiEmbeddingProvider,
) {
    fun search(
        query: String,
        active: List<RetainedMemoryNode>,
        endpoint: EtaEmbeddingEndpoint,
        controller: AgentRunController,
    ): EtaSemanticResult {
        require(query.isNotBlank() && query.length <= 12_000)
        val records = active.filter(RetainedMemoryNode::active)
        if (records.isEmpty() || !AgentRetainPrivacy.allow(query)) {
            return EtaSemanticResult(emptyList(), 0, records.size)
        }
        val fingerprint = EtaMemoryVectorIndex.fingerprint(endpoint)
        val stored = index.readValid(fingerprint, records)
        val lexical = AgentRetainedRecall.rank(query, records, 8)
        val prioritized = (lexical + records.sortedByDescending { it.updatedAt })
            .distinctBy { it.id }
        val pending = prioritized.filter { it.id !in stored.vectors }.take(23)
        val queryText = query.take(900)
        val texts = listOf(queryText) + pending.map(EtaMemoryVectorIndex::embeddingText)
        val vectors = embeddingProvider(endpoint).embed(texts, controller)
        require(vectors.size == texts.size)
        val inserted = pending.indices.associate { idx -> pending[idx].id to vectors[idx + 1] }
        val updated = if (inserted.isEmpty()) stored else index.save(fingerprint, records, inserted)
        val queryVector = vectors[0]
        val eligibleIds = AgentRetainedRecall.eligible(query, records).mapTo(hashSetOf()) { it.id }
        val ranked = updated.vectors.values.asSequence()
            .filter { it.memoryId in eligibleIds && it.coordinates.size == queryVector.size }
            .map { it.memoryId to EtaOpenAiEmbeddingProvider.cosine(queryVector, it.coordinates) }
            .filter { it.second >= MIN_COSINE }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(12).toList()
        return EtaSemanticResult(ranked, updated.vectors.size, records.size)
    }

    fun buildIndex(
        active: List<RetainedMemoryNode>,
        endpoint: EtaEmbeddingEndpoint,
        controller: AgentRunController,
        maximumBatches: Int = 25,
    ): Pair<Int, Int> {
        val records = active.filter(RetainedMemoryNode::active).take(400)
        val fingerprint = EtaMemoryVectorIndex.fingerprint(endpoint)
        for (round in 0 until maximumBatches.coerceIn(1, 25)) {
            controller.throwIfCancelled()
            val snapshot = index.readValid(fingerprint, records)
            val pending = records.filter { it.id !in snapshot.vectors }.take(24)
            if (pending.isEmpty()) return snapshot.vectors.size to records.size
            val embedded = embeddingProvider(endpoint).embed(
                pending.map(EtaMemoryVectorIndex::embeddingText), controller,
            )
            require(embedded.size == pending.size)
            index.save(fingerprint, records, pending.indices.associate { idx ->
                pending[idx].id to embedded[idx]
            })
        }
        val current = index.readValid(fingerprint, records)
        return current.vectors.size to records.size
    }

    companion object {
        const val MIN_COSINE = 0.20
    }
}
