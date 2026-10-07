package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject

/** 旧普通身份入口复用文件工具合同，保持调用方的路径错误码。 */
internal object UserFileAccess {
    fun backend(cwd: String = TerminalRuntime.userWorkspacePath, isCancelled: () -> Boolean = { false }): FileToolBackend {
        val workspace = File(TerminalRuntime.userWorkspacePath)
        return LocalFileToolBackend(
            workspace = File(cwd),
            allowedRoots = listOf(workspace, File(workspace.parentFile, "proot"), File("/storage/emulated/0")),
            home = workspace,
            isCancelled = isCancelled,
        )
    }

    private fun operations(): AgentFileOperations = AgentFileOperations(backend())

    fun read(path: String, offsetBytes: Int, maxBytes: Int): String =
        legacyResult(operations().readFile(path, offsetBytes.toLong(), maxBytes, maxLines = 2000))

    fun write(path: String, content: String, append: Boolean): String =
        legacyResult(operations().writeFile(path, content, append))

    fun list(path: String, showHidden: Boolean, limit: Int): String =
        legacyResult(operations().listDirectory(path, showHidden, limit))

    private fun legacyResult(result: String): String {
        val json = JSONObject(result)
        if (json.optString("code") == "PATH_OUTSIDE_SCOPE") json.put("code", "INVALID_PATH")
        return json.toString()
    }
}
