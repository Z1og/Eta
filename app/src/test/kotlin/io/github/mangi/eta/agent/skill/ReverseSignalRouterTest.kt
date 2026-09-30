package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseSignalRouterTest {

    private fun skill(id: String) = SkillIndexEntry(
        id = id,
        name = id,
        description = "desc $id",
        rootPath = "/tmp/skills/$id",
        skillFilePath = "/tmp/skills/$id/SKILL.md",
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
    )

    private val installed = listOf(
        skill("apk-reverse"),
        skill("binary-analysis"),
        skill("dynamic-instrumentation"),
        skill("network-pentest"),
        skill("unrelated"),
    )

    @Test
    fun `routes by file extension to apk-reverse`() {
        val routed = ReverseSignalRouter.route("帮我看看这个 target.apk", installed, 4)
        assertEquals(listOf("apk-reverse"), routed.map { it.id })
    }

    @Test
    fun `routes by tool name to dynamic-instrumentation`() {
        val routed = ReverseSignalRouter.route("用 frida 挂一下这个进程", installed, 4)
        assertEquals(listOf("dynamic-instrumentation"), routed.map { it.id })
    }

    @Test
    fun `routes by multiple signals keeping priority order`() {
        val routed = ReverseSignalRouter.route("target.so 用 frida 调", installed, 4)
        assertTrue(routed.map { it.id }.containsAll(listOf("binary-analysis", "dynamic-instrumentation")))
        assertEquals("binary-analysis", routed.first().id)
    }

    @Test
    fun `no signal yields empty`() {
        assertTrue(ReverseSignalRouter.route("今天天气怎么样", installed, 4).isEmpty())
    }

    @Test
    fun `signal for uninstalled skill yields empty`() {
        val routed = ReverseSignalRouter.route("ghidra 反编译", installed, 4)
        assertTrue(routed.isEmpty())
    }

    @Test
    fun `respects max matches`() {
        val routed = ReverseSignalRouter.route("target.apk target.so frida nmap", installed, 2)
        assertEquals(2, routed.size)
    }
}
