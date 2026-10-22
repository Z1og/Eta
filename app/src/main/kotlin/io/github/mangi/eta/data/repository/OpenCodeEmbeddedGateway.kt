package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.provider.BuiltinProviders
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages an independently built, GPL-licensed opencode2api Android/ARM64 process.
 * No privileged shell, listening on external interfaces or management web UI.
 * The existing provider's remote URL/key remain untouched as an alternate mode.
 */
internal object OpenCodeEmbeddedGateway {
    private const val PREFS = "eta_embedded_opencode2api"
    private const val ENABLED = "local_gateway_enabled"
    private const val LOCAL_KEY = "local_gateway_secret"
    private const val LOCAL_PORT = "local_gateway_port"
    private const val ZEN_KEY = "upstream_zen_key"
    private const val GO_KEY = "upstream_go_key"
    private const val UPSTREAM_PROXY = "upstream_proxy_url"
    private const val BINARY = "libeta_opencode2api.so"
    // Public, credential-free opencode2api v3 cache captured from the same source
    // revision as the bundled Android binary. A live refresh supersedes it.
    private const val BOOTSTRAP_CATALOG = "opencode2api-bootstrap-catalog-v3.json"
    private const val MODEL_CACHE_SUFFIX = ".models.catalog.json"

    @Volatile private var application: Context? = null
    private val lock = Any()
    private var runningProcess: Process? = null
    private var runningProcessProxy: String? = null

    fun init(context: Context) {
        application = context.applicationContext
    }

    private fun context(): Context = checkNotNull(application) {
        "OpenCode embedded service is not initialized in this app process"
    }

    private fun preferences() = context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun available(): Boolean =
        File(context().applicationInfo.nativeLibraryDir ?: "", BINARY)
            .let { it.isFile && it.canExecute() }

    fun useLocal(provider: ProviderSetting): Boolean =
        provider.id == BuiltinProviders.OPENCODE_ID && enabled()

    fun enabled(): Boolean = preferences().getBoolean(ENABLED, true)

    fun setEnabled(shouldEnable: Boolean) {
        if (!shouldEnable) stop()
        check(preferences().edit().putBoolean(ENABLED, shouldEnable).commit()) {
            "无法保存 OpenCode 本地/外部模式"
        }
    }

    fun zenKey(): String = preferences().getString(ZEN_KEY, "") ?: ""
    fun goKey(): String = preferences().getString(GO_KEY, "") ?: ""

    fun setUpstreamKeys(zen: String, go: String) {
        require(zen.length <= 4096 && go.length <= 4096)
        require(zen.none { it == '\n' || it == '\r' } && go.none { it == '\n' || it == '\r' })
        synchronized(lock) {
            stopLocked()
            check(preferences().edit()
                .putString(ZEN_KEY, zen.trim())
                .putString(GO_KEY, go.trim())
                .commit()) { "无法保存 OpenCode 上游 Key" }
        }
    }

    /** Empty: follow an Android system HTTP proxy if one exists, else direct. */
    fun upstreamProxy(): String = preferences().getString(UPSTREAM_PROXY, "").orEmpty()

    internal fun validateProxy(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return ""
        if (raw == "direct") return raw
        require(raw.length <= 300 && raw.none { it.isWhitespace() }) { "代理地址格式无效" }
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in setOf("http", "https", "socks5", "socks5h") &&
            !uri.host.isNullOrBlank() && uri.port in 1..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrBlank() || uri.rawPath == "/")
        ) { "代理地址应为 http://地址:端口 或 socks5://地址:端口，不包含账号或路径" }
        return uri.toASCIIString()
    }

    fun setUpstreamProxy(value: String) {
        val normalized = validateProxy(value)
        synchronized(lock) {
            stopLocked()
            check(preferences().edit().putString(UPSTREAM_PROXY, normalized).commit()) {
                "无法保存 OpenCode 上游代理"
            }
        }
    }

    private fun androidSystemProxy(): String? = runCatching {
        val connectivity = context().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val proxy = connectivity?.defaultProxy ?: return@runCatching null
        val pac = proxy.pacFileUrl
        if (pac != null && pac != Uri.EMPTY) return@runCatching null
        val host = proxy.host?.trim()?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val encodedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        validateProxy("http://$encodedHost:${proxy.port}").ifBlank { null }
    }.getOrNull()

    fun upstreamProxyDescription(): String = when (val configured = upstreamProxy()) {
        "direct" -> "强制直连"
        "" -> if (androidSystemProxy() != null) "系统 HTTP 代理" else "直连（未检测到系统代理）"
        else -> "手动代理（${runCatching { URI(configured).scheme }.getOrDefault("未知")}）"
    }

    private fun effectiveUpstreamProxy(): String =
        upstreamProxy().ifBlank { androidSystemProxy() ?: "direct" }

    private fun localKey(): String = synchronized(lock) {
        preferences().getString(LOCAL_KEY, null)?.let { return@synchronized it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val value = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        check(preferences().edit().putString(LOCAL_KEY, value).commit())
        value
    }

    private fun localPort(): Int = synchronized(lock) {
        preferences().getInt(LOCAL_PORT, 0).takeIf { it in 1024..65535 }
            ?: ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
                socket.localPort.also {
                    check(preferences().edit().putInt(LOCAL_PORT, it).commit())
                }
            }
    }

    fun localBaseUrl(): String = "http://127.0.0.1:${localPort()}/v1"

    fun effectiveProvider(provider: ProviderSetting): ProviderSetting {
        if (!useLocal(provider)) return provider
        return (provider as? OpenAiCompatibleProviderSetting)?.copy(
            baseUrl = localBaseUrl(),
            apiKey = localKey(),
        ) ?: provider
    }

    fun effectiveModelConfig(
        provider: ProviderSetting,
        config: AgentModelClient.ModelConfig,
    ): AgentModelClient.ModelConfig {
        if (!useLocal(provider)) return config
        return config.copy(baseUrl = localBaseUrl(), apiKey = localKey())
    }

    fun isEmbeddedConfig(config: AgentModelClient.ModelConfig): Boolean =
        config.providerId == BuiltinProviders.OPENCODE_ID &&
            enabled() && config.baseUrl == localBaseUrl()

    fun ensureRunningFor(config: AgentModelClient.ModelConfig) {
        if (isEmbeddedConfig(config)) {
            ensureRunning()
            // /healthz can return HTTP 503 while the Go process is already listening.
            // Chat inference must wait for the *catalogue*, not just the listener;
            // otherwise the model loop wastes all retries on startup 503 responses.
            waitForModelCatalog()
        }
    }

    fun ensureRunningFor(provider: ProviderSetting) {
        if (useLocal(provider)) {
            ensureRunning()
            waitForModelCatalog()
        }
    }

    fun status(): String = synchronized(lock) {
        when {
            !available() -> "当前安装包不支持此 CPU 架构（本版仅 ARM64）"
            runningProcess?.isAlive == true -> "本地网关进程正在运行"
            enabled() -> "待启动（首次拉取模型/发送对话时自动启动）"
            else -> "已切换外部 API"
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        val old = runningProcess
        runningProcess = null
        runningProcessProxy = null
        if (old == null) return
        old.destroy()
        if (!old.waitFor(400, TimeUnit.MILLISECONDS)) old.destroyForcibly()
    }

    /** Must be invoked on a background thread: starts an actual child process. */
    fun ensureRunning(): String = synchronized(lock) {
        check(enabled()) { "请先开启内置 OpenCode 网关" }
        check(available()) { "未检测到随 APK 打包的 Android ARM64 opencode2api 可执行文件" }
        val desiredProxy = effectiveUpstreamProxy()
        runningProcess?.takeIf { it.isAlive }?.let {
            // System network settings can change after starting the child
            // process (Wi-Fi/mobile handoff, system proxy enabled/disabled).
            // Restart only when the effective route changed.
            if (runningProcessProxy == desiredProxy && checkHealth()) {
                return@synchronized "本地网关已启动"
            }
        }
        stopLocked()

        val folder = File(context().filesDir, "opencode2api")
        check(folder.isDirectory || folder.mkdirs()) { "无法创建本地网关配置目录" }
        val config = File(folder, "config.json")
        writeConfig(config, localPort(), localKey(), zenKey(), goKey(), desiredProxy)
        // On a clean Android install the Go gateway has no on-disk catalog.
        // A failed outbound discovery would leave /healthz at models.status=pending
        // indefinitely even when its localhost listener is healthy. Install a
        // validated *public* catalog snapshot before spawning the process;
        // the upstream's normal refresh loop remains fully enabled.
        seedModelCatalog(config) {
            context().assets.open(BOOTSTRAP_CATALOG)
        }

        val executable = File(context().applicationInfo.nativeLibraryDir, BINARY)
        val logFile = File(folder, "gateway.log")
        val command = ProcessBuilder(
            executable.absolutePath, "-config", config.absolutePath,
        ).apply {
            directory(folder)
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.to(logFile))
            environment()["HOME"] = folder.absolutePath
            environment()["TMPDIR"] = context().cacheDir.absolutePath
        }
        val process = try {
            command.start()
        } catch (error: Exception) {
            throw IllegalStateException(
                "本地网关二进制无法执行：${error.javaClass.simpleName}。请检查 Android CPU、原生库提取和系统执行权限",
                error,
            )
        }
        runningProcess = process
        runningProcessProxy = desiredProxy
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(14)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) break
            if (checkHealth()) return@synchronized "本地网关已启动（127.0.0.1）"
            Thread.sleep(160)
        }
        stopLocked()
        val reason = runCatching { logFile.readText().takeLast(420) }.getOrDefault("")
        error("本地网关未能在 14 秒内启动；${reason.replace(Regex("[\\r\\n]+"), " ").take(220)}")
    }

    /** 503 is expected until OpenCode finishes fetching its model catalogue. */
    private fun checkHealth(): Boolean = healthSnapshot() != null

    /**
     * Startup readiness and catalog readiness are different checks. The gateway
     * may be listening while its upstream discovery remains pending or failed.
     * Report only safe status labels; never expose authorization headers or logs.
     */
    fun catalogueReadiness(): String = describeCatalogueHealth(healthSnapshot())

    internal fun describeCatalogueHealth(health: JSONObject?): String {
        if (health == null) return "网关健康检查无响应；请先确认本地服务能够启动"
        val models = health.optJSONObject("models")?.optString("status")
            .orEmpty().takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,30}")) } ?: "unknown"
        val keyInfo = health.optJSONObject("keys")
        val anonymous = keyInfo?.optBoolean("anonymous", false) == true
        val keyCount = keyInfo?.optInt("total", 0) ?: 0
        val state = when (models) {
            "ready" -> "目录已就绪"
            "stale" -> "目录使用过期缓存，网关正在刷新"
            else -> "目录尚未就绪（models.status=$models）"
        }
        return "网关进程可访问；$state；匿名线路=${if (anonymous) "已启用" else "未启用"}，上游 Key 数=$keyCount。" +
            "模型目录就绪不代表上游推理请求一定成功"
    }

    internal fun isCatalogueReady(health: JSONObject?): Boolean =
        health?.optJSONObject("models")?.optString("status") in setOf("ready", "stale")

    private fun waitForModelCatalog() {
        // Cold-start upstream discovery can take longer than the previous 18 s.
        // The check returns immediately on a warm/ready gateway.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val health = healthSnapshot()
            if (isCatalogueReady(health)) return
            if (health?.optJSONObject("models")?.optString("status") == "empty") {
                error("OpenCode 内置网关已启动，但没有可用模型；" + describeCatalogueHealth(health))
            }
            Thread.sleep(350)
        }
        error("OpenCode 内置网关等待模型目录超过 60 秒；" + catalogueReadiness())
    }

    /** A pending catalogue returns HTTP 503; keep the process alive while it refreshes. */
    private fun healthSnapshot(): JSONObject? = runCatching {
        val conn = (URL("http://127.0.0.1:${localPort()}/healthz")
            .openConnection() as HttpURLConnection).apply {
                connectTimeout = 420
                readTimeout = 500
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
        try {
            if (conn.responseCode !in setOf(200, 503)) return@runCatching null
            val body = (if (conn.responseCode == 200) conn.inputStream else conn.errorStream)
                ?: return@runCatching null
            val json = body.use { stream ->
                JSONObject(stream.bufferedReader().use { it.readText().take(2048) })
            }
            json.takeIf { it.has("status") && it.has("models") && it.has("keys") }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    internal fun configuration(
        port: Int,
        key: String,
        zen: String = "",
        go: String = "",
        proxy: String = "direct",
    ): JSONObject {
        require(port in 1024..65535 && key.length >= 32)
        return JSONObject().apply {
            put("listen", "127.0.0.1:$port")
            put("server_keys", JSONArray().put(key))
            put("zen_keys", JSONArray().apply { if (zen.isNotBlank()) put(zen) })
            put("go_keys", JSONArray().apply { if (go.isNotBlank()) put(go) })
            put("anonymous", true)
            put("proxies", JSONArray().put(validateProxy(proxy).ifBlank { "direct" }))
            put("models", JSONObject().put("free_only", true).put("refresh_seconds", 300))
            put("retry", JSONObject().put("max_attempts", 2).put("timeout_seconds", 120))
            put("logging", JSONObject().put("level", "warn").put("ring_size", 100))
            put("webui", JSONObject().put("enabled", false))
        }
    }

    /** Never include gateway log contents, credentials or URLs in UI errors. */
    internal fun classifyGatewayLog(tail: String): String {
        val failure = tail.lineSequence().lastOrNull {
            it.contains("request_failed") || it.contains("anonymous_attempt_transport_failed")
        }?.lowercase() ?: return "未找到近期上游连接失败记录；可在设置页导出运行日志进一步诊断"
        return when {
            "no such host" in failure || "lookup " in failure -> "网关无法解析上游域名（DNS）"
            "certificate" in failure || "x509" in failure -> "网关的上游 TLS 证书验证失败"
            "handshake" in failure -> "与上游建立 TLS 握手失败"
            "network is unreachable" in failure || "no route to host" in failure -> "手机当前网络无法到达上游（路由不可达）"
            "connection refused" in failure -> "上游或代理拒绝连接"
            "i/o timeout" in failure || "context deadline exceeded" in failure || "deadline exceeded" in failure -> "连接上游或等待响应超时"
            "connection reset" in failure || "eof" in failure || "broken pipe" in failure -> "上游或中间网络设备提前断开了连接"
            else -> "网关所有上游连接尝试均失败；需核对网络和代理出口"
        }
    }

    fun recentGatewayFailureHint(): String {
        val log = File(context().filesDir, "opencode2api/gateway.log")
        if (!log.isFile || System.currentTimeMillis() - log.lastModified() > 180_000L) {
            return "没有近期的网关错误记录"
        }
        return runCatching {
            RandomAccessFile(log, "r").use { reader ->
                val size = minOf(reader.length(), 32_768L).toInt()
                reader.seek(reader.length() - size)
                val bytes = ByteArray(size)
                reader.readFully(bytes)
                classifyGatewayLog(String(bytes, Charsets.UTF_8))
            }
        }.getOrDefault("无法读取网关诊断信息")
    }

    /** Keep an already fetched catalog. Only bootstrap a missing cache. */
    internal fun seedModelCatalog(config: File, openBootstrap: () -> InputStream): Boolean {
        val cache = File(config.path + MODEL_CACHE_SUFFIX)
        if (cache.isFile) return false
        val payload = openBootstrap().use { it.readBytes() }
        require(payload.size in 100..262_144) { "OpenCode bootstrap catalog size invalid" }
        val catalog = JSONObject(payload.toString(Charsets.UTF_8))
        require(catalog.optInt("schema_version") == 3 &&
            catalog.optString("updated_at").isNotBlank() &&
            (catalog.optJSONArray("zen")?.length() ?: 0) > 0 &&
            catalog.optJSONObject("native_protocols") != null &&
            catalog.optJSONObject("unsupported") != null
        ) { "OpenCode bootstrap catalog format invalid" }
        val staging = File(config.path + MODEL_CACHE_SUFFIX + ".new-" + java.util.UUID.randomUUID())
        try {
            FileOutputStream(staging).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            // Do not replace an independently refreshed catalog if another
            // process initialized the gateway during this write.
            if (cache.isFile) return false
            check(staging.renameTo(cache)) { "OpenCode model catalog cache installation failed" }
            cache.setReadable(false, false)
            cache.setReadable(true, true)
            return true
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun writeConfig(file: File, port: Int, key: String, zen: String, go: String, proxy: String) {
        val tmp = File(file.parentFile, "config.new")
        tmp.writeText(configuration(port, key, zen, go, proxy).toString(), Charsets.UTF_8)
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(false, false)
        tmp.setWritable(true, true)
        check(tmp.renameTo(file)) { "无法保存网关配置" }
    }
}
