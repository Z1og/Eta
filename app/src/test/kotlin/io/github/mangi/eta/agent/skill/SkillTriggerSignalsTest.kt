package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTriggerSignalsTest {
    private fun message(
        role: String,
        content: String = "",
        reasoning: String = "",
    ) = AgentModelClient.ConversationMessage(
        role = role,
        content = content,
        reasoningContent = reasoning,
    )

    @Test
    fun reasoningJoinsTheTriggerText() {
        val history = listOf(
            message("user", content = "看看这个文件"),
            message("assistant", content = "我先看看结构", reasoning = "这是一个 so 文件，ELF 格式"),
        )

        val text = SkillTriggerSignals.buildText("继续", history)

        assertTrue("思考里的技术判断必须参与路由", text.contains("ELF"))
    }

    @Test
    fun onlyTheTailOfLongReasoningIsUsed() {
        // 思考的前半段多是试探与自我纠错，结论通常在尾部。
        val reasoning = "也许该先这样。".repeat(400) + "最终判断：需要 frida hook"
        val history = listOf(message("assistant", reasoning = reasoning))

        val text = SkillTriggerSignals.buildText("继续", history)

        assertTrue(text.contains("frida"))
        assertTrue("不应把整段思考都拼进来", text.length < 2_000)
    }

    @Test
    fun reasoningAloneCanDriveRouting() {
        // 用户只说"继续"，靠上一轮思考里的 elf 命中技能。
        val history = listOf(
            message("user", content = "看看这个文件"),
            message(
                "assistant",
                content = "",
                reasoning = "这是 ELF 二进制，先确认架构再看加密",
            ),
        )
        val skill = SkillIndexEntry(
            id = "binary-analysis",
            name = "binary-analysis",
            description = "",
            rootPath = "/skills/binary-analysis",
            skillFilePath = "/skills/binary-analysis/SKILL.md",
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            triggers = listOf("elf", "二进制"),
        )

        val ranked = SkillTriggerMatcher.rankScored(
            input = SkillTriggerMatcher.Input(
                text = SkillTriggerSignals.buildText("继续", history),
                extensions = emptySet(),
            ),
            skills = listOf(skill),
        )

        assertEquals("binary-analysis", ranked.firstOrNull()?.skill?.id)
    }
}
