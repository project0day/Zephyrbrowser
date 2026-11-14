package com.zephyr.browser.userscripts

import java.util.Locale

/**
 * Tampermonkey-style userscript metadata parser + URL matcher.
 * PURE Kotlin (no Android imports) so the matching logic is unit-testable.
 *
 * Supported metadata keys (parsed from the "=UserScript=" header comment block):
 *  - name, description, version
 *  - match    : Chrome-style pattern "scheme host path" where the host may be
 *               prefixed with a wildcard-dot (matches the domain itself AND any
 *               subdomain) and the path part is a glob whose star matches any
 *               characters. A bare star as the whole value matches any page.
 *               Matching is done against path + query string, like Chrome.
 *  - include  : simplified glob on the FULL URL (no regex form, kept simple)
 *  - exclude  : glob on the full URL; ALWAYS wins over match/include
 *  - run-at   : document-start, document-end, document-idle (default: idle)
 *  - noframes : stored for compatibility; the injector only runs in the main
 *               frame anyway, so this is informational.
 *
 * The legacy manual "domains" column from the scripts table is honoured through
 * [domainsAllow]: the script applies only if BOTH the header matches AND the
 * domains column allows the URL (a bare star or empty column allows everything).
 */
data class UserScriptMeta(
    val name: String,
    val matches: List<String>,
    val includes: List<String>,
    val excludes: List<String>,
    val runAt: String,
    val noframes: Boolean,
    val description: String,
    val version: String
) {

    /**
     * Does the script header want to run on [url]?
     * Rules: only http or https URLs qualify; exclude globs win; if the header
     * carries no match/include rules at all it applies everywhere (legacy
     * scripts created through the manual editor are governed by the domains
     * column alone); otherwise ANY match-pattern or include glob hit counts.
     */
    fun matchesUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        for (e in excludes) {
            if (globMatches(e, url)) return false
        }
        val hasRules = matches.isNotEmpty() || includes.isNotEmpty()
        if (!hasRules) return true
        for (m in matches) {
            if (matchPattern(m, url)) return true
        }
        for (i in includes) {
            if (globMatches(i, url)) return true
        }
        return false
    }

    companion object {
        const val RUN_AT_START = "document-start"
        const val RUN_AT_END = "document-end"
        const val RUN_AT_IDLE = "document-idle"

        private const val HEADER_BEGIN = "// ==UserScript=="
        private const val HEADER_END = "// ==/UserScript=="

        /** Parse the metadata header from raw script text. Never throws. */
        fun parse(code: String): UserScriptMeta {
            var name = ""
            var description = ""
            var version = ""
            var runAt = RUN_AT_IDLE
            var noframes = false
            val matches = ArrayList<String>()
            val includes = ArrayList<String>()
            val excludes = ArrayList<String>()
            try {
                var inside = false
                for (raw in code.split('\n')) {
                    val line = raw.trim()
                    if (line.startsWith(HEADER_BEGIN)) {
                        inside = true
                        continue
                    }
                    if (inside && line.startsWith(HEADER_END)) break
                    if (!inside || !line.startsWith("// @")) continue
                    val body = line.removePrefix("// @").trim()
                    if (body.isEmpty()) continue
                    val key = body.substringBefore(' ').lowercase(Locale.ROOT)
                    val value = body.substringAfter(' ', "").trim()
                    when (key) {
                        "name" -> if (name.isEmpty()) name = value
                        "description" -> if (description.isEmpty()) description = value
                        "version" -> if (version.isEmpty()) version = value
                        "match" -> if (value.isNotEmpty()) matches.add(value)
                        "include" -> if (value.isNotEmpty()) includes.add(value)
                        "exclude" -> if (value.isNotEmpty()) excludes.add(value)
                        "run-at", "runat" -> runAt = normalizeRunAt(value)
                        "noframes" -> noframes = true
                    }
                }
            } catch (_: Exception) {
                // a malformed header must never crash the injector
            }
            return UserScriptMeta(
                name = name, matches = matches, includes = includes, excludes = excludes,
                runAt = runAt, noframes = noframes, description = description, version = version
            )
        }

        private fun normalizeRunAt(value: String): String = when (value.lowercase(Locale.ROOT).trim()) {
            "document-start", "start" -> RUN_AT_START
            "document-end", "end" -> RUN_AT_END
            else -> RUN_AT_IDLE
        }

        /**
         * Remove the metadata header block from the script text — the header is
         * comments (not executable) and would only bloat the injected payload.
         */
        fun stripHeader(code: String): String {
            val start = code.indexOf(HEADER_BEGIN)
            if (start < 0) return code
            val end = code.indexOf(HEADER_END, start)
            if (end < 0) return code
            return code.substring(0, start) + code.substring(end + HEADER_END.length)
        }

        /**
         * Legacy manual restriction from the scripts table: comma-separated
         * domains, a bare star (or empty) means "all". A domain entry matches
         * the URL host exactly or as a parent domain; a wildcard-dot prefix on
         * an entry behaves like the plain domain.
         */
        fun domainsAllow(domains: String, url: String): Boolean {
            val raw = domains.trim().lowercase(Locale.ROOT)
            if (raw.isEmpty() || raw == "*") return true
            val host = hostOf(url)
            if (host.isEmpty()) return false
            for (entry in raw.split(',')) {
                var d = entry.trim()
                if (d.isEmpty() || d == "*") return true
                if (d.startsWith("*.")) d = d.removePrefix("*.")
                d = d.removePrefix(".").removeSuffix(".")
                if (d.isNotEmpty() && (host == d || host.endsWith(".$d"))) return true
            }
            return false
        }

        /** Lowercase host of an absolute URL, without port or userinfo. Pure string ops. */
        fun hostOf(url: String): String {
            try {
                val schemeSep = url.indexOf("://")
                if (schemeSep < 0) return ""
                val rest = url.substring(schemeSep + 3)
                val noAuth = if (rest.contains('@')) rest.substringAfter('@') else rest
                val hostPart = noAuth.substringBefore('/').substringBefore('?').substringBefore('#')
                return hostPart.substringBefore(':').lowercase(Locale.ROOT)
            } catch (_: Exception) {
                return ""
            }
        }

        /**
         * Chrome-style match pattern: scheme, host (wildcard-dot prefix allowed),
         * path glob. The path glob is matched against path + query. A pattern
         * without a scheme separator degrades to a plain glob on the full URL.
         */
        internal fun matchPattern(pattern: String, url: String): Boolean {
            val p = pattern.trim()
            if (p.isEmpty()) return false
            if (p == "*") return true
            val sep = p.indexOf("://")
            if (sep <= 0) return globMatches(p, url)
            val patScheme = p.substring(0, sep).lowercase(Locale.ROOT)
            val rest = p.substring(sep + 3)

            val usep = url.indexOf("://")
            if (usep <= 0) return false
            val urlScheme = url.substring(0, usep).lowercase(Locale.ROOT)
            val schemeOk = when (patScheme) {
                "*" -> true
                "http*" -> urlScheme == "http" || urlScheme == "https"
                else -> patScheme == urlScheme
            }
            if (!schemeOk) return false

            val uRestRaw = url.substring(usep + 3)
            val uRest = if (uRestRaw.contains('@')) uRestRaw.substringAfter('@') else uRestRaw
            val uSlash = uRest.indexOf('/')
            val uHost = (if (uSlash < 0) uRest else uRest.substring(0, uSlash))
                .substringBefore(':').lowercase(Locale.ROOT)
            val uPath = if (uSlash < 0) "/" else uRest.substring(uSlash)

            val pSlash = rest.indexOf('/')
            val pHost: String
            val pPath: String
            if (pSlash < 0) {
                pHost = rest
                pPath = "/"
            } else {
                pHost = rest.substring(0, pSlash)
                pPath = rest.substring(pSlash)
            }
            val ph = pHost.lowercase(Locale.ROOT)
            when {
                // v1.7.8 FIX (user: «скрипты не работают вообще»): the MOST common
                // userscript pattern `*://*/*` (and `http*://*/*`) matches ANY host —
                // the old matcher required uHost == "*" and rejected every URL, so
                // every all-sites script (the majority of GreasyFork scripts) never ran.
                ph == "*" -> { /* any host */ }
                ph.startsWith("*.") -> {
                    val base = ph.removePrefix("*.")
                    if (base.isEmpty()) return false
                    if (uHost != base && !uHost.endsWith(".$base")) return false
                }
                else -> if (uHost != ph) return false
            }
            return globMatches(pPath, uPath)
        }

        /** Plain glob on the whole string: a star matches any character run. */
        internal fun globMatches(glob: String, text: String): Boolean {
            val g = glob.trim()
            if (g.isEmpty()) return false
            if (g == "*") return true
            val re = try {
                Regex(g.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                return false
            }
            return re.matches(text)
        }
    }
}
