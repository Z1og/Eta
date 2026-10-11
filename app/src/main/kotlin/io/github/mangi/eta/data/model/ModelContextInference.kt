package io.github.mangi.eta.data.model

/**
 * 同一 provider 内的**模型族窗口继承**。
 *
 * 目录元数据靠模型名精确匹配，中转端点的别名与日期后缀常常对不上，于是窗口"未知"。
 * 同族兄弟（`gpt-4o-mini-2024-07-18` 与 `gpt-4o-mini`、`deepseek-v3-0324` 与 `deepseek-v3`）
 * 共享同一窗口上限，这里在**自身窗口未知**时按族继承，并取族内最小值（保守，宁小勿大）。
 */
internal object ModelContextInference {

    private val VENDOR_PREFIX = Regex("""^[a-z0-9_-]+/""")
    private val TAG_SUFFIX = Regex("""[:@].*$""")
    private val VERSION_SUFFIX = Regex(
        """-(?:\d{8}|\d{6}|\d{4}-\d{2}-\d{2}|latest|v\d+(?:\.\d+)?)$""",
        RegexOption.IGNORE_CASE,
    )

    /** 归一化：去厂商前缀（`anthropic/…`）、`:free`/`@版本` 标签与空白。 */
    fun normalize(modelId: String): String =
        modelId.trim().lowercase()
            .replace(VENDOR_PREFIX, "")
            .replace(TAG_SUFFIX, "")
            .replace(Regex("""\s+"""), "")

    /** 族名：在归一化基础上剥掉日期/版本后缀（`…-2024-07-18` → `…`）。 */
    fun family(modelId: String): String {
        var current = normalize(modelId)
        while (true) {
            val stripped = current.replace(VERSION_SUFFIX, "")
            if (stripped == current || stripped.isBlank()) break
            current = stripped
        }
        return current
    }

    /** 自身窗口未知时从同族兄弟继承；取候选最小值。模型不在列表中则不推断。 */
    fun infer(siblings: List<Model>, model: Model): Int? {
        if (siblings.none { it.id == model.id }) return null
        val target = family(model.modelId)
        if (target.isBlank()) return null
        return siblings.asSequence()
            .filter { it.id != model.id }
            .filter { candidate ->
                val other = family(candidate.modelId)
                other == target || target.startsWith("$other-") || other.startsWith("$target-")
            }
            .mapNotNull { it.contextWindowOverride ?: it.contextWindow }
            .filter { it > 0 }
            .minOrNull()
    }
}
