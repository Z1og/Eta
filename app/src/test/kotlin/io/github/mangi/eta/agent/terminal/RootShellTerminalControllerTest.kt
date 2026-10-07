package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentToolCallValidator
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootShellTerminalControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sessionExecKeepsShellEnvironment() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val open = JSONObject(
                controller.terminalAction(
                    action = "open",
                    command = "",
                    cwd = temporaryFolder.root.absolutePath,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )
            val sessionId = open.getString("session_id")

            val export = JSONObject(
                controller.terminalAction(
                    action = "exec",
                    command = "export ETA_TEST_VALUE=streaming",
                    cwd = null,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = sessionId,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )
            val echo = JSONObject(
                controller.terminalAction(
                    action = "exec",
                    command = "printf %s \"\$ETA_TEST_VALUE\"",
                    cwd = null,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = sessionId,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )

            assertTrue(export.toString(), export.getBoolean("ok"))
            assertTrue(echo.toString(), echo.getBoolean("ok"))
            assertEquals("streaming", echo.getString("stdout"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun asyncExecRejectsSessionIdBecauseItDoesNotReuseSessionState() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val open = JSONObject(
                controller.terminalAction(
                    action = "open",
                    command = "",
                    cwd = temporaryFolder.root.absolutePath,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )
            val result = JSONObject(
                controller.terminalAction(
                    action = "exec",
                    command = "sleep 1",
                    cwd = null,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = open.getString("session_id"),
                    jobId = null,
                    async = true,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false
                )
            )

            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("ASYNC_SESSION_UNSUPPORTED", result.getString("code"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun terminalLogsDoNotContainCommandOrWorkingDirectory() {
        val logger = RecordingLogger()
        val controller = RootShellTerminalController(logger)
        val cwd = temporaryFolder.newFolder("sensitive_cwd_marker").absolutePath
        val command = "printf sensitive_command_marker"

        try {
            val result = JSONObject(
                controller.terminalOpenAndExec(
                    command = command,
                    cwd = cwd,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false
                )
            )

            assertTrue(result.toString(), result.getBoolean("ok"))
            val logs = logger.messages.joinToString("\n")
            assertTrue(logs, logs.contains("action=exec"))
            assertTrue(logs, logs.contains("commandChars=${command.length}"))
            assertFalse(logs, logs.contains("sensitive_command_marker"))
            assertFalse(logs, logs.contains("sensitive_cwd_marker"))
            assertFalse(logs, logs.contains(cwd))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun terminalSchemaAndExecutionRejectIgnoredOrConflictingArguments() {
        val tools = JSONArray().also(AgentTerminalToolCatalog::appendTo)
        assertEquals(1, tools.length())
        assertEquals("terminal", tools.getJSONObject(0).getJSONObject("function").getString("name"))
        val validator = AgentToolCallValidator(tools)
        val invalid = listOf(
            """{"action":"exec"}""",
            """{"action":"exec","command":"pwd","session_id":"session","cwd":"/tmp"}""",
            """{"action":"exec","command":"pwd","session_id":"session","identity":"user"}""",
            """{"action":"exec","command":"pwd","session_id":"session","environment":"android"}""",
            """{"action":"exec","command":"pwd","session_id":"session","async":true}""",
            """{"action":"open","command":"pwd"}""",
            """{"action":"close"}""",
            """{"action":"close","session_id":"session","job_id":"job"}""",
            """{"action":"daemon_list","identity":"root"}""",
            """{"action":"daemon_logs"}""",
            """{"action":"daemon_start","command":"sleep 1","timeout_ms":1000}""",
            """{"action":"read_async_result","job_id":"job","offset_chars":-1}""",
            """{"action":"exec","command":"pwd","timeout_ms":"1000"}""",
        )
        val controller = RootShellTerminalController(NoopLogger)
        try {
            invalid.forEach { json ->
                assertNotNull(json, validator.validate(AgentModelClient.ToolCall("call", "terminal", json)))
                val result = JSONObject(controller.terminalAction(JSONObject(json)))
                assertFalse(json, result.getBoolean("ok"))
                assertTrue(result.toString(), result.getString("code") in setOf("INVALID_ARGUMENT", "ASYNC_SESSION_UNSUPPORTED"))
            }
            listOf(
                """{"action":"open"}""",
                """{"action":"exec","command":"pwd"}""",
                """{"action":"exec","command":"pwd","session_id":"session","async":false}""",
                """{"action":"close","job_id":"job"}""",
                """{"action":"daemon_start","command":"sleep 1"}""",
                """{"action":"daemon_list"}""",
                """{"action":"daemon_logs","task_id":"task"}""",
                """{"action":"daemon_stop","task_id":"task"}""",
                """{"action":"read_async_result","job_id":"job"}""",
            ).forEach { json ->
                assertNull(json, validator.validate(AgentModelClient.ToolCall("call", "terminal", json)))
                assertNull(json, TerminalToolContract.validate(JSONObject(json)))
            }
            val legacy = """{"action":"open_and_exec","command":"pwd"}"""
            assertNotNull(validator.validate(AgentModelClient.ToolCall("call", "terminal", legacy)))
            assertNull(TerminalToolContract.validate(JSONObject(legacy)))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun oneShotReturnsObservedRuntimeWithoutMixingMetadataIntoOutput() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val args = JSONObject().put("action", "exec").put("command", "printf visible_output")
                .put("identity", "user").put("cwd", temporaryFolder.root.absolutePath)
            val result = JSONObject(controller.terminalAction(args))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("visible_output", result.getString("stdout"))
            assertEquals("exited", result.getString("status"))
            assertEquals("exec", result.getString("action"))
            val runtime = result.getJSONObject("runtime")
            assertEquals("user", runtime.getString("host_identity"))
            assertTrue(runtime.toString(), runtime.getInt("uid") >= 0)
            assertFalse(runtime.getString("shell_provider").isBlank())
            assertTrue(runtime.getJSONArray("available_commands").toString().contains("sh"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun outputJustOverDisplayLimitIsMarkedTruncated() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val result = JSONObject(controller.terminalAction(JSONObject()
                .put("action", "exec")
                .put("command", "awk 'BEGIN { for (i=0;i<16001;i++) printf \"x\" }'")
                .put("identity", "user")
                .put("cwd", temporaryFolder.root.absolutePath)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertTrue(result.toString(), result.getBoolean("stdout_truncated"))
            assertFalse(result.getString("stdout").contains("__ETA_RUNTIME_"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun runtimeProbeHandlesPartialHeaderAndDistinguishesGuestUid() {
        val marker = TerminalExecutionProbe.marker()
        assertEquals("", TerminalExecutionProbe.extract(marker.take(10), marker, "user", TerminalEnvironment.ANDROID).text)
        val text = "$marker:begin\nuid=0\nprovider=sh\ncommand=sh\n$marker:end\nhello"
        val parsed = TerminalExecutionProbe.extract(text, marker, "user", TerminalEnvironment.ALPINE)
        assertEquals("hello", parsed.text)
        assertEquals("guest", parsed.runtime!!.getString("uid_scope"))
        assertEquals("user", parsed.runtime.getString("host_identity"))
    }

    @Test
    fun completedAsyncJobIsRetainedUntilLastRequestedPage() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val started = JSONObject(controller.terminalAction(JSONObject()
                .put("action", "exec").put("command", "printf abcdef").put("async", true)
                .put("identity", "user").put("cwd", temporaryFolder.root.absolutePath)))
            val jobId = started.getString("job_id")
            val request = JSONObject().put("action", "read_async_result").put("job_id", jobId)
                .put("max_chars", 3).put("close_if_done", true)
            var first = JSONObject(controller.terminalAction(request))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (first.getBoolean("running") && System.nanoTime() < deadline) {
                Thread.sleep(10)
                first = JSONObject(controller.terminalAction(request))
            }
            assertFalse(first.toString(), first.getBoolean("running"))
            assertEquals("abc", first.getString("stdout"))
            assertFalse(first.getBoolean("job_closed"))
            assertEquals(6, first.getInt("total_chars"))
            val last = JSONObject(controller.terminalAction(request.put("offset_chars", first.getInt("next_offset_chars"))))
            assertEquals("def", last.getString("stdout"))
            assertTrue(last.getBoolean("job_closed"))
            assertEquals("JOB_NOT_FOUND", JSONObject(controller.terminalAction(request)).getString("code"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun linuxEnvironmentRequiresInstallationAndRootIdentity() {
        val missingController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPath = File(temporaryFolder.root, "missing-rootfs").absolutePath,
        )
        try {
            val missing = JSONObject(
                missingController.terminalAction(
                    action = "open_and_exec",
                    command = "python3 --version",
                    cwd = temporaryFolder.root.absolutePath,
                    timeoutMs = 5_000,
                    identity = "root",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false,
                    environment = "linux",
                ),
            )
            assertFalse(missing.toString(), missing.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_NOT_READY", missing.getString("code"))
        } finally {
            missingController.closeAll()
        }

        val rootfs = temporaryFolder.newFolder("ready-rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/busybox").writeText("busybox")
        File(rootfs, AlpineEnvironmentPaths.READY_MARKER).writeText("version=3.24.1\n")
        val readyController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPath = rootfs.absolutePath,
        )
        try {
            val user = JSONObject(
                readyController.terminalAction(
                    action = "open_and_exec",
                    command = "id",
                    cwd = temporaryFolder.root.absolutePath,
                    timeoutMs = 5_000,
                    identity = "user",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false,
                    environment = "linux",
                ),
            )
            assertFalse(user.toString(), user.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_REQUIRES_ROOT", user.getString("code"))
        } finally {
            readyController.closeAll()
        }

        var requestedEnvironment: TerminalEnvironment? = null
        val debianController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPathProvider = { environment ->
                requestedEnvironment = environment
                if (environment == TerminalEnvironment.DEBIAN) {
                    File(temporaryFolder.root, "missing-debian-rootfs").absolutePath
                } else {
                    null
                }
            },
            selectedLinuxEnvironmentProvider = { TerminalEnvironment.DEBIAN },
        )
        try {
            val result = JSONObject(
                debianController.terminalAction(
                    action = "open_and_exec",
                    command = "python3 --version",
                    cwd = null,
                    timeoutMs = 5_000,
                    identity = "root",
                    mergeStderr = false,
                    sessionId = null,
                    jobId = null,
                    async = false,
                    offsetChars = 0,
                    maxChars = 8_000,
                    closeIfDone = false,
                    environment = "linux",
                ),
            )
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_NOT_READY", result.getString("code"))
            assertEquals(TerminalEnvironment.DEBIAN, requestedEnvironment)
        } finally {
            debianController.closeAll()
        }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private class RecordingLogger : AgentLogger {
        val messages = mutableListOf<String>()

        override fun debug(message: () -> String) {
            messages += message()
        }

        override fun info(message: String) {
            messages += message
        }

        override fun warn(message: String) {
            messages += message
        }

        override fun error(message: String, throwable: Throwable?) {
            messages += message
        }
    }
}
