package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/** A revisable fact, reference, or procedure. Tombstones prevent deleted notes reappearing on sync. */
internal data class KnowledgeDocument(
    val id: String,
    val title: String,
    val content: String,
    val kind: String,
    val source: String,
    val tags: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: String,
    val deleted: Boolean = false,
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("content", content).put("kind", kind)
        .put("source", source).put("tags", JSONArray(tags))
        .put("created_at", createdAt).put("updated_at", updatedAt)
        .put("revision", revision).put("deleted", deleted)

    companion object {
        fun fromJson(body: JSONObject): KnowledgeDocument? {
            val id = body.optString("id").takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: return null
            val revision = body.optString("revision").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                ?: return null
            val title = body.optString("title")
            val content = body.optString("content")
            if (title.length > 160 || content.length > AgentKnowledgeStore.MAX_DOCUMENT_CHARS) return null
            val tags = body.optJSONArray("tags")?.let { array ->
                (0 until array.length().coerceAtMost(12)).mapNotNull { i ->
                    array.optString(i).takeIf { it.isNotBlank() && it.length <= 40 }
                }
            }.orEmpty()
            return KnowledgeDocument(
                id, title, content, body.optString("kind").take(30),
                body.optString("source").take(250), tags,
                body.optLong("created_at"), body.optLong("updated_at"), revision,
                body.optBoolean("deleted"),
            )
        }
    }
}

internal object KnowledgeJson {
    fun encode(documents: List<KnowledgeDocument>): String =
        JSONObject().put("schema", 1).put("documents", JSONArray().also { array ->
            documents.forEach { array.put(it.json()) }
        }).toString()

    fun decode(raw: String): List<KnowledgeDocument> {
        if (raw.isBlank()) return emptyList()
        val root = JSONObject(raw)
        require(root.optInt("schema") == 1) { "Unsupported knowledge format" }
        val array = root.getJSONArray("documents")
        require(array.length() <= AgentKnowledgeStore.MAX_DOCUMENTS * 2) { "Too many knowledge documents" }
        return (0 until array.length()).map { index ->
            KnowledgeDocument.fromJson(
                array.optJSONObject(index) ?: error("Invalid knowledge record at index $index"),
            ) ?: error("Invalid knowledge record at index $index")
        }
    }
}

internal object KnowledgeMerge {
    /**
     * A three-way per-document merge. Divergent edits become a stable conflict copy instead of
     * discarding either side. The copied ID is deterministic, so retries cannot proliferate copies.
     */
    fun merge(
        base: List<KnowledgeDocument>,
        local: List<KnowledgeDocument>,
        remote: List<KnowledgeDocument>,
    ): List<KnowledgeDocument> {
        val before = base.associateBy { it.id }
        val left = local.associateBy { it.id }
        val right = remote.associateBy { it.id }
        val ids = (left.keys + right.keys).toSortedSet()
        val merged = linkedMapOf<String, KnowledgeDocument>()
        ids.forEach { id ->
            val b = before[id]
            val l = left[id]
            val r = right[id]
            when {
                l == null -> if (r != null) merged[id] = r
                r == null -> merged[id] = l
                l == r || l.revision == r.revision -> merged[id] = l
                l == b || l.revision == b?.revision -> merged[id] = r
                r == b || r.revision == b?.revision -> merged[id] = l
                else -> {
                    // Concurrent delete/edit: keep the tombstone under the original ID,
                    // preserve the edited text as a separate visible conflict document.
                    val preferred = if (l.deleted || r.deleted) {
                        if (l.deleted) l else r
                    } else l
                    val conflict = if (preferred === l) r else l
                    merged[id] = preferred
                    if (!conflict.deleted) {
                        val forkId = UUID.nameUUIDFromBytes(
                            ("eta-knowledge-conflict:" + id + ":" + conflict.revision).toByteArray(Charsets.UTF_8),
                        ).toString()
                        if (forkId !in ids) {
                            merged[forkId] = conflict.copy(
                                id = forkId,
                                title = (conflict.title.take(138) + " (sync conflict copy)").take(160),
                            )
                        }
                    }
                }
            }
        }
        require(merged.size <= AgentKnowledgeStore.MAX_DOCUMENTS) {
            "Knowledge merge would exceed capacity; no records were discarded"
        }
        return merged.values.sortedWith(compareByDescending<KnowledgeDocument> { it.updatedAt }.thenBy { it.id })
    }
}

internal object KnowledgeRanker {
    private val wordPattern = Regex("[a-z0-9_]{2,}|[\\p{IsHan}]+", RegexOption.IGNORE_CASE)
    private fun tokens(text: String): List<String> {
        val normalized = text.lowercase()
        return wordPattern.findAll(normalized).flatMap { match ->
            val word = match.value
            if (word.first().code in 0x3400..0x9FFF) {
                (word.indices.map { word.substring(it, it + 1) } +
                    (0 until word.length - 1).map { word.substring(it, it + 2) }).asSequence()
            } else sequenceOf(word)
        }.take(3_000).toList()
    }

    fun rank(query: String, docs: List<KnowledgeDocument>, limit: Int = 5): List<KnowledgeDocument> {
        val terms = tokens(query.take(300)).distinct()
        if (terms.isEmpty()) return emptyList()
        val eligible = docs.filter { !it.deleted }
        if (eligible.isEmpty()) return emptyList()
        val bodyTokens = eligible.associateWith { tokens(it.content.take(15_000)) }
        val titleTokens = eligible.associateWith { tokens(it.title) }
        val avgLength = bodyTokens.values.map { it.size }.average().coerceAtLeast(1.0)
        val frequency = terms.associateWith { term ->
            eligible.count { doc -> term in bodyTokens.getValue(doc) ||
                term in titleTokens.getValue(doc) || doc.tags.any { it.contains(term, ignoreCase = true) } }
        }
        return eligible.map { doc ->
            val body = bodyTokens.getValue(doc)
            val title = titleTokens.getValue(doc)
            val score = terms.sumOf { term ->
                val df = frequency.getValue(term)
                if (df == 0) 0.0 else {
                    val idf = ln(1.0 + (eligible.size - df + 0.5) / (df + 0.5))
                    val tf = body.count { it == term }.toDouble()
                    val bm25 = if (tf == 0.0) 0.0 else {
                        idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * body.size / avgLength))
                    }
                    bm25 + (if (term in title) idf * 3.0 else 0.0) +
                        (if (doc.tags.any { it.contains(term, ignoreCase = true) }) idf * 2.0 else 0.0)
                }
            }
            doc to score
        }.filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<KnowledgeDocument, Double>> { it.second }
                .thenByDescending { it.first.updatedAt })
            .take(limit.coerceIn(1, 8)).map { it.first }
    }
}

internal class AgentKnowledgeStore(rootDir: File) {
    private val directory = File(rootDir, "memory")
    private val store = DurableAtomicFile(File(directory, "knowledge.json"))
    private val lockFile = File(directory, "knowledge.lock")
    private val mutex = Any()

    fun snapshot(): List<KnowledgeDocument> = locked { readLocked() }
    fun count(): Int = snapshot().count { !it.deleted }

    fun get(id: String): KnowledgeDocument? = snapshot().find { it.id == id && !it.deleted }

    fun search(query: String, limit: Int = 5): List<KnowledgeDocument> =
        KnowledgeRanker.rank(query, snapshot(), limit)

    fun save(
        title: String,
        content: String,
        kind: String = "reference",
        source: String = "",
        tags: List<String> = emptyList(),
        id: String? = null,
        expectedRevision: String? = null,
        now: Long = System.currentTimeMillis(),
    ): KnowledgeDocument = locked {
        require(title.isNotBlank() && title.length <= 160) { "Knowledge title is required (max 160 characters)" }
        require(content.isNotBlank() && content.length <= MAX_DOCUMENT_CHARS) { "Knowledge body is required (max $MAX_DOCUMENT_CHARS characters)" }
        require(source.length <= 250 && tags.size <= 12 && tags.all { it.length <= 40 }) { "Invalid knowledge metadata" }
        require(kind in setOf("fact", "procedure", "reference")) { "Invalid knowledge type" }
        val docs = readLocked().toMutableList()
        val index = if (id == null) -1 else docs.indexOfFirst { it.id == id }
        if (id != null) {
            require(index >= 0 && !docs[index].deleted) { "Knowledge document not found" }
            require(!expectedRevision.isNullOrBlank() && expectedRevision == docs[index].revision) {
                "KNOWLEDGE_CONFLICT: reload the document before editing"
            }
        }
        val old = docs.getOrNull(index)
        val actualId = old?.id ?: UUID.randomUUID().toString()
        val normalizedTags = tags.distinct()
        val hash = sha256("$actualId|$title|$content|$kind|$source|$normalizedTags|$now|false")
        val next = KnowledgeDocument(actualId, title, content, kind, source, normalizedTags,
            old?.createdAt ?: now, now, hash)
        if (index >= 0) docs[index] = next else docs.add(next)
        writeLocked(docs)
        next
    }

    fun delete(id: String, revision: String): Boolean = locked {
        val docs = readLocked().toMutableList()
        val index = docs.indexOfFirst { it.id == id && !it.deleted }
        if (index < 0) return@locked false
        require(docs[index].revision == revision) { "KNOWLEDGE_CONFLICT: reload before delete" }
        val old = docs[index]
        val time = System.currentTimeMillis()
        docs[index] = old.copy(content = "", deleted = true, updatedAt = time,
            revision = sha256("$id|deleted|$time"))
        writeLocked(docs)
        true
    }

    fun mergeFrom(remote: List<KnowledgeDocument>, base: List<KnowledgeDocument>): List<KnowledgeDocument> = locked {
        val merged = KnowledgeMerge.merge(base, readLocked(), remote)
        writeLocked(merged)
        merged
    }

    fun replaceIfUnchanged(expected: List<KnowledgeDocument>, replacement: List<KnowledgeDocument>): Boolean = locked {
        if (readLocked() != expected) return@locked false
        writeLocked(replacement)
        true
    }

    private fun readLocked(): List<KnowledgeDocument> {
        if (!store.exists()) return emptyList()
        val bytes = store.openRead().use { it.readBytes() }
        require(bytes.size <= MAX_FILE_BYTES) { "Knowledge database exceeds 4 MiB" }
        return KnowledgeJson.decode(bytes.toString(Charsets.UTF_8))
    }

    private fun writeLocked(documents: List<KnowledgeDocument>) {
        require(documents.size <= MAX_DOCUMENTS) { "Knowledge document limit reached" }
        val bytes = KnowledgeJson.encode(documents).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Knowledge database exceeds 4 MiB" }
        val output = store.startWrite()
        try {
            output.write(bytes)
            store.finishWrite(output)
        } catch (failure: Throwable) {
            store.failWrite(output)
            throw failure
        }
    }

    private fun <T> locked(action: () -> T): T = synchronized(mutex) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create knowledge directory" }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val lock = channel.lock()
            try { action() } finally { lock.release() }
        }
    }

    companion object {
        const val MAX_DOCUMENTS = 512
        const val MAX_DOCUMENT_CHARS = 12_000
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

internal object AgentKnowledgeRepository {
    @Volatile private lateinit var store: AgentKnowledgeStore
    fun init(context: Context) {
        if (!::store.isInitialized) store = AgentKnowledgeStore(context.applicationContext.filesDir)
    }
    fun snapshot(): List<KnowledgeDocument> = checked().snapshot()
    fun count(): Int = checked().count()
    fun search(query: String, limit: Int = 5): List<KnowledgeDocument> = checked().search(query, limit)
    fun get(id: String): KnowledgeDocument? = checked().get(id)
    fun save(title: String, content: String, kind: String = "reference", source: String = "",
        tags: List<String> = emptyList(), id: String? = null, revision: String? = null,
    ): KnowledgeDocument = checked().save(title, content, kind, source, tags, id, revision)
    fun delete(id: String, revision: String): Boolean = checked().delete(id, revision)
    fun replaceIfUnchanged(old: List<KnowledgeDocument>, new: List<KnowledgeDocument>): Boolean =
        checked().replaceIfUnchanged(old, new)
    private fun checked() = check(::store.isInitialized) { "AgentKnowledgeRepository.init() not called" }.let { store }
}
