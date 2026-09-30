package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
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
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

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

        val result = try {
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
            // 逆向模式状态：所有已安装逆向技能均启用即视为开启（与技能页主开关判定一致）。
            // 失败静默降级为关闭，不影响正常对话。
            val reverseModeEnabled = if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                false
            } else {
                runCatching {
                    val reverseInstalled = skillIndexService.listSkillsForManagement()
                        .filter { it.installed && it.id in ReverseSkillCatalog.REVERSE_SKILL_IDS }
                    reverseInstalled.isNotEmpty() && reverseInstalled.all { it.enabled }
                }.onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("reverse_mode_state_failed") {
                        "Reverse mode state detection failed: type=${throwable.safeLogType()}"
                    }
                }.getOrDefault(false)
            }
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
                    reverseSkillIds = ReverseSkillCatalog.REVERSE_SKILL_IDS,
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
                // 逆向模式状态（融合 dsh-infinite-gen-4）：与技能页主开关同源——
                // 所有已安装逆向技能均启用即视为开启，开启时常驻注入逆向交付契约。
                reverseModeEnabled = reverseModeEnabled,
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, request.config.contextWindow, memoryEnabled) }
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
                    AgentMemoryContextBuilder.build(
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
            val runTools = JSONArray(mcpTools.toString()).also { tools ->
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    characterMemoryTools.execute(call)
                } else routingExecutor.execute(call)
            }
            val completedResponse = AgentModelClient.complete(
                config = request.config,
                sessionId = request.effectiveModelSessionId,
                operationId = request.runId,
                initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                roleplayContext = roleplayContext,
                rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                onContextSnapshot = { snapshot ->
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
                additionalTools = runTools,
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
}
