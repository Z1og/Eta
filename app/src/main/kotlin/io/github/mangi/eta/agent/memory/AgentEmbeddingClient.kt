package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderTypes
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

internal data class EtaEmbeddingEndpoint(
    val url: String,
    val model: String,
    val apiKey: String,
) {
    companion object {
        /** Embeddings are a separate API; a chat model name must not be used implicitly. */
        fun fromConfig(config: AgentModelClient.ModelConfig, model: String): EtaEmbeddingEndpoint {
            require(config.providerType == ProviderTypes.OPENAI_COMPATIBLE) {
                "The selected provider does not expose an OpenAI-compatible embeddings API"
            }
            require(model.matches(Regex("[a-zA-Z0-9._:/-]{1,100}"))) {
                "Provide a valid embedding model identifier"
            }
            val base = config.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Embedding provider URL is invalid")
            require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() &&
                base.query == null && base.fragment == null) {
                "Embedding requests need HTTPS without URL credentials or query parameters"
            }
            val target = base.newBuilder().addPathSegment("embeddings").build()
            return EtaEmbeddingEndpoint(target.toString(), model, config.apiKey)
        }
    }
}

internal fun interface EtaEmbeddingProvider {
    fun embed(inputs: List<String>, controller: AgentRunController): List<FloatArray>
}

/** Standard POST /embeddings with bounded batch, dimensions, response and timeouts. */
internal class EtaOpenAiEmbeddingProvider(
    private val endpoint: EtaEmbeddingEndpoint,
) : EtaEmbeddingProvider {
    private val client = AgentHttpClient.client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun embed(inputs: List<String>, controller: AgentRunController): List<FloatArray> {
        require(inputs.isNotEmpty() && inputs.size <= 24)
        require(inputs.all { it.isNotBlank() && it.length <= 2400 })
        controller.throwIfCancelled()
        val payload = JSONObject()
            .put("model", endpoint.model)
            .put("input", JSONArray(inputs))
            .put("encoding_format", "float")
        val request = Request.Builder()
            .url(endpoint.url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .apply {
                if (endpoint.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${endpoint.apiKey}")
                }
            }.build()
        val call = client.newCall(request)
        val binding = controller.register { call.cancel() }
        return try {
            call.execute().use { response ->
                controller.throwIfCancelled()
                if (!response.isSuccessful) {
                    throw IOException("Embedding request rejected: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty embeddings response")
                require(body.contentLength() <= MAX_RESPONSE_BYTES) { "Embeddings response is too large" }
                val raw = body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= MAX_RESPONSE_BYTES) { "Embeddings response is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                parseVectors(raw, inputs.size)
            }
        } finally {
            binding.close()
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        fun parseVectors(body: String, count: Int): List<FloatArray> {
            val result = JSONObject(body)
            val data = result.optJSONArray("data") ?: error("Embedding response has no data")
            require(data.length() == count) { "Embedding response count mismatch" }
            val vectors = arrayOfNulls<FloatArray>(count)
            for (position in 0 until data.length()) {
                val row = data.getJSONObject(position)
                val index = row.getInt("index")
                require(index in 0 until count && vectors[index] == null) { "Invalid embedding index" }
                val coordinates = row.getJSONArray("embedding")
                require(coordinates.length() in 32..3072) { "Embedding dimension is unsupported" }
                val values = FloatArray(coordinates.length()) { coordinate ->
                    val value = coordinates.getDouble(coordinate)
                    require(value.isFinite() && kotlin.math.abs(value) <= 100000.0) {
                        "Non-finite embedding value"
                    }
                    value.toFloat()
                }
                vectors[index] = normalize(values)
            }
            val valid = vectors.filterNotNull()
            require(valid.size == count && valid.map(FloatArray::size).distinct().size == 1) {
                "Embeddings have inconsistent dimensions"
            }
            return valid
        }

        fun normalize(values: FloatArray): FloatArray {
            var squared = 0.0
            values.forEach { v ->
                require(v.isFinite())
                squared += v.toDouble() * v
            }
            require(squared > 1e-16 && squared.isFinite()) { "Zero-length embedding" }
            val magnitude = sqrt(squared)
            return FloatArray(values.size) { idx -> (values[idx] / magnitude).toFloat() }
        }

        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return -1.0
            var dot = 0.0
            a.indices.forEach { index -> dot += a[index].toDouble() * b[index] }
            return dot.coerceIn(-1.0, 1.0)
        }
    }
}
