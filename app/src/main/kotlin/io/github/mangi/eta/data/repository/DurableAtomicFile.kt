package io.github.mangi.eta.data.repository

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable replacement compatible with Android and JVM test hosts (including Windows).
 * Callers must hold their existing cross-process lock for read/modify/write transactions.
 */
internal class DurableAtomicFile(val baseFile: File) {
    private val staged = File(baseFile.parentFile, baseFile.name + ".new")
    private val legacyBackup = File(baseFile.parentFile, baseFile.name + ".bak")

    fun exists(): Boolean = baseFile.exists() || legacyBackup.exists()

    fun openRead(): FileInputStream {
        if (legacyBackup.exists()) {
            Files.move(legacyBackup.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // An interrupted write never replaces the last committed base file.
        if (staged.exists()) staged.delete()
        return FileInputStream(baseFile)
    }

    fun startWrite(): FileOutputStream {
        val directory = requireNotNull(baseFile.parentFile) { "Storage path has no parent directory" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create storage directory" }
        if (legacyBackup.exists()) {
            Files.move(legacyBackup.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return FileOutputStream(staged, false)
    }

    fun finishWrite(output: FileOutputStream) {
        output.fd.sync()
        output.close()
        try {
            Files.move(staged.toPath(), baseFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            // Filesystems without atomic rename still get a fully flushed staged file.
            Files.move(staged.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        require(!staged.exists()) { "Storage file replacement incomplete" }
    }

    fun failWrite(output: FileOutputStream) {
        runCatching { output.close() }
        runCatching { staged.delete() }
    }

    fun delete() {
        if (baseFile.exists() && !baseFile.delete()) throw IOException("Failed to remove storage file")
        if (staged.exists() && !staged.delete()) throw IOException("Failed to remove staged file")
    }
}
