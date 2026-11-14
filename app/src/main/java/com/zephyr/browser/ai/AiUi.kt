package com.zephyr.browser.ai

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zephyr.browser.R
import com.zephyr.browser.settings.SettingsUi

/**
 * v1.7.25b: shared chrome for the AI sheets (chat, chat list, model picker) so
 * they all look like one product instead of three different dialogs.
 */
object AiUi {

    const val ACCENT = 0xFF6366F1.toInt()
    const val ACCENT_2 = 0xFFA855F7.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun onSurface(ctx: Context) = SettingsUi.themedColor(ctx, R.attr.colorOnSurface, 0xFFE8E8F0.toInt())
    fun onVariant(ctx: Context) = SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant, 0xFF9A9AB0.toInt())
    fun surface(ctx: Context) = SettingsUi.themedColor(ctx, R.attr.colorSurface, 0xFF15151F.toInt())
    fun surfaceVar(ctx: Context) = SettingsUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFF25252F.toInt())

    /** Luminance test so the system bars get matching light/dark icons. */
    fun isDarkSurface(c: Int): Boolean =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) < 140

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    fun blend(base: Int, over: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(over) * ratio).toInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(over) * ratio).toInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(over) * ratio).toInt()
        return Color.argb(255, r, g, b)
    }

    fun ripple(ctx: Context, borderless: Boolean = true): Drawable? = try {
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless
        else android.R.attr.selectableItemBackground
        val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
        val d = ta.getDrawable(0)
        ta.recycle()
        d
    } catch (_: Exception) { null }

    /** Top-rounded surface panel that fills [heightRatio] of the screen. */
    fun panel(ctx: Context, heightRatio: Float): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val h = if (heightRatio <= 0f) ViewGroup.LayoutParams.WRAP_CONTENT
        else (ctx.resources.displayMetrics.heightPixels * heightRatio).toInt()
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = dp(ctx, 26).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(surface(ctx))
        }
        addView(grabber(ctx))
    }

    fun grabber(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(ctx, 10)
            bottomMargin = dp(ctx, 8)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 2).toFloat()
            setColor(withAlpha(onVariant(ctx), 0x66))
        }
    }

    fun sheetTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(onSurface(ctx))
        setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 10))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        setBackgroundColor(withAlpha(onVariant(ctx), 0x1F))
    }

    /**
     * Scrollable body that takes the remaining height of a [panel].
     *
     * v1.7.47 FIX (user: «в окне выбора модели скролл вверх срабатывает плохо,
     * лист хочет закрыться»): a plain ScrollView does not cooperate with
     * BottomSheetBehavior, so an upward drag inside the list was read as a drag
     * on the sheet itself and started dismissing it. NestedScrollView reports
     * its scroll position to the behaviour, so the sheet only moves once the
     * list is genuinely at the top.
     */
    fun body(ctx: Context): Pair<androidx.core.widget.NestedScrollView, LinearLayout> {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 4), dp(ctx, 14), dp(ctx, 12))
        }
        val scroll = androidx.core.widget.NestedScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isNestedScrollingEnabled = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(col)
        }
        return scroll to col
    }

    /** Round gradient avatar carrying one or two letters — used for model rows. */
    fun initialAvatar(ctx: Context, label: String, sizeDp: Int = 40): TextView = TextView(ctx).apply {
        val initials = label.trim().split(" ", "-", "/").filter { it.isNotBlank() }
            .take(2).joinToString("") { it.first().uppercase() }
            .ifBlank { "AI" }
        text = initials
        textSize = if (sizeDp >= 40) 14f else 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        val tint = hueFor(label)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(tint, blend(tint, ACCENT_2, 0.45f))
        ).apply { cornerRadius = dp(ctx, sizeDp / 3).toFloat() }
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            .apply { marginEnd = dp(ctx, 13) }
    }

    /** Stable per-name accent so every provider keeps its own colour. */
    private fun hueFor(name: String): Int {
        val palette = intArrayOf(
            0xFF6366F1.toInt(), 0xFF0EA5E9.toInt(), 0xFF10B981.toInt(),
            0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFFA855F7.toInt(),
            0xFF14B8A6.toInt(), 0xFFEC4899.toInt()
        )
        var h = 7
        for (c in name) h = h * 31 + c.code
        return palette[((h % palette.size) + palette.size) % palette.size]
    }

    /** A full-width pill button used at the bottom of the sheets. */
    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = 14.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT_2)
        ).apply { cornerRadius = dp(ctx, 18).toFloat() }
        foreground = ripple(ctx, borderless = false)
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(ctx, 16); marginEnd = dp(ctx, 16)
            topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 18)
        }
        setOnClickListener { onClick() }
    }

    /** Card row shell used by the chat list and the model picker. */
    fun selectableRow(ctx: Context, selected: Boolean): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 13), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 18).toFloat()
            setColor(if (selected) withAlpha(ACCENT, 0x24) else surfaceVar(ctx))
            if (selected) setStroke(dp(ctx, 1), withAlpha(ACCENT, 0xAA))
        }
        foreground = ripple(ctx, borderless = false)
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 8) }
    }

    fun twoLine(ctx: Context, title: String, sub: String, strongTitle: Boolean = true): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = title
                textSize = 14.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (strongTitle) typeface = Typeface.DEFAULT_BOLD
                setTextColor(onSurface(ctx))
            })
            if (sub.isNotBlank()) addView(TextView(ctx).apply {
                text = sub
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 2), 0, 0)
                setTextColor(onVariant(ctx))
            })
        }

    /** Build a bottom sheet already wearing the AI chrome. */
    /**
     * Height of the system navigation bar, or 0 when there is none.
     * v1.7.34: a BottomSheetDialog lays out edge-to-edge, so its last row sits
     * UNDER the gesture bar (user: «внизу панель навигации перекрывает кнопку
     * Отключить агента»). Every sheet reserves this much at the bottom.
     */
    fun navBarHeight(ctx: Context): Int = try {
        val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    } catch (_: Throwable) { 0 }

    fun sheet(ctx: Context, heightRatio: Float): Pair<BottomSheetDialog, LinearLayout> {
        val d = BottomSheetDialog(ctx)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true

        val screenH = ctx.resources.displayMetrics.heightPixels
        val root: LinearLayout
        if (heightRatio >= 0.85f) {
            // v1.7.25b KEYBOARD FIX (user: «при тапе по полю ввода клавиатура
            // открывается, а поле ввода остаётся внизу»): a panel with a FIXED
            // pixel height cannot shrink when the IME appears, so the composer
            // stayed behind the keyboard. A full-height sheet (MATCH_PARENT +
            // expandedOffset for the top gap) shrinks together with the resized
            // window, and the weighted message list gives the space up.
            d.behavior.isFitToContents = false
            d.behavior.expandedOffset = (screenH * (1f - heightRatio)).toInt()
            root = panel(ctx, -1f)
            root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        } else {
            root = panel(ctx, heightRatio)
        }
        // v1.7.34: keep the last row clear of the gesture bar
        root.setPadding(
            root.paddingLeft, root.paddingTop,
            root.paddingRight, root.paddingBottom + navBarHeight(ctx)
        )
        d.setContentView(root)
        d.window?.apply {
            // ADJUST_RESIZE only reaches the content while the window is allowed
            // to be laid out inside the system insets.
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        return d to root
    }

    /**
     * v1.7.26: the chat window is a FULL-SCREEN dialog, not a bottom sheet.
     *
     * The sheet misbehaved with the keyboard (user: «диалог открывается наверху
     * и обрезанный… потом резко опустился и уже за клавой»): BottomSheetBehavior
     * re-settles itself while the window is being resized for the IME, so the
     * panel jumped and the composer ended up under the keyboard. A plain dialog
     * with MATCH_PARENT content and ADJUST_RESIZE simply shrinks — the message
     * list gives up the space and the composer always sits on the keyboard.
     *
     * Returns the dialog plus the content column; tapping the strip above the
     * panel calls [onScrimTap] (used to minimize into the floating bubble).
     */
    fun fullScreen(ctx: Context, topGapRatio: Float, onScrimTap: () -> Unit): Pair<android.app.Dialog, LinearLayout> {
        val d = android.app.Dialog(ctx)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val gap = (ctx.resources.displayMetrics.heightPixels * topGapRatio).toInt()
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = gap }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val r = dp(ctx, 26).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(surface(ctx))
            }
            // swallow taps so they never reach the scrim below
            isClickable = true
            addView(grabber(ctx))
        }
        val holder = android.widget.FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            // v1.7.31: OUR OWN scrim, painted inside the dialog content.
            // The window-level dim used to darken the activity BEHIND the
            // dialog — including its navigation bar — so the bar could never
            // match the panel no matter what colour it was set to (user: «так
            // и не смог добиться цвета панели навигации»). Drawing the scrim
            // ourselves leaves the system bar untouched and exactly on tone.
            // v1.7.38 (user: «взади чёрный прозрачный экран окрашивается — можно
            // ли убрать?»): no scrim at all. The panel covers almost everything
            // anyway, and the strip above it now shows the page unchanged.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { onScrimTap() }
            addView(panel)
        }

        d.setContentView(holder)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setDimAmount(0f)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            // v1.7.27 (user: «почему чёрная панель навигации, а не в цвет
            // темы?»): a plain Dialog window does not inherit the activity's
            // system-bar colors, so the gesture bar fell back to black.
            try {
                // v1.7.28: navigationBarColor is IGNORED unless the window is
                // allowed to draw the system-bar backgrounds itself — without
                // these flags the gesture bar stayed black (user: «выхожу назад
                // на главное окно ИИ — всё ещё чёрная панель навигации»).
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                val bg = surface(ctx)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = bg
                // v1.7.29 (user: «панель навигации чуть светлее — хочу прям в
                // цвет диалога»): Android 10+ paints a translucent contrast
                // scrim over the gesture bar unless this is switched off, and
                // that scrim was the lighter tint on top of our surface color.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    isNavigationBarContrastEnforced = false
                }
                val lightBars = !isDarkSurface(bg)
                androidx.core.view.WindowCompat.getInsetsController(this, decorView).apply {
                    isAppearanceLightNavigationBars = lightBars
                    isAppearanceLightStatusBars = lightBars
                }
            } catch (_: Throwable) {}
        }

        // v1.7.30 — the gesture bar, take three (user: «ты так и не одолел её,
        // хотя при нажатии на Историю диалогов она окрашивается»).
        //
        // That observation was the answer: the history sheet is a
        // BottomSheetDialog, and Material's edge-to-edge helper recolors the
        // bar THROUGH THE HOSTING ACTIVITY. A plain Dialog cannot — its own
        // navigationBarColor is only honoured while the dialog window itself
        // owns the bar, which a dimmed, transparent-background dialog does not.
        // So stop fighting the dialog window and tint the activity's bar for as
        // long as the chat is open, restoring it verbatim afterwards.
        tintHostBars(ctx, d)
        return d to panel
    }

    /** Recolor the HOST activity's system bars while [d] is showing. */
    private fun tintHostBars(ctx: Context, d: android.app.Dialog) {
        val act = ctx as? android.app.Activity ?: return
        val host = act.window ?: return
        val bg = surface(ctx)
        val prevNav = host.navigationBarColor
        val prevStatus = host.statusBarColor
        val prevContrast =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                host.isNavigationBarContrastEnforced else false
        val controller = androidx.core.view.WindowCompat.getInsetsController(host, host.decorView)
        val prevLightNav = controller.isAppearanceLightNavigationBars

        fun apply() {
            try {
                host.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                host.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                host.navigationBarColor = bg
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    host.isNavigationBarContrastEnforced = false
                }
                controller.isAppearanceLightNavigationBars = !isDarkSurface(bg)
            } catch (_: Throwable) {}
        }
        fun restore() {
            try {
                host.navigationBarColor = prevNav
                host.statusBarColor = prevStatus
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    host.isNavigationBarContrastEnforced = prevContrast
                }
                controller.isAppearanceLightNavigationBars = prevLightNav
            } catch (_: Throwable) {}
        }

        // Hook the decor view's attach state, NOT setOnShowListener /
        // setOnDismissListener: the caller installs its own dismiss handler
        // right after this and would silently replace ours.
        d.window?.decorView?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = apply()
                override fun onViewDetachedFromWindow(v: View) = restore()
            }
        )
    }
}
