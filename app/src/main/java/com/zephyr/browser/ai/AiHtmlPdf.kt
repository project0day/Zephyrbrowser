package com.zephyr.browser.ai

import android.app.Activity
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.7.67: turn HTML into a real PDF, using the browser that is already here.
 *
 * The user's complaint was that generated CVs came out as grey text on white.
 * The cause was not the model — it was the tool: [AiDocBuilder] accepts
 * headings, paragraphs and bullets, so there is no way to *say* "colour",
 * "two columns" or "photo". The output could not be better than the vocabulary.
 *
 * The vocabulary every model already speaks fluently is HTML and CSS, and this
 * app happens to contain a world-class engine for it. So the assistant writes a
 * page, an offscreen WebView lays it out, and Android's print pipeline turns
 * that into PDF — colours, columns, rules, photos and all.
 *
 * Three details make the difference between "works" and "works properly":
 *
 *  - NO print dialog. PrintManager would ask the user to pick a printer on
 *    every CV; the adapter is driven directly into a file instead;
 *  - COLOUR SURVIVES. Print rendering drops backgrounds by default, which is
 *    exactly what turns a designed CV back into grey text; the templates carry
 *    `print-color-adjust: exact`, and this class refuses to render without it;
 *  - NO NETWORK. The page is loaded with a null base URL and its own resources
 *    inline, so a missing web font or a blocked CDN can never leave a half-
 *    drawn document.
 */
object AiHtmlPdf {

    class Result(val pdf: File, val html: File, val pages: Int)

    /**
     * Render [html] and write a PDF to [target]. Blocking; call off the UI
     * thread. The HTML is saved beside the PDF so the user can look at it in a
     * tab and ask for changes.
     */
    fun render(activity: Activity, html: String, target: File): Result {
        target.parentFile?.mkdirs()
        val htmlFile = File(target.parentFile, target.nameWithoutExtension + ".html")
        htmlFile.writeText(ensureColorFidelity(html), Charsets.UTF_8)

        var pages = 1
        var error: Throwable? = null
        val done = CountDownLatch(1)

        activity.runOnUiThread {
            try {
                // v1.7.68 FIX (user: «вёрстка трижды выдала „не успела
                // отрисоваться"»).
                //
                // A WebView that belongs to no window may never finish laying
                // out, and its print adapter then never answers. Android's own
                // documentation says as much. So the view IS added to the
                // window — at real A4 proportions, fully transparent and behind
                // everything — and removed the moment the PDF is written.
                val wv = WebView(activity)
                wv.settings.javaScriptEnabled = false
                wv.settings.loadsImagesAutomatically = true
                wv.settings.blockNetworkLoads = true
                val host = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val dm = activity.resources.displayMetrics
                // 210 × 297 mm at this screen's density
                val wPx = (210f / 25.4f * dm.xdpi).toInt().coerceIn(600, 3000)
                val hPx = (297f / 25.4f * dm.ydpi).toInt().coerceIn(800, 4200)
                try {
                    wv.alpha = 0f
                    host?.addView(wv, android.view.ViewGroup.LayoutParams(wPx, hPx))
                } catch (_: Throwable) { /* printing may still work unattached */ }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // one frame for layout to settle, then print.
                        //
                        // Printing MUST NOT block here: the adapter delivers its
                        // callbacks on this very thread, so waiting for them on
                        // it would deadlock until the timeout. The work is
                        // started and the outer latch is released from the
                        // callback instead.
                        view.postDelayed({
                            val cleanUp = {
                                try { (view.parent as? android.view.ViewGroup)?.removeView(view) }
                                catch (_: Throwable) {}
                                try { view.destroy() } catch (_: Throwable) {}
                            }
                            try {
                                printToFile(view, target) { n, t ->
                                    pages = n
                                    error = t
                                    cleanUp()
                                    done.countDown()
                                }
                            } catch (t: Throwable) {
                                error = t
                                cleanUp()
                                done.countDown()
                            }
                        }, 500)
                    }
                }
                wv.loadDataWithBaseURL(
                    null, htmlFile.readText(Charsets.UTF_8), "text/html", "UTF-8", null
                )
            } catch (t: Throwable) {
                error = t
                done.countDown()
            }
        }

        if (!done.await(90, TimeUnit.SECONDS)) throw IllegalStateException("Вёрстка не успела отрисоваться.")
        error?.let { throw it }
        if (!target.exists() || target.length() < 500) {
            throw IllegalStateException("PDF не получился — вероятно, в вёрстке ошибка.")
        }
        return Result(target, htmlFile, pages)
    }

    /**
     * Drive the print adapter straight into a file, with no printer picker.
     *
     * Asynchronous by necessity: every callback below arrives on the main
     * thread, so [onDone] is how the result leaves — never a return value.
     */
    private fun printToFile(wv: WebView, target: File, onDone: (Int, Throwable?) -> Unit) {
        val adapter = wv.createPrintDocumentAdapter(target.nameWithoutExtension)
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
            // the page's own CSS owns the margins; a printer margin on top of
            // them would shrink a carefully sized one-page CV onto two
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        var pageCount = 1
        // guards against an adapter that answers twice, which would release the
        // waiting caller and then touch a destroyed WebView
        var settled = false
        val finish = { t: Throwable? ->
            if (!settled) {
                settled = true
                // the framework always pairs onStart with onFinish; WebView's
                // adapter frees its print snapshot there
                try { adapter.onFinish() } catch (_: Throwable) {}
                onDone(pageCount, t)
            }
        }

        // v1.7.68: the print framework ALWAYS calls onStart first, and
        // WebView's adapter prepares the page for printing inside it. Skipping
        // it left onLayout with nothing to measure — which is why every render
        // timed out instead of failing loudly.
        try { adapter.onStart() } catch (_: Throwable) {}

        adapter.onLayout(null, attrs, CancellationSignal(),
            android.print.ZephyrPrintCallbacks.layout(
                object : android.print.ZephyrPrintCallbacks.OnLayout {
                    override fun finished(info: android.print.PrintDocumentInfo?) {
                        pageCount = info?.pageCount?.takeIf { it > 0 } ?: 1
                        try {
                            if (target.exists()) target.delete()
                            val pfd = ParcelFileDescriptor.open(
                                target,
                                ParcelFileDescriptor.MODE_READ_WRITE or
                                    ParcelFileDescriptor.MODE_CREATE or
                                    ParcelFileDescriptor.MODE_TRUNCATE
                            )
                            adapter.onWrite(
                                arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(),
                                android.print.ZephyrPrintCallbacks.write(
                                    object : android.print.ZephyrPrintCallbacks.OnWrite {
                                        override fun finished() {
                                            try { pfd.close() } catch (_: Throwable) {}
                                            finish(null)
                                        }

                                        override fun failed(error: CharSequence?) {
                                            try { pfd.close() } catch (_: Throwable) {}
                                            finish(IllegalStateException(error?.toString() ?: "write failed"))
                                        }
                                    }
                                )
                            )
                        } catch (t: Throwable) {
                            finish(t)
                        }
                    }

                    override fun failed(error: CharSequence?) {
                        finish(IllegalStateException(error?.toString() ?: "layout failed"))
                    }
                }
            ), null
        )

    }

    /**
     * Guarantee the colours reach the paper.
     *
     * Print rendering strips background colours unless a page asks for them to
     * be kept. A CV whose sidebar silently turns white is exactly the outcome
     * this feature exists to prevent, so the rule is injected when the page
     * does not already carry it — rather than trusting every generated page to
     * remember.
     */
    private fun ensureColorFidelity(html: String): String {
        if (html.contains("print-color-adjust", true)) return html
        val rule = "<style>*{-webkit-print-color-adjust:exact !important;" +
            "print-color-adjust:exact !important;}</style>"
        val head = html.indexOf("</head>", ignoreCase = true)
        return if (head >= 0) html.substring(0, head) + rule + html.substring(head)
        else rule + html
    }
}
