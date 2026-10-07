package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TexMathDelimitersTest {
    @Test
    fun inlineParenthesesKeepLatexSourceIntact() {
        val paragraph = blocks("行内 \\(a_i + b_j = \\frac{1}{2}\\) 结束").single() as MarkdownParagraph

        val formula = "a_i + b_j = \\frac{1}{2}"
        assertTrue(paragraph.text.text.contains(formula))
        assertTrue(paragraph.text.spanStyles.any { range ->
            range.item.fontFamily == FontFamily.Monospace &&
                paragraph.text.text.substring(range.start, range.end).contains(formula)
        })
    }

    @Test
    fun displayBracketsBecomeMathBlockWithoutLosingEscapes() {
        val math = blocks("\\[\n\\sum_{i=1}^{n} a_i = \\{x \\mid x > 0\\}\n\\]").single() as MarkdownCode

        assertTrue(math.isMath)
        assertEquals("\\sum_{i=1}^{n} a_i = \\{x \\mid x > 0\\}", math.text.text)
    }

    @Test
    fun singleLineDisplayBracketsAreMath() {
        val math = blocks("推导如下：\n\n\\[ E = mc^2 \\]").last() as MarkdownCode
        assertTrue(math.isMath)
        assertEquals("E = mc^2", math.text.text)
    }

    @Test
    fun normalizationPreservesOffsets() {
        val source = "a \\(x\\) b\n\n\\[\ny\n\\]"
        assertEquals(source.length, TexMathDelimiters.normalize(source).length)
    }

    @Test
    fun escapedMarkdownBracketsAndCodeAreNotMath() {
        listOf(
            "数组 arr\\[0\\] 的值",
            "`\\(x\\)` 是行内代码",
            "```latex\n\\[\nx\n\\]\n```",
            "转义反斜杠 \\\\(x\\\\) 不是公式",
            "未闭合 \\(x 后面没有了",
            "跨空行 \\(x\n\ny\\) 不是公式",
        ).forEach { source ->
            assertEquals(source, TexMathDelimiters.normalize(source))
        }
    }

    @Test
    fun unmatchedBacktickDoesNotHideLaterFormula() {
        val normalized = TexMathDelimiters.normalize("单个 ` 反引号，然后 \\(x\\)")
        assertEquals("单个 ` 反引号，然后 \$\$x\$\$", normalized)
    }

    @Test
    fun dollarMathAndCurrencyStayUnchanged() {
        listOf("价格 \$4 和 \$20 每百万", "行内 \$E=mc^2\$ 结束").forEach { source ->
            assertEquals(source, TexMathDelimiters.normalize(source))
        }
        val paragraph = blocks("价格 \$4 和 \$20 每百万").single() as MarkdownParagraph
        assertFalse(paragraph.text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun streamingUnclosedDisplayMathConvertsOnceClosed() {
        val session = StreamingGfmParserSession()
        val partial = session.parse("\\[\n\\frac{a}{b}", isComplete = false).document.blocks
        assertFalse(partial.any { it is MarkdownCode && it.isMath })
        val complete = session.parse("\\[\n\\frac{a}{b}\n\\]", isComplete = true).document.blocks
        assertEquals("\\frac{a}{b}", (complete.single() as MarkdownCode).text.text)
    }

    private fun blocks(source: String): List<MarkdownBlock> =
        StreamingGfmParserSession().parse(source, isComplete = true).document.blocks
}
