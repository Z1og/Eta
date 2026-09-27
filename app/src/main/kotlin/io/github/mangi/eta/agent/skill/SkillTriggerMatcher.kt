package io.github.mangi.eta.agent.skill

/**
 * Skill 关键词触发匹配器。
 *
 * 用户消息包含 SKILL.md frontmatter `triggers` 中的任一关键词时，
 * 该 Skill 被视为命中，其正文会在本轮运行中自动注入 system prompt，
 * 无需模型先通过 skills_read 索引再按需加载。
 *
 * 匹配规则：
 * - 大小写不敏感的子串匹配（中文关键词天然无大小写问题）
 * - 同一 Skill 只命中一次；按索引顺序去重
 * - 单轮最多注入 [MAX_AUTO_LOADED] 个 Skill 正文，防止 prompt 超限
 */
internal object SkillTriggerMatcher {

    /** 单轮自动加载的 Skill 正文上限。 */
    const val MAX_AUTO_LOADED = 3

    fun match(
        prompt: String,
        skills: List<SkillIndexEntry>,
        maxMatches: Int = MAX_AUTO_LOADED,
    ): List<SkillIndexEntry> {
        if (prompt.isBlank() || skills.isEmpty() || maxMatches <= 0) return emptyList()
        val haystack = prompt.lowercase()
        val matched = mutableListOf<SkillIndexEntry>()
        val seenIds = mutableSetOf<String>()
        for (skill in skills) {
            if (matched.size >= maxMatches) break
            if (skill.id in seenIds) continue
            val triggers = skill.triggers
            if (triggers.isEmpty()) continue
            val hit = triggers.any { trigger ->
                trigger.isNotBlank() && haystack.contains(trigger.lowercase())
            }
            if (hit) {
                matched += skill
                seenIds += skill.id
            }
        }
        return matched
    }
}
