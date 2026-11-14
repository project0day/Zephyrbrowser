package com.zephyr.browser.ai

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * v1.7.62 (user: «а exel word итд файлы тоже может или нет?»).
 *
 * They can, and without another library. A .docx, .xlsx or .pptx is a ZIP of
 * XML files — the format was designed to be opened by anything — and the
 * browser already zips and unzips. So this is a few hundred lines of plain
 * Java, not another six megabytes on top of PDFBox.
 *
 * What is honest about the boundary:
 *
 *  - READING works properly for all three: the text is in the XML, and pulling
 *    it out is reliable;
 *  - FILLING works by replacing placeholders in a .docx. It is not a general
 *    editor, and it can miss a placeholder Word split across two runs — which
 *    is reported rather than hidden, because a silently unfilled contract is
 *    worse than a clear failure;
 *  - CREATING produces a minimal but valid document, which Word, LibreOffice
 *    and Google Docs all open. No styling beyond paragraphs and a bold first
 *    line, deliberately: a broken fancy file is worth less than a plain one.
 *
 * The old binary .doc and .xls are NOT supported. They are a different format
 * entirely, undocumented in practice, and would need Apache POI — tens of
 * megabytes. A user with one of those is told to convert it, not left guessing.
 */
object AiOffice {

    private const val MAX_TEXT = 20000

    enum class Kind { DOCX, XLSX, PPTX, LEGACY, OTHER }

    fun kindOf(file: File): Kind = when (file.extension.lowercase()) {
        "docx" -> Kind.DOCX
        "xlsx", "xlsm" -> Kind.XLSX
        "pptx" -> Kind.PPTX
        "doc", "xls", "ppt" -> Kind.LEGACY
        else -> Kind.OTHER
    }

    // ---------------- reading ----------------

    class Doc(val kind: Kind, val text: String, val truncated: Boolean, val note: String?)

    fun read(file: File): Doc {
        val kind = kindOf(file)
        if (kind == Kind.LEGACY) throw IllegalArgumentException(
            "Старый формат " + file.extension + " (до 2007 года) не поддерживается. " +
                "Пересохраните файл как .docx или .xlsx — их я читаю."
        )
        if (kind == Kind.OTHER) throw IllegalArgumentException(
            "Это не документ Office: " + file.name
        )
        ZipFile(file).use { zip ->
            val raw = when (kind) {
                Kind.DOCX -> readDocx(zip)
                Kind.XLSX -> readXlsx(zip)
                else -> readPptx(zip)
            }
            return Doc(
                kind,
                raw.take(MAX_TEXT).trim(),
                raw.length > MAX_TEXT,
                if (raw.isBlank()) "Текста не найдено — возможно, документ состоит из картинок." else null
            )
        }
    }

    private fun entryText(zip: ZipFile, name: String): String? =
        zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

    private fun readDocx(zip: ZipFile): String {
        val xml = entryText(zip, "word/document.xml") ?: return ""
        return docxBodyToText(xml)
    }

    /** `<w:t>` holds the text; `</w:p>` ends a paragraph; `<w:br/>` a line. */
    private fun docxBodyToText(xml: String): String {
        val sb = StringBuilder()
        val re = Regex("""<w:t[^>]*>(.*?)</w:t>|</w:p>|<w:br\s*/>|<w:tab\s*/>""", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(xml)) {
            when {
                m.groupValues[1].isNotEmpty() || m.value.startsWith("<w:t") -> sb.append(unescape(m.groupValues[1]))
                m.value == "</w:p>" -> sb.append('\n')
                m.value.startsWith("<w:tab") -> sb.append('\t')
                else -> sb.append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Cells reference a shared string table by index when `t="s"`, and hold the
     * value inline otherwise. Getting that wrong turns a spreadsheet into a
     * column of numbers, so both paths are handled.
     */
    private fun readXlsx(zip: ZipFile): String {
        val shared = ArrayList<String>()
        entryText(zip, "xl/sharedStrings.xml")?.let { xml ->
            for (m in Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val text = Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(m.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
                shared.add(text)
            }
        }
        val sb = StringBuilder()
        val sheets = zip.entries().toList()
            .map { it.name }
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
        for (sheet in sheets) {
            val xml = entryText(zip, sheet) ?: continue
            if (sheets.size > 1) sb.append("\n--- ").append(sheet.substringAfterLast('/')).append(" ---\n")
            for (row in Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val cells = ArrayList<String>()
                for (c in Regex("""<c\b([^>]*)>(.*?)</c>""", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1])) {
                    val attrs = c.groupValues[1]
                    val body = c.groupValues[2]
                    val v = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
                        .find(body)?.groupValues?.get(1)
                    val inline = Regex("""<is>.*?<t[^>]*>(.*?)</t>.*?</is>""", RegexOption.DOT_MATCHES_ALL)
                        .find(body)?.groupValues?.get(1)
                    val text = when {
                        inline != null -> unescape(inline)
                        v == null -> ""
                        attrs.contains("""t="s"""") -> shared.getOrElse(v.toIntOrNull() ?: -1) { "" }
                        else -> unescape(v)
                    }
                    cells.add(text)
                }
                if (cells.any { it.isNotBlank() }) sb.append(cells.joinToString("\t")).append('\n')
            }
        }
        return sb.toString()
    }

    private fun readPptx(zip: ZipFile): String {
        val sb = StringBuilder()
        val slides = zip.entries().toList().map { it.name }
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        for ((i, s) in slides.withIndex()) {
            val xml = entryText(zip, s) ?: continue
            sb.append("\n--- Слайд ").append(i + 1).append(" ---\n")
            for (m in Regex("""<a:t>(.*?)</a:t>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                sb.append(unescape(m.groupValues[1])).append('\n')
            }
        }
        return sb.toString()
    }

    // ---------------- filling a .docx ----------------

    class FillResult(val out: File, val replaced: List<String>, val notFound: List<String>)

    /**
     * Replace placeholders in a .docx and save a new file.
     *
     * Every other entry in the archive is copied byte for byte — only
     * document.xml is touched — so images, styles and headers survive exactly.
     *
     * The known limitation is Word's own doing: it splits a run wherever it
     * records an edit, so "{{name}}" can live in the file as "{{na" + "me}}"
     * and a plain replacement will miss it. A de-splitting pass runs first for
     * the common case, and anything still not found is reported by name.
     */
    fun fillDocx(file: File, values: Map<String, String>, suffix: String = "-filled"): FillResult {
        if (kindOf(file) != Kind.DOCX) throw IllegalArgumentException(
            "Подстановка работает только с .docx"
        )
        val out = sibling(file, suffix, "docx")
        val replaced = ArrayList<String>()
        val notFound = ArrayList<String>()

        ZipFile(file).use { zip ->
            ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
                for (e in zip.entries()) {
                    zos.putNextEntry(ZipEntry(e.name))
                    if (e.name == "word/document.xml") {
                        var xml = entryText(zip, e.name).orEmpty()
                        xml = mergeSplitRuns(xml)
                        for ((k, v) in values) {
                            val esc = escape(v)
                            if (xml.contains(k)) {
                                xml = xml.replace(k, esc)
                                replaced.add(k)
                            } else notFound.add(k)
                        }
                        zos.write(xml.toByteArray(Charsets.UTF_8))
                    } else {
                        zip.getInputStream(e).use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
            }
        }
        return FillResult(out, replaced, notFound)
    }

    /**
     * Glue together adjacent text runs that carry identical formatting.
     *
     * This is what makes placeholder replacement work on documents a human has
     * edited: Word records every edit as a run boundary, and the boundary is
     * invisible to the reader but fatal to a string search.
     */
    private fun mergeSplitRuns(xml: String): String =
        xml.replace(Regex("""</w:t>\s*</w:r>\s*<w:r\b[^>]*>\s*<w:t[^>]*>"""), "")

    // ---------------- creating ----------------

    /** A minimal but valid .docx: one paragraph per line of [text]. */
    fun createDocx(target: File, text: String): File {
        val paragraphs = text.split("\n").joinToString("") { line ->
            "<w:p><w:r><w:t xml:space=\"preserve\">" + escape(line) + "</w:t></w:r></w:p>"
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr/></w:body></w:document>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

        writeZip(target, mapOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rels,
            "word/document.xml" to document
        ))
        return target
    }

    /**
     * A minimal .xlsx from rows of cells.
     *
     * Everything is written as an inline string rather than through the shared
     * string table: the table saves space in a big workbook and costs
     * correctness in a small one, and these are small.
     */
    fun createXlsx(target: File, rows: List<List<String>>): File {
        val sb = StringBuilder()
        for ((r, row) in rows.withIndex()) {
            sb.append("<row r=\"").append(r + 1).append("\">")
            for ((c, cell) in row.withIndex()) {
                sb.append("<c r=\"").append(colName(c)).append(r + 1)
                if (cell.toDoubleOrNull() != null) {
                    sb.append("\"><v>").append(cell).append("</v></c>")
                } else {
                    sb.append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(cell)).append("</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sb</sheetData></worksheet>"""

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Лист1" sheetId="1" r:id="rId1"/></sheets></workbook>"""

        val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        writeZip(target, mapOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rels,
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to wbRels,
            "xl/worksheets/sheet1.xml" to sheet
        ))
        return target
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    private fun writeZip(target: File, files: Map<String, String>) {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target).buffered()).use { zos ->
            for ((name, body) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    // ---------------- plumbing ----------------

    private fun sibling(file: File, suffix: String, ext: String): File {
        val base = file.name.substringBeforeLast('.', file.name)
        val dir = file.parentFile ?: AiWorkspace.root()
        var out = File(dir, "$base$suffix.$ext")
        var i = 2
        while (out.exists()) { out = File(dir, "$base$suffix-$i.$ext"); i++ }
        return out
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
