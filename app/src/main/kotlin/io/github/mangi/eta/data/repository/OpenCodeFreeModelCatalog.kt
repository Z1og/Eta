package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * opencode2api and OpenCode Zen both return OpenAI-style /v1/models data.
 * A name containing "free" or an explicit zero input+output cost is
 * evidence for *catalog eligibility*, not proof of successful inference.
 */
internal object OpenCodeFreeModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val freeToken = Regex("(^|[-_./])free($|[-_./])", RegexOption.IGNORE_CASE)

    fun parseFreeModelIds(body: String): Set<String> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("OpenCode 模型目录不是有效 JSON")
        val data = root["data"] as? JsonArray
            ?: throw IllegalArgumentException("OpenCode /v1/models 没有 data 数组")
        require(data.size <= 2_000) { "OpenCode 模型目录过大" }
        return data.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty() || id.length > 200 || !eligible(item, id)) null else id
        }.toSet()
    }

    internal fun eligible(model: JsonObject, id: String): Boolean {
        if (deprecated(model)) return false
        if (freeToken.containsMatchIn(id)) return true
        val cost = (model["cost"] ?: model["pricing"]) as? JsonObject ?: return false
        return cost.isExplicitlyZero("input") && cost.isExplicitlyZero("output")
    }

    private fun deprecated(model: JsonObject): Boolean =
        (model["deprecated"] as? JsonPrimitive)?.contentOrNull.equals("true", ignoreCase = true) ||
            (model["status"] as? JsonPrimitive)?.contentOrNull.equals("deprecated", ignoreCase = true)

    private fun JsonObject.isExplicitlyZero(field: String): Boolean =
        (this[field] as? JsonPrimitive)?.doubleOrNull == 0.0

    /**
     * models.dev's public OpenCode provider directory is an emergency metadata
     * source only. It may be stale, and listing does not establish inference access.
     */
    fun parseModelsDev(body: String, freeOnly: Boolean): List<Model> {
        require(body.length <= 9_000_000) { "models.dev 模型目录超过容量限制" }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("models.dev 返回的模型目录不是有效 JSON")
        val catalog = (root["opencode"] as? JsonObject)?.get("models") as? JsonObject
            ?: throw IllegalArgumentException("models.dev 没有 OpenCode 模型目录")
        require(catalog.size <= 2_000) { "models.dev 模型目录过大" }
        return catalog.mapNotNull { (id, element) ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (id.isBlank() || id.length > 200 || deprecated(item) ||
                freeOnly && !eligible(item, id)
            ) return@mapNotNull null
            val modalities = item["modalities"] as? JsonObject
            val inputs = (modalities?.get("input") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            val outputs = (modalities?.get("output") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            if (outputs.isNotEmpty() && "text" !in outputs) return@mapNotNull null
            val limit = item["limit"] as? JsonObject
            Model(
                id = UUID.randomUUID().toString(),
                modelId = id,
                displayName = (item["name"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: id,
                ownedBy = "opencode",
                source = ModelSource.REMOTE,
                contextWindow = (limit?.get("context") as? JsonPrimitive)?.intOrNull
                    ?.takeIf { it > 0 },
                inputModalities = inputs,
                outputModalities = outputs,
                attachment = (item["attachment"] as? JsonPrimitive)?.booleanOrNull,
                toolCall = (item["tool_call"] as? JsonPrimitive)?.booleanOrNull,
                reasoning = (item["reasoning"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }

    fun identifyOfficialOpenCodeUrl(baseUrl: String): Boolean =
        runCatching {
            val url = baseUrl.trim().toHttpUrlOrNull() ?: return@runCatching false
            url.isHttps && url.host == "opencode.ai" && url.encodedPath.startsWith("/zen")
        }.getOrDefault(false)
}
