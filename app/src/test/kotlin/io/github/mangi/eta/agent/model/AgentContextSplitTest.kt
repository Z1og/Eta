package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentContextSplitTest {

    @Test
    fun lastSafeSplitMatchesBruteForceOnEveryPrefix() {
        val history = listOf(
            user("第一个问题"),
            assistant(text = "先看看"),
            assistantToolCalls("call-1"),
            tool("call-1", "结果一"),
            assistant(text = "答案一"),
            user("第二个问题"),
            assistantToolCalls("call-2"),
            tool("call-2", "结果二"),
            user("第三个问题"),
        )
        for (size in 1..history.size) {
            val prefix = history.take(size)
            assertEquals(
                "size=$size",
                bruteForce(prefix),
                AgentContextCompactor.lastSafeSplit(prefix),
            )
        }
    }

    @Test
    fun openToolChainBlocksSplittingUntilResultArrives() {
        val open = listOf(
            user("问题"),
            assistant(text = "开始"),
            assistantToolCalls("call-9"),
        )
        assertEquals(2, AgentContextCompactor.lastSafeSplit(open))

        val closed = open + tool("call-9", "结果")
        assertEquals(4, AgentContextCompactor.lastSafeSplit(closed))
    }

    @Test
    fun emptyHistoryHasNoSplit() {
        assertEquals(null, AgentContextCompactor.lastSafeSplit(emptyList()))
    }

    private fun bruteForce(history: List<JSONObject>): Int? =
        (history.size downTo 1).firstOrNull { AgentContextCompactor.canSplit(history, it) }

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)

    private fun assistant(text: String) = JSONObject().put("role", "assistant").put("content", text)

    private fun assistantToolCalls(id: String) = JSONObject()
        .put("role", "assistant")
        .put("content", "")
        .put(
            "tool_calls",
            JSONArray().put(
                JSONObject().put("id", id).put(
                    "function",
                    JSONObject().put("name", "read_file").put("arguments", "{}"),
                ),
            ),
        )

    private fun tool(id: String, content: String) = JSONObject()
        .put("role", "tool")
        .put("tool_call_id", id)
        .put("content", content)
}
