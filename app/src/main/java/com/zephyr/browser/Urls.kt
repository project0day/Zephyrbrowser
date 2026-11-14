package com.zephyr.browser

import android.net.Uri
import android.util.Patterns
import java.util.Locale

/** URL / input normalization utilities (pure Kotlin, unit-testable). */
object Urls {

    private val IP_RE = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private val TOP_FILE_EXT = setOf(
        "apk", "zip", "rar", "7z", "tar", "gz", "mp3", "flac", "ogg", "wav", "aac", "m4a",
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "pdf", "epub", "doc", "docx", "xls", "xlsx",
        "ppt", "pptx", "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "iso", "exe", "dmg", "torrent"
    )

    val DEFAULT_HOME = "about:home"

    /** Registrable-ish domain: strips common www/mobile prefixes, returns lowercase host. */
    fun domainOf(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            val u = if (url.startsWith("http")) Uri.parse(url) else Uri.parse("https://$url")
            (u.host ?: "").lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        } catch (_: Exception) { "" }
    }

    /** Origin (scheme://host[:port]) used for password/permission scoping. */
    fun originOf(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            val u = if (url.startsWith("http")) Uri.parse(url) else Uri.parse("https://$url")
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme ?: "https"}://${u.host ?: ""}$port"
        } catch (_: Exception) { "" }
    }

    fun looksLikeUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty()) return false
        if (s.contains(' ') || s.contains('\n')) return false
        if (s.startsWith("http://") || s.startsWith("https://")) return true
        if (s.startsWith("about:") || s.startsWith("file://") || s.startsWith("data:")) return true
        if (s.startsWith("intent://") || s.contains("://")) return true
        val hostPart = s.substringBefore('/').substringBefore(':')
        if (IP_RE.matches(hostPart)) return true
        if (hostPart.contains('.') && !hostPart.endsWith(".") && !hostPart.startsWith(".")) {
            val tld = hostPart.substringAfterLast('.').lowercase(Locale.ROOT)
            if (tld.length in 2..24 && tld.all { it.isLetter() }) return true
        }
        return false
    }

    /** Normalize user input to a URL the WebView can load.
     *  v1.5.0 FIX: the RAW query is passed to the template — encoding happens EXACTLY
     *  ONCE inside SearchEngines.searchUrl (the old pre-encode here + re-encode there
     *  double-encoded queries: Google then showed literal %D0%25BA… in its own field). */
    fun normalize(input: String, searchTemplate: (String) -> String): String {
        val s = input.trim()
        if (s.isEmpty()) return DEFAULT_HOME
        if (s == DEFAULT_HOME) return s
        if (looksLikeUrl(s)) {
            if (s.startsWith("http://") || s.startsWith("https://") ||
                s.startsWith("about:") || s.startsWith("file://") || s.startsWith("data:") ||
                s.startsWith("intent://") || s.contains("://")
            ) return s
            return "https://$s"
        }
        return searchTemplate(s)
    }

    fun fileExtOf(url: String): String? {
        val clean = url.substringBefore('#').substringBefore('?')
        val seg = clean.substringAfterLast('/')
        val ext = seg.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (ext.length in 1..5 && ext.all { it.isLetter() }) ext else null
    }

    fun isProbablyFile(url: String): Boolean = fileExtOf(url)?.let { it in TOP_FILE_EXT } ?: false

    fun guessName(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?')
        val seg = clean.substringAfterLast('/')
        return if (seg.length > 25) seg.take(25) else (seg.ifEmpty { domainOf(url).ifEmpty { "file" } })
    }

    fun isHttp(url: String?): Boolean = url != null && (url.startsWith("http://") || url.startsWith("https://"))

    // ---------------- v1.4.2 FIX 1: pretty (percent-decoded) URL for the omnibox ----------------

    /**
     * Display form of a URL: percent-encoded octets (%D0%B0 …) and '+' in the query are
     * decoded to readable characters, so the omnibox shows
     *   yandex.ru/search/?text=котики
     * instead of
     *   yandex.ru/search/?text=%D0%BA%D0%BE%D1%82%D0%B8%D0%BA%D0%B8
     * Never used for loading — [normalize] still encodes. Truncated to 160 chars.
     */
    fun prettyUrl(url: String): String {
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url.take(160)
        return try {
            val u = Uri.parse(url)
            val scheme = u.scheme ?: "https"
            val host = (u.host ?: "").lowercase(Locale.ROOT)
            val port = if (u.port > 0) ":${u.port}" else ""
            val path = percentDecode(u.encodedPath ?: "", plusAsSpace = false)
            val query = u.encodedQuery?.let { "?" + percentDecode(it, plusAsSpace = true) } ?: ""
            val fragment = u.encodedFragment?.let { "#" + percentDecode(it, plusAsSpace = false) } ?: ""
            var display = "$scheme://$host$port$path$query$fragment"
            // drop a lonely trailing slash only on bare origins (no path/query/fragment)
            if (path == "/" || path.isEmpty()) {
                if (query.isEmpty() && fragment.isEmpty()) display = display.removeSuffix("/")
            }
            display.take(160)
        } catch (_: Exception) {
            url.take(160)
        }
    }

    /** Decode %XX sequences (as UTF-8 bytes) and optionally '+'→' '. Invalid escapes stay literal. */
    private fun percentDecode(s: String, plusAsSpace: Boolean): String {
        if (!s.contains('%')) return if (plusAsSpace) s.replace("+", " ") else s
        return try {
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 3 <= s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val v = if (hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) hex.toInt(16) else null
                    if (v != null) { out.write(v); i += 3; continue }
                }
                if (c == '+' && plusAsSpace) out.write(' '.code)
                else out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            out.toString("UTF-8")
        } catch (_: Exception) { s }
    }
}
