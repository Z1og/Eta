package io.github.mangi.eta.data.repository

import org.json.JSONArray
import org.json.JSONObject

internal data class ExperienceSyncSnapshot(
    val items: List<AgentTaskExperience>,
    val clearedAt: Long,
) {
    fun json(): String = JSONObject()
        .put("schema", 1)
        .put("cleared_at", clearedAt)
        .put("items", JSONArray().also { array ->
            items.forEach { e ->
                array.put(JSONObject()
                    .put("id", e.id).put("tags", JSONArray(e.tags))
                    .put("tools", JSONArray(e.tools)).put("errors", JSONArray(e.errors))
                    .put("run_completed", e.runCompleted)
                    .put("first_seen", e.firstSeen).put("last_seen", e.lastSeen)
                    .put("occurrences", e.occurrences))
            }
        }).toString()

    companion object {
        val EMPTY = ExperienceSyncSnapshot(emptyList(), 0L)
        private val SAFE_TAG = Regex("[\\p{L}\\p{N}_/ -]{1,64}")
        private val SAFE_TOOL = Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")
        private val SAFE_CODE = Regex("[A-Z][A-Z0-9_]{2,63}")
        fun fromJson(raw: String): ExperienceSyncSnapshot {
            if (raw.isBlank()) return EMPTY
            val obj = JSONObject(raw)
            require(obj.optInt("schema") == 1)
            val cutoff = obj.optLong("cleared_at").coerceAtLeast(0L)
            val arr = obj.optJSONArray("items") ?: JSONArray()
            require(arr.length() <= AgentExperienceStore.MAX_ENTRIES * 2)
            val items = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optString("id").takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
                    ?: return@mapNotNull null
                fun strings(key: String, max: Int, allowed: Regex): List<String> {
                    val array = e.optJSONArray(key) ?: return emptyList()
                    return (0 until array.length().coerceAtMost(max))
                        .mapNotNull { array.optString(it).takeIf(allowed::matches) }
                }
                val tags = strings("tags", 8, SAFE_TAG)
                val tools = strings("tools", 16, SAFE_TOOL)
                val errors = strings("errors", 8, SAFE_CODE)
                val last = e.optLong("last_seen")
                if (last <= cutoff || tools.isEmpty() || tags.isEmpty()) return@mapNotNull null
                AgentTaskExperience(id, tags, tools, errors,
                    e.optBoolean("run_completed"),
                    e.optLong("first_seen").takeIf { it > 0L } ?: last,
                    last, e.optInt("occurrences", 1).coerceIn(1, 1_000_000))
            }
            return ExperienceSyncSnapshot(items.distinctBy { it.id }
                .sortedByDescending { it.lastSeen }.take(AgentExperienceStore.MAX_ENTRIES), cutoff)
        }

        fun merge(local: ExperienceSyncSnapshot, remote: ExperienceSyncSnapshot): ExperienceSyncSnapshot {
            val clearedAt = maxOf(local.clearedAt, remote.clearedAt)
            val merged = (local.items + remote.items)
                .filter { it.lastSeen > clearedAt }
                .groupBy { it.id }
                .values.map { variants ->
                    variants.maxWith(compareBy<AgentTaskExperience> { it.lastSeen }
                        .thenBy { it.occurrences })
                }.sortedByDescending { it.lastSeen }
                .take(AgentExperienceStore.MAX_ENTRIES)
            return ExperienceSyncSnapshot(merged, clearedAt)
        }
    }
}
