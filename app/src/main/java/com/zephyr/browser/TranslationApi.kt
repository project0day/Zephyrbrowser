package com.zephyr.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.net.URL

/**
 * v1.7.6 (user: «Переводчик без гугл-обёртки — инъекционный перевод выделенного
 * текста, как Chrome Translate inline»): in-app text translation without any
 * wrapper page or navigation.
 *
 * Strategy (no API keys, no wrapper):
 *  1. translate.googleapis.com ajax endpoint — auto-detects the source language
 *     (classic free endpoint used by many clients; the request is invisible to
 *     the user — nothing opens, nothing navigates);
 *  2. MyMemory community API as a fallback — needs an explicit language pair,
 *     so the source is guessed from the script (cyrillic → ru, else en).
 *
 * The target language follows the app language (ru/en/fr, default ru).
 *
 * v1.7.10 (user: «Перевод плохо работает и вообще переводит пару слов и все!»):
 * page translation used to fire ONE HTTP request per text node — minutes for a
 * normal page. [translateBatch] now packs a whole group of strings into a
 * single round-trip (items joined with "\n"; Google's `dt=t` segments preserve
 * those newlines, so the answer can be split back reliably).
 */
object TranslationApi {

    /** v1.7.10: max joined-payload length of one batch sub-request. The gtx
     *  endpoint comfortably accepts ~1700 chars per call (the legacy single
     *  [translate] path sends up to 1800), so 1600 leaves headroom. */
    private const val BATCH_MAX = 1600

    /** Target language for the current app/user locale. */
    fun target(): String {
        val pref = Prefs.language
        if (pref == "ru" || pref == "en" || pref == "fr") return pref
        return try {
            val sys = java.util.Locale.getDefault().language
            if (sys == "ru" || sys == "en" || sys == "fr") sys else "ru"
        } catch (_: Exception) { "ru" }
    }

    /** @return the translation, or null when every provider failed. */
    suspend fun translate(text: String, target: String = target()): String? {
        return withContext(Dispatchers.IO) {
            val t = text.trim().take(1800)
            if (t.isEmpty()) null
            else viaGtx(t, target) ?: viaMyMemory(t, target)
        }
    }

    /** v1.7.10: batch translation — one HTTP round-trip for a whole group of
     *  strings (joined with \n). Returns translations aligned with [items]; on
     *  any mismatch or failure falls back to per-string [translate] so a batch
     *  can never corrupt page text.
     *
     *  Alignment contract (the safety core):
     *   - items are joined with "\n" into one payload (split into ≤[BATCH_MAX]
     *     -char sub-requests when needed, results concatenated);
     *   - Google preserves the newlines inside the translated segments, so the
     *     joined answer is split back by "\n";
     *   - the fast path is accepted ONLY when the translated line count equals
     *     the expected source line count (Σ per-item lines). For single-line
     *     items that check degenerates to exactly `count == items.size` → the
     *     required 1:1 map; the page translator sends multi-line group texts
     *     (text nodes joined with \n), which are sliced back per item by their
     *     known line counts. If Google merged/dropped/added any line, or the
     *     request failed twice → per-string fallback (max 4 concurrent), which
     *     is slower but always correctly aligned.
     *   - blank items are returned unchanged without any request.
     *   - every sub-request gets exactly one retry for network blips. */
    suspend fun translateBatch(items: List<String>, target: String = target()): List<String> {
        return withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext emptyList()
            val out = items.toMutableList()
            val needFallback = ArrayList<Int>()

            // 1) split non-blank items into sub-requests: joined payload ≤ BATCH_MAX
            val batches = ArrayList<List<Int>>()
            var cur = ArrayList<Int>()
            var curLen = 0
            for (i in items.indices) {
                val s = items[i]
                if (s.isBlank()) continue                       // "" / whitespace → stays as-is
                if (cur.isNotEmpty() && curLen + s.length + 1 > BATCH_MAX) {
                    batches.add(cur); cur = ArrayList(); curLen = 0
                }
                cur.add(i)
                curLen += if (curLen == 0) s.length else s.length + 1   // +1 = "\n" separator
            }
            if (cur.isNotEmpty()) batches.add(cur)

            // 2) one (retried) gtx round-trip per sub-request, verify line alignment
            for (b in batches) {
                val joined = b.joinToString("\n") { items[it] }
                var tr = viaGtxRaw(joined, target)
                if (tr == null) tr = viaGtxRaw(joined, target)         // one retry (blips)
                var ok = false
                if (tr != null) {
                    val lines = tr.split("\n")
                    val counts = b.map { items[it].split("\n").size }  // per-item source lines
                    if (lines.size == counts.sum()) {                  // single-line items → == items.size
                        var cursor = 0
                        for (k in b.indices) {
                            out[b[k]] = lines.subList(cursor, cursor + counts[k]).joinToString("\n")
                            cursor += counts[k]
                        }
                        ok = true
                    }
                }
                if (!ok) needFallback.addAll(b)
            }

            // 3) per-string fallback, at most 4 requests in flight
            if (needFallback.isNotEmpty()) {
                val fb = fallbackParallel(needFallback.map { items[it] }, target)
                for (k in needFallback.indices) out[needFallback[k]] = fb[k]
            }
            out
        }
    }

    /** v1.7.10: per-string rescue path for failed/misaligned batches —
     *  bounded at 4 concurrent requests so a big page still finishes fast. */
    private suspend fun fallbackParallel(list: List<String>, target: String): List<String> = coroutineScope {
        val gate = Semaphore(4)
        list.map { s -> async { gate.withPermit { translate(s, target) ?: s } } }.awaitAll()
    }

    /** Endpoint 1: auto-detect, segment-wise JSON array (legacy single-string path). */
    private fun viaGtx(text: String, target: String): String? = viaGtxRaw(text, target)

    /** v1.7.10: raw gtx call — no input truncation (callers keep the payload
     *  ≤ ~1700 chars; [translateBatch] splits at [BATCH_MAX]). The joined
     *  segment text keeps every internal "\n" — required for batch alignment;
     *  only outer whitespace is trimmed. */
    private fun viaGtxRaw(text: String, target: String): String? {
        return try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8")
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().readText()
                // response shape: [[["перевод","source",null,null,10],...],null,"en",...]
                val segs = org.json.JSONArray(body).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until segs.length()) {
                    val seg = segs.optJSONArray(i) ?: continue
                    sb.append(seg.optString(0))
                }
                val out = sb.toString().trim()
                if (out.isEmpty()) null else out
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (_: Exception) { null }
    }

    /** Endpoint 2: MyMemory — explicit pair, script-based source guess. */
    private fun viaMyMemory(text: String, target: String): String? {
        return try {
            val src = if (text.any { it in 'Ѐ'..'ӿ' }) "ru" else "en"
            val pair = if (src == target) "en|$target" else "$src|$target"
            val url = "https://api.mymemory.translated.net/get?q=" +
                URLEncoder.encode(text, "UTF-8") + "&langpair=" + pair + "&de=zephyr@example.com"
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().readText()
                val out = org.json.JSONObject(body)
                    .optJSONObject("responseData")?.optString("translatedText")?.trim()
                if (out.isNullOrEmpty() || out.contains("MYMEMORY WARNING")) null else out
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (_: Exception) { null }
    }
}
