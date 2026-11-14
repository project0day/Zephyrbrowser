package com.zephyr.browser.ai

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v1.7.33: the cipher shared by Zephyr and the agent connector script.
 *
 * Deliberately built from HMAC-SHA256 alone. The connector has to run inside
 * whatever sandbox the user's agent lives in, and Python's standard library has
 * no AES — requiring `cryptography` would mean the link fails on half the
 * environments. HMAC is everywhere, on both sides.
 *
 * Construction (encrypt-then-MAC, nothing home-made about the primitives):
 *   key      = PBKDF2-HMAC-SHA256(pairing code, salt, 60k, 32 bytes)
 *   encKey   = HMAC(key, "zephyr-enc")
 *   macKey   = HMAC(key, "zephyr-mac")
 *   keystream_i = HMAC(encKey, nonce || big-endian i)      → 32 bytes per block
 *   ct       = plaintext XOR keystream
 *   tag      = HMAC(macKey, nonce || ct) truncated to 16 bytes
 *   wire     = base64(nonce(12) || ct || tag(16))
 *
 * The transport (a public ntfy topic) only ever sees `wire`.
 */
object AgentCrypto {

    private const val ITERATIONS = 60_000

    fun deriveKey(code: String): ByteArray {
        val norm = normalize(code)
        val salt = sha256(("zephyr-salt|$norm").toByteArray(Charsets.UTF_8)).copyOf(16)
        val spec = javax.crypto.spec.PBEKeySpec(norm.toCharArray(), salt, ITERATIONS, 256)
        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(spec).encoded
    }

    /** Topic name both sides compute from the code — never the code itself. */
    fun topicBase(code: String): String {
        val h = sha256(("zephyr-topic|" + normalize(code)).toByteArray(Charsets.UTF_8))
        return "zephyr" + hex(h).take(20)
    }

    fun encrypt(key: ByteArray, plain: ByteArray): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = xorKeystream(hmac(key, "zephyr-enc"), nonce, plain)
        val tag = hmacRaw(hmac(key, "zephyr-mac"), nonce + ct).copyOf(16)
        return Base64.encodeToString(nonce + ct + tag, Base64.NO_WRAP)
    }

    /** null when the payload is not ours or the code is wrong. */
    fun decrypt(key: ByteArray, wire: String): ByteArray? = try {
        val raw = Base64.decode(wire, Base64.NO_WRAP)
        if (raw.size < 12 + 16) null else {
            val nonce = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size - 16)
            val tag = raw.copyOfRange(raw.size - 16, raw.size)
            val expect = hmacRaw(hmac(key, "zephyr-mac"), nonce + ct).copyOf(16)
            if (!constantTimeEquals(tag, expect)) null
            else xorKeystream(hmac(key, "zephyr-enc"), nonce, ct)
        }
    } catch (_: Throwable) { null }

    // ---------------- primitives ----------------

    private fun xorKeystream(encKey: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var off = 0
        var block = 0
        while (off < data.size) {
            val counter = byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(),
                (block ushr 8).toByte(), block.toByte()
            )
            val ks = hmacRaw(encKey, nonce + counter)
            var i = 0
            while (i < ks.size && off < data.size) {
                out[off] = (data[off].toInt() xor ks[i].toInt()).toByte()
                off++; i++
            }
            block++
        }
        return out
    }

    private fun hmac(key: ByteArray, label: String): ByteArray =
        hmacRaw(key, label.toByteArray(Charsets.UTF_8))

    private fun hmacRaw(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(b: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    /** Codes are compared case- and dash-insensitively. */
    fun normalize(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }

    /** Crockford-ish alphabet: no I, L, O, U — nothing to misread aloud. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** A fresh pairing code: ZEPH-XXXX-XXXX-XXXX (60 bits). */
    fun newCode(): String {
        val rnd = SecureRandom()
        val groups = (0 until 3).map { _ ->
            (0 until 4).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
        }
        return "ZEPH-" + groups.joinToString("-")
    }
}
