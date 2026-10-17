package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseSkillCatalogTest {
    @Test
    fun onlyTheThreeUpstreamSkillsCountAsNative() {
        // upstream 的 builtin_skills/manifest.json 就这三个，其余全部是随包/导入的逆向能力。
        assertFalse(ReverseSkillCatalog.isReverse("self-improving-agent"))
        assertFalse(ReverseSkillCatalog.isReverse("skill-creator"))
        assertFalse(ReverseSkillCatalog.isReverse("skill-installer"))

        assertTrue(ReverseSkillCatalog.isReverse("apk-reverse"))
        assertTrue(ReverseSkillCatalog.isReverse("hanshuang-router"))
        assertTrue(ReverseSkillCatalog.isReverse("hw-trace"))
        // 关键性质：新增技能无需登记就自动算逆向——靠白名单枚举正是之前漏掉一百多个的原因。
        assertTrue(ReverseSkillCatalog.isReverse("some-future-skill-2099"))
    }

    @Test
    fun reverseIdsAreComputedFromTheLiveIndexInsteadOfAHardcodedList() {
        val liveIds = listOf(
            "self-improving-agent", "skill-creator", "skill-installer",
            "apk-reverse", "hw-trace", "hanshuang-router",
        )

        assertEquals(
            setOf("apk-reverse", "hw-trace", "hanshuang-router"),
            ReverseSkillCatalog.reverseIdsOf(liveIds),
        )
        assertTrue(ReverseSkillCatalog.hasReverseTargets(liveIds))
        assertFalse(ReverseSkillCatalog.hasReverseTargets(listOf("self-improving-agent")))
    }

    @Test
    fun blankIdIsNeverReverseSoItCannotLeakIntoTheBundle() {
        assertFalse(ReverseSkillCatalog.isReverse(""))
        assertFalse(ReverseSkillCatalog.isReverse("   "))
        assertEquals(emptySet<String>(), ReverseSkillCatalog.reverseIdsOf(listOf("", "self-improving-agent")))
    }
}
