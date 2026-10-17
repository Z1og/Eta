package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentModelCapabilityProbe
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelRefusal
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.GoalProgress
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.ConversationMemoryPins
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillTriggerMatcher
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.ProjectSpecDetector
import io.github.mangi.eta.agent.skill.ResolvedSkillContext
import io.github.mangi.eta.agent.skill.ReverseSignalRouter
import io.github.mangi.eta.agent.skill.ReverseSkillCatalog
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillTriggerSignals
import io.github.mangi.eta.agent.skill.SkillUsageStats
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentSubAgentTool
import io.github.mangi.eta.agent.tool.AgentTaskTool
import io.github.mangi.eta.agent.tool.ScreenBusyException
import io.github.mangi.eta.agent.tool.ScreenLease
import io.github.mangi.eta.agent.tool.ScreenToolPolicy
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentTaskPlanStore
import io.github.mangi.eta.data.repository.AgentUserPromptRepository
import io.github.mangi.eta.data.repository.AgentUserPromptSnapshot
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.config.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        val archivedEvents = mutableListOf<AgentEvent>()
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        // 失败清理需要在 try 外拿到会话 id。
        var memoryConversationId: String? = null
        val result = try {
            val contextWindow = request.config.knownContextWindow
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val compatibleSkills = skillIndexService.listInstalledSkills()
                .filter { SkillCompatibilityChecker.evaluate(it).available }
            // 逆向总开关：持久化状态，不再从"逆向技能是否全部启用"反推。
            // 反推在逆向集合扩到两百个之后必然失败（差一个就 false），
            // 表现就是开关打开后预读与破甲记忆都不生效。
            val reverseModeEnabled = if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                false
            } else {
                runCatching { runBlocking { SettingsDataStore.reverseBundleEnabled() } }
                    .onFailure { throwable ->
                        AndroidAgentLogger.warnThrottled("reverse_bundle_state_failed") {
                            "Reverse bundle state read failed: type=${throwable.safeLogType()}"
                        }
                    }
                    .getOrDefault(false)
            }
            // 逆向技能 = 全部技能 − eta 原生三个，按当前索引实时算。
            val reverseSkillIds = ReverseSkillCatalog.reverseIdsOf(
                skillIndexService.listSkillsForManagement().map { it.id },
            )
            // 关键词触发：用户消息命中 SKILL.md triggers 时自动加载正文（改写回复等非任务操作不触发）。
            // 增强：信号从「单条消息」扩为「本轮消息 + 最近若干轮对话尾」；一并启用扩展名强命中
            // （frontmatter `ext`）、IDF 泛词降权、逆向模式偏置、同族去冗余与本地使用反馈加权；
            // 低置信命中不下发正文、改给候选短名单；零关键词命中时按扩展名/工具名走兜底路由。
            val triggerText = if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                ""
            } else {
                SkillTriggerSignals.buildText(request.prompt, request.history)
            }
            // 使用反馈：给本地统计里确实被 skills_read 读过的技能温和加权（失败静默）。
            val usageStats = runCatching { SkillUsageStats.load(appContext.filesDir) }
                .getOrDefault(emptyMap())
            // 负样本：自动注入多次但从未被实际读取的技能温和降权（触发可能不准）。
            val autoLoads = runCatching { SkillUsageStats.loadAutoLoads(appContext.filesDir) }
                .getOrDefault(emptyMap())
            val rankedSkills = if (usageStats.isEmpty() && autoLoads.isEmpty()) {
                compatibleSkills
            } else {
                compatibleSkills.map { entry ->
                    val reads = usageStats[entry.id] ?: 0
                    val boost = SkillUsageStats.boost(reads) *
                        SkillUsageStats.dampen(autoLoads[entry.id] ?: 0, reads)
                    if (boost == 1.0) entry
                    else entry.copy(triggerWeight = entry.triggerWeight * boost)
                }
            }
            val autoLoadedSkills: List<ResolvedSkillContext>
            val rankedCandidates: List<SkillIndexEntry>
            if (triggerText.isBlank()) {
                autoLoadedSkills = emptyList()
                rankedCandidates = emptyList()
            } else {
                val triggerInput = SkillTriggerMatcher.Input(
                    text = triggerText,
                    extensions = SkillTriggerMatcher.detectExtensions(triggerText),
                )
                val ranked = SkillTriggerMatcher.rankScored(
                    input = triggerInput,
                    skills = rankedSkills,
                    maxResults = SkillTriggerMatcher.MAX_CANDIDATES + SkillTriggerMatcher.MAX_AUTO_LOADED,
                    reverseSkillIds = reverseSkillIds,
                    reverseBias = reverseModeEnabled,
                )
                // 阈值：只对足够特异的命中自动下发正文（<2.0 视为低置信），避免注入错技能。
                val strongBodies = SkillTriggerMatcher.selectBodies(
                    ranked.filter { it.specificity >= 2.0 },
                    SkillTriggerMatcher.MAX_AUTO_LOADED,
                )
                // 结构化兜底路由（扩展名/工具名）——补足关键词命中不到的口。
                val bySignal = ReverseSignalRouter.route(
                    text = triggerText,
                    skills = compatibleSkills,
                    maxMatches = SkillTriggerMatcher.MAX_AUTO_LOADED,
                )
                val selected = (strongBodies + bySignal)
                    .distinctBy { it.id }
                    .take(SkillTriggerMatcher.MAX_AUTO_LOADED)
                // 记录自动注入，作为负样本素材（自动注入但未被读取的技能将被温和降权）。
                runCatching { selected.forEach { SkillUsageStats.recordAutoLoad(appContext.filesDir, it.id) } }
                autoLoadedSkills = selected.mapNotNull { entry ->
                    runCatching { skillLoader.load(entry, "trigger-keyword") }
                        .onFailure { throwable ->
                            AndroidAgentLogger.warnThrottled("skill_trigger_load_failed") {
                                "Auto-load failed for skill ${entry.id}: type=${throwable.safeLogType()}"
                            }
                        }
                        .getOrNull()
                }
                val loadedIds = selected.mapTo(mutableSetOf()) { it.id }
                rankedCandidates = if (loadedIds.size >= SkillTriggerMatcher.MAX_AUTO_LOADED) {
                    emptyList()
                } else {
                    ranked.map { it.skill }
                        .filter { it.id !in loadedIds }
                        .distinctBy { it.id }
                        .take(SkillTriggerMatcher.MAX_CANDIDATES)
                }
            }
            // Trellis 项目规范探测（方案 A）：从用户消息中的路径向上找 .trellis/spec/，
            // 命中则注入规范索引；失败静默降级，不影响正常对话。
            val projectSpecs = if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                null
            } else {
                runCatching { ProjectSpecDetector.detect(request.prompt) }
                    .onFailure { throwable ->
                        AndroidAgentLogger.warnThrottled("trellis_spec_detect_failed") {
                            "Trellis spec detection failed: type=${throwable.safeLogType()}"
                        }
                    }
                    .getOrNull()
            }
            val skillContext = SkillContext(
                installedSkills = compatibleSkills,
                autoLoadedSkills = autoLoadedSkills,
                rankedCandidates = rankedCandidates,
                projectSpecs = projectSpecs,
                // 逆向总开关（融合 dsh-infinite-gen-4）：一个开关统管技能启停、协议注入与预读偏置。
                reverseModeEnabled = reverseModeEnabled,
                reverseSkillIds = reverseSkillIds,
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            memoryConversationId = conversationId
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, contextWindow, memoryEnabled) }
            }
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    EtaDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val characterMemoryTools = roleplayContext?.let { roleplay ->
                CharacterMemoryTools(appContext, roleplay.characterId) {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                }
            }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    ConversationMemoryPins.contextFor(
                        conversationId = conversationId,
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            // 用户自备作战协议（filesDir/prompts/ 下第一个非空 .md）。
            // 会话开始时读取一次并冻结：中途改文件只影响下个会话，避免系统提示抖动废掉请求前缀缓存。
            // 只看作战协议自己的开关：与逆向能力开关平级，关掉逆向不影响这里，反之亦然。
            val userPrompt = runBlocking {
                runCatching {
                    AgentUserPromptRepository.loadFor(
                        enabled = AgentUserPromptRepository.enabled(),
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_user_prompt_unavailable") {
                        "User prompt unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentUserPromptSnapshot.EMPTY
                }
            }
            if (request.config.terminalTools) {
                // 启动探测由 App 拥有；模型首轮等待其结果，避免把检测中的授权当成普通设备。
                runBlocking {
                    while (io.github.mangi.eta.agent.device.RootAccess.state.value.isChecking) {
                        runController.throwIfCancelled()
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            val executor = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                memoryWritable = roleplayContext == null,
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility =
                        AgentToolRequirements.requiresAccessibility(toolName)
                    if (
                        !requiresAccessibility &&
                        !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                    ) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) {
                            AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                        } else {
                            null
                        }
                        when {
                            accessibility != null && !accessibility.available ->
                                ToolExecutionDecision.Reject(
                                    code = accessibility.code,
                                    message = accessibility.message,
                                )
                            entrySurfaceGuard?.dismissOnce() == false ->
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                                )
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
            )
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            val historyTool = conversationId?.let { id ->
                ConversationHistoryTool {
                    val checkpoint = runBlocking { EtaDatabase.get(appContext).conversationDao().contextCheckpoint(id) }
                    val journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson)
                        .ifEmpty { AgentConversationCodec.decodeTranscript(checkpoint?.historyJson) }
                    journal + session.transcript
                }
            }
            val taskStore = conversationId?.let { AgentTaskPlanStore(appContext.filesDir) }
            val taskTool = if (taskStore != null && conversationId != null) {
                AgentTaskTool(taskStore, conversationId)
            } else {
                null
            }
            var subAgentTool: AgentSubAgentTool? = null
            var subAgentExecutor: AgentModelClient.ToolExecutor? = null
            val runTools = JSONArray(mcpTools.toString()).also { tools ->
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
                if (taskTool != null) {
                    tools.put(taskTool.schema())
                    tools.put(AgentSubAgentTool.schema())
                }
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    characterMemoryTools.execute(call)
                } else if (call.name == AgentTaskTool.TASK_WRITE && taskTool != null) {
                    taskTool.execute(call)
                } else if (call.name == AgentSubAgentTool.SPAWN_AGENTS) {
                    subAgentTool?.execute(call) ?: errorToolResult(
                        "SUBAGENT_UNAVAILABLE", "当前会话不支持子代理",
                    )
                } else {
                    val invoke = {
                        routingExecutor.execute(call).also { result ->
                            if (call.name == "memory_write") {
                                runCatching { JSONObject(result.content) }.getOrNull()
                                    ?.takeIf { it.optBoolean("ok") }
                                    ?.let { ConversationMemoryPins.recordOwnWrite(conversationId, it.optString("revision")) }
                            }
                        }
                    }
                    // 屏幕是进程级独占资源：两个 agent 交替 observe/tap 会让彼此的观察互相作废。
                    if (ScreenToolPolicy.isScreenTool(call.name)) {
                        runCatching { ScreenLease.withLease(request.runId) { invoke() } }
                            .getOrElse { throwable ->
                                if (throwable is ScreenBusyException) {
                                    errorToolResult(
                                        "SCREEN_BUSY",
                                        "屏幕正被另一个 agent（${throwable.holder}）使用；先做不需要看屏幕的步骤，稍后重试。",
                                    )
                                } else {
                                    throw throwable
                                }
                            }
                    } else {
                        invoke()
                    }
                }
            }
            // 子代理的执行器：拦掉屏幕、派发与清单写入，其余能力共享。
            // 共享工具实例是有意的——屏幕观察状态才需要隔离，而不碰屏幕的子代理不会污染它。
            subAgentExecutor = AgentModelClient.ToolExecutor { call ->
                when {
                    call.name == AgentSubAgentTool.SPAWN_AGENTS ->
                        errorToolResult("SUBAGENT_CANNOT_SPAWN", "子代理不能再派子代理；请自己完成这一步。")
                    ScreenToolPolicy.isScreenTool(call.name) ->
                        errorToolResult("SCREEN_TOOLS_UNAVAILABLE", "子代理不能操作屏幕；需要看屏幕的步骤留给主代理。")
                    call.name == AgentTaskTool.TASK_WRITE ->
                        errorToolResult("SUBAGENT_CANNOT_WRITE_TASKS", "子代理不能修改主代理的任务清单。")
                    else -> runToolExecutor.execute(call)
                }
            }
            subAgentTool = AgentSubAgentTool { role, prompt ->
                val childExecutor = subAgentExecutor
                if (childExecutor == null) {
                    errorToolResult("SUBAGENT_UNAVAILABLE", "子代理执行器未就绪")
                } else {
                    // 每个子代理独立上下文、独立取消器：主 run 被取消时级联终止，不留后台野进程。
                    val childController = AgentRunController()
                    val binding = runController.register { childController.cancel() }
                    // 子代理没有用户盯着，轮次上限之外再加一道墙钟：超过就直接取消，
                    // 让它在检查点抛取消异常，而不是无限跑下去烧 token。
                    val watchdog = Thread {
                        try {
                            Thread.sleep(SUB_AGENT_TIMEOUT_MS)
                            childController.cancel()
                        } catch (_: InterruptedException) {
                            // 正常结束时由 finally 打断，属于预期路径。
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                    try {
                        val response = AgentModelClient.complete(
                            config = request.config,
                            maxRounds = SUB_AGENT_MAX_ROUNDS,
                            prompt = buildString {
                                appendLine("你是主代理派出的子代理，角色：$role。")
                                appendLine("你看不到主对话，只依据下面的任务说明工作。")
                                appendLine()
                                appendLine(prompt)
                                appendLine()
                                append(
                                    "要求：只做这一件事；做完直接给结论与证据（文件路径、命令输出、来源）。" +
                                        "不能操作屏幕，不能派子代理，不能反问；缺什么就自己用工具查清楚。",
                                )
                            },
                            toolExecutor = childExecutor,
                            history = emptyList(),
                            runController = childController,
                            skillContext = skillContext,
                            memoryContext = AgentMemoryContext.DISABLED,
                            // 子代理不背作战协议：81k 字符乘以并行数会把窗口直接撑爆。
                            userPrompt = AgentUserPromptSnapshot.EMPTY,
                            additionalTools = runTools,
                            executionContext = executor.terminalExecutionContext,
                        )
                        AgentModelClient.ToolResult(response.content)
                    } catch (throwable: Throwable) {
                        errorToolResult("SUBAGENT_FAILED", throwable.message ?: "子代理执行失败")
                    } finally {
                        watchdog.interrupt()
                        binding.close()
                    }
                }
            }
            fun runModelAttempt(modelConfig: AgentModelClient.ModelConfig): AgentModelClient.ModelResponse.Text =
                AgentModelClient.complete(
                    config = modelConfig,
                    sessionId = request.effectiveModelSessionId,
                    operationId = request.runId,
                    initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                    initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                    roleplayContext = roleplayContext,
                    rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                    compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                    onContextSnapshot = { snapshot ->
                        // 压缩会把历史里的记忆写入摘要掉，下一轮需要重新注入最新记忆。
                        ConversationMemoryPins.forget(conversationId)
                        val committed = snapshot.copy(operationId = request.runId)
                        AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                        session.updateContext(committed)
                    },
                    onTranscript = { transcript ->
                        AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                        session.updateTranscript(transcript)
                    },
                    capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                    prompt = request.prompt,
                    assistantScreenContext = request.assistantScreenContext,
                    toolExecutor = runToolExecutor,
                    images = request.images,
                    history = request.history,
                    runController = runController,
                    skillContext = skillContext,
                    memoryContext = memoryContext,
                    userPrompt = userPrompt,
                    goal = request.goal,
                    goalProgress = taskStore?.let { store ->
                        {
                            val plan = store.load(conversationId.orEmpty())
                            if (plan.total == 0) {
                                null
                            } else {
                                GoalProgress(
                                    total = plan.total,
                                    done = plan.doneCount,
                                    open = plan.openCount,
                                    allDone = plan.allDone,
                                )
                            }
                        }
                    },
                    // 清单跟着工具结果再注入一次，但只在它真的变了时注入：
                    // 每轮无脑重发会把上下文泡满，反而挤掉工作空间。
                    taskPlanHint = taskStore?.let { store ->
                        var lastSignature = ""
                        {
                            val plan = store.load(conversationId.orEmpty())
                            val signature = plan.tasks.joinToString { "${it.id}:${it.status.name}" }
                            if (plan.total == 0 || signature == lastSignature) {
                                null
                            } else {
                                lastSignature = signature
                                "当前任务清单（用 ${AgentTaskTool.TASK_WRITE} 更新）：\n" + plan.summary()
                            }
                        }
                    },
                    additionalTools = runTools,
                    executionContext = executor.terminalExecutionContext,
                ) { event ->
                    timing.accept(event)
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }

            val completedResponse = withRefusalRetry(request, onRetry = { retryAttempt ->
                acceptEvent(
                    session,
                    AgentEvent.ModelRetryScheduled(
                        round = 1,
                        attempt = retryAttempt,
                        maxAttempts = REFUSAL_RETRY_MAX_ATTEMPTS + 1,
                        delayMs = 0,
                        reasonCode = "MODEL_REFUSAL_RETRY",
                    ),
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                )
            }) { modelConfig ->
                runModelAttempt(modelConfig)
            }
            response = completedResponse
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            // 失败时历史是否完整落盘不确定，放弃固定的记忆快照；记忆未变时重新注入的内容逐字节相同。
            ConversationMemoryPins.forget(memoryConversationId)
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                if (requestFailure?.code == "CONTEXT_OVERFLOW") {
                    learnContextWindowFromOverflow(request.config, requestFailure)
                }
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
        }

        if (cancelled && session.isTerminal) {
            runCatching {
                persistArtifacts(snapshotRequest(request), result, archivedEvents)
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, result, archivedEvents) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        archivedEvents += event
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }

    /**
     * 口头拒绝重试：当前模型拒绝配合（MODEL_REFUSAL）时，**用当前 provider 当前模型**
     * 原样重发同一请求（不换端），最多 [REFUSAL_RETRY_MAX_ATTEMPTS] 次，仍拒绝才把失败
     * 交给上层。仅对话类操作启用；改写/压缩/手动压缩不重试。拒绝内容不入上下文
     * （Loop 层已保证），因此重试是干净的全量重跑；采样随机性让重试有实际通过率。
     */
    private fun withRefusalRetry(
        request: AgentRuntimeWire.RunRequest,
        onRetry: (attempt: Int) -> Unit,
        attempt: (AgentModelClient.ModelConfig) -> AgentModelClient.ModelResponse.Text,
    ): AgentModelClient.ModelResponse.Text {
        val modelConfig = request.config
        var retryAttempt = 0
        while (true) {
            try {
                return attempt(modelConfig)
            } catch (failure: AgentModelExecutionException) {
                val cause = failure.cause as? AgentModelFailure
                if (
                    cause?.code != AgentModelRefusal.FAILURE_CODE ||
                    retryAttempt >= REFUSAL_RETRY_MAX_ATTEMPTS ||
                    request.operation != AgentRuntimeWire.OP_CHAT ||
                    !Prefs.isEnabled(Prefs.Keys.AGENT_REFUSAL_FAILOVER_ENABLED)
                ) {
                    throw failure
                }
                retryAttempt++
                AndroidAgentLogger.warn(
                    "Agent refusal retry (same provider): attempt=$retryAttempt " +
                        "provider=${modelConfig.providerId} model=${modelConfig.model}",
                )
                onRetry(retryAttempt)
            }
        }
    }

    /**
     * 超限报错里常带服务商写明的上下文上限——这是**权威**数字，直接学下来写进模型覆盖值，
     * 下次换回该模型时自动压缩就能按真实上限触发（不必等用户手填或探测）。
     * 失败静默：学习只是锦上添花，绝不影响本次失败的呈现。
     */
    private fun learnContextWindowFromOverflow(
        config: AgentModelClient.ModelConfig,
        failure: AgentModelFailure?,
    ) {
        val learned = AgentModelCapabilityProbe.parseContextLimit(failure?.message.orEmpty()) ?: return
        if (config.providerId.isBlank()) return
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching {
                withTimeout(LEARN_WINDOW_TIMEOUT_MS) {
                    val provider = ProviderRepository.providerById(config.providerId) ?: return@withTimeout
                    val target = provider.models.firstOrNull { it.modelId == config.model }
                        ?: return@withTimeout
                    if (target.effectiveContextWindow == learned) return@withTimeout
                    ModelRepository.saveModel(provider.id, target.copy(contextWindowOverride = learned))
                    AndroidAgentLogger.info(
                        "Agent learned context window from provider error: provider=${provider.id} " +
                            "model=${config.model} window=$learned",
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent context window learning failed: type=${throwable.safeLogType()}",
                )
            }
        }
    }
}

/** 学习窗口的落库超时：宁可不学，也不要拖住失败呈现。 */
private const val LEARN_WINDOW_TIMEOUT_MS = 10_000L

/** 口头拒绝后用当前 provider 原样重试的次数上限。 */
private const val REFUSAL_RETRY_MAX_ATTEMPTS = 2

/** 子代理轮次上限：没有用户盯着，含糊任务不会自己认输。 */
private const val SUB_AGENT_MAX_ROUNDS = 12

/** 子代理墙钟上限：轮次再宽松也得有个终点，防止单个子代理拖住整批。 */
private const val SUB_AGENT_TIMEOUT_MS = 300_000L

/** 工具层的失败结果：统一 `{ok:false, code, message}`，模型能据此换方案而不是崩掉。 */private fun errorToolResult(code: String, message: String): AgentModelClient.ToolResult =
    AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).put("message", message).toString(),
    )
