package com.zephyr.browser

import android.net.Uri
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * v1.7.25b: turns a cross-origin iframe PLAYER page into real media rows.
 * v1.7.26: much harder digging, after the user tested it on Kinogo and got
 * «прямых ссылок не нашлось».
 *
 * Movie sites embed players from another origin, so the in-page DOM walk cannot
 * enter them. This resolver fetches the player page NATIVELY with the right
 * Referer/UA/Cookie and extracts the media links from its HTML:
 *
 *  - plain `.m3u8` / `.mp4` / … urls, including protocol-relative `//host/…`;
 *  - Playerjs / Kodik quality lists: `"file":"[360p]https://…,[720p]https://…"`;
 *  - the CIS players' base64 obfuscation of `file:` (with the `//_//` junk
 *    markers and the `#` prefix both handled);
 *  - `\/`, `\uXXXX` and HTML-entity escaped variants of all of the above;
 *  - `*.txt` / `*.json` playlists referenced by the player, fetched one hop
 *    deeper (this is what Kinogo-style players actually serve);
 *  - nested iframes, one extra hop.
 *
 * Everything is best-effort and fully guarded: a site that refuses simply
 * yields no rows and the «open the player in a tab» path stays available.
 */
object EmbedResolver {

    /** One media link dug out of a player page. */
    data class Found(val url: String, val quality: String, val isHls: Boolean)

    private val MEDIA_RE = Regex(
        """(?:https?:)?//[^\s"'\\<>()\[\]]+?\.(?:m3u8|mp4|webm|m4v|mkv|mov)(?:\?[^\s"'\\<>()\[\]]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val QUALITY_LIST_RE = Regex(
        """\[(\d{3,4}p?)]\s*((?:https?:)?//[^\s",\\<>\[\]]+)""",
        RegexOption.IGNORE_CASE
    )
    private val IFRAME_RE = Regex(
        """<iframe[^>]+src\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    /** `file: "…"`, `"file":"…"`, `hls:"…"`, `"src":"…"` — the player configs. */
    private val CONFIG_VALUE_RE = Regex(
        """["']?(?:file|hls|src|manifest|playlist)["']?\s*[:=]\s*["']([^"']{8,})["']""",
        RegexOption.IGNORE_CASE
    )
    /** A playlist the player loads separately. */
    private val PLAYLIST_RE = Regex(
        """(?:https?:)?//[^\s"'\\<>()]+?\.(?:txt|json)(?:\?[^\s"'\\<>()]*)?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Fetch [embedUrl] and extract every playable link. [depth] guards the
     * nested hops. Runs blocking — call it off the main thread.
     */
    fun resolve(
        embedUrl: String,
        referer: String?,
        userAgent: String?,
        cookies: String?,
        depth: Int = 0
    ): List<Found> {
        val html = fetch(embedUrl, referer, userAgent, cookies) ?: return emptyList()
        val out = LinkedHashMap<String, Found>()

        harvest(unescape(html), embedUrl, out)

        // the player configs often hide the real list behind base64
        if (out.isEmpty()) {
            for (m in CONFIG_VALUE_RE.findAll(html).take(40)) {
                val decoded = deobfuscate(m.groupValues[1]) ?: continue
                harvest(unescape(decoded), embedUrl, out)
                if (out.isNotEmpty()) break
            }
        }

        if (out.isNotEmpty() || depth >= 2) return out.values.toList()

        // a separate .txt/.json playlist — the shape Kinogo-style players use
        for (p in PLAYLIST_RE.findAll(unescape(html)).map { it.value }.distinct().take(3)) {
            val abs = absolute(p, embedUrl) ?: continue
            val body = fetch(abs, embedUrl, userAgent, cookies) ?: continue
            harvest(unescape(body), abs, out)
            if (out.isNotEmpty()) return out.values.toList()
        }

        // still nothing → follow ONE nested iframe (players are often wrapped)
        for (m in IFRAME_RE.findAll(html).take(3)) {
            val nested = absolute(m.groupValues[1], embedUrl) ?: continue
            if (nested == embedUrl) continue
            val deeper = resolve(nested, embedUrl, userAgent, cookies, depth + 1)
            if (deeper.isNotEmpty()) return deeper
        }
        return emptyList()
    }

    // ---------------- extraction ----------------

    private fun harvest(text: String, base: String, out: LinkedHashMap<String, Found>) {
        // labeled quality lists first — the richest source when present
        for (m in QUALITY_LIST_RE.findAll(text)) {
            val q = m.groupValues[1].let { if (it.endsWith("p", true)) it else it + "p" }
            val u = absolute(cleanUrl(m.groupValues[2]), base) ?: continue
            out[u] = Found(u, q, u.contains(".m3u8", true))
        }
        // then any bare media url
        for (m in MEDIA_RE.findAll(text)) {
            val u = absolute(cleanUrl(m.value), base) ?: continue
            if (out.containsKey(u)) continue
            out[u] = Found(u, qualityFromUrl(u), u.contains(".m3u8", true))
        }
    }

    /**
     * Playerjs-family obfuscation: the `file` value is base64 (sometimes with
     * a leading `#`) and sprinkled with `//_//` junk markers. Returns null when
     * the value is not obfuscated at all.
     */
    private fun deobfuscate(raw: String): String? {
        var v = raw.trim()
        if (v.startsWith("http", true) || v.startsWith("//")) return null
        v = v.removePrefix("#")
        // strip the junk sequences the players inject between base64 chunks
        v = v.replace(Regex("//_//[A-Za-z0-9+/=]{0,24}"), "")
            .replace(Regex("[^A-Za-z0-9+/=]"), "")
        if (v.length < 16) return null
        return try {
            val bytes = Base64.decode(v, Base64.DEFAULT)
            val s = String(bytes, Charsets.UTF_8)
            if (s.contains("//") || s.contains("http")) s else null
        } catch (_: Throwable) { null }
    }

    // ---------------- helpers ----------------

    private fun fetch(url: String, referer: String?, ua: String?, cookies: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 12000
                readTimeout = 15000
                setRequestProperty(
                    "User-Agent",
                    ua ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru,en;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip")
                referer?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
                cookies?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            }
            if (conn.responseCode !in 200..299) return null
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true)
                GZIPInputStream(conn.inputStream) else conn.inputStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var n = r.read(buf)
                // players are small; 2 MB is far more than enough and bounds the work
                while (n > 0 && sb.length < 2_000_000) { sb.appendRange(buf, 0, n); n = r.read(buf) }
                sb.toString()
            }
        } catch (_: Throwable) { null } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** JS/JSON escaping hides the urls from a plain regex — undo the common forms. */
    private fun unescape(s: String): String {
        var t = s.replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#47;", "/")
        // \u002F and friends
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) { m ->
            try { m.groupValues[1].toInt(16).toChar().toString() } catch (_: Throwable) { m.value }
        }
        return t
    }

    private fun cleanUrl(raw: String): String {
        var u = raw.trim().trim('"', '\'', ',', ';', ')', '(')
        while (u.endsWith("\\")) u = u.dropLast(1)
        return u
    }

    private fun absolute(raw: String, base: String): String? = try {
        val u = raw.trim()
        val abs = when {
            u.startsWith("http", true) -> u
            u.startsWith("//") -> (Uri.parse(base).scheme ?: "https") + ":" + u
            u.startsWith("/") -> Uri.parse(base).let { it.scheme + "://" + it.host + u }
            else -> null
        }
        if (abs != null && abs.length > 14) abs else null
    } catch (_: Throwable) { null }

    /** Last-resort label: pull "720p"/"1080" out of the url itself. */
    private fun qualityFromUrl(u: String): String {
        Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(u)?.let { return it.groupValues[1] + "p" }
        Regex("""[/_-](360|480|540|576|720|1080|1440|2160)[/_.-]""").find(u)?.let {
            return it.groupValues[1] + "p"
        }
        return ""
    }
}
