package com.zephyr.browser.panels

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import java.io.File

/**
 * v1.7.74: look at a PDF without leaving the app.
 *
 * The workspace exists so a document the assistant produced can be checked
 * immediately, and "это не текстовый файл" defeated exactly that. Android's own
 * PdfRenderer draws the pages; there is no library and no dependency involved.
 *
 * Pages are rendered one at a time as they are needed rather than all at once:
 * a twenty-page contract rendered eagerly is twenty full-screen bitmaps, and
 * the phone notices.
 */
object PdfViewer {

    private const val MAX_PAGES = 30

    fun show(activity: Activity, file: File) {
        val ctx = activity
        val d = { v: Int -> (v * ctx.resources.displayMetrics.density + 0.5f).toInt() }
        val width = (ctx.resources.displayMetrics.widthPixels * 0.86f).toInt()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(6), d(6), d(6), d(6))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.85f).toInt()
            )
            addView(TextView(ctx).apply {
                text = file.name
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(d(18), d(16), d(18), d(8))
            })
            addView(ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(column)
            })
        }

        val dialog = MaterialAlertDialogBuilder(ctx).setView(root).create()

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(12), 0, d(12), d(10))
            addView(button(ctx, ctx.getString(R.string.ws_share)) {
                dialog.dismiss()
                share(activity, file)
            })
            addView(button(ctx, ctx.getString(R.string.ok)) { dialog.dismiss() })
        })

        dialog.show()

        Thread {
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                        val pages = renderer.pageCount.coerceAtMost(MAX_PAGES)
                        for (i in 0 until pages) {
                            val bmp = renderer.openPage(i).use { page ->
                                val scale = width.toFloat() / page.width.coerceAtLeast(1)
                                val h = (page.height * scale).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b ->
                                    // a page is mostly white; without this it
                                    // renders onto transparency and looks black
                                    android.graphics.Canvas(b).drawColor(Color.WHITE)
                                    page.render(
                                        b, null, null,
                                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                    )
                                }
                            }
                            activity.runOnUiThread {
                                column.addView(ImageView(ctx).apply {
                                    setImageBitmap(bmp)
                                    adjustViewBounds = true
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    ).apply { bottomMargin = d(10) }
                                    background = GradientDrawable().apply {
                                        setColor(Color.WHITE)
                                        setStroke(1, 0x22000000)
                                    }
                                })
                            }
                        }
                        if (renderer.pageCount > MAX_PAGES) activity.runOnUiThread {
                            column.addView(TextView(ctx).apply {
                                text = ctx.getString(
                                    R.string.pdfv_more, renderer.pageCount - MAX_PAGES
                                )
                                textSize = 12.5f
                                gravity = Gravity.CENTER
                                alpha = 0.75f
                                setPadding(0, d(10), 0, d(16))
                            })
                        }
                    }
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    column.addView(TextView(ctx).apply {
                        text = ctx.getString(R.string.pdfv_fail)
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setPadding(0, d(40), 0, d(40))
                    })
                }
            }
        }.start()
    }

    private fun share(activity: Activity, file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider", file
            )
            activity.startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    activity.getString(R.string.ws_share)
                )
            )
        } catch (_: Throwable) {}
    }

    private fun button(ctx: android.content.Context, label: String, onTap: () -> Unit) =
        TextView(ctx).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(PanelUi.accent(ctx))
            setPadding(0, (14 * ctx.resources.displayMetrics.density).toInt(), 0,
                (14 * ctx.resources.displayMetrics.density).toInt())
            isClickable = true
            background = PanelUi.ripple(ctx)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onTap() }
        }
}
