package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    /** 每轮工具结果回传后的重触发钩子：返回非空提示文本时，作为「技能提示」追加到下一轮上下文。 */
    private val onToolResultsHint: ((String) -> String?)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
    /** 持续目标正文；非空时模型自然收尾后会自动续跑，直到达成、被取消或触及上限。 */
    private val goal: String = "",
    /** 当前子任务清单进度；有清单时以清单为完成依据，而不是模型自己的说法。 */
    private val goalProgress: (() -> GoalProgress?)? = null,
    /** 本地轮次上限，0 表示不设限（主 run 沿用模型自然结束）。子代理必须给上限。 */
    private val maxRounds: Int = 0,
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var availableToolNames = emptySet<String>()
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
    )
    private var supplementIndex = initialSupplementIndex

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var skillHintIndex = 0

    /** 目标已经续跑过的轮数，以及是否已经收敛（完成 / blocked / 取消 / 触顶）。 */
    private var goalContinuations = 0
    private var goalSettled = false

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1
        var precedingTools: JSONArray? = null

        // 单次拒绝只跳过当前子任务；连续拒绝达到上限才升级为失败，交给执行层换端 failover。
        var consecutiveRefusals = 0

        while (true) {
            // 子代理没有用户盯着，必须有本地上限：目标写得含糊时它不会自己认输。
            if (maxRounds > 0 && round > maxRounds) {
                throw AgentModelFailure(
                    "ROUND_LIMIT",
                    false,
                    "本次运行超过 $maxRounds 轮上限，已停止；请缩小任务范围后重试。",
                )
            }
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            // Anthropic 思考签名绑定发出工具调用时的 system 与 tools；工具结果回传后再刷新目录。
            val roundTools = if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
                precedingTools ?: tools
            } else if (purpose.allowsTools) {
                toolsForRound?.invoke() ?: tools
            } else {
                JSONArray()
            }
            precedingTools = roundTools
            availableToolNames = (0 until roundTools.length()).mapNotNullTo(mutableSetOf()) { index ->
                roundTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
            }
            publishTranscript()
            if (purpose.allowsTools) context.compact()
            val requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages) ?: messages,
            )
            var roundInputTokens: Int? = null
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose),
                    provider = provider,
                    controller = runController,
                    onEvent = { event ->
                        if (event is AgentEvent.RoundStarted) roundInputTokens = null
                        onEvent(event)
                    },
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                            throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
                        }
                        if (providerEvent is ProviderEvent.Usage) {
                            roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                        }
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                ).also { response ->
                    rejectContentFilter(response.response)
                    if (purpose == ProviderRequestPurpose.CHAT) validateChatResponse(response.response)
                }
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            context.observeInputTokens(roundInputTokens)
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            if (purpose.allowsTools && toolCalls.isEmpty() &&
                AgentModelRefusal.isRefusal(assistantMessage.optString("content"))
            ) {
                // 拒绝内容不入上下文（否则后续轮次被「我不能…」带偏）。单次拒绝只跳过这个子任务：
                // 追加中性的续跑指令，让既定计划继续推进；连续拒绝才升级为失败交由 failover。
                if (consecutiveRefusals >= REFUSAL_SKIP_LIMIT) {
                    throw AgentModelRefusal.failure(assistantMessage.optString("content"))
                }
                consecutiveRefusals++
                onEvent(
                    AgentEvent.ModelRetryScheduled(
                        round = round,
                        attempt = consecutiveRefusals,
                        maxAttempts = REFUSAL_SKIP_LIMIT + 1,
                        delayMs = 0,
                        reasonCode = "MODEL_REFUSAL_SKIPPED",
                    ),
                )
                appendMessage(AgentConversationCodec.userTextMessage(AgentModelRefusal.continuationNote(consecutiveRefusals)))
                continue
            }
            consecutiveRefusals = 0
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_eta_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                val outcomes = toolCalls.map { call ->
                    val outcome = when (providerResponse.stopReason) {
                        AssistantStopReason.TOOL_USE -> executeTool(round, call)
                        AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                            round, call, "TRUNCATED_TOOL_CALL",
                            "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                        )
                        else -> rejectedToolOutcome(
                            round, call, "UNEXPECTED_TOOL_CALL",
                            "模型在 ${providerResponse.stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
                        )
                    }
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                    publishTranscript()
                    outcome
                }
                appendToolImages(round, outcomes)
                publishTranscript()
                // 运行中自动重触发：工具结果里出现新信号时，追加一条技能提示供下一轮参考。
                // 提示只进 messages（供 provider），不进 transcript（不在会话 UI 显示）。
                if (onToolResultsHint != null) {
                    val hint = onToolResultsHint.invoke(outcomes.joinToString("\n") { it.result.content })
                    if (!hint.isNullOrBlank()) {
                        messages.put(
                            AgentConversationCodec.userTextMessage("[Eta 技能提示] $hint")
                                .put("_eta_message_id", "user-$operationId-skillhint-${++skillHintIndex}"),
                        )
                        context.userAppended()
                    }
                }
                round += 1
                continue
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            // 目标还没收敛时不能封闭接收入口：run 仍在跑，后面来的补充指令必须还能进来。
            if (purpose.allowsTools && appendPendingSteeringOrSeal(sealWhenEmpty = !hasActiveGoal())) {
                round += 1
                continue
            }
            // steering 优先于目标续跑：用户刚插进来的指令不该被自动续跑顶掉。
            if (purpose.allowsTools && hasActiveGoal() && continueGoal(assistantMessage.optString("content"))) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(final = true)
            // 到这里才真正收尾：此后进来的补充指令会被拒绝，而不是被收下却永远不执行。
            runController.sealSteering()
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = stripGoalStatus(content),
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    /**
     * 拒答或过滤会在流中途截断回复，半截正文、思考块和工具调用都不能进入上下文：
     * 带着被截断的签名块继续请求会被上游判定为改动了 thinking 块，同一会话随后每轮都会 400。
     * 因此不写入 history、不执行工具，直接结束本次运行，由用户改写请求或换模型。
     */
    private fun rejectContentFilter(response: ProviderResponse) {
        if (response.stopReason != AssistantStopReason.CONTENT_FILTER) return
        val explanation = response.assistantMessage.optJSONObject("stop_details")
            ?.let { details -> details.optString("explanation").takeUnless { details.isNull("explanation") } }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        throw AgentModelFailure(
            "MODEL_CONTENT_FILTER",
            false,
            buildString {
                append("模型服务商拦截了本次回复，未完成的内容已丢弃。")
                explanation?.let { append("服务商说明：").append(it) }
                append("请修改或删除触发拦截的请求后重试，或换用其他模型。")
            },
        )
    }

    private fun validateChatResponse(response: ProviderResponse) {
        val message = response.assistantMessage
        if (AgentConversationCodec.parseToolCalls(message).isNotEmpty()) return
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return
        throw when (response.stopReason) {
            AssistantStopReason.OUTPUT_LIMIT ->
                AgentModelFailure("MODEL_OUTPUT_LIMIT", false, "模型输出额度已耗尽但未生成正文，请检查输出上限或降低思考强度。")
            else -> AgentModelFailure("MODEL_EMPTY_RESPONSE", false, "模型未返回正文或工具调用，请检查服务商状态。")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun appendPendingSteeringOrSeal(sealWhenEmpty: Boolean = true): Boolean {
        val supplement = if (sealWhenEmpty) {
            runController.pollSteeringOrSeal()
        } else {
            runController.pollSteeringMessage()
        } ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    /** 目标还在推进中（有正文、且尚未判定完成/被取消/触及上限）。 */
    private fun hasActiveGoal(): Boolean = goal.isNotBlank() && !goalSettled

    /**
     * 目标续跑的唯一判定点。
     *
     * 只在模型自己停下来之后调用：模型说过 done/blocked、用户取消、或触及轮次上限时不续跑。
     * 上限是硬性的——目标写得含糊时，模型不会自己认输，没有上限就是无限烧 token。
     */
    private fun continueGoal(content: String): Boolean {
        when (goalStatusOf(content)) {
            GOAL_STATUS_DONE, GOAL_STATUS_BLOCKED -> {
                goalSettled = true
                return false
            }
            else -> Unit
        }
        if (runController.isGoalCancelled) {
            goalSettled = true
            return false
        }
        // 有清单就以清单为准：模型说"差不多了"不算完成，清单里还有待办就得继续。
        val progress = goalProgress?.invoke()
        if (progress != null && progress.total > 0 && progress.allDone) {
            goalSettled = true
            return false
        }
        if (goalContinuations >= MAX_GOAL_CONTINUATIONS) {
            goalSettled = true
            appendMessage(
                AgentConversationCodec.userTextMessage(
                    "目标已连续推进 $MAX_GOAL_CONTINUATIONS 轮仍未报告完成，本轮到此结束。" +
                        "不要继续调用工具；用一句话说明当前进度和剩余部分，交给用户决定下一步。",
                ).put("_eta_message_id", "user-$operationId-goallimit"),
            )
            context.userAppended()
            return true
        }
        goalContinuations++
        val progressNote = progress?.takeIf { it.total > 0 }?.let {
            "当前子任务进度：${it.done}/${it.total} 完成，还有 ${it.open} 条待办。" +
                "用 task_write 更新清单状态，全部 done 才算目标达成。"
        }.orEmpty()
        appendMessage(
            AgentConversationCodec.userTextMessage(
                "继续推进目标：上一轮没有报告目标达成。" +
                    progressNote +
                    "先确认已经完成的部分，再处理剩余部分；不要重做已完成或已验证过的步骤。" +
                    "确实无法继续时输出 <goal_status>blocked</goal_status> 并说明卡在哪。",
            ).put("_eta_message_id", "user-$operationId-goal-$goalContinuations"),
        )
        context.userAppended()
        return true
    }

    private fun goalStatusOf(content: String): String? =
        GOAL_STATUS_REGEX.find(content)?.groupValues?.getOrNull(1)?.lowercase()

    /** 完成标记是运行时信号，不该显示给用户。整段只有标记时保留原文，避免出现空回复。 */
    private fun stripGoalStatus(content: String): String =
        content.replace(GOAL_STATUS_REGEX, "").trim().ifBlank { content }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_eta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        if (toolCall.name !in availableToolNames) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "TOOL_NOT_AVAILABLE",
                message = "工具未在本轮能力目录中提供，请使用当前可用工具",
            )
        }
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )

        val result = try {
            toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 每个已完成结果立即落盘；图片观察仍统一放在完整工具批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_eta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}

/** 连续被拒多少个子任务后升级为失败（此前每次仅跳过当前子任务并续跑）。 */
private const val REFUSAL_SKIP_LIMIT = 2

/** 目标模式下子任务清单的进度快照，供续跑判定使用。 */
internal data class GoalProgress(
    val total: Int,
    val done: Int,
    val open: Int,
    val allDone: Boolean,
)

/**
 * 目标模式最多自动续跑多少轮。
 *
 * 目标含糊时模型不会自己认输，所以上限必须是硬性的；到顶就交回用户，
 * 而不是继续烧 token 或反复执行同一批操作。
 */
private const val MAX_GOAL_CONTINUATIONS = 12

private const val GOAL_STATUS_DONE = "done"
private const val GOAL_STATUS_BLOCKED = "blocked"

/** 容忍模型在标记里加空格或换行：解析失败就等于目标永远不收敛。 */
private val GOAL_STATUS_REGEX = Regex("""<goal_status>\s*([A-Za-z]+)\s*</goal_status>""")
