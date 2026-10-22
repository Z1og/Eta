package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Arrays
import org.json.JSONObject

internal data class EtaSyncConfig(
    val endpoint: String = "",
    val username: String = "",
    val hasPassword: Boolean = false,
    val hasPassphrase: Boolean = false,
) {
    val configured: Boolean get() = endpoint.isNotBlank() && hasPassphrase
}

internal data class EtaSyncResult(
    val success: Boolean,
    val message: String,
    val uploaded: Boolean = false,
    val knowledgeCount: Int = 0,
    val experienceCount: Int = 0,
    val retainedCount: Int = 0,
    val knowledgeConflicts: Int = 0,
)

/** HTTPS WebDAV + client-side encryption; no dependency on a special sync server. */
internal class EtaWebDavSyncStore(context: Context) {
    private val app = context.applicationContext
    private val options = app.getSharedPreferences("eta_webdav_sync", Context.MODE_PRIVATE)
    private val secrets = McpSecretStore(app)
    private val root = File(app.filesDir, "memory")
    private val syncLockFile = File(root, "webdav-sync.lock")
    private val inProcessLock = Any()

    fun config(): EtaSyncConfig = EtaSyncConfig(
        endpoint = options.getString("endpoint", "").orEmpty(),
        username = options.getString("username", "").orEmpty(),
        hasPassword = !secrets.bearerToken("eta-webdav-password").isNullOrEmpty(),
        hasPassphrase = !secrets.bearerToken("eta-webdav-passphrase").isNullOrEmpty(),
    )

    fun configure(
        endpoint: String, username: String, password: String, passphrase: String,
    ): EtaSyncConfig = synchronized(inProcessLock) {
        val clean = endpoint.trim()
        EtaWebDavTransport.validateEndpoint(clean)
        require(username.length <= 128 && !username.contains(':')) { "Invalid WebDAV username" }
        if (passphrase.isNotBlank()) require(passphrase.length >= 12) {
            "Encryption passphrase must be at least 12 characters"
        }
        if (password.isNotBlank()) secrets.setBearerToken("eta-webdav-password", password)
        if (passphrase.isNotBlank()) secrets.setBearerToken("eta-webdav-passphrase", passphrase)
        require(!secrets.bearerToken("eta-webdav-passphrase").isNullOrBlank()) {
            "Set a sync encryption passphrase first"
        }
        check(options.edit().putString("endpoint", clean).putString("username", username).commit()) {
            "Failed to save WebDAV configuration"
        }
        config()
    }

    fun clearConfig() = synchronized(inProcessLock) {
        check(options.edit().clear().commit())
        secrets.clear("eta-webdav-password")
        secrets.clear("eta-webdav-passphrase")
    }

    fun hasMemoryConflict(): Boolean =
        config().let { it.configured && conflictMarkerFile(it).exists() }

    fun remoteConflictContent(): String? {
        val active = config().takeIf(EtaSyncConfig::configured) ?: return null
        val hash = loadConflictMarker(active) ?: return null
        val file = DurableAtomicFile(
            File(File(root, "conflicts"), "remote-memory-${hash.take(24)}.md"),
        )
        if (!file.exists()) return null
        val bytes = file.openRead().use { stream ->
            val data = stream.readBytes()
            require(data.size <= AgentMemoryStore.MAX_FILE_BYTES) { "Remote memory copy is too large" }
            data
        }
        val content = bytes.toString(Charsets.UTF_8)
        return content.takeIf { AgentKnowledgeStore.sha256(it) == hash }
    }

    fun sync(resolveReviewedMemoryConflict: Boolean = false): EtaSyncResult = exclusive {
        val config = config()
        require(config.configured) { "Configure WebDAV and the encryption passphrase first" }
        val secretText = secrets.bearerToken("eta-webdav-passphrase")
            ?: error("Sync encryption passphrase missing")
        val passphrase = secretText.toCharArray()
        try {
            val client = EtaWebDavTransport(
                config.endpoint, config.username,
                secrets.bearerToken("eta-webdav-password").orEmpty(),
            )
            val baseline = baselineFile(config)
            val base = loadBaseline(baseline)
            client.ensureCollection()
            repeat(MAX_RETRIES) {
                val local = localPayload()
                val fetched = client.read()
                if (fetched == null && base != null) {
                    return@exclusive EtaSyncResult(false,
                        "Remote sync state disappeared. The local files are unchanged; verify the URL or restore the remote file.")
                }
                val remote = fetched?.let {
                    EtaSyncPayload.fromJson(EtaWebDavCrypto.decrypt(it.blob, passphrase))
                }
                val normalMerge = EtaSyncMerge.compute(base, local, remote)
                val candidate = if (
                    resolveReviewedMemoryConflict && normalMerge is EtaSyncMerge.MemoryConflict &&
                    remote != null && loadConflictMarker(config) == AgentKnowledgeStore.sha256(remote.memoryMarkdown)
                ) {
                    // This special path requires a separate explicit confirmation in the UI.
                    // Keep the remote's knowledge and experiences intact, but accept the locally reviewed
                    // Markdown; the If-Match below still prevents overwriting a new remote revision.
                    EtaSyncMerge.compute(
                        base, local,
                        remote.copy(memoryMarkdown = base?.memoryMarkdown.orEmpty()),
                    )
                } else normalMerge
                when (candidate) {
                    EtaSyncMerge.MemoryConflict -> {
                        remote?.let {
                            preserveConflictCopy(it.memoryMarkdown)
                            saveConflictMarker(config, AgentKnowledgeStore.sha256(it.memoryMarkdown))
                        }
                        return@exclusive EtaSyncResult(false,
                            "MEMORY_CONFLICT: local and remote MEMORY.md changed independently. No data was overwritten. Review the remote copy in memory/conflicts, edit and save MEMORY.md, then confirm the reviewed local version in memory settings.")
                    }
                    is EtaSyncMerge.Merged -> {
                        // Avoid uploading a snapshot already stale because a concurrent model run wrote memory.
                        if (localPayload() != local) return@repeat
                        val merged = candidate.payload
                        val upload = fetched == null || merged != remote
                        if (upload) {
                            val ciphertext = EtaWebDavCrypto.encrypt(merged.json(), passphrase)
                            if (!client.write(ciphertext, fetched?.etag)) return@repeat
                        }
                        // Apply locally only with CAS; if another process changed data, retry on next sync.
                        val localMemory = AgentMemoryRepository.snapshot()
                        val memoryOk = if (localMemory.content == merged.memoryMarkdown) {
                            true
                        } else if (localMemory.content != local.memoryMarkdown) {
                            false
                        } else {
                            AgentMemoryRepository.replaceAllIfRevision(
                                merged.memoryMarkdown, localMemory.revision,
                            ) is AgentMemoryWriteResult.Success
                        }
                        val knowledgeOk = AgentKnowledgeRepository.replaceIfUnchanged(
                            local.knowledge, merged.knowledge,
                        )
                        val experienceOk = AgentExperienceRepository.replaceIfUnchanged(
                            local.experiences, merged.experiences,
                        )
                        val retainedOk = AgentRetainedMemoryRepository.replaceIfUnchanged(
                            local.retained, merged.retained,
                        )
                        if (!memoryOk || !knowledgeOk || !experienceOk || !retainedOk) {
                            return@exclusive EtaSyncResult(false,
                                "Remote state committed, but local data changed during sync. Run sync again; no concurrent local edit was overwritten.",
                                uploaded = upload)
                        }
                        saveBaseline(baseline, merged)
                        conflictMarkerFile(config).delete()
                        return@exclusive EtaSyncResult(
                            success = true,
                            message = if (upload) "WebDAV sync completed" else "Already up to date",
                            uploaded = upload,
                            knowledgeCount = merged.knowledge.count { !it.deleted },
                            experienceCount = merged.experiences.items.size,
                            retainedCount = merged.retained.items.count { it.active },
                            knowledgeConflicts = candidate.knowledgeConflicts,
                        )
                    }
                }
            }
            EtaSyncResult(false, "WebDAV was updated concurrently; retry sync.")
        } finally {
            Arrays.fill(passphrase, '\u0000')
        }
    }

    private fun localPayload(): EtaSyncPayload = EtaSyncPayload(
        memoryMarkdown = AgentMemoryRepository.snapshot().content,
        knowledge = AgentKnowledgeRepository.snapshot(),
        experiences = AgentExperienceRepository.syncSnapshot(),
        retained = AgentRetainedMemoryRepository.snapshot(),
    )

    private fun baselineFile(config: EtaSyncConfig): DurableAtomicFile {
        return DurableAtomicFile(File(root, "webdav-base-${syncFingerprint(config)}.json"))
    }

    private fun syncFingerprint(config: EtaSyncConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest((config.endpoint + "\u0000" + config.username).toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }

    private fun conflictMarkerFile(config: EtaSyncConfig): DurableAtomicFile =
        DurableAtomicFile(File(root, "webdav-memory-conflict-${syncFingerprint(config)}.hash"))

    private fun loadConflictMarker(config: EtaSyncConfig): String? {
        val file = conflictMarkerFile(config)
        if (!file.exists()) return null
        return file.openRead().use { stream ->
            stream.readBytes().toString(Charsets.US_ASCII).takeIf {
                it.matches(Regex("[a-f0-9]{64}"))
            }
        }
    }

    private fun saveConflictMarker(config: EtaSyncConfig, remoteHash: String) {
        val file = conflictMarkerFile(config)
        val output = file.startWrite()
        try {
            output.write(remoteHash.toByteArray(Charsets.US_ASCII))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun loadBaseline(file: DurableAtomicFile): EtaSyncPayload? {
        if (!file.exists()) return null
        val bytes = file.openRead().use { stream ->
            val content = stream.readBytes()
            require(content.size <= EtaSyncPayload.MAX_BYTES) { "Sync baseline exceeds safety limit" }
            content
        }
        return EtaSyncPayload.fromJson(bytes.toString(Charsets.UTF_8))
    }

    private fun saveBaseline(file: DurableAtomicFile, payload: EtaSyncPayload) {
        val bytes = payload.json().toByteArray(Charsets.UTF_8)
        require(bytes.size <= EtaSyncPayload.MAX_BYTES)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun preserveConflictCopy(remote: String) {
        val directory = File(root, "conflicts")
        check(directory.isDirectory || directory.mkdirs())
        val hash = AgentKnowledgeStore.sha256(remote).take(24)
        val file = DurableAtomicFile(File(directory, "remote-memory-$hash.md"))
        val output = file.startWrite()
        try {
            output.write(remote.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun <T> exclusive(action: () -> T): T = synchronized(inProcessLock) {
        check(root.isDirectory || root.mkdirs())
        RandomAccessFile(syncLockFile, "rw").channel.use { channel ->
            val lock = channel.lock()
            try { action() } finally { lock.release() }
        }
    }

    private companion object { const val MAX_RETRIES = 3 }
}

internal object EtaWebDavSyncRepository {
    @Volatile private lateinit var store: EtaWebDavSyncStore
    fun init(context: Context) {
        if (!::store.isInitialized) store = EtaWebDavSyncStore(context.applicationContext)
    }
    fun config(): EtaSyncConfig = checked().config()
    fun hasMemoryConflict(): Boolean = checked().hasMemoryConflict()
    fun remoteConflictContent(): String? = checked().remoteConflictContent()
    fun configure(endpoint: String, username: String, password: String, passphrase: String) =
        checked().configure(endpoint, username, password, passphrase)
    fun clearConfig() = checked().clearConfig()
    fun sync(resolveReviewedMemoryConflict: Boolean = false): EtaSyncResult =
        checked().sync(resolveReviewedMemoryConflict)
    private fun checked(): EtaWebDavSyncStore {
        check(::store.isInitialized) { "EtaWebDavSyncRepository.init(context) missing" }
        return store
    }
}
