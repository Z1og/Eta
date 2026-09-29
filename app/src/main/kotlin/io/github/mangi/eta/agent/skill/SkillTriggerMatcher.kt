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
 * - 按匹配质量排序：最长命中优先（更具体的触发词代表更强的意图信号），
 *   相同长度时按累计命中长度，再相同则保持索引顺序
 * - 同一 Skill 只命中一次；单轮最多注入 [MAX_AUTO_LOADED] 个 Skill 正文
 */
internal object SkillTriggerMatcher {

    /** 单轮自动加载的 Skill 正文上限。 */
    const val MAX_AUTO_LOADED = 4

    fun match(
        prompt: String,
        skills: List<SkillIndexEntry>,
        maxMatches: Int = MAX_AUTO_LOADED,
    ): List<SkillIndexEntry> {
        if (prompt.isBlank() || skills.isEmpty() || maxMatches <= 0) return emptyList()
        val haystack = prompt.lowercase()

        data class Hit(
            val skill: SkillIndexEntry,
            val best: Int,
            val total: Int,
        )

        val hits = mutableListOf<Hit>()
        val seenIds = mutableSetOf<String>()
        for (skill in skills) {
            if (skill.id in seenIds) continue
            val triggers = skill.triggers
            if (triggers.isEmpty()) continue
            var best = 0
            var total = 0
            for (trigger in triggers) {
                val t = trigger.trim().lowercase()
                if (t.isEmpty()) continue
                if (haystack.contains(t)) {
                    if (t.length > best) best = t.length
                    total += t.length
                }
            }
            if (best > 0) {
                hits += Hit(skill, best, total)
                seenIds += skill.id
            }
        }
        // sortedWith 为稳定排序：得分相同的条目保持索引顺序
        return hits
            .sortedWith(compareByDescending<Hit> { it.best }.thenByDescending { it.total })
            .take(maxMatches)
            .map { it.skill }
    }
}
