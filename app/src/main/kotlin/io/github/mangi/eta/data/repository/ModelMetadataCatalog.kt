package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
    this as? kotlinx.serialization.json.JsonArray

private fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/**
 * 模型元数据目录——为不返回能力元数据的 OpenAI 兼容端点（中转站、自建网关等）
 * 自动补齐 contextWindow 与 reasoning 能力。
 *
 * 两级来源，只填空（provider 自报的字段永远优先）：
 * 1. [OPENROUTER_MODELS_URL] 公开目录（无需鉴权），按 modelId 匹配，24h 内存缓存
 * 2. [heuristicFamilies] 静态家族表：无网 / 目录未收录时的保守兜底
 *
 * 任何失败都静默降级：最坏情况等于不补齐，不影响正常请求链路。
 * 对话流量不经过 OpenRouter，这里只是查"模型说明书"。
 */
internal object ModelMetadataCatalog {

    private const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val FETCH_TIMEOUT_SECONDS = 6L

    private val json = Json { ignoreUnknownKeys = true }

    /** 单个模型的已知元数据；null 字段表示不补齐该维度。 */
    data class CatalogEntry(
        val contextWindow: Int? = null,
        val reasoning: Boolean? = null,
        val capabilities: ModelReasoningCapabilities? = null,
    )

    private data class Cache(val loadedAt: Long, val byKey: Map<String, CatalogEntry>)

    @Volatile
    private var cache: Cache? = null
    private val loadMutex = Mutex()

    private val openAiEfforts = listOf(
        ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
    )
    private val anthropicEfforts = listOf(
        ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH, ReasoningEffort.MAX,
    )

    // ─────────────────────────────────────────────────────────
    // 方案 B：静态家族表（最长前缀优先）。窗口取保守值——
    // 估小会提前压缩（安全），估大会溢出无保护（危险）。
    // ─────────────────────────────────────────────────────────

    private data class Family(val prefix: String, val entry: CatalogEntry)

    private val heuristicFamilies: List<Family> = listOf(
        // OpenAI
        Family("gpt-5", CatalogEntry(
            contextWindow = 272_000,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(
                supportedEfforts = openAiEfforts, canDisable = true, defaultEnabled = false,
            ),
        )),
        Family("o3", CatalogEntry(
            contextWindow = 200_000,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = openAiEfforts),
        )),
        Family("o4-mini", CatalogEntry(
            contextWindow = 200_000,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = openAiEfforts),
        )),
        // Anthropic：标准 200k；id 带 1m 标记走 1M beta
        Family("claude", CatalogEntry(
            contextWindow = 200_000,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(
                supportedEfforts = anthropicEfforts,
                canDisable = true,
                supportsBudget = true,
                defaultEnabled = false,
            ),
        )),
        // DeepSeek
        Family("deepseek-reasoner", CatalogEntry(
            contextWindow = 131_072,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(mandatory = true, defaultEnabled = true),
        )),
        Family("deepseek-r1", CatalogEntry(
            contextWindow = 131_072,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(mandatory = true, defaultEnabled = true),
        )),
        Family("deepseek", CatalogEntry(contextWindow = 131_072)),
        // Qwen
        Family("qwen3", CatalogEntry(
            contextWindow = 262_144,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(
                supportedEfforts = anthropicEfforts, canDisable = true, defaultEnabled = false,
            ),
        )),
        Family("qwen-max", CatalogEntry(contextWindow = 32_768)),
        Family("qwen", CatalogEntry(contextWindow = 131_072)),
        // Moonshot / Zhipu / MiniMax
        Family("kimi-k3", CatalogEntry(
            contextWindow = 262_144,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = anthropicEfforts, canDisable = true),
        )),
        Family("kimi", CatalogEntry(
            contextWindow = 262_144,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = anthropicEfforts, canDisable = true),
        )),
        Family("glm-5", CatalogEntry(
            contextWindow = 204_800,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = anthropicEfforts, canDisable = true),
        )),
        Family("glm-4.5", CatalogEntry(
            contextWindow = 131_072,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = anthropicEfforts, canDisable = true),
        )),
        Family("glm", CatalogEntry(contextWindow = 131_072)),
        Family("minimax-m", CatalogEntry(
            contextWindow = 1_000_000,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = anthropicEfforts, canDisable = true),
        )),
        // Google
        Family("gemini-3", CatalogEntry(
            contextWindow = 1_048_576,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(
                supportedEfforts = openAiEfforts, canDisable = true, defaultEnabled = false,
            ),
        )),
        Family("gemini-2.5", CatalogEntry(
            contextWindow = 1_048_576,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(
                supportedEfforts = openAiEfforts, canDisable = true, defaultEnabled = false,
            ),
        )),
        // xAI
        Family("grok-4", CatalogEntry(
            contextWindow = 256_000,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = openAiEfforts),
        )),
        Family("grok-3", CatalogEntry(contextWindow = 131_072)),
        // 字节
        Family("doubao-seed", CatalogEntry(
            contextWindow = 256_000,
            reasoning = true,
            capabilities = ModelReasoningCapabilities(supportedEfforts = anthropicEfforts, canDisable = true),
        )),
        Family("doubao", CatalogEntry(contextWindow = 131_072)),
    )

    // ─────────────────────────────────────────────────────────
    // 入口
    // ─────────────────────────────────────────────────────────

    /** 只为缺失元数据的模型补齐；全部齐备时不发起任何网络请求。 */
    suspend fun enrich(models: List<Model>): List<Model> {
        if (models.none { it.contextWindow == null || it.reasoningCapabilities == null }) return models
        val catalog = loadCatalogOrNull() ?: emptyMap()
        return enrichWith(models, catalog)
    }

    /** 纯函数补齐，便于测试与无网复用。 */
    fun enrichWith(models: List<Model>, catalog: Map<String, CatalogEntry>): List<Model> =
        models.map { model ->
            val entry = entryFor(model.modelId, catalog) ?: return@map model
            model.copy(
                contextWindow = model.contextWindow ?: entry.contextWindow,
                reasoning = model.reasoning ?: entry.reasoning,
                reasoningCapabilities = model.reasoningCapabilities ?: entry.capabilities,
            )
        }

    /** 匹配单个 modelId：先查目录，再退静态家族表。 */
    fun entryFor(modelId: String, catalog: Map<String, CatalogEntry>): CatalogEntry? {
        val key = normalizeForMatch(modelId)
        if (key.isEmpty()) return null
        catalog[key]?.let { return it }
        return heuristicEntry(modelId)
    }

    // ─────────────────────────────────────────────────────────
    // 方案 A：OpenRouter 公开目录
    // ─────────────────────────────────────────────────────────

    private suspend fun loadCatalogOrNull(): Map<String, CatalogEntry>? =
        withContext(Dispatchers.IO) {
            cache?.takeIf { System.currentTimeMillis() - it.loadedAt < CACHE_TTL_MS }?.byKey
                ?: loadMutex.withLock {
                    cache?.takeIf { System.currentTimeMillis() - it.loadedAt < CACHE_TTL_MS }?.byKey
                        ?: runCatching { fetchOpenRouterCatalog() }.getOrNull()?.also {
                            cache = Cache(System.currentTimeMillis(), it)
                        }
                }
        }

    private fun fetchOpenRouterCatalog(): Map<String, CatalogEntry> {
        val client = AgentHttpClient.client.newBuilder()
            .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(OPENROUTER_MODELS_URL)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyMap()
            val body = response.body.string()
            val root = json.parseToJsonElement(body).jsonObjectOrNull() ?: return emptyMap()
            val data = root["data"]?.jsonArrayOrNull() ?: return emptyMap()
            return buildMap {
                data.forEach { element ->
                    val obj = element.jsonObjectOrNull() ?: return@forEach
                    val id = obj["id"]?.stringOrNull()?.trim()?.lowercase() ?: return@forEach
                    if (id.isEmpty()) return@forEach
                    val contextLength = obj["context_length"]?.stringOrNull()?.toIntOrNull()
                        ?: obj["context_length"]?.let { (it as? JsonPrimitive)?.longOrNull?.toInt() }
                    val params = obj["supported_parameters"]?.jsonArrayOrNull()
                        ?.mapNotNull { it.stringOrNull() }
                        .orEmpty()
                    val entry = CatalogEntry(
                        contextWindow = contextLength?.takeIf { it > 0 },
                        reasoning = null,
                        capabilities = openRouterCapabilities(params),
                    )
                    // 主键：规范化 id；副键：去掉 vendor 前缀的尾部名
                    put(normalizeForMatch(id), entry)
                    val tail = id.substringAfterLast('/')
                    put(normalizeForMatch(tail), entry)
                }
            }
        }
    }

    /** OpenRouter 的 supported_parameters → Eta 推理能力。 */
    private fun openRouterCapabilities(params: List<String>): ModelReasoningCapabilities? {
        val hasEffort = "reasoning_effort" in params
        val hasBudget = "thinking_budget" in params || "reasoning_budget" in params
        val hasToggle = "reasoning" in params || "include_reasoning" in params || "enable_thinking" in params
        if (!hasEffort && !hasBudget && !hasToggle) return null
        return when {
            hasEffort -> ModelReasoningCapabilities(
                supportedEfforts = openAiEfforts,
                canDisable = true,
                defaultEnabled = false,
            )
            hasBudget -> ModelReasoningCapabilities(
                supportedEfforts = anthropicEfforts,
                canDisable = true,
                supportsBudget = true,
                defaultEnabled = false,
            )
            else -> ModelReasoningCapabilities(
                canDisable = true,
                defaultEnabled = true,
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    // 匹配工具
    // ─────────────────────────────────────────────────────────

    /**
     * 规范化用于匹配：小写、去掉日期后缀与常见变体后缀、去掉分隔符。
     * 例：`Claude-Sonnet-4-5-20250929` → `claudesonnet45`；
     *     `anthropic/claude-sonnet-4.5` → `claudesonnet45`。
     */
    internal fun normalizeForMatch(modelId: String): String {
        var id = modelId.trim().lowercase()
        val slash = id.lastIndexOf('/')
        if (slash >= 0 && id.indexOf('/') != slash) id = id.substring(slash + 1)
        id = id.replace(Regex("-(20\\d{6})$"), "")
        id = id.replace(Regex("-(latest|preview|exp|experimental|stable)$"), "")
        id = id.replace(Regex("[^a-z0-9]"), "")
        return id
    }

    private fun heuristicEntry(modelId: String): CatalogEntry? {
        val id = modelId.trim().lowercase()
        if (id.isBlank()) return null
        // claude 1M beta 标记优先
        if ("claude" in id && "1m" in id) {
            heuristicFamilies.firstOrNull { it.prefix == "claude" }?.let { family ->
                return family.entry.copy(contextWindow = 1_000_000)
            }
        }
        val stripped = normalizeForMatch(id)
        return heuristicFamilies
            .filter { stripped.startsWith(normalizeForMatch(it.prefix)) }
            .maxByOrNull { it.prefix.length }
            ?.entry
    }
}
