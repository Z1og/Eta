package io.github.mangi.eta.agent.tool

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 最小 MCP stdio client（NDJSON / JSON-RPC 2.0）。
 *
 * 用法：给一条启动 MCP server 的 shell 命令（如 chroot 内 `uv run --directory ... python fofa.py`），
 * 本 client 拉起进程 → initialize → tools/call（或 tools/list）→ 取回结果 → 杀进程。
 * 每次调用短生命周期进程换取实现简单；uv 冷启动几秒属正常。
 */
internal object AgentMcpClient {

    private const val READ_TIMEOUT_MS = 90_000L
    private const val MAX_STDERR_CHARS = 2_000

    data class CallResult(
        val ok: Boolean,
        val payload: String,
    )

    /** 在 root shell 里拉起 [launchCommand]，执行一次 MCP 调用。 */
    fun call(
        launchCommand: String,
        method: String,
        paramsJson: String,
        timeoutSeconds: Int,
    ): CallResult {
        if (launchCommand.isBlank()) return CallResult(false, "EMPTY_COMMAND")
        val timeoutMs = timeoutSeconds.coerceIn(5, 300) * 1_000L
        val process = runCatching {
            ProcessBuilder("su", "-c", "exec $launchCommand")
                .redirectErrorStream(false)
                .start()
        }.getOrElse { return CallResult(false, "SPAWN_FAILED:${it.message}") }

        val stderrCollector = StringBuilder()
        val stderrThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.take(200).forEach { line ->
                        if (stderrCollector.length < MAX_STDERR_CHARS) stderrCollector.appendLine(line)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        return try {
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

            fun send(message: JSONObject) {
                writer.write(message.toString())
                writer.newLine()
                writer.flush()
            }

            fun await(id: Int, deadline: Long): JSONObject? {
                while (System.currentTimeMillis() < deadline) {
                    if (!reader.ready()) {
                        Thread.sleep(40)
                        if (!process.isAlive) return null
                        continue
                    }
                    val line = reader.readLine() ?: return null
                    if (line.isBlank()) continue
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (json.optInt("id", -1) == id) return json
                }
                return null
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "initialize")
                    .put(
                        "params",
                        JSONObject()
                            .put("protocolVersion", "2024-11-05")
                            .put("capabilities", JSONObject())
                            .put(
                                "clientInfo",
                                JSONObject().put("name", "eta").put("version", "1.0"),
                            ),
                    ),
            )
            val init = await(1, deadline)
                ?: return CallResult(false, "INIT_TIMEOUT: ${stderrTail(stderrCollector)}")
            if (init.has("error")) {
                return CallResult(false, "INIT_ERROR: ${init.optJSONObject("error")}")
            }
            send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "notifications/initialized"),
            )

            val callParams = if (paramsJson.isBlank()) {
                JSONObject()
            } else {
                runCatching { JSONObject(paramsJson) }
                    .getOrElse { return CallResult(false, "PARAMS_NOT_JSON") }
            }
            send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 2)
                    .put("method", method)
                    .put("params", callParams),
            )
            val response = await(2, deadline)
                ?: return CallResult(false, "CALL_TIMEOUT: ${stderrTail(stderrCollector)}")
            if (response.has("error")) {
                return CallResult(false, "CALL_ERROR: ${response.optJSONObject("error")}")
            }
            CallResult(true, response.opt("result")?.toString() ?: "null")
        } finally {
            runCatching { process.destroy() }
            runCatching { process.waitFor(1_000, TimeUnit.MILLISECONDS) }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
    }

    private fun stderrTail(collector: StringBuilder): String =
        collector.toString().takeLast(MAX_STDERR_CHARS).trim()
}
