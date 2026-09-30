package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 触发精度语料回归：用仓库里真实的 builtin_skills frontmatter 跑 matcher，
 * 校验典型查询的 top-1/top-2 命中预期技能——作为触发词与阈值调优的基线。
 * 在 CI 上执行（本地 Windows OneDrive 路径下 JVM 测试加载受限）。
 */
class SkillTriggerCorpusTest {

    private fun skillsRoot(): File {
        val candidates = listOf(
            File("src/main/assets/builtin_skills"),
            File("app/src/main/assets/builtin_skills"),
            File("../app/src/main/assets/builtin_skills"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("builtin_skills assets not found from ${File("").absolutePath}")
    }

    private fun parseTriggers(skillFile: File): List<String> {
        val text = skillFile.readText()
        if (!text.startsWith("---")) return emptyList()
        val fm = text.split("---", limit = 3).getOrElse(1) { return emptyList() }
        val line = fm.lineSequence().firstOrNull { it.trimStart().startsWith("triggers:") } ?: return emptyList()
        return line.substringAfter("triggers:")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun loadCorpus(): List<SkillIndexEntry> {
        val root = skillsRoot()
        return root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.isFile) return@mapNotNull null
            SkillIndexEntry(
                id = dir.name,
                name = dir.name,
                description = "",
                rootPath = dir.absolutePath,
                skillFilePath = skillFile.absolutePath,
                hasScripts = false,
                hasReferences = false,
                hasAssets = false,
                hasEvals = false,
                triggers = parseTriggers(skillFile),
            )
        }
    }

    private val cases = listOf(
        "帮我反编译这个 apk 看看" to "apk-reverse",
        "用 frida hook 一下这个方法" to "dynamic-instrumentation",
        "ETE trace 抓一下执行流" to "hw-trace",
        "il2cpp 游戏还原类结构" to "il2cpp-dump",
        "它的 frida 检测太严了怎么绕" to "anti-frida-bypass",
        "抓一下这个 App 的 https 流量" to "capture-mitm",
        "挖一下某某集团的 SRC 漏洞" to "src-hunt",
        "用 ida 分析这个 so" to "ida-reverse",
        "抓包看看接口" to "capture-mitm",
    )

    @Test
    fun `curated queries hit expected skills in top2`() {
        val corpus = loadCorpus()
        assertTrue("corpus too small: ${corpus.size}", corpus.size > 100)
        val misses = mutableListOf<String>()
        for ((query, expected) in cases) {
            val hits = SkillTriggerMatcher.match(query, corpus, maxMatches = 2).map { it.id }
            if (expected !in hits) {
                misses += "$query → expected=$expected hits=$hits"
            }
        }
        assertEquals("corpus misses:\n" + misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun `generic chatter triggers nothing or only strong hits`() {
        val corpus = loadCorpus()
        val hits = SkillTriggerMatcher.match("今天天气真不错", corpus, maxMatches = 4)
        assertTrue("casual text should not auto-load: ${hits.map { it.id }}", hits.isEmpty())
    }
}
