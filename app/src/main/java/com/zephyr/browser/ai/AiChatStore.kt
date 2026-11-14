package com.zephyr.browser.ai

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved conversation. */
data class AiChat(
    val id: String,
    var title: String,
    var updated: Long,
    val messages: MutableList<AiMessage> = ArrayList()
)

/**
 * v1.7.25b: persistent AI conversations.
 *
 * The chat used to live only inside the bottom sheet — closing it threw the whole
 * dialogue away (user: «если диалог закрылся с ИИ то история и всё утеряно?»).
 * Everything is now written to `ai_chats.json` in the app's private storage, so
 * a chat survives closing the sheet, leaving the app and rebooting the phone.
 *
 * Attachments are stored with their metadata; the base64 payload of an image is
 * kept only for the newest [IMAGE_KEEP] messages of a chat so the file stays small
 * (older images still render from their content uri while it is readable).
 */
object AiChatStore {

    /**
     * The name a chat carries until its first message supplies one.
     *
     * v1.7.88: was the literal «Диалог», which is what an English user saw in
     * their chat list. Resolved on each call rather than cached, because the
     * app's language can change while the process lives.
     */
    private fun untitled(): String = try {
        com.zephyr.browser.App.ctx?.getString(com.zephyr.browser.R.string.ai_untitled_chat)
            .orEmpty().ifBlank { "Chat" }
    } catch (_: Throwable) { "Chat" }

    private const val FILE = "ai_chats.json"
    private const val MAX_CHATS = 40
    private const val MAX_MESSAGES = 120
    private const val IMAGE_KEEP = 8

    private var cache: MutableList<AiChat>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    // ---------------- load / save ----------------

    @Synchronized
    fun all(ctx: Context): MutableList<AiChat> {
        cache?.let { return it }
        val out = ArrayList<AiChat>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                val arr = root.optJSONArray("chats") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val chat = AiChat(
                        id = o.optString("id", System.nanoTime().toString()),
                        title = o.optString("title", untitled()),
                        updated = o.optLong("updated", 0L)
                    )
                    val ms = o.optJSONArray("messages") ?: JSONArray()
                    for (j in 0 until ms.length()) {
                        val m = ms.getJSONObject(j)
                        chat.messages.add(
                            AiMessage(
                                role = m.optString("role", "user"),
                                text = m.optString("text"),
                                attachments = parseAtts(m.optJSONArray("atts")),
                                error = m.optBoolean("error", false),
                                tokens = m.optInt("tokens", 0),
                                elapsedMs = m.optLong("elapsed", 0L),
                                at = m.optLong("at", 0L).let { if (it > 0) it else System.currentTimeMillis() },
                                via = m.optString("via"),
                                requests = m.optInt("reqs", 0),
                                truncated = m.optBoolean("cut", false)
                            )
                        )
                    }
                    out.add(chat)
                }
            }
        } catch (_: Throwable) { /* corrupt file → start fresh */ }
        out.sortByDescending { it.updated }
        cache = out
        return out
    }

    private fun parseAtts(arr: JSONArray?): List<AiAttachment> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<AiAttachment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                AiAttachment(
                    uri = Uri.parse(o.optString("uri")),
                    name = o.optString("name", "file"),
                    mime = o.optString("mime", "*/*"),
                    isImage = o.optBoolean("img", false),
                    text = o.optString("text").ifBlank { null },
                    dataUrl = o.optString("data").ifBlank { null }
                )
            )
        }
        return out
    }

    @Synchronized
    fun persist(ctx: Context) {
        val chats = cache ?: return
        try {
            chats.sortByDescending { it.updated }
            while (chats.size > MAX_CHATS) chats.removeAt(chats.size - 1)
            val arr = JSONArray()
            for (c in chats) {
                val ms = JSONArray()
                val msgs = if (c.messages.size > MAX_MESSAGES)
                    c.messages.subList(c.messages.size - MAX_MESSAGES, c.messages.size) else c.messages
                val keepFrom = (msgs.size - IMAGE_KEEP).coerceAtLeast(0)
                msgs.forEachIndexed { idx, m ->
                    val atts = JSONArray()
                    for (a in m.attachments) {
                        val ao = JSONObject()
                            .put("uri", a.uri.toString())
                            .put("name", a.name)
                            .put("mime", a.mime)
                            .put("img", a.isImage)
                        a.text?.let { ao.put("text", it) }
                        if (a.dataUrl != null && idx >= keepFrom) ao.put("data", a.dataUrl)
                        atts.put(ao)
                    }
                    ms.put(
                        JSONObject()
                            .put("role", m.role)
                            .put("text", m.text)
                            .put("error", m.error)
                            .put("tokens", m.tokens)
                            .put("elapsed", m.elapsedMs)
                            .put("at", m.at)
                            .put("via", m.via)
                            .put("reqs", m.requests)
                            .put("cut", m.truncated)
                            .put("atts", atts)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("updated", c.updated)
                        .put("messages", ms)
                )
            }
            file(ctx).writeText(JSONObject().put("chats", arr).toString())
        } catch (_: Throwable) { /* best effort */ }
    }

    // ---------------- operations ----------------

    fun newChat(ctx: Context): AiChat {
        val c = AiChat(
            id = System.currentTimeMillis().toString() + "-" + (0..9999).random(),
            title = "",
            updated = System.currentTimeMillis()
        )
        all(ctx).add(0, c)
        return c
    }

    fun byId(ctx: Context, id: String?): AiChat? = id?.let { i -> all(ctx).firstOrNull { it.id == i } }

    /** Newest chat that already has messages, or null. */
    fun latestNonEmpty(ctx: Context): AiChat? = all(ctx).firstOrNull { it.messages.isNotEmpty() }

    fun touch(ctx: Context, chat: AiChat) {
        chat.updated = System.currentTimeMillis()
        if (chat.title.isBlank()) {
            val first = chat.messages.firstOrNull { it.role == "user" }?.text?.trim().orEmpty()
            chat.title = when {
                first.isNotBlank() -> first.replace("\n", " ").take(48)
                chat.messages.isNotEmpty() -> untitled()
                else -> ""
            }
        }
        persist(ctx)
    }

    fun delete(ctx: Context, chat: AiChat) {
        all(ctx).removeAll { it.id == chat.id }
        persist(ctx)
    }

    fun deleteAll(ctx: Context) {
        all(ctx).clear()
        persist(ctx)
    }

    fun rename(ctx: Context, chat: AiChat, title: String) {
        chat.title = title.trim().ifBlank { chat.title }
        persist(ctx)
    }

    /** Drop chats that never received a message (the pencil button creates one eagerly). */
    fun pruneEmpty(ctx: Context, keep: String?) {
        all(ctx).removeAll { it.messages.isEmpty() && it.id != keep }
    }
}
