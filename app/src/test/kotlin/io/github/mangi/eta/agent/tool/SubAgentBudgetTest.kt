package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubAgentBudgetTest {
    @Test
    fun shortSinglePurposePromptsGetTheSmallestBudget() {
        assertEquals(
            SubAgentComplexity.Simple,
            SubAgentBudget.estimate("读出 build.gradle 里的 compileSdk"),
        )
        assertEquals(6, SubAgentBudget.roundsFor("读一下 a.txt", declared = null))
    }

    @Test
    fun mediumPromptsLandInTheMiddle() {
        val prompt = "检查 app/src/main 下所有 Kotlin 文件的 import 顺序是否一致，" +
            "把不一致的列出来但不要改。".repeat(12)
        assertEquals(SubAgentComplexity.Normal, SubAgentBudget.estimate(prompt))
        assertEquals(12, SubAgentBudget.roundsFor(prompt, declared = null))
    }

    @Test
    fun longOrMultiSignalPromptsGetTheLargestBudget() {
        // 长度本身足够长
        assertEquals(
            SubAgentComplexity.Complex,
            SubAgentBudget.estimate("把这件事做详细：".repeat(200)),
        )
        // 短但命中两个以上复杂信号
        assertEquals(
            SubAgentComplexity.Complex,
            SubAgentBudget.estimate("逐一对比三个模块，并分别验证它们的入口"),
        )
        assertEquals(24, SubAgentBudget.roundsFor("逐一对比并分别验证", declared = null))
    }

    @Test
    fun explicitDeclarationAlwaysWins() {
        // 模型自己知道细节，声明优先于启发式——但只作为覆盖项，默认仍由估算兜底。
        assertEquals(
            6,
            SubAgentBudget.roundsFor("很长很长的任务说明".repeat(200), SubAgentComplexity.Simple),
        )
        assertEquals(
            24,
            SubAgentBudget.roundsFor("一句话", SubAgentComplexity.Complex),
        )
    }

    @Test
    fun wireValuesRoundTripForTheSchemaEnum() {
        SubAgentComplexity.entries.forEach { complexity ->
            assertEquals(complexity, SubAgentComplexity.fromWire(complexity.wireValue))
        }
        assertEquals(SubAgentComplexity.Complex, SubAgentComplexity.fromWire(" COMPLEX "))
        assertNull(SubAgentComplexity.fromWire("unknown"))
        assertNull(SubAgentComplexity.fromWire(null))
    }
}
