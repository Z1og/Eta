package io.github.mangi.eta.agent.skill

/**
 * 逆向能力集合的唯一定义。
 *
 * 判定方式刻意反过来：**不枚举逆向技能，而是枚举 eta 原版自带的技能**。
 * 随包技能已有 200+ 个且每天由同步任务增加，靠白名单枚举逆向技能必然漏——
 * 漏掉的那些在总开关打开时不会被启用，表现就是"开关像是失效的"。
 *
 * eta 原版只带这三个（upstream 的 builtin_skills/manifest.json 就是它们），
 * 其余随包与导入的全部属于逆向能力。
 */
object ReverseSkillCatalog {
    /** eta 原版自带的技能；除此之外一律视为逆向能力。 */
    val NATIVE_SKILL_IDS: Set<String> = setOf(
        "self-improving-agent",
        "skill-creator",
        "skill-installer",
    )

    fun isReverse(skillId: String): Boolean = skillId.isNotBlank() && skillId !in NATIVE_SKILL_IDS

    /** 从全量技能 id 算出逆向集合；打分偏置与提示词分组都用它。 */
    fun reverseIdsOf(allIds: Collection<String>): Set<String> =
        allIds.filterTo(mutableSetOf(), ::isReverse)

    /** 只剩原生技能的极端情况：此时总开关没有可启停的目标。 */
    fun hasReverseTargets(allIds: Collection<String>): Boolean =
        allIds.any(::isReverse)
}
