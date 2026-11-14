package com.zephyr.browser.ai

import android.content.Context
import android.util.Base64
import com.zephyr.browser.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.7.28: export / import of the AI Assistant setup.
 *
 * User: «хочу дать другу доступ к помощнику — он импортирует, и у него всё
 * появится: API-ключи, модели и т.д.». One file carries the model profiles
 * (name / endpoint / model / key), the system prompt and the generation
 * settings; the saved conversations are optional and off by default.
 *
 * API keys are credentials, so the file can — and by default should — be
 * AES-256-GCM encrypted with a passphrase (PBKDF2-SHA256, 120k iterations),
 * the same scheme the browser backup already uses. Keys can also be excluded
 * entirely, which produces a share-safe file with only the model list.
 */
object AiBackup {

    private const val FORMAT = "zephyr-ai"
    private const val VERSION = 1

    /** What a file turned out to contain, after a successful import. */
    data class ImportResult(
        val ok: Boolean,
        val profiles: Int = 0,
        val chats: Int = 0,
        val hadKeys: Boolean = false,
        val needsPassword: Boolean = false,
        val error: String? = null
    )

    // ---------------- export ----------------

    /**
     * Build the backup document.
     * @param includeKeys false → the profiles travel without their API keys
     * @param includeChats true → the saved conversations ride along
     * @param password blank → plain JSON, otherwise an encrypted envelope
     */
    fun export(
        ctx: Context,
        includeKeys: Boolean,
        includeChats: Boolean,
        password: String
    ): ByteArray {
        val profiles = JSONArray()
        for (p in AiModels.list()) {
            profiles.put(
                JSONObject()
                    .put("name", p.name)
                    .put("endpoint", p.endpoint)
                    .put("model", p.model)
                    .put("key", if (includeKeys) p.key else "")
            )
        }

        val settings = JSONObject()
            .put("prompt", Prefs.aiPrompt)
            .put("temperature", Prefs.aiTemperature)
            .put("maxTokens", Prefs.aiMaxTokens)
            .put("historyDepth", Prefs.aiHistoryDepth)
            .put("streaming", Prefs.aiStreaming)
            .put("showUsage", Prefs.aiShowUsage)
            .put("sendPageContext", Prefs.aiSendPageContext)

        val doc = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("hasKeys", includeKeys)
            .put("activeProfile", AiModels.activeIndex())
            .put("profiles", profiles)
            .put("settings", settings)

        if (includeChats) {
            val chats = JSONArray()
            for (c in AiChatStore.all(ctx)) {
                if (c.messages.isEmpty()) continue
                val ms = JSONArray()
                for (m in c.messages) {
                    // attachments are device-local uris — only the text travels
                    ms.put(
                        JSONObject()
                            .put("role", m.role)
                            .put("text", m.text)
                            .put("error", m.error)
                            .put("tokens", m.tokens)
                            .put("elapsed", m.elapsedMs)
                    )
                }
                chats.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("updated", c.updated)
                        .put("messages", ms)
                )
            }
            doc.put("chats", chats)
        }

        val plain = doc.toString(2).toByteArray(Charsets.UTF_8)
        return if (password.isBlank()) plain else encrypt(plain, password)
    }

    /** Suggested file name for the SAF picker. */
    fun fileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "zephyr-ai-$stamp.json"
    }

    // ---------------- import ----------------

    /** Peek: is this an encrypted envelope? */
    fun isEncrypted(text: String): Boolean = try {
        JSONObject(text).optString("enc") == "AES-256-GCM"
    } catch (_: Exception) { false }

    /** Peek: does this look like one of our AI files at all? */
    fun looksLikeOurs(text: String): Boolean = try {
        val o = JSONObject(text)
        o.optString("format") == FORMAT || o.optString("enc") == "AES-256-GCM"
    } catch (_: Exception) { false }

    /**
     * Apply a backup file.
     * @param replace true → the saved profiles are replaced, false → merged
     *   (an identical endpoint+model is updated in place rather than duplicated)
     */
    fun import(ctx: Context, raw: String, password: String, replace: Boolean): ImportResult {
        val text = if (isEncrypted(raw)) {
            if (password.isBlank()) return ImportResult(false, needsPassword = true)
            decrypt(raw, password)
                ?: return ImportResult(false, needsPassword = true, error = "Неверный пароль")
        } else raw

        return try {
            val doc = JSONObject(text)
            if (doc.optString("format") != FORMAT) {
                return ImportResult(false, error = "Это не файл ИИ-помощника Zephyr")
            }

            val incoming = ArrayList<AiProfile>()
            val arr = doc.optJSONArray("profiles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val model = o.optString("model")
                incoming.add(
                    AiProfile(
                        name = o.optString("name").ifBlank { model.ifBlank { "Модель" } },
                        endpoint = o.optString("endpoint"),
                        model = model,
                        key = o.optString("key")
                    )
                )
            }
            if (incoming.isEmpty()) return ImportResult(false, error = "В файле нет моделей")

            val merged: MutableList<AiProfile>
            if (replace) {
                merged = incoming
            } else {
                merged = AiModels.list().toMutableList()
                for (p in incoming) {
                    val idx = merged.indexOfFirst {
                        it.endpoint.equals(p.endpoint, true) && it.model.equals(p.model, true)
                    }
                    // a file exported without keys must not wipe a key we already have
                    if (idx >= 0) {
                        val keep = if (p.key.isBlank()) merged[idx].key else p.key
                        merged[idx] = p.copy(key = keep)
                    } else merged.add(p)
                }
            }
            val active = doc.optInt("activeProfile", 0).coerceIn(0, merged.size - 1)
            AiModels.save(merged, active)

            doc.optJSONObject("settings")?.let { s ->
                Prefs.setAiSettings(
                    Prefs.aiApiKey, Prefs.aiEndpoint, Prefs.aiModel,
                    s.optString("prompt").ifBlank { Prefs.aiPrompt }
                )
                Prefs.setAiTemperature(s.optInt("temperature", Prefs.aiTemperature))
                Prefs.setAiMaxTokens(s.optInt("maxTokens", Prefs.aiMaxTokens))
                Prefs.setAiHistoryDepth(s.optInt("historyDepth", Prefs.aiHistoryDepth))
                Prefs.setAiStreaming(s.optBoolean("streaming", Prefs.aiStreaming))
                Prefs.setAiShowUsage(s.optBoolean("showUsage", Prefs.aiShowUsage))
                Prefs.setAiSendPageContext(s.optBoolean("sendPageContext", Prefs.aiSendPageContext))
            }

            var chatCount = 0
            doc.optJSONArray("chats")?.let { chats ->
                val store = AiChatStore.all(ctx)
                for (i in 0 until chats.length()) {
                    val co = chats.getJSONObject(i)
                    val id = co.optString("id").ifBlank { System.nanoTime().toString() }
                    if (store.any { it.id == id }) continue
                    val chat = AiChat(id, co.optString("title", "Диалог"), co.optLong("updated", 0L))
                    val ms = co.optJSONArray("messages") ?: JSONArray()
                    for (j in 0 until ms.length()) {
                        val m = ms.getJSONObject(j)
                        chat.messages.add(
                            AiMessage(
                                role = m.optString("role", "user"),
                                text = m.optString("text"),
                                error = m.optBoolean("error", false),
                                tokens = m.optInt("tokens", 0),
                                elapsedMs = m.optLong("elapsed", 0L)
                            )
                        )
                    }
                    if (chat.messages.isNotEmpty()) { store.add(chat); chatCount++ }
                }
                if (chatCount > 0) AiChatStore.persist(ctx)
            }

            ImportResult(
                ok = true,
                profiles = incoming.size,
                chats = chatCount,
                hadKeys = doc.optBoolean("hasKeys", incoming.any { it.key.isNotBlank() })
            )
        } catch (t: Throwable) {
            ImportResult(false, error = t.localizedMessage ?: "Файл повреждён")
        }
    }

    // ---------------- crypto (same scheme as the browser backup) ----------------

    private fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("enc", "AES-256-GCM")
            .put("kdf", "PBKDF2-SHA256-120000")
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("payload", Base64.encodeToString(ct, Base64.NO_WRAP))
            .toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun decrypt(text: String, password: String): String? = try {
        val env = JSONObject(text)
        val salt = Base64.decode(env.optString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(env.optString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(env.optString("payload"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (_: Exception) { null }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
