package com.zephyr.browser.panels

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import com.zephyr.browser.ai.AiBusiness

/**
 * v1.7.73: draw a signature with a finger, once.
 *
 * "Подпиши за меня" needs an image, and the shortest path from a person to an
 * image of their signature is their own finger — not a scanner, not a photo of
 * paper that arrives rotated and grey.
 *
 * Saved with a TRANSPARENT background so it sits on a document rather than as a
 * white rectangle over it, and trimmed to the ink, so the layout can place it
 * without a margin of empty pixels around the strokes.
 */
object SignaturePad {

    private class PadView(activity: Activity) : View(activity) {
        private val paths = ArrayList<Path>()
        private var current: Path? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val isEmpty: Boolean get() = paths.isEmpty()

        fun clear() { paths.clear(); current = null; invalidate() }

        override fun onDraw(canvas: Canvas) {
            for (p in paths) canvas.drawPath(p, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // a dialog's own scroll container would otherwise steal the
                    // drag halfway through a stroke, and the signature would
                    // come out as disconnected dots
                    parent?.requestDisallowInterceptTouchEvent(true)
                    current = Path().apply { moveTo(event.x, event.y) }
                    paths.add(current!!)
                }
                MotionEvent.ACTION_MOVE -> current?.lineTo(event.x, event.y)
                MotionEvent.ACTION_UP -> current = null
            }
            invalidate()
            return true
        }

        /** Render the strokes onto transparency, cropped to the ink. */
        fun toBitmap(): Bitmap? {
            if (paths.isEmpty() || width <= 0 || height <= 0) return null
            val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(full).apply { for (p in paths) drawPath(p, paint) }

            val bounds = android.graphics.RectF()
            val all = Path()
            for (p in paths) all.addPath(p)
            all.computeBounds(bounds, true)
            val pad = paint.strokeWidth
            val left = (bounds.left - pad).coerceAtLeast(0f).toInt()
            val top = (bounds.top - pad).coerceAtLeast(0f).toInt()
            val right = (bounds.right + pad).coerceAtMost(width.toFloat()).toInt()
            val bottom = (bounds.bottom + pad).coerceAtMost(height.toFloat()).toInt()
            if (right - left < 4 || bottom - top < 4) return full
            val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            if (cropped !== full) full.recycle()
            return cropped
        }
    }

    fun show(activity: Activity, onDone: (Boolean) -> Unit) {
        val ctx = activity
        val d = { v: Int -> (v * ctx.resources.displayMetrics.density + 0.5f).toInt() }
        val pad = PadView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, d(190)
            )
            background = GradientDrawable().apply {
                cornerRadius = d(12).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(d(1), 0x33000000)
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20), d(8), d(20), d(4))
            addView(TextView(ctx).apply {
                setText(R.string.sig_hint)
                textSize = 12.5f
                alpha = 0.75f
                setPadding(0, 0, 0, d(8))
            })
            addView(pad)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sig_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val bmp = pad.toBitmap()
                val ok = bmp != null && AiBusiness.saveSignature(ctx, bmp)
                onDone(ok)
            }
            .setNeutralButton(R.string.sig_clear) { _, _ ->
                AiBusiness.clearSignature(ctx)
                onDone(false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
