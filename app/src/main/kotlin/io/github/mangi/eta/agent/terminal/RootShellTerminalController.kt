package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal class RootShellTerminalController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val detachedSupervisor: DetachedTaskSupervisor? = null,
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val selectedLinuxEnvironmentProvider: () -> TerminalEnvironment = {
        TerminalEnvironment.ALPINE
    },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
) : AutoCloseable {
    private companion object {
        const val DEFAULT_CWD = "/data/local/tmp/eta"
        const val LINUX_DEFAULT_CWD = "/workspace"
        const val USER_STORAGE = "/storage/emulated/0"
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MAX_TIMEOUT_SECONDS = 180
        const val MAX_COMMAND_CHARS = 4_000
        const val MAX_OUTPUT_CHARS = 16_000
        const val MAX_ASYNC_OUTPUT_CHARS = 64_000
        const val MAX_COMMAND_OUTPUT_BYTES = 256 * 1024
    }

    private val sessions = linkedMapOf<String, TerminalSession>()
    private val asyncJobs = linkedMapOf<String, AsyncCommand>()
    private val cleanupStarted = AtomicBoolean(false)

    fun runCommand(command: String, cwd: String?, timeoutSeconds: Int): String {
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = defaultIdentity(TerminalEnvironment.ANDROID),
            environment = TerminalEnvironment.ANDROID,
            mergeStderr = false,
            toolName = "run_command"
        )
    }

    fun terminalOpenAndExec(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        environment: String = TerminalEnvironment.ANDROID.wireName,
    ): String {
        val timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = identity.ifBlank { defaultIdentity(normalizeEnvironment(environment)) },
            environment = normalizeEnvironment(environment),
            mergeStderr = mergeStderr,
            toolName = "terminal"
        )
    }

    fun terminalAction(args: JSONObject): String {
        TerminalToolContract.validate(args)?.let { return errorJson(it.code, it.message) }
        return terminalAction(
            action = args.getString("action"),
            command = args.optString("command"),
            cwd = args.optString("cwd").ifBlank { null },
            timeoutMs = args.optInt("timeout_ms", 30_000),
            identity = args.optString("identity"),
            mergeStderr = args.optBoolean("merge_stderr", false),
            sessionId = args.optString("session_id").ifBlank { null },
            jobId = args.optString("job_id").ifBlank { null },
            async = args.optBoolean("async", false),
            offsetChars = args.optInt("offset_chars", 0),
            maxChars = args.optInt("max_chars", 8_000),
            closeIfDone = args.optBoolean("close_if_done", false),
            environment = args.optString("environment", TerminalEnvironment.ANDROID.wireName),
            taskId = args.optString("task_id").ifBlank { null },
        )
    }

    fun terminalAction(
        action: String,
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        sessionId: String?,
        jobId: String?,
        async: Boolean,
        offsetChars: Int,
        maxChars: Int,
        closeIfDone: Boolean,
        environment: String = TerminalEnvironment.ANDROID.wireName,
        taskId: String? = null,
    ): String {
        return when (action.lowercase()) {
            "open" -> openSession(identity = identity, cwd = cwd, environment = environment)
            "exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                environment = environment,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "open_and_exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                environment = environment,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "read_async_result" -> readAsyncResult(
                jobId = jobId.orEmpty(),
                offsetChars = offsetChars,
                maxChars = maxChars,
                closeIfDone = closeIfDone
            )
            "close" -> closeTerminal(sessionId = sessionId, jobId = jobId)
            "daemon_start" -> daemonStart(
                command = command,
                cwd = cwd,
                identity = identity,
                environment = environment,
            )
            "daemon_list" -> daemonList()
            "daemon_logs" -> daemonLogs(taskId = taskId.orEmpty())
            "daemon_stop" -> daemonStop(taskId = taskId.orEmpty())
            else -> errorJson(
                "UNSUPPORTED_TERMINAL_ACTION",
                "terminal action 仅支持 open/exec/open_and_exec/read_async_result/close/daemon_start/daemon_list/daemon_logs/daemon_stop"
            )
        }
    }

    private fun openSession(identity: String, cwd: String?, environment: String): String {
        val normalizedEnvironment = normalizeEnvironment(environment)
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { defaultIdentity(normalizedEnvironment) })
        val sessionRootfs = rootfsPathFor(normalizedEnvironment)
        environmentPreflight(normalizedIdentity, normalizedEnvironment, sessionRootfs)?.let { return it }
        val safeCwd = normalizeCwd(cwd, normalizedEnvironment, normalizedIdentity)
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val process = startSessionProcess(normalizedIdentity, normalizedEnvironment, sessionRootfs)
            ?: return errorJson(
                "PROCESS_START_FAILED",
                "无法启动 ${normalizedEnvironment.wireName}/$normalizedIdentity terminal session",
            )
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val session = TerminalSession(
            id = id,
            identity = normalizedIdentity,
            environment = normalizedEnvironment,
            rootfsPath = sessionRootfs,
            cwd = safeCwd,
            createdAt = System.currentTimeMillis(),
            process = process,
            stdout = stdout,
            stderr = stderr
        )
        session.stdoutThread = thread(name = "agent-terminal-session-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input) }
        }
        session.stderrThread = thread(name = "agent-terminal-session-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input) }
        }
        session.waiterThread = thread(name = "agent-terminal-session-waiter-$id", isDaemon = true) {
            runCatching { process.waitFor() }
            processSupervisor.retireExitedProcess(process)
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(sessions) { sessions[id] = session }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }

        val mkdirDefault = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val probeMarker = TerminalExecutionProbe.marker()
        val setup = "${mkdirDefault}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && {\n${TerminalExecutionProbe.script(probeMarker)}\n}"
        val setupResult = runSessionCommand(session, setup, timeoutMs = 5_000)
        if (setupResult.exitCode != 0 || setupResult.timedOut) {
            closeSession(id)
            return errorJson("SESSION_OPEN_FAILED", setupResult.stderr.ifBlank { "exit=${setupResult.exitCode}" })
        }
        session.cwd = setupResult.cwd ?: safeCwd
        session.runtime = TerminalExecutionProbe.extract(setupResult.stdout, probeMarker, normalizedIdentity, normalizedEnvironment).runtime
        session.stdout.clear()
        session.stderr.clear()
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open")
            .put("session_id", id)
            .put("identity", normalizedIdentity)
            .put("environment", normalizedEnvironment.wireName)
            .put("cwd", session.cwd)
            .put("runtime", session.runtime ?: JSONObject.NULL)
            .put("status", "open")
            .toString()
    }

    private fun execInTerminal(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: String,
        mergeStderr: Boolean,
        sessionId: String?,
        async: Boolean
    ): String {
        val session = sessionId?.takeIf { it.isNotBlank() }?.let { id ->
            synchronized(sessions) { sessions[id] }
                ?: return errorJson("SESSION_NOT_FOUND", "未找到 terminal session：$id")
        }
        val effectiveEnvironment = session?.environment ?: normalizeEnvironment(environment)
        val effectiveIdentity = session?.identity ?: normalizeIdentity(identity.ifBlank { defaultIdentity(effectiveEnvironment) })
        if (session != null && !cwd.isNullOrBlank()) {
            return errorJson("INVALID_ARGUMENT", "session_id 不能与 cwd 同时提供；需要切换目录请在会话中执行 cd")
        }
        if (session != null && identity.isNotBlank() && !identity.equals(session.identity, ignoreCase = true)) {
            return errorJson("INVALID_ARGUMENT", "identity 与已有会话不一致")
        }
        environmentPreflight(effectiveIdentity, effectiveEnvironment, session?.rootfsPath ?: rootfsPathFor(effectiveEnvironment))?.let { return it }
        val effectiveCwd = cwd?.takeIf { it.isNotBlank() } ?: session?.cwd
        if (async) {
            if (session != null) {
                return errorJson(
                    "ASYNC_SESSION_UNSUPPORTED",
                    "async terminal job 不复用持久 session；请省略 session_id，并用 cwd/identity 启动后台命令"
                )
            }
            return startAsyncCommand(
                command = command,
                cwd = effectiveCwd,
                timeoutMs = timeoutMs,
                identity = effectiveIdentity,
                environment = effectiveEnvironment,
                mergeStderr = mergeStderr,
                sessionId = session?.id
            )
        }
        if (session != null) {
            return execInSession(
                session = session,
                command = command,
                timeoutMs = timeoutMs,
                mergeStderr = mergeStderr
            )
        }
        val result = runCommand(
            command = command,
            cwd = effectiveCwd,
            timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
                .coerceIn(1, MAX_TIMEOUT_SECONDS),
            identity = effectiveIdentity,
            environment = effectiveEnvironment,
            mergeStderr = mergeStderr,
            toolName = "terminal"
        )
        return result
    }

    private fun startAsyncCommand(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
        sessionId: String?
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, environment, normalizedIdentity)
        val setup = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val probeMarker = TerminalExecutionProbe.marker()
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && {\n${TerminalExecutionProbe.script(probeMarker)}\n$trimmed\n}"
        val process = processSupervisor.startShellProcess(
            identity = normalizedIdentity,
            command = fullCommand,
            mergeStderr = mergeStderr,
            environment = environment,
            linuxRootfsPath = rootfsPathFor(environment),
            linuxSharedMounts = sharedMountsFor(environment),
        ) ?: return errorJson(
            if (processSupervisor.isClosing) "TERMINAL_CLOSED" else "PROCESS_START_FAILED",
            if (processSupervisor.isClosing) "terminal controller 已关闭" else "无法启动 terminal process",
        )
        val id = "job_" + UUID.randomUUID().toString().take(8)
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val job = AsyncCommand(
            id = id,
            process = process,
            stdout = stdout,
            stderr = stderr,
            command = trimmed,
            cwd = safeCwd,
            identity = normalizedIdentity,
            environment = environment,
            mergeStderr = mergeStderr,
            sessionId = sessionId,
            probeMarker = probeMarker,
            startedAt = System.currentTimeMillis(),
            timeoutMs = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        )
        job.stdoutThread = thread(name = "agent-terminal-async-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input, MAX_ASYNC_OUTPUT_CHARS) }
        }
        job.stderrThread = thread(name = "agent-terminal-async-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input, MAX_ASYNC_OUTPUT_CHARS) }
        }
        job.waiterThread = thread(name = "agent-terminal-async-waiter-$id", isDaemon = true) {
            try {
                val finished = process.waitFor(job.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                if (!finished) {
                    job.timedOut = true
                    processSupervisor.terminateProcessTree(process)
                }
                job.stdoutThread.join(500)
                job.stderrThread.join(500)
                job.exitCode = runCatching { process.exitValue() }.getOrDefault(-2)
                job.completedAt = System.currentTimeMillis()
            } finally {
                processSupervisor.retireExitedProcess(process)
            }
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(asyncJobs) { asyncJobs[id] = job }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }
        logger.info(
            "Agent terminal action=exec outcome=started async=true " +
                "identity=$normalizedIdentity environment=${environment.wireName} " +
                "timeoutMs=${job.timeoutMs} commandChars=${trimmed.length}"
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "exec")
            .put("async", true)
            .put("job_id", id)
            .put("session_id", sessionId ?: JSONObject.NULL)
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("running", true)
            .put("status", "running")
            .put("runtime", TerminalExecutionProbe.extract(stdout.text(), probeMarker, normalizedIdentity, environment).runtime ?: JSONObject.NULL)
            .toString()
    }

    private fun readAsyncResult(
        jobId: String,
        offsetChars: Int,
        maxChars: Int,
        closeIfDone: Boolean
    ): String {
        val job = synchronized(asyncJobs) { asyncJobs[jobId] }
            ?: return errorJson("JOB_NOT_FOUND", "未找到 async terminal job：$jobId")
        if (job.identity == "root" && !rootAvailable()) return errorJson("ROOT_REQUIRED", "Root 授权不可用")
        val observation = TerminalExecutionProbe.extract(job.stdout.text(), job.probeMarker, job.identity, job.environment)
        val stdoutRaw = observation.text
        val stderrRaw = job.stderr.text()
        val merged = stdoutRaw
        if (offsetChars < 0 || offsetChars > merged.length) {
            return errorJson("INVALID_ARGUMENT", "offset_chars 超出当前保留输出范围")
        }
        val offset = offsetChars
        val limit = maxChars.coerceIn(1, MAX_OUTPUT_CHARS)
        val slice = merged.substring(offset, (offset + limit).coerceAtMost(merged.length))
        val done = job.exitCode != null
        val pageComplete = offset + slice.length >= merged.length
        val closed = done && closeIfDone && pageComplete
        if (closed) {
            synchronized(asyncJobs) { asyncJobs.remove(jobId) }?.let(::closeJob)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "read_async_result")
            .put("job_id", job.id)
            .put("session_id", job.sessionId ?: JSONObject.NULL)
            .put("environment", job.environment.wireName)
            .put("identity", job.identity)
            .put("cwd", job.cwd)
            .put("runtime", observation.runtime ?: JSONObject.NULL)
            .put("running", !done)
            .put("status", when { !done -> "running"; job.timedOut -> "timed_out"; job.exitCode == 0 -> "exited"; else -> "failed" })
            .put("job_closed", closed)
            .put("exit_code", job.exitCode ?: JSONObject.NULL)
            .put("timed_out", job.timedOut)
            .put("stdout", slice)
            .put("next_offset_chars", offset + slice.length)
            .put("total_chars", merged.length)
            .put("retained_chars", merged.length)
            .put("stdout_total_bytes", (job.stdout.totalBytesRead() - observation.metadataBytes).coerceAtLeast(0))
            .put("stderr_total_bytes", job.stderr.totalBytesRead())
            .put("truncated", offset + slice.length < merged.length)
            .put("output_truncated", job.stdout.isTruncated() || job.stderr.isTruncated() || (!job.mergeStderr && stderrRaw.length > MAX_OUTPUT_CHARS))
            .put("stderr", if (job.mergeStderr) "" else stderrRaw.truncateForJson())
            .put("stdout_truncated", job.stdout.isTruncated())
            .put("stderr_truncated", !job.mergeStderr && (job.stderr.isTruncated() || stderrRaw.length > MAX_OUTPUT_CHARS))
            .toString()
    }

    private fun daemonStart(
        command: String,
        cwd: String?,
        identity: String,
        environment: String,
    ): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedEnvironment = normalizeEnvironment(environment)
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { defaultIdentity(normalizedEnvironment) })
        environmentPreflight(normalizedIdentity, normalizedEnvironment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, normalizedEnvironment, normalizedIdentity)
        return when (val result = supervisor.start(trimmed, safeCwd, normalizedIdentity, normalizedEnvironment)) {
            is DaemonStartResult.Started -> JSONObject()
                .put("ok", true)
                .put("tool", "terminal")
                .put("action", "daemon_start")
                .put("task_id", result.task.id)
                .put("pid", result.task.pid)
                .put("identity", result.task.identity)
                .put("environment", result.task.environment.wireName)
                .put("cwd", result.task.cwd)
                .toString()
            is DaemonStartResult.Failed -> errorJson(result.code, result.message)
        }
    }

    private fun daemonList(): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        val statuses = supervisor.list()
        val tasks = JSONArray()
        statuses.forEach { status ->
            tasks.put(
                JSONObject()
                    .put("task_id", status.task.id)
                    .put("pid", status.task.pid)
                    .put("running", status.running)
                    .put("command", status.task.command)
                    .put("cwd", status.task.cwd)
                    .put("identity", status.task.identity)
                    .put("environment", status.task.environment.wireName)
                    .put("started_at", status.task.startedAt)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_list")
            .put("task_count", statuses.size)
            .put("running_count", statuses.count { it.running })
            .put("tasks", tasks)
            .toString()
    }

    private fun daemonLogs(taskId: String): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        if (taskId.isBlank()) return errorJson("INVALID_ARGUMENT", "task_id 不能为空")
        val result = supervisor.readLogs(taskId)
        if (!result.ok) {
            return errorJson(result.code.ifBlank { "LOGS_UNAVAILABLE" }, result.message)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_logs")
            .put("task_id", taskId)
            .put("log", result.text.truncateForJson())
            .put("log_truncated", result.truncated || result.text.length > MAX_OUTPUT_CHARS)
            .toString()
    }

    private fun daemonStop(taskId: String): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        if (taskId.isBlank()) return errorJson("INVALID_ARGUMENT", "task_id 不能为空")
        val task = supervisor.findTask(taskId) ?: return errorJson("TASK_NOT_FOUND", "未找到守护任务：$taskId")
        if (task.identity == "root" && !rootAvailable()) return errorJson("ROOT_REQUIRED", "Root 授权不可用")
        if (!supervisor.stop(taskId)) {
            return errorJson("DAEMON_STOP_FAILED", "守护任务停止失败，请重试")
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_stop")
            .put("task_id", taskId)
            .toString()
    }

    private fun closeTerminal(sessionId: String?, jobId: String?): String {
        if (sessionId.isNullOrBlank() == jobId.isNullOrBlank()) {
            return errorJson("INVALID_ARGUMENT", "close 必须且只能提供 session_id 或 job_id")
        }
        var closedSession = false
        var closedJob = false
        sessionId?.takeIf { it.isNotBlank() }?.let { id ->
            closedSession = closeSession(id)
        }
        jobId?.takeIf { it.isNotBlank() }?.let { id ->
            closedJob = closeJob(id)
        }
        if (!closedSession && !closedJob) {
            return if (sessionId != null) errorJson("SESSION_NOT_FOUND", "未找到 terminal session")
            else errorJson("JOB_NOT_FOUND", "未找到 async terminal job")
        }
        return JSONObject()
            .put("ok", closedSession || closedJob)
            .put("tool", "terminal")
            .put("action", "close")
            .put("closed_session", closedSession)
            .put("closed_job", closedJob)
            .toString()
    }

    override fun close() {
        closeAll()
    }

    /** 取消热路径只封闭新进程接纳；进程树终止和 reader/waiter 回收在后台完成。 */
    fun interruptAll() {
        beginClosing()
        if (cleanupStarted.compareAndSet(false, true)) {
            thread(name = "agent-terminal-cleanup", isDaemon = true) {
                closeAllInternal()
            }
        }
    }

    fun closeAll() {
        beginClosing()
        cleanupStarted.set(true)
        closeAllInternal()
    }

    private fun beginClosing() {
        processSupervisor.beginClosing()
        synchronized(sessions) {
            sessions.values.forEach { session -> session.closed = true }
        }
    }

    private fun closeAllInternal() {
        val sessionIds = synchronized(sessions) { sessions.keys.toList() }
        sessionIds.forEach(::closeSession)

        val jobs = synchronized(asyncJobs) {
            asyncJobs.values.toList().also { asyncJobs.clear() }
        }
        jobs.forEach(::closeJob)

        val remainingProcesses = processSupervisor.takeRemainingProcesses()
        remainingProcesses.forEach { process ->
            processSupervisor.terminateAndReap(process)
            processSupervisor.unregisterProcess(process)
        }
    }

    private fun closeSession(id: String): Boolean {
        val session = synchronized(sessions) { sessions.remove(id) } ?: return false
        session.closed = true
        runCatching { session.process.outputStream.close() }
        processSupervisor.terminateAndReap(session.process)
        runCatching { session.stdoutThread.join(500) }
        runCatching { session.stderrThread.join(500) }
        runCatching { session.waiterThread.join(500) }
        processSupervisor.unregisterProcess(session.process)
        return true
    }

    private fun closeJob(id: String): Boolean {
        val job = synchronized(asyncJobs) { asyncJobs.remove(id) } ?: return false
        closeJob(job)
        return true
    }

    private fun closeJob(job: AsyncCommand) {
        processSupervisor.terminateAndReap(job.process)
        runCatching { job.stdoutThread.join(500) }
        runCatching { job.stderrThread.join(500) }
        runCatching { job.waiterThread.join(500) }
        processSupervisor.unregisterProcess(job.process)
    }

    private fun execInSession(
        session: TerminalSession,
        command: String,
        timeoutMs: Int,
        mergeStderr: Boolean
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val timeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        val result = runSessionCommand(session, trimmed, timeout)
        val outcome = when {
            result.timedOut -> "timed_out"
            result.exitCode == 0 -> "succeeded"
            else -> "failed"
        }
        val logMessage =
            "Agent terminal action=exec outcome=$outcome session=true " +
                "identity=${session.identity} environment=${session.environment.wireName} " +
                "timeoutMs=$timeout commandChars=${trimmed.length} " +
                "exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        if (result.cwd != null) session.cwd = result.cwd
        if (result.timedOut) {
            closeSession(session.id)
        }
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            result.stdout + "\n[stderr]\n" + result.stderr
        } else {
            result.stdout
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", "terminal")
            .put("action", "exec")
            .put("session_id", session.id)
            .put("identity", session.identity)
            .put("environment", session.environment.wireName)
            .put("cwd", session.cwd)
            .put("runtime", session.runtime ?: JSONObject.NULL)
            .put("status", when { result.timedOut -> "timed_out"; session.closed -> "cancelled"; result.exitCode == 0 -> "exited"; else -> "failed" })
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", rawStdout.length > MAX_OUTPUT_CHARS)
            .put("stderr_truncated", !mergeStderr && result.stderr.length > MAX_OUTPUT_CHARS)
            .put("output_truncated", rawStdout.length > MAX_OUTPUT_CHARS || (!mergeStderr && result.stderr.length > MAX_OUTPUT_CHARS))
            .put("session_closed", result.timedOut || session.closed)
            .toString()
    }

    private fun runSessionCommand(
        session: TerminalSession,
        command: String,
        timeoutMs: Int
    ): SessionCommandResult {
        synchronized(session.lock) {
            if (session.closed || !session.process.isAlive) {
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "terminal session 已关闭",
                    cwd = session.cwd,
                    timedOut = false
                )
            }
            val marker = SessionStatusProtocol.newMarker()
            val stdoutStart = session.stdout.text().length
            val stderrStart = session.stderr.text().length
            val commandBlock = buildString {
                append(command)
                append('\n')
                append(SessionStatusProtocol.statusCommand(marker))
                append('\n')
            }
            runCatching {
                session.process.outputStream.write(commandBlock.toByteArray(Charsets.UTF_8))
                session.process.outputStream.flush()
            }.getOrElse {
                session.closed = true
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                    stderr = it.message ?: it.javaClass.simpleName,
                    cwd = session.cwd,
                    timedOut = false
                )
            }

            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
            while (System.currentTimeMillis() < deadline) {
                val stdoutDelta = session.stdout.text().drop(stdoutStart)
                if (session.closed || !session.process.isAlive) {
                    return SessionCommandResult(
                        exitCode = -1,
                        stdout = stdoutDelta.trimEnd(),
                        stderr = session.stderr.text().drop(stderrStart).ifBlank { "terminal session 已关闭" }.trimEnd(),
                        cwd = session.cwd,
                        timedOut = false
                    )
                }
                val status = stdoutDelta.lineSequence()
                    .firstOrNull { SessionStatusProtocol.isStatusLine(it, marker) }
                    ?.let { SessionStatusProtocol.parseStatusLine(it, marker) }
                if (status != null) {
                    val exitCode = status.exitCode
                    val cwd = status.cwd ?: session.cwd
                    val cleanedStdout = stdoutDelta
                        .lineSequence()
                        .filterNot { SessionStatusProtocol.isStatusLine(it, marker) }
                        .joinToString("\n")
                        .trimEnd()
                    val stderrDelta = session.stderr.text().drop(stderrStart).trimEnd()
                    session.stdout.clear()
                    session.stderr.clear()
                    return SessionCommandResult(
                        exitCode = exitCode,
                        stdout = cleanedStdout,
                        stderr = stderrDelta,
                        cwd = cwd,
                        timedOut = false
                    )
                }
                Thread.sleep(50)
            }

            session.closed = true
            processSupervisor.terminateProcessTree(session.process)
            return SessionCommandResult(
                exitCode = -2,
                stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                stderr = session.stderr.text().drop(stderrStart).ifBlank { "命令执行超时" }.trimEnd(),
                cwd = session.cwd,
                timedOut = true
            )
        }
    }

    private fun runCommand(
        command: String,
        cwd: String?,
        timeoutSeconds: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
        toolName: String
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, environment, normalizedIdentity)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val setup = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val probeMarker = TerminalExecutionProbe.marker()
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && {\n${TerminalExecutionProbe.script(probeMarker)}\n$trimmed\n}"
        val result = runText(
            identity = normalizedIdentity,
            command = fullCommand,
            timeoutSeconds = timeout.toLong(),
            environment = environment,
        )
        val outcome = when (result.exitCode) {
            0 -> "succeeded"
            -2 -> "timed_out"
            else -> "failed"
        }
        val action = if (toolName == "terminal") "exec" else "run_command"
        val logMessage =
            "Agent terminal action=$action outcome=$outcome identity=$normalizedIdentity " +
                "environment=${environment.wireName} " +
                "timeoutSeconds=$timeout commandChars=${trimmed.length} exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        val observation = TerminalExecutionProbe.extract(result.output, probeMarker, normalizedIdentity, environment)
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            observation.text + "\n[stderr]\n" + result.stderr
        } else {
            observation.text
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", toolName)
            .put("action", if (toolName == "terminal") "exec" else JSONObject.NULL)
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("runtime", observation.runtime ?: JSONObject.NULL)
            .put("status", when { processSupervisor.isClosing -> "cancelled"; result.exitCode == -2 -> "timed_out"; result.exitCode == 0 -> "exited"; else -> "failed" })
            .put("exit_code", result.exitCode)
            .put("timed_out", result.exitCode == -2)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", result.outputTruncated || rawStdout.length > MAX_OUTPUT_CHARS || (mergeStderr && result.stderrTruncated))
            .put("stderr_truncated", !mergeStderr && (result.stderrTruncated || result.stderr.length > MAX_OUTPUT_CHARS))
            .put("output_truncated", result.outputTruncated || result.stderrTruncated || rawStdout.length > MAX_OUTPUT_CHARS || result.stderr.length > MAX_OUTPUT_CHARS)
            .toString()
    }

    fun fileTool(name: String, args: JSONObject): String {
        return try {
            val environment = normalizeEnvironment(args.optString("environment", "android"))
            val rootfsPath = rootfsPathFor(environment)
            val identity = args.optString("identity").ifBlank {
                if (environment.isLinux) TerminalRuntime.defaultIdentity(environment, rootfsPath) else "user"
            }.let(::normalizeIdentity)
            environmentPreflight(identity, environment, rootfsPath)?.let { return it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            fun checkActive() {
                if (processSupervisor.isClosing) throw FileToolException("CANCELLED", "文件操作已取消")
                if (identity == "root" && !rootAvailable()) throw FileToolException("ROOT_REQUIRED", "Root 授权已不可用")
                if (System.nanoTime() >= deadline) throw FileToolException("FILE_TIMEOUT", "文件操作达到时间预算，请缩小范围")
            }
            val cwd = normalizeCwd(args.optString("cwd").takeIf(String::isNotEmpty), environment, identity)
            val backend = if (environment == TerminalEnvironment.ANDROID && identity == "user") {
                UserFileAccess.backend(cwd, isCancelled = { checkActive(); false })
            } else {
                val mounts = sharedMountsFor(environment)
                ShellFileToolBackend(
                    environment = if (environment.isLinux) "linux" else "android",
                    identity = identity,
                    cwd = cwd,
                    home = if (environment.isLinux) "/root" else USER_STORAGE,
                    ensureActive = ::checkActive,
                ) { command, input, outputLimit ->
                    checkActive()
                    runOneShotShell(
                        processSupervisor = processSupervisor,
                        identity = identity,
                        command = command,
                        timeoutSeconds = ((deadline - System.nanoTime()) / 1_000_000_000L + 1).coerceIn(1, 20),
                        stdin = input,
                        environment = environment,
                        linuxRootfsPath = rootfsPath,
                        linuxSharedMounts = mounts,
                        maxOutputBytes = outputLimit,
                    )
                }
            }
            FileToolDispatcher.execute(backend, name, args)
        } catch (failure: FileToolException) {
            errorJson(failure.code, failure.message ?: "文件操作失败")
        } catch (_: IllegalArgumentException) {
            errorJson("INVALID_ARGUMENT", "文件环境、路径或参数无效")
        } catch (_: org.json.JSONException) {
            errorJson("INVALID_ARGUMENT", "文件参数缺失或类型错误")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            errorJson("CANCELLED", "文件操作已取消")
        } catch (_: java.io.IOException) {
            errorJson("FILE_IO_ERROR", "文件环境不可访问")
        }
    }

    fun readFile(path: String, offsetBytes: Int, maxBytes: Int): String = fileTool(
        "read_file", JSONObject().put("path", path).put("offset_bytes", offsetBytes)
            .put("max_bytes", maxBytes).put("max_lines", 2000)
            .put("identity", defaultIdentity(TerminalEnvironment.ANDROID)),
    )

    fun writeFile(path: String, content: String, append: Boolean): String = fileTool(
        "write_file", JSONObject().put("path", path).put("content", content).put("append", append)
            .put("identity", defaultIdentity(TerminalEnvironment.ANDROID)),
    )

    fun listDirectory(path: String, showHidden: Boolean, limit: Int): String = fileTool(
        "list_directory", JSONObject().put("path", path).put("show_hidden", showHidden).put("limit", limit)
            .put("identity", defaultIdentity(TerminalEnvironment.ANDROID)),
    )

    private fun normalizeIdentity(identity: String): String {
        val normalized = identity.ifBlank { "root" }.lowercase()
        require(normalized == "root" || normalized == "user") {
            "identity 仅支持 root/user"
        }
        return normalized
    }

    private fun normalizeEnvironment(environment: String): TerminalEnvironment =
        when (environment.ifBlank { TerminalEnvironment.ANDROID.wireName }.lowercase()) {
            TerminalEnvironment.ANDROID.wireName -> TerminalEnvironment.ANDROID
            SELECTED_LINUX_WIRE_NAME -> selectedLinuxEnvironmentProvider()
                .takeIf { it == TerminalEnvironment.ALPINE || it == TerminalEnvironment.DEBIAN }
                ?: TerminalEnvironment.ALPINE
            TerminalEnvironment.ALPINE.wireName -> TerminalEnvironment.ALPINE
            TerminalEnvironment.DEBIAN.wireName -> TerminalEnvironment.DEBIAN
            else -> throw IllegalArgumentException("environment 仅支持 android/linux")
        }

    private fun environmentPreflight(
        identity: String,
        environment: TerminalEnvironment,
        rootfsPath: String? = rootfsPathFor(environment),
    ): String? = when {
        environment.isLinux && identity != "root" && LinuxEnvironmentPaths.backendOf(rootfsPath) != LinuxExecutionBackend.PROOT ->
            errorJson("LINUX_ENVIRONMENT_REQUIRES_ROOT", "Linux 工具环境仅支持 root identity")
        environment.isLinux && !LinuxEnvironmentPaths.rootfsReady(rootfsPath) ->
            errorJson(
                "LINUX_ENVIRONMENT_NOT_READY",
                "Linux 工具环境尚未安装，请先在设置中完成环境配置",
            )
        identity == "root" && !rootAvailable() -> errorJson("ROOT_REQUIRED", "Root 授权不可用")
        environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT && identity == "root" ->
            errorJson("INVALID_IDENTITY", "免 Root Linux 使用普通应用身份，请使用 identity=user")
        else -> null
    }

    private fun defaultIdentity(environment: TerminalEnvironment): String = when {
        environment.isLinux -> TerminalRuntime.defaultIdentity(environment, rootfsPathFor(environment))
        rootAvailable() -> "root"
        else -> "user"
    }

    private fun normalizeCwd(cwd: String?, environment: TerminalEnvironment, identity: String): String {
        val defaultCwd = if (environment.isLinux) LINUX_DEFAULT_CWD else TerminalRuntime.workspace(identity)
        val requested = cwd.orEmpty().ifEmpty { defaultCwd }
        require('\u0000' !in requested && requested.length <= 4096) { "工作目录无效或过长" }
        val environmentPath = when {
            requested == "~" || requested.startsWith("~/") || requested.startsWith("/") -> requested
            else -> "$defaultCwd/$requested"
        }
        return if (environment.isLinux) {
            val value = when { environmentPath == "~" -> "/root"; environmentPath.startsWith("~/") -> "/root/${environmentPath.removePrefix("~/")}"; else -> environmentPath }
            value
        } else if (identity == "user") {
            val value = when { environmentPath == "~" -> defaultCwd; environmentPath.startsWith("~/") -> "$defaultCwd/${environmentPath.removePrefix("~/")}"; else -> environmentPath }
            File(value).canonicalPath
        } else normalizePath(environmentPath)
    }

    private fun normalizePath(path: String): String {
        val raw = path
        require(raw.isNotEmpty() && '\u0000' !in raw) { "path 无效" }
        val effective = when {
            raw == "~" -> USER_STORAGE
            raw.startsWith("~/") -> USER_STORAGE + "/" + raw.removePrefix("~/")
            raw.startsWith("/") -> raw
            else -> "$DEFAULT_CWD/$raw"
        }
        // Root 可见的链接未必对 App UID 可见，交给目标 Shell 按真实命名空间解析。
        return effective
    }

    private fun startSessionProcess(
        identity: String,
        environment: TerminalEnvironment,
        rootfsPath: String?,
    ): Process? =
        processSupervisor.startShellProcess(
            identity = identity,
            command = null,
            mergeStderr = false,
            environment = environment,
            linuxRootfsPath = rootfsPath,
            linuxSharedMounts = sharedMountsFor(environment),
        )

    /** 共享挂载只在 Linux 会话建立时解析；Android 环境不涉及。 */
    private fun sharedMountsFor(environment: TerminalEnvironment): List<SharedFolderMount> =
        if (environment.isLinux) linuxSharedMountsProvider() else emptyList()

    private fun rootfsPathFor(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private fun runText(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
    ): ShellTextResult {
        val result = runProcess(
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = environment,
            maxOutputBytes = MAX_COMMAND_OUTPUT_BYTES,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd(),
            outputTruncated = result.outputTruncated,
            stderrTruncated = result.stderrTruncated,
        )
    }

    private fun runProcess(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        stdin: ByteArray?,
        environment: TerminalEnvironment,
        maxOutputBytes: Int = Int.MAX_VALUE,
    ): OneShotShellResult =
        runOneShotShell(
            processSupervisor = processSupervisor,
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = stdin,
            environment = environment,
            linuxRootfsPath = rootfsPathFor(environment),
            linuxSharedMounts = sharedMountsFor(environment),
            maxOutputBytes = maxOutputBytes,
        )

    private fun String.truncateForJson(): String =
        if (length <= MAX_OUTPUT_CHARS) this else take(MAX_OUTPUT_CHARS) + "\n...[truncated]"

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(300))
            .toString()

    private data class ShellTextResult(
        val exitCode: Int,
        val output: String,
        val stderr: String,
        val outputTruncated: Boolean = false,
        val stderrTruncated: Boolean = false,
    )
    private data class SessionCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val cwd: String?,
        val timedOut: Boolean
    )

    private class TerminalSession(
        val id: String,
        val identity: String,
        val environment: TerminalEnvironment,
        val rootfsPath: String?,
        var cwd: String,
        val createdAt: Long,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector
    ) {
        val lock = Any()

        var runtime: JSONObject? = null

        @Volatile
        var closed: Boolean = false

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }

    private class AsyncCommand(
        val id: String,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector,
        val command: String,
        val cwd: String,
        val identity: String,
        val environment: TerminalEnvironment,
        val mergeStderr: Boolean,
        val sessionId: String?,
        val probeMarker: String,
        val startedAt: Long,
        val timeoutMs: Int
    ) {
        @Volatile
        var exitCode: Int? = null

        @Volatile
        var timedOut: Boolean = false

        @Volatile
        var completedAt: Long? = null

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }
}
