package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubAgentToolTest {
    @Test
    fun subAgentsActuallyRunAtTheSameTime() {
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val gate = CountDownLatch(1)
        val tool = AgentSubAgentTool { role, _, _ ->
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            // 三个都起来了才放行；串行实现会在这里逐个超时，maxConcurrent 永远到不了 3。
            gate.await(2, TimeUnit.SECONDS)
            concurrent.decrementAndGet()
            AgentModelClient.ToolResult("$role 完成")
        }
        val releaser = Thread {
            val deadline = System.currentTimeMillis() + 2_000
            while (maxConcurrent.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            gate.countDown()
        }.apply { start() }

        val payload = JSONObject(
            tool.execute(
                call(
                    """{"tasks":[
                        {"role":"读配置","prompt":"读 a"},
                        {"role":"读日志","prompt":"读 b"},
                        {"role":"查文档","prompt":"查 c"}]}""",
                ),
            ).content,
        )
        releaser.join(2_000)

        assertEquals(3, maxConcurrent.get())
        assertEquals(3, payload.getInt("count"))
        assertEquals(0, payload.getInt("failed"))
        assertTrue(payload.getBoolean("ok"))
        assertTrue(payload.getString("summary").contains("读配置 完成"))
    }

    @Test
    fun oneFailingSubAgentDoesNotDiscardTheOthers() {
        val tool = AgentSubAgentTool { role, _, _ ->
            if (role == "坏掉的那个") {
                throw IllegalStateException("子代理崩了")
            }
            AgentModelClient.ToolResult("$role 完成")
        }

        val payload = JSONObject(
            tool.execute(
                call(
                    """{"tasks":[
                        {"role":"好的","prompt":"做"},
                        {"role":"坏掉的那个","prompt":"做"}]}""",
                ),
            ).content,
        )

        assertEquals(2, payload.getInt("count"))
        assertEquals(1, payload.getInt("failed"))
        // 失败的那条也留在结果里，主代理才知道它是失败了而不是没派出去。
        assertEquals(2, payload.getJSONArray("agents").length())
        assertTrue(payload.getString("summary").contains("子代理崩了"))
    }

    @Test
    fun emptyOrOversizedBatchesAreRejectedBeforeSpawningAnything() {
        var invoked = 0
        val tool = AgentSubAgentTool { _, _, _ ->
            invoked++
            AgentModelClient.ToolResult("不应被调用")
        }

        val empty = JSONObject(tool.execute(call("""{"tasks":[]}""")).content)
        assertEquals("SUBAGENT_EMPTY", empty.getString("code"))

        val tooMany = JSONObject(
            tool.execute(
                call(
                    (1..AgentSubAgentTool.MAX_SUB_AGENTS + 1)
                        .joinToString(prefix = """{"tasks":[""", postfix = "]}") {
                            """{"role":"r$it","prompt":"p"}"""
                        },
                ),
            ).content,
        )
        assertEquals("SUBAGENT_TOO_MANY", tooMany.getString("code"))
        assertEquals(0, invoked)
    }

    private fun call(arguments: String) = AgentModelClient.ToolCall(
        id = "call-1",
        name = AgentSubAgentTool.SPAWN_AGENTS,
        argumentsJson = arguments,
    )
}
