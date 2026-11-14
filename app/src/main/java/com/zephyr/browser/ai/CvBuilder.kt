package com.zephyr.browser.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.7.68: build the CV from DATA, not from generated markup.
 *
 * Two failures pushed this: the assistant took a long time (it had to emit two
 * hundred lines of HTML before anything happened) and the result sometimes did
 * not render at all (one broken tag anywhere and the page is gone). Another
 * model, asked what went wrong, arrived at the same prescription — take the
 * template, substitute the text, never let the model touch the layout.
 *
 * So the model now sends a small JSON object: name, role, contacts, summary,
 * jobs, education, skills, languages. The layout, the colour and the escaping
 * live here, where they cannot be broken by a missing `</div>`.
 *
 * [AiHtmlPdf] still accepts free-form HTML for everything else — this is the
 * fast, safe path for the one document that matters most.
 */
object CvBuilder {

    /** Ready-made palettes, so "in blue" does not become a hex-code negotiation. */
    private val PALETTE = mapOf(
        "blue" to "#1f4e79",
        "teal" to "#0f7b6c",
        "green" to "#1f6f43",
        "purple" to "#4b3b8f",
        "red" to "#8a1c1c",
        "orange" to "#b45309",
        "graphite" to "#2f3437",
        "black" to "#111418"
    )

    fun paletteNames(): String = PALETTE.keys.joinToString(", ")

    fun accentOf(name: String): String {
        val n = name.trim().lowercase()
        if (n.startsWith("#") && (n.length == 7 || n.length == 4)) return n
        return PALETTE[n] ?: PALETTE.getValue("blue")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun strings(o: JSONObject, key: String): List<String> {
        val out = ArrayList<String>()
        when (val v = o.opt(key)) {
            is JSONArray -> for (i in 0 until v.length()) {
                v.opt(i)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
            is String -> v.split("\n", ";", "·").map { it.trim() }
                .filter { it.isNotEmpty() }.forEach { out.add(it) }
        }
        return out
    }

    private fun objects(o: JSONObject, key: String): List<JSONObject> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    /**
     * Render [data] into a complete page.
     *
     * Missing sections are omitted rather than left as empty headings: a CV with
     * a bare "Languages" title looks unfinished in a way a missing section does
     * not.
     */
    fun build(template: String, accentName: String, data: JSONObject, lang: String = ""): String {
        // v1.7.88: the headings follow the DOCUMENT's language, not the app's —
        // a French devis written from a Russian interface must say "Expérience",
        // not "Опыт работы". See DocLang.
        val w = DocLang.words(lang)
        val accent = accentOf(accentName)
        val name = data.optString("name").ifBlank { w.fullName }
        val role = data.optString("role")
        val photo = data.optString("photo")
        val summary = data.optString("summary")
        val contacts = strings(data, "contacts")
        val skills = strings(data, "skills")
        val languages = strings(data, "languages")
        val jobs = objects(data, "experience")
        val education = objects(data, "education")

        fun jobBlock(j: JSONObject, accentText: Boolean): String {
            val title = esc(j.optString("title"))
            val where = esc(j.optString("where"))
            val whenS = esc(j.optString("when"))
            val points = strings(j, "points")
            val sb = StringBuilder("<div class=\"job\">")
            sb.append("<div class=\"top\"><span class=\"title\">").append(title)
                .append("</span><span class=\"when\">").append(whenS).append("</span></div>")
            if (where.isNotEmpty()) sb.append("<div class=\"where\">").append(where).append("</div>")
            if (points.isNotEmpty()) {
                sb.append("<ul>")
                for (p in points) sb.append("<li>").append(esc(p)).append("</li>")
                sb.append("</ul>")
            }
            sb.append("</div>")
            return sb.toString()
        }

        val expHtml = jobs.joinToString("") { jobBlock(it, false) }
        val eduHtml = education.joinToString("") { jobBlock(it, false) }

        return when (template.trim().lowercase()) {
            "classic" -> classic(w, accent, name, role, summary, contacts, expHtml, eduHtml, skills, languages)
            "minimal" -> minimal(w, accent, name, role, summary, contacts, expHtml, eduHtml, skills, languages)
            else -> sidebar(w, accent, name, role, photo, summary, contacts, expHtml, eduHtml, skills, languages)
        }
    }

    private const val FONTS =
        "'Roboto','Noto Sans','Droid Sans','Helvetica Neue',Arial,sans-serif"

    private fun head(accent: String, extra: String) = """
<!doctype html><html lang="ru"><head><meta charset="utf-8"><style>
  :root{ --accent:$accent; --ink:#1b1d21; --muted:#5b6470; }
  *{ box-sizing:border-box; -webkit-print-color-adjust:exact; print-color-adjust:exact; }
  @page{ size:A4; margin:0; }
  html,body{ margin:0; padding:0; }
  body{ font-family:$FONTS; color:var(--ink); font-size:10.5pt; line-height:1.45; }
  .job{ margin-bottom:4.6mm; }
  .job .top{ display:flex; justify-content:space-between; gap:4mm; align-items:baseline; }
  .job .title{ font-weight:700; }
  .job .when{ color:var(--muted); font-size:9pt; white-space:nowrap; }
  .job .where{ color:var(--accent); font-size:9.5pt; margin-bottom:1mm; }
  .job ul{ margin:1mm 0 0; padding-left:5mm; }
  .job li{ margin-bottom:.8mm; }
  $extra
</style></head>
""".trimIndent()

    private fun sidebar(
        w: DocLang.Words,
        accent: String, name: String, role: String, photo: String, summary: String,
        contacts: List<String>, exp: String, edu: String,
        skills: List<String>, languages: List<String>
    ): String {
        val photoTag = if (photo.startsWith("data:image")) {
            "<img class=\"photo\" src=\"" + photo + "\" alt=\"\">"
        } else ""
        val skillHtml = skills.joinToString("") { s ->
            // "навык|80" gives the bar a level; without it the bar is skipped
            val parts = s.split("|")
            val label = esc(parts[0].trim())
            val pct = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (pct != null) "<div>$label<div class=\"bar\"><i style=\"width:${pct.coerceIn(10, 100)}%\"></i></div></div>"
            else "<div class=\"plain\">$label</div>"
        }
        return head(accent, """
  .sheet{ width:210mm; min-height:296mm; display:flex; }
  .side{ width:68mm; background:var(--accent); color:#fff; padding:15mm 9mm; }
  .main{ flex:1; padding:15mm 12mm 12mm 11mm; }
  .photo{ width:36mm; height:36mm; border-radius:50%; object-fit:cover; display:block;
          margin:0 auto 6mm; border:3px solid rgba(255,255,255,.55); }
  .side h1{ font-size:16.5pt; margin:0 0 2mm; line-height:1.15; }
  .side .role{ font-size:10pt; opacity:.92; margin:0 0 7mm; }
  .side h2{ font-size:8.5pt; text-transform:uppercase; letter-spacing:1.4px; margin:6mm 0 2.5mm;
            padding-bottom:1.4mm; border-bottom:1px solid rgba(255,255,255,.35); }
  .side div,.side li{ font-size:9.5pt; }
  .side ul{ margin:0; padding-left:4.5mm; }
  .contact div{ margin-bottom:1.5mm; word-break:break-word; }
  .plain{ margin-bottom:1.4mm; }
  .bar{ height:3.4px; background:rgba(255,255,255,.28); border-radius:2px; margin:1mm 0 2.6mm; }
  .bar i{ display:block; height:100%; background:#fff; border-radius:2px; }
  .main h2{ font-size:10.5pt; color:var(--accent); text-transform:uppercase;
            letter-spacing:1.2px; margin:0 0 3mm; }
  .main section{ margin-bottom:6.5mm; }
  .summary{ background:${accent}14; border-left:3px solid var(--accent);
            padding:3.4mm 4mm; margin-bottom:6.5mm; font-size:10pt; }
""") + """
<body><div class="sheet">
  <aside class="side">
    $photoTag
    <h1>${esc(name)}</h1>
    ${if (role.isNotBlank()) "<p class=\"role\">" + esc(role) + "</p>" else ""}
    ${section(w.contacts, "<div class=\"contact\">" + contacts.joinToString("") { "<div>" + esc(it) + "</div>" } + "</div>", contacts.isNotEmpty())}
    ${section(w.skills, skillHtml, skills.isNotEmpty())}
    ${section(w.languages, "<ul>" + languages.joinToString("") { "<li>" + esc(it) + "</li>" } + "</ul>", languages.isNotEmpty())}
  </aside>
  <main class="main">
    ${if (summary.isNotBlank()) "<div class=\"summary\">" + esc(summary) + "</div>" else ""}
    ${if (exp.isNotBlank()) "<section><h2>" + w.experience + "</h2>$exp</section>" else ""}
    ${if (edu.isNotBlank()) "<section><h2>" + w.education + "</h2>$edu</section>" else ""}
  </main>
</div></body></html>
""".trimIndent()
    }

    private fun section(title: String, body: String, show: Boolean) =
        if (show) "<h2>$title</h2>$body" else ""

    private fun classic(
        w: DocLang.Words,
        accent: String, name: String, role: String, summary: String,
        contacts: List<String>, exp: String, edu: String,
        skills: List<String>, languages: List<String>
    ): String = head(accent, """
  .sheet{ width:210mm; min-height:296mm; padding:16mm 18mm; }
  header{ border-bottom:2.5px solid var(--accent); padding-bottom:4mm; margin-bottom:5.5mm; }
  h1{ font-size:21pt; margin:0 0 1.5mm; }
  .role{ color:var(--accent); font-size:11pt; margin:0 0 2.5mm; }
  .contact{ color:var(--muted); font-size:9.5pt; }
  .contact span{ margin-right:5mm; white-space:nowrap; }
  h2{ font-size:10.5pt; text-transform:uppercase; letter-spacing:1.5px;
      color:var(--accent); margin:6mm 0 2.5mm; }
  .skills{ display:flex; flex-wrap:wrap; gap:2mm; }
  .skills span{ border:1px solid var(--accent); color:var(--accent);
                border-radius:3px; padding:.8mm 2.6mm; font-size:9pt; }
""") + """
<body><div class="sheet">
  <header>
    <h1>${esc(name)}</h1>
    ${if (role.isNotBlank()) "<p class=\"role\">" + esc(role) + "</p>" else ""}
    <div class="contact">${contacts.joinToString("") { "<span>" + esc(it) + "</span>" }}</div>
  </header>
  ${if (summary.isNotBlank()) "<section><h2>" + w.about + "</h2><p>" + esc(summary) + "</p></section>" else ""}
  ${if (exp.isNotBlank()) "<section><h2>" + w.experience + "</h2>$exp</section>" else ""}
  ${if (edu.isNotBlank()) "<section><h2>" + w.education + "</h2>$edu</section>" else ""}
  ${if (skills.isNotEmpty()) "<section><h2>" + w.skills + "</h2><div class=\"skills\">" + skills.joinToString("") { "<span>" + esc(it.substringBefore('|')) + "</span>" } + "</div></section>" else ""}
  ${if (languages.isNotEmpty()) "<section><h2>" + w.languages + "</h2><p>" + languages.joinToString(" · ") { esc(it) } + "</p></section>" else ""}
</div></body></html>
""".trimIndent()

    private fun minimal(
        w: DocLang.Words,
        accent: String, name: String, role: String, summary: String,
        contacts: List<String>, exp: String, edu: String,
        skills: List<String>, languages: List<String>
    ): String = head(accent, """
  .sheet{ width:210mm; min-height:296mm; padding:19mm 20mm 14mm; }
  h1{ font-size:29pt; font-weight:300; letter-spacing:-.5px; margin:0; }
  .role{ color:var(--accent); font-size:11pt; letter-spacing:2.4px;
         text-transform:uppercase; margin:2mm 0 3.5mm; }
  .contact{ color:var(--muted); font-size:9.5pt; margin-bottom:8mm; }
  hr{ border:0; border-top:1px solid #e6e8ea; margin:0 0 6mm; }
  .row{ display:flex; gap:7mm; margin-bottom:5.5mm; }
  .row .label{ width:32mm; flex:none; color:var(--accent); font-size:9pt;
               text-transform:uppercase; letter-spacing:1.3px; padding-top:1mm; }
  .row .body{ flex:1; }
""") + """
<body><div class="sheet">
  <h1>${esc(name)}</h1>
  ${if (role.isNotBlank()) "<p class=\"role\">" + esc(role) + "</p>" else ""}
  <div class="contact">${contacts.joinToString(" · ") { esc(it) }}</div>
  <hr>
  ${row(w.about, if (summary.isBlank()) "" else esc(summary))}
  ${row(w.experience, exp)}
  ${row(w.education, edu)}
  ${row(w.skills, skills.joinToString(" · ") { esc(it.substringBefore('|')) })}
  ${row(w.languages, languages.joinToString(" · ") { esc(it) })}
</div></body></html>
""".trimIndent()

    private fun row(label: String, body: String) =
        if (body.isBlank()) "" else "<div class=\"row\"><div class=\"label\">$label</div><div class=\"body\">$body</div></div>"
}
