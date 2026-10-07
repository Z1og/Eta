package io.github.mangi.eta.agent.terminal

import org.json.JSONArray
import org.json.JSONObject

/** Schema 与执行前校验共用动作合同，避免解析默认值后丢失参数是否显式提供的信息。 */
internal object TerminalToolContract {
    data class Failure(val code: String, val message: String)

    private data class Action(val required: Set<String>, val optional: Set<String>) {
        val fields: Set<String> get() = required + optional + "action"
    }

    private val environmentFields = setOf("cwd", "identity", "environment")
    private val actions = linkedMapOf(
        "open" to Action(emptySet(), environmentFields),
        "exec" to Action(setOf("command"), environmentFields + setOf("session_id", "timeout_ms", "merge_stderr", "async")),
        "read_async_result" to Action(setOf("job_id"), setOf("offset_chars", "max_chars", "close_if_done")),
        "close" to Action(emptySet(), setOf("session_id", "job_id")),
        "daemon_start" to Action(setOf("command"), environmentFields),
        "daemon_list" to Action(emptySet(), emptySet()),
        "daemon_logs" to Action(setOf("task_id"), emptySet()),
        "daemon_stop" to Action(setOf("task_id"), emptySet()),
    )

    fun schema(): JSONObject {
        val properties = properties()
        val branches = JSONArray()
        actions.forEach { (name, action) ->
            val branch = JSONObject()
                .put("properties", JSONObject().put("action", JSONObject().put("enum", JSONArray().put(name))))
                .put("required", JSONArray((action.required + "action").toList()))
            val excluded = properties.keys().asSequence().filter { it !in action.fields }.toList()
            val forbidden = JSONArray(excluded.map { JSONObject().put("required", JSONArray().put(it)) })
            if (name == "exec") {
                (environmentFields + "async").forEach { field ->
                    val conflict = JSONObject().put("required", JSONArray().put("session_id").put(field))
                    if (field == "async") conflict.put("properties", JSONObject().put("async", JSONObject().put("enum", JSONArray().put(true))))
                    forbidden.put(conflict)
                }
            }
            if (forbidden.length() > 0) branch.put("not", JSONObject().put("anyOf", forbidden))
            if (name == "close") branch.put("oneOf", JSONArray()
                .put(JSONObject().put("required", JSONArray().put("session_id")))
                .put(JSONObject().put("required", JSONArray().put("job_id"))))
            branches.put(branch)
        }
        return JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray().put("action")).put("additionalProperties", false)
            .put("oneOf", branches)
    }

    fun validate(args: JSONObject): Failure? {
        val actionName = args.opt("action") as? String
            ?: return invalid("action 必须是非空字符串")
        val canonicalAction = if (actionName == "open_and_exec") "exec" else actionName
        val action = actions[canonicalAction]
            ?: return Failure("UNSUPPORTED_TERMINAL_ACTION", "不支持的 terminal action")
        val properties = properties()
        args.keys().forEach { key ->
            if (key !in action.fields) return invalid("$actionName 不接受参数 $key")
            val value = args.opt(key)
            val spec = properties.getJSONObject(key)
            when (spec.getString("type")) {
                "string" -> {
                    if (value !is String || value.isBlank()) return invalid("$key 必须是非空字符串")
                    val allowed = spec.optJSONArray("enum")
                    val legacyValue = key == "action" && value == "open_and_exec" ||
                        key == "environment" && value in setOf("alpine", "debian")
                    if (!legacyValue && allowed != null && (0 until allowed.length()).none { allowed.getString(it) == value }) {
                        return invalid("$key 不是支持的取值")
                    }
                    if (spec.has("maxLength") && value.length > spec.getInt("maxLength")) return invalid("$key 过长")
                }
                "boolean" -> if (value !is Boolean) return invalid("$key 必须是布尔值")
                "integer" -> {
                    if (value !is Number || !value.toDouble().isFinite() || value.toDouble() != value.toLong().toDouble()) {
                        return invalid("$key 必须是整数")
                    }
                    if (value.toLong() < spec.optLong("minimum", Long.MIN_VALUE) || value.toLong() > spec.optLong("maximum", Long.MAX_VALUE)) {
                        return invalid("$key 超出允许范围")
                    }
                }
            }
        }
        action.required.firstOrNull { !args.has(it) }?.let { return invalid("$actionName 缺少参数 $it") }
        if (canonicalAction == "exec" && args.has("session_id")) {
            if (args.optBoolean("async")) return Failure("ASYNC_SESSION_UNSUPPORTED", "async 不复用持久会话，请省略 session_id")
            environmentFields.firstOrNull { args.has(it) }?.let {
                return invalid("session_id 已确定执行环境，不能同时提供 $it；需要切换目录请在会话中执行 cd")
            }
        }
        if (canonicalAction == "close" && args.has("session_id") == args.has("job_id")) {
            return invalid("close 必须且只能提供 session_id 或 job_id")
        }
        return null
    }

    private fun invalid(message: String) = Failure("INVALID_ARGUMENT", message)

    private fun properties(): JSONObject = JSONObject()
        .put("action", text("终端动作。exec 是统一命令入口。").put("enum", JSONArray(actions.keys.toList())))
        .put("command", text("exec/daemon_start 必填；使用实际环境支持的 Shell 语法。").put("maxLength", 4_000))
        .put("identity", text("宿主执行身份。user 是 App UID；Android 默认当前可用身份，Linux 由所选后端决定。").put("enum", JSONArray().put("user").put("root")))
        .put("environment", text("android 使用 Android 命令；linux 使用所选发行版。默认 android。").put("enum", JSONArray().put("android").put("linux")))
        .put("cwd", text("工作目录。Android user 默认 Eta 私有工作区，root 默认 /data/local/tmp/eta；Linux 默认 /workspace。相对路径相对于默认工作区；~ 在 user 下为私有工作区、root 下为公共存储、Linux 下为 /root。不能与 session_id 同时提供。"))
        .put("session_id", text("open 返回的持久会话 ID；仅供 exec 或 close 使用，复用会话已有目录、环境和身份。"))
        .put("job_id", text("async exec 返回的任务 ID；仅供 read_async_result 或 close 使用。"))
        .put("task_id", text("daemon_start 返回的任务 ID；仅供 daemon_logs 或 daemon_stop 使用。"))
        .put("timeout_ms", integer("exec 超时毫秒，默认 30000。超时会终止命令；会话内超时也会关闭会话。", 1_000, 180_000))
        .put("merge_stderr", bool("exec 是否把 stderr 合并到 stdout，默认 false。"))
        .put("async", bool("exec 是否独立异步执行，默认 false；true 不能与 session_id 组合。"))
        .put("offset_chars", integer("read_async_result 的 stdout 字符偏移，默认 0。", 0, Int.MAX_VALUE))
        .put("max_chars", integer("read_async_result 每页 stdout 字符上限，默认 8000。", 1, 16_000))
        .put("close_if_done", bool("任务结束且本页已到保留输出末尾时释放任务，默认 false。"))

    private fun text(description: String) = JSONObject().put("type", "string").put("minLength", 1).put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun integer(description: String, minimum: Int, maximum: Int) = JSONObject()
        .put("type", "integer").put("minimum", minimum).put("maximum", maximum).put("description", description)
}
