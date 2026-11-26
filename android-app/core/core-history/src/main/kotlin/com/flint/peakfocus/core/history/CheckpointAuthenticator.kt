package com.flint.peakfocus.core.history

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Integrity for the persisted best-streak checkpoint.
 *
 * The checkpoint is a *derived* value whose only justification is history that may have been
 * pruned. A plain checksum would let anyone who can edit the app-private metadata recompute it,
 * so the production implementation keys an HMAC with platform-protected material where available
 * and falls back to a per-install random key file. That makes accidental corruption and casual
 * edits detectable; it is not a defence against a local attacker who can read the app's private
 * storage or instrument the process, and we do not claim otherwise.
 */
interface CheckpointAuthenticator {
    /** Returns a hex HMAC, or null when signing is unavailable. */
    fun sign(payload: String): String?

    fun verify(payload: String, mac: String): Boolean
}

internal const val CHECKPOINT_HMAC_ALGORITHM = "HmacSHA256"

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}

/** HMAC-SHA256 over a canonical payload; comparisons are constant-time. */
class HmacCheckpointAuthenticator private constructor(
    private val keyProvider: () -> ByteArray,
) : CheckpointAuthenticator {
    constructor(key: ByteArray) : this({ key })

    override fun sign(payload: String): String? = runCatching { hmac(payload).toHex() }.getOrNull()

    override fun verify(payload: String, mac: String): Boolean = runCatching {
        val given = mac.hexToBytes() ?: return false
        MessageDigest.isEqual(hmac(payload), given)
    }.getOrDefault(false)

    private fun hmac(payload: String): ByteArray =
        Mac.getInstance(CHECKPOINT_HMAC_ALGORITHM)
            .apply { init(SecretKeySpec(keyProvider(), CHECKPOINT_HMAC_ALGORITHM)) }
            .doFinal(payload.toByteArray(Charsets.UTF_8))

    companion object {
        private const val KEY_BYTES = 32
        private val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        /**
         * Lazy per-install key: constructing a store must not touch the directory, so an
         * untouched/empty history directory stays empty.
         */
        fun loadOrCreate(directory: File): CheckpointAuthenticator = HmacCheckpointAuthenticator {
            cache.computeIfAbsent(directory.absolutePath) { readOrCreateKey(directory) }
        }

        private fun readOrCreateKey(directory: File): ByteArray {
            val file = File(directory, "checkpoint-key.v1")
            val existing = runCatching { file.readText().trim() }.getOrNull()
                ?.hexToBytes()?.takeIf { it.size == KEY_BYTES }
            if (existing != null) return existing
            val fresh = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
            val tmp = File(directory, "${file.name}.tmp")
            runCatching {
                tmp.writeText(fresh.toHex())
                if (!tmp.renameTo(file)) { file.writeText(fresh.toHex()); tmp.delete() }
            }
            return fresh
        }
    }
}

/**
 * Primary signer with a per-install key-file fallback. `verify` accepts a MAC produced by either,
 * so a device whose Keystore becomes unavailable does not silently invalidate earlier checkpoints.
 */
class FallbackCheckpointAuthenticator(
    private val primary: CheckpointAuthenticator,
    private val fallback: CheckpointAuthenticator,
) : CheckpointAuthenticator {
    override fun sign(payload: String): String? =
        primary.sign(payload) ?: fallback.sign(payload)

    override fun verify(payload: String, mac: String): Boolean =
        primary.verify(payload, mac) || fallback.verify(payload, mac)
}

/**
 * Production signer using an Android Keystore HMAC key (non-exportable, per-app, survives
 * restarts). Any failure degrades to "unverifiable", and the store then reports a degraded best
 * rather than trusting the file.
 */
class KeystoreCheckpointAuthenticator(
    private val alias: String = "flint.history.best.v1",
) : CheckpointAuthenticator {
    @Volatile private var cached: SecretKey? = null

    override fun sign(payload: String): String? = runCatching {
        Mac.getInstance(CHECKPOINT_HMAC_ALGORITHM)
            .apply { init(key()) }
            .doFinal(payload.toByteArray(Charsets.UTF_8))
            .toHex()
    }.getOrNull()

    override fun verify(payload: String, mac: String): Boolean = runCatching {
        val given = mac.hexToBytes() ?: return@runCatching false
        val expected = Mac.getInstance(CHECKPOINT_HMAC_ALGORITHM)
            .apply { init(key()) }
            .doFinal(payload.toByteArray(Charsets.UTF_8))
        MessageDigest.isEqual(expected, given)
    }.getOrDefault(false)

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = store.getEntry(alias, null) as? java.security.KeyStore.SecretKeyEntry
            val secret = entry?.secretKey ?: run {
                val generator = javax.crypto.KeyGenerator.getInstance(
                    CHECKPOINT_HMAC_ALGORITHM, "AndroidKeyStore",
                )
                generator.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        alias, android.security.keystore.KeyProperties.PURPOSE_SIGN,
                    ).setKeySize(256).build(),
                )
                generator.generateKey()
            }
            cached = secret
            return secret
        }
    }
}
