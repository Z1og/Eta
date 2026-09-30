package io.github.mangi.eta.agent.skill

import kotlin.math.ln

/**
 * Skill 关键词触发匹配器。
 *
 * 用户消息包含 SKILL.md frontmatter `triggers`（或 `aliases`）中的任一关键词时，
 * 该 Skill 被视为命中，其正文可在本轮运行中自动注入 system prompt，
 * 无需模型先通过 skills_read 索引再按需加载。
 *
 * 匹配规则（增强版）：
 * - 大小写不敏感的子串匹配（中文关键词天然无大小写问题）
 * - 多信号输入：可同时喂入「本轮消息 + 最近若干轮对话尾」拼成的文本，
 *   以及从中探测到的文件扩展名集合（frontmatter `ext` 强命中）
 * - **IDF 降权**：被越多技能共享的触发词（"逆向/渗透"这类泛词）权重越低，
 *   独有专词（`ollvm/il2cpp/脱壳`）权重越高——无需人工标注，纯统计
 * - 排序：特异度（最长命中词 × IDF）优先，其次为加权总分
 *   （命中长度 × IDF × 技能 triggerWeight × 逆向偏置）
 * - 逆向模式开启时，命中逆向技能的得分 ×[REVERSE_BIAS]
 * - 同族去冗余：`eni-` / `seagull-` / `coldbrew-` 等大族最多占 [MAX_PER_FAMILY] 个名额
 * - 同一 Skill 只命中一次；单轮最多注入 [MAX_AUTO_LOADED] 个 Skill 正文
 */
internal object SkillTriggerMatcher {

    /** 单轮自动加载的 Skill 正文上限。 */
    const val MAX_AUTO_LOADED = 4

    /** 低置信时下发的候选短名单上限。 */
    const val MAX_CANDIDATES = 6

    /** 扩展名信号命中时折算的特异度——足够大，使其压过普通短词命中。 */
    private const val EXT_SIGNAL_LEN = 12

    /** 逆向模式开启时，逆向技能命中得分的乘数。 */
    private const val REVERSE_BIAS = 1.5

    /** 同一技能族允许占用的自动加载名额上限。 */
    private const val MAX_PER_FAMILY = 1

    /** 需要做去冗余的重型技能族前缀（同族技能触发词高度重叠）。 */
    private val FAMILY_PREFIXES = setOf("eni", "seagull", "coldbrew")

    private val EXTENSION_REGEX = Regex("\\.([a-zA-Z0-9]{1,6})\\b")

    /** 匹配输入——文本与已探测到的扩展名集合。 */
    data class Input(
        val text: String,
        val extensions: Set<String> = emptySet(),
    )

    /** 打分命中的技能：特异度（主排序键）与加权总分（次排序键）。 */
    data class Scored(
        val skill: SkillIndexEntry,
        val specificity: Double,
        val score: Double,
    )

    /** 从文本中探测文件扩展名（小写、不含点），用于扩展名强命中与结构化兜底路由。 */
    fun detectExtensions(text: String): Set<String> =
        EXTENSION_REGEX.findAll(text).mapTo(mutableSetOf()) { it.groupValues[1].lowercase() }

    /** 兼容旧调用：只看单条 prompt、无逆向偏置。 */
    fun match(
        prompt: String,
        skills: List<SkillIndexEntry>,
        maxMatches: Int = MAX_AUTO_LOADED,
    ): List<SkillIndexEntry> = match(Input(prompt), skills, maxMatches)

    fun match(
        input: Input,
        skills: List<SkillIndexEntry>,
        maxMatches: Int = MAX_AUTO_LOADED,
        reverseSkillIds: Set<String> = emptySet(),
        reverseBias: Boolean = false,
    ): List<SkillIndexEntry> {
        val ranked = rankScored(
            input = input,
            skills = skills,
            maxResults = maxMatches * 6,
            reverseSkillIds = reverseSkillIds,
            reverseBias = reverseBias,
        )
        return selectBodies(ranked, maxMatches)
    }

    /**
     * 打分并排序，返回带分数的命中（不做同族去冗余，供候选短名单使用）。
     * IDF 基于传入的 [skills] 语料实时统计——同一触发词被越多技能声明，权重越低。
     */
    fun rankScored(
        input: Input,
        skills: List<SkillIndexEntry>,
        maxResults: Int = MAX_CANDIDATES,
        reverseSkillIds: Set<String> = emptySet(),
        reverseBias: Boolean = false,
    ): List<Scored> {
        if (input.text.isBlank() || skills.isEmpty() || maxResults <= 0) return emptyList()
        val haystack = input.text.lowercase()
        val extensions = input.extensions
        val total = skills.size

        val documentFrequency = HashMap<String, Int>(512)
        for (skill in skills) {
            for (trigger in skill.triggers) {
                val key = trigger.trim().lowercase()
                if (key.isNotEmpty()) documentFrequency[key] = (documentFrequency[key] ?: 0) + 1
            }
        }

        fun idf(trigger: String): Double =
            0.3 + ln((total + 1.0) / ((documentFrequency[trigger] ?: 0) + 1.0))

        val hits = ArrayList<Scored>()
        val seenIds = HashSet<String>()
        for (skill in skills) {
            if (!seenIds.add(skill.id)) continue
            var specificity = 0.0
            var aggregate = 0.0
            for (raw in skill.triggers) {
                val trigger = raw.trim().lowercase()
                if (trigger.isEmpty() || !haystack.contains(trigger)) continue
                val contribution = trigger.length * idf(trigger)
                aggregate += contribution
                if (contribution > specificity) specificity = contribution
            }
            if (skill.extensions.isNotEmpty() && extensions.isNotEmpty() &&
                skill.extensions.any { it.lowercase() in extensions }
            ) {
                aggregate += EXT_SIGNAL_LEN
                if (EXT_SIGNAL_LEN.toDouble() > specificity) specificity = EXT_SIGNAL_LEN.toDouble()
            }
            if (specificity <= 0.0) continue
            var score = aggregate * skill.triggerWeight.coerceAtLeast(0.1)
            if (reverseBias && skill.id in reverseSkillIds) score *= REVERSE_BIAS
            hits += Scored(skill, specificity, score)
        }
        return hits
            .sortedWith(compareByDescending<Scored> { it.specificity }.thenByDescending { it.score })
            .take(maxResults)
    }

    /** 从打分结果里按同族去冗余挑选正文（供阈值筛选后的加载使用）。 */
    fun selectBodies(scored: List<Scored>, maxMatches: Int): List<SkillIndexEntry> =
        selectWithFamilyDiversity(scored.map { it.skill }, maxMatches)

    private fun familyOf(id: String): String? =
        id.substringBefore('-', "").takeIf { it in FAMILY_PREFIXES }

    private fun selectWithFamilyDiversity(
        ordered: List<SkillIndexEntry>,
        maxMatches: Int,
    ): List<SkillIndexEntry> {
        if (maxMatches <= 0) return emptyList()
        val familyCount = HashMap<String, Int>()
        val out = ArrayList<SkillIndexEntry>(maxMatches)
        for (skill in ordered) {
            val family = familyOf(skill.id)
            if (family != null) {
                val used = familyCount[family] ?: 0
                if (used >= MAX_PER_FAMILY) continue
                familyCount[family] = used + 1
            }
            out += skill
            if (out.size >= maxMatches) break
        }
        return out
    }
}
