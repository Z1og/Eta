package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJournalBoundsTest {

    @Test
    fun journalUnderBudgetIsReturnedUntouched() {
        val journal = (1..10).map { ConversationMessage("user", "m$it", messageId = "u$it") }
        assertSame(journal, boundedJournal(journal, maxChars = 10_000))
    }

    @Test
    fun oversizedJournalKeepsTheTail() {
        val journal = (1..200).map {
            ConversationMessage("user", "x".repeat(1_000), messageId = "u$it")
        }
        val bounded = boundedJournal(journal, maxChars = 20_000)
        assertTrue("应显著变短", bounded.size < journal.size)
        assertEquals("必须保留最新的那条", journal.last().messageId, bounded.last().messageId)
        assertTrue("不得改动尾部之外的顺序", bounded.map { it.messageId } == journal.takeLast(bounded.size).map { it.messageId })
    }

    @Test
    fun tinyBudgetStillKeepsTheMinimumEntries() {
        val journal = (1..200).map {
            ConversationMessage("user", "x".repeat(50_000), messageId = "u$it")
        }
        val bounded = boundedJournal(journal, maxChars = 1)
        assertTrue("至少保留一段可继续的上下文", bounded.size >= 40)
        assertEquals(journal.last().messageId, bounded.last().messageId)
    }

    @Test
    fun emptyJournalStaysEmpty() {
        assertEquals(0, boundedJournal(emptyList(), maxChars = 10).size)
    }
}
