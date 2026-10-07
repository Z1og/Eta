package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileToolIntegrationTest {
    @Test fun newFileToolsDefaultToAppIdentityEvenWhenRootIsAvailable() {
        val directory = File(TerminalRuntime.userWorkspacePath, "file-contract-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "中文 '$' config.txt").apply { writeText("甲乙丙丁\n".repeat(6000)) }
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { true })
        try {
            val first = JSONObject(controller.fileTool("read_file", JSONObject().put("path", file.path).put("max_bytes", 65536)))
            assertTrue(first.toString(), first.getBoolean("ok"))
            assertEquals("user", first.getString("identity"))
            assertTrue(first.getBoolean("truncated"))
            assertFalse(first.getString("content").contains('\uFFFD'))
            val second = JSONObject(controller.fileTool("read_file", JSONObject().put("path", file.path)
                .put("offset_bytes", first.getLong("next_offset_bytes")).put("expected_revision", first.getString("revision"))))
            assertTrue(second.toString(), second.getBoolean("ok"))
            file.appendText("外部更新")
            val stale = JSONObject(controller.fileTool("edit_file", JSONObject().put("path", file.path)
                .put("old_text", "甲").put("new_text", "己").put("replace_all", true).put("expected_revision", first.getString("revision"))))
            assertEquals("FILE_CHANGED", stale.getString("code"))
            assertTrue(file.readText().startsWith("甲"))
        } finally { controller.close(); directory.deleteRecursively() }
    }

    @Test fun unsupportedReferencesAndRevokedRootDoNotRunFileCommands() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val uri = JSONObject(controller.fileTool("read_file", JSONObject().put("path", "content://example/document/1")))
            assertEquals("UNSUPPORTED_FILE_REFERENCE", uri.getString("code"))
            val privileged = JSONObject(controller.fileTool("write_file", JSONObject().put("path", "/root/private")
                .put("content", "x").put("identity", "root")))
            assertEquals("ROOT_REQUIRED", privileged.getString("code"))
            controller.close()
            val cancelled = JSONObject(controller.fileTool("list_directory", JSONObject()))
            assertEquals("CANCELLED", cancelled.getString("code"))
        } finally { controller.close() }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
