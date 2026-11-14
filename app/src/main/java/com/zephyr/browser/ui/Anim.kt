package com.zephyr.browser.ui

import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * v1.5.1 rework: shared view animations with a modern material feel.
 *  - interpolators: fast-out-slow-in style via layered Decelerate/Overshoot;
 *  - stagger() caps the TOTAL cascade time (the old open-ended per-child delay is
 *    what made exits look "clunky" — only the top rows had started moving);
 *  - popIn uses a gentle overshoot (springy settle) instead of a flat decelerate.
 * All helpers are fire-and-forget ViewPropertyAnimator wrappers — safe to call on
 * any freshly-built view tree (they run on the next frame, after measure/layout).
 */
object Anim {

    private val DECEL = DecelerateInterpolator(1.6f)
    private val SOFT_DECEL = DecelerateInterpolator(1.2f)
    private val OVERSHOOT = OvershootInterpolator(1.08f)
    private val ACCEL = AccelerateInterpolator(1.1f)

    /** Fade-in with an optional stagger delay. */
    fun fadeIn(v: View, duration: Long = 200, delay: Long = 0) {
        v.alpha = 0f
        v.animate().cancel()
        v.animate().alpha(1f)
            .setDuration(duration).setStartDelay(delay)
            .setInterpolator(SOFT_DECEL).start()
    }

    /** Scale-in (0.9→1) + fade with a springy settle — popups, cards, dialogs. */
    fun popIn(v: View, duration: Long = 240, delay: Long = 0, fromScale: Float = 0.9f) {
        v.alpha = 0f
        v.scaleX = fromScale
        v.scaleY = fromScale
        v.animate().cancel()
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(duration).setStartDelay(delay)
            .setInterpolator(OVERSHOOT).start()
    }

    /** Slide-up + fade (sheets, panels, overlays). */
    fun slideUp(v: View, distanceDp: Float = 28f, duration: Long = 280, delay: Long = 0) {
        val dp = v.resources.displayMetrics.density
        v.translationY = distanceDp * dp
        v.alpha = 0f
        v.animate().cancel()
        v.animate().translationY(0f).alpha(1f)
            .setDuration(duration).setStartDelay(delay)
            .setInterpolator(DECEL).start()
    }

    /** Fade-out helper (returns immediately; useful before hiding a view). */
    fun fadeOut(v: View, duration: Long = 160, endAction: (() -> Unit)? = null) {
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(duration).setInterpolator(ACCEL)
            .withEndAction { endAction?.invoke() }.start()
    }

    /** v1.5.1: scale-down + fade exit (mirror of popIn — for dismiss animations). */
    fun popOut(v: View, duration: Long = 150, toScale: Float = 0.96f, endAction: (() -> Unit)? = null) {
        v.animate().cancel()
        v.animate().alpha(0f).scaleX(toScale).scaleY(toScale)
            .setDuration(duration).setInterpolator(ACCEL)
            .withEndAction { endAction?.invoke() }.start()
    }

    /**
     * Staggered entrance of a container's direct children.
     * v1.5.1: total cascade is CAPPED at ~260 ms — children beyond the cap join the
     * last wave instead of lagging behind one by one (the old open-ended delays made
     * late rows pop in long after the first ones, and exits showed only the top rows
     * moving). Combined slide-up + micro-scale + fade for a richer motion.
     */
    fun stagger(group: ViewGroup, stepMs: Long = 18, duration: Long = 230) {
        val n = group.childCount
        if (n <= 0) return
        val cap = 260L
        val step = if (n > 1) (cap / (n - 1)).coerceAtMost(stepMs) else 0L
        val dp = group.resources.displayMetrics.density
        for (i in 0 until n) {
            val c = group.getChildAt(i)
            c.alpha = 0f
            c.translationY = 8f * dp
            c.scaleX = 0.985f
            c.animate().cancel()
            c.animate().alpha(1f).translationY(0f).scaleX(1f)
                .setDuration(duration).setStartDelay(i * step)
                .setInterpolator(SOFT_DECEL).start()
        }
    }

    /** v1.5.1: cascade for GRID layouts — row-major wave (left column leads slightly). */
    fun cascade(group: ViewGroup, columns: Int = 2, duration: Long = 240) {
        val n = group.childCount
        if (n <= 0) return
        val dp = group.resources.displayMetrics.density
        for (i in 0 until n) {
            val c = group.getChildAt(i)
            val wave = i / columns
            val maxWave = ((n - 1) / columns).coerceAtLeast(1)
            val delay = (wave * (240f / maxWave)).toLong()
            c.alpha = 0f
            c.translationY = 12f * dp
            c.animate().cancel()
            c.animate().alpha(1f).translationY(0f)
                .setDuration(duration).setStartDelay(delay)
                .setInterpolator(SOFT_DECEL).start()
        }
    }

    /** Subtle press feedback (callable from any OnClickListener wrapper). */
    fun press(v: View) {
        v.animate().cancel()
        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).setInterpolator(ACCEL)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(OVERSHOOT).start()
            }.start()
    }
}
