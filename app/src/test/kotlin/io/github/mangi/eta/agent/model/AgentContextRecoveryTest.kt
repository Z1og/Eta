package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextRecoveryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
    )

    @Test
    fun forcedCompactionIncludesLargeRecentRepliesAndPreservesLatestUser() {
        val recent = "近期事实".repeat(5_000)
        val messages = messages((1..6).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "任务 $turn"),
            AgentModelClient.ConversationMessage("assistant", if (turn >= 5) recent else "旧事实".repeat(500)),
        ) })
        val original = messages.toString()
        val compacted = AgentContextCompactor(config, provider { _, _ -> response("已完成六个任务。") }, AgentRunController())
            .compact(messages, 1, emptySet(), force = true)
        assertTrue(AgentContextBudget.rawEstimate(compacted) < 1_000)
        assertEquals("任务 6", compacted.getJSONObject(compacted.length() - 1).getString("content"))
        assertEquals(5, compacted.getJSONObject(1).getInt("_eta_compacted_users"))
        assertEquals(original, messages.toString())
    }

    @Test
    fun automaticCompactionCanIncludeAnOversizedCompletedTail() {
        val messages = messages(listOf(
            AgentModelClient.ConversationMessage("user", "当前任务"),
            AgentModelClient.ConversationMessage("assistant", "长".repeat(70_000)),
        ))
        val session = AgentContextSession(config.copy(contextWindow = 80_000), messages, 1, "operation",
            provider { _, _ -> response("已完成当前任务。") }, AgentRunController(), { emptySet() }, {}, {})
        session.compact(JSONArray())
        assertTrue(AgentContextBudget.rawEstimate(messages) < 1_000)
        assertNotNull(session.snapshot())
        assertEquals("当前任务", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test
    fun largeSingleToolBatchIsSummarizedWithoutLosingTextOrBreakingUnicode() {
        val group = listOf(
            AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = """[
                {"id":"a","type":"function","function":{"name":"fixture","arguments":"{}"}},
                {"id":"b","type":"function","function":{"name":"fixture","arguments":"{}"}}]"""),
            AgentModelClient.ConversationMessage("tool", "头部" + "中文😀\"\\\n".repeat(10_000) + "尾部", toolCallId = "a"),
            AgentModelClient.ConversationMessage("tool", "第二项结果", toolCallId = "b"),
        )
        val fragments = mutableListOf<String>()
        val summary = AgentContextSummarizer(config, provider { request, _ ->
            assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
            assertEquals(0, request.effectiveTools.length())
            assertTrue(AgentContextBudget.rawEstimate(request.messages) <= 32_000)
            val fragment = dataText(request)
            assertFalse(fragment.first().isLowSurrogate())
            assertFalse(fragment.last().isHighSurrogate())
            fragments += fragment
            response("完整摘要")
        }, AgentRunController(), false).summarize(listOf(group))
        assertEquals("完整摘要", summary)
        assertTrue(fragments.size > 1)
        assertEquals(group.joinToString("\n", postfix = "\n") { AgentConversationCodec.toJsonObject(it).toString() },
            fragments.joinToString(""))
    }

    @Test
    fun overflowWithinOneMessageSplitsItAndMergesEveryFragment() {
        val message = AgentModelClient.ConversationMessage("assistant", "始" + "内".repeat(4_000) + "终")
        var requests = 0
        val accepted = mutableListOf<String>()
        val summary = AgentContextSummarizer(config, provider { request, _ ->
            requests++
            val text = dataText(request)
            if (text.length > 2_500) throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
            if (accepted.isNotEmpty()) assertTrue(request.messages.toString().contains("前段摘要"))
            accepted += text
            response(if (accepted.size == 1) "前段摘要" else "完整摘要")
        }, AgentRunController(), false).summarize(listOf(listOf(message)))
        assertEquals(3, requests)
        assertEquals(AgentConversationCodec.toJsonObject(message).toString() + "\n", accepted.joinToString(""))
        assertEquals("完整摘要", summary)
    }

    @Test
    fun emptyOrTruncatedSummaryRetriesWithSmallerInputButNeverCommitsPartialResults() {
        for (stop in listOf("stop", "length")) {
            val messages = messages(listOf(
                AgentModelClient.ConversationMessage("user", "旧任务"),
                AgentModelClient.ConversationMessage("assistant", "事实".repeat(3_000)),
            ))
            val original = messages.toString()
            val sizes = mutableListOf<Int>()
            val session = AgentContextSession(config, messages, 1, "operation", provider { request, _ ->
                sizes += dataText(request).length
                response(if (stop == "stop") "" else "半截摘要", stop)
            }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交") })
            val error = assertThrows(AgentModelFailure::class.java) { session.compact(JSONArray(), force = true) }
            assertEquals("CONTEXT_SUMMARY_INVALID", error.code)
            assertEquals(4, sizes.size)
            assertTrue(sizes.zipWithNext().all { (before, after) -> after < before })
            assertEquals(original, messages.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun cancellationAfterAFragmentLeavesTheOriginalContextIntact() {
        val messages = messages(listOf(
            AgentModelClient.ConversationMessage("assistant", "长".repeat(65_000)),
            AgentModelClient.ConversationMessage("user", "继续"),
        ))
        val original = messages.toString()
        val controller = AgentRunController()
        var requests = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            if (++requests == 2) controller.cancel()
            response("部分摘要")
        }, controller, { emptySet() }, {}, { fail("取消时不能提交") })
        assertThrows(Exception::class.java) { session.compact(JSONArray(), force = true) }
        assertEquals(2, requests)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun emptyLengthResponseNearWindowCompactsBeforeRetryWithoutKeepingFailedOutput() {
        var chats = 0
        var summaries = 0
        val result = AgentModelClient.complete(config.copy(contextWindow = 100_000), "继续",
            AgentModelClient.ToolExecutor { error("不应执行工具") }, history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("旧任务已完成。")
                } else if (++chats == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 95_000, outputTokens = 5_000)))
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "未完成思考"))
                    response("", "length")
                } else {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 1_000)))
                    response("完成")
                }
            })
        assertEquals(2, chats)
        assertEquals(1, summaries)
        assertEquals(listOf("完成"), result.transcript.map { it.content })
        assertEquals("", result.reasoningContent)
    }

    @Test
    fun emptyResponsesWithoutCapacityEvidenceNeverTriggerCompaction() {
        data class Case(val window: Int?, val input: Int?, val stop: String, val code: String)
        val cases = listOf(
            Case(100_000, 1_000, "length", "MODEL_OUTPUT_LIMIT"),
            Case(100_000, null, "length", "MODEL_OUTPUT_LIMIT"),
            Case(null, 1_010_000, "length", "MODEL_OUTPUT_LIMIT"),
            Case(100_000, 95_000, "content_filter", "MODEL_CONTENT_FILTER"),
            Case(100_000, 95_000, "stop", "MODEL_EMPTY_RESPONSE"),
        )
        for (case in cases) {
            var requests = 0
            val failure = assertThrows(AgentModelExecutionException::class.java) {
                AgentModelClient.complete(config.copy(contextWindow = case.window), "继续",
                    AgentModelClient.ToolExecutor { error("不应执行工具") }, history = history(), provider = provider { request, emit ->
                        requests++
                        assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = case.input, outputTokens = 100_000)))
                        response("", case.stop)
                    })
            }
            assertEquals(case.code, (failure.cause as AgentModelFailure).code)
            assertEquals(1, requests)
            assertTrue(failure.transcript.isEmpty())
        }
    }

    @Test
    fun emptyLengthResponseAfterHostedToolIsNotReplayed() {
        var requests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config.copy(contextWindow = 100_000), "查询",
                AgentModelClient.ToolExecutor { error("不应执行工具") }, history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.HostedToolStarted("hosted", "web_search"))
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 95_000)))
                    response("", "length")
                })
        }
        assertEquals(1, requests)
        assertFalse((failure.cause as AgentModelFailure).recoveryAllowed)
    }

    @Test
    fun retryDoesNotReuseInputUsageFromAFailedAttempt() {
        var requests = 0
        val loop = AgentLoop(
            config = config.copy(contextWindow = 100_000), messages = messages(history()), systemCount = 1,
            tools = JSONArray(), provider = provider { request, emit ->
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                if (++requests == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 95_000)))
                    throw AgentModelFailure.incompleteStream("fixture")
                }
                response("", "length")
            }, toolExecutor = AgentModelClient.ToolExecutor { error("不应执行工具") },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(), onEvent = {},
            modelRetry = AgentModelRetry { _, _ -> },
        )
        val failure = assertThrows(AgentModelFailure::class.java) { loop.run() }
        assertEquals("MODEL_OUTPUT_LIMIT", failure.code)
        assertEquals(2, requests)
    }

    @Test
    fun summaryDoesNotRetryFilteredResponsesOrUnexpectedHostedTools() {
        for (hosted in listOf(false, true)) {
            var requests = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentContextSummarizer(config, provider { _, emit ->
                    requests++
                    if (hosted) emit(ProviderEvent.HostedToolStarted("hosted", "web_search"))
                    response("", if (hosted) "stop" else "content_filter")
                }, AgentRunController(), false).summarize(listOf(history()))
            }
            assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
            assertFalse(failure.recoveryAllowed)
            assertEquals(1, requests)
        }
    }

    private fun history() = listOf(AgentModelClient.ConversationMessage("assistant", "旧事实".repeat(2_000)))

    private fun messages(history: List<AgentModelClient.ConversationMessage>) =
        JSONArray().put(JSONObject().put("role", "system").put("content", "规则")).also { messages ->
            history.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        }

    private fun dataText(request: ProviderRequest) = request.messages.getJSONObject(1).getString("content")
        .substringAfter("待整理的历史：\n")

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request, onEvent)
    }
}
