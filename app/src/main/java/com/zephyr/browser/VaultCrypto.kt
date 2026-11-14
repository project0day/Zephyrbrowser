package com.zephyr.browser

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM vault crypto backed by Android Keystore.
 *
 * v1.7.0 CRITICAL FIX: since v1.3 encrypt() passed an explicit GCMParameterSpec(128, iv)
 * in ENCRYPT_MODE with a keystore key generated via setRandomizedEncryptionRequired(true).
 * AndroidKeyStore REJECTS caller-provided IVs for encryption
 * (InvalidAlgorithmParameterException "Caller-provided IV not permitted") — so EVERY
 * encrypt() threw on a real device: the save-password dialog's insert was swallowed by
 * `catch (_: Exception) {}` (silent no-op — user pressed "Да" and nothing was saved),
 * the passwords panel showed «Не удалось зашифровать пароль», and card saving failed the
 * same way. The fix: init the cipher WITHOUT an IV spec (the keystore generates a fresh
 * random IV, read back via cipher.iv), prepend that IV to the ciphertext. On any keystore
 * failure → delete the alias, regenerate, retry once → last resort: file-backed key
 * (32 random bytes in noBackupFilesDir) with an explicit IV and an "f:" ciphertext prefix.
 * Ciphertext format is otherwise unchanged (Base64(iv+ct)), so rows written by pre-v1.3
 * keystore keys still decrypt; decrypt() additionally tries the other backend once to
 * cover mixed history after a keystore wipe.
 */
object VaultCrypto {

    private const val KS_ALIAS = "zephyr_vault_key"
    private const val KEY_FILE = ".vault_key"
    private const val FILE_PREFIX = "f:"

    @Volatile
    private var appContext: android.content.Context? = null

    /** In-memory key — ONLY for the JVM/unit path (no AndroidKeyStore, no Context). */
    private var memoryKey: SecretKey? = null

    /** Lazy file-backed fallback key (persisted, so "f:" rows survive restarts). */
    private var fileKey: SecretKey? = null
    private val fileLock = Any()

    /** Call once from App.onCreate so the file-fallback key can be persisted. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    // ---------------- keys ----------------

    /** Keystore key, or null when AndroidKeyStore is unavailable (JVM unit tests). */
    private fun keystoreKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey) ?: generateKeystoreKey()
    } catch (_: Exception) {
        null
    }

    private fun generateKeystoreKey(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return kg.generateKey()
    }

    // v1.8.6: deleteKeystoreAlias() lived here and is gone rather than left
    // unused. It wiped the alias so a failed encrypt could retry with a fresh
    // key — which silently destroyed every password already stored under the
    // old one (audit A-004). A dead helper for "recovery" is an invitation to
    // call it again; the recovery it offered was the loss.

    /**
     * File-backed fallback key: 32 random bytes persisted Base64 in
     * noBackupFilesDir (filesDir as a safety net), created on first need only.
     * Without a Context (JVM unit tests, init() never called) keeps the OLD
     * in-memory fallback behavior so local round-trip tests still pass.
     */
    private fun fileFallbackKey(): SecretKey? {
        val ctx = appContext ?: return memoryFallbackKey()
        fileKey?.let { return it }
        return synchronized(fileLock) {
            fileKey?.let { return it }
            try {
                val dir: File = ctx.noBackupFilesDir ?: ctx.filesDir
                val f = File(dir, KEY_FILE)
                val bytes: ByteArray
                if (f.exists()) {
                    bytes = Base64.decode(f.readText().trim(), Base64.NO_WRAP)
                } else {
                    bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    f.parentFile?.mkdirs()
                    f.writeText(Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
                if (bytes.size != 32) throw IllegalStateException("corrupt vault key file")
                SecretKeySpec(bytes, "AES").also { fileKey = it }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun memoryFallbackKey(): SecretKey? =
        memoryKey ?: synchronized(this) {
            memoryKey ?: KeyGenerator.getInstance("AES").apply {
                init(256, SecureRandom())
            }.generateKey().also { memoryKey = it }
        }

    // ---------------- crypto ----------------

    fun encrypt(plain: String): String {
        // 1) keystore path — NO caller IV in ENCRYPT mode (the v1.3–v1.6 killer bug).
        try {
            return encryptWithKeystore(plain)
        } catch (_: Throwable) {
        }
        // 2) SECURITY/DATA-LOSS (audit A-004, fixed 1.8.6).
        //
        // This step used to call deleteKeystoreAlias() and retry. That is
        // unrecoverable: every password and card already stored was encrypted
        // under the old key, and destroying the alias destroys the only thing
        // that can read them back. No later decrypt can succeed.
        //
        // And the trigger need not be real corruption — a keystore throws
        // transiently, for instance just after the screen lock is changed or
        // while the device is still unlocking. One such failure during one save
        // would have discarded the entire vault.
        //
        // So the alias is now left alone. The write falls through to the file
        // key below, which stores this record safely while every existing row
        // stays readable by the keystore key that is still there.
        //
        // Nothing regenerates the alias any more, deliberately. If the key is
        // ever genuinely unusable the file backend carries the vault, and that
        // is a survivable state; destroying data to recover a key is not.
        //
        // 3) last resort: file-backed (or, without a Context, in-memory) key.
        //    Explicit IVs ARE allowed for plain SecretKeySpec keys.
        return try {
            encryptWithFileKey(plain)
        } catch (t: Throwable) {
            // everything failed → rethrow so callers surface the error (NEVER silent)
            throw t
        }
    }

    private fun encryptWithKeystore(plain: String): String {
        val key = keystoreKey() ?: throw IllegalStateException("AndroidKeyStore unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // CRITICAL: no GCMParameterSpec here — with setRandomizedEncryptionRequired(true)
        // the keystore must generate the IV itself; we read it back after init().
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw IllegalStateException("keystore returned no IV")
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun encryptWithFileKey(plain: String): String {
        val key = fileFallbackKey() ?: throw IllegalStateException("no vault key available")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return FILE_PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? {
        if (enc.isEmpty()) return null
        val fromFile = enc.startsWith(FILE_PREFIX)
        val payload = if (fromFile) enc.substring(FILE_PREFIX.length) else enc
        // try the matching backend first; on failure try the other once (mixed history:
        // rows written by the other backend before a keystore wipe / fallback switch).
        val primary: SecretKey? = if (fromFile) fileFallbackKey() else keystoreKey()
        primary?.let { k -> decryptWith(k, payload)?.let { return it } }
        val other: SecretKey? = if (fromFile) keystoreKey() else fileFallbackKey()
        other?.let { k -> decryptWith(k, payload)?.let { return it } }
        return null
    }

    private fun decryptWith(key: SecretKey, payload: String): String? = try {
        val all = Base64.decode(payload, Base64.NO_WRAP)
        if (all.size <= 12) null
        else {
            val iv = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Caller-provided IVs ARE allowed in DECRYPT mode — pass the stored IV.
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    /** Round-trip self test: true when encrypt→decrypt works on this device right now. */
    fun healthy(): Boolean = try {
        val probe = "zephyr-self-test"
        decrypt(encrypt(probe)) == probe
    } catch (_: Throwable) {
        false
    }

    fun sha256Hex(s: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = (salt + ":" + s).toByteArray(Charsets.UTF_8)
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** PIN manager: salted hash stored in Prefs. */
object PinManager {
    private var salt: String = ""
    fun init(saltValue: String) { salt = saltValue }
    fun hash(pin: String): String = VaultCrypto.sha256Hex(pin, salt)
}

/** Offline strong password generator. */
object PasswordGenerator {
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SPECIAL = "!@#$%&*+-_=?"

    fun generate(length: Int = 16, upper: Boolean = true, digits: Boolean = true, special: Boolean = true): String {
        val rng = SecureRandom()
        val pool = buildString {
            append(LOWER)
            if (upper) append(UPPER)
            if (digits) append(DIGITS)
            if (special) append(SPECIAL)
        }
        if (pool.isEmpty()) return ""
        val out = StringBuilder()
        repeat(length.coerceIn(4, 64)) { out.append(pool[rng.nextInt(pool.length)]) }
        // guarantee one of each selected class
        if (upper) replaceAt(out, 0, UPPER[rng.nextInt(UPPER.length)])
        if (digits) replaceAt(out, 1, DIGITS[rng.nextInt(DIGITS.length)])
        if (special) replaceAt(out, 2, SPECIAL[rng.nextInt(SPECIAL.length)])
        return out.toString()
    }

    private fun replaceAt(sb: StringBuilder, index: Int, c: Char) {
        if (sb.isNotEmpty() && index < sb.length) sb[index] = c
    }
}
