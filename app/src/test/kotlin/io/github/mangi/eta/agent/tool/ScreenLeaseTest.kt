package io.github.mangi.eta.agent.tool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ScreenLeaseTest {
    @Before
    @After
    fun releaseBothAgents() {
        ScreenLease.releaseAll("agent-a")
        ScreenLease.releaseAll("agent-b")
    }

    @Test
    fun theSameAgentCanReenterWithoutBlockingItself() {
        val value = ScreenLease.withLease("agent-a") {
            ScreenLease.withLease("agent-a") { "ok" }
        }
        assertEquals("ok", value)
    }

    @Test
    fun anotherAgentIsRejectedWithAnExplicitHolderInsteadOfWaitingForever() {
        ScreenLease.withLease("agent-a") {
            val failure = assertThrows(ScreenBusyException::class.java) {
                ScreenLease.withLease("agent-b", timeoutMs = 150) { "不应执行" }
            }
            assertEquals("agent-a", failure.holder)
        }
    }

    @Test
    fun releasingAllFreesTheScreenEvenInsideTheStickyWindow() {
        // run 结束时必须能立刻放开，否则屏幕会锁死在一个已经停手的 agent 上。
        ScreenLease.withLease("agent-a") { ScreenLease.releaseAll("agent-a") }

        val owner = ScreenLease.withLease("agent-b", timeoutMs = 500) {
            ScreenLease.currentOwner()
        }
        assertEquals("agent-b", owner)
    }
}
