package io.github.mangi.eta.agent.skill

import androidx.compose.runtime.Immutable

/**
 * 技能索引条目——对应磁盘上一个 [SKILL.md] 文件的元信息。
 */
@Immutable
data class SkillIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val rootPath: String,
    val skillFilePath: String,
    val hasScripts: Boolean,
    val hasReferences: Boolean,
    val hasAssets: Boolean,
    val hasEvals: Boolean,
    val enabled: Boolean = true,
    val source: String = "user",
    val installed: Boolean = true,
    /** 关键词触发列表——用户消息包含任一关键词时自动加载该 Skill 正文。 */
    val triggers: List<String> = emptyList(),
    /**
     * 扩展名触发信号——消息/附件里出现这些扩展名时强命中该技能（不含点、小写，
     * 对应 frontmatter `ext`）。用于 `.apk`/`.so` 这类无关键词但意图明确的逆向场景。
     */
    val extensions: List<String> = emptyList(),
    /**
     * 触发权重——命中得分乘数（对应 frontmatter `trigger_weight`，默认 1.0）。
     * 用于在同类技能中抬高更精准技能、压低泛词技能。
     */
    val triggerWeight: Double = 1.0,
)

/**
 * 已解析的技能上下文——包含 SKILL.md 正文和附属目录路径。
 */
@Immutable
data class ResolvedSkillContext(
    val skillId: String,
    val frontmatter: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val bodyMarkdown: String,
    val loadedReferences: List<String> = emptyList(),
    val scriptsDir: String? = null,
    val assetsDir: String? = null,
    val triggerReason: String,
)

@Immutable
data class SkillCompatibilityResult(
    val available: Boolean,
    val reason: String? = null,
)

/**
 * 单次 Agent 运行中解析出的技能上下文集合。
 */
@Immutable
data class SkillContext(
    val installedSkills: List<SkillIndexEntry> = emptyList(),
    /** 关键词触发后已自动加载正文的 Skill——正文将直接注入 system prompt。 */
    val autoLoadedSkills: List<ResolvedSkillContext> = emptyList(),
    /** 方案 A 探测到的 Trellis 项目规范索引——非空时注入系统消息，提醒模型写码前先读规范。 */
    val projectSpecs: ProjectSpecIndex? = null,
    /** 逆向模式是否开启（融合 dsh-infinite-gen-4 交付契约）——开启时常驻注入逆向交付契约系统消息。 */
    val reverseModeEnabled: Boolean = false,
) {
    companion object {
        val EMPTY = SkillContext()
    }
}

/** SKILL.md 文件解析结果。 */
internal data class ParsedSkillFile(
    val frontmatter: Map<String, String>,
    val body: String,
)
