package io.github.mangi.eta.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileLogSinkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appendedLinesReachTheLogFile() {
        FileLogSink.resetForTest()
        val dir = temporaryFolder.newFolder("logs")
        FileLogSink.start(dir)
        try {
            FileLogSink.append("I", "diagnostic line one")
            FileLogSink.append("E", "diagnostic line two")
            val file = File(dir, "eta.log")
            val content = awaitFileContent(file) { text ->
                text.contains("diagnostic line one") && text.contains("diagnostic line two")
            }
            assertTrue("content=[$content]", content.contains("I diagnostic line one"))
            assertTrue("content=[$content]", content.contains("E diagnostic line two"))
            assertTrue("snapshot=[${FileLogSink.snapshot()}]", FileLogSink.snapshot().contains("diagnostic line two"))
            assertEquals(listOf(file), FileLogSink.files().filter { it.exists() })
        } finally {
            FileLogSink.resetForTest()
        }
    }

    @Test
    fun appendNeverBlocksWhenSinkIsNotStarted() {
        // 未启动时（非主进程 / 初始化失败）只允许静默丢弃，不能抛异常影响业务。
        FileLogSink.resetForTest()
        repeat(1_000) { index -> FileLogSink.append("D", "dropped $index") }
    }

    private fun awaitFileContent(file: File, ready: (String) -> Boolean): String {
        var content = ""
        repeat(100) {
            content = runCatching { file.readText() }.getOrDefault("")
            if (ready(content)) return content
            Thread.sleep(50)
        }
        return content
    }
}
