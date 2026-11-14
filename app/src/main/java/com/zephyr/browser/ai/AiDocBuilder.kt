package com.zephyr.browser.ai

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/**
 * v1.7.63 (user: «хочу, чтобы агент мог создавать красивые CV для работы и
 * потом конвертировать docx в pdf, чтобы распечатать… я безработный»).
 *
 * Converting an arbitrary .docx to PDF is genuinely hard: it needs a layout
 * engine that reproduces Word's line breaking, and the honest tools for that
 * are LibreOffice-sized. Saying "no" there would have been correct.
 *
 * But the actual need is not conversion — it is having the same CV as an
 * editable file to send and as a fixed file to print. So nothing is converted:
 * ONE piece of content is laid out TWICE, once into .docx and once into PDF, by
 * the same code from the same blocks. The two files match because they were
 * built from the same source, not because one was translated into the other.
 *
 * The input is the markup a model writes without being asked twice: # heading,
 * ## section, - bullet, **bold**. Anything fancier would be a format the
 * assistant has to be taught, and taught formats get used wrong.
 */
object AiDocBuilder {

    enum class Kind { TITLE, HEADING, TEXT, BULLET, SPACER, RULE }

    class Block(val kind: Kind, val text: String, val bold: Boolean = false)

    // ---------------- parsing ----------------

    fun parse(markup: String): List<Block> {
        val out = ArrayList<Block>()
        var titleTaken = false
        for (rawLine in markup.replace("\r\n", "\n").split("\n")) {
            val line = rawLine.trimEnd()
            val t = line.trim()
            when {
                t.isEmpty() -> out.add(Block(Kind.SPACER, ""))
                t == "---" || t == "___" -> out.add(Block(Kind.RULE, ""))
                t.startsWith("## ") -> out.add(Block(Kind.HEADING, clean(t.removePrefix("## "))))
                t.startsWith("# ") -> {
                    // the first # is the person's name on a CV, and deserves to
                    // look like it; later ones are ordinary headings
                    val kind = if (titleTaken) Kind.HEADING else Kind.TITLE
                    titleTaken = true
                    out.add(Block(kind, clean(t.removePrefix("# "))))
                }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") ->
                    out.add(Block(Kind.BULLET, clean(t.substring(2).trim())))
                else -> {
                    val bold = t.startsWith("**") && t.endsWith("**") && t.length > 4
                    out.add(Block(Kind.TEXT, clean(t), bold))
                }
            }
        }
        return out
    }

    /** Inline emphasis is stripped: the block already carries its own weight. */
    private fun clean(s: String) = s.replace("**", "").replace("__", "").trim()

    // ---------------- .docx ----------------

    fun writeDocx(target: File, blocks: List<Block>): File {
        val body = StringBuilder()
        for (b in blocks) {
            when (b.kind) {
                Kind.SPACER -> body.append(para("", 20, false, 0))
                Kind.RULE -> body.append(rule())
                Kind.TITLE -> body.append(para(b.text, 34, true, 240))
                Kind.HEADING -> body.append(para(b.text, 26, true, 180))
                Kind.BULLET -> body.append(para("•  " + b.text, 21, false, 40, indent = 260))
                Kind.TEXT -> body.append(para(b.text, 21, b.bold, 60))
            }
        }

        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body></w:document>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

        target.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(target).buffered()).use { zos ->
            for ((name, text) in listOf(
                "[Content_Types].xml" to contentTypes,
                "_rels/.rels" to rels,
                "word/document.xml" to document
            )) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return target
    }

    /** [half] is half-points, which is how Word measures text size. */
    private fun para(
        text: String, half: Int, bold: Boolean, spaceBefore: Int, indent: Int = 0
    ): String {
        val rpr = "<w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/>" +
            (if (bold) "<w:b/>" else "") + "<w:sz w:val=\"$half\"/></w:rPr>"
        val ppr = "<w:pPr><w:spacing w:before=\"$spaceBefore\" w:after=\"0\"/>" +
            (if (indent > 0) "<w:ind w:left=\"$indent\"/>" else "") + "</w:pPr>"
        return "<w:p>$ppr<w:r>$rpr<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>"
    }

    private fun rule(): String =
        "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:color=\"BBBBBB\"/></w:pBdr></w:pPr></w:p>"

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---------------- PDF ----------------

    /**
     * The same blocks, laid out on A4.
     *
     * Line breaking measures each word with the real font rather than counting
     * characters, so a long line wraps where it actually runs out of page —
     * the difference between a CV and a CV with a name hanging off the edge.
     */
    fun writePdf(ctx: Context, target: File, blocks: List<Block>): File {
        AiPdf.init(ctx)
        PDDocument().use { doc ->
            val regular = unicode(doc, false) ?: PDType1Font.HELVETICA
            val boldFont = unicode(doc, true) ?: PDType1Font.HELVETICA_BOLD

            val margin = 56f
            var page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            var cs = PDPageContentStream(doc, page)
            val width = PDRectangle.A4.width - margin * 2
            var y = PDRectangle.A4.height - margin

            fun newPage() {
                cs.close()
                page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                cs = PDPageContentStream(doc, page)
                y = PDRectangle.A4.height - margin
            }

            fun draw(text: String, font: PDFont, size: Float, indent: Float) {
                for (line in wrap(text, font, size, width - indent)) {
                    if (y - size < margin) newPage()
                    cs.beginText()
                    cs.setFont(font, size)
                    cs.newLineAtOffset(margin + indent, y)
                    cs.showText(safe(line, font))
                    cs.endText()
                    y -= size * 1.35f
                }
            }

            for (b in blocks) {
                when (b.kind) {
                    Kind.SPACER -> y -= 8f
                    Kind.RULE -> {
                        if (y - 12f < margin) newPage()
                        y -= 6f
                        cs.setLineWidth(0.7f)
                        cs.setStrokingColor(180, 180, 180)
                        cs.moveTo(margin, y); cs.lineTo(margin + width, y); cs.stroke()
                        y -= 10f
                    }
                    Kind.TITLE -> { y -= 4f; draw(b.text, boldFont, 20f, 0f); y -= 6f }
                    Kind.HEADING -> { y -= 10f; draw(b.text, boldFont, 13f, 0f); y -= 3f }
                    Kind.BULLET -> draw("•  " + b.text, regular, 10.5f, 14f)
                    Kind.TEXT -> draw(b.text, if (b.bold) boldFont else regular, 10.5f, 0f)
                }
            }
            cs.close()
            target.parentFile?.mkdirs()
            doc.save(target)
        }
        return target
    }

    private fun unicode(doc: PDDocument, bold: Boolean): PDFont? = try {
        val candidates = if (bold) listOf(
            "/system/fonts/Roboto-Bold.ttf", "/system/fonts/NotoSans-Bold.ttf",
            "/system/fonts/DroidSans-Bold.ttf"
        ) else listOf(
            "/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf"
        )
        candidates.asSequence().map { File(it) }.firstOrNull { it.isFile }
            ?.let { com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, it) }
    } catch (_: Throwable) { null }

    /** Without a Unicode font, non-Latin text is transliterated rather than lost. */
    private fun safe(s: String, font: PDFont): String =
        if (font is PDType1Font && AiPdf.needsTranslit(s)) AiPdf.latin(s) else s

    private fun wrap(text: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val out = ArrayList<String>()
        val words = text.split(" ")
        var line = StringBuilder()
        for (w in words) {
            val candidate = if (line.isEmpty()) w else line.toString() + " " + w
            val width = try {
                font.getStringWidth(safe(candidate, font)) / 1000f * size
            } catch (_: Throwable) { candidate.length * size * 0.5f }
            if (width > maxWidth && line.isNotEmpty()) {
                out.add(line.toString())
                line = StringBuilder(w)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }
}
