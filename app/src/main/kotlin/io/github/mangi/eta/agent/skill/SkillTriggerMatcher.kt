package io.github.mangi.eta.agent.skill

/**
 * Skill 关键词触发匹配器。
 *
 * 用户消息包含 SKILL.md frontmatter `triggers`（或 `aliases`）中的任一关键词时，
 * 该 Skill 被视为命中，其正文会在本轮运行中自动注入 system prompt，
 * 无需模型先通过 skills_read 索引再按需加载。
 *
 * 匹配规则（增强版）：
 * - 大小写不敏感的子串匹配（中文关键词天然无大小写问题）
 * - 多信号输入：可同时喂入「本轮消息 + 最近若干轮对话尾」拼成的文本，
 *   以及从中探测到的文件扩展名集合（frontmatter `ext` 强命中）
 * - 排序：最长命中优先（更具体的触发词代表更强的意图信号），
 *   相同长度时按加权得分（命中长度 × 技能 triggerWeight × 逆向偏置）
 * - 逆向模式开启时，[SkillIndexEntry.id] 命中的逆向技能得分 ×[REVERSE_BIAS]，
 *   让逆向/渗透意图更容易被对应技能接住
 * - 同族去冗余：`eni-` / `seagull-` / `coldbrew-` 等大族最多占 [MAX_PER_FAMILY] 个名额，
 *   避免同族技能刷满全部自动加载位、挤掉真正对口的那一个
 * - 同一 Skill 只命中一次；单轮最多注入 [MAX_AUTO_LOADED] 个 Skill 正文
 */
internal object SkillTriggerMatcher {

    /** 单轮自动加载的 Skill 正文上限。 */
    const val MAX_AUTO_LOADED = 4

    /** 扩展名信号命中时折算的关键词长度——足够大，使其压过普通短词命中。 */
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
        if (input.text.isBlank() || skills.isEmpty() || maxMatches <= 0) return emptyList()
        val haystack = input.text.lowercase()
        val extensions = input.extensions

        data class Hit(
            val skill: SkillIndexEntry,
            val best: Int,
            val score: Double,
        )

        val hits = mutableListOf<Hit>()
        val seenIds = mutableSetOf<String>()
        for (skill in skills) {
            if (skill.id in seenIds) continue
            var best = 0
            var total = 0
            for (trigger in skill.triggers) {
                val t = trigger.trim().lowercase()
                if (t.isEmpty()) continue
                if (haystack.contains(t)) {
                    if (t.length > best) best = t.length
                    total += t.length
                }
            }
            // 扩展名信号：技能声明的 ext 与消息里出现的扩展名相交即强命中。
            if (skill.extensions.isNotEmpty() && extensions.isNotEmpty() &&
                skill.extensions.any { it.lowercase() in extensions }
            ) {
                if (EXT_SIGNAL_LEN > best) best = EXT_SIGNAL_LEN
                total += EXT_SIGNAL_LEN
            }
            if (best <= 0) continue
            var score = total.toDouble() * skill.triggerWeight.coerceAtLeast(0.1)
            if (reverseBias && skill.id in reverseSkillIds) score *= REVERSE_BIAS
            hits += Hit(skill, best, score)
            seenIds += skill.id
        }
        // sortedWith 为稳定排序：得分相同的条目保持索引顺序
        val ordered = hits
            .sortedWith(compareByDescending<Hit> { it.best }.thenByDescending { it.score })
            .map { it.skill }
        return selectWithFamilyDiversity(ordered, maxMatches)
    }

    private fun familyOf(id: String): String? =
        id.substringBefore('-', "").takeIf { it in FAMILY_PREFIXES }

    private fun selectWithFamilyDiversity(
        ordered: List<SkillIndexEntry>,
        maxMatches: Int,
    ): List<SkillIndexEntry> {
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
