package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentKnowledgeStore
import io.github.mangi.eta.data.repository.DurableAtomicFile
import io.github.mangi.eta.data.repository.RetainedMemoryNode
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rebuildable, local-only cache. WebDAV sync does not transmit embeddings because
 * two devices may use different models. No user text or API credentials are stored here.
 */
internal class EtaMemoryVectorIndex(rootDir: File) {
    private val dir = File(rootDir, "memory")
    private val data = DurableAtomicFile(File(dir, "semantic_vectors_v1.json"))
    private val lockFile = File(dir, "semantic_vectors.lock")
    private val monitor = Any()

    data class VectorEntry(
        val memoryId: String,
        val contentHash: String,
        val coordinates: FloatArray,
    )

    data class State(
        val fingerprint: String,
        val dimension: Int,
        val vectors: Map<String, VectorEntry>,
    ) {
        companion object {
            fun empty(fingerprint: String): State = State(fingerprint, 0, emptyMap())
        }
    }

    fun readValid(fingerprint: String, current: List<RetainedMemoryNode>): State = locked {
        filterCurrent(readLocked(fingerprint), current)
    }

    fun save(
        fingerprint: String,
        current: List<RetainedMemoryNode>,
        newlyEmbedded: Map<String, FloatArray>,
    ): State = locked {
        val cached = filterCurrent(readLocked(fingerprint), current)
        val validNodes = current.filter { it.active }.associateBy { it.id }
        var dimension = cached.dimension
        val updated = cached.vectors.toMutableMap()
        newlyEmbedded.forEach { (id, values) ->
            val node = validNodes[id] ?: return@forEach
            require(values.size in 32..3072 && values.all(Float::isFinite))
            if (dimension != 0 && dimension != values.size) {
                // A gateway may change the actual embedding model behind a stable name.
                // Old vectors cannot be compared and must be regenerated.
                updated.clear()
                dimension = 0
            }
            if (dimension == 0) dimension = values.size
            require(values.size == dimension) { "Embedding model dimensions changed mid-index" }
            updated[id] = VectorEntry(id, contentHash(node), EtaOpenAiEmbeddingProvider.normalize(values))
        }
        val next = State(fingerprint, dimension, updated)
        writeLocked(next)
        next
    }

    fun reset() = locked { data.delete() }

    private fun filterCurrent(state: State, nodes: List<RetainedMemoryNode>): State {
        val hashes = nodes.filter { it.active }.associate { it.id to contentHash(it) }
        return state.copy(vectors = state.vectors.filter { (id, entry) ->
            hashes[id] == entry.contentHash && entry.coordinates.size == state.dimension
        })
    }

    private fun readLocked(fingerprint: String): State {
        if (!data.exists()) return State.empty(fingerprint)
        val bytes = data.openRead().use { input ->
            val raw = input.readBytes()
            require(raw.size <= MAX_FILE_BYTES) { "Embedding cache exceeds local limit" }
            raw
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        if (root.optInt("schema") != 1 || root.optString("fingerprint") != fingerprint) {
            return State.empty(fingerprint)
        }
        val dimension = root.optInt("dimension")
        if (dimension !in 32..3072) return State.empty(fingerprint)
        val array = root.optJSONArray("vectors") ?: JSONArray()
        require(array.length() <= 400)
        val records = mutableMapOf<String, VectorEntry>()
        repeat(array.length()) { position ->
            val row = array.getJSONObject(position)
            val id = row.optString("id")
            val hash = row.optString("content_hash")
            if (!UUID_REGEX.matches(id) || !HASH_REGEX.matches(hash)) return@repeat
            val encoded = row.optString("values")
            val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                ?: return@repeat
            if (decoded.size != dimension * 4) return@repeat
            val buffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
            val vector = FloatArray(dimension) { buffer.float }
            if (!vector.all(Float::isFinite)) return@repeat
            records[id] = VectorEntry(id, hash, vector)
        }
        return State(fingerprint, dimension, records)
    }

    private fun writeLocked(state: State) {
        require(state.vectors.size <= 400)
        val array = JSONArray()
        state.vectors.values.forEach { entry ->
            val bytes = ByteBuffer.allocate(entry.coordinates.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            entry.coordinates.forEach(bytes::putFloat)
            array.put(JSONObject().put("id", entry.memoryId)
                .put("content_hash", entry.contentHash)
                .put("values", Base64.getEncoder().encodeToString(bytes.array())))
        }
        val body = JSONObject().put("schema", 1)
            .put("fingerprint", state.fingerprint)
            .put("dimension", state.dimension).put("vectors", array)
            .toString().toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_FILE_BYTES) { "Embedding index is too large" }
        val stream = data.startWrite()
        try {
            stream.write(body)
            data.finishWrite(stream)
        } catch (failure: Throwable) {
            data.failWrite(stream)
            throw failure
        }
    }

    private fun <T> locked(action: () -> T): T = synchronized(monitor) {
        check(dir.isDirectory || dir.mkdirs())
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val lock = channel.lock()
            try { action() } finally { lock.release() }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private val UUID_REGEX = Regex("[a-fA-F0-9-]{36}")
        private val HASH_REGEX = Regex("[a-f0-9]{64}")

        fun contentHash(node: RetainedMemoryNode): String = AgentKnowledgeStore.sha256(
            "${node.id}|${node.scope}|${node.layer}|${node.subject}|${node.text}|${node.entities}",
        )

        fun embeddingText(node: RetainedMemoryNode): String =
            "${node.layer}: ${node.subject}. ${node.text}".take(900)

        fun fingerprint(endpoint: EtaEmbeddingEndpoint): String =
            AgentKnowledgeStore.sha256("${endpoint.url}|${endpoint.model}")
    }
}
