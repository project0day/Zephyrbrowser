package com.zephyr.browser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.sin

/**
 * v1.7.47: the "something is being made" animation for the AI chat.
 *
 * Three blinking dots said "typing", not "drawing", and looked borrowed. This
 * is Zephyr's own: a field of small dots rolling through a travelling sine
 * wave, lit by a gradient sweep that runs along the same direction. The wave
 * carries left to right, the brightness follows the crest, so the block reads
 * as work in progress rather than a stalled spinner.
 *
 * Self-contained: one View, one animation callback, no drawables, no library.
 */
class GenerativeWaveView(ctx: Context) : View(ctx) {

    var tintStart: Int = 0xFF6366F1.toInt()
    var tintEnd: Int = 0xFFA855F7.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val dotRadius = 2.6f * density
    private val spacing = 11f * density

    private var phase = 0f
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            phase += 0.085f
            if (phase > Math.PI.toFloat() * 20f) phase = 0f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (46 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return

        // the sweep travels with the wave, so the bright band sits on the crest
        val sweep = ((phase * 0.16f) % 2f) - 0.5f
        paint.shader = LinearGradient(
            (sweep - 0.45f) * w, 0f, (sweep + 0.45f) * w, 0f,
            intArrayOf(tintStart, tintEnd, tintStart),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        val count = ((w - spacing) / spacing).toInt().coerceAtLeast(1)
        val left = (w - (count - 1) * spacing) / 2f
        val mid = h / 2f
        val amplitude = 6.5f * density

        for (i in 0 until count) {
            val x = left + i * spacing
            val t = phase - i * 0.42f
            val y = mid + sin(t.toDouble()).toFloat() * amplitude
            // dots at the crest are larger and more opaque — the eye follows it
            val lift = (sin(t.toDouble()).toFloat() + 1f) / 2f
            paint.alpha = (70 + 150 * lift).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, dotRadius * (0.72f + 0.46f * lift), paint)
        }
        paint.alpha = 255
        paint.shader = null
        paint.color = Color.TRANSPARENT
    }
}
