package com.zephyr.browser.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.7.70 «Шаблоны» (user: «я auto-entrepreneur — нашёл клиента, сказал ИИ
 * сделать devis, он заполнил по моему требованию, и я отправил клиенту»).
 *
 * The idea is stronger than "one more feature" because it closes a loop a
 * self-employed person walks every week: quote → invoice → send. Today that is
 * a Word file opened, edited over the last client's data, exported and
 * attached. The pieces to replace it were all already here — browser-grade
 * layout, PDF, memory, folders, scheduling — and only the middle was missing:
 * a document that remembers its own shape.
 *
 * What makes it a construction kit rather than a stack of forms: a template IS
 * HTML, so the ASSISTANT writes it. The user does not wait for someone to draw
 * a quote form, then an invoice form, then a contract. They describe it once.
 *
 * Stored as ordinary files under the workspace — visible, editable, backed up
 * with everything else — with one small index beside them:
 *
 *   Documents/Zephyr/Templates/
 *     index.json
 *     Коммерция/devis.html
 *     Документы/attestation.html
 *     Программы/calc.html
 */
object AiTemplates {

    const val KIND_DOC = "document"
    const val KIND_APP = "app"

    /**
     * v1.7.73 (user: «добавь возможность загрузить свой шаблон — многие уже
     * имеют свои и не хотят создавать другие»).
     *
     * An imported file keeps its own format rather than being converted:
     *
     *  - DOCX is filled by substituting placeholders inside the archive, so
     *    the user logo, fonts and tables survive exactly as their accountant
     *    approved them — a conversion to HTML would not;
     *  - PDF is filled by stamping onto the page, the same machinery that fills
     *    a downloaded government form;
     *  - HTML is the native case and needs no explanation.
     */
    const val KIND_DOCX = "docx"
    const val KIND_PDF = "pdf"

    /**
     * Folders offered by default; a template may name any other.
     *
     * v1.7.88: these were Russian literals, so an English user's templates were
     * filed under «Коммерция». A `get()` rather than a `val`, because the app
     * language can change while the process is alive.
     *
     * Note the trade-off. A group name IS the folder name on disk, and there is
     * no separate key to translate against. Templates already saved keep their
     * folders — `all()` picks those up — so switching language leaves the old
     * folders in place and starts offering new ones beside them. That is
     * visible and harmless; silently renaming someone's folders would not be.
     */
    val CATEGORIES: List<String>
        get() = listOf(
            s(com.zephyr.browser.R.string.tpl_cat_commerce),
            s(com.zephyr.browser.R.string.tpl_cat_documents),
            s(com.zephyr.browser.R.string.tpl_cat_letters),
            s(com.zephyr.browser.R.string.tpl_cat_apps)
        )

    private fun s(res: Int): String =
        try { com.zephyr.browser.App.ctx?.getString(res).orEmpty() } catch (_: Throwable) { "" }

    class Template(
        val id: Long,
        val name: String,
        val category: String,
        val kind: String,
        val file: File,
        /** Placeholders the template expects, in the order to ask for them. */
        val fields: List<String>,
        val note: String,
        val at: Long
    ) {
        val isApp: Boolean get() = kind == KIND_APP
        val isDocx: Boolean get() = kind == KIND_DOCX
        val isPdf: Boolean get() = kind == KIND_PDF
        /** True when filling means rendering HTML. */
        val isHtml: Boolean get() = kind == KIND_DOC || kind == KIND_APP
        fun preview(): File = File(file.parentFile, file.nameWithoutExtension + ".preview.jpg")
    }

    fun root(): File = File(AiWorkspace.root(), "Templates").apply { if (!exists()) mkdirs() }

    private fun indexFile(): File = File(root(), "index.json")

    // ---------------- index ----------------

    fun all(): List<Template> {
        val f = indexFile()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Template>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("file")
                val file = File(path)
                // an index entry whose file the user deleted is not a template
                if (!file.isFile) continue
                val fields = ArrayList<String>()
                o.optJSONArray("fields")?.let { fa ->
                    for (k in 0 until fa.length()) fa.optString(k).takeIf { it.isNotBlank() }?.let { fields.add(it) }
                }
                out.add(
                    Template(
                        o.optLong("id"), o.optString("name"),
                        o.optString("category").ifBlank { CATEGORIES[0] },
                        o.optString("kind").ifBlank { KIND_DOC },
                        file, fields, o.optString("note"), o.optLong("at")
                    )
                )
            }
            out.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
        } catch (_: Throwable) { emptyList() }
    }

    private fun writeIndex(list: List<Template>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("id", t.id).put("name", t.name).put("category", t.category)
                    .put("kind", t.kind).put("file", t.file.absolutePath)
                    .put("note", t.note).put("at", t.at)
                    .put("fields", JSONArray().apply { t.fields.forEach { put(it) } })
            )
        }
        try { indexFile().writeText(arr.toString()) } catch (_: Throwable) {}
    }

    fun byName(name: String): Template? {
        val n = name.trim()
        return all().firstOrNull { it.name.equals(n, true) }
    }

    fun categories(): List<String> =
        (CATEGORIES + all().map { it.category }).distinct()

    // ---------------- writing ----------------

    private fun safeName(s: String): String =
        s.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").take(60).ifBlank { "template" }

    /**
     * Create or replace a template by name.
     *
     * Replacing rather than appending, for the same reason scenarios do: saying
     * "сделай шаблон devis" twice should refine the one that exists, not leave
     * two called devis that behave differently.
     */
    fun put(
        name: String, category: String, kind: String,
        html: String, fields: List<String>, note: String
    ): Template? {
        val n = safeName(name)
        if (html.isBlank()) return null
        val cat = safeName(category.ifBlank { CATEGORIES[0] })
        val dir = File(root(), cat).apply { if (!exists()) mkdirs() }
        val file = File(dir, "$n.html")
        try { file.writeText(html) } catch (_: Throwable) { return null }

        val list = all().toMutableList()
        val existing = list.indexOfFirst { it.name.equals(n, true) }
        val t = Template(
            if (existing >= 0) list[existing].id else System.currentTimeMillis(),
            n, cat, if (kind == KIND_APP) KIND_APP else KIND_DOC,
            file, fields, note, System.currentTimeMillis()
        )
        register(list, existing, t)
        return t
    }

    /**
     * Adopt a file the user already has as a template.
     *
     * The bytes are copied rather than referenced: a template that lives in
     * Downloads disappears the day the user tidies up, and a quote form that
     * vanishes mid-job is worse than one that takes 40 KB.
     */
    fun import(
        name: String, category: String, kind: String,
        source: java.io.InputStream, extension: String, note: String
    ): Template? {
        val n = safeName(name)
        val cat = safeName(category.ifBlank { CATEGORIES[0] })
        val dir = File(root(), cat).apply { if (!exists()) mkdirs() }
        val file = File(dir, "$n.$extension")
        try {
            file.outputStream().use { out -> source.copyTo(out) }
        } catch (_: Throwable) { return null }

        // whatever placeholders the imported file already contains
        val fields = try {
            when (kind) {
                KIND_DOCX -> placeholdersOf(AiOffice.read(file).text)
                KIND_PDF -> emptyList()
                else -> placeholdersOf(file.readText())
            }
        } catch (_: Throwable) { emptyList() }

        val list = all().toMutableList()
        val existing = list.indexOfFirst { it.name.equals(n, true) }
        val t = Template(
            if (existing >= 0) list[existing].id else System.currentTimeMillis(),
            n, cat, kind, file, fields, note, System.currentTimeMillis()
        )
        register(list, existing, t)
        return t
    }

    private fun register(list: MutableList<Template>, existing: Int, t: Template) {
        // moving between folders (or changing format) leaves the old file behind
        if (existing >= 0) {
            val old = list[existing]
            if (old.file.absolutePath != t.file.absolutePath) {
                try { old.file.delete(); old.preview().delete() } catch (_: Throwable) {}
            }
            list[existing] = t
        } else list.add(t)
        writeIndex(list)
    }

    fun remove(id: Long): Boolean {
        val list = all()
        val gone = list.firstOrNull { it.id == id } ?: return false
        try { gone.file.delete(); gone.preview().delete() } catch (_: Throwable) {}
        writeIndex(list.filter { it.id != id })
        return true
    }

    // ---------------- filling ----------------

    /**
     * `{{name}}` and `{{ name }}` both count; unknown ones are left visible.
     *
     * v1.7.71 CRASH FIX: the closing braces were written bare as `}}`. Java's
     * own engine accepts that, so it looks correct and compiles — but Android
     * uses ICU, which rejects an unescaped `}` and throws while this object is
     * being initialised. The screen then died before drawing a single row.
     * Both braces are escaped on both sides now.
     */
    private val PLACEHOLDER = Regex("""\{\{\s*([\w.\-]+)\s*\}\}""")

    /** Every placeholder a template contains, in the order it first appears. */
    fun placeholdersOf(html: String): List<String> =
        PLACEHOLDER.findAll(html).map { it.groupValues[1] }.distinct().toList()

    class Filled(val html: String, val missing: List<String>)

    /**
     * Substitute [values] into the template.
     *
     * A placeholder with no value is REPORTED rather than blanked: an invoice
     * silently missing its amount is worse than one that says which field was
     * not supplied.
     */
    fun fill(html: String, values: Map<String, String>): Filled {
        val missing = LinkedHashSet<String>()
        val out = PLACEHOLDER.replace(html) { m ->
            val key = m.groupValues[1]
            val v = values[key] ?: values[key.lowercase()]
            if (v == null) { missing.add(key); m.value } else esc(v)
        }
        return Filled(out, missing.toList())
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * v1.7.75 (user: «подпись не идёт в документ»; the model diagnosed it
     * correctly — «в самом HTML шаблона нет блока для картинки подписи»).
     *
     * It was right, and the design was wrong: `{{signature}}` only worked if
     * the template happened to contain it, which no imported or older template
     * does. "Подпиши за меня" cannot depend on a placeholder the user never
     * heard of.
     *
     * So when a signature is asked for and the template has nowhere to put one,
     * a signature block is appended before the end of the page — name, image
     * and date, in the document's own font. An existing `{{signature}}` still
     * wins, because a template that placed it deliberately knows better where
     * it belongs.
     */
    fun ensureSignature(html: String, imageDataUri: String, name: String, date: String): String {
        if (html.contains("{{signature}}") || html.contains("{{ signature }}")) return html

        val block = """
<div style="margin-top:12mm;page-break-inside:avoid;text-align:right;font-size:10pt;">
  <div style="display:inline-block;text-align:center;min-width:55mm;">
    <img src="$imageDataUri" style="max-height:20mm;max-width:55mm;display:block;margin:0 auto 1mm;">
    <div style="border-top:1px solid #9aa2ad;padding-top:1.5mm;">${esc(name)}</div>
    <div style="color:#6b7280;font-size:9pt;">${esc(date)}</div>
  </div>
</div>
""".trimIndent()

        val end = html.lastIndexOf("</body>", ignoreCase = true)
        return if (end >= 0) html.substring(0, end) + block + html.substring(end)
        else html + block
    }
}
