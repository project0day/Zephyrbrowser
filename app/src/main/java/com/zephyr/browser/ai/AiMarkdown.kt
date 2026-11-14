package com.zephyr.browser.ai

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View

/**
 * v1.7.25: tiny Markdown → Spannable renderer for AI replies.
 * Supports: # headers, **bold**, *italic*, `inline code`, ```code blocks```,
 * - / * bullets, 1. numbered lists, [text](url) links and bare autolinks.
 * Links are real [ClickableSpan]s so a tap opens the page in the browser.
 */
object AiMarkdown {

    private val AUTOLINK = Regex("""(?<![(\w])((?:https?://|www\.)[^\s<>()\[\]"']+)""")
    private val MD_LINK = Regex("""\[([^\]]+)]\((\S+?)\)""")

    fun render(
        raw: String,
        textColor: Int,
        mutedColor: Int,
        linkColor: Int,
        codeBg: Int,
        onLink: (String) -> Unit,
        /**
         * v1.7.49: tap handler for a filesystem path found in the answer
         * (user: «пусть путь будет кликабельным и открывает Файлы прямо на
         * этом файле»). Null leaves paths as plain text.
         */
        onPath: ((String) -> Unit)? = null
    ): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = raw.replace("\r\n", "\n").split("\n")
        var inFence = false
        var fenceStart = 0

        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                if (!inFence) {
                    inFence = true
                    fenceStart = sb.length
                } else {
                    inFence = false
                    if (sb.length > fenceStart) {
                        sb.setSpan(TypefaceSpan("monospace"), fenceStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(BackgroundColorSpan(codeBg), fenceStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(RelativeSizeSpan(0.92f), fenceStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                continue
            }
            if (inFence) {
                sb.append(line).append('\n')
                continue
            }

            var text = line
            var headerLevel = 0
            while (headerLevel < 4 && text.startsWith("#")) { headerLevel++; text = text.substring(1) }
            if (headerLevel > 0) text = text.trimStart()

            var bulletMark = false
            val trimmed = text.trimStart()
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
                val indent = text.length - trimmed.length
                text = " ".repeat(indent) + "•  " + trimmed.substring(2)
                bulletMark = true
            }
            // horizontal rule
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                val s = sb.length
                sb.append("──────────\n")
                sb.setSpan(ForegroundColorSpan(mutedColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            val start = sb.length
            appendInline(sb, text, linkColor, codeBg, onLink)
            val end = sb.length

            if (headerLevel > 0) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val scale = when (headerLevel) { 1 -> 1.28f; 2 -> 1.16f; 3 -> 1.07f; else -> 1.0f }
                sb.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (bulletMark) {
                sb.setSpan(ForegroundColorSpan(textColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append('\n')
        }

        // v1.7.49: absolute device paths become tappable
        if (onPath != null) linkifyPaths(sb, linkColor, onPath)

        // trailing newline trim
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    /** `/storage/emulated/0/Xlite` and friends — a real place on the device. */
    private val DEVICE_PATH = Regex(
        """(?<![\w/])(/(?:storage|sdcard|mnt|data/data|system)/[\w.\-@+ /]{2,180})"""
    )

    /**
     * v1.7.49: turn filesystem paths in an answer into taps.
     *
     * The assistant finds a file and prints where it is — and the user was left
     * to memorise the path and hunt for it by hand. Each path is now a link
     * into the browser's own file manager, which opens the folder with the file
     * highlighted.
     */
    private fun linkifyPaths(
        sb: SpannableStringBuilder,
        linkColor: Int,
        onPath: (String) -> Unit
    ) {
        val text = sb.toString()
        for (m in DEVICE_PATH.findAll(text)) {
            var path = m.groupValues[1].trimEnd('.', ',', ';', ':', ')', '»', '"')
            if (path.length < 8) continue
            val start = m.range.first
            val end = start + path.length
            // do not fight an existing link span over the same range
            val taken = sb.getSpans(start, end, ClickableSpan::class.java)
            if (taken.isNotEmpty()) continue
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onPath(path)
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = linkColor
                    ds.isUnderlineText = true
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Inline pass: links first (so their label is not re-scanned), then emphasis/code. */
    private fun appendInline(
        sb: SpannableStringBuilder,
        line: String,
        linkColor: Int,
        codeBg: Int,
        onLink: (String) -> Unit
    ) {
        val base = sb.length
        // 1. collect link ranges on the SOURCE string while stripping markdown syntax
        data class Link(val start: Int, val end: Int, val url: String)
        val links = ArrayList<Link>()
        val out = StringBuilder()

        var i = 0
        while (i < line.length) {
            val rest = line.substring(i)
            val md = MD_LINK.find(rest)
            val auto = AUTOLINK.find(rest)
            val mdAt = md?.range?.first ?: Int.MAX_VALUE
            val autoAt = auto?.range?.first ?: Int.MAX_VALUE
            if (mdAt == Int.MAX_VALUE && autoAt == Int.MAX_VALUE) { out.append(rest); break }
            if (mdAt <= autoAt) {
                out.append(rest, 0, mdAt)
                val label = md!!.groupValues[1]
                val url = md.groupValues[2]
                val s = out.length
                out.append(label)
                links.add(Link(s, out.length, url))
                i += md.range.last + 1
            } else {
                out.append(rest, 0, autoAt)
                val url = auto!!.groupValues[1].trimEnd('.', ',', ';', ':', ')')
                val s = out.length
                out.append(url)
                links.add(Link(s, out.length, if (url.startsWith("www.")) "https://$url" else url))
                i += autoAt + url.length
            }
        }

        // 2. emphasis + inline code on the link-stripped text
        var body = out.toString()
        data class Mark(val start: Int, val end: Int, val kind: Int) // 0 bold 1 italic 2 code
        val marks = ArrayList<Mark>()

        fun strip(token: String, kind: Int) {
            var idx = 0
            while (true) {
                val a = body.indexOf(token, idx)
                if (a < 0) break
                val b = body.indexOf(token, a + token.length)
                if (b < 0) break
                val inner = body.substring(a + token.length, b)
                if (inner.isBlank()) { idx = b + token.length; continue }
                body = body.substring(0, a) + inner + body.substring(b + token.length)
                // shift previously recorded marks + links that sit after the removal
                val shiftFirst = token.length
                val shiftSecond = token.length
                for (k in marks.indices) {
                    val m = marks[k]
                    var s = m.start; var e = m.end
                    if (s > a) s -= shiftFirst
                    if (e > a) e -= shiftFirst
                    if (s > b - shiftFirst) s -= shiftSecond
                    if (e > b - shiftFirst) e -= shiftSecond
                    marks[k] = Mark(s, e, m.kind)
                }
                for (k in links.indices) {
                    val l = links[k]
                    var s = l.start; var e = l.end
                    if (s > a) s -= shiftFirst
                    if (e > a) e -= shiftFirst
                    if (s > b - shiftFirst) s -= shiftSecond
                    if (e > b - shiftFirst) e -= shiftSecond
                    links[k] = Link(s, e, l.url)
                }
                marks.add(Mark(a, a + inner.length, kind))
                idx = a + inner.length
            }
        }
        strip("**", 0)
        strip("`", 2)
        strip("*", 1)

        sb.append(body)

        for (m in marks) {
            if (m.start < 0 || m.end > body.length || m.start >= m.end) continue
            when (m.kind) {
                0 -> sb.setSpan(StyleSpan(Typeface.BOLD), base + m.start, base + m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                1 -> sb.setSpan(StyleSpan(Typeface.ITALIC), base + m.start, base + m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                2 -> {
                    sb.setSpan(TypefaceSpan("monospace"), base + m.start, base + m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(codeBg), base + m.start, base + m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        for (l in links) {
            if (l.start < 0 || l.end > body.length || l.start >= l.end) continue
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onLink(l.url)
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = linkColor
                    ds.isUnderlineText = true
                }
            }, base + l.start, base + l.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Plain-text version (for copy-to-clipboard). */
    fun stripMarkdown(raw: String): String = raw
        .replace(MD_LINK) { it.groupValues[1] + " (" + it.groupValues[2] + ")" }
        .replace("```", "")
        .replace("**", "")
        .replace("`", "")
}
