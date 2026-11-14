package com.zephyr.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zephyr.browser.R

/**
 * v1.7.28: the «drag it down here to dismiss» target.
 *
 * Both the floating player and the AI bubble used to (or, for the AI bubble, did
 * not) offer this. The player's version was a flat grey circle with a bare ✕
 * (user: «кнопка X не красивая, сделай что-то лучше»), so this replaces it with
 * a proper drop target: a frosted disc with a soft halo and a hairline ring, a
 * caption underneath, and an armed state that swells into a red gradient with a
 * haptic tick — the same language as iOS PiP / Messenger chat heads.
 */
class DragCloseTarget private constructor(
    val view: FrameLayout,
    private val disc: FrameLayout,
    private val halo: View,
    private val icon: ImageView,
    private val caption: TextView,
    private val idleBg: GradientDrawable,
    private val armedBg: GradientDrawable
) {

    private var armed = false

    /** Screen-space center of the disc, or null before it is laid out. */
    fun discCenter(): FloatArray? {
        if (disc.width <= 0) return null
        val loc = IntArray(2)
        return try {
            disc.getLocationOnScreen(loc)
            floatArrayOf(loc[0] + disc.width / 2f, loc[1] + disc.height / 2f)
        } catch (_: Exception) { null }
    }

    /** Radius (px) within which a drag counts as "over the target". */
    fun catchRadius(): Float = (disc.width.coerceAtLeast(1) * 0.95f)

    fun show() {
        if (view.visibility == View.VISIBLE) return
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = view.height.toFloat().coerceAtLeast(40f) * 0.4f
        view.animate().alpha(1f).translationY(0f).setDuration(160).start()
    }

    fun hide() {
        if (view.visibility != View.VISIBLE) return
        view.animate().alpha(0f).translationY(view.height * 0.3f).setDuration(140)
            .withEndAction { view.visibility = View.GONE; view.translationY = 0f }.start()
    }

    fun setArmed(a: Boolean) {
        if (a == armed) return
        armed = a
        disc.background = if (a) armedBg else idleBg
        icon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (a) Color.WHITE else 0xFFE6E6EE.toInt()
        )
        caption.setTextColor(if (a) 0xFFFF9C9C.toInt() else 0xFFBFBFCC.toInt())
        caption.setText(if (a) R.string.drag_close_release else R.string.drag_close_hint)
        try { disc.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
        disc.animate().scaleX(if (a) 1.22f else 1f).scaleY(if (a) 1.22f else 1f)
            .setDuration(140).start()
        halo.animate().alpha(if (a) 0.55f else 0.22f)
            .scaleX(if (a) 1.35f else 1f).scaleY(if (a) 1.35f else 1f)
            .setDuration(140).start()
    }

    fun detach() {
        try { (view.parent as? ViewGroup)?.removeView(view) } catch (_: Exception) {}
    }

    companion object {

        private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

        /** Build the target and attach it to the activity content view. */
        fun attach(ctx: Context, content: ViewGroup, bottomMarginDp: Int = 34): DragCloseTarget {
            val discSize = dp(ctx, 62)
            val haloSize = dp(ctx, 108)

            // soft radial halo behind the disc
            val halo = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = haloSize / 2f
                    colors = intArrayOf(0x66000000, 0x00000000)
                }
                alpha = 0.22f
                layoutParams = FrameLayout.LayoutParams(haloSize, haloSize, Gravity.CENTER)
            }

            val idleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xD91C1C24.toInt())
                setStroke(dp(ctx, 1), 0x33FFFFFF)
            }
            val armedBg = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF87171.toInt(), 0xFFDC2626.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(ctx, 2), 0x66FFFFFF)
            }

            val icon = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFE6E6EE.toInt())
                layoutParams = FrameLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24), Gravity.CENTER)
            }

            val disc = FrameLayout(ctx).apply {
                background = idleBg
                // v1.7.38: no elevation anywhere here — the user sees only the
                // square shadow rectangle, never the softness it was meant to add
                elevation = 0f
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setOval(0, 0, v.width, v.height)
                    }
                }
                layoutParams = FrameLayout.LayoutParams(discSize, discSize, Gravity.CENTER)
                addView(icon)
            }

            val discWrap = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(haloSize, haloSize)
                    .apply { gravity = Gravity.CENTER_HORIZONTAL }
                addView(halo)
                addView(disc)
            }

            val caption = TextView(ctx).apply {
                setText(R.string.drag_close_hint)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(0xFFBFBFCC.toInt())
                setPadding(dp(ctx, 12), dp(ctx, 2), dp(ctx, 12), dp(ctx, 6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 10).toFloat()
                    setColor(0x99000000.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(ctx, 2) }
            }

            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(discWrap)
                addView(caption)
            }

            val holder = FrameLayout(ctx).apply {
                visibility = View.GONE
                // Elevation is still needed to sit ABOVE the floating player
                // window (12), but an elevated view casts a shadow shaped like
                // its bounds — the square the user kept seeing. An outline with
                // zero alpha keeps the z-order and draws no shadow at all.
                elevation = dp(ctx, 16).toFloat()
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRect(0, 0, v.width, v.height)
                        o.alpha = 0f
                    }
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                ).apply { bottomMargin = dp(ctx, bottomMarginDp) }
                addView(column)
            }

            try { content.addView(holder) } catch (_: Exception) {}
            return DragCloseTarget(holder, disc, halo, icon, caption, idleBg, armedBg)
        }
    }
}
