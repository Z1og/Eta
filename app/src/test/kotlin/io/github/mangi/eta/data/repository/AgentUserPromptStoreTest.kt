package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentUserPromptStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingEmptyOrReadmeOnlyDirectoryNeverProducesInjection() {
        val root = temporaryFolder.newFolder()
        val store = AgentUserPromptStore(root)

        assertFalse(store.snapshot().available)

        val prompts = File(root, AgentUserPromptStore.DIRECTORY_NAME).also { it.mkdirs() }
        File(prompts, AgentUserPromptStore.IGNORED_FILE).writeText("只读说明，不是协议")
        File(prompts, "空文件.md").writeText("   \n  ")
        File(prompts, "非md.txt").writeText("扩展名不符")

        val snapshot = store.snapshot()
        assertFalse(snapshot.available)
        assertEquals(null, snapshot.fileName)
    }

    @Test
    fun picksFirstNonEmptyMarkdownBySortedNameAndSkipsReadme() {
        val root = temporaryFolder.newFolder()
        val prompts = File(root, AgentUserPromptStore.DIRECTORY_NAME).also { it.mkdirs() }
        File(prompts, AgentUserPromptStore.IGNORED_FILE).writeText("README 正文不能被当作协议")
        File(prompts, "b-第二.md").writeText("第二份")
        File(prompts, "a-第一.md").writeText("第一份")

        val snapshot = AgentUserPromptStore(root).snapshot()

        assertTrue(snapshot.available)
        assertEquals("a-第一.md", snapshot.fileName)
        assertEquals("第一份", snapshot.content)
        assertEquals(3, snapshot.charCount)
    }

    @Test
    fun oversizedContentIsTruncatedWithExplicitMarkerButReportsRawLength() {
        val root = temporaryFolder.newFolder()
        val store = AgentUserPromptStore(root)
        val raw = "字".repeat(AgentUserPromptStore.MAX_CHARS + 500)

        val snapshot = store.replaceAll(raw)

        assertTrue(snapshot.truncated)
        assertEquals(raw.length, snapshot.charCount)
        assertTrue(snapshot.content.startsWith("字".repeat(AgentUserPromptStore.MAX_CHARS)))
        assertTrue(snapshot.content.contains("已截断 500 字符"))
    }

    @Test
    fun oversizedPayloadIsRejectedAtomicallyAndOldFileSurvives() {
        val root = temporaryFolder.newFolder()
        val store = AgentUserPromptStore(root)
        val original = store.replaceAll("原有协议")

        val failure = assertThrows(AgentUserPromptException::class.java) {
            store.replaceAll("a".repeat(AgentUserPromptStore.MAX_FILE_BYTES + 1))
        }

        assertEquals("PROMPT_TOO_LARGE", failure.code)
        assertEquals(original, store.snapshot())
    }

    @Test
    fun clearActiveBacksUpElsewhereBeforeRemovingTheActiveFile() {
        val root = temporaryFolder.newFolder()
        val store = AgentUserPromptStore(root)
        store.replaceAll("待清空的协议")

        val cleared = store.clearActive()

        assertFalse(cleared.available)
        val backups = File(File(root, AgentUserPromptStore.DIRECTORY_NAME), ".backup").listFiles()
        assertNotNull(backups)
        assertEquals(1, backups!!.size)
        assertEquals("待清空的协议", backups.single().readText())
    }

    @Test
    fun persistedModeValuesRoundTripAndActivationFollowsReverseFlag() {
        assertEquals(
            AgentUserPromptMode.Off,
            AgentUserPromptMode.fromPersistedValue(null),
        )
        assertEquals(
            AgentUserPromptMode.Off,
            AgentUserPromptMode.fromPersistedValue("未知值"),
        )
        AgentUserPromptMode.values().forEach { mode ->
            assertEquals(mode, AgentUserPromptMode.fromPersistedValue(mode.persistedValue))
        }

        assertFalse(AgentUserPromptRepository.isActive(AgentUserPromptMode.Off, reverseModeEnabled = false))
        assertFalse(AgentUserPromptRepository.isActive(AgentUserPromptMode.Off, reverseModeEnabled = true))
        assertTrue(AgentUserPromptRepository.isActive(AgentUserPromptMode.Global, reverseModeEnabled = false))
        assertFalse(
            AgentUserPromptRepository.isActive(AgentUserPromptMode.ReverseOnly, reverseModeEnabled = false),
        )
        assertTrue(
            AgentUserPromptRepository.isActive(AgentUserPromptMode.ReverseOnly, reverseModeEnabled = true),
        )
    }
}
