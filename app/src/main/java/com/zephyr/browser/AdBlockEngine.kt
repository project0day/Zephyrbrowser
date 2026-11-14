package com.zephyr.browser

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * v1.7.8 REWRITE — EasyList/AdGuard/hosts-compatible ad block engine.
 *
 * The v1.2-era engine had TWO fatal semantic bugs that made blocking
 * effectively dead on real filter lists (user: «блокировка не работает
 * вообще»):
 *  1. `^` was treated as an END ANCHOR ("must end here"), so the single most
 *     common rule shape in EasyList — `||host^` — only ever matched the bare
 *     domain root with NO path. `||doubleclick.net^` let
 *     https://ad.doubleclick.net/ddm/adj?… straight through.
 *  2. Generic element-hiding rules `##.sel` (no domain prefix) were dropped
 *     (the parser required the `##` marker at index > 0), and `#@#` exception
 *     rules were added as HIDE rules instead of un-hiding.
 *
 * The v1.7.8 matcher (semantics validated against a 30-case battery that
 * mirrors real EasyList/AdGuard rule shapes before porting):
 *  - `^` is a SEPARATOR: matches exactly one char that is not alnum and not
 *    one of `-._%~`, or the END of the URL (ABP spec);
 *  - `||host` domain anchor: request host == host or a subdomain;
 *  - `|…|` start/end anchors; `*` wildcards; `$options`: third-party, ~,
 *    domain=, important, badfilter, type hints (types are not verifiable
 *    inside a WebView request interceptor, so type-restricted rules apply to
 *    any subresource — the standard lightweight-browser trade-off);
 *  - unsupported option classes ($csp, $removeparam, $denyallow, $document,
 *    $popup, $replace, extended-css `#?#`, procedural selectors `:has(`…)
 *    are SKIPPED instead of mis-firing;
 *  - `#@#` element-hide exceptions remove matching selectors per domain;
 *  - hosts-file lines (`0.0.0.0 domain`) become `||domain^` rules;
 *  - network rules are keyword-indexed (classic Adblock Plus approach): the
 *    rule is stored under its longest literal token, the request URL is
 *    tokenized the same way — matching stays O(tokens × few rules) even with
 *    100k-rule lists.
 */
class AdBlockEngine {

    class NetRule(
        val segs: List<Seg>,               // literal segments separated by '*' / '^'
        val anchoredStartDomain: Boolean,  // ||host
        val anchoredStart: Boolean,        // |…
        val anchoredEnd: Boolean,          // …|
        val trailingCaret: Boolean,        // …^ (separator or end)
        val thirdParty: Boolean?,          // null = any
        val important: Boolean,
        val isAllow: Boolean,
        val domainAllow: Set<String>?,
        val domainDeny: Set<String>?
    ) {
        class Seg(val text: String, val sepBefore: String) // "" | "*" | "^"
    }

    private class ElemRule(val domainAllow: List<String>, val domainDeny: List<String>, val selector: String)

    private val lock = ReentrantReadWriteLock()
    private val blockIndex = HashMap<String, MutableList<NetRule>>()
    private val allowIndex = HashMap<String, MutableList<NetRule>>()
    private val elemHide = ArrayList<ElemRule>()
    private val elemUnhide = ArrayList<Pair<List<String>, String>>()   // domains (empty = global) → selector
    private var ruleCount = 0

    companion object {
        /** chars that are NOT separators for `^` (ABP: separator = any non-alnum except -._% and ~) */
        private val SEP_NOT = setOf('-', '.', '_', '%', '~')
        private const val PROC_MARKERS = ":has-text,:has(,:matches,:-abp-,:style(,:remove(,[-abp-properties="
        private val SKIP_OPTS = setOf(
            "document", "popup", "generichide", "genericblock", "elemhide", "webrtc", "badfilter"
        )

        fun isSepChar(c: Char): Boolean = !c.isLetterOrDigit() && c !in SEP_NOT

        /** longest literal token (>= 3 chars) of the pattern — the index key.
         *  MUST use the same run-splitting as the request tokenizer (alnum runs). */
        fun keywordOf(pattern: String): String? {
            var best: String? = null
            val sb = StringBuilder()
            fun flush() {
                if (sb.length >= 3 && (best == null || sb.length > best!!.length)) best = sb.toString()
                sb.setLength(0)
            }
            for (c in pattern) {
                if (c.isLetterOrDigit()) sb.append(c) else flush()
            }
            flush()
            return best
        }

        private fun hostOf(url: String): String = try {
            val sep = url.indexOf("://")
            if (sep < 0) ""
            else {
                var rest = url.substring(sep + 3)
                if (rest.contains('@')) rest = rest.substringAfter('@')
                rest.substringBefore('/').substringBefore('?').substringBefore('#')
                    .substringBefore(':').lowercase()
            }
        } catch (_: Exception) { "" }

        fun hostMatchesDomain(host: String, domainRule: String): Boolean {
            if (domainRule.startsWith("||")) {
                val d = domainRule.substring(2); return host == d || host.endsWith(".$d")
            }
            if (domainRule.startsWith(".")) return host.endsWith(domainRule)
            return host == domainRule || host.endsWith(".$domainRule")
        }
    }

    fun size(): Int = lock.read { ruleCount }

    fun clear() = lock.write {
        blockIndex.clear(); allowIndex.clear(); elemHide.clear(); elemUnhide.clear(); ruleCount = 0
    }

    /** Parse one filter-list line. Returns true if a rule was added. */
    fun addLine(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("!") || l.startsWith("[")) return false

        // ---- hosts-file line: "0.0.0.0 domain" / "127.0.0.1 domain" ----
        if (HOSTS_LINE.containsMatchIn(l)) {
            val host = l.split(Regex("\\s+")).getOrNull(1) ?: return false
            if (host.contains('.') && host.none { !it.isLetterOrDigit() && it != '.' && it != '-' })
                return addLine("||$host^")
        }

        // ---- element hiding / exceptions (order matters: #@# before ##) ----
        for (marker in listOf("#@#", "#?#", "##")) {
            val idx = l.indexOf(marker)
            if (idx >= 0) {
                if (marker == "#?#") return false   // extended-css: unsupported, skip
                val domainsPart = l.substring(0, idx)
                val selector = l.substring(idx + marker.length).trim()
                if (selector.isEmpty() || selector.length > 400) return false
                if (PROCS.any { selector.contains(it) }) return false   // procedural selectors — can't emulate
                val allow = ArrayList<String>(); val deny = ArrayList<String>()
                for (d in domainsPart.split(',')) {
                    val dd = d.trim()
                    if (dd.isEmpty()) continue
                    if (dd.startsWith("~")) deny.add(dd.removePrefix("~")) else allow.add(dd)
                }
                lock.write {
                    if (marker == "#@#") elemUnhide.add(allow to selector)
                    else elemHide.add(ElemRule(allow, deny, selector))
                    ruleCount++
                }
                return true
            }
        }

        // ---- network rule ----
        var body = l
        val isAllow = body.startsWith("@@")
        if (isAllow) body = body.substring(2)

        var thirdParty: Boolean? = null
        var important = false
        var domainAllow: MutableSet<String>? = null
        var domainDeny: MutableSet<String>? = null
        val dollar = body.lastIndexOf('$')
        if (dollar > 0) {
            val opts = body.substring(dollar + 1)
            body = body.substring(0, dollar)
            for (o in opts.split(',')) {
                val opt = o.trim().lowercase()
                when {
                    opt == "third-party" || opt == "3p" -> thirdParty = true
                    opt == "~third-party" || opt == "1p" -> thirdParty = false
                    opt == "important" -> important = true
                    opt == "badfilter" -> return false
                    opt.startsWith("domain=") -> {
                        for (d in opt.substring(7).split('|')) {
                            val dd = d.trim()
                            if (dd.startsWith("~")) (domainDeny ?: mutableSetOf<String>().also { domainDeny = it }).add(dd.removePrefix("~"))
                            else if (dd.isNotEmpty()) (domainAllow ?: mutableSetOf<String>().also { domainAllow = it }).add(dd)
                        }
                    }
                    // unsupported option classes — skip the whole rule rather than mis-fire
                    opt in SKIP_OPTS || opt.startsWith("csp") || opt.startsWith("removeparam") ||
                        opt.startsWith("denyallow") || opt.startsWith("replace") ||
                        opt.startsWith("dnsrewrite") || opt.startsWith("redirect-rule") -> return false
                    opt.startsWith("~") -> return false   // inverted type: skip to avoid over-blocking
                    // known type hints (script/image/…) — unverifiable in WebView, accepted silently
                }
            }
        }
        if (body.isEmpty()) return false

        var pattern = body.trim().lowercase()
        var anchoredStartDomain = false
        var anchoredStart = false
        var anchoredEnd = false
        var trailingCaret = false
        if (pattern.startsWith("||")) { anchoredStartDomain = true; pattern = pattern.substring(2) }
        else if (pattern.startsWith("|")) { anchoredStart = true; pattern = pattern.substring(1) }
        if (pattern.endsWith("|")) { anchoredEnd = true; pattern = pattern.dropLast(1) }
        else if (pattern.endsWith("^")) { trailingCaret = true; pattern = pattern.dropLast(1) }
        if (pattern.isEmpty() || pattern.length > 600) return false
        if (pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")) return false // full regex rule
        if (pattern.any { it == '\\' || it == '(' || it == ')' || it == '[' || it == ']' || it == '{' || it == '}' || it == '+' }) {
            val cut = pattern.split('\\', '(', ')', '[', ']', '{', '}', '+').firstOrNull() ?: ""
            if (cut.length < 4) return false
            pattern = cut
            anchoredEnd = false; trailingCaret = false
        }

        // tokenize into literal segments separated by '*' / '^'
        val segs = ArrayList<NetRule.Seg>(4)
        val cur = StringBuilder()
        var sep = ""
        for (c in pattern) {
            when (c) {
                '*' -> { segs.add(NetRule.Seg(cur.toString(), sep)); cur.setLength(0); sep = "*" }
                '^' -> { segs.add(NetRule.Seg(cur.toString(), sep)); cur.setLength(0); sep = "^" }
                else -> cur.append(c)
            }
        }
        segs.add(NetRule.Seg(cur.toString(), sep))

        val kw = keywordOf(pattern) ?: return false
        if (kw.length < 3) return false   // <3-char keywords are unreachable by the request tokenizer
        val rule = NetRule(segs, anchoredStartDomain, anchoredStart, anchoredEnd, trailingCaret,
            thirdParty, important, isAllow,
            domainAllow?.toSet(), domainDeny?.toSet())
        lock.write {
            (if (isAllow) allowIndex else blockIndex).getOrPut(kw) { mutableListOf() }.add(rule)
            ruleCount++
        }
        return true
    }

    fun addList(text: String) {
        for (line in text.lineSequence()) addLine(line)
    }

    private fun matchRule(r: NetRule, urlLower: String, requestHost: String, pageHost: String): Boolean {
        if (r.thirdParty != null) {
            val isThird = requestHost.isNotEmpty() && pageHost.isNotEmpty() &&
                !hostMatchesDomain(requestHost, pageHost.removePrefix("www."))
            if (isThird != r.thirdParty) return false
        }
        if (r.domainAllow != null || r.domainDeny != null) {
            if (r.domainDeny?.any { hostMatchesDomain(pageHost, it) } == true) return false
            if (r.domainAllow != null && r.domainAllow.none { hostMatchesDomain(pageHost, it) }) return false
        }
        var idx = 0
        var first = true
        for (seg in r.segs) {
            val text = seg.text
            if (text.isEmpty()) continue
            val found = urlLower.indexOf(text, idx)
            if (found < 0) return false
            if (first) {
                if (r.anchoredStart && found != 0) return false
                if (r.anchoredStartDomain) {
                    val dom = text.substringBefore('/')
                    if (requestHost != dom && !requestHost.endsWith(".$dom")) return false
                }
                first = false
            }
            if (seg.sepBefore == "^") {
                if (found == 0) return false
                if (!isSepChar(urlLower[found - 1])) return false
            }
            idx = found + text.length
        }
        if (r.anchoredEnd) {
            if (idx != urlLower.length) return false
        } else if (r.trailingCaret && idx < urlLower.length) {
            if (!isSepChar(urlLower[idx])) return false
        }
        return true
    }

    private fun matchesAny(
        index: HashMap<String, MutableList<NetRule>>,
        requestUrl: String, pageHost: String, requestHost: String,
        importantOnly: Boolean = false
    ): Boolean {
        val urlLower = requestUrl.lowercase()
        val tokens = hashSetOf<String>()
        val sb = StringBuilder(urlLower.length)
        for (c in urlLower) {
            if (c.isLetterOrDigit()) sb.append(c) else {
                if (sb.length >= 3) tokens.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.length >= 3) tokens.add(sb.toString())
        for (part in requestHost.split('.')) if (part.length >= 3) tokens.add(part)
        for (kw in tokens) {
            val rules = index[kw] ?: continue
            for (r in rules) {
                if (importantOnly && !r.important) continue
                if (matchRule(r, urlLower, requestHost, pageHost)) return true
            }
        }
        return false
    }

    /** Pure-data matching — safe to call from Chromium threads. */
    fun shouldBlock(requestUrl: String, pageUrl: String): Boolean {
        val requestHost = hostOf(requestUrl)
        if (requestHost.isEmpty()) return false
        val pageHost = hostOf(pageUrl)
        // $important blocks beat any exception
        if (matchesAny(blockIndex, requestUrl, pageHost, requestHost, importantOnly = true)) return true
        if (matchesAny(allowIndex, requestUrl, pageHost, requestHost)) return false
        return matchesAny(blockIndex, requestUrl, pageHost, requestHost)
    }

    /** CSS selectors injected to hide ad elements on [pageHost]; exceptions applied. */
    fun elementHideCss(pageHost: String): String {
        val hide = ArrayList<Triple<List<String>, List<String>, String>>()   // (allow, deny, selector)
        val unhide = ArrayList<Pair<List<String>, String>>()
        lock.read {
            for (e in elemHide) hide.add(Triple(e.domainAllow, e.domainDeny, e.selector))
            for (u in elemUnhide) unhide.add(u.first to u.second)
        }
        if (hide.isEmpty()) return ""
        val selectors = ArrayList<String>(hide.size)
        for ((allow, deny, sel) in hide) {
            if (deny.isNotEmpty() && deny.any { hostMatchesDomain(pageHost, it) }) continue
            if (allow.isNotEmpty() && allow.none { hostMatchesDomain(pageHost, it) }) continue
            selectors.add(sel)
        }
        if (selectors.isEmpty()) return ""
        val excluded = unhide.filter { it.first.isEmpty() || it.first.any { hostMatchesDomain(pageHost, it) } }
            .map { it.second }.toSet()
        val kept = selectors.filter { it !in excluded }
        if (kept.isEmpty()) return ""
        return "/*zephyr:eh*/" + kept.joinToString(",") { escapeCss(it) } + "{display:none!important;visibility:hidden!important}"
    }

    private fun escapeCss(sel: String): String = sel.replace("\\", "\\\\").replace("\"", "\\\"").replace("*/", "* /")

    private val HOSTS_LINE = Regex("^(\\d{1,3}\\.){3}\\d{1,3}\\s+[\\w.\\-]+\\s*$")
    private val PROCS = PROC_MARKERS.split(',')
}
