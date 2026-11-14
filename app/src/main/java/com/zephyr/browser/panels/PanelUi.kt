package com.zephyr.browser.panels

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/** Shared programmatic-UI helpers for panel screens. */
object PanelUi {

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    fun rippleRes(ctx: Context): Int {
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        ta.recycle()
        return android.R.attr.selectableItemBackground
    }

    fun ripple(ctx: Context): android.graphics.drawable.Drawable? = try {
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = ta.getDrawable(0)
        ta.recycle()
        d
    } catch (_: Exception) { null }

    fun themedColor(ctx: Context, attr: Int, fallback: Int = 0xFF5F6368.toInt()): Int = try {
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, fallback)
        ta.recycle()
        c
    } catch (_: Exception) { fallback }

    /**
     * Dialog text field inside a TextInputLayout.
     * FIX for the ClassCastException crash (ViewGroup$LayoutParams → LinearLayout$LayoutParams):
     * the EditText is added with explicit LinearLayout.LayoutParams.
     */
    fun dialogField(ctx: Context, hint: String, preset: String = ""): TextInputEditText {
        val layout = TextInputLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            isHintEnabled = true
        }
        val edit = TextInputEditText(ctx).apply {
            this.hint = hint
            setText(preset)
            setSingleLine(true)
        }
        // THE fix: explicit LinearLayout.LayoutParams when adding into TextInputLayout
        layout.addView(edit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // expose layout through the tag so callers can add THIS view (the layout) into dialogs
        edit.tag = layout
        return edit
    }

    /** Wrap [dialogField] result: returns the TextInputLayout that hosts the returned edit. */
    fun fieldWrap(edit: TextInputEditText): TextInputLayout = edit.tag as TextInputLayout

    /**
     * v1.5.0: dialog field with a FLOATING label + in-field placeholder.
     * Replaces the broken pattern `dialogField(...).apply { hint = "MM/YY" }` — a hint set
     * on the EditText AFTER it is wrapped by TextInputLayout overlaps the typed text
     * (the layout cannot float it). Here the label floats on the layout and the
     * placeholder only shows while the field is empty.
     */
    fun dialogFieldLabeled(
        ctx: Context,
        label: String,
        placeholder: String? = null,
        preset: String = ""
    ): TextInputEditText {
        val layout = TextInputLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            isHintEnabled = true
            hint = label
            placeholder?.let { placeholderText = it }
        }
        val edit = TextInputEditText(ctx).apply {
            setText(preset)
            setSingleLine(true)
        }
        layout.addView(edit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        edit.tag = layout
        return edit
    }

    /** v1.7.1: custom accent when set, else theme colorPrimary — panels chrome follows it. */
    fun accent(ctx: Context): Int =
        Prefs.accentOrDefault(themedColor(ctx, com.zephyr.browser.R.attr.colorPrimary))

    /** Section header (small colored caps label). v1.7.1: follows the custom accent. */
    fun sectionHeader(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(accent(ctx))
        setPadding(ctx.dp(4), ctx.dp(18), ctx.dp(4), ctx.dp(8))
    }

    /** List entry row: favicon-ish icon + 2-line text + optional trailing view. */
    fun entryRow(
        ctx: Context,
        iconRes: Int? = null,
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), ctx.dp(11), ctx.dp(8), ctx.dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            foreground = ripple(ctx)
            if (onClick != null) setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        if (iconRes != null) {
            row.addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22))
                imageTintList = android.content.res.ColorStateList.valueOf(
                    themedColor(ctx, com.zephyr.browser.R.attr.colorOnSurfaceVariant))
            })
            (row.getChildAt(0).layoutParams as LinearLayout.LayoutParams).marginEnd = ctx.dp(14)
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = title
                textSize = 14.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(themedColor(ctx, com.zephyr.browser.R.attr.colorOnSurface))
            })
            if (subtitle != null) addView(TextView(ctx).apply {
                text = subtitle
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(themedColor(ctx, com.zephyr.browser.R.attr.colorOnSurfaceVariant))
            })
        })
        if (trailing != null) row.addView(trailing)
        return row
    }

    /** Empty-state text. */
    /**
     * v1.7.90: the one empty state.
     *
     * An empty list is the worst place to say nothing. Some panels had a single
     * grey line, some had a full icon-title-hint block, and a few had nothing at
     * all — so whether a new user was told what a screen is for depended on
     * which screen they opened first.
     *
     * Three parts, and all three earn their place: the icon makes it read as a
     * deliberate state rather than a failure to load, the title says what is
     * missing, and the hint says how to get one. A hint that only restates the
     * title ("No bookmarks yet") is worse than none — say what to do.
     */
    fun emptyState(ctx: Context, iconRes: Int, titleRes: Int, hintRes: Int): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(32), ctx.dp(54), ctx.dp(32), ctx.dp(54))

            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ctx.dp(74), ctx.dp(74))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    // the accent at ~12%: present, but not a button
                    setColor((accent(ctx) and 0x00FFFFFF) or 0x1F000000)
                }
                addView(ImageView(ctx).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(accent(ctx))
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34))
                })
            })
            addView(TextView(ctx).apply {
                setText(titleRes)
                textSize = 16.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(themedColor(ctx, com.zephyr.browser.R.attr.colorOnSurface))
                setPadding(0, ctx.dp(16), 0, 0)
            })
            addView(TextView(ctx).apply {
                setText(hintRes)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setLineSpacing(ctx.dp(2).toFloat(), 1f)
                setTextColor(themedColor(ctx, com.zephyr.browser.R.attr.colorOnSurfaceVariant))
                setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), 0)
            })
        }

    @Deprecated("Use emptyState — a bare line does not tell the user what to do")
    fun emptyView(ctx: Context, textRes: Int): TextView = TextView(ctx).apply {
        setText(textRes)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, ctx.dp(48), 0, ctx.dp(48))
        setTextColor(themedColor(ctx, com.zephyr.browser.R.attr.colorOnSurfaceVariant))
    }

    /** Simple gradient "stat chip" (for nice gradient accents). */
    fun gradientChip(ctx: Context, text: String, colors: IntArray): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(14), ctx.dp(6))
        background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TL_BR
            this.colors = colors
            cornerRadius = ctx.dp(18).toFloat()
        }
    }

    // =====================================================================
    // v1.4.1 premium list framework: card rows, thumbnails, swipe-to-dismiss
    // =====================================================================

    /** Rounded pill background for search fields in panels. */
    fun searchFieldBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ctx.dp(22).toFloat()
        setColor(themedColor(ctx, com.zephyr.browser.R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt()))
    }

    /** Standard list column: the LinearLayout returned here must be hosted in a ScrollView. */
    fun listColumn(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(24))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // v1.7.7: shared premium surfaces (reader library cards etc.)
    fun rounded(ctx: Context, radiusDp: Int): GradientDrawable = cardBg(ctx, radiusDp)
    fun roundedFill(ctx: Context, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ctx.dp(radiusDp).toFloat()
        setColor(accent(ctx))
    }
    fun roundedFillAttr(ctx: Context, radiusDp: Int, attr: Int = R.attr.colorPrimary): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ctx.dp(radiusDp).toFloat()
        setColor(themedColor(ctx, attr))
    }

    fun cardBg(ctx: Context, radiusDp: Int = 16): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ctx.dp(radiusDp).toFloat()
        setColor(themedColor(ctx, com.zephyr.browser.R.attr.colorSurface, 0xFFFFFFFF.toInt()))
        setStroke(ctx.dp(1).coerceAtLeast(1), themedColor(ctx, com.zephyr.browser.R.attr.colorOutline, 0x14000000.toInt()))
    }

    /**
     * Premium card row: rounded surface card with optional leading view (thumbnail / icon chip),
     * 2-line text and optional trailing view. Replaces the flat entryRow look.
     */
    fun cardRow(
        ctx: Context,
        leading: View? = null,
        iconRes: Int? = null,
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), ctx.dp(11), ctx.dp(8), ctx.dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ctx.dp(8)
            }
            background = cardBg(ctx)
            isClickable = true
            isFocusable = true
            clipToOutline = true
            foreground = ripple(ctx)
            if (onClick != null) setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        when {
            leading != null -> row.addView(leading)
            iconRes != null -> row.addView(iconChip(ctx, iconRes))
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = title
                textSize = 14.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(themedColor(ctx, com.zephyr.browser.R.attr.colorOnSurface))
            })
            if (subtitle != null) addView(TextView(ctx).apply {
                text = subtitle
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(0, ctx.dp(2), 0, 0)
                setTextColor(themedColor(ctx, com.zephyr.browser.R.attr.colorOnSurfaceVariant))
            })
        })
        if (trailing != null) row.addView(trailing)
        return row
    }

    /** Rounded tinted circle with an icon — the default leading decoration for card rows. */
    fun iconChip(ctx: Context, iconRes: Int, sizeDp: Int = 42): LinearLayout = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // v1.7.1: 12% custom accent when overridden, theme container otherwise
            setColor(if (Prefs.customAccent != 0)
                (accent(ctx) and 0x00FFFFFF) or 0x20000000
            else themedColor(ctx, com.zephyr.browser.R.attr.colorPrimaryContainer, 0x1F1A73E8.toInt()))
        }
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp)).apply {
            marginEnd = ctx.dp(14)
        }
        addView(android.widget.ImageView(ctx).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20))
            imageTintList = android.content.res.ColorStateList.valueOf(
                accent(ctx))
        })
    }

    /** Thumbnail frame: rounded square preview (downloads / tabs / media). */
    fun thumbFrame(ctx: Context, sizeDp: Int = 46, radiusDp: Int = 12): android.widget.FrameLayout =
        android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp)).apply {
                marginEnd = ctx.dp(14)
            }
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(radiusDp).toFloat()
                setColor(themedColor(ctx, com.zephyr.browser.R.attr.colorSurfaceVariant, 0xFFEFF1F3.toInt()))
            }
            clipToOutline = true
        }

    /**
     * Swipe-to-dismiss wrapper: drag horizontally to reveal a delete action; release past
     * the threshold fires [onRemove] with a slide-out animation.
     */
    fun swipeable(
        ctx: Context,
        child: View,
        onRemove: () -> Unit
    ): android.widget.FrameLayout {
        val density = ctx.resources.displayMetrics.density
        val threshold = 90f * density
        val deleteIcon = android.widget.ImageView(ctx).apply {
            setImageResource(com.zephyr.browser.R.drawable.ic_delete)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (22 * density).toInt(), (22 * density).toInt(),
                Gravity.CENTER_VERTICAL or Gravity.END)
            (layoutParams as android.widget.FrameLayout.LayoutParams).marginEnd = (20 * density).toInt()
        }
        val deleteBg = GradientDrawable().apply {
            cornerRadius = ctx.dp(16).toFloat()
            setColor(0xFFD93025.toInt())
        }
        val action = android.widget.FrameLayout(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
            background = deleteBg
            clipToOutline = true
            addView(deleteIcon)
        }
        val wrap = object : android.widget.FrameLayout(ctx) {
            var startX = 0f
            var startY = 0f
            var downAt = 0L
            var startTrans = 0f
            var dragging = false
            override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                        startY = e.y
                        downAt = e.eventTime
                        startTrans = child.translationX
                        dragging = false
                        parent.requestDisallowInterceptTouchEvent(false)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - startX
                        val dy = e.y - startY
                        if (kotlin.math.abs(dx) > 16 * density &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                        ) {
                            dragging = true
                            parent.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
                return false
            }
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - startX
                        val t = (startTrans + dx).coerceAtMost(0f)   // swipe LEFT only
                        child.translationX = t
                        action.translationX = width.toFloat() + t * 0.35f
                        action.alpha = (1f + t / threshold).coerceIn(0f, 1f)
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val totalX = kotlin.math.abs(e.x - startX)
                        val totalY = kotlin.math.abs(e.y - startY)
                        val dtMs = e.eventTime - downAt
                        if (dragging) {
                            if (child.translationX < -threshold) {
                                animate().translationX(-width.toFloat() * 1.05f).alpha(0f)
                                    .setDuration(190).withEndAction {
                                        onRemove()
                                    }.start()
                            } else {
                                child.animate().translationX(0f).setDuration(170).start()
                                action.animate().translationX(width.toFloat()).alpha(0f).setDuration(170).start()
                                if (e.actionMasked == android.view.MotionEvent.ACTION_UP &&
                                    totalX < 60 * density && dtMs < 400
                                ) {
                                    child.performClick()
                                }
                            }
                        } else if (e.actionMasked == android.view.MotionEvent.ACTION_UP &&
                            totalX < 20 * density && totalY < 20 * density && dtMs < 400
                        ) {
                            child.performClick()
                        }
                        return dragging
                    }
                }
                return false
            }
        }
        action.translationX = 100000f   // parked offscreen until dragged
        action.alpha = 0f
        wrap.addView(action)
        wrap.addView(child, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    // ------------------------------------------------------------------
    // v1.7.8: dismissable explanation banners
    // v1.7.9: PREMIUM aurora redesign; v1.7.14: made STATIC (light sweep removed)
    // ------------------------------------------------------------------

    /** v1.7.9 PREMIUM REDESIGN (user: «Все подсказки которые ты сделал они мега
     *  страшные!!!!! в какойто обводке круглой! что за дичь?? неужели нельзя
     *  сделать красивые макеты градиентные с анимациями света взади или еще
     *  чтото придумать красивое!!! я сказал премиум качество а не топорное
     *  тап ляп!! переделай!!»): the old hairline-stroke card is gone. This now
     *  builds an "aurora" insight card — a deep violet→indigo→teal gradient
     *  (theme-agnostic, works on light and dark), two soft STATIC glow blobs
     *  behind the text, a frosted icon chip and a frosted ripple close pill.
     *  v1.7.14 (user: «анимация прохода прожектора убери её, не красиво»): ALL
     *  continuous animation is GONE — the periodic skewed light sweep and the
     *  drifting/breathing blob animators were removed; the blobs stay as static
     *  decoration, so the card keeps its premium depth with zero motion.
     *  Entrance is still a fade+rise; dismissal is still a fade+slide. API
     *  unchanged: returns null when [key] was already dismissed — existing
     *  call sites need no edits. */
    fun hintBanner(ctx: Context, key: String, iconRes: Int, title: String, body: String): View? {
        if (Prefs.isHintDismissed(key)) return null
        val dp = { v: Int -> (v * ctx.resources.displayMetrics.density + 0.5f).toInt() }

        // ── 1. aurora base: dark violet→indigo→teal, radius 22, NO stroke ─────
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF2D2A55.toInt(), 0xFF223A63.toInt(), 0xFF164E63.toInt())
                cornerRadius = dp(22).toFloat()
            }
            clipToOutline = true            // glows stay inside the rounded rect
            alpha = 0f                      // entrance: fade + rise on first attach
            translationY = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) }
        }

        // ── 2. static glow BEHIND the content ──────────────────────────────
        // (a) two soft glow blobs: radial gradients fading to fully transparent,
        //     positioned as big circles hugging opposite corners (clipped by card)
        val glowLayer = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        fun glowBlob(sizeDp: Int, core: Int, gravity: Int, tx: Float, ty: Float): View {
            val s = dp(sizeDp)
            return View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(s, s, gravity)
                translationX = tx
                translationY = ty
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    setGradientRadius(s / 2f)
                    colors = intArrayOf(core, core and 0x00FFFFFF)
                }
            }
        }
        val glowAccent = if (Prefs.customAccent != 0) (accent(ctx) and 0x00FFFFFF) or 0x42000000
                         else 0x4222D3EE.toInt()     // soft aurora cyan by default
        val blobA = glowBlob(190, 0x38FFFFFF, Gravity.TOP or Gravity.START,
            -dp(58).toFloat(), -dp(82).toFloat())     // white 22%, top-left
        val blobB = glowBlob(220, glowAccent, Gravity.BOTTOM or Gravity.END,
            dp(54).toFloat(), dp(98).toFloat())       // cyan/accent 26%, bottom-right
        blobA.alpha = 0.62f
        blobB.alpha = 0.60f
        glowLayer.addView(blobA)
        glowLayer.addView(blobB)

        // ── 3. frosted content on top of the light ────────────────────────────
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        // icon in a frosted circular chip (34dp, white-glass fill, mint glyph)
        content.addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0x3DFFFFFF, 0x1FFFFFFF)
            }
            addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                imageTintList = ColorStateList.valueOf(0xFFA7F3D0.toInt())
            })
        })
        // title + body
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = title
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFF5F3FF.toInt())
            })
            addView(TextView(ctx).apply {
                text = body
                textSize = 13f
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextColor(0xFFC7C3E5.toInt())
                setPadding(0, dp(3), 0, 0)
            })
        })
        // frosted close pill (34dp, white ripple, fade+slide dismissal)
        content.addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(10) }
            isClickable = true
            isFocusable = true
            contentDescription = "close"
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1FFFFFFF) }, null)
            setOnClickListener {
                Prefs.dismissHint(key)
                card.animate().alpha(0f).translationX(dp(24).toFloat()).setDuration(180)
                    .withEndAction { (card.parent as? ViewGroup)?.removeView(card) }
                    .start()
            }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
                imageTintList = ColorStateList.valueOf(0xB3FFFFFF.toInt())
            })
        })

        card.addView(glowLayer)
        card.addView(content)

        // ── 4. lifecycle: entrance on first attach (v1.7.14 — no continuous animators)
        //        the entrance anim is cancelled on detach — leak-safe ───────────────────
        var entered = false
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!entered) {
                    entered = true
                    v.animate().alpha(1f).translationY(0f).setDuration(260)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            override fun onViewDetachedFromWindow(v: View) {
                v.animate().cancel()
            }
        })
        return card
    }
}
