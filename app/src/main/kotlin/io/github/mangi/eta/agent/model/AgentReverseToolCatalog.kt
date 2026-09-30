package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 逆向一等工具声明：进程/内存读写与 Frida 执行。执行逻辑在 AgentLocalTools。 */
internal object AgentReverseToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "process_list",
                    description = "List running processes (pid, user, command) on the device via root. Use to find the target process before mem_read/mem_search/frida_script.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "filter",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional substring filter on command line, e.g. a package name.")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Max rows, default 100.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "mem_read",
                    description = "Read bytes from another process's memory via /proc/<pid>/mem (root). Returns hex string.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("pid", JSONObject().put("type", "integer").put("description", "Target process id."))
                                .put(
                                    "address",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Start address, hex like 0x7abc1234.")
                                )
                                .put(
                                    "length",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Bytes to read, 1-65536, default 256.")
                                )
                        )
                        .put("required", JSONArray().put("pid").put("address"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "mem_search",
                    description = "Search bytes (hex) or a text string in a process's readable-writable memory regions (root). Returns matching addresses (hex). Prefer passing start/end from /proc maps or mem_read results; large scans are budget-limited.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("pid", JSONObject().put("type", "integer").put("description", "Target process id."))
                                .put(
                                    "pattern_hex",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Byte pattern as hex, e.g. 'DEADBEEF' or '48 8b 05'. Takes precedence over text.")
                                )
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "ASCII text to search when pattern_hex is empty.")
                                )
                                .put(
                                    "start",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional hex start address to bound the scan.")
                                )
                                .put(
                                    "end",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional hex end address to bound the scan.")
                                )
                                .put(
                                    "budget_mb",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Max MB scanned when no start/end given, default 32.")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Max matches, default 50.")
                                )
                        )
                        .put("required", JSONArray().put("pid"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "mem_write",
                    description = "Write bytes (hex) into another process's memory via /proc/<pid>/mem (root). Use after locating the exact address with mem_search.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("pid", JSONObject().put("type", "integer").put("description", "Target process id."))
                                .put(
                                    "address",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Start address, hex like 0x7abc1234.")
                                )
                                .put(
                                    "bytes_hex",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Bytes to write as hex, e.g. '9090' or '48 31 c0'.")
                                )
                        )
                        .put("required", JSONArray().put("pid").put("address").put("bytes_hex"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "frida_ps",
                    description = "Run frida-ps (process list from Frida) in the Linux environment. Use to verify frida-server is alive before frida_script.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Timeout, default 15.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "frida_script",
                    description = "Run a one-shot Frida JavaScript against a target (attach to package or pid) and return its output. For persistent sessions/hot reload, run frida as a detached task via the terminal tool.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "target",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Package name (attach by name) or numeric pid.")
                                )
                                .put(
                                    "script",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Frida JavaScript source (Java.perform style).")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Timeout, default 30. The frida process is killed afterwards.")
                                )
                        )
                        .put("required", JSONArray().put("target").put("script"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "mcp_call",
                    description = "Call an MCP server over stdio (JSON-RPC): launches the given shell command as the server, performs initialize + one method call (e.g. tools/list, tools/call), returns the result. Use for FOFA/nuclei style MCP servers inside the Linux environment.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Shell command that starts the MCP server, e.g. a chrooted 'uv run --directory <dir> python fofa.py'. Runs under root shell.")
                                )
                                .put(
                                    "method",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "JSON-RPC method, default 'tools/list'. Use 'tools/call' with params_json.")
                                )
                                .put(
                                    "params_json",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "JSON object string for the method params, e.g. {\"name\":\"fofa_search\",\"arguments\":{...}}.")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Timeout, default 60. Cold start of uv/python servers is normal.")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
            )
    }
}
