package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.memory.AgentExperienceEvidence
import io.github.mangi.eta.agent.memory.AgentExperienceTrace
import io.github.mangi.eta.agent.memory.AgentExperienceVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentExperienceStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun storesOnlyControlledTagsAndObservedTools() {
        val store = AgentExperienceStore(folder.newFolder())
        val evidence = AgentExperienceEvidence(
            toolNames = listOf("observe_screen", "tap_element"),
            errorCodes = listOf("ACTION_OUTCOME_UNKNOWN"),
        )
        assertTrue(store.record("打开微信给张三发送密码123456", evidence, true))
        val retrieved = store.recall("微信怎么打开")
        assertTrue(retrieved.orEmpty().contains("observe_screen"))
        assertTrue(retrieved.orEmpty().contains("ACTION_OUTCOME_UNKNOWN"))
        assertFalse(retrieved.orEmpty().contains("张三"))
        assertFalse(retrieved.orEmpty().contains("123456"))
        assertEquals(1, store.count())
        store.clear()
        assertEquals(0, store.count())
        assertNull(store.recall("打开微信"))
    }

    @Test fun ignoresUnrelatedPromptsAndUnexecutedTasks() {
        val store = AgentExperienceStore(folder.newFolder())
        val evidence = AgentExperienceEvidence(listOf("tap_element"), emptyList())
        assertFalse(store.record("你好", evidence, true))
        assertFalse(store.record("打开微信", AgentExperienceEvidence(emptyList(), emptyList()), true))
        assertTrue(store.record("打开微信", evidence, true))
        assertNull(store.recall("打开日历"))
    }

    @Test fun traceRejectsSensitiveToolResultsAndKeepsErrorCodesOnly() {
        val trace = AgentExperienceTrace()
        trace.observe("memory_write", """{"ok":true,"content":"secret"}""")
        trace.observe("tap_element", """{"ok":false,"code":"STALE_OBSERVATION","message":"secret"}""")
        trace.observe("type_text", """{"ok":true,"text":"password"}""")
        val snapshot = trace.snapshot()
        assertEquals(listOf("tap_element", "type_text"), snapshot.toolNames)
        assertEquals(listOf("STALE_OBSERVATION"), snapshot.errorCodes)
    }

    @Test fun tagsAreDeterministicAndCannotStoreUnknownWords() {
        val tags = AgentExperienceVocabulary.describe("微信搜索绝密公司机密文件")
        assertTrue(tags.contains("微信"))
        assertFalse(tags.any { it.contains("绝密") || it.contains("机密") })
    }
}
