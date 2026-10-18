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

    /** 纯扩展名命中的排序分：固定很低，保证永远排在真正命中关键词的技能之后。 */
    private const val EXT_ONLY_RANK = 0.1

    /**
     * 拉丁术语（so / elf / dex / frida / hook...）。
     *
     * 真实数据里 so 相关触发词全是复合词（`so逆向`、`so破解`、`so加固`），
     * 用户说"so 文件的加密逻辑"时完整子串一个都命中不了 —— 整条查询召回 0 个技能。
     * 术语层就是为了补这个洞：查询与触发词共享同一个术语时算弱命中。
     */
    private val LATIN_TERM = Regex("[a-z][a-z0-9_]{1,15}")

    /**
     * 太常见、不构成信号的词。
     *
     * 刻意**不含 `so`**：在逆向语境里它几乎总是指 Shared Object（`.so` 文件），
     * 而 so 相关技能的关键词恰恰都是"so逆向/so破解/so加固"这类复合词，
     * 把 so 当停用词会让整类任务召回为 0。误命中风险有限（涉及 so 的技能只有几个）。
     */
    private val LATIN_STOPWORDS = setOf(
        "my", "is", "the", "to", "in", "on", "of", "and", "for", "an",
        "it", "this", "that", "with", "how", "can", "do", "you", "me",
    )

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
        /**
         * 排序用综合分：命中词的平均特异性 × 命中密度。
         *
         * 只看 specificity 会让"某个触发词特别长"的技能永远霸榜——
         * 一个带 60 个触发词的技能几乎必然命中，然后靠最长那个词把别人挤掉。
         * 密度（命中数 / 触发词数，封顶 8）让"只有 5 个触发词却命中 3 个"的技能
         * 能赢过"60 个触发词只命中 3 个"的技能。
         */
        val rank: Double = score,
        /** 命中次数（含术语弱命中）。真实数据里命中数往往只有 1–2 个，它是比 specificity 更可靠的信号。 */
        val hitCount: Int = 0,
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
        // 去空白副本：中文用户输入常在拉丁词与中文之间夹空格（"frida 检测"），
        // 而触发词是连写（"frida检测"）。含 CJK 的触发词额外做一次去空白匹配，
        // 纯拉丁触发词不做——去空白会把 "admit my" 桥接成 "mitm" 这类误命中。
        val haystackCompact = haystack.filterNot { it.isWhitespace() }
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
        // 查询里的技术术语，用于补复合触发词召回不到的洞。
        val queryTerms = LATIN_TERM.findAll(haystack)
            .map { it.value }
            .filter { it !in LATIN_STOPWORDS }
            .toSet()
        for (skill in skills) {
            if (!seenIds.add(skill.id)) continue
            var specificity = 0.0
            var aggregate = 0.0
            var hitCount = 0
            var idfSum = 0.0
            for (raw in skill.triggers) {
                val trigger = raw.trim().lowercase()
                if (trigger.isEmpty()) continue
                val matched = haystack.contains(trigger) ||
                    (containsCjk(trigger) && haystackCompact.contains(trigger.filterNot { it.isWhitespace() }))
                if (!matched) continue
                val contribution = trigger.length * idf(trigger)
                aggregate += contribution
                hitCount += 1
                idfSum += idf(trigger)
                if (contribution > specificity) specificity = contribution
            }
            if (skill.extensions.isNotEmpty() && extensions.isNotEmpty() &&
                skill.extensions.any { it.lowercase() in extensions }
            ) {
                aggregate += EXT_SIGNAL_LEN
                if (EXT_SIGNAL_LEN.toDouble() > specificity) specificity = EXT_SIGNAL_LEN.toDouble()
            }
            if (hitCount == 0 && queryTerms.isNotEmpty()) {
                // 术语弱命中：只在完整子串一个都没命中时才启用，避免把精确命中搅浑。
                val skillTerms = skill.triggers.asSequence()
                    .flatMap { LATIN_TERM.findAll(it.lowercase()).map { match -> match.value } }
                    .filter { it !in LATIN_STOPWORDS }
                    .toSet()
                val overlap = queryTerms.intersect(skillTerms)
                if (overlap.isNotEmpty()) {
                    hitCount = overlap.size
                    // 强到足够进入正文阈值（2.0），但排序上仍排在完整命中之后。
                    specificity = maxOf(specificity, 1.5 + overlap.size * 0.5)
                }
            }
            if (specificity <= 0.0) continue
            val weight = skill.triggerWeight.coerceAtLeast(0.1)
            val score = aggregate * weight
            // 密度封顶 8：触发词少于 8 个的技能不该因为分母小而白拿满分。
            val density = if (hitCount == 0) {
                0.0
            } else {
                hitCount.toDouble() / minOf(skill.triggers.size, 8).coerceAtLeast(1)
            }
            val avgIdf = if (hitCount == 0 || idfSum <= 0.0) 0.0 else idfSum / hitCount
            // 纯术语弱命中（idfSum 为 0）给固定弱分，避免除零后 rank 归零、排在最后。
            val base = when {
                hitCount == 0 -> EXT_ONLY_RANK
                idfSum <= 0.0 -> 0.4 * hitCount
                else -> avgIdf * (0.6 + 0.4 * density)
            }
            val biased = if (reverseBias && skill.id in reverseSkillIds) base * REVERSE_BIAS else base
            hits += Scored(skill, specificity, score, biased * weight, hitCount)
        }
        // 命中数优先：真实数据里每个技能通常只命中 1–2 个词，
        // "命中 2 个" 就是比 "命中 1 个" 更相关，不该被某个长词的 specificity 压过去。
        return hits
            .sortedWith(
                compareByDescending<Scored> { it.hitCount }
                    .thenByDescending { it.specificity }
                    .thenByDescending { it.rank },
            )
            .take(maxResults)
    }

    /** 从打分结果里按同族去冗余挑选正文（供阈值筛选后的加载使用）。 */
    fun selectBodies(scored: List<Scored>, maxMatches: Int): List<SkillIndexEntry> =
        selectWithFamilyDiversity(scored.map { it.skill }, maxMatches)

    private fun familyOf(id: String): String? =
        id.substringBefore('-', "").takeIf { it in FAMILY_PREFIXES }

    /** 触发词里是否含 CJK 字符——决定是否启用去空白匹配。 */
    private fun containsCjk(text: String): Boolean = text.any { it in '\u4e00'..'\u9fff' }

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
