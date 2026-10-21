package io.github.mangi.eta.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 Eta 自己的日志同时落到私有文件里，方便没有 adb 时直接导出。
 *
 * 写入在独立线程完成：[append] 只做入队，队列满时丢行并计数，绝不阻塞调用方，
 * 因此热路径（流式输出、压缩、落盘）不会因为写日志被拖慢。
 */
internal object FileLogSink {

    private const val CURRENT_NAME = "eta.log"
    private const val PREVIOUS_NAME = "eta.log.1"
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val QUEUE_CAPACITY = 8_000
    private const val IDLE_FLUSH_MS = 500L
    private const val MAX_SNAPSHOT_BYTES = 2_000_000L

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private val dropped = AtomicInteger()
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var current: File? = null

    @Volatile
    private var previous: File? = null

    @Volatile
    private var started = false

    @Volatile
    private var lastDropReport = 0L

    fun start(dir: File) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            runCatching {
                dir.mkdirs()
                val currentFile = File(dir, CURRENT_NAME)
                val previousFile = File(dir, PREVIOUS_NAME)
                current = currentFile
                previous = previousFile
                writer = openWriter(currentFile, append = true)
            }.onFailure {
                started = false
                android.util.Log.w(ModuleConfig.TAG, "file log sink unavailable: ${it.javaClass.simpleName}")
                return
            }
            val thread = Thread(::drain, "eta-log-sink")
            thread.isDaemon = true
            thread.start()
        }
    }

    fun append(level: String, message: String, throwable: Throwable? = null) {
        if (!started) return
        val line = buildString {
            append(formatNow())
            append(' ').append(android.os.Process.myPid()).append(':').append(Thread.currentThread().name)
            append(' ').append(level).append(' ').append(message)
            if (throwable != null) {
                append('\n').append(android.util.Log.getStackTraceString(throwable))
            }
        }
        if (!queue.offer(line)) {
            val total = dropped.incrementAndGet()
            if (total == 1 || total % 200 == 0) {
                android.util.Log.w(ModuleConfig.TAG, "file log sink dropped $total lines")
            }
        }
    }

    /** 最近日志的文本快照，用于导出；超大时只保留尾部。 */
    fun snapshot(): String {
        val files = files()
        val text = StringBuilder()
        var remaining = MAX_SNAPSHOT_BYTES
        for (file in files) {
            if (!file.exists()) continue
            val content = runCatching { file.readText() }.getOrNull() ?: continue
            val slice = if (content.length <= remaining) content else content.takeLast(remaining.toInt())
            remaining -= slice.length.toLong()
            if (slice.isNotEmpty()) text.append(slice)
        }
        if (dropped.get() > 0) {
            text.append("\n[dropped ").append(dropped.get()).append(" lines]\n")
        }
        return text.toString()
    }

    /** 旧文件在前、当前文件在后。 */
    fun files(): List<File> = listOfNotNull(previous, current)

    /** 仅测试用：复位到未启动状态，避免单例跨用例互相污染。 */
    internal fun resetForTest() {
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
            current = null
            previous = null
            started = false
            queue.clear()
            dropped.set(0)
        }
    }

    fun clear() {
        queue.clear()
        runCatching { current?.delete() }
        runCatching { previous?.delete() }
    }

    private fun drain() {
        while (true) {
            val line = try {
                queue.poll(IDLE_FLUSH_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
            if (line != null) {
                write(line)
                // 一次性把积压排空，避免高频日志下反复走超时分支。
                while (true) {
                    write(queue.poll() ?: break)
                }
            }
            flush()
        }
    }

    private fun write(line: String) {
        val target = writer ?: return
        runCatching {
            target.write(line)
            target.write('\n'.code)
        }.onFailure {
            writer = null
            return
        }
        val file = current
        if (file != null && file.length() >= MAX_FILE_BYTES) rotate()
    }

    private fun flush() {
        runCatching { writer?.flush() }
        val total = dropped.getAndSet(0)
        if (total > 0) {
            val now = System.currentTimeMillis()
            if (now - lastDropReport > 30_000L) {
                lastDropReport = now
                runCatching {
                    writer?.write("${formatNow()} dropped $total log lines due to backpressure\n")
                }
            }
        }
    }

    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        val currentFile = current ?: return
        val previousFile = previous
        runCatching {
            previousFile?.delete()
            if (currentFile.renameTo(File(currentFile.parentFile, PREVIOUS_NAME))) {
                previous = File(currentFile.parentFile, PREVIOUS_NAME)
            }
        }
        current = currentFile
        writer = runCatching { openWriter(currentFile, append = false) }.getOrNull()
    }

    private fun openWriter(file: File, append: Boolean): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, append), Charsets.UTF_8), 16 * 1024)

    private fun formatNow(): String = synchronized(timestampFormat) {
        timestampFormat.format(Date(System.currentTimeMillis()))
    }
}
