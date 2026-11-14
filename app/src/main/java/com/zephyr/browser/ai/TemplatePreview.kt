package com.zephyr.browser.ai

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * v1.7.73 (user: «я бы хотел видеть визуально миниатюрно, как вкладки у хрома,
 * страницу devis, которую я сделал»).
 *
 * A list of names tells you nothing about a document. A picture of it tells you
 * everything at a glance, and that is the whole difference between a file
 * manager and a workspace.
 *
 * The thumbnail is the template rendered once and kept beside it. Placeholders
 * stay visible in the picture — a preview showing `{{client_name}}` where the
 * client goes is more informative than one filled with invented data, because
 * it shows the SHAPE of the document and where the blanks are.
 *
 * Generation is lazy and one-at-a-time: it costs a WebView layout and a PDF
 * render, which is fine once but would be a stall for twelve templates at once.
 */
object TemplatePreview {

    /** Guards against two panels rendering the same template simultaneously. */
    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())

    fun cached(t: AiTemplates.Template): Bitmap? = try {
        val f = t.preview()
        // a preview older than its template is a preview of something else
        if (f.isFile && f.lastModified() >= t.file.lastModified()) {
            BitmapFactory.decodeFile(f.absolutePath)
        } else null
    } catch (_: Throwable) { null }

    /**
     * Build the thumbnail if it is missing, then call [onReady] on the main
     * thread. Does nothing when one is already being built for this template.
     */
    fun ensure(activity: Activity, t: AiTemplates.Template, onReady: (Bitmap) -> Unit) {
        cached(t)?.let { onReady(it); return }
        val key = t.file.absolutePath
        if (!busy.add(key)) return

        Thread {
            var result: Bitmap? = null
            try {
                // v1.7.74 FIX (user: «загрузил pdf или docx — миниатюры
                // показывает набор ромбиков и квадратиков»).
                //
                // Every template was rendered as HTML. A .docx or .pdf is a
                // binary archive, so the WebView drew its raw bytes as text —
                // hence the mojibake. Each format now produces its thumbnail
                // the only way it can.
                val data = when {
                    // a PDF is already a page; render it directly
                    t.isPdf -> AiPdf.pageImage(t.file, 1, maxWidth = 420)

                    // Word has no renderer here, so the preview is its own text
                    // laid out as a page — enough to recognise the document
                    t.isDocx -> {
                        val text = try { AiOffice.read(t.file).text } catch (_: Throwable) { "" }
                        val tmp = File(activity.cacheDir, "tpl_preview_${t.id}.pdf")
                        AiHtmlPdf.render(activity, docxPreviewHtml(t.name, text), tmp)
                        val d = AiPdf.pageImage(tmp, 1, maxWidth = 420)
                        tmp.delete()
                        d
                    }

                    else -> {
                        val html = t.file.readText()
                        // rendered into the cache: the PDF itself is a means,
                        // not a file the user asked for
                        val tmp = File(activity.cacheDir, "tpl_preview_${t.id}.pdf")
                        AiHtmlPdf.render(activity, html, tmp)
                        val d = AiPdf.pageImage(tmp, 1, maxWidth = 420)
                        tmp.delete()
                        d
                    }
                }
                if (data != null) {
                    val raw = android.util.Base64.decode(
                        data.substringAfter("base64,"), android.util.Base64.NO_WRAP
                    )
                    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    if (bmp != null) {
                        try {
                            java.io.FileOutputStream(t.preview()).use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            }
                        } catch (_: Throwable) {}
                        result = bmp
                    }
                }
            } catch (_: Throwable) {
                // a template that cannot render gets no thumbnail; the card
                // falls back to its icon rather than the panel failing
            } finally {
                busy.remove(key)
            }
            val bmp = result ?: return@Thread
            activity.runOnUiThread { onReady(bmp) }
        }.start()
    }

    /** Drop the cached image so the next open rebuilds it. */
    fun invalidate(t: AiTemplates.Template) {
        try { t.preview().delete() } catch (_: Throwable) {}
    }

    /** A page-shaped rendering of a Word template's text, for the thumbnail. */
    private fun docxPreviewHtml(name: String, text: String): String {
        val body = text.take(1800)
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .split("\n").joinToString("") { line ->
                if (line.isBlank()) "<p class=\"gap\"></p>" else "<p>" + line.trim() + "</p>"
            }
        return """
<!doctype html><html><head><meta charset="utf-8"><style>
  @page{ size:A4; margin:0; }
  html,body{ margin:0; }
  body{ font-family:'Roboto','Noto Sans',sans-serif; color:#20242a; font-size:9.5pt; line-height:1.4; }
  .sheet{ width:210mm; min-height:296mm; padding:18mm 20mm; }
  .tag{ display:inline-block; background:#e9edf3; color:#43506b; font-size:8pt;
        letter-spacing:1px; padding:1.5mm 3mm; border-radius:3px; margin-bottom:6mm; }
  p{ margin:0 0 1.6mm; }
  p.gap{ height:2.6mm; }
</style></head><body><div class="sheet">
  <div class="tag">WORD · ${name.replace("<", "")}</div>
  $body
</div></body></html>
""".trimIndent()
    }
}
