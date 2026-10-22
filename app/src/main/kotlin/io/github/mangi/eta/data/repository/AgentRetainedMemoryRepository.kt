package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * One atomic memory is an evidenced claim, not a raw conversation.
 * Link proposals are deliberately unverified until a separate graph-review phase.
 */
internal data class RetainedRelation(
    val targetId: String,
    val type: String,
    val confidence: Double,
    val status: String = "proposed",
    val reason: String = "",
    val updatedAt: Long = 0L,
) {
    fun json(): JSONObject = JSONObject()
        .put("target_id", targetId).put("type", type).put("confidence", confidence)
        .put("status", status).put("reason", reason).put("updated_at", updatedAt)

    companion object {
        val TYPES = setOf("SAME_TOPIC", "SUPPORTS", "CONTRADICTS", "PREREQUISITE", "APPLIES_TO")
        val STATUSES = setOf("proposed", "confirmed", "rejected")
        fun parse(json: JSONObject): RetainedRelation? {
            val target = json.optString("target_id").takeIf { isUuid(it) } ?: return null
            val type = json.optString("type").takeIf { it in TYPES } ?: return null
            val score = json.optDouble("confidence", -1.0).takeIf { it in 0.75..1.0 }
                ?: return null
            val status = json.optString("status").ifBlank { "proposed" }
                .takeIf { it in STATUSES } ?: return null
            val reason = json.optString("reason").takeIf { it.length <= 160 } ?: return null
            return RetainedRelation(target, type, score, status, reason,
                json.optLong("updated_at").coerceAtLeast(0L))
        }

        fun mergeLinks(a: List<RetainedRelation>, b: List<RetainedRelation>): List<RetainedRelation> =
            (a + b).groupBy { it.targetId to it.type }.values.map { variants ->
                variants.maxWith(
                    compareBy<RetainedRelation> { if (it.status == "proposed") 0 else 1 }
                        .thenBy { it.updatedAt }
                        .thenBy { if (it.status == "rejected") 1 else 0 },
                )
            }.sortedWith(
                compareByDescending<RetainedRelation> { it.status != "proposed" }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.targetId },
            ).take(12)
    }
}

internal data class RetainedMemoryNode(
    val id: String,
    val text: String,
    val layer: String,
    val category: String,
    val scope: String,
    val subject: String,
    val entities: List<String>,
    val sourceKind: String,
    val evidence: String,
    val sourceRuns: List<String>,
    val confidence: Double,
    val links: List<RetainedRelation>,
    val createdAt: Long,
    val updatedAt: Long,
    val validFrom: Long,
    val invalidatedAt: Long = 0L,
    val deletedAt: Long = 0L,
    val occurrences: Int = 1,
) {
    val active: Boolean get() = deletedAt == 0L && invalidatedAt == 0L

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("text", text).put("layer", layer)
        .put("category", category).put("scope", scope).put("subject", subject)
        .put("entities", JSONArray(entities))
        .put("source_kind", sourceKind).put("evidence", evidence)
        .put("source_runs", JSONArray(sourceRuns))
        .put("confidence", confidence)
        .put("links", JSONArray().also { target -> links.forEach { target.put(it.json()) } })
        .put("created_at", createdAt).put("updated_at", updatedAt)
        .put("valid_from", validFrom).put("invalidated_at", invalidatedAt)
        .put("deleted_at", deletedAt).put("occurrences", occurrences)

    companion object {
        val LAYERS = setOf("semantic", "episodic", "procedural")
        val CATEGORIES = setOf("preference", "fact", "goal", "procedure", "experience")

        fun parse(body: JSONObject): RetainedMemoryNode? {
            val id = body.optString("id").takeIf(::isUuid) ?: return null
            val text = body.optString("text").takeIf { it.length in 8..280 } ?: return null
            val layer = body.optString("layer").takeIf { it in LAYERS } ?: return null
            val category = body.optString("category").takeIf { it in CATEGORIES } ?: return null
            val scope = body.optString("scope").takeIf { it == "global" || it.startsWith("app:") && it.length in 5..80 }
                ?: return null
            val subject = body.optString("subject").takeIf { it.length in 1..96 } ?: return null
            val sourceKind = body.optString("source_kind").takeIf { it == "user_statement" } ?: return null
            val evidence = body.optString("evidence").takeIf { it.length in 3..200 } ?: return null
            val runs = body.optJSONArray("source_runs").limitedStrings(8, 64) { it.matches(Regex("[a-f0-9]{32,64}")) }
            val entities = body.optJSONArray("entities").limitedStrings(8, 48)
            val links = body.optJSONArray("links")?.let { arr ->
                (0 until arr.length().coerceAtMost(12))
                    .mapNotNull { arr.optJSONObject(it)?.let(RetainedRelation::parse) }
                    .distinctBy { it.targetId to it.type }
            }.orEmpty()
            val confidence = body.optDouble("confidence", -1.0).takeIf { it in 0.0..1.0 }
                ?: return null
            val created = body.optLong("created_at").takeIf { it > 0L } ?: return null
            val updated = body.optLong("updated_at").takeIf { it >= created } ?: return null
            val valid = body.optLong("valid_from").takeIf { it > 0L } ?: created
            return RetainedMemoryNode(
                id, text, layer, category, scope, subject, entities, sourceKind, evidence,
                runs, confidence, links, created, updated, valid,
                body.optLong("invalidated_at").coerceAtLeast(0L),
                body.optLong("deleted_at").coerceAtLeast(0L),
                body.optInt("occurrences", 1).coerceIn(1, 1000000),
            )
        }
    }
}

internal data class RetainedMemorySnapshot(
    val items: List<RetainedMemoryNode>,
    val clearedAt: Long = 0L,
) {
    fun json(): String = JSONObject()
        .put("schema", 1).put("cleared_at", clearedAt)
        .put("items", JSONArray().also { array -> items.forEach { array.put(it.json()) } })
        .toString()

    companion object {
        val EMPTY = RetainedMemorySnapshot(emptyList())
        fun fromJson(raw: String): RetainedMemorySnapshot {
            if (raw.isBlank()) return EMPTY
            require(raw.toByteArray(Charsets.UTF_8).size <= RetainedMemoryStore.MAX_BYTES)
            val root = JSONObject(raw)
            require(root.optInt("schema") == 1) { "Unsupported Retain memory schema" }
            val cleared = root.optLong("cleared_at").coerceAtLeast(0L)
            val array = root.optJSONArray("items") ?: JSONArray()
            require(array.length() <= RetainedMemoryStore.MAX_ITEMS * 2)
            val items = (0 until array.length()).map { position ->
                RetainedMemoryNode.parse(
                    array.optJSONObject(position) ?: error("Invalid retained memory record"),
                ) ?: error("Invalid retained memory record at $position")
            }.filter { it.updatedAt > cleared }
            require(items.distinctBy { it.id }.size == items.size) { "Duplicate retained memory IDs" }
            return RetainedMemorySnapshot(items, cleared)
        }
    }
}

internal data class RetainCandidate(
    val text: String,
    val layer: String,
    val category: String,
    val scope: String,
    val subject: String,
    val evidence: String,
    val entities: List<String>,
    val links: List<RetainedRelation>,
    val supersedesId: String? = null,
    val confidence: Double = 0.85,
)

/** Versioned upsert rules for future semantic search and graph-based memory recall. */
internal object RetainedMemoryMerge {
    fun merge(left: RetainedMemorySnapshot, right: RetainedMemorySnapshot): RetainedMemorySnapshot {
        val cutoff = maxOf(left.clearedAt, right.clearedAt)
        val result = (left.items + right.items).filter { it.updatedAt > cutoff }
            .groupBy { it.id }
            .values.map { records ->
                records.reduce { a, b ->
                    val newer = if (b.updatedAt > a.updatedAt ||
                        b.updatedAt == a.updatedAt && b.deletedAt > a.deletedAt) b else a
                    newer.copy(
                        createdAt = minOf(a.createdAt, b.createdAt),
                        sourceRuns = (a.sourceRuns + b.sourceRuns).distinct().takeLast(8),
                        entities = (a.entities + b.entities).distinct().take(8),
                        links = RetainedRelation.mergeLinks(a.links, b.links),
                        invalidatedAt = maxOf(a.invalidatedAt, b.invalidatedAt),
                        deletedAt = maxOf(a.deletedAt, b.deletedAt),
                        occurrences = maxOf(a.occurrences, b.occurrences),
                    )
                }
            }.sortedWith(compareByDescending<RetainedMemoryNode> { it.updatedAt }.thenBy { it.id })
        require(result.size <= RetainedMemoryStore.MAX_ITEMS) { "Retained memory capacity exceeded; no records discarded" }
        return RetainedMemorySnapshot(result, cutoff)
    }
}

internal class RetainedMemoryStore(rootDir: File) {
    private val directory = File(rootDir, "memory")
    private val file = DurableAtomicFile(File(directory, "retained_memories.json"))
    private val lockFile = File(directory, "retained_memories.lock")
    private val monitor = Any()

    fun snapshot(): RetainedMemorySnapshot = locked { readLocked() }
    fun count(): Int = snapshot().items.count { it.active }
    fun visible(limit: Int = 400): List<RetainedMemoryNode> =
        snapshot().items.asSequence().filter { it.deletedAt == 0L }
            .sortedByDescending { it.updatedAt }.take(limit).toList()
    fun active(limit: Int = 30): List<RetainedMemoryNode> =
        snapshot().items.asSequence().filter(RetainedMemoryNode::active)
            .sortedByDescending { it.updatedAt }.take(limit).toList()

    fun retain(
        candidates: List<RetainCandidate>,
        sourceRunHash: String,
        now: Long = System.currentTimeMillis(),
    ): List<RetainedMemoryNode> = locked {
        require(sourceRunHash.matches(Regex("[a-f0-9]{32,64}")))
        val state = readLocked()
        val committedAt = maxOf(now, state.clearedAt + 1L)
        val items = state.items.toMutableList()
        val remembered = mutableListOf<RetainedMemoryNode>()
        candidates.take(3).forEach { raw ->
            require(raw.layer in RetainedMemoryNode.LAYERS)
            require(raw.category in RetainedMemoryNode.CATEGORIES)
            require(raw.scope == "global" || raw.scope.startsWith("app:") && raw.scope.length in 5..80)
            require(raw.text.length in 8..280 && raw.subject.length in 1..96)
            require(raw.evidence.length in 3..200)
            val normalized = raw.text.trim().lowercase().replace(Regex("\\s+"), " ")
            val key = "${raw.scope}|${raw.layer}|${raw.category}|${normalized}"
            val id = UUID.nameUUIDFromBytes(("eta-retain-v1:" + key).toByteArray(Charsets.UTF_8)).toString()
            val oldIndex = items.indexOfFirst { it.id == id }
            val old = items.getOrNull(oldIndex)
            if (old != null && !old.active) return@forEach // Never resurrect a deleted or superseded fact.
            if (old != null && sourceRunHash in old.sourceRuns) return@forEach
            val allowedLinks = raw.links.filter { link ->
                link.targetId != id && items.any { it.id == link.targetId && it.active && it.scope == raw.scope }
            }.distinctBy { it.targetId to it.type }.take(8)
            val record = RetainedMemoryNode(
                id = id, text = raw.text.trim(), layer = raw.layer, category = raw.category,
                scope = raw.scope, subject = raw.subject, entities = raw.entities.distinct().take(8),
                sourceKind = "user_statement", evidence = raw.evidence,
                sourceRuns = (old?.sourceRuns.orEmpty() + sourceRunHash).distinct().takeLast(8),
                confidence = raw.confidence.coerceIn(0.0, 1.0),
                links = RetainedRelation.mergeLinks(old?.links.orEmpty(), allowedLinks),
                createdAt = old?.createdAt ?: committedAt,
                updatedAt = maxOf(committedAt, (old?.updatedAt ?: 0L) + 1L),
                validFrom = old?.validFrom ?: committedAt,
                occurrences = (old?.occurrences ?: 0).plus(1).coerceAtMost(1000000),
            )
            if (oldIndex >= 0) items[oldIndex] = record else items.add(record)
            // The model can propose a replacement but cannot erase history.
            val oldFact = raw.supersedesId?.let { replaced ->
                items.find { it.id == replaced && it.active && it.scope == record.scope &&
                    it.subject == record.subject && it.id != id }
            }
            if (oldFact != null) {
                val position = items.indexOfFirst { it.id == oldFact.id }
                items[position] = oldFact.copy(
                    invalidatedAt = committedAt,
                    updatedAt = maxOf(committedAt, oldFact.updatedAt + 1L),
                )
            }
            remembered += record
        }
        require(items.size <= MAX_ITEMS) { "Retained memory limit reached" }
        if (remembered.isNotEmpty()) writeLocked(state.copy(items = items))
        remembered
    }

    fun delete(id: String, now: Long = System.currentTimeMillis()): Boolean = locked {
        val state = readLocked()
        val index = state.items.indexOfFirst { it.id == id && it.deletedAt == 0L }
        if (index < 0) return@locked false
        val items = state.items.toMutableList()
        items[index] = items[index].copy(deletedAt = now, updatedAt = now)
        writeLocked(state.copy(items = items))
        true
    }

    /** Model writes only proposals, never confirmed or rejected decisions. */
    fun proposeLinks(sourceId: String, proposed: List<RetainedRelation>,
                     now: Long = System.currentTimeMillis()): Int = locked {
        val state = readLocked()
        val source = state.items.find { it.id == sourceId && it.active } ?: return@locked 0
        val targets = state.items.filter { it.active && it.scope == source.scope }
            .associateBy { it.id }
        val accepted = proposed.filter {
            it.status == "proposed" && it.targetId != source.id && targets.containsKey(it.targetId)
        }.distinctBy { it.targetId to it.type }.take(8)
        if (accepted.isEmpty()) return@locked 0
        val existing = source.links.associateBy { it.targetId to it.type }
        val additions = accepted.filter { proposal ->
            existing[proposal.targetId to proposal.type]?.status !in setOf("confirmed", "rejected")
        }.map { it.copy(updatedAt = maxOf(now, source.updatedAt + 1L)) }
        if (additions.isEmpty()) return@locked 0
        val next = source.copy(
            links = RetainedRelation.mergeLinks(source.links, additions),
            updatedAt = maxOf(now, source.updatedAt + 1L),
        )
        writeLocked(state.copy(items = state.items.map { if (it.id == sourceId) next else it }))
        additions.size
    }

    /** Explicit user review is the only route to promoting a proposed relation. */
    fun reviewLink(sourceId: String, targetId: String, type: String,
                   status: String, now: Long = System.currentTimeMillis()): Boolean = locked {
        require(status == "confirmed" || status == "rejected")
        val state = readLocked()
        val source = state.items.find { it.id == sourceId && it.active } ?: return@locked false
        val target = state.items.find { it.id == targetId && it.active &&
            it.scope == source.scope } ?: return@locked false
        val link = source.links.find { it.targetId == target.id && it.type == type }
            ?: return@locked false
        val moment = maxOf(now, source.updatedAt + 1L, link.updatedAt + 1L)
        val next = source.copy(
            links = source.links.map {
                if (it.targetId == targetId && it.type == type) {
                    it.copy(status = status, updatedAt = moment)
                } else it
            },
            updatedAt = moment,
        )
        writeLocked(state.copy(items = state.items.map { if (it.id == sourceId) next else it }))
        true
    }

    fun clear(now: Long = System.currentTimeMillis()) = locked {
        val previous = readLocked()
        writeLocked(RetainedMemorySnapshot(emptyList(), maxOf(now, previous.clearedAt)))
    }

    fun replaceIfUnchanged(
        expected: RetainedMemorySnapshot, replacement: RetainedMemorySnapshot,
    ): Boolean = locked {
        if (readLocked() != expected) return@locked false
        writeLocked(replacement)
        true
    }

    private fun readLocked(): RetainedMemorySnapshot {
        if (!file.exists()) return RetainedMemorySnapshot.EMPTY
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size <= MAX_BYTES)
        return RetainedMemorySnapshot.fromJson(bytes.toString(Charsets.UTF_8))
    }

    private fun writeLocked(state: RetainedMemorySnapshot) {
        require(state.items.size <= MAX_ITEMS)
        val bytes = state.json().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (failure: Throwable) {
            file.failWrite(out)
            throw failure
        }
    }

    private fun <T> locked(action: () -> T): T = synchronized(monitor) {
        check(directory.isDirectory || directory.mkdirs())
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val locked = channel.lock()
            try { action() } finally { locked.release() }
        }
    }

    companion object {
        const val MAX_ITEMS = 400
        const val MAX_BYTES = 1024 * 1024
    }
}

internal object AgentRetainedMemoryRepository {
    @Volatile private lateinit var store: RetainedMemoryStore
    fun init(context: Context) {
        if (!::store.isInitialized) store = RetainedMemoryStore(context.applicationContext.filesDir)
    }
    fun snapshot(): RetainedMemorySnapshot = checked().snapshot()
    fun active(limit: Int = 30): List<RetainedMemoryNode> = checked().active(limit)
    fun visible(limit: Int = 400): List<RetainedMemoryNode> = checked().visible(limit)
    fun count(): Int = checked().count()
    fun retain(candidates: List<RetainCandidate>, sourceRunHash: String): List<RetainedMemoryNode> =
        checked().retain(candidates, sourceRunHash)
    fun delete(id: String): Boolean = checked().delete(id)
    fun proposeLinks(id: String, links: List<RetainedRelation>): Int =
        checked().proposeLinks(id, links)
    fun reviewLink(source: String, target: String, type: String, status: String): Boolean =
        checked().reviewLink(source, target, type, status)
    fun clear() = checked().clear()
    fun replaceIfUnchanged(old: RetainedMemorySnapshot, replacement: RetainedMemorySnapshot): Boolean =
        checked().replaceIfUnchanged(old, replacement)
    private fun checked(): RetainedMemoryStore {
        check(::store.isInitialized) { "AgentRetainedMemoryRepository.init(context) missing" }
        return store
    }
}

private fun isUuid(value: String): Boolean =
    value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))

private fun JSONArray?.limitedStrings(
    maximum: Int, maxLength: Int, predicate: (String) -> Boolean = { true },
): List<String> {
    if (this == null) return emptyList()
    return (0 until length().coerceAtMost(maximum)).mapNotNull { position ->
        optString(position).trim().takeIf { it.isNotEmpty() && it.length <= maxLength && predicate(it) }
    }.distinct()
}
