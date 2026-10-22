package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

internal data class EtaWebDavRemote(val etag: String, val blob: ByteArray)

/** The WebDAV URL is an existing collection. Eta creates only eta-sync-v1/ beneath it. */
internal class EtaWebDavTransport(
    rawEndpoint: String,
    private val username: String,
    private val password: String,
) {
    private val endpoint: HttpUrl = validateEndpoint(rawEndpoint)
    private val directory: HttpUrl = endpoint.newBuilder()
        .addPathSegment("eta-sync-v1").addPathSegment("").build()
    private val resource: HttpUrl = directory.newBuilder().addPathSegment("state.bin").build()
    private val client = AgentHttpClient.client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build()

    fun ensureCollection() {
        val response = perform("MKCOL", directory)
        response.use {
            if (it.code !in setOf(201, 405)) {
                throw statusFailure("MKCOL", it.code)
            }
        }
    }

    fun read(): EtaWebDavRemote? {
        perform("GET", resource).use { response ->
            if (response.code == 404) return null
            if (response.code != 200) throw statusFailure("GET", response.code)
            val etag = response.header("ETag")?.trim().orEmpty()
            require(etag.matches(STRONG_ETAG)) {
                "WebDAV server must return a strong ETag for conditional updates"
            }
            val bytes = bounded(response.body?.byteStream()
                ?: throw IOException("WebDAV returned no body"))
            return EtaWebDavRemote(etag, bytes)
        }
    }

    /**
     * Only conditional writes are permitted. HTTP 412 means someone else changed the document
     * after GET, so the caller must fetch, merge and retry; unconditional overwrites are forbidden.
     */
    fun write(blob: ByteArray, knownEtag: String?): Boolean {
        require(blob.size <= EtaWebDavCrypto.MAX_BLOB_BYTES)
        val body = blob.toRequestBody("application/octet-stream".toMediaType())
        perform("PUT", resource, body, if (knownEtag == null) "If-None-Match" else "If-Match",
            knownEtag ?: "*").use { response ->
            return when (response.code) {
                200, 201, 204 -> true
                409, 412 -> false
                else -> throw statusFailure("PUT", response.code)
            }
        }
    }

    private fun perform(
        method: String,
        url: HttpUrl,
        body: okhttp3.RequestBody? = null,
        conditionName: String? = null,
        conditionValue: String? = null,
    ): okhttp3.Response {
        val builder = Request.Builder().url(url).method(method, body)
        if (username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
        }
        if (conditionName != null && conditionValue != null) {
            builder.header(conditionName, conditionValue)
        }
        builder.header("Cache-Control", "no-store")
        return client.newCall(builder.build()).execute()
    }

    private fun bounded(input: java.io.InputStream): ByteArray = input.use {
        val buffer = ByteArray(16_384)
        val output = ByteArrayOutputStream()
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            if (output.size() + read > EtaWebDavCrypto.MAX_BLOB_BYTES) {
                throw IOException("WebDAV sync object exceeds safety limit")
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun statusFailure(operation: String, status: Int): IOException =
        IOException("WebDAV $operation failed: HTTP $status")

    companion object {
        private val STRONG_ETAG = Regex("\"[^\\r\\n]*\"")
        fun validateEndpoint(raw: String): HttpUrl {
            require(raw.length in 1..2048 && raw == raw.trim()) { "Invalid WebDAV URL" }
            val parsed = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid WebDAV URL")
            require(parsed.scheme == "https") { "WebDAV sync requires HTTPS" }
            require(parsed.username.isBlank() && parsed.password.isBlank()) {
                "Put WebDAV login credentials in the separate fields, not in the URL"
            }
            require(parsed.query == null && parsed.fragment == null) { "WebDAV URL cannot include query or fragment" }
            return parsed
        }
    }
}
