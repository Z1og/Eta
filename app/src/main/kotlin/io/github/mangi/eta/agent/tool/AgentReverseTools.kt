package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import org.json.JSONArray
import org.json.JSONObject

/**
 * 逆向一等工具处理器：进程枚举、跨进程内存读写/搜索、Frida 一次性执行。
 * 内存操作经 root 的 /proc/<pid>/mem；Frida 经 Alpine 终端环境（会话/热载请用 terminal 分离任务）。
 */
internal class AgentReverseTools(
    private val rootExecutor: BoundedRootCommandExecutor,
    private val terminal: RootShellTerminalController,
) {
    private fun ok(block: JSONObject.() -> Unit): String =
        JSONObject().put("ok", true).apply(block).toString()

    private fun err(code: String, message: String): String =
        JSONObject().put("ok", false).put("error", code).put("message", message).toString()

    private fun fail(r: BoundedRootCommandExecutor.Result): String =
        err(
            if (r.errorCode.isNotBlank()) r.errorCode else "COMMAND_FAILED",
            "exit=${r.exitCode} timedOut=${r.timedOut} ${r.stderr.take(300)}",
        )

    private fun parseHex(raw: String): Long {
        val cleaned = raw.trim().removePrefix("0x").removePrefix("0X")
        require(cleaned.isNotEmpty() && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "非法十六进制地址：$raw"
        }
        return cleaned.toLong(16)
    }

    fun processList(args: JSONObject): String {
        val filter = args.optString("filter").trim()
        val limit = args.optInt("limit", 100).coerceIn(1, 300)
        var cmd = "ps -A -o PID,USER,COMM,ARGS"
        if (filter.isNotEmpty()) cmd += " | grep -F ${shellQuote(filter)}"
        cmd += " | head -n $limit"
        val r = rootExecutor.execute(cmd, 15_000, 96 * 1024)
        if (!r.ok) return fail(r)
        val rows = JSONArray()
        r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("PID") }
            .forEach { line ->
                val parts = line.split(Regex("\\s+"), limit = 4)
                if (parts.isNotEmpty() && parts[0].toLongOrNull() != null) {
                    rows.put(
                        JSONObject()
                            .put("pid", parts[0].toLong())
                            .put("user", parts.getOrElse(1) { "" })
                            .put("comm", parts.getOrElse(2) { "" })
                            .put("args", parts.getOrElse(3) { "" })
                    )
                }
            }
        return ok { put("count", rows.length()); put("processes", rows) }
    }

    fun memRead(args: JSONObject): String {
        val pid = args.optLong("pid", -1)
        if (pid <= 0) return err("MISSING_PARAM", "缺少合法 pid")
        val addr = runCatching { parseHex(args.optString("address")) }
            .getOrElse { return err("INVALID_ADDRESS", it.message ?: "非法地址") }
        val length = args.optInt("length", 256).coerceIn(1, 65_536)
        val base = addr - (addr % 4096)
        val delta = addr - base
        val total = delta + length
        val blocks = (total + 4095) / 4096
        val cmd = "dd if=/proc/$pid/mem bs=4096 skip=${base / 4096} count=$blocks 2>/dev/null | od -An -v -tx1 | tr -d ' \\n'"
        val r = rootExecutor.execute(cmd, 25_000, (total * 2).toInt() + 1_024)
        if (!r.ok) return fail(r)
        val hex = r.stdout.trim()
        val startChar = (delta * 2).toInt()
        if (hex.length < startChar) return err("READ_FAILED", "读取长度不足：hex_chars=${hex.length}")
        val out = hex.substring(startChar, minOf(hex.length, startChar + length * 2))
        return ok {
            put("pid", pid)
            put("address", "0x" + addr.toString(16))
            put("length", out.length / 2)
            put("hex", out)
            put("truncated", r.truncated)
        }
    }

    fun memWrite(args: JSONObject): String {
        val pid = args.optLong("pid", -1)
        if (pid <= 0) return err("MISSING_PARAM", "缺少合法 pid")
        val addr = runCatching { parseHex(args.optString("address")) }
            .getOrElse { return err("INVALID_ADDRESS", it.message ?: "非法地址") }
        val hex = args.optString("bytes_hex").replace(Regex("[^0-9a-fA-F]"), "")
        if (hex.isEmpty() || hex.length % 2 != 0) return err("INVALID_BYTES", "bytes_hex 需为偶数长度十六进制")
        if (hex.length / 2 > 4_096) return err("TOO_LARGE", "单次写入上限 4096 字节，请分段")
        val escapes = hex.chunked(2).joinToString("") { "\\x$it" }
        val cmd = "printf '$escapes' | dd of=/proc/$pid/mem bs=1 seek=$addr conv=notrunc 2>/dev/null; echo rc=\$?"
        val r = rootExecutor.execute(cmd, 30_000, 4 * 1024)
        val rc = Regex("rc=(\\d+)").find(r.stdout)?.groupValues?.get(1)
        return if (rc == "0") {
            ok { put("pid", pid); put("address", "0x" + addr.toString(16)); put("written", hex.length / 2) }
        } else {
            err("WRITE_FAILED", "exit=${r.exitCode} rc=$rc ${r.stderr.take(300)}")
        }
    }

    fun memSearch(args: JSONObject): String {
        val pid = args.optLong("pid", -1)
        if (pid <= 0) return err("MISSING_PARAM", "缺少合法 pid")
        val patternHex = args.optString("pattern_hex").replace(Regex("[^0-9a-fA-F]"), "")
        val text = args.optString("text")
        val pattern = when {
            patternHex.isNotEmpty() -> patternHex
            text.isNotEmpty() -> text.toByteArray().joinToString("") { "%02x".format(it) }
            else -> return err("MISSING_PARAM", "需要 pattern_hex 或 text")
        }
        if (pattern.length % 2 != 0) return err("INVALID_PATTERN", "pattern 需为偶数长度十六进制")
        val startRaw = args.optString("start").trim()
        val endRaw = args.optString("end").trim()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)

        val scriptPath = "$WORK_DIR/rev/mem_search.sh"
        terminal.writeFile(scriptPath, buildMemSearchScript(), append = false)
        val cmd = buildString {
            append("sh ").append(scriptPath).append(' ')
            append(pid).append(' ').append(pattern).append(' ').append(limit)
            if (startRaw.isNotEmpty() && endRaw.isNotEmpty()) {
                val s = runCatching { parseHex(startRaw) }.getOrElse { return err("INVALID_ADDRESS", "start 非法") }
                val e = runCatching { parseHex(endRaw) }.getOrElse { return err("INVALID_ADDRESS", "end 非法") }
                append(' ').append(s).append(' ').append(e)
            }
        }
        val r = rootExecutor.execute(cmd, 120_000, 16 * 1024)
        if (!r.ok) return fail(r)
        val matches = JSONArray()
        r.stdout.lineSequence().filter { it.matches(Regex("0x[0-9a-fA-F]+")) }.forEach { matches.put(it) }
        return ok {
            put("pid", pid)
            put("count", matches.length())
            put("matches", matches)
            put("note", "大范围未加 start/end 时按 rw 区段预算扫描，可能被预算截断")
        }
    }

    fun mcpCall(args: JSONObject): String {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return err("MISSING_PARAM", "缺少 command")
        val method = args.optString("method").ifBlank { "tools/list" }
        val timeoutSec = args.optInt("timeout_seconds", 60)
        val result = AgentMcpClient.call(
            launchCommand = command,
            method = method,
            paramsJson = args.optString("params_json"),
            timeoutSeconds = timeoutSec,
        )
        if (!result.ok) return err("MCP_FAILED", result.payload)
        val parsed: Any = runCatching<Any> { JSONObject(result.payload) }
            .recover { JSONArray(result.payload) }
            .getOrElse { result.payload }
        return ok { put("method", method); put("result", parsed) }
    }

    fun fridaPs(args: JSONObject): String {
        val timeoutSec = args.optInt("timeout_seconds", 15).coerceIn(3, 60)
        return terminal.terminalAction(
            action = "open_and_exec",
            command = "frida-ps",
            cwd = null,
            timeoutMs = timeoutSec * 1_000,
            identity = "root",
            mergeStderr = true,
            sessionId = null,
            jobId = null,
            async = false,
            offsetChars = 0,
            maxChars = 16_000,
            closeIfDone = true,
            environment = TerminalEnvironment.ALPINE.wireName,
            taskId = null,
        )
    }

    fun fridaScript(args: JSONObject): String {
        val target = args.optString("target").trim()
        val script = args.optString("script")
        if (target.isEmpty() || script.isBlank()) return err("MISSING_PARAM", "需要 target 与 script")
        val timeoutSec = args.optInt("timeout_seconds", 30).coerceIn(5, 300)
        val stamp = System.currentTimeMillis()
        val scriptPath = "$WORK_DIR/rev/frida_$stamp.js"
        val writeResult = JSONObject(terminal.writeFile(scriptPath, script, append = false))
        if (!writeResult.optBoolean("ok", false)) return writeResult.toString()
        val alpinePath = scriptPath.replace(WORK_DIR, ALPINE_WORK_DIR)
        val fridaArgs = if (target.all { it.isDigit() }) {
            "-p $target"
        } else {
            "-n ${shellQuote(target)}"
        }
        return terminal.terminalAction(
            action = "open_and_exec",
            command = "frida $fridaArgs -l ${shellQuote(alpinePath)} -q",
            cwd = null,
            timeoutMs = timeoutSec * 1_000,
            identity = "root",
            mergeStderr = true,
            sessionId = null,
            jobId = null,
            async = false,
            offsetChars = 0,
            maxChars = 32_000,
            closeIfDone = true,
            environment = TerminalEnvironment.ALPINE.wireName,
            taskId = null,
        )
    }

    private fun buildMemSearchScript(): String = """
#!/system/bin/sh
# mem_search.sh <pid> <pattern_hex> <limit> [start end]
PID=@@1
PATHEX=@@2
LIMIT=@@3
START=@{4:-}
END=@{5:-}
BUDGET_MB=32
PAT=@@(echo "@@PATHEX" | sed 's/../\\x&/g')
BYTES=@@(printf "@@PAT")

scan_range() {
  S=@@1; E=@@2
  [ "@@E" -le "@@S" ] && return 0
  dd if=/proc/@@PID/mem bs=4096 skip=@@((S/4096)) count=@@(( (E-S+4095)/4096 )) 2>/dev/null \
    | LC_ALL=C grep -abo "@@BYTES" 2>/dev/null | head -n "@@LIMIT" \
    | awk -v s="@@S" -F: '{printf "0x%x\n", s+@@1}'
}

if [ -n "@@START" ] && [ -n "@@END" ]; then
  scan_range "@@START" "@@END"
else
  BUDGET=@@((BUDGET_MB*1024*1024))
  SCANNED=0
  awk '@@2 ~ /rw/ {print @@1}' /proc/@@PID/maps > /tmp/.rev_maps_@@PID
  while read -r R; do
    A=0x@@{R%-*}; B=0x@@{R#*-}
    A=@@(printf '%d' "@@A"); B=@@(printf '%d' "@@B")
    SIZE=@@((B-A))
    [ "@@((SCANNED+SIZE))" -gt "@@BUDGET" ] && SIZE=@@((BUDGET-SCANNED)) && B=@@((A+SIZE))
    [ "@@SIZE" -le 0 ] && break
    scan_range "@@A" "@@B"
    SCANNED=@@((SCANNED+SIZE))
  done < /tmp/.rev_maps_@@PID
  rm -f /tmp/.rev_maps_@@PID
fi
""".trimIndent().replace("@@", "$").replace("@{", "${'$'}{") + "\n"

    companion object {
        private const val WORK_DIR = "/data/local/tmp/eta"
        private const val ALPINE_WORK_DIR = "/workspace"

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}
