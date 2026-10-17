package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentGoalStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun goalIsScopedPerConversationAndSurvivesAReopen() {
        val root = temporaryFolder.newFolder()
        val store = AgentGoalStore(root)

        store.save("conv-a", "把排队引导做完")
        store.save("conv-b", "把目标模式做完")

        assertEquals("把排队引导做完", store.goal("conv-a"))
        assertEquals("把目标模式做完", store.goal("conv-b"))
        assertEquals("", store.goal("conv-c"))
        // 重新打开必须读到同一份：目标要在重启后仍然生效，否则"持续"无从谈起。
        assertEquals("把排队引导做完", AgentGoalStore(root).goal("conv-a"))
    }

    @Test
    fun blankGoalDeletesTheFileInsteadOfStoringAnEmptyOne() {
        val root = temporaryFolder.newFolder()
        val store = AgentGoalStore(root)
        store.save("conv-a", "先设一个目标")

        assertEquals("", store.save("conv-a", "   \n  "))

        assertEquals("", store.goal("conv-a"))
        val goalDir = File(root, AgentGoalStore.DIRECTORY_NAME)
        assertFalse("空白目标不该留下文件", goalDir.listFiles().orEmpty().any { it.isFile })
    }

    @Test
    fun oversizedGoalIsTruncatedWithAnExplicitMarkerAndStaysIdempotent() {
        val root = temporaryFolder.newFolder()
        val store = AgentGoalStore(root)
        val raw = "字".repeat(AgentGoalStore.MAX_CHARS + 40)

        val saved = store.save("conv-a", raw)

        assertTrue(saved.startsWith("字".repeat(AgentGoalStore.MAX_CHARS)))
        assertTrue(saved.contains("已截断 40 字符"))
        // 读回来必须是同一份截断结果，否则每次装载都会多带一段标记。
        assertEquals(saved, store.goal("conv-a"))
    }

    @Test
    fun conversationIdCannotEscapeTheGoalDirectory() {
        val root = temporaryFolder.newFolder()
        val store = AgentGoalStore(root)

        store.save("../../outside", "越界测试")

        assertEquals("越界测试", store.goal("../../outside"))
        val goalDir = File(root, AgentGoalStore.DIRECTORY_NAME)
        assertTrue(goalDir.isDirectory)
        assertEquals(1, goalDir.listFiles().orEmpty().count { it.isFile })
        assertFalse(File(root.parentFile, "outside.md").exists())
    }

    @Test
    fun clearingAnUnknownConversationIsANoOp() {
        val store = AgentGoalStore(temporaryFolder.newFolder())
        store.clear("never-saved")
        assertEquals("", store.goal("never-saved"))
    }
}
