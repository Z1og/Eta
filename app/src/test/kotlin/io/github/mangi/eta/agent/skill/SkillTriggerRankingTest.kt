package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillTriggerRankingTest {
    private fun skill(
        id: String,
        triggers: List<String>,
        weight: Double = 1.0,
        extensions: List<String> = emptyList(),
    ) = SkillIndexEntry(
        id = id,
        name = id,
        description = "",
        rootPath = "/skills/$id",
        skillFilePath = "/skills/$id/SKILL.md",
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
        triggers = triggers,
        triggerWeight = weight,
        extensions = extensions,
    )

    private val query = "帮我反编译这个 apk 看看签名校验"

    @Test
    fun aSkillWithDozensOfTriggersNoLongerMonopolisesTheTopSlot() {
        // 复刻 reverse-task-planner 的形态：62 个触发词，其中一个又长又罕见。
        // 旧排序只看最长命中词的 specificity，它会永远排第一。
        val omnivore = skill(
            "omnivore",
            listOf("逆向工程全流程自动化交付") + (1..61).map { "泛词$it" },
        )
        val precise = skill("precise", listOf("反编译", "apk", "签名校验"))

        val ranked = SkillTriggerMatcher.rankScored(
            input = SkillTriggerMatcher.Input(text = query),
            skills = listOf(omnivore, precise),
        )

        assertEquals("precise", ranked.first().skill.id)
    }

    @Test
    fun extensionOnlyMatchDoesNotOutrankARealKeywordHit() {
        val extOnly = skill("ext", listOf("完全无关的词"), extensions = listOf("apk"))
        val keyword = skill("kw", listOf("反编译"))

        val text = "看看这个 .apk，帮我反编译"
        val ranked = SkillTriggerMatcher.rankScored(
            input = SkillTriggerMatcher.Input(
                text = text,
                extensions = SkillTriggerMatcher.detectExtensions(text),
            ),
            skills = listOf(extOnly, keyword),
        )

        assertEquals("kw", ranked.first().skill.id)
    }

    @Test
    fun specificityIsStillReportedForTheBodyThreshold() {
        val ranked = SkillTriggerMatcher.rankScored(
            input = SkillTriggerMatcher.Input(text = query),
            skills = listOf(skill("precise", listOf("反编译", "apk", "签名校验"))),
        )

        // 阈值筛选（>= 2.0 才下发正文）依赖 specificity，不能因为改排序把它弄丢。
        assertEquals(1, ranked.size)
        assert(ranked.first().specificity > 0.0)
    }
}
