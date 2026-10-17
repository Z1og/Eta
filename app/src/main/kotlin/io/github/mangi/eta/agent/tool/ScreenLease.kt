package io.github.mangi.eta.agent.tool

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 租约超时：拿不到屏幕控制权时的明确失败，调用方据此改走非屏幕路径。 */
internal class ScreenBusyException(val holder: String?) :
    IllegalStateException("屏幕正被另一个 agent 使用")

/**
 * 屏幕是进程级的独占资源：同一时刻只能有一个 agent 在观察或操作它。
 *
 * 两个 agent 交替 observe / tap 会让彼此的 observation_id 立刻失效，
 * 表现出来的现象是"点错了地方"，比直接报错更难查。所以这里必须真互斥。
 *
 * 三个刻意的设计：
 * 1. **同一个 agent 可重入**——它自己的一串动作不能被自己卡住。
 * 2. **粘性保持**——释放后短时间内仍保留 owner，避免两个 agent 高频交替插进对方的操作序列；
 *    超过窗口就允许别人抢，不会把屏幕永久锁死在一个已经停手的 agent 上。
 * 3. **超时必失败**——宁可回 `SCREEN_BUSY` 让模型换路子，也不让它无限等。
 */
internal object ScreenLease {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()

    private var owner: String? = null
    private var depth = 0
    private var lastReleaseAt = 0L

    /** 默认等多久；超过就认输，交给模型换方案。 */
    const val DEFAULT_TIMEOUT_MS = 45_000L

    /** 释放后仍视为"它在用"的窗口。 */
    const val STICKY_MS = 20_000L

    fun <T> withLease(
        agentId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: () -> T,
    ): T {
        acquire(agentId, timeoutMs)
        try {
            return block()
        } finally {
            release(agentId)
        }
    }

    private fun acquire(agentId: String, timeoutMs: Long) {
        lock.withLock {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                val current = owner
                val idleFor = System.currentTimeMillis() - lastReleaseAt
                when {
                    current == agentId -> {
                        depth++
                        return
                    }
                    // 只有"没人用"或"别人已经收手超过粘性窗口"才允许抢；
                    // depth > 0 表示它正在用，别人必须等——否则粘性窗口形同虚设。
                    current == null || (depth == 0 && idleFor >= STICKY_MS) -> {
                        owner = agentId
                        depth = 1
                        return
                    }
                    else -> {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) throw ScreenBusyException(current)
                        available.awaitNanos(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(250)))
                    }
                }
            }
        }
    }

    private fun release(agentId: String) {
        lock.withLock {
            if (owner != agentId) return
            depth = (depth - 1).coerceAtLeast(0)
            if (depth == 0) {
                // 不清 owner：短窗口内仍算它持有，避免别人插进它下一串动作中间。
                lastReleaseAt = System.currentTimeMillis()
                available.signalAll()
            }
        }
    }

    /** run 结束或取消时调用，确保不会把屏幕锁死在一个已经死掉的 agent 上。 */
    fun releaseAll(agentId: String) {
        lock.withLock {
            if (owner != agentId) return
            owner = null
            depth = 0
            lastReleaseAt = System.currentTimeMillis()
            available.signalAll()
        }
    }

    /** 当前持有者，仅用于诊断与提示文案。 */
    fun currentOwner(): String? = lock.withLock { owner }
}

/** 哪些工具会动屏幕或依赖屏幕状态：这些必须持租约。 */
internal object ScreenToolPolicy {
    private val SCREEN_TOOLS = setOf(
        "observe_screen", "tap", "tap_area", "tap_element",
        "long_press", "long_press_element", "swipe", "scroll", "scroll_element",
        "type_text", "input_text", "replace_text", "clear_text",
        "set_clipboard", "get_clipboard", "paste_text", "press_key",
        "wait_for_text", "wait_for_package", "open_system_panel",
        "launch_app", "open_uri",
    )

    fun isScreenTool(name: String): Boolean = name in SCREEN_TOOLS
}
