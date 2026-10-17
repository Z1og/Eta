package io.github.mangi.eta.agent.tool

/**
 * 子代理的轮次预算。
 *
 * 一轮 = 一次模型请求 + 它返回的那批工具调用执行完。
 *
 * 分档而不是给所有人同一个上限：给简单任务 24 轮是浪费（它早该结束了，
 * 多出来的轮次只会让它反复改同一处），给复杂任务 6 轮又会半路掐断。
 *
 * 复杂度有两个来源，优先级从高到低：
 * 1. 主代理在 `spawn_agents` 里显式声明的 `complexity`；
 * 2. 没声明时按任务说明的长度与信号词估算。
 *
 * 之所以保留显式声明：模型自己知道这条子任务要看几个文件、跑几条命令；
 * 但它可能偷懒一律填 complex，所以只把它当"覆盖项"，默认仍由启发式兜底。
 */
internal enum class SubAgentComplexity(val wireValue: String, val rounds: Int) {
    Simple("simple", 6),
    Normal("normal", 12),
    Complex("complex", 24),
    ;

    companion object {
        fun fromWire(value: String?): SubAgentComplexity? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

internal object SubAgentBudget {
    /** 超过这个长度基本是"要通读/要对照多份材料"的活。 */
    private const val COMPLEX_CHARS = 1_200

    /** 低于这个长度是"一句话问一件事"。 */
    private const val SIMPLE_CHARS = 300

    /** 出现两个以上就按复杂处理：单个词太容易误判（"分别"在中文里到处都是）。 */
    private val COMPLEX_HINTS = listOf(
        "逐一", "分别", "每个", "全部", "遍历", "反编译", "对比", "逐条",
        "重构", "验证", "多个文件", "整个项目", "所有", "批量", "测试覆盖",
    )

    fun complexityOf(prompt: String, declared: SubAgentComplexity?): SubAgentComplexity =
        declared ?: estimate(prompt)

    fun roundsFor(prompt: String, declared: SubAgentComplexity?): Int =
        complexityOf(prompt, declared).rounds

    /** 没有显式声明时的启发式；纯函数，便于单测。 */
    fun estimate(prompt: String): SubAgentComplexity {
        val text = prompt.trim()
        val hits = COMPLEX_HINTS.count { text.contains(it) }
        return when {
            text.length >= COMPLEX_CHARS || hits >= 2 -> SubAgentComplexity.Complex
            text.length >= SIMPLE_CHARS || hits == 1 -> SubAgentComplexity.Normal
            else -> SubAgentComplexity.Simple
        }
    }
}
