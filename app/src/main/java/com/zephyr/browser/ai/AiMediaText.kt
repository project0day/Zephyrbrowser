package com.zephyr.browser.ai

import com.zephyr.browser.Prefs

/**
 * Media URLs in an assistant answer: finding them, and taking them back out.
 *
 * v1.9.10. These five lived inside AiAssistantSheet.show(), where only the
 * open chat could reach them. But the bubble is not the only place an answer
 * is shown: the chat list draws a preview of the last message, and the push
 * notification quotes the reply. Both were printing the raw text, so the
 * address of the user's private bridge appeared in the history list and on the
 * lock screen (user: «в истории в превью показывает ссылку нашего моста»).
 *
 * Top-level functions, not an object, so the existing unqualified calls in
 * AiAssistantSheet keep resolving without a diff.
 */

fun hostOf(url: String): String? = try {
    android.net.Uri.parse(url).host?.lowercase()
} catch (_: Throwable) { null }

fun hasMediaExtension(u: String): Boolean {
    val path = u.substringBefore('?').lowercase()
    return listOf(
        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp",
        ".wav", ".mp3", ".ogg", ".m4a", ".aac", ".opus"
    ).any { path.endsWith(it) }
}

/** Hosts the assistant itself talks to — its own files are fair game. */
fun mediaHosts(): Set<String> {
    val out = HashSet<String>()
    try {
        for (p in AiModels.list()) hostOf(p.endpoint)?.let { out.add(it) }
        hostOf(Prefs.agentServer)?.let { out.add(it) }
    } catch (_: Throwable) {}
    return out
}


/**
 * v1.7.46: media in the bubble instead of a link to open.
 *
 * A bridge that draws pictures cannot hold the HTTP connection for the
 * 30–90 seconds it takes — the platform's balancer cuts it. So it
 * answers instantly with a link and finishes in the background (user:
 * «он присылает ссылку… а я хочу чтобы точки мерцали и потом появлялась
 * фотография»). Zephyr probes the link's Content-Type and waits out the
 * generation with a placeholder.
 *
 * v1.7.47 FIX (user: «попросил открыть amazon.fr — а внизу „Генерирую
 * 207 сек"»): the first cut probed EVERY url in an answer, so an
 * ordinary link the model merely mentioned was treated as a picture
 * being born. A link now qualifies only when it is explicitly media —
 * Markdown image, `<img>`/`<audio>`, a media file extension — or when it
 * points at the very host the assistant is talking to. amazon.fr can no
 * longer be mistaken for a generated file.
 */
fun mediaCandidates(text: String): List<String> {
    val explicit = LinkedHashSet<String>()
    val plain = LinkedHashSet<String>()

    Regex("""!\[[^\]]*]\((\S+?)\)""").findAll(text)
        .forEach { explicit.add(it.groupValues[1]) }
    Regex("""<(?:audio|img)[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(text).forEach { explicit.add(it.groupValues[1]) }
    Regex("""\[[^\]]*]\((https?://\S+?)\)""").findAll(text)
        .forEach { plain.add(it.groupValues[1]) }
    Regex("""https?://[^\s<>"'()\[\]]+""").findAll(text)
        .forEach { plain.add(it.value) }

    fun clean(s: String) = s.trimEnd('.', ',', ';', ':', ')', '"', '\'')

    val hosts = mediaHosts()
    val picked = LinkedHashSet<String>()
    explicit.map(::clean).forEach { picked.add(it) }
    plain.map(::clean).forEach { u ->
        // v1.7.53: an API endpoint is never a file — quoting it in an
        // error must not start a generation watch
        val low = u.lowercase()
        val isApi = low.contains("/chat/completions") || low.endsWith("/v1") ||
            low.endsWith("/models") || low.contains("/api/v1/chat")
        if (!isApi && (hasMediaExtension(u) || hostOf(u) in hosts)) picked.add(u)
    }

    // one file can arrive as two links — the page and the raw bytes.
    // Keep one per id so the bubble never grows two placeholders.
    val byId = LinkedHashMap<String, String>()
    for (u in picked) {
        if (!u.startsWith("http", true) && !u.startsWith("data:", true)) continue
        val id = u.substringAfterLast('/').substringBefore('?').ifBlank { u }
        val existing = byId[id]
        // prefer the direct file over a wrapper page
        if (existing == null || (!hasMediaExtension(existing) && hasMediaExtension(u))) {
            byId[id] = u
        }
    }
    return byId.values.take(2)
}

/**
 * v1.9.7 (user: «не хочу тупых ссылок, хочу чисто — анимация, потом
 * картинка, как у любого ИИ»).
 *
 * Every media URL becomes a bubble, so leaving it in the text as well
 * shows the user the plumbing: a raw link above the picture it is
 * already displaying. This takes those URLs back out of the prose —
 * the markdown image form, the bracketed-link form, and the bare URL,
 * plus the empty parentheses and stray punctuation left behind.
 *
 * Only URLs that ARE being rendered are removed. An ordinary link the
 * assistant offers stays a link.
 */
fun stripMediaUrls(text: String, urls: Collection<String>): String {
    if (urls.isEmpty()) return text
    var out = text
    for (u in urls) {
        val q = Regex.escape(u)
        out = out
            .replace(Regex("""!\[[^\]]*]\(\s*$q\s*\)"""), "")
            .replace(Regex("""\[[^\]]*]\(\s*$q\s*\)"""), "")
            .replace(Regex("""<(?:audio|img)[^>]*src\s*=\s*["']$q["'][^>]*>""",
                RegexOption.IGNORE_CASE), "")
            .replace(u, "")
    }
    return out
        // "(  )" and "«»" left where a url used to sit
        .replace(Regex("""\(\s*\)"""), "")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""[ \t]+([.,!?:;])"""), "$1")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

/**
 * The answer as the user is allowed to see it elsewhere: no media plumbing.
 *
 * Used by the chat-list preview and the push notification. Both quote an
 * answer outside the bubble that renders its pictures, so both must lose the
 * URLs the same way copy and share do.
 */
fun plainAnswerText(raw: String): String =
    AiMarkdown.stripMarkdown(stripMediaUrls(raw, mediaCandidates(raw)))
