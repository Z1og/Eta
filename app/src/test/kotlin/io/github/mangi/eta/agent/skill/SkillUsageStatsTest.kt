package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkillUsageStatsTest {

    @Test
    fun `records and loads read counts`() {
        val dir = Files.createTempDirectory("usage").toFile()
        assertTrue(SkillUsageStats.load(dir).isEmpty())
        SkillUsageStats.recordRead(dir, "apk-reverse")
        SkillUsageStats.recordRead(dir, "apk-reverse")
        SkillUsageStats.recordRead(dir, "ida-reverse")
        val map = SkillUsageStats.load(dir)
        assertEquals(2, map["apk-reverse"])
        assertEquals(1, map["ida-reverse"])
    }

    @Test
    fun `blank id is ignored`() {
        val dir = Files.createTempDirectory("usage").toFile()
        SkillUsageStats.recordRead(dir, "  ")
        assertTrue(SkillUsageStats.load(dir).isEmpty())
    }

    @Test
    fun `boost is bounded between one and two`() {
        assertEquals(1.0, SkillUsageStats.boost(0), 0.0001)
        assertTrue(SkillUsageStats.boost(1) > 1.0)
        assertTrue(SkillUsageStats.boost(1) < 2.0)
        assertTrue(SkillUsageStats.boost(10_000) <= 2.0)
    }
}
