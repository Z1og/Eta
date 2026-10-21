package io.github.mangi.eta.core

import android.os.SystemClock

/**
 * 记录"发消息"这条链最近走过的阶段。
 *
 * 卡死类问题事后只能看到"最后一行日志"，看不出它当时卡在哪一步。
 * 这里维护一个很小的环形缓冲（最多 [MAX_ENTRIES] 条），
 * 由 [AnrWatchdog] 在检测到主线程卡顿时整段带进日志——
 * 一眼就能看出是 UI 编码、落盘、跨进程传输、还是 runtime 侧压缩。
 *
 * 只记阶段名，不记内容，开销是一次入队，不做字符串拼接以外的分配。
 */
internal object RunPhaseTrace {

    private const val MAX_ENTRIES = 24

    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val startedAt = SystemClock.elapsedRealtime()

    @Volatile
    private var current: String = "idle"

    fun mark(phase: String) {
        current = phase
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast("+${elapsed}ms $phase")
        }
    }

    /** 带体积信息的阶段：用于确认"耗时是否随历史增长"。 */
    fun markSized(phase: String, chars: Int) {
        mark("$phase(${chars / 1024}KB)")
    }

    fun describe(): String {
        val snapshot = synchronized(lock) { entries.toList() }
        return buildString {
            append("current=").append(current).append('\n')
            append("recent: ").append(snapshot.joinToString(" -> "))
        }
    }
}
