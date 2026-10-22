package io.github.mangi.eta.data.repository

import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A portable encrypted WebDAV blob: ASCII magic + 16-byte salt + 12-byte nonce + AES-GCM ciphertext.
 * PBKDF2-HMAC-SHA256 enables recovery on another Android device with the same passphrase.
 */
internal object EtaWebDavCrypto {
    private val MAGIC = "ETASYNC1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ROUNDS = 180_000
    const val MAX_BLOB_BYTES = EtaSyncPayload.MAX_BYTES + 128

    fun encrypt(content: String, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 12) { "Sync encryption passphrase must be at least 12 characters" }
        val plaintext = content.toByteArray(Charsets.UTF_8)
        require(plaintext.size <= EtaSyncPayload.MAX_BYTES) { "Sync data is too large" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = derive(passphrase, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(MAGIC)
            val body = cipher.doFinal(plaintext)
            return MAGIC + salt + nonce + body
        } finally {
            Arrays.fill(key, 0)
            Arrays.fill(plaintext, 0)
        }
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): String {
        require(blob.size in (MAGIC.size + SALT_BYTES + NONCE_BYTES + 16)..MAX_BLOB_BYTES) {
            "Unsupported or oversized encrypted sync file"
        }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Unknown sync encryption format"
        }
        val saltStart = MAGIC.size
        val nonceStart = saltStart + SALT_BYTES
        val bodyStart = nonceStart + NONCE_BYTES
        val salt = blob.copyOfRange(saltStart, nonceStart)
        val nonce = blob.copyOfRange(nonceStart, bodyStart)
        val key = derive(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(MAGIC)
            val clear = cipher.doFinal(blob.copyOfRange(bodyStart, blob.size))
            try { clear.toString(Charsets.UTF_8) } finally { Arrays.fill(clear, 0) }
        } catch (failure: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Incorrect sync passphrase or encrypted data was modified", failure)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ROUNDS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
