package com.zephyr.browser.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/** Programmatic settings-row framework (Chrome-style list rows). */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object SettingsUi {

    fun themedColor(ctx: Context, attr: Int, fallback: Int = 0xFF5F6368.toInt()): Int = try {
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, fallback)
        ta.recycle()
        c
    } catch (_: Exception) { fallback }

    fun ripple(ctx: Context): android.graphics.drawable.Drawable? = try {
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = ta.getDrawable(0)
        ta.recycle()
        d
    } catch (_: Exception) { null }

    // ==== v1.7.1: visible custom accent ====
    // Prefs.customAccent used to recolor ONLY the tab switcher and the theme-picker ring,
    // so picking an accent changed nothing the user could see in day-to-day UI. Now the
    // settings/panels chrome (row icons, icon chips, section headers, switches) follows it.
    // When customAccent == 0 this resolves to the theme's colorPrimary → NO-OP for defaults.

    /** Custom accent when set, otherwise the theme's colorPrimary. */
    fun accent(ctx: Context): Int = Prefs.accentOrDefault(themedColor(ctx, R.attr.colorPrimary))

    /** Icon-chip background: theme primaryContainer normally; 12% custom accent when overridden. */
    private fun accentContainer(ctx: Context): Int =
        if (Prefs.customAccent != 0) (accent(ctx) and 0x00FFFFFF) or 0x20000000
        else themedColor(ctx, R.attr.colorPrimaryContainer, 0x1F1A73E8.toInt())

    /** Perceived brightness 0..1 — thumb contrast on accent-colored controls. */
    private fun luminance(c: Int): Float =
        0.299f * ((c shr 16 and 0xFF) / 255f) +
        0.587f * ((c shr 8 and 0xFF) / 255f) +
        0.114f * ((c and 0xFF) / 255f)

    // ==== v1.7.1: soft surfaces ====

    /** Linear blend of two opaque colors ([ratio] = weight of [b]). */
    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val ar = a shr 16 and 0xFF; val ag = a shr 8 and 0xFF; val ab = a and 0xFF
        val br = b shr 16 and 0xFF; val bg = b shr 8 and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * ratio).toInt()
        val g = (ag + (bg - ag) * ratio).toInt()
        val bl = (ab + (bb - ab) * ratio).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Theme outline at [alpha]/255 — soft stroke/divider that adapts to all 18 themes. */
    private fun softOutline(ctx: Context, alpha: Int): Int =
        (themedColor(ctx, R.attr.colorOutline, 0xFF9AA0A6.toInt()) and 0x00FFFFFF) or (alpha shl 24)

    /**
     * v1.7.1 backdrop: 50/50 colorBackground × colorSurfaceVariant tint painted on the
     * scroll container so grouped cards (colorSurface) contrast against it. Derived from
     * theme attributes only → correct in every theme incl. Sepia/Sand/Dark/Midnight.
     */
    private fun backdropColor(ctx: Context): Int {
        val bg = themedColor(ctx, android.R.attr.colorBackground, 0xFFFFFFFF.toInt())
        val sv = themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt())
        return blend(bg, sv, 0.5f)
    }

    /** Screen scaffold: ScrollView + column with padding (scroll paints the v1.7.1 backdrop). */
    /** Tag put on every row built here; [autoGroup] looks for it. */
    const val ROW_TAG = "zephyr.settings.row"

    fun screen(ctx: Context): LinearLayout {
        val scroll = android.widget.ScrollView(ctx)
        scroll.setBackgroundColor(backdropColor(ctx))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // v1.7.1: horizontal padding moved to cards/labels (rows are full-bleed inside cards)
            setPadding(0, ctx.dp(10), 0, ctx.dp(28))
        }
        scroll.addView(col)
        col.tag = scroll
        return col
    }

    /** The ScrollView wrapped around [screen] result (screen().tag as ScrollView). */
    fun scrollOf(col: LinearLayout): android.widget.ScrollView = col.tag as android.widget.ScrollView

    /** Section header (small colored caps label). v1.7.1: follows the custom accent. */
    fun sectionHeader(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(accent(ctx))
        setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(8))
    }

    // =====================================================================
    // Explanatory text — v1.7.90
    //
    // There were three of these before, and they were the same idea written
    // three ways: alpha 0.7, alpha 0.75, and an explicit onSurfaceVariant, with
    // bottom padding of 4, 8 or 10 depending on who wrote the screen. Nobody
    // notices any one of them; everybody notices that the app is slightly
    // uneven. There is now one of each kind, and only these should be used.
    //
    //   note()   a quiet explanation under a group of settings
    //   banner() something the user must take in before acting
    //
    // The rule for choosing: a note explains, a banner warns. If the text has
    // no consequence attached, it is a note.
    // =====================================================================

    /**
     * The quiet explanation that sits under a [groupCard].
     *
     * Deliberately low-contrast: it is there for the person who stops to read,
     * and must not compete with the settings above it.
     */
    fun note(ctx: Context, text: CharSequence): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        setLineSpacing(ctx.dp(1).toFloat(), 1f)
        setTextColor(themedColor(ctx, R.attr.colorOnSurfaceVariant))
        setPadding(ctx.dp(20), ctx.dp(7), ctx.dp(20), ctx.dp(12))
    }

    fun note(ctx: Context, textRes: Int): TextView = note(ctx, ctx.getString(textRes))

    /**
     * A dialog heading that carries an explanation, for dialogs that also show
     * a list.
     *
     * v1.7.98 (user: «Температура, Лимит ответа, глубина истории — недоступны
     * для модификации»). An AlertDialog has ONE content slot: a message or a
     * list, never both. Calling `setMessage` alongside `setSingleChoiceItems`
     * therefore produced a dialog with the hint and no options at all — the
     * settings looked read-only. The same mistake had already been made once,
     * in the burn dialog, and fixed there the same way.
     *
     * The hint belongs above the list, so it goes into the title.
     */
    fun dialogTitle(ctx: Context, title: String, hint: String?): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(20), ctx.dp(24), ctx.dp(4))
            addView(TextView(ctx).apply {
                text = title
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themedColor(ctx, R.attr.colorOnSurface))
            })
            if (!hint.isNullOrBlank()) addView(TextView(ctx).apply {
                text = hint
                textSize = 12.5f
                setLineSpacing(ctx.dp(2).toFloat(), 1f)
                setTextColor(themedColor(ctx, R.attr.colorOnSurfaceVariant))
                setPadding(0, ctx.dp(8), 0, 0)
            })
        }

    /** How loudly a [banner] speaks. */
    enum class Tone { INFO, WARN, DANGER }

    /**
     * A framed callout for something that changes what the user should do:
     * a warning before a destructive action, a prerequisite they do not have,
     * a consequence that is not obvious from the switch itself.
     *
     * Used sparingly on purpose. A screen where everything is highlighted has
     * highlighted nothing, and the notes above carry most of the explaining.
     */
    fun banner(
        ctx: Context,
        tone: Tone,
        iconRes: Int,
        text: CharSequence
    ): LinearLayout {
        val accent = when (tone) {
            Tone.INFO -> accent(ctx)
            Tone.WARN -> 0xFFE8A33D.toInt()
            Tone.DANGER -> 0xFFE05252.toInt()
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(14).toFloat()
                // the tone's own colour at ~9% — readable in both themes
                setColor((accent and 0x00FFFFFF) or 0x17000000)
                setStroke(ctx.dp(1), (accent and 0x00FFFFFF) or 0x44000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(10)) }

            addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(accent)
                layoutParams = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20))
                    .apply { topMargin = ctx.dp(1); marginEnd = ctx.dp(12) }
            })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 12.5f
                setLineSpacing(ctx.dp(2).toFloat(), 1f)
                setTextColor(themedColor(ctx, R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    fun banner(ctx: Context, tone: Tone, iconRes: Int, textRes: Int): LinearLayout =
        banner(ctx, tone, iconRes, ctx.getString(textRes))

    /**
     * v1.7.1: small bold group label rendered ABOVE a [groupCard] (no background of its own).
     * Used on the main settings screen for the grouped-card layout.
     */
    fun groupLabel(ctx: Context, textRes: Int): TextView = TextView(ctx).apply {
        setText(textRes)
        textSize = 12f
        letterSpacing = 0.06f
        typeface = Typeface.DEFAULT_BOLD
        // colorOnSurfaceVariant at 80% opacity
        setTextColor((themedColor(ctx, R.attr.colorOnSurfaceVariant) and 0x00FFFFFF) or 0xCC000000.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(6))
        }
    }

    /**
     * v1.7.1: rounded card that groups 2–6 settings rows (user: "разбить настройки на
     * группы с закруглёнными карточками"). Rows are added full-bleed and separated with
     * [rowDivider]; the soft stroke keeps the card visible on the tinted backdrop in
     * every theme (light and dark).
     */
    fun groupCard(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ctx.dp(6), 0, ctx.dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(12), 0, ctx.dp(12), ctx.dp(14))
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(22).toFloat()
            setColor(themedColor(ctx, R.attr.colorSurface, 0xFFFFFFFF.toInt()))
            setStroke(ctx.dp(1).coerceAtLeast(1), softOutline(ctx, 0x59))
        }
        // clip row ripples to the rounded outline
        clipToOutline = true
    }

    /** v1.7.1: thin inset divider between rows inside a [groupCard] (aligned past the icon). */
    fun rowDivider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(softOutline(ctx, 0x8C))
        val h = (ctx.resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, h).apply {
            // 14dp row padding + 36dp icon + 14dp gap → aligns with row titles
            marginStart = ctx.dp(64)
        }
    }

    /** Clickable row: icon + title + value text (right) + chevron. */
    fun clickRow(ctx: Context, iconRes: Int?, title: String, value: String? = null, onClick: () -> Unit): LinearLayout {
        val row = baseRow(ctx, iconRes, title)
        if (value != null) {
            row.addView(TextView(ctx).apply {
                text = value
                textSize = 13f
                maxLines = 1
                gravity = Gravity.END
                setTextColor(themedColor(ctx, R.attr.colorOnSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { marginStart = ctx.dp(6) }
            imageTintList = ColorStateList.valueOf(themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
        row.setOnClickListener { onClick() }
        return row
    }

    /** Switch row: whole row toggles (switch is passive). */
    fun switchRow(ctx: Context, iconRes: Int?, title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = baseRow(ctx, iconRes, title, summary)
        val sw = Switch(ctx).apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
        }
        // v1.7.1: custom accent visibly recolors the switches (skipped entirely for the
        // default accent → stock Material look unchanged for default users)
        if (Prefs.customAccent != 0) {
            val on = accent(ctx)
            sw.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(on, softOutline(ctx, 0x99)))
            val thumbOn = if (luminance(on) > 0.6f) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt()
            sw.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(thumbOn, 0xFFFFFFFF.toInt()))
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle(); onChange(sw.isChecked) }
        return row
    }

    private fun baseRow(ctx: Context, iconRes: Int?, title: String, summary: String? = null): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(13), ctx.dp(14), ctx.dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            foreground = ripple(ctx)
            // v1.7.29: marks this view as a settings ROW so autoGroup() can fold
            // loose rows into rounded group cards on every screen.
            tag = ROW_TAG
        }
        if (iconRes != null) {
            val iconContainer = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accentContainer(ctx))
                }
                layoutParams = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply {
                    marginEnd = ctx.dp(14)
                }
                addView(ImageView(ctx).apply {
                    setImageResource(iconRes)
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18))
                    imageTintList = ColorStateList.valueOf(accent(ctx))
                })
            }
            row.addView(iconContainer)
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                maxLines = 2
                setTextColor(themedColor(ctx, R.attr.colorOnSurface))
            })
            if (summary != null) addView(TextView(ctx).apply {
                text = summary
                textSize = 12.5f
                maxLines = 3
                setLineSpacing(ctx.dp(1).toFloat(), 1f)
                setTextColor(themedColor(ctx, R.attr.colorOnSurfaceVariant))
                // v1.7.8 (user: «в резервной копии пункт Создать почти приклеен с
                // надписью файл .json»): titles and summaries were visually glued —
                // 2dp → 4dp breathing room + line spacing on every settings row.
                setPadding(0, ctx.dp(4), 0, 0)
            })
        })
        return row
    }

    /** Gradient accent header card (nice touch: gradient surfaces per user request). */
    fun gradientCard(ctx: Context, title: String, subtitle: String, colors: IntArray, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
            }
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                this.colors = colors
                cornerRadius = ctx.dp(20).toFloat()
            }
            isClickable = true
            foreground = ripple(ctx)
            setOnClickListener { onClick() }
        }
        card.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
        })
        card.addView(TextView(ctx).apply {
            text = subtitle
            textSize = 12.5f
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(0, ctx.dp(2), 0, 0)
        })
        return card
    }

    // ==== v1.7.7: shared premium-surface helpers (Files app, new sheets) ====

    fun color(ctx: Context, attr: Int): Int = themedColor(ctx, attr)

    /** Soft surface tile: theme surface + hairline stroke (theme-aware in all 18 themes). */
    fun rounded(ctx: Context, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp.toFloat()
        setColor(themedColor(ctx, R.attr.colorSurface, 0xFFFFFFFF.toInt()))
        setStroke(1, softOutline(ctx, 0x59))
    }

    /** Accent-filled pill/button. */
    fun roundedFill(ctx: Context, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp.toFloat()
        setColor(accent(ctx))
    }

    /** Attribute-colored filled tile (defaults to colorPrimary). */
    fun roundedFillAttr(ctx: Context, radiusDp: Int, attr: Int = R.attr.colorPrimary): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp.toFloat()
        setColor(themedColor(ctx, attr, accent(ctx)))
    }

    /** Accent-filled circle (FAB-style). */
    fun circleFill(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(accent(ctx))
    }

    /** Common extension → mime map for opening files without a resolver type. */
    fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt", "md", "log", "csv" -> "text/plain"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "xml" -> "text/xml"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        else -> "*/*"
    }

    // ==================================================================
    // v1.7.29: ONE consistent look for every settings screen
    // ==================================================================

    /**
     * Folds loose rows into rounded group cards.
     *
     * Only the AI screen was built with [groupCard]s; the others dropped their
     * rows straight into the column, so they looked like a flat list next to it
     * (user: «окно настроек ИИ реализовано красиво по группам — сделай все
     * другие окна в таком же виде, с полукруглыми краями»). Rewriting fourteen
     * fragments by hand would be fourteen chances to break something, so the
     * grouping happens structurally instead: every run of consecutive [baseRow]
     * children between two section headers is re-parented into one card with
     * hairline dividers between the rows.
     *
     * Anything that is not a row — previews, galleries, hint banners, headers —
     * is left exactly where it was. Safe to call repeatedly.
     */
    fun autoGroup(col: LinearLayout?) {
        val c = col ?: return
        try {
            val ctx = c.context
            val children = ArrayList<View>(c.childCount)
            for (i in 0 until c.childCount) children.add(c.getChildAt(i))

            // nothing to do when the screen already groups its own rows
            if (children.none { it.tag == ROW_TAG }) return

            c.removeAllViews()
            var run: MutableList<View>? = null

            fun flush() {
                val rows = run ?: return
                run = null
                if (rows.isEmpty()) return
                val card = groupCard(ctx)
                rows.forEachIndexed { idx, r ->
                    if (idx > 0) card.addView(rowDivider(ctx))
                    r.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    card.addView(r)
                }
                c.addView(card)
            }

            for (v in children) {
                if (v.tag == ROW_TAG) {
                    if (run == null) run = ArrayList()
                    run!!.add(v)
                } else {
                    flush()
                    c.addView(v)
                }
            }
            flush()
        } catch (_: Throwable) { /* never let cosmetics break a screen */ }
    }

    /** Depth-first search for the column produced by [screen] (col.tag === its ScrollView). */
    fun findColumn(root: View?): LinearLayout? {
        if (root == null) return null
        if (root is LinearLayout && root.tag is android.widget.ScrollView &&
            root.tag === root.parent
        ) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findColumn(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * Clickable row with the description UNDER the title instead of squeezed
     * into the right-hand value slot. v1.7.29 (user: «первый пункт имеет какой-то
     * дефект, какие-то символы сверху снизу и посередине слово»): a long summary
     * passed as `value` was rendered as a one-line right-aligned label and got
     * crushed into a column a few characters wide.
     */
    fun clickRowSummary(
        ctx: Context,
        iconRes: Int?,
        title: String,
        summary: String?,
        onClick: () -> Unit
    ): LinearLayout {
        val row = baseRow(ctx, iconRes, title, summary)
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18))
                .apply { marginStart = ctx.dp(6) }
            imageTintList = ColorStateList.valueOf(themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
        row.setOnClickListener { onClick() }
        return row
    }
}
