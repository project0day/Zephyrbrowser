package com.zephyr.browser.ai

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.7.61 (user: «чтобы помощник извлекал из PDF данные, заполнял их, ставил
 * подписи и даты — я качал Adobe, вручную галочки ставил, потеря времени»).
 *
 * Android ships PdfRenderer, which turns a page into a bitmap and nothing more:
 * no text, no fields. Filling a form needs the document's actual object model,
 * so this is built on PDFBox (the Android port, Apache 2.0).
 *
 * Three jobs, in order of how often they matter:
 *
 *  - READ — the text of a document, so the assistant can answer questions about
 *    it without the user retyping anything;
 *  - FIELDS — list an AcroForm's fields with their real names, types and
 *    current values, then set them. This is the "поставить галочку и вписать
 *    имя" case that cost an Adobe download;
 *  - STAMP — draw text (a date, a signature line) at a position on a page, for
 *    documents that have no form fields at all, which is most of them.
 *
 * Nothing is ever written back over the original. Every operation produces a
 * new file next to it, because a filled form that overwrote the blank one is a
 * mistake with no undo.
 */
object AiPdf {

    /** Guards against paying the resource-loader cost on every call. */
    @Volatile private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        try { PDFBoxResourceLoader.init(ctx.applicationContext); ready = true } catch (_: Throwable) {}
    }

    private const val MAX_TEXT = 12000
    private const val MAX_FIELDS = 120

    /**
     * v1.7.62 (user: «мне надо, чтобы на русском, французском и английском мог
     * спокойно заполнять документы»).
     *
     * PDF's built-in Helvetica covers Latin-1, so French accents were already
     * fine — but Cyrillic is simply not in it, and every Russian value either
     * threw or had to be transliterated.
     *
     * The fix costs nothing to ship: Android carries Roboto, which covers
     * Latin, Cyrillic and Greek, in /system/fonts. It is loaded from the device
     * and embedded (subset) into the document being written, so the result
     * opens correctly anywhere — no font in the APK, no 700 KB of DejaVu.
     *
     * The list is ordered by coverage, not by preference: whatever this ROM
     * actually has wins, and a device with none of them falls back to
     * transliteration rather than failing.
     */
    private val SYSTEM_FONTS = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
        "/system/fonts/DejaVuSans.ttf",
        "/system/fonts/RobotoStatic-Regular.ttf"
    )

    private fun systemFontFile(): File? =
        SYSTEM_FONTS.asSequence().map { File(it) }.firstOrNull { it.isFile && it.length() > 0 }

    /** A Unicode font embedded into [doc], or null when the ROM has none. */
    private fun unicodeFont(doc: PDDocument): com.tom_roush.pdfbox.pdmodel.font.PDFont? = try {
        systemFontFile()?.let {
            com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, it)
        }
    } catch (_: Throwable) { null }

    /** True when a Unicode font is available on this device. */
    fun unicodeAvailable(): Boolean = systemFontFile() != null

    // ---------------- reading ----------------

    class Doc(val pages: Int, val text: String, val truncated: Boolean, val hasForm: Boolean)

    fun read(ctx: Context, file: File, fromPage: Int, toPage: Int): Doc {
        init(ctx)
        PDDocument.load(file).use { doc ->
            val total = doc.numberOfPages
            val stripper = PDFTextStripper().apply {
                startPage = fromPage.coerceIn(1, total.coerceAtLeast(1))
                endPage = if (toPage > 0) toPage.coerceIn(startPage, total) else total
                sortByPosition = true
            }
            val raw = stripper.getText(doc)
            val hasForm = (doc.documentCatalog?.acroForm?.fields?.size ?: 0) > 0
            return Doc(
                total,
                raw.take(MAX_TEXT).trim(),
                raw.length > MAX_TEXT,
                hasForm
            )
        }
    }

    // ---------------- form fields ----------------

    class Field(
        val name: String,
        val type: String,
        val value: String,
        val options: List<String>,
        val readOnly: Boolean
    )

    fun fields(ctx: Context, file: File): List<Field> {
        init(ctx)
        PDDocument.load(file).use { doc ->
            val form = doc.documentCatalog?.acroForm ?: return emptyList()
            val out = ArrayList<Field>()
            collect(form.fields, out)
            return out.take(MAX_FIELDS)
        }
    }

    /** AcroForm fields nest; a group is not itself fillable. */
    private fun collect(fields: List<PDField>?, into: ArrayList<Field>) {
        if (fields == null) return
        for (f in fields) {
            if (f is com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField) {
                collect(f.children, into)
                continue
            }
            val type = when (f) {
                is PDCheckBox -> "checkbox"
                is PDRadioButton -> "radio"
                is PDChoice -> "choice"
                is PDTextField -> "text"
                else -> "field"
            }
            val options = when (f) {
                is PDCheckBox -> listOf("on", "off")
                is PDRadioButton -> try { f.onValues.toList() } catch (_: Throwable) { emptyList() }
                is PDChoice -> try { f.optionsDisplayValues ?: emptyList() } catch (_: Throwable) { emptyList() }
                else -> emptyList()
            }
            into.add(
                Field(
                    f.fullyQualifiedName ?: f.partialName ?: "",
                    type,
                    try { f.valueAsString ?: "" } catch (_: Throwable) { "" },
                    options,
                    f.isReadOnly
                )
            )
        }
    }

    class FillResult(val out: File, val filled: List<String>, val missing: List<String>)

    /**
     * Write [values] into the form and save as a NEW file.
     *
     * [flatten] bakes the values into the page so they cannot be edited or lost
     * by a viewer that ignores form data — which is what a signed document
     * usually needs, and what many portals require on upload.
     */
    fun fill(
        ctx: Context,
        file: File,
        values: Map<String, String>,
        flatten: Boolean,
        suffix: String = "-filled"
    ): FillResult {
        init(ctx)
        val filled = ArrayList<String>()
        val missing = ArrayList<String>()
        val out = sibling(file, suffix)
        PDDocument.load(file).use { doc ->
            val form: PDAcroForm = doc.documentCatalog?.acroForm
                ?: throw IllegalStateException("В этом PDF нет полей формы.")
            // some generators leave appearances off; without this the values are
            // stored but nothing is visible in a viewer
            form.needAppearances = false

            // v1.7.62: a form's own font is usually Helvetica, and writing
            // Cyrillic into it throws "U+0424 is not available in this font" —
            // so the value was lost, not merely ugly. The device's Unicode font
            // is registered in the form's default resources and pointed at by
            // any field that receives non-Latin text; fields with plain Latin
            // keep the document's original look untouched.
            var uniName: com.tom_roush.pdfbox.cos.COSName? = null
            if (values.values.any { needsTranslit(it) }) {
                try {
                    val font = unicodeFont(doc)
                    if (font != null) {
                        val res = form.defaultResources
                            ?: com.tom_roush.pdfbox.pdmodel.PDResources().also { form.defaultResources = it }
                        uniName = res.add(font)
                    }
                } catch (_: Throwable) {}
            }

            for ((name, raw) in values) {
                val field = form.getField(name)
                if (field == null) { missing.add(name); continue }
                if (field.isReadOnly) { missing.add("$name (только чтение)"); continue }
                try {
                    when (field) {
                        is PDCheckBox -> {
                            val on = raw.trim().lowercase() in setOf("on", "true", "yes", "да", "1", "x", "✓")
                            if (on) field.check() else field.unCheck()
                        }
                        is PDRadioButton -> field.value = raw
                        // PDChoice also has a List setter; name the String one explicitly
                        is PDChoice -> field.setValue(raw)
                        else -> {
                            // point this field at the Unicode font before the
                            // value goes in, or the appearance stream is built
                            // with the old one and throws
                            if (uniName != null && needsTranslit(raw) && field is PDTextField) {
                                try { field.defaultAppearance = "/" + uniName.name + " 0 Tf 0 g" }
                                catch (_: Throwable) {}
                            }
                            field.setValue(raw)
                        }
                    }
                    filled.add(name)
                } catch (t: Throwable) {
                    // last resort: keep the document usable with a transliterated
                    // value rather than leaving the field empty
                    val fallback = if (needsTranslit(raw)) latin(raw) else null
                    if (fallback != null && runCatching { field.setValue(fallback) }.isSuccess) {
                        filled.add("$name (латиницей)")
                    } else {
                        missing.add(name + " (" + (t.localizedMessage ?: "ошибка") + ")")
                    }
                }
            }
            if (flatten) try { form.flatten() } catch (_: Throwable) {}
            doc.save(out)
        }
        return FillResult(out, filled, missing)
    }

    // ---------------- stamping ----------------

    /**
     * Draw [text] on [page] at a position given in percent of the page size.
     *
     * Percentages, not points: the assistant is told "bottom right", not "at
     * 431.2, 88.7", and percentages survive A4-versus-Letter without a
     * conversion nobody will get right.
     */
    /**
     * One thing to write on the page.
     *
     * Either an absolute position ([xPercent]/[yPercent]) or an ANCHOR — a
     * piece of text already printed on the page, after which this one goes.
     * Anchors exist because absolute coordinates are guesswork for anything
     * that has not seen the page, and guessing is what put a name on top of
     * the word "Commune".
     */
    class Mark(
        val text: String,
        val page: Int = 1,
        val xPercent: Float = -1f,
        val yPercent: Float = -1f,
        val size: Float = 11f,
        val after: String = "",
        /** Extra offset from the anchor, in percent of page width/height. */
        val dx: Float = 1f,
        val dy: Float = 0f
    )

    /**
     * v1.7.63 FIX (user: «второй агент создал 4 новых pdf по одному слову на
     * каждом», and the model itself diagnosed it: "each pdf_stamp call creates
     * a new file based on the original, losing previous stamps").
     *
     * It was right, and it was my design error: one call, one load of the
     * ORIGINAL, one new file. Filling a form means writing a dozen things, so
     * the twelfth output had eleven fields missing.
     *
     * Now a whole page's worth of marks is applied in a single open, to a
     * single output. The filled document is the result of one call, which is
     * also what makes it reviewable.
     */
    fun stampAll(ctx: Context, file: File, marks: List<Mark>, suffix: String = "-filled"): File {
        init(ctx)
        if (marks.isEmpty()) throw IllegalArgumentException("Нечего впечатывать.")
        val out = sibling(file, suffix)
        PDDocument.load(file).use { doc ->
            val uni = unicodeFont(doc)
            // positions are read once, before anything is drawn, so anchors
            // refer to the ORIGINAL layout and not to text added a moment ago
            val layoutCache = HashMap<Int, List<Line>>()
            for (m in marks) {
                val idx = (m.page - 1).coerceIn(0, (doc.numberOfPages - 1).coerceAtLeast(0))
                val p: PDPage = doc.getPage(idx)
                // v1.7.64 FIX (user: «справа появился ещё файл, который я не смог
                // увидеть»; the model called it "текст улетел за границы видимой
                // области" and that part was exactly right).
                //
                // Positions were computed against the MediaBox. Viewers display
                // the CROP box, and on scanned or re-generated forms the two
                // differ — sometimes only by an offset, which is enough to push
                // every value off the visible sheet while the file itself looks
                // untouched. The visible rectangle is the only correct basis.
                val box = visualBox(p)

                var xPct = m.xPercent
                var yPct = m.yPercent
                if (m.after.isNotBlank()) {
                    val lines = layoutCache.getOrPut(idx) { linesOf(doc, idx + 1) }
                    val hit = bestAnchor(lines, m.after)
                    if (hit != null) {
                        // land just past the LABEL, not past the whole line
                        val bare = m.after.substringBefore('#').trim()
                        xPct = (hit.endOfLabel(bare) + m.dx).coerceIn(0f, 95f)
                        yPct = (hit.yPercent + m.dy).coerceIn(0f, 100f)
                    }
                }
                if (xPct < 0f) xPct = 10f
                if (yPct < 0f) yPct = 50f

                // a rotated page is displayed turned, so the visible width and
                // height swap and the text has to turn with it
                val rot = ((p.rotation % 360) + 360) % 360
                val visW = if (rot == 90 || rot == 270) box.height else box.width
                val visH = if (rot == 90 || rot == 270) box.width else box.height
                val vx = visW * (xPct / 100f)
                val vy = visH * (yPct / 100f)   // measured from the TOP
                val matrix = when (rot) {
                    90 -> com.tom_roush.pdfbox.util.Matrix.getRotateInstance(
                        Math.toRadians(90.0), box.lowerLeftX + vy, box.lowerLeftY + vx
                    )
                    180 -> com.tom_roush.pdfbox.util.Matrix.getRotateInstance(
                        Math.toRadians(180.0), box.upperRightX - vx, box.lowerLeftY + vy
                    )
                    270 -> com.tom_roush.pdfbox.util.Matrix.getRotateInstance(
                        Math.toRadians(270.0), box.upperRightX - vy, box.upperRightY - vx
                    )
                    // PDF's origin is the BOTTOM-left; the caller thinks top-down
                    else -> com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(
                        box.lowerLeftX + vx, box.upperRightY - vy
                    )
                }
                // v1.7.66 (user's model: «текст наложился на правую границу»).
                // A value longer than the room left on the line used to run off
                // the sheet. It is measured against the space that is actually
                // there and shrunk to fit — smaller but readable beats missing.
                val font = uni ?: PDType1Font.HELVETICA
                val shown = if (uni != null) m.text else latin(m.text)
                var fontSize = m.size
                val roomPt = visW * ((100f - xPct) / 100f) - 6f
                if (roomPt > 20f) {
                    val natural = try {
                        font.getStringWidth(shown) / 1000f * fontSize
                    } catch (_: Throwable) { 0f }
                    if (natural > roomPt) {
                        fontSize = (fontSize * roomPt / natural).coerceAtLeast(5.5f)
                    }
                }
                PDPageContentStream(
                    doc, p, PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setTextMatrix(matrix)
                    cs.showText(shown)
                    cs.endText()
                }
            }
            doc.save(out)
        }
        return out
    }

    // ---------------- layout ----------------

    /**
     * A printed line and where it sits, in percent of the page.
     *
     * v1.7.65 (user's model, correctly: «алгоритм ставит значение на самый
     * правый край строки»).
     *
     * [xEndPercent] is where the whole LINE ends, and that was the bug: on a
     * form, "Nom de naissance : ......................" is one line, so
     * anchoring after it put the name at the right margin — or past it. What an
     * anchor actually needs is where the MATCHED LABEL ends, so every
     * character's right edge is kept and [endOfLabel] answers that.
     */
    class Line(
        val text: String,
        val xPercent: Float,
        val xEndPercent: Float,
        val yPercent: Float,
        /** Right edge of each character of [text], in percent. */
        val charEnds: FloatArray = FloatArray(0)
    ) {
        /**
         * Where [label] ends inside this line, in percent — or the line's end
         * when the characters were not recorded.
         *
         * Matching is case-insensitive and ignores the run of dots, underscores
         * and spaces a form uses as a writing rule, so "Fait à" matches
         * "Fait à ....." and lands right after the words.
         */
        fun endOfLabel(label: String): Float {
            val idx = text.indexOf(label, ignoreCase = true)
            if (idx < 0 || charEnds.isEmpty()) return xEndPercent
            val last = (idx + label.length - 1).coerceIn(0, charEnds.size - 1)
            return charEnds[last]
        }
    }

    // ---------------- detecting the blanks ----------------

    /**
     * A place on a paper form where something is meant to be written.
     *
     * v1.7.66. Looking at how this is actually solved — DocuSeal, OpenSign and
     * GSA's pdf-filler all do the same two things — settled the design:
     *
     *  - nobody matches label text at fill time. They locate the BLANK (the
     *    dotted leader, the underscore run, the empty box), because the blank
     *    is where writing goes, and a label is only its name;
     *  - the result is saved as a TEMPLATE of coordinates, so the second fill
     *    of the same form is exact rather than re-guessed.
     *
     * That also dissolves the duplicate-label problem the user hit: two
     * "Nom de naissance" produce two separate blanks at different heights, so
     * they are two distinct fields — #3 and #7 — not one ambiguous name.
     */
    class FormField(
        val index: Int,
        val label: String,
        /** 1 for the first "Nom de naissance" on the page, 2 for the next. */
        val occurrence: Int,
        /** Where writing starts, in percent. */
        val xPercent: Float,
        val yPercent: Float,
        /** How much room the blank gives, in percent of page width. */
        val widthPercent: Float
    ) {
        /** "Nom de naissance#2" when repeated, plain otherwise. */
        val key: String get() = if (occurrence > 1) "$label#$occurrence" else label
    }

    /** A run of dots, underscores or middle dots — a place to write. */
    private val LEADER = Regex("""[.…_·•]{3,}|…+""")

    /**
     * Find every blank on a page and name it by the text to its left.
     *
     * A line can hold more than one ("Code postal : ...... Commune : ......"),
     * so each leader run becomes its own field with its own label — the words
     * between the previous blank and this one.
     */
    fun detectFields(doc: PDDocument, page: Int): List<FormField> {
        val out = ArrayList<FormField>()
        val seen = HashMap<String, Int>()
        var n = 0
        for (line in linesOf(doc, page)) {
            var cursor = 0
            for (m in LEADER.findAll(line.text)) {
                val labelRaw = line.text.substring(cursor, m.range.first)
                cursor = m.range.last + 1
                val label = labelRaw.trim().trimEnd(':', '-', '—').trim()
                    // a label is the tail of the phrase, not a whole sentence
                    .takeLast(48).trim()
                if (label.length < 3) continue
                val occ = (seen[label.lowercase()] ?: 0) + 1
                seen[label.lowercase()] = occ
                val startX = if (line.charEnds.isNotEmpty())
                    line.charEnds.getOrElse(m.range.first - 1) { line.xPercent }
                else line.xPercent
                val endX = if (line.charEnds.isNotEmpty())
                    line.charEnds.getOrElse(m.range.last) { line.xEndPercent }
                else line.xEndPercent
                out.add(
                    FormField(++n, label, occ, startX, line.yPercent, (endX - startX).coerceAtLeast(5f))
                )
            }
        }
        return out
    }

    /**
     * Pick the line a label refers to.
     *
     * v1.7.65 (user's model: «запретить искать слишком короткие слова типа At,
     * Le — иначе цепляет случайные буквы в тексте»). It was right: the first
     * line merely *containing* the key won, so "Le" matched the middle of a
     * word in the document's title and a date landed in the heading.
     *
     * The scoring is small but does the work of a rule nobody has to configure:
     *
     *  - a WHOLE-WORD match beats a match inside another word, which is the
     *    entire "Le / At / Code" problem;
     *  - a label FOLLOWED by a colon or a writing rule of dots or underscores
     *    beats one that is not, because that is what a fillable label looks
     *    like on paper;
     *  - a label near the START of its line beats one buried in a sentence.
     *
     * A key of one or two characters is refused outright: no scoring can make
     * that unambiguous, and a wrong confident placement is worse than a
     * reported miss.
     */
    fun bestAnchor(lines: List<Line>, label: String): Line? {
        var key = label.trim()
        if (key.isEmpty()) return null

        // v1.7.66 (user's model: «„Nom de naissance" встречается дважды — для
        // хоста и для гостя, часть текста улетела»). "Label#2" names the second
        // one. Without it, both values fought over the same line.
        var wanted = 1
        Regex("""^(.*?)#(\d+)$""").find(key)?.let { m ->
            key = m.groupValues[1].trim()
            wanted = m.groupValues[2].toIntOrNull() ?: 1
        }
        if (key.length < 3) return null

        if (wanted > 1) {
            // occurrences are counted top-down, the order a person reads in
            val hits = lines.filter { it.text.contains(key, ignoreCase = true) }
                .sortedBy { it.yPercent }
            return hits.getOrNull(wanted - 1)
        }

        var best: Line? = null
        var bestScore = Int.MIN_VALUE
        for (line in lines) {
            val idx = line.text.indexOf(key, ignoreCase = true)
            if (idx < 0) continue

            var score = 0
            val before = line.text.getOrNull(idx - 1)
            val after = line.text.getOrNull(idx + key.length)
            if (before == null || !before.isLetterOrDigit()) score += 40
            if (after == null || !after.isLetterOrDigit()) score += 40
            // the tail of a fillable label: ":", "…", "....", "____"
            val tail = line.text.substring((idx + key.length).coerceAtMost(line.text.length))
                .trimStart()
            if (tail.startsWith(":") || tail.startsWith("…") ||
                tail.startsWith("..") || tail.startsWith("__")
            ) score += 30
            // earlier in the line is more likely to be the label itself
            score += (20 - idx).coerceAtLeast(-20)
            // a longer key matched is a more certain match
            score += key.length.coerceAtMost(20)

            if (score > bestScore) { bestScore = score; best = line }
        }
        return best
    }

    /** What a viewer actually shows: the crop box, or the media box when unset. */
    fun visualBox(page: PDPage): PDRectangle {
        val crop = page.cropBox
        return if (crop != null && crop.width > 0 && crop.height > 0) crop else page.mediaBox
    }

    /**
     * Where each line of text is printed on a page.
     *
     * This is what makes filling a non-interactive form possible: the model can
     * ask "where does it say «Fait à»" and place its value next to that, rather
     * than inventing coordinates and landing on a label.
     */
    fun linesOf(doc: PDDocument, page: Int): List<Line> {
        val out = ArrayList<Line>()
        // same rectangle as the stamping code, or anchors would point one way
        // and the text would land another
        val box = visualBox(doc.getPage((page - 1).coerceIn(0, (doc.numberOfPages - 1).coerceAtLeast(0))))
        val w = box.width.takeIf { it > 0 } ?: 595f
        val h = box.height.takeIf { it > 0 } ?: 842f
        val stripper = object : PDFTextStripper() {
            override fun writeString(
                text: String,
                textPositions: MutableList<com.tom_roush.pdfbox.text.TextPosition>
            ) {
                if (text.isNotBlank() && textPositions.isNotEmpty()) {
                    val first = textPositions.first()
                    val last = textPositions.last()
                    // one TextPosition can stand for several characters (a
                    // ligature, an expanded glyph), so the map is built by
                    // walking the positions rather than by index
                    val ends = ArrayList<Float>(text.length)
                    for (tp in textPositions) {
                        val n = (tp.unicode ?: "").length.coerceAtLeast(1)
                        val end = (tp.xDirAdj + tp.widthDirAdj) / w * 100f
                        repeat(n) { ends.add(end) }
                    }
                    // writeString trims; the map must line up with the trimmed text
                    val lead = text.length - text.trimStart().length
                    val trimmed = text.trim()
                    val aligned = FloatArray(trimmed.length) { i ->
                        ends.getOrElse(i + lead) { ends.lastOrNull() ?: 0f }
                    }
                    out.add(
                        Line(
                            trimmed,
                            first.xDirAdj / w * 100f,
                            (last.xDirAdj + last.widthDirAdj) / w * 100f,
                            first.yDirAdj / h * 100f,
                            aligned
                        )
                    )
                }
                super.writeString(text, textPositions)
            }
        }
        stripper.startPage = page
        stripper.endPage = page
        stripper.sortByPosition = true
        stripper.getText(doc)
        return out
    }

    fun scanFields(ctx: Context, file: File, page: Int): List<FormField> {
        init(ctx)
        PDDocument.load(file).use { doc -> return detectFields(doc, if (page > 0) page else 1) }
    }

    fun layout(ctx: Context, file: File, page: Int): List<Line> {
        init(ctx)
        PDDocument.load(file).use { doc -> return linesOf(doc, if (page > 0) page else 1) }
    }

    /** Render one page to a JPEG data URL, so a vision model can look at it. */
    fun pageImage(file: File, page: Int, maxWidth: Int = 1100): String? = try {
        android.os.ParcelFileDescriptor.open(
            file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
        ).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                val idx = (page - 1).coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))
                r.openPage(idx).use { p ->
                    val scale = maxWidth.toFloat() / p.width.coerceAtLeast(1)
                    val bw = maxWidth
                    val bh = (p.height * scale).toInt().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        bw, bh, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    // a form is mostly white; without this the page renders on
                    // transparency and turns black in a JPEG
                    android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bos)
                    bmp.recycle()
                    "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                        bos.toByteArray(), android.util.Base64.NO_WRAP
                    )
                }
            }
        }
    } catch (_: Throwable) { null }

    fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())

    // ---------------- plumbing ----------------

    /** A new file beside the original — the source is never overwritten. */
    private fun sibling(file: File, suffix: String): File {
        val base = file.name.substringBeforeLast('.', file.name)
        val dir = file.parentFile ?: AiWorkspace.root()
        var out = File(dir, "$base$suffix.pdf")
        var i = 2
        while (out.exists()) { out = File(dir, "$base$suffix-$i.pdf"); i++ }
        return out
    }

    private val CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    private val LAT = listOf(
        "a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n",
        "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y",
        "", "e", "yu", "ya"
    )

    /**
     * Helvetica is a Latin-only base font. Embedding a Cyrillic TTF would mean
     * shipping one; until then a stamped Russian name is transliterated, which
     * is legible, rather than throwing and stamping nothing.
     */
    fun latin(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val lower = ch.lowercaseChar()
            val idx = CYR.indexOf(lower)
            when {
                idx >= 0 -> {
                    val t = LAT[idx]
                    sb.append(if (ch.isUpperCase() && t.isNotEmpty()) t.replaceFirstChar { it.uppercase() } else t)
                }
                ch.code in 32..126 -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString()
    }

    fun needsTranslit(s: String): Boolean = s.any { it.code > 126 }
}
