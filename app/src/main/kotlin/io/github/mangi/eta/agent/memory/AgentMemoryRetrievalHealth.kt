package io.github.mangi.eta.agent.memory

import android.content.Context
import io.github.mangi.eta.data.repository.DurableAtomicFile
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

/** Persist only counters/status for the Settings diagnostics. Never persist queries or model output. */
internal data class AgentMemoryRetrievalReport(
    val mode: String,
    val candidateCount: Int,
    val hitCount: Int,
    val coreChunks: Int,
    val retainedCount: Int,
    val detail: String,
    val recordedAt: Long = System.currentTimeMillis(),
) {
    fun shortSummary(): String {
        val label = when (mode) {
            "chat" -> "已使用当前聊天模型"
            "embedding" -> "已使用 Embeddings + 聊天模型"
            "fallback" -> "聊天模型检索失败，已退回本地检索"
            "none" -> "无可检索内容"
            "disabled" -> "语义检索已关闭"
            else -> "待验证"
        }
        return "$label；候选 $candidateCount 条，命中 $hitCount 条，MEMORY.md 片段 $coreChunks 条，结构化 $retainedCount 条" +
            if (detail.isBlank()) "" else "；$detail"
    }
}

internal class AgentMemoryRetrievalHealthStore(rootDir: File) {
    private val folder = File(rootDir, "memory")
    private val file = DurableAtomicFile(File(folder, "retrieval_diagnostics.json"))
    private val lockFile = File(folder, "retrieval_diagnostics.lock")

    fun write(report: AgentMemoryRetrievalReport) = locked {
        val encoded = JSONObject()
            .put("schema", 1).put("mode", report.mode.take(20))
            .put("candidates", report.candidateCount)
            .put("hits", report.hitCount)
            .put("core_chunks", report.coreChunks)
            .put("retained_count", report.retainedCount)
            .put("detail", report.detail.take(140))
            .put("recorded_at", report.recordedAt).toString()
        val out = file.startWrite()
        try {
            out.write(encoded.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (failure: Throwable) {
            file.failWrite(out)
            throw failure
        }
    }

    fun read(): AgentMemoryRetrievalReport? = locked {
        if (!file.exists()) return@locked null
        val data = file.openRead().use { it.readBytes() }
        require(data.size <= 4096) { "Invalid retrieval diagnostics" }
        val item = JSONObject(data.toString(Charsets.UTF_8))
        if (item.optInt("schema") != 1) return@locked null
        AgentMemoryRetrievalReport(
            mode = item.optString("mode"),
            candidateCount = item.optInt("candidates"),
            hitCount = item.optInt("hits"),
            coreChunks = item.optInt("core_chunks"),
            retainedCount = item.optInt("retained_count"),
            detail = item.optString("detail"),
            recordedAt = item.optLong("recorded_at"),
        )
    }

    private fun <T> locked(action: () -> T): T {
        check(folder.isDirectory || folder.mkdirs())
        return RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val lock = channel.lock()
            try { action() } finally { lock.release() }
        }
    }
}
