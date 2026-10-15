package io.github.mangi.eta.agent.model

/**
 * 模型**口头拒绝**的识别与失败封装。
 *
 * 目标：把「模型拒绝配合」从正常回复中区分出来——拒绝内容一旦写入上下文，
 * 后续轮次会被这句「我不能…」带偏（模型倾向延续自己此前的拒绝姿态），
 * 因此拒绝与 CONTENT_FILTER 同权处理：不入上下文，并给上层 failover 一个确定信号。
 *
 * 原则：**宁漏勿误**——只认开场白里的明确拒绝措辞；正文中间的「无法找到进程」
 * 之类的工程性表述不匹配。识别不了的交给用户，绝不误杀正常回复。
 */
internal object AgentModelRefusal {

    const val FAILURE_CODE = "MODEL_REFUSAL"

    /** 只在开场窗口内匹配：拒绝几乎总是出现在回复开头。 */
    private const val OPENING_WINDOW = 240

    private val OPENING_PATTERNS = listOf(
        // 中文
        Regex("""我不能(?:帮|协助|提供|配合|参与)"""),
        Regex("""我无法(?:帮|协助|提供|完成|执行|配合)"""),
        Regex("""不能帮(?:助)?你|无法(?:协助|帮助)你?"""),
        Regex("""我做不到|我不会(?:帮|协助)你?"""),
        Regex("""违反(?:我的|相关)?(?:政策|原则|准则|法律|规定)"""),
        Regex("""(?:出于|基于)(?:政策|安全|合规|伦理|法律)"""),
        Regex("""作为(?:一个)?(?:AI|ai|人工智能)""", RegexOption.IGNORE_CASE),
        Regex("""我是一个语言模型|我的(?:设计|编程)(?:目的|不允许)"""),
        Regex("""不(?:能|便|适合)提供(?:这方?面|此类|相关)"""),
        // 英文
        Regex("""i\s+(?:can'?t|cannot|won'?t|will not|am unable to|'m unable to)\s+(?:help|assist|provide|comply|fulfill|perform|support)""", RegexOption.IGNORE_CASE),
        Regex("""i\s+must\s+(?:decline|refuse)""", RegexOption.IGNORE_CASE),
        Regex("""against\s+my\s+(?:principles|policies|guidelines|programming)""", RegexOption.IGNORE_CASE),
        Regex("""as\s+an\s+ai\b""", RegexOption.IGNORE_CASE),
        Regex("""i'?m\s+sorry,?\s*(?:but\s+)?i\s+(?:can'?t|cannot|won'?t)""", RegexOption.IGNORE_CASE),
        Regex("""cannot\s+provide\s+(?:guidance|assistance|instructions)""", RegexOption.IGNORE_CASE),
    )

    /** 是否为开场拒绝。空文本与正常工程回复（含「无法找到进程」类表述）不误判。 */
    fun isRefusal(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        val opening = trimmed.take(OPENING_WINDOW)
        return OPENING_PATTERNS.any { it.containsMatchIn(opening) }
    }

    fun failure(content: String): AgentModelFailure = AgentModelFailure(
        code = FAILURE_CODE,
        retryable = false,
        message = buildString {
            append("模型拒绝了该请求，拒绝内容未写入上下文。")
            val excerpt = content.trim().take(120)
            if (excerpt.isNotEmpty()) append("原话：").append(excerpt)
            append("可重述请求、换用其他模型，或稍后再试。")
        },
    )

    /**
     * 跳过当前子任务时的续跑指令——纯中性工程语言，不重述、不施压被拒的那一步，
     * 只让既定计划继续往下推进；计划已列在上下文里，模型据此执行下一项即可。
     */
    fun continuationNote(): String = buildString {
        append("上一个子任务本轮未执行（模型策略拒绝），已跳过该步骤。")
        append("请继续执行既定计划中的下一项子任务，只输出具体的工程动作、命令与执行结果；")
        append("若计划已全部完成，输出当前阶段的结论汇总。")
    }
}
