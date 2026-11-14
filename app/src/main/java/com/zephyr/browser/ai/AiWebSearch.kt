package com.zephyr.browser.ai

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * v1.7.30: the AI Assistant's window onto the live web.
 *
 * User: «хочу, чтобы помощник мог выйти в интернет, чтобы ответы были точные —
 * спрошу, какое сейчас время, а он не говорит». A language model has a training
 * cutoff and no clock, so anything current is a guess. Two things fix that:
 *
 *  1. the current date/time is injected into every request (see AiClient);
 *  2. the model can CALL these functions — [search] runs a DuckDuckGo query and
 *     [readPage] pulls the readable text out of a result — and then answers from
 *     what actually came back.
 *
 * DuckDuckGo's HTML endpoint is used because it needs no API key and no account.
 * Nothing but the query text leaves the device, and only when the model decides
 * it needs to look something up (the whole feature is switchable in Settings).
 */
object AiWebSearch {

    data class Result(val title: String, val url: String, val snippet: String)

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    // ---------------- search ----------------

    /** Run [query] and return up to [limit] results. Blocking. */
    fun search(query: String, limit: Int = 5): List<Result> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val html = fetch("https://html.duckduckgo.com/html/?q=$q&kl=ru-ru") ?: return emptyList()
        val out = ArrayList<Result>()

        val blockRe = Regex(
            """<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRe = Regex(
            """class="result__snippet"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippets = snippetRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        var i = 0
        for (m in blockRe.findAll(html)) {
            if (out.size >= limit) break
            val href = decodeDdgLink(m.groupValues[1])
            val title = stripTags(m.groupValues[2])
            if (href.isBlank() || title.isBlank()) { i++; continue }
            out.add(Result(title, href, snippets.getOrElse(i) { "" }))
            i++
        }
        return out
    }

    /** DuckDuckGo wraps every hit in /l/?uddg=<encoded real url>. */
    private fun decodeDdgLink(raw: String): String {
        var h = raw.replace("&amp;", "&")
        if (h.startsWith("//")) h = "https:$h"
        val marker = "uddg="
        val at = h.indexOf(marker)
        if (at >= 0) {
            val enc = h.substring(at + marker.length).substringBefore('&')
            return try { java.net.URLDecoder.decode(enc, "UTF-8") } catch (_: Throwable) { h }
        }
        return h
    }

    // ---------------- page reading ----------------

    /** Fetch [url] and return its readable text, capped at [maxChars]. Blocking. */
    fun readPage(url: String, maxChars: Int = 6000): String {
        val html = fetch(url) ?: return "[страницу не удалось открыть]"
        // drop everything that is not prose
        var body = html
        for (tag in listOf("script", "style", "noscript", "svg", "head", "nav", "footer")) {
            body = body.replace(
                Regex("""<$tag\b[^>]*>.*?</$tag>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                " "
            )
        }
        body = body.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")
        // keep paragraph boundaries readable
        body = body.replace(Regex("""</(p|div|li|h[1-6]|tr|br)>""", RegexOption.IGNORE_CASE), "\n")
        val text = stripTags(body)
            .replace(Regex("""\n{3,}"""), "\n\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
        return if (text.length > maxChars) text.take(maxChars) + "…" else text
    }

    // ---------------- plumbing ----------------

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 12000
                readTimeout = 15000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru,en;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            if (conn.responseCode !in 200..299) return null
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true)
                GZIPInputStream(conn.inputStream) else conn.inputStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var n = r.read(buf)
                while (n > 0 && sb.length < 1_500_000) { sb.appendRange(buf, 0, n); n = r.read(buf) }
                sb.toString()
            }
        } catch (_: Throwable) { null } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun stripTags(s: String): String = s
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("""&#(\d+);""")) { m ->
            try { m.groupValues[1].toInt().toChar().toString() } catch (_: Throwable) { " " }
        }
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** Render results as the compact text block the model gets back. */
    fun format(results: List<Result>): String {
        if (results.isEmpty()) return "Поиск не дал результатов."
        return results.mapIndexed { i, r ->
            "[${i + 1}] ${r.title}\n${r.url}\n${r.snippet}"
        }.joinToString("\n\n")
    }
}
