package io.github.mangi.eta.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 [FileLogSink] 落盘的日志导出到「下载/Eta」目录。
 *
 * 走 MediaStore 而不是私有目录：minSdk 33 上不需要任何存储权限，
 * 导出的文件用文件管理器、微信、QQ 都能直接拿到，无需 adb。
 */
internal object LogExporter {

    private val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/Eta"

    /** 返回导出后的可读位置；失败时抛出。 */
    fun export(context: Context): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val displayName = "eta-log-$stamp.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建导出文件")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(header(context).toByteArray(Charsets.UTF_8))
                for (file in FileLogSink.files()) {
                    if (!file.exists()) continue
                    stream.write("\n===== ${file.name} =====\n".toByteArray(Charsets.UTF_8))
                    file.inputStream().use { input -> input.copyTo(stream) }
                }
                stream.flush()
            } ?: error("无法写入导出文件")
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return "$relativeDir/$displayName"
    }

    private fun header(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")
        return buildString {
            append("Eta 运行日志导出\n")
            append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("app=").append(version).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" | android=").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
            append("pid=").append(android.os.Process.myPid()).append('\n')
        }
    }
}
