package com.zephyr.browser.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** One attachment the user added to a chat message. */
data class AiAttachment(
    val uri: Uri,
    val name: String,
    val mime: String,
    val isImage: Boolean,
    /** extracted text for documents; null for images */
    val text: String? = null,
    /** base64 data-url for images; null for documents */
    val dataUrl: String? = null
)

/** One chat turn kept in the conversation history. */
data class AiMessage(
    val role: String,               // "user" | "assistant"
    val text: String,
    val attachments: List<AiAttachment> = emptyList(),
    val error: Boolean = false,
    /** v1.7.27: tokens the provider billed for this turn (0 = unknown). */
    val tokens: Int = 0,
    /** v1.7.27: how long the answer took, milliseconds. */
    val elapsedMs: Long = 0L,
    /** v1.7.38: when the turn happened — drives the date separators. */
    val at: Long = System.currentTimeMillis(),
    /** v1.7.44: which profile produced this answer. */
    val via: String = "",
    /** v1.7.44: how many API calls this answer cost. */
    val requests: Int = 0,
    /** v1.7.48: the answer was cut off by the token limit. */
    val truncated: Boolean = false
)

/**
 * Result of a chat call.
 *
 * v1.7.44: [via] and [requests] exist because an answer used to arrive with no
 * way of telling WHO produced it. When the swarm quietly answered instead of
 * the selected profile, nothing on screen disagreed — and a whole evening went
 * into hunting a bug that was really just "somebody else replied".
 */
data class AiResult(
    val text: String,
    val ok: Boolean,
    val usage: AiUsage? = null,
    /** Name of the profile that actually produced this answer. */
    val via: String = "",
    /** HTTP calls this answer cost — tool rounds included. */
    val requests: Int = 1,
    /**
     * v1.7.48: the model ran out of room mid-sentence (finish_reason
     * "length"). The chat offers to continue instead of leaving the user to
     * notice the cut and ask again.
     */
    val truncated: Boolean = false
)

/** v1.7.27: token accounting reported by the provider. */
data class AiUsage(val prompt: Int, val completion: Int, val total: Int)

/**
 * v1.7.25: OpenAI-compatible chat client with conversation history and
 * image / document attachments. Works with OpenAI, NVIDIA NIM, DeepSeek, Groq,
 * OpenRouter, Mistral, Together, Cerebras and local Ollama.
 */
object AiClient {

    /**
     * A string resource, for the text the user reads.
     *
     * v1.7.86: the status line and the error messages were Russian literals,
     * so a French user watching the assistant work saw "Печатает…" and, when
     * something failed, a Russian explanation of it. This object is handed no
     * Context — which is how that happened — so it borrows the application's.
     *
     * Strings that go to the MODEL rather than the screen are deliberately not
     * routed through here: tool descriptions and tool results are part of the
     * API contract, not the interface, and they stay in one language.
     */
    private fun s(res: Int, vararg args: Any): String = try {
        val c = com.zephyr.browser.App.ctx
        if (c == null) "" else if (args.isEmpty()) c.getString(res) else c.getString(res, *args)
    } catch (_: Throwable) { "" }

    /**
     * v1.9.13: the round ceiling used to end in "часть работы могла
     * выполниться — проверьте". That tells the user to go and audit something
     * we already know: every tool this answer ran is in [usedTools]. Naming
     * them turns a dead end into a place to carry on from.
     */
    private fun roundLimitMessage(usedTools: List<String>): String {
        val names = usedTools.distinct().mapNotNull { name ->
            AiActionLogSheet.TITLES[name]?.let { s(it) }?.takeIf { it.isNotBlank() } ?: name
        }
        if (names.isEmpty()) return s(R.string.ai_err_rounds, MAX_TOOL_ROUNDS)
        return s(R.string.ai_err_rounds_done, MAX_TOOL_ROUNDS, names.joinToString(", "))
    }

    private const val MAX_DOC_CHARS = 12000
    private const val MAX_IMAGE_EDGE = 1280

    // ---------------- attachments ----------------

    /** Read a picked uri into an attachment (downscaled image, or extracted text). */
    fun readAttachment(ctx: Context, uri: Uri): AiAttachment? = try {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: guessMime(uri.toString())
        val name = displayName(ctx, uri)
        if (mime.startsWith("image/")) {
            AiAttachment(uri, name, mime, true, dataUrl = encodeImage(ctx, uri))
        } else {
            AiAttachment(uri, name, mime, false, text = extractText(ctx, uri, mime))
        }
    } catch (_: Throwable) { null }

    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: "file"
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun guessMime(s: String): String = when {
        s.endsWith(".png", true) -> "image/png"
        s.endsWith(".jpg", true) || s.endsWith(".jpeg", true) -> "image/jpeg"
        s.endsWith(".webp", true) -> "image/webp"
        s.endsWith(".pdf", true) -> "application/pdf"
        else -> "application/octet-stream"
    }

    /** Downscale + JPEG-encode into a `data:` url the vision models accept. */
    private fun encodeImage(ctx: Context, uri: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_IMAGE_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        if (bmp == null) null else {
            val fixed = applyExif(ctx, uri, bmp)
            val out = ByteArrayOutputStream()
            fixed.compress(Bitmap.CompressFormat.JPEG, 82, out)
            fixed.recycle()
            if (fixed !== bmp) bmp.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (_: Throwable) { null }

    /**
     * v1.7.80: rotate a photo before it is re-encoded for the model.
     *
     * A phone camera does not rotate pixels — it writes the orientation into an
     * EXIF tag and leaves the grid as the sensor read it. Re-encoding here threw
     * that tag away, so a portrait photo reached the model lying on its side and
     * the model was quietly answering about a sideways picture. Nothing in the
     * chat showed it, because every viewer on the way honoured the tag.
     */
    private fun applyExif(ctx: Context, uri: Uri, bmp: Bitmap): Bitmap {
        val m = android.graphics.Matrix()
        try {
            val orientation = ctx.contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: return bmp
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                    m.postRotate(90f); m.postScale(-1f, 1f)
                }
                android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                    m.postRotate(270f); m.postScale(-1f, 1f)
                }
                else -> return bmp
            }
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (_: Throwable) {
            return bmp
        }
    }

    /** Best-effort text extraction for documents (plain text formats + rough PDF). */
    private fun extractText(ctx: Context, uri: Uri, mime: String): String {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return "[не удалось прочитать файл]"
            val isPdf = mime == "application/pdf" ||
                String(bytes.copyOfRange(0, minOf(5, bytes.size)), Charsets.ISO_8859_1).startsWith("%PDF")
            if (isPdf) {
                val rough = pdfText(bytes)
                if (rough.isNotBlank()) rough.take(MAX_DOC_CHARS)
                else "[PDF без текстового слоя — содержимое распознать не удалось]"
            } else {
                val s = String(bytes, Charsets.UTF_8)
                // reject binaries: too many control chars means it is not a text document
                val ctrl = s.take(2000).count { it.code < 9 || (it.code in 14..31) }
                if (ctrl > 40) "[бинарный файл, ${bytes.size} байт — текст извлечь нельзя]"
                else s.take(MAX_DOC_CHARS)
            }
        } catch (_: Throwable) { "[не удалось прочитать файл]" }
    }

    /** Extremely small uncompressed-stream PDF text scraper (no external deps). */
    private fun pdfText(bytes: ByteArray): String {
        return try {
            val raw = String(bytes, Charsets.ISO_8859_1)
            val sb = StringBuilder()
            Regex("""\((?:\\.|[^\\()])*\)\s*Tj""").findAll(raw).forEach { m ->
                val t = m.value.substringBeforeLast(')').removePrefix("(")
                sb.append(t.replace("\\(", "(").replace("\\)", ")")).append(' ')
                if (sb.length > MAX_DOC_CHARS) return sb.toString()
            }
            sb.toString().trim()
        } catch (_: Throwable) { "" }
    }

    // ---------------- chat ----------------

    /** Live progress of one streaming request. */
    interface StreamSink {
        /** A short human status: connecting, searching the web, … */
        fun onStatus(text: String)
        /** The model's chain-of-thought, when the provider exposes one. */
        fun onReasoning(delta: String)
        /** A chunk of the visible answer. */
        fun onDelta(delta: String)
        /** Terminal callback — exactly once. */
        fun onDone(result: AiResult)
        /** Polled between chunks; returning false aborts the request. */
        fun isActive(): Boolean

        /**
         * v1.7.99: something the user typed while this answer was still being
         * produced, or null.
         *
         * Called once between rounds of the tool loop and consumed if present —
         * so a follow-up genuinely reaches the model mid-work rather than
         * waiting for the answer to end. Returning a message is a promise that
         * the sink has taken ownership of it: it will not be offered twice.
         *
         * The default keeps every existing sink (swarm members, the agent
         * bridge, the pre-warm probe) working unchanged — only the chat window
         * has a user to hear from.
         */
        fun takePendingUserMessage(): String? = null
    }

    /** What one streaming pass ended with. */
    private class Pass(
        val finish: String,
        val toolCalls: List<ToolCall>,
        val usage: AiUsage?,
        val error: String?
    )

    /**
     * One tool call. [raw] is the provider's ORIGINAL object, kept verbatim.
     *
     * v1.7.43: Gemini attaches a `thought_signature` to each function call and
     * REQUIRES it back on the follow-up turn — rebuilding the call from just
     * id/name/arguments dropped it, and the next request died with
     * «Function call is missing a thought_signature». Anything a provider hangs
     * off a tool call now survives the round trip untouched.
     */
    private class ToolCall(
        val id: String,
        val name: String,
        val args: String,
        val raw: JSONObject? = null
    )

    /**
     * v1.7.26: STREAMING chat — the answer arrives token by token with a live
     * status line instead of a blind three-dot animation.
     *
     * v1.7.30: the model can REACH THE INTERNET (user: «хочу, чтобы помощник
     * мог выйти в интернет, чтобы ответы были точные… спрошу, какое сейчас
     * время, а он не говорит»). Two independent fixes:
     *   - the real date, time and timezone go into every system prompt, so
     *     "сколько сейчас времени" is answered without any network at all;
     *   - two tools, `web_search` and `open_page`, are offered to the model.
     *     When it calls them, Zephyr runs the DuckDuckGo query / fetches the
     *     page, feeds the text back and lets the model answer from it. Up to
     *     [MAX_TOOL_ROUNDS] rounds, then it must answer with what it has.
     *
     * Providers that reject the `tools` field simply get a retry without it.
     */
    fun chatStream(
        history: List<AiMessage>,
        profile: AiProfile,
        pageContext: String,
        sink: StreamSink,
        attempt: Int = 0
    ) {
        // v1.7.55: a blanket "применить всё" belongs to ONE answer. A new
        // question starts with the user being asked again.
        if (attempt == 0) AiWriteReview.beginTurn()

        // v1.7.68: hold the process alive for the length of the request, so
        // minimizing the browser no longer cuts the answer off. Started on the
        // outermost call only; retries and swarm members ride the same hold.
        val host = AiTools.host
        var held = false
        if (attempt == 0 && host != null) {
            try { AiKeepAliveService.begin(host); held = true } catch (_: Throwable) {}
        }
        val release = {
            if (held) {
                held = false
                try { host?.let { AiKeepAliveService.end(it) } } catch (_: Throwable) {}
            }
        }
        // every exit from this function goes through the sink, so the hold is
        // released there rather than at a dozen return statements
        @Suppress("NAME_SHADOWING")
        val sink = if (!held) sink else object : StreamSink {
            override fun onStatus(text: String) = sink.onStatus(text)
            override fun onReasoning(delta: String) = sink.onReasoning(delta)
            override fun onDelta(delta: String) = sink.onDelta(delta)
            override fun isActive(): Boolean = sink.isActive()
            override fun onDone(result: AiResult) {
                release()
                sink.onDone(result)
            }
        }
        // v1.7.36: when the swarm is on, the question goes to the team rather
        // than to whichever single profile happens to be selected.
        if (attempt == 0 && AiSwarm.active()) {
            AiSwarm.run(history, pageContext, sink) { member, memberSink ->
                chatOne(history, member, pageContext, memberSink)
            }
            return
        }
        chatOne(history, profile, pageContext, sink, attempt)
    }

    /** One turn against ONE profile — the unit the swarm schedules. */
    private fun chatOne(
        history: List<AiMessage>,
        profile: AiProfile,
        pageContext: String,
        sink: StreamSink,
        attempt: Int = 0
    ) {
        // v1.7.33: a paired remote agent answers instead of an HTTP provider.
        // No endpoint, no key — the conversation travels to whoever is holding
        // the pairing code. Falls through to a normal model when nobody answers.
        if (profile.endpoint.startsWith(AGENT_SCHEME, true)) {
            askAgent(history, profile, pageContext, sink)
            return
        }
        val answer = StringBuilder()
        // messages produced during the tool loop (assistant tool_calls + results)
        val extra = JSONArray()
        var usage: AiUsage? = null
        // v1.7.97 (user: «агент через эндпоинт не имеет доступа к закладкам,
        // истории, созданию файлов»).
        //
        // This was `Prefs.aiWebAccess` alone — one switch labelled "Internet
        // access" silently decided whether the model was offered ANY tool at
        // all. Turn it off and the assistant also lost bookmarks, history, the
        // workspace and the file tools, none of which touch the internet. From
        // the outside it looked exactly like the provider not supporting tools,
        // which is why it read as an agent problem.
        //
        // Now the web tools answer to the web switch, and everything else to
        // its own toggle; the request carries tools whenever any group is on.
        var toolsAllowed = Prefs.aiWebAccess || AiTools.anyEnabled()

        var round = 0
        // v1.7.99: how much of `answer` has already been copied into `extra`
        // as an assistant turn — see the follow-up injection below.
        var mirrored = 0
        // v1.7.44: every pass is one HTTP call — count them so the cost of an
        // answer is visible instead of showing up later as a rate limit
        var calls = 0
        // v1.9.13: what this answer actually did, so hitting the ceiling can
        // report it instead of telling the user to go and check for themselves
        val usedTools = mutableListOf<String>()
        while (true) {
            if (!sink.isActive()) {
                sink.onDone(AiResult(answer.toString(), true, usage, profile.name, calls)); return
            }

            val pass = try {
                runPass(history, profile, pageContext, sink, extra, answer, toolsAllowed, attempt)
            } catch (t: Throwable) {
                val partial = answer.toString().trim()
                if (partial.isNotEmpty()) {
                    sink.onDone(AiResult(partial, true, usage, profile.name, calls)); return
                }
                sink.onDone(AiResult(networkMessage(t, profile), false, null, profile.name, calls)); return
            }

            calls++
            pass.usage?.let { usage = it }

            if (pass.error != null) {
                // v1.7.51: the provider does not understand the reasoning
                // controls → drop them for this profile and try again, silently
                if (supportsThinking(profile) && profile.thinking != "auto" &&
                    isThinkingRejection(pass.error)
                ) {
                    Prefs.setAiNoThinking(rejected().apply { add(profileKey(profile)) })
                    continue
                }
                // the provider choked on the tools field → retry plainly, once
                if (toolsAllowed && pass.error.contains("tool", true)) {
                    toolsAllowed = false
                    continue
                }
                val partial = answer.toString().trim()
                sink.onDone(
                    if (partial.isNotEmpty()) AiResult(partial, true, usage, profile.name, calls)
                    else AiResult(pass.error, false, null, profile.name, calls)
                )
                return
            }

            // v1.7.58 (user: «дал ту же задачу — „Модель не вернула текст"»).
            //
            // A multi-file job — make a folder, copy files into it, zip it —
            // spends a round per step and hit the ceiling mid-work. The model
            // was then cut off while it was still calling tools, so it had
            // produced no prose at all, and the user got a message blaming the
            // model and suggesting another one. Both wrong: it was our limit,
            // and switching models would not have helped.
            //
            // Now the ceiling ends the TOOLS, not the answer: one final pass
            // runs with the tools withdrawn, which forces the model to reply in
            // words — with what it managed to do and what is left.
            if (pass.toolCalls.isNotEmpty() && round >= MAX_TOOL_ROUNDS && toolsAllowed) {
                toolsAllowed = false
                sink.onStatus(s(R.string.ai_st_summing))
                continue
            }

            // v1.7.99 (user: «как у Claude — не ждать, а отправить ещё одно
            // сообщение, и он подхватит»).
            //
            // Here is the one place where that is genuinely possible. The
            // request in flight cannot be changed — it is already at the
            // provider — but this loop makes a NEW request every round, and a
            // message typed in the meantime can ride along with the next one.
            // So while the assistant is working through tools, a follow-up
            // reaches it mid-task rather than after it.
            //
            // Deliberately before the "are we done" check: if the model was
            // about to stop, the follow-up keeps the same answer going instead
            // of starting a fresh turn, which is what "подхватит" means.
            val followUp = try { sink.takePendingUserMessage() } catch (_: Throwable) { null }
            if (!followUp.isNullOrBlank() && round < MAX_TOOL_ROUNDS) {
                // What the model has already said has to go into the
                // conversation before the follow-up does, or the next request
                // shows it a question with no memory of its own answer — and
                // it starts again from the top. Only the part not already
                // mirrored is added, so a second follow-up cannot duplicate it.
                //
                // `answer` keeps accumulating on purpose: the visible bubble is
                // one answer that carries on, which is the point.
                val said = answer.substring(mirrored).trim()
                if (said.isNotEmpty()) {
                    extra.put(JSONObject().put("role", "assistant").put("content", said))
                    mirrored = answer.length
                }
                extra.put(JSONObject().put("role", "user").put("content", followUp))
                sink.onStatus(s(R.string.ai_st_followup))
                round++
                continue
            }

            if (pass.toolCalls.isEmpty() || round >= MAX_TOOL_ROUNDS) {
                val text = answer.toString().trim()
                sink.onDone(
                    if (text.isBlank())
                        AiResult(
                            // say which of the two it actually was
                            if (round >= MAX_TOOL_ROUNDS) roundLimitMessage(usedTools)
                            else s(R.string.ai_err_no_text),
                            false, null, profile.name, calls
                        )
                    else AiResult(text, true, usage, profile.name, calls, pass.finish == "length")
                )
                return
            }

            // ---- run what the model asked for, then go round again ----
            round++
            val assistantMsg = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            val callsArr = JSONArray()
            for (c in pass.toolCalls) {
                // replay the provider's own object when we have it, so extras
                // like Gemini's thought_signature survive
                val obj = c.raw ?: JSONObject()
                obj.put("id", c.id)
                obj.put("type", obj.optString("type").ifBlank { "function" })
                obj.remove("index")
                val f = obj.optJSONObject("function") ?: JSONObject()
                f.put("name", c.name)
                // v1.7.55: never echo arguments that are not valid JSON. A
                // provider handed a malformed call back rejects the whole
                // request, turning a local parsing slip into a hard 400.
                f.put("arguments", if (isJsonObject(c.args)) c.args else "{}")
                obj.put("function", f)
                callsArr.put(obj)
            }
            assistantMsg.put("tool_calls", callsArr)
            extra.put(assistantMsg)

            for (c in pass.toolCalls) {
                if (!sink.isActive()) {
                    sink.onDone(AiResult(answer.toString(), true, usage, profile.name, calls)); return
                }
                val output = runTool(c, sink)
                usedTools.add(c.name)
                extra.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", c.id)
                        .put("name", c.name)
                        .put("content", output)
                )
                // v1.7.57: a tool result is text, so page_screenshot parks its
                // picture and it is appended here as a real image message —
                // the only shape providers accept for vision.
                AiTools.pendingImage?.let { dataUrl ->
                    AiTools.pendingImage = null
                    extra.put(
                        JSONObject().put("role", "user").put(
                            "content", JSONArray()
                                .put(JSONObject().put("type", "text")
                                    .put("text", "Снимок страницы по запросу инструмента:"))
                                .put(JSONObject().put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", dataUrl)))
                        )
                    )
                }
            }
        }
    }

    /** v1.7.41: page automation legitimately needs more steps than a search. */
    /**
     * v1.7.44: 8 → 4. Every round is a separate API call, so one question with
     * page automation could quietly cost nine requests and bury a free tier's
     * per-minute quota in a single answer.
     */
    /**
     * v1.7.58: 4 → 8. Four was chosen when the tools were read-only — search,
     * read a page, answer. Writing changed the shape of the work: "create a
     * folder, copy five files into it, zip it" is eight steps before a single
     * word is written, so the ceiling was being hit by ordinary requests rather
     * than by runaway ones. Eight still bounds the cost, and hitting it now
     * ends in a summary instead of an error.
     */
    /**
     * How many tool rounds one answer may spend.
     *
     * v1.9.13 (user: «открой гугл фр — открывает сразу; найди сайт где
     * посмотреть фильм и открой — упирается в 8 шагов»).
     *
     * Eight was enough for a single action and too few for anything that has
     * to look something up first. A search, three or four pages read to judge
     * them, another search, then the open — that is already at the ceiling,
     * and the answer died one step short of doing the thing it was asked to
     * do. The prompt now also steers the model away from reading pages it only
     * needs to open (see buildBody), so the extra room is a margin rather than
     * the fix.
     */
    private const val MAX_TOOL_ROUNDS = 14

    /** True when [s] is a single well-formed JSON object — what an arguments field must be. */
    private fun isJsonObject(s: String): Boolean = try {
        JSONObject(s); true
    } catch (_: Throwable) { false }

    /** Execute one tool call and return the text handed back to the model. */
    private fun runTool(c: ToolCall, sink: StreamSink): String = try {
        val args = try { JSONObject(c.args) } catch (_: Throwable) { JSONObject() }
        when (c.name) {
            "web_search" -> {
                val q = args.optString("query").ifBlank { args.optString("q") }
                if (q.isBlank()) "Пустой запрос." else {
                    sink.onStatus(s(R.string.ai_st_searching, q.take(40)))
                    AiWebSearch.format(AiWebSearch.search(q, 5))
                }
            }
            "open_page" -> {
                val u = args.optString("url")
                if (u.isBlank()) "Не указан адрес." else {
                    sink.onStatus(s(R.string.ai_st_reading))
                    AiWebSearch.readPage(u)
                }
            }
            else -> {
                // v1.7.41: everything the browser itself can do lives in AiTools
                if (AiTools.handles(c.name)) AiTools.run(c.name, args) { status -> sink.onStatus(status) }
                else "Неизвестный инструмент: " + c.name
            }
        }
    } catch (t: Throwable) {
        "Инструмент не отработал: " + (t.localizedMessage ?: t.javaClass.simpleName)
    }

    /** One HTTP request + stream read. Returns how the pass ended. */
    private fun runPass(
        history: List<AiMessage>,
        profile: AiProfile,
        pageContext: String,
        sink: StreamSink,
        extra: JSONArray,
        answer: StringBuilder,
        toolsAllowed: Boolean,
        attempt: Int
    ): Pass {
        var conn: HttpURLConnection? = null
        try {
            val target = profile.requestUrl
            if (answer.isEmpty() && extra.length() == 0) {
                sink.onStatus(s(R.string.ai_st_connecting, profile.name))
            }
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", if (Prefs.aiStreaming) "text/event-stream" else "application/json")
                if (profile.key.isNotEmpty()) setRequestProperty("Authorization", "Bearer " + profile.key)
                setRequestProperty("HTTP-Referer", "https://zephyr.browser")
                setRequestProperty("X-Title", "Zephyr Browser")
                connectTimeout = 25000
                readTimeout = 300000
                doOutput = true
            }

            val body = buildBody(
                trimHistory(history), profile, pageContext,
                stream = Prefs.aiStreaming, extra = extra, withTools = toolsAllowed
            )
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            // v1.7.52: «Подключаюсь» used to cover connect, upload AND the wait
            // for headers, so a slow provider looked like a network problem.
            // The stages are now distinct.
            if (answer.isEmpty() && extra.length() == 0) sink.onStatus(s(R.string.ai_st_waiting))

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = BufferedReader(
                    InputStreamReader(conn.errorStream ?: conn.inputStream, "UTF-8")
                ).use { it.readText() }
                // v1.7.28: honour the provider's own Retry-After once
                val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
                if (code == 429 && retryAfter != null && retryAfter in 1..60 && attempt == 0) {
                    conn.disconnect(); conn = null
                    sink.onStatus(s(R.string.ai_st_retry, retryAfter))
                    var waited = 0
                    while (waited < retryAfter && sink.isActive()) { Thread.sleep(1000); waited++ }
                    if (!sink.isActive()) return Pass("stop", emptyList(), null, null)
                    return runPass(history, profile, pageContext, sink, extra, answer, toolsAllowed, attempt + 1)
                }
                return Pass("error", emptyList(), null, friendlyError(code, err, target, profile))
            }

            if (answer.isEmpty()) sink.onStatus(s(R.string.ai_st_thinking))
            val isSse = (conn.contentType ?: "").contains("event-stream", true)
            val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))

            if (!isSse) {
                val raw = reader.use { it.readText() }
                val obj = try { JSONObject(raw) } catch (_: Throwable) { null }
                val calls = obj?.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optJSONArray("tool_calls")
                if (calls != null && calls.length() > 0) {
                    return Pass("tool_calls", parseToolCalls(calls), parseUsage(obj.optJSONObject("usage")), null)
                }
                val text = parseReply(raw)
                if (text.isNotBlank()) { answer.append(text); sink.onDelta(text) }
                return Pass("stop", emptyList(), parseUsage(obj?.optJSONObject("usage")), null)
            }

            var gotFirst = answer.isNotEmpty()
            var reasoningSeen = false
            var finish = "stop"
            var passUsage: AiUsage? = null
            // tool calls stream in fragments, keyed by index
            // index -> (id, name, arguments, the provider's merged raw object)
            val toolAcc = LinkedHashMap<Int, Array<Any>>()
            // v1.7.55 FIX (user: «создай два файла .md» → 400 INVALID_ARGUMENT on
            // Gemini, fine on DeepSeek).
            //
            // The slot key was `t.optInt("index", i)` — the position inside THIS
            // delta's array when the provider omits `index`. Gemini's
            // OpenAI-compatible stream does omit it and sends each call in its
            // own chunk, alone, so every call came back as position 0 and the
            // second one was merged into the first: name "write_filewrite_file",
            // arguments two concatenated JSON objects. One file worked; two
            // never could. The malformed call was then echoed back to the
            // provider on the next round, which is where the 400 came from.
            //
            // So: trust `index` when it is there, otherwise give each distinct
            // call id its own slot, and treat an id-less fragment as a
            // continuation of the call it is following.
            val idSlots = HashMap<String, Int>()
            var nextSlot = 0
            var lastSlot = -1

            reader.use { r ->
                while (true) {
                    if (!sink.isActive()) return Pass("stop", emptyList(), passUsage, null)
                    val line = r.readLine() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    try {
                        val obj = JSONObject(payload)
                        parseUsage(obj.optJSONObject("usage"))?.let { passUsage = it }
                        val choices = obj.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val c0 = choices.getJSONObject(0)
                        c0.optString("finish_reason").takeIf { it.isNotBlank() }?.let { finish = it }
                        val delta = c0.optJSONObject("delta") ?: c0.optJSONObject("message") ?: continue

                        delta.optJSONArray("tool_calls")?.let { tc ->
                            for (i in 0 until tc.length()) {
                                val t = tc.optJSONObject(i) ?: continue
                                val callId = t.optString("id")
                                val idx = when {
                                    t.has("index") -> t.optInt("index").also { n ->
                                        if (callId.isNotBlank()) idSlots[callId] = n
                                        if (n >= nextSlot) nextSlot = n + 1
                                    }
                                    callId.isNotBlank() -> idSlots.getOrPut(callId) { nextSlot++ }
                                    lastSlot >= 0 -> lastSlot
                                    else -> nextSlot++
                                }
                                lastSlot = idx
                                val slot = toolAcc.getOrPut(idx) {
                                    arrayOf(StringBuilder(), StringBuilder(), StringBuilder(), JSONObject())
                                }
                                val sid = slot[0] as StringBuilder
                                val sname = slot[1] as StringBuilder
                                val sargs = slot[2] as StringBuilder
                                val sraw = slot[3] as JSONObject
                                // merge every field the provider sends, not just ours
                                val keys = t.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    if (k != "function" && k != "index") sraw.put(k, t.get(k))
                                }
                                // an id arrives whole, in one chunk — but some
                                // providers repeat it in every chunk, and
                                // appending would build "call_1call_1call_1"
                                if (sid.isEmpty() && callId.isNotBlank()) sid.append(callId)
                                t.optJSONObject("function")?.let { f ->
                                    val fk = f.keys()
                                    val existing = sraw.optJSONObject("function") ?: JSONObject()
                                    while (fk.hasNext()) {
                                        val k = fk.next()
                                        if (k != "name" && k != "arguments") existing.put(k, f.get(k))
                                    }
                                    sraw.put("function", existing)
                                    // same for the name: repeated in full by some
                                    // providers, streamed in pieces by none seen
                                    // so far — so ignore an exact repeat
                                    f.optString("name").takeIf { it.isNotBlank() }?.let {
                                        if (sname.toString() != it) sname.append(it)
                                    }
                                    f.optString("arguments").takeIf { it.isNotEmpty() }?.let { sargs.append(it) }
                                }
                            }
                        }

                        val reason = delta.optString("reasoning_content")
                            .ifBlank { delta.optString("reasoning") }
                        if (reason.isNotBlank()) {
                            if (!reasoningSeen) { reasoningSeen = true; sink.onStatus(s(R.string.ai_st_reasoning)) }
                            sink.onReasoning(reason)
                        }

                        // v1.9.7: the standard in-band media channel.
                        //
                        // Providers that generate pictures or speech return them
                        // inside the choice, not in the prose: `delta.images`
                        // (OpenAI-compatible bridges, OpenRouter) and
                        // `delta.audio` (the audio preview models). Until now
                        // this client read neither, so a bridge had to print the
                        // URL into the answer for anything to appear — which is
                        // why the chat was full of raw links.
                        //
                        // The URL is appended to the answer rather than streamed
                        // as visible text: it persists with the message, the
                        // existing media poller finds it, and the renderer
                        // strips it back out before display. The user sees the
                        // picture; nobody sees the plumbing.
                        mediaUrlsIn(delta).forEach { u ->
                            if (!answer.contains(u)) answer.append('\n').append(u)
                        }

                        val piece = when (val content: Any? = delta.opt("content")) {
                            is String -> content
                            is JSONArray -> (0 until content.length())
                                .mapNotNull { content.optJSONObject(it)?.optString("text") }
                                .joinToString("")
                            else -> ""
                        }
                        if (piece.isNotEmpty()) {
                            // v1.9.12: the bare newlines a model emits before a
                            // tool call are not typing. Announcing "Печатает…"
                            // on them put the status at odds with the empty
                            // bubble underneath it.
                            if (!gotFirst && piece.isNotBlank()) {
                                gotFirst = true; sink.onStatus(s(R.string.ai_st_typing))
                            }
                            answer.append(piece)
                            sink.onDelta(piece)
                        }
                    } catch (_: Throwable) { /* keep-alive or partial frame */ }
                }
            }

            val calls = toolAcc.values.mapNotNull { slot ->
                val n = (slot[1] as StringBuilder).toString()
                if (n.isBlank()) null else ToolCall(
                    (slot[0] as StringBuilder).toString().ifBlank { "call_" + n },
                    n,
                    (slot[2] as StringBuilder).toString().ifBlank { "{}" },
                    slot[3] as JSONObject
                )
            }
            return Pass(finish, calls, passUsage, null)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parseToolCalls(arr: JSONArray): List<ToolCall> {
        val out = ArrayList<ToolCall>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val f = o.optJSONObject("function") ?: continue
            val name = f.optString("name")
            if (name.isBlank()) continue
            out.add(
                ToolCall(
                    o.optString("id").ifBlank { "call_$i" }, name,
                    f.optString("arguments").ifBlank { "{}" }, o
                )
            )
        }
        return out
    }

    private fun networkMessage(t: Throwable, profile: AiProfile): String = when (t) {
        is java.net.SocketTimeoutException -> s(R.string.ai_err_timeout, profile.model)
        else -> s(R.string.ai_err_offline, t.localizedMessage ?: t.javaClass.simpleName)
    }

    /** The two functions the model may call. */
    private fun toolsSpec(): JSONArray {
        val search = JSONObject()
            .put("type", "function")
            .put(
                "function", JSONObject()
                    .put("name", "web_search")
                    .put(
                        "description",
                        "Ищет в интернете через DuckDuckGo. Используй для всего, что могло " +
                            "измениться после обучения: новости, курсы, погода, расписания, цены, " +
                            "факты о текущих событиях. Возвращает заголовки, адреса и выдержки."
                    )
                    .put(
                        "parameters", JSONObject()
                            .put("type", "object")
                            .put(
                                "properties", JSONObject().put(
                                    "query", JSONObject()
                                        .put("type", "string")
                                        .put("description", "Поисковый запрос")
                                )
                            )
                            .put("required", JSONArray().put("query"))
                    )
            )
        val open = JSONObject()
            .put("type", "function")
            .put(
                "function", JSONObject()
                    .put("name", "open_page")
                    .put(
                        "description",
                        "Открывает конкретный адрес и возвращает текст страницы. " +
                            "Используй, когда выдержки из поиска мало и нужны подробности."
                    )
                    .put(
                        "parameters", JSONObject()
                            .put("type", "object")
                            .put(
                                "properties", JSONObject().put(
                                    "url", JSONObject()
                                        .put("type", "string")
                                        .put("description", "Полный адрес страницы")
                                )
                            )
                            .put("required", JSONArray().put("url"))
                    )
            )
        val arr = JSONArray()
        // v1.7.97: the two web tools now belong to the web switch alone. They
        // used to be unconditional, which is how one toggle came to govern the
        // lot — see the note on `toolsAllowed`.
        if (Prefs.aiWebAccess) { arr.put(search); arr.put(open) }
        // v1.7.41: browser / page / personal-data tools ride alongside the
        // web ones, gated by the user's own toggles
        val extra = AiTools.specs()
        for (i in 0 until extra.length()) arr.put(extra.get(i))
        return arr
    }

    /**
     * v1.7.52: open the TLS connection before the user presses send.
     *
     * The first request to a host pays for DNS plus a full TLS handshake —
     * noticeable on mobile, and it lands inside the «Подключаюсь…» stage where
     * it reads as the model being slow. Touching the host when the chat opens
     * gets that out of the way, so the first real question starts warm.
     *
     * Deliberately cheap and silent: one HEAD, short timeouts, every failure
     * ignored. It is an optimisation, never a dependency.
     */
    fun prewarm(profile: AiProfile) {
        if (profile.endpoint.startsWith(AGENT_SCHEME, true)) return
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(profile.requestUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Zephyr")
                }
                conn.responseCode
            } catch (_: Throwable) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    // ---------------- reasoning effort ----------------

    /**
     * v1.7.53: models known to reason, the way Roo Code and friends do it.
     *
     * Those tools keep a capability registry and update it with each release —
     * there is no runtime way to ask. Zephyr does the same, but only to SET
     * EXPECTATIONS: the control stays available for everything, because the
     * same model name behaves differently between deployments and a hardcoded
     * list goes stale in weeks. A name on this list means "the setting will
     * very likely work"; anything else falls back silently if it does not.
     */
    private val REASONING_HINTS = listOf(
        "o1", "o3", "o4", "gpt-5", "deepseek-r", "deepseek-v3",
        "qwen3", "qwq", "nemotron", "magistral", "glm-4.5", "glm-4.6", "glm-5",
        "gemini-2.5", "gemini-3", "claude-3-7", "claude-4", "sonnet-4", "opus-4",
        "thinking", "reasoner", "-r1"
    )

    /** Best-effort guess used only to label the picker. */
    fun looksLikeReasoningModel(model: String): Boolean {
        val m = model.lowercase()
        return REASONING_HINTS.any { m.contains(it) }
    }

    /**
     * v1.7.51: profiles whose provider rejected the thinking parameters.
     *
     * There is no reliable way to ask a provider whether it understands
     * `reasoning_effort` or `chat_template_kwargs` — the catalogue does not say,
     * and the same model name behaves differently between deployments. So the
     * fields are sent, and a 400 that names them is treated as "this one does
     * not support it": the request is retried clean and the profile is
     * remembered for the rest of the session. The user never sees the failure.
     */
    // v1.7.53: persisted, so the one wasted request happens ONCE per profile
    // ever — not once per app start.
    private fun rejected(): MutableSet<String> = Prefs.aiNoThinking.toMutableSet()

    private fun profileKey(p: AiProfile) = p.endpoint.lowercase() + "|" + p.model.lowercase()

    fun supportsThinking(p: AiProfile) = profileKey(p) !in Prefs.aiNoThinking

    /** True when the error text is the provider complaining about those fields. */
    private fun isThinkingRejection(detail: String): Boolean {
        val d = detail.lowercase()
        return d.contains("reasoning_effort") || d.contains("chat_template_kwargs") ||
            d.contains("enable_thinking") || d.contains("thinking")
    }

    /** Add the reasoning controls this profile asked for. */
    private fun applyThinking(body: JSONObject, p: AiProfile) {
        if (!supportsThinking(p)) return
        when (p.thinking.lowercase()) {
            "off" -> {
                // NVIDIA NIM / Qwen style, plus the bare vLLM flag
                body.put("chat_template_kwargs", JSONObject().put("thinking", false))
                body.put("enable_thinking", false)
            }
            "low", "medium", "high" -> {
                body.put("reasoning_effort", p.thinking.lowercase())
                body.put("chat_template_kwargs", JSONObject().put("thinking", true))
            }
            // "auto" — send nothing and let the model decide
        }
    }

    /** Shared request body for both the streaming and the blocking call. */
    private fun buildBody(
        history: List<AiMessage>,
        profile: AiProfile,
        pageContext: String,
        stream: Boolean,
        extra: JSONArray? = null,
        withTools: Boolean = false
    ): JSONObject {
        val messages = JSONArray()
        // v1.7.48: a profile may carry its own instructions; blank falls back
        // to the global prompt
        val sys = StringBuilder(profile.prompt.ifBlank { Prefs.aiPrompt })

        // v1.7.30: a model has no clock. Give it one.
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale("ru"))
        val tz = java.util.TimeZone.getDefault()
        sys.append("\n\nСейчас: ").append(fmt.format(now))
            .append(" (часовой пояс устройства: ").append(tz.id).append(").")
            .append(" Отвечая про дату, время или «сегодня», опирайся на это значение.")

        // v1.7.57: what the assistant knows about this user, carried across chats
        sys.append(AiMemory.promptBlock())

        // v1.7.70: saved templates, named so a request like «сделай devis»
        // finds the user's own layout instead of inventing a new one
        if (withTools) {
            val tpl = try { AiTemplates.all() } catch (_: Throwable) { emptyList() }
            if (tpl.isNotEmpty()) {
                sys.append("\n\nУ пользователя есть свои шаблоны документов: ")
                sys.append(tpl.joinToString(", ") { it.name })
                sys.append(". Если просят такой документ — заполняй ЕГО через use_template, ")
                sys.append("а не сочиняй новый. Реквизиты, дату и номер подставляй из business_profile.")
            }
        }

        // v1.9.10 (user: «резко перестал выходить в интернет и открывать
        // вкладки — видимо мы испортили»).
        //
        // This paragraph used to promise web_search and open_page whenever ANY
        // tool was allowed, and it never named open_tab at all. After v1.7.97
        // split the web switch from the rest, prompt and array stopped
        // matching: with "Интернет" off the model was still told it could
        // search, tried to, and reported a failure it could not explain —
        // while the tool that WOULD have worked, open_tab, sat in the array
        // unmentioned, so the model fell back to pasting a link for the user
        // to open by hand.
        //
        // The prompt now describes exactly the tools that were put in the
        // array, and nothing else.
        if (withTools) {
            if (Prefs.aiWebAccess) {
                sys.append("\n\nУ тебя есть инструменты. web_search и open_page — интернет: ")
                    .append("если ответ зависит от свежих данных или ты не уверен, сначала ищи, ")
                    .append("потом отвечай по найденному и указывай источники ссылками.")
            }
            if (AiTools.host != null) {
                if (Prefs.aiToolsBrowser) {
                    sys.append("\n\nТы управляешь браузером пользователя. С вкладками: ")
                        .append("open_tab — открыть адрес в новой вкладке, browser_search — ")
                        .append("поискать в браузере, list_tabs — посмотреть открытые вкладки, ")
                        .append("close_tab — закрыть по номеру. Когда просят что-то открыть — ")
                        .append("вызывай open_tab, а не присылай ссылку текстом.")
                    // v1.9.13: «найди сайт и открой» кончалось потолком шагов —
                    // модель читала страницы-кандидаты через open_page, чтобы
                    // «убедиться», и до open_tab уже не доходила. Открыть
                    // страницу пользователю и прочитать её себе — разные задачи.
                    sys.append("\n\nЕсли просят ОТКРЫТЬ сайт или страницу — не читай ")
                        .append("её через open_page. Хватит одного web_search: бери лучший ")
                        .append("результат и сразу вызывай open_tab. open_page нужен только ")
                        .append("когда тебе самому нужно содержимое, чтобы ответить словами. ")
                        .append("И не объявляй о намерении — просто делай и потом скажи, что сделал.")
                }
                if (Prefs.aiToolsPage) {
                    sys.append("\n\nСо страницей: ")
                        .append("page_text — прочитать, page_extract — собрать данные по CSS-селектору, ")
                        .append("page_scroll — подгрузить ленивую ленту, page_click и page_fill — ")
                        .append("нажать и заполнить. Действуй по шагам: открой, прокрути, извлеки, ")
                        .append("и только потом отвечай.")
                }
                if (Prefs.aiToolsBrowser || Prefs.aiToolsPage || Prefs.aiToolsData ||
                    Prefs.aiToolsScripts
                ) {
                    sys.append("\n\nДействия, которые что-то меняют, пользователь ")
                        .append("подтверждает вручную — если он отказал, не повторяй попытку, ")
                        .append("а скажи об этом.")
                }
            }
        }
        if (pageContext.isNotBlank() && Prefs.aiSendPageContext) {
            sys.append("\n\nКонтекст открытой страницы (используй при необходимости):\n")
                .append(pageContext.take(4000))
        }
        sys.append("\n\nФорматируй ответы в Markdown. Ссылки давай полностью (https://…).")

        // v1.7.88: the language of a DOCUMENT is not the language of the chat.
        // The common case in this app is exactly where they differ — a
        // Russian-speaking autoentrepreneur in France writing a French devis
        // from a Russian interface. The model is told to read that from the
        // context instead of defaulting to whatever the user happens to type.
        sys.append("\n\nЯзык интерфейса пользователя: ").append(AiLang.name())
            .append(". Отвечай в чате на языке пользователя. Но язык ДОКУМЕНТА, ")
            .append("который ты создаёшь (резюме, devis, счёт, письмо), определяй по тому, ")
            .append("кто его будет читать: по языку вакансии, клиента или страны. ")
            .append("Если это очевидно из разговора — ставь параметр lang соответственно ")
            .append("и весь текст документа пиши на этом языке. Если непонятно — ")
            .append("бери язык интерфейса. При сомнении спроси одной короткой фразой.")
        messages.put(JSONObject().put("role", "system").put("content", sys.toString()))

        var hasImage = false
        // v1.9.9 (user: «скину фото с текстом, и через три сообщения он снова
        // пишет „увидел фотографию" и только потом отвечает»).
        //
        // Every past image was re-sent in full on every turn. That is what a
        // vision chat is supposed to do for the CURRENT picture, but three
        // turns later the model is still receiving the same photograph as if it
        // had just arrived — and a talkative one greets it again before getting
        // to the question.
        //
        // It is also expensive in a way nothing on screen shows: a photograph is
        // tens of thousands of base64 characters, re-uploaded with every single
        // message for the rest of the conversation. That is the user's data
        // allowance and their provider bill.
        //
        // Only the most recent message that carries pictures still sends them.
        // Older ones keep a short note in their text, so the model knows a
        // picture was there and can ask for it again rather than pretending it
        // never existed.
        val lastImageTurn = history.indexOfLast { m ->
            !m.error && m.attachments.any { it.isImage && it.dataUrl != null }
        }
        for ((idx, m) in history.withIndex()) {
            if (m.error) continue
            val imgs =
                if (idx == lastImageTurn) m.attachments.filter { it.isImage && it.dataUrl != null }
                else emptyList()
            val docs = m.attachments.filter { !it.isImage }
            val textPart = StringBuilder(m.text)
            // a picture this turn no longer carries: say so rather than let the
            // message read as if there had never been one
            val droppedImages = if (idx == lastImageTurn) 0
            else m.attachments.count { it.isImage && it.dataUrl != null }
            if (droppedImages > 0) {
                textPart.append(
                    if (droppedImages == 1) "\n\n[an image was attached to this message earlier in the chat]"
                    else "\n\n[$droppedImages images were attached to this message earlier in the chat]"
                )
            }
            for (d in docs) {
                textPart.append("\n\n--- Вложенный документ: ").append(d.name)
                    .append(" (").append(d.mime).append(") ---\n")
                    .append(d.text ?: "").append("\n--- конец документа ---")
            }
            if (imgs.isEmpty()) {
                messages.put(JSONObject().put("role", m.role).put("content", textPart.toString()))
            } else {
                hasImage = true
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", textPart.toString()))
                for (img in imgs) {
                    parts.put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", img.dataUrl))
                    )
                }
                messages.put(JSONObject().put("role", m.role).put("content", parts))
            }
        }
        // the loop needs more room when the model is driving the browser
        // tool-loop messages ride at the end, in order
        if (extra != null) for (i in 0 until extra.length()) messages.put(extra.get(i))

        val body = JSONObject()
            .put("model", profile.model)
            .put("messages", messages)
            .put("temperature", Prefs.aiTemperature / 100.0)
            .put("max_tokens", if (hasImage) minOf(Prefs.aiMaxTokens, 1536) else Prefs.aiMaxTokens)
            .put("stream", stream)
        if (stream) body.put("stream_options", JSONObject().put("include_usage", true))
        applyThinking(body, profile)
        if (withTools) {
            // v1.7.97: an EMPTY tools array is not the same as no tools field —
            // several providers reject `"tools": []` outright, and the failure
            // would land in the "provider choked on tools" branch and look like
            // a broken model. Send the field only when there is something in it.
            val spec = toolsSpec()
            if (spec.length() > 0) {
                body.put("tools", spec)
                body.put("tool_choice", "auto")
            }
        }
        return body
    }

    /** v1.7.27: only the last N turns are replayed (Settings → глубина истории). */
    private fun trimHistory(history: List<AiMessage>): List<AiMessage> {
        val depth = Prefs.aiHistoryDepth.coerceAtLeast(2)
        return if (history.size <= depth) history else history.subList(history.size - depth, history.size)
    }

    private fun parseUsage(o: JSONObject?): AiUsage? {
        val u = o ?: return null
        val total = u.optInt("total_tokens", 0)
        val prompt = u.optInt("prompt_tokens", 0)
        val completion = u.optInt("completion_tokens", 0)
        return if (total > 0 || prompt > 0 || completion > 0)
            AiUsage(prompt, completion, if (total > 0) total else prompt + completion)
        else null
    }

    /** Blocking one-shot call (kept for non-interactive callers). */
    fun chat(history: List<AiMessage>, profile: AiProfile, pageContext: String = ""): AiResult {
        var conn: HttpURLConnection? = null
        return try {
            val target = profile.requestUrl
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (profile.key.isNotEmpty()) setRequestProperty("Authorization", "Bearer " + profile.key)
                setRequestProperty("HTTP-Referer", "https://zephyr.browser")
                setRequestProperty("X-Title", "Zephyr Browser")
                connectTimeout = 25000
                readTimeout = 300000
                doOutput = true
            }
            val body = buildBody(trimHistory(history), profile, pageContext, stream = false)
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code in 200..299) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val usage = try { parseUsage(JSONObject(resp).optJSONObject("usage")) } catch (_: Throwable) { null }
                AiResult(parseReply(resp), true, usage)
            } else {
                val err = BufferedReader(
                    InputStreamReader(conn.errorStream ?: conn.inputStream, "UTF-8")
                ).use { it.readText() }
                AiResult(friendlyError(code, err, target, profile), false)
            }
        } catch (t: Throwable) {
            AiResult("⚠️ Нет связи с ИИ-сервисом.\n" + (t.localizedMessage ?: t.javaClass.simpleName), false)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parseReply(resp: String): String {
        return try {
            val json = JSONObject(resp)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val c = choices.getJSONObject(0)
                val msg = c.optJSONObject("message")
                val content: Any? = msg?.opt("content")
                val text = when (content) {
                    is String -> content
                    is JSONArray -> (0 until content.length())
                        .mapNotNull { content.optJSONObject(it)?.optString("text") }
                        .joinToString("")
                    else -> c.optString("text")
                }
                val reasoning = msg?.optString("reasoning_content").orEmpty()
                // v1.9.7: the same in-band media channel as the streaming path —
                // message.images / message.audio. Appended, not shown; the
                // renderer strips it back out before the bubble is drawn.
                val media = if (msg != null) mediaUrlsIn(msg) else emptyList()
                val withMedia = { t: String ->
                    if (media.isEmpty()) t
                    else (t.trimEnd() + "\n" + media.joinToString("\n")).trim()
                }
                val bodyText = text.orEmpty().trim()
                if (bodyText.isBlank() && reasoning.isNotBlank()) withMedia(reasoning.trim())
                else withMedia(bodyText).ifBlank { s(R.string.ai_err_empty) }
            } else {
                // Ollama native shape
                val m = json.optJSONObject("message")?.optString("content")?.trim()
                if (!m.isNullOrBlank()) m
                else json.optString("response").trim().ifBlank { s(R.string.ai_err_empty) }
            }
        } catch (_: Throwable) { resp.take(1200) }
    }

    /** Turn raw provider errors into something actionable instead of «404 page not found». */
    private fun friendlyError(code: Int, raw: String, target: String, p: AiProfile): String {
        var detail = try {
            val j = JSONObject(raw)
            val fromError = j.optJSONObject("error")?.optString("message").orEmpty()
            if (fromError.isNotBlank()) fromError
            else j.optString("detail").ifBlank { j.optString("message") }
        } catch (_: Throwable) { "" }
        if (detail.isBlank()) detail = raw.trim().take(300)

        return when (code) {
            401, 403 -> s(R.string.ai_err_key, code, p.name, detail)
            404 -> s(R.string.ai_err_endpoint, target, p.model, detail)
            // v1.7.43: lead with what the provider ACTUALLY said. The old text
            // guessed «неверное имя модели» and sent the user chasing the wrong
            // thing when the real complaint was about tool metadata.
            400, 422 -> s(R.string.ai_err_rejected, code, detail, p.model)
            429 -> s(R.string.ai_err_rate, detail)
            in 500..599 -> s(R.string.ai_err_provider, code, detail)
            else -> s(R.string.ai_err_http, code, detail)
        }
    }

    /** Marks a profile whose "provider" is a paired agent, not an HTTP API. */
    const val AGENT_SCHEME = "agent://"

    /**
     * v1.7.33: route one turn through [AgentLink].
     *
     * The agent gets the same system prompt and the same trimmed history an
     * HTTP provider would, so nothing else in the assistant has to care which
     * one is answering. If the agent stays silent past the timeout and the user
     * left the fallback on, the newest configured HTTP profile finishes the job
     * and the reply says who actually answered.
     */
    private fun askAgent(
        history: List<AiMessage>,
        profile: AiProfile,
        pageContext: String,
        sink: StreamSink
    ) {
        val body = buildBody(trimHistory(history), profile, pageContext, stream = false)
        val system = body.optJSONArray("messages")?.optJSONObject(0)?.optString("content").orEmpty()
        val msgs = JSONArray()
        val all = body.optJSONArray("messages") ?: JSONArray()
        for (i in 1 until all.length()) {
            val m = all.optJSONObject(i) ?: continue
            // attachments are already inlined as text by buildBody; images are
            // dropped here — a remote agent has no way to fetch a data: url cheaply
            val content = m.opt("content")
            msgs.put(
                JSONObject()
                    .put("role", m.optString("role"))
                    .put("content", if (content is String) content else m.optString("content"))
            )
        }

        val answer = AgentLink.ask(
            system = system,
            messages = msgs,
            onStatus = { sink.onStatus(it) },
            onDelta = { sink.onDelta(it) },
            isActive = { sink.isActive() }
        )

        if (answer.ok) {
            sink.onDone(AiResult(answer.text, true, null, profile.name, 1))
            return
        }
        // inside a swarm the team already provides the fallback — do not nest one
        if (!answer.fellThrough || !Prefs.agentFallback || AiSwarm.active()) {
            sink.onDone(AiResult(answer.text, false, null, profile.name, 1))
            return
        }

        val backup = AiModels.list().firstOrNull {
            !it.endpoint.startsWith(AGENT_SCHEME, true) && AiModels.isConfigured(it)
        }
        if (backup == null) {
            sink.onDone(
                AiResult(
                    answer.text + "\n\n" + s(R.string.ai_err_no_backup),
                    false
                )
            )
            return
        }
        sink.onStatus(s(R.string.ai_st_agent_silent, backup.name))
        chatOne(history, backup, pageContext, object : StreamSink {
            override fun onStatus(text: String) = sink.onStatus(text)
            override fun onReasoning(delta: String) = sink.onReasoning(delta)
            override fun onDelta(delta: String) = sink.onDelta(delta)
            override fun isActive(): Boolean = sink.isActive()
            override fun onDone(result: AiResult) {
                // v1.7.44: the credit line is gone from the TEXT — who answered
                // is now a proper field, shown under every reply
                sink.onDone(result.copy(via = backup.name))
            }
        })
    }

    // ---------------- media probing ----------------

    enum class MediaKind { IMAGE, AUDIO, PENDING, NONE }

    class MediaProbe(val kind: MediaKind, val contentType: String = "")

    /**
     * v1.7.46: what is behind this link — a picture, a sound, or nothing yet?
     *
     * A bridge that generates media asynchronously answers with a link before
     * the file exists. Until it does, the url returns 404/202/an empty body or
     * an HTML "still drawing" page. Those cases are reported as PENDING so the
     * chat can keep a placeholder up and try again, instead of deciding once
     * and giving up.
     */
    fun probeMedia(url: String): MediaProbe {
        if (url.startsWith("data:image", true)) return MediaProbe(MediaKind.IMAGE)
        if (url.startsWith("data:audio", true)) return MediaProbe(MediaKind.AUDIO)
        if (!url.startsWith("http", true)) return MediaProbe(MediaKind.NONE)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 12000
                // ask for the first byte only — enough for the headers
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Zephyr")
            }
            val code = conn.responseCode
            val type = (conn.contentType ?: "").lowercase()
            when {
                // v1.7.53: "not there yet" only counts for urls that actually
                // look like generated files. Any other 404 is just a 404, and
                // waiting three minutes on it was pure noise.
                (code == 404 || code == 202 || code == 204 || code == 425) ->
                    if (looksLikeMediaUrl(url) || url.substringBefore('?').contains('.', true) &&
                        Regex(""".w{2,4}$""").containsMatchIn(url.substringBefore('?'))
                    ) MediaProbe(MediaKind.PENDING, type) else MediaProbe(MediaKind.NONE, type)
                code !in 200..299 -> MediaProbe(MediaKind.NONE, type)
                type.startsWith("image/") -> MediaProbe(MediaKind.IMAGE, type)
                type.startsWith("audio/") || type.contains("ogg") || type.contains("wav") ->
                    MediaProbe(MediaKind.AUDIO, type)
                // an HTML waiting page on a link that smells like generated
                // media — the file is coming, keep watching
                type.contains("html") && looksLikeMediaUrl(url) -> MediaProbe(MediaKind.PENDING, type)
                else -> MediaProbe(MediaKind.NONE, type)
            }
        } catch (_: Throwable) {
            MediaProbe(MediaKind.NONE)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Media carried inside a choice, as URLs.
     *
     * v1.9.7. Handles both shapes a delta or a message can use:
     *
     *   images: [{ type: "image_url", image_url: { url: "https://…" } }]
     *   images: ["https://…"]                       (the short form some send)
     *   audio:  { url: "https://…" }
     *   audio:  { data: "<base64 wav>", … }         (bytes, no URL)
     *
     * Base64 audio is written to the cache and returned as a `file://` URL, so
     * everything downstream — the poller, the player — deals with one kind of
     * thing. A model that streams audio in chunks would rewrite the same file,
     * which is why the id is part of the name.
     */
    private fun mediaUrlsIn(node: JSONObject): List<String> {
        val out = ArrayList<String>(2)
        try {
            node.optJSONArray("images")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (val item: Any? = arr.opt(i)) {
                        is String -> item.takeIf { it.isNotBlank() }?.let { out.add(it) }
                        is JSONObject -> {
                            val u = item.optJSONObject("image_url")?.optString("url")
                                ?: item.optString("url")
                            if (!u.isNullOrBlank()) out.add(u)
                        }
                    }
                }
            }
            node.optJSONObject("audio")?.let { a ->
                val u = a.optString("url")
                if (u.isNotBlank()) out.add(u)
                else a.optString("data").takeIf { it.isNotBlank() }?.let { b64 ->
                    cacheAudio(a.optString("id").ifBlank { b64.hashCode().toString() }, b64)
                        ?.let { out.add(it) }
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    /** Decode base64 audio into the cache; returns a file:// url, or null. */
    private fun cacheAudio(id: String, base64: String): String? = try {
        val ctx = com.zephyr.browser.App.ctx
        if (ctx == null) null else {
            val dir = java.io.File(ctx.cacheDir, "ai-audio").apply { mkdirs() }
            val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
            val f = java.io.File(dir, "$safe.wav")
            if (!f.exists() || f.length() == 0L) {
                f.writeBytes(Base64.decode(base64, Base64.DEFAULT))
            }
            "file://" + f.absolutePath
        }
    } catch (_: Throwable) { null }

    /**
     * Paths bridges use for generated files — worth waiting on.
     *
     * v1.9.6 (user: «в первом сообщении картинка не появляется, а во втором
     * появляется»). Both messages carried a link to the same bridge; the
     * difference was one character.
     *
     *   .../api/bridge/media/<id>       → matched "/media/"  → waited → shown
     *   .../api/bridge/live-media/<id>  → no "/media/"       → treated as an
     *                                     ordinary link and left as text
     *
     * In "live-media/" the character before "media" is a hyphen, not a slash,
     * so the old check missed it — and with it the whole pending-poll path:
     * no placeholder, no "generating" caption, nothing when the bytes arrived.
     * The second message happened to use the other spelling, which is why the
     * feature looked intermittent rather than broken.
     *
     * Matching on the segment rather than on slashes makes the check immune to
     * whatever a bridge decides to call its route next: media, live-media,
     * media-v2 and tmp-audio all pass now.
     */
    private fun looksLikeMediaUrl(url: String): Boolean {
        val l = url.lowercase().substringBefore('?')
        return MEDIA_PATH.containsMatchIn(l)
    }

    /** A path segment that is, or ends in, one of the generated-file words. */
    private val MEDIA_PATH =
        Regex("""/[\w-]*(media|image|img|audio|speech|tts|gen\w*|file)s?[\w-]*(/|$)""")
}
