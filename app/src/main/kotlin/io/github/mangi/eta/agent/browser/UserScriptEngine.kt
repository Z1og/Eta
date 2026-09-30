package io.github.mangi.eta.agent.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 一个已安装的用户脚本（油猴脚本）。 */
internal data class UserScript(
    val id: String,
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val source: String,
    val matches: List<String>,
    val includes: List<String>,
    val runAt: String,
    val grants: List<String>,
    val enabled: Boolean,
    val installedAt: Long,
)

/** 用户脚本元数据解析结果——纯逻辑，便于单元测试。 */
internal data class ParsedUserScript(
    val name: String?,
    val namespace: String,
    val version: String,
    val description: String,
    val matches: List<String>,
    val includes: List<String>,
    val runAt: String,
    val grants: List<String>,
    val hasMetaBlock: Boolean,
)

/**
 * 用户脚本的解析与 URL 匹配规则——不依赖 Android API，可单元测试。
 *
 * 支持 Tampermonkey 的 `@match` 匹配模式（scheme 通配、`*.host`、路径 `*`）
 * 与 `@include` 的 glob / 正则形式；`@run-at` 支持 document-start / end / idle。
 */
internal object UserScriptRules {
    const val RUN_AT_START = "document-start"
    const val RUN_AT_END = "document-end"
    const val RUN_AT_IDLE = "document-idle"

    private val URL_PARTS = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)([^#]*)")

    /** 从脚本源码中解析 `==UserScript==` 元数据块；缺失时返回仅含默认值的解析结果。 */
    fun parse(source: String): ParsedUserScript {
        val metaLines = metadataLines(source)
        val values = LinkedHashMap<String, MutableList<String>>()
        for (line in metaLines) {
            val matcher = Regex("^@([a-zA-Z-]+)\\s+(.*)$").find(line.trim()) ?: continue
            val key = matcher.groupValues[1].lowercase()
            val value = matcher.groupValues[2].trim()
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        fun first(key: String): String? = values[key]?.firstOrNull()?.takeIf(String::isNotBlank)
        return ParsedUserScript(
            name = first("name")?.take(160),
            namespace = (first("namespace") ?: "").take(240),
            version = (first("version") ?: "").take(64),
            description = (first("description") ?: "").take(300),
            matches = values["match"].orEmpty().map(String::trim).filter(String::isNotBlank),
            includes = values["include"].orEmpty().map(String::trim).filter(String::isNotBlank),
            runAt = normalizeRunAt(first("run-at")),
            grants = values["grant"].orEmpty().map(String::trim).filter(String::isNotBlank),
            hasMetaBlock = metaLines.isNotEmpty(),
        )
    }

    private fun metadataLines(source: String): List<String> {
        val start = source.indexOf("==UserScript==")
        if (start < 0) return emptyList()
        val end = source.indexOf("==/UserScript==", start)
        val block = if (end > start) source.substring(start, end) else source.substring(start)
        return block.lineSequence()
            .map { it.trim().removePrefix("//").trim() }
            .filter { it.startsWith("@") }
            .toList()
    }

    /** 归一化 `@run-at`；缺失或未知时按 Tampermonkey 语义落到 document-idle。 */
    fun normalizeRunAt(raw: String?): String = when (raw?.trim()?.lowercase()) {
        RUN_AT_START, "start", "document_start" -> RUN_AT_START
        RUN_AT_END, "end", "document_end" -> RUN_AT_END
        else -> RUN_AT_IDLE
    }

    /** 脚本是否应在该 URL 上注入：任一 @match 或 @include 命中即算。 */
    fun matchScript(matches: List<String>, includes: List<String>, url: String): Boolean {
        if (matches.none { matchPattern(it, url) }) {
            if (includes.none { matchInclude(it, url) }) return false
        }
        return true
    }

    /** Chrome/Tampermonkey `@match` 模式：`scheme://host/path`，`*` 仅在路径中为通配。 */
    fun matchPattern(pattern: String, url: String): Boolean {
        val schemeSplit = pattern.indexOf("://")
        if (schemeSplit <= 0) return false
        val scheme = pattern.substring(0, schemeSplit).lowercase()
        val rest = pattern.substring(schemeSplit + 3)
        val pathSlash = rest.indexOf('/')
        val hostPattern = (if (pathSlash >= 0) rest.substring(0, pathSlash) else rest).lowercase()
        val pathPattern = if (pathSlash >= 0) rest.substring(pathSlash) else "/"

        val parts = URL_PARTS.find(url) ?: return false
        val urlScheme = parts.groupValues[1].lowercase()
        val authority = parts.groupValues[2].lowercase()
        val urlPath = parts.groupValues[3].ifBlank { "/" }

        val schemeOk = when (scheme) {
            "*", "http*" -> urlScheme == "http" || urlScheme == "https"
            else -> urlScheme == scheme
        }
        if (!schemeOk) return false

        val host = authority.substringBefore(':')
        // host 匹配：含 * 按 glob（对齐 TM 宽松语义，如 *.haijiao*）；
        // 另补 *.example.org 的 apex 候选（glob ".*\.example\.org" 不含 apex，需单独匹配 example.org）。
        val hostOk = when {
            !hostPattern.contains('*') -> host == hostPattern
            else -> globToRegex(hostPattern).matches(host) ||
                (hostPattern.startsWith("*.") && globToRegex(hostPattern.substring(2)).matches(host))
        }
        if (!hostOk) return false
        return globToRegex(pathPattern).matches(urlPath)
    }

    /** `@include` 匹配：`/regex/` 形式按正则，其余按 glob（`*` 通配）。 */
    fun matchInclude(include: String, url: String): Boolean {
        val trimmed = include.trim()
        if (trimmed.length >= 2 && trimmed.startsWith("/") && trimmed.endsWith("/")) {
            return runCatching { Regex(trimmed.substring(1, trimmed.length - 1)).containsMatchIn(url) }
                .getOrDefault(false)
        }
        return globToRegex(trimmed).matches(url)
    }

    /** 把仅含 `*` 通配的 glob 编译为整串匹配的正则；失败时返回永不匹配。 */
    private fun globToRegex(glob: String): Regex = runCatching {
        Regex(
            glob.split("*", limit = -1).joinToString(separator = ".*") { phase ->
                Regex.escape(phase)
            },
            RegexOption.DOT_MATCHES_ALL,
        )
    }.getOrDefault(Regex("(?!x)x^"))
}

/** 用户脚本与 GM 数据的本机持久化（filesDir 下两个 JSON 文件，原子写）。 */
internal class UserScriptStore(context: Context) {
    private val scriptsFile = File(context.filesDir, "userscripts.json")
    private val dataFile = File(context.filesDir, "userscript-data.json")
    private val lock = Any()

    fun loadScripts(): MutableList<UserScript> = synchronized(lock) {
        runCatching {
            val root = JSONObject(scriptsFile.takeIf(File::exists)?.readText().orEmpty())
            val items = root.optJSONArray("scripts") ?: return@synchronized mutableListOf()
            mutableListOf<UserScript>().apply {
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    add(
                        UserScript(
                            id = o.optString("id"),
                            name = o.optString("name", "未命名脚本"),
                            namespace = o.optString("namespace"),
                            version = o.optString("version"),
                            description = o.optString("description"),
                            source = o.optString("source"),
                            matches = o.optStringArray("matches"),
                            includes = o.optStringArray("includes"),
                            runAt = o.optString("run_at", UserScriptRules.RUN_AT_IDLE),
                            grants = o.optStringArray("grants"),
                            enabled = o.optBoolean("enabled", true),
                            installedAt = o.optLong("installed_at"),
                        )
                    )
                }
            }.filter { it.id.isNotBlank() && it.source.isNotBlank() }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun saveScripts(scripts: List<UserScript>) = synchronized(lock) {
        val root = JSONObject().put(
            "scripts",
            JSONArray().apply {
                scripts.forEach { s ->
                    put(
                        JSONObject()
                            .put("id", s.id)
                            .put("name", s.name)
                            .put("namespace", s.namespace)
                            .put("version", s.version)
                            .put("description", s.description)
                            .put("source", s.source)
                            .put("matches", s.matches.toJsonArray())
                            .put("includes", s.includes.toJsonArray())
                            .put("run_at", s.runAt)
                            .put("grants", s.grants.toJsonArray())
                            .put("enabled", s.enabled)
                            .put("installed_at", s.installedAt)
                    )
                }
            },
        )
        atomicWrite(scriptsFile, root.toString())
    }

    fun loadData(): MutableMap<String, MutableMap<String, String>> = synchronized(lock) {
        runCatching {
            val root = JSONObject(dataFile.takeIf(File::exists)?.readText().orEmpty())
            mutableMapOf<String, MutableMap<String, String>>().apply {
                root.keys().forEach { sid ->
                    val bucket = root.optJSONObject(sid) ?: return@forEach
                    put(
                        sid,
                        mutableMapOf<String, String>().apply {
                            bucket.keys().forEach { key -> put(key, bucket.optString(key)) }
                        },
                    )
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    fun saveData(data: Map<String, Map<String, String>>) = synchronized(lock) {
        val root = JSONObject()
        data.forEach { (sid, bucket) ->
            val o = JSONObject()
            bucket.forEach { (k, v) -> o.put(k, v) }
            root.put(sid, o)
        }
        atomicWrite(dataFile, root.toString())
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun List<String>.toJsonArray(): JSONArray = JSONArray().apply { forEach { put(it) } }

    private fun JSONObject.optStringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }
}

/**
 * 用户脚本运行时：负责把匹配的脚本注入 Eta Agent 浏览器 WebView，
 * 并通过 [EtaScriptBridge] 提供 GM_* API 的本机实现。
 *
 * 注入时机（主线程调用 [injectOnMain]）：
 * - document-start → WebViewClient.onPageStarted
 * - document-end → WebViewClient.onPageFinished
 * - document-idle → onPageFinished 后约 150ms
 *
 * WebView 没有 Thunderbird 式的独立脚本沙箱，脚本与页面共享 window，
 * `unsafeWindow` 即 `window`；@match/@include 未命中的脚本不会注入。
 */
internal object UserScriptEngine {
    private const val TAG = "EtaUserScript"
    private const val MAX_SCRIPT_BYTES = 2_000_000
    private const val HTTP_RESPONSE_CAP = 1_000_000
    private const val MAX_RECORDS = 24

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var store: UserScriptStore? = null
    private var scripts: MutableList<UserScript>? = null
    private var gmData: MutableMap<String, MutableMap<String, String>>? = null

    /** 当前页面的桥接令牌：每次主导航重新生成，页面 JS 无法从 window 上取得。 */
    @Volatile
    var pageToken: String = ""
        private set

    private var tokenPageKey: String = ""

    /** 当前宿主 WebView，由 AgentBrowserSession 在创建/销毁时挂接。 */
    @Volatile
    private var hostView: WebView? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (store == null) store = UserScriptStore(context.applicationContext)
        }
    }

    fun attachHost(view: WebView?) {
        hostView = view
        if (view == null) pageToken = ""
    }

    // ---------- 脚本管理（浏览器执行线程调用） ----------

    fun listScripts(): List<UserScript> = synchronized(lock) { loaded().toList() }

    fun addScript(source: String): Pair<UserScript, List<String>> {
        require(source.isNotBlank()) { "脚本内容为空" }
        require(source.length <= MAX_SCRIPT_BYTES) { "脚本超过 ${MAX_SCRIPT_BYTES / 1_000_000}MB 上限" }
        val meta = UserScriptRules.parse(source)
        val warnings = mutableListOf<String>()
        if (!meta.hasMetaBlock) warnings.add("脚本缺少 ==UserScript== 元数据块，将按默认设置安装")
        if (meta.matches.isEmpty() && meta.includes.isEmpty()) {
            warnings.add("脚本未声明 @match 或 @include，不会在任何页面自动注入")
        }
        val script = UserScript(
            id = "us_" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = meta.name ?: "未命名脚本",
            namespace = meta.namespace,
            version = meta.version,
            description = meta.description,
            source = source,
            matches = meta.matches,
            includes = meta.includes,
            runAt = meta.runAt,
            grants = meta.grants,
            enabled = true,
            installedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            loaded().removeAll { it.name == script.name && it.namespace == script.namespace }
            loaded().add(script)
            persistLocked()
        }
        return script to warnings
    }

    fun addScriptFromUrl(url: String): Pair<UserScript, List<String>> {
        require(url.startsWith("http")) { "仅支持 http/https 的脚本地址" }
        val request = Request.Builder().url(url).build()
        AgentHttpClient.client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载脚本失败：HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            require(body.isNotBlank()) { "下载的脚本内容为空" }
            return addScript(body)
        }
    }

    fun setEnabled(scriptId: String, enabled: Boolean): UserScript? = synchronized(lock) {
        val index = loaded().indexOfFirst { it.id == scriptId }
        if (index < 0) return null
        val updated = loaded()[index].copy(enabled = enabled)
        loaded()[index] = updated
        persistLocked()
        updated
    }

    fun toggleEnabled(scriptId: String): UserScript? = synchronized(lock) {
        val current = loaded().firstOrNull { it.id == scriptId } ?: return null
        setEnabled(scriptId, !current.enabled)
    }

    fun removeScript(scriptId: String): Boolean = synchronized(lock) {
        val removed = loaded().removeAll { it.id == scriptId }
        if (removed) {
            gmBucket()?.remove(scriptId)
            persistLocked()
            persistDataLocked()
        }
        removed
    }

    // ---------- GM_* API 本机实现（JavascriptInterface 线程调用） ----------

    fun handleGm(token: String, op: String, payload: JSONObject): JSONObject {
        val envelope = JSONObject()
        if (token.isBlank() || token != pageToken) {
            return envelope.put("ok", false).put("error", "FORBIDDEN")
        }
        return when (op) {
            "get" -> {
                val bucket = gmBucketFor(payload.optString("sid"))
                envelope.put("ok", true)
                    .put("value", bucket?.get(payload.optString("key")) ?: JSONObject.NULL)
            }
            "set" -> {
                val bucket = gmBucketFor(payload.optString("sid"))
                bucket?.put(payload.optString("key"), payload.optString("value"))
                persistDataLocked()
                envelope.put("ok", true)
            }
            "del" -> {
                gmBucketFor(payload.optString("sid"))?.remove(payload.optString("key"))
                persistDataLocked()
                envelope.put("ok", true)
            }
            "keys" -> {
                val keys = gmBucketFor(payload.optString("sid"))?.keys?.toList().orEmpty()
                envelope.put("ok", true).put("value", JSONArray(keys))
            }
            "clip" -> {
                val text = payload.optString("text")
                mainHandler.post {
                    runCatching {
                        val context = hostView?.context?.applicationContext ?: return@post
                        val manager =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        manager?.setPrimaryClip(ClipData.newPlainText("Eta GM_setClipboard", text))
                    }
                }
                envelope.put("ok", true)
            }
            "http" -> {
                launchGmHttpRequest(payload)
                envelope.put("ok", true).put("async", true)
            }
            else -> envelope.put("ok", false).put("error", "UNSUPPORTED_OP")
        }
    }

    private fun gmBucketFor(scriptId: String): MutableMap<String, String>? = synchronized(lock) {
        if (scriptId.isBlank()) return null
        gmData().getOrPut(scriptId) { mutableMapOf() }
    }

    private fun gmBucket(): MutableMap<String, MutableMap<String, String>>? = synchronized(lock) { gmData() }

    private fun launchGmHttpRequest(payload: JSONObject) {
        val url = payload.optString("url")
        if (!url.startsWith("http")) return
        val callId = payload.optString("callId")
        if (callId.isBlank()) return
        val method = payload.optString("method", "GET").uppercase()
        val headers = payload.optJSONObject("headers") ?: JSONObject()
        val body = payload.optString("body").takeIf { it.isNotEmpty() && method != "GET" && method != "HEAD" }
        val timeoutMs = payload.optLong("timeout", 30_000L).coerceIn(1_000L, 60_000L)

        val builder = Request.Builder().url(url)
        headers.keys().forEach { key ->
            runCatching { builder.header(key, headers.optString(key)) }
        }
        when (method) {
            "POST" -> builder.post(
                body.orEmpty().toRequestBody(
                    headers.optString("Content-Type").ifBlank { "text/plain" }.toMediaTypeOrNull()
                )
            )
            "PUT" -> builder.put(
                body.orEmpty().toRequestBody(
                    headers.optString("Content-Type").ifBlank { "text/plain" }.toMediaTypeOrNull()
                )
            )
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete()
            else -> builder.get()
        }

        val client = AgentHttpClient.client.newBuilder()
            .connectTimeout(15_000L, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()

        client.newCall(builder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                resolveOnMain(callId, 0, "{}", "[EtaGM] request failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val text = runCatching {
                    val raw = response.body?.string().orEmpty()
                    if (raw.length > HTTP_RESPONSE_CAP) {
                        raw.take(HTTP_RESPONSE_CAP) + "...[truncated]"
                    } else {
                        raw
                    }
                }.getOrDefault("[EtaGM] response read failed")
                val headersJson = JSONObject().apply {
                    response.headers.forEach { (name, value) -> put(name, value) }
                }
                resolveOnMain(callId, response.code, headersJson.toString(), text)
                response.close()
            }
        })
    }

    private fun resolveOnMain(callId: String, status: Int, headersJson: String, body: String) {
        mainHandler.post {
            val view = hostView ?: return@post
            runCatching {
                view.evaluateJavascript(
                    "window.__etaGmResolve && window.__etaGmResolve(" +
                        "${JSONObject.quote(callId)}, $status, " +
                        "${JSONObject.quote(headersJson)}, ${JSONObject.quote(body)});",
                    null,
                )
            }
        }
    }

    // ---------- 注入（主线程调用） ----------

    fun injectOnMain(view: WebView, url: String, phase: String) {
        // 令牌按页面（URL）刷新：同一页面的 start/end/idle 注入共享同一个令牌。
        if (pageToken.isBlank() || url != tokenPageKey) {
            pageToken = UUID.randomUUID().toString().replace("-", "")
            tokenPageKey = url
        }
        val pending = synchronized(lock) {
            loaded().filter { it.enabled && it.runAt == phase && UserScriptRules.matchScript(it.matches, it.includes, url) }
        }
        Log.i(TAG, "inject phase=$phase url=${url.take(120)} matched=${pending.size}/${enabledCount()}")
        if (pending.isEmpty()) return
        val token = pageToken
        if (token.isBlank()) return
        runCatching {
            view.evaluateJavascript(runtimeJs()) { }
            pending.forEach { script ->
                view.evaluateJavascript(scriptJs(script, token)) { result ->
                    // 回调返回即执行完成；脚本内部异常由 eval 的 try/catch 记录到 window.__etaScriptError。
                    Log.i(TAG, "injected id=${script.id} phase=$phase url=${url.take(80)}")
                    record(script, phase, url, ok = true, error = null)
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "inject evaluate failed phase=$phase: ${e.javaClass.simpleName}: ${e.message}")
            pending.forEach { record(it, phase, url, ok = false, error = e.message) }
        }
    }

    private fun runtimeJs(): String = """
        (function() {
          if (window.__etaGmRuntime) return;
          window.__etaGmRuntime = true;
          window.__etaGmRegistry = {};
          window.__etaGmResolve = function(callId, status, headersJson, body) {
            var entry = window.__etaGmRegistry[callId];
            if (!entry) return;
            delete window.__etaGmRegistry[callId];
            var headers = {};
            try { headers = JSON.parse(headersJson || '{}'); } catch (_) {}
            try {
              if (typeof entry.onload === 'function') {
                entry.onload({ status: status, finalUrl: entry.url, responseHeaders: headers, responseText: body });
              }
            } catch (e) { console.warn('[EtaGM] onload error', e); }
          };
        })();
    """.trimIndent()

    private fun scriptJs(script: UserScript, token: String): String {
        val flag = "__etaRun_" + script.id.replace(Regex("[^a-zA-Z0-9_]"), "")
        val meta = JSONObject()
            .put(
                "script",
                JSONObject()
                    .put("name", script.name)
                    .put("namespace", script.namespace)
                    .put("version", script.version)
                    .put("description", script.description)
                    .put("run-at", script.runAt)
            )
            .put("version", "4.18-eta")
        return "(function() {" +
            "if (window.$flag) return; window.$flag = true;" +
            "var TOKEN = ${JSONObject.quote(token)};" +
            "var SID = ${JSONObject.quote(script.id)};" +
            "function gm(op, payload) {" +
            "  try {" +
            "    var raw = window.EtaGM.gm(TOKEN, op, JSON.stringify(payload || {}));" +
            "    var v = JSON.parse(raw || '{}');" +
            "    if (v && v.ok === false) throw new Error(v.error || 'GM_ERROR');" +
            "    return v.value;" +
            "  } catch (e) { console.warn('[EtaGM]', op, e); return undefined; }" +
            "}" +
            "var GM_info = ${meta};" +
            "var GM = {" +
            "  getValue: function(k, d) { var v = gm('get', {sid:SID, key:String(k)}); return (v === null || v === undefined) ? d : safeParse(v); }," +
            "  setValue: function(k, val) { gm('set', {sid:SID, key:String(k), value: JSON.stringify(val)}); }," +
            "  deleteValue: function(k) { gm('del', {sid:SID, key:String(k)}); }," +
            "  listValues: function() { var r = gm('keys', {sid:SID}); return r || []; }," +
            "  addStyle: function(css) { var s = document.createElement('style'); s.textContent = String(css); (document.head || document.documentElement).appendChild(s); }," +
            "  setClipboard: function(t) { gm('clip', {text: String(t)}); }," +
            "  xmlHttpRequest: function(details) {" +
            "    var callId = 'c' + Math.random().toString(36).slice(2);" +
            "    window.__etaGmRegistry[callId] = details || {};" +
            "    gm('http', {sid:SID, callId:callId, method:(details && details.method) || 'GET', url:(details && details.url) || '', headers:(details && details.headers) || {}, body:(details && details.data == null) ? null : String(details.data), timeout:(details && details.timeout) || 30000});" +
            "  }," +
            "  notification: function() { console.info('[EtaGM] GM_notification 在 Eta WebView 中为空实现'); }," +
            "  registerMenuCommand: function() { console.info('[EtaGM] GM_registerMenuCommand 在 Eta WebView 中为空实现'); }" +
            "};" +
            "function safeParse(v) { try { return JSON.parse(v); } catch (_) { return v; } }" +
            "var GM_getValue = GM.getValue, GM_setValue = GM.setValue, GM_deleteValue = GM.deleteValue," +
            "GM_listValues = GM.listValues, GM_addStyle = GM.addStyle, GM_setClipboard = GM.setClipboard," +
            "GM_xmlhttpRequest = GM.xmlHttpRequest, GM_notification = GM.notification," +
            "GM_registerMenuCommand = GM.registerMenuCommand;" +
            "var unsafeWindow = window;" +
            "try { eval(${JSONObject.quote(script.source)}); }" +
            "catch (__etaErr) { console.error('[EtaUserScript]', SID, __etaErr); window.__etaScriptError = String(__etaErr && __etaErr.message || __etaErr); }" +
            "})();"
    }

    // ---------- 内部 ----------

    private val injectionRecords = mutableListOf<JSONObject>()

    private fun enabledCount(): Int = synchronized(lock) { loaded().count { it.enabled } }

    private fun record(script: UserScript, phase: String, url: String, ok: Boolean, error: String?) {
        synchronized(lock) {
            injectionRecords.add(
                JSONObject()
                    .put("id", script.id)
                    .put("name", script.name)
                    .put("phase", phase)
                    .put("url", url.take(200))
                    .put("ok", ok)
                    .put("error", error ?: JSONObject.NULL)
                    .put("at", System.currentTimeMillis())
            )
            while (injectionRecords.size > MAX_RECORDS) injectionRecords.removeAt(0)
        }
    }

    /** get_page_info 附带的注入状态摘要。 */
    fun pageStatus(url: String): JSONObject = synchronized(lock) {
        val installed = loaded()
        JSONObject()
            .put("installed", installed.size)
            .put("enabled", installed.count { it.enabled })
            .put(
                "matched_for_url",
                JSONArray().apply {
                    installed.filter { it.enabled && UserScriptRules.matchScript(it.matches, it.includes, url) }
                        .forEach { put(it.id) }
                },
            )
            .put(
                "injections",
                JSONArray().apply {
                    injectionRecords.filter { it.optString("url") == url.take(200) }.forEach { put(it) }
                },
            )
    }

    /** debug_scripts 的完整诊断状态。 */
    fun debugState(): JSONObject = synchronized(lock) {
        JSONObject()
            .put(
                "scripts",
                JSONArray().apply {
                    loaded().forEach { s ->
                        put(
                            JSONObject()
                                .put("id", s.id)
                                .put("name", s.name)
                                .put("enabled", s.enabled)
                                .put("run_at", s.runAt)
                                .put("matches", JSONArray(s.matches))
                                .put("includes", JSONArray(s.includes))
                                .put("source_chars", s.source.length)
                        )
                    }
                },
            )
            .put("host_attached", hostView != null)
            .put("page_token_set", pageToken.isNotBlank())
            .put("token_page", tokenPageKey.take(200))
            .put("records", JSONArray(injectionRecords))
    }

    private fun loaded(): MutableList<UserScript> {
        scripts?.let { return it }
        val loadedScripts = store?.loadScripts() ?: mutableListOf()
        scripts = loadedScripts
        return loadedScripts
    }

    private fun gmData(): MutableMap<String, MutableMap<String, String>> {
        gmData?.let { return it }
        val loaded = store?.loadData() ?: mutableMapOf()
        gmData = loaded
        return loaded
    }

    private fun persistLocked() {
        store?.saveScripts(scripts.orEmpty().toList())
    }

    private fun persistDataLocked() {
        gmData?.let { store?.saveData(it) }
    }
}

/** 暴露给页面 JS 的 GM 桥（window.EtaGM），所有方法带页面令牌校验。 */
internal class EtaScriptBridge {
    @JavascriptInterface
    fun gm(token: String, op: String, payload: String): String {
        val parsed = runCatching { JSONObject(payload.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return runCatching {
            UserScriptEngine.handleGm(token.orEmpty(), op.orEmpty(), parsed).toString()
        }.getOrDefault(JSONObject().put("ok", false).put("error", "BRIDGE_ERROR").toString())
    }
}
