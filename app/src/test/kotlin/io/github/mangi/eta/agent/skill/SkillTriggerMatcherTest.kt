package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTriggerMatcherTest {

    private fun skill(id: String, vararg triggers: String) = SkillIndexEntry(
        id = id,
        name = id,
        description = "desc $id",
        rootPath = "/tmp/skills/$id",
        skillFilePath = "/tmp/skills/$id/SKILL.md",
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
        triggers = triggers.toList(),
    )

    @Test
    fun `keyword hit loads matching skill`() {
        val skills = listOf(
            skill("apk-reverse", "apk逆向", "反编译apk", "jadx"),
            skill("game-cheat", "外挂", "透视", "自瞄"),
            skill("unrelated", "完全不相关的词"),
        )
        val matched = SkillTriggerMatcher.match("帮我用jadx反编译apk看看", skills)
        assertEquals(listOf("apk-reverse"), matched.map { it.id })
    }

    @Test
    fun `matching is case insensitive`() {
        val skills = listOf(skill("ida-reverse", "IDA Pro", "反汇编"))
        val matched = SkillTriggerMatcher.match("用ida pro分析一下这个so", skills)
        assertEquals(listOf("ida-reverse"), matched.map { it.id })
    }

    @Test
    fun `multiple skills match ranked by specificity`() {
        val skills = listOf(
            skill("first", "渗透"),
            skill("second", "渗透测试", "内网"),
            skill("third", "无关键词"),
        )
        val matched = SkillTriggerMatcher.match("做一次内网渗透测试", skills)
        // "渗透测试"(4字) 比 "渗透"(2字) 更具体，评分排序后 second 优先
        assertEquals(listOf("second", "first"), matched.map { it.id })
    }

    @Test
    fun `respects max matches`() {
        val skills = listOf(
            skill("a", "关键词"),
            skill("b", "关键词"),
            skill("c", "关键词"),
            skill("d", "关键词"),
        )
        val matched = SkillTriggerMatcher.match("关键词", skills)
        assertEquals(SkillTriggerMatcher.MAX_AUTO_LOADED, matched.size)
        assertEquals(4, matched.size)
    }

    @Test
    fun `no triggers means no match`() {
        val skills = listOf(skill("no-triggers"))
        assertTrue(SkillTriggerMatcher.match("任意文本", skills).isEmpty())
    }

    @Test
    fun `blank prompt returns empty`() {
        val skills = listOf(skill("a", "关键词"))
        assertTrue(SkillTriggerMatcher.match("", skills).isEmpty())
        assertTrue(SkillTriggerMatcher.match("   ", skills).isEmpty())
    }

    @Test
    fun `duplicate skill ids are deduplicated`() {
        val skills = listOf(
            skill("dup", "关键词"),
            skill("dup", "关键词"),
        )
        val matched = SkillTriggerMatcher.match("关键词", skills)
        assertEquals(1, matched.size)
    }

    private fun skillExt(id: String, extensions: List<String>, vararg triggers: String) = SkillIndexEntry(
        id = id,
        name = id,
        description = "desc $id",
        rootPath = "/tmp/skills/$id",
        skillFilePath = "/tmp/skills/$id/SKILL.md",
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
        triggers = triggers.toList(),
        extensions = extensions,
    )

    private fun skillWeighted(id: String, weight: Double, vararg triggers: String) = SkillIndexEntry(
        id = id,
        name = id,
        description = "desc $id",
        rootPath = "/tmp/skills/$id",
        skillFilePath = "/tmp/skills/$id/SKILL.md",
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
        triggers = triggers.toList(),
        triggerWeight = weight,
    )

    @Test
    fun `extension signal strongly matches skill without keyword`() {
        val skills = listOf(
            skillExt("apk-reverse", listOf("apk")),
            skill("unrelated", "完全无关的词"),
        )
        val text = "帮我看看这个 demo.apk 怎么回事"
        val input = SkillTriggerMatcher.Input(text, SkillTriggerMatcher.detectExtensions(text))
        assertEquals(
            listOf("apk-reverse"),
            SkillTriggerMatcher.match(input, skills).map { it.id },
        )
    }

    @Test
    fun `detectExtensions finds lowercase extensions`() {
        assertEquals(
            setOf("apk", "so"),
            SkillTriggerMatcher.detectExtensions("dump 出 libx.SO 与 a.apk"),
        )
    }

    @Test
    fun `reverse bias reorders tied non-reverse vs reverse skill`() {
        val skills = listOf(
            skill("plain", "逆向"),
            skill("apk-reverse", "逆向"),
        )
        val unbiased = SkillTriggerMatcher.match("逆向", skills)
        assertEquals(listOf("plain", "apk-reverse"), unbiased.map { it.id })

        val biased = SkillTriggerMatcher.match(
            input = SkillTriggerMatcher.Input("逆向"),
            skills = skills,
            reverseSkillIds = setOf("apk-reverse"),
            reverseBias = true,
        )
        assertEquals(listOf("apk-reverse", "plain"), biased.map { it.id })
    }

    @Test
    fun `higher trigger weight wins on equal hit length`() {
        val skills = listOf(
            skillWeighted("low", 1.0, "关键词"),
            skillWeighted("high", 2.0, "关键词"),
        )
        assertEquals(
            listOf("high", "low"),
            SkillTriggerMatcher.match("关键词", skills).map { it.id },
        )
    }

    @Test
    fun `heavy family is deduplicated to one slot`() {
        val skills = listOf(
            skill("eni-apk-reverse", "逆向"),
            skill("eni-binary-diff", "逆向"),
            skill("eni-js-reverse", "逆向"),
            skill("eni-reverse-deep", "逆向"),
            skill("apk-reverse", "逆向"),
        )
        val matched = SkillTriggerMatcher.match("逆向", skills).map { it.id }
        assertEquals(1, matched.count { it.startsWith("eni-") })
        assertTrue("apk-reverse" in matched)
    }
}
