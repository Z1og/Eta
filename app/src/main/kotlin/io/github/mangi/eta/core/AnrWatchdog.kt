package io.github.mangi.eta.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * 主线程卡顿看门狗。
 *
 * 「继续对话卡死」这类问题发生在主线程，普通日志只能记到卡死前的最后一行，
 * 看不出卡在哪。这里每 [CHECK_INTERVAL_MS] 给主线程投一个心跳，超过 [STALL_THRESHOLD_MS]
 * 没被消费就把全进程线程栈写进日志，导出后可以直接定位阻塞点。
 */
internal object AnrWatchdog {

    private const val CHECK_INTERVAL_MS = 1_000L
    private const val STALL_THRESHOLD_MS = 5_000L
    private const val REPORT_COOLDOWN_MS = 20_000L
    private const val MAX_FRAMES = 40
    private const val MAX_REPORT_CHARS = 24_000

    private val tick = AtomicLong(SystemClock.uptimeMillis())

    @Volatile
    private var lastReportAt = 0L

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        synchronized(this) {
            if (running) return
            running = true
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val thread = Thread({
            tick.set(SystemClock.uptimeMillis())
            while (running) {
                val postedAt = SystemClock.uptimeMillis()
                mainHandler.post { tick.set(SystemClock.uptimeMillis()) }
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val now = SystemClock.uptimeMillis()
                val last = tick.get()
                // last < postedAt：本次心跳还没被主线程取走，说明主线程至少卡了一个检查周期。
                if (last < postedAt && now - last >= STALL_THRESHOLD_MS) {
                    report(now - last)
                }
            }
        }, "eta-anr-watchdog")
        thread.isDaemon = true
        thread.start()
    }

    private fun report(stallMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastReportAt < REPORT_COOLDOWN_MS) return
        lastReportAt = now
        val text = buildString {
            append("主线程卡顿 ").append(stallMs).append("ms | looper=")
            append(runCatching { Looper.getMainLooper().toString() }.getOrDefault("n/a"))
            append('\n')
            append(RunPhaseTrace.describe())
            append('\n')
            append(dumpStacks())
        }
        AndroidAgentLogger.error(text.take(MAX_REPORT_CHARS))
    }

    private fun dumpStacks(): String {
        val traces = runCatching { Thread.getAllStackTraces() }.getOrElse { return "无法获取线程栈: ${it.javaClass.simpleName}\n" }
        val mainThread = Thread.currentThread().let { current ->
            traces.keys.firstOrNull { it == Looper.getMainLooper().thread } ?: current
        }
        val ordered = ArrayList<Thread>(traces.size)
        mainThread?.let { ordered.add(it) }
        traces.keys.forEach { thread -> if (thread !== mainThread) ordered.add(thread) }
        return buildString {
            for (thread in ordered) {
                val stack = traces[thread] ?: continue
                append("---- ").append(thread.name).append(" (tid=").append(thread.id)
                    .append(", state=").append(thread.state.name)
                    .append(", daemon=").append(thread.isDaemon).append(")\n")
                val frames = if (stack.size > MAX_FRAMES) stack.copyOfRange(0, MAX_FRAMES) else stack
                frames.forEach { frame -> append("    at ").append(frame.toString()).append('\n') }
                if (stack.size > MAX_FRAMES) append("    ... ").append(stack.size - MAX_FRAMES).append(" more\n")
            }
        }
    }
}
