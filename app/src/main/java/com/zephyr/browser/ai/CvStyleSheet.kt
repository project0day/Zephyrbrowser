package com.zephyr.browser.ai

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.zephyr.browser.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.7.68 (user: «ИИ вызывает и открывает sheets меню, где говорит: выбирай
 * шаблон какой хочешь, или спрашивает какой цвет»).
 *
 * A good idea for a reason worth naming: choosing a look is a matter of taste,
 * and taste is the one thing the model cannot read from a prompt. Asking in
 * words costs a whole round trip and still ends in "ну сделай красиво". A
 * picker answers it in two taps.
 *
 * Each template is shown as a tiny drawn preview rather than a word — "sidebar"
 * means nothing until you see the coloured column.
 */
object CvStyleSheet {

    class Choice(val template: String, val accent: String)

    // v1.7.88: name and description were Russian literals, so the style picker
    // was in Russian whatever the app language. These describe the INTERFACE
    // choice, not the document, so they are plain resources.
    private val TEMPLATES = listOf(
        Triple("sidebar", R.string.cvs_sidebar, R.string.cvs_sidebar_sub),
        Triple("classic", R.string.cvs_classic, R.string.cvs_classic_sub),
        Triple("minimal", R.string.cvs_minimal, R.string.cvs_minimal_sub)
    )

    private val COLORS = listOf(
        "blue" to 0xFF1F4E79.toInt(),
        "teal" to 0xFF0F7B6C.toInt(),
        "green" to 0xFF1F6F43.toInt(),
        "purple" to 0xFF4B3B8F.toInt(),
        "red" to 0xFF8A1C1C.toInt(),
        "orange" to 0xFFB45309.toInt(),
        "graphite" to 0xFF2F3437.toInt()
    )

    /**
     * Ask, and block until answered. Returns null when the user backs out —
     * which the caller treats as "decide for me", not as a failure.
     */
    fun ask(activity: Activity): Choice? {
        var result: Choice? = null
        var template = "sidebar"
        var accent = "blue"
        val latch = CountDownLatch(1)

        activity.runOnUiThread {
            try {
                val ctx = activity
                val d = { v: Int -> AiUi.dp(ctx, v) }
                // v1.7.71 (user: «кнопки снизу чуть опустить — тогда выбор
                // шаблонов не придётся скролить»). Taller sheet, tighter
                // cards: all three layouts and the colours fit at once.
                val (dialog, root) = AiUi.sheet(ctx, 0.88f)

                root.addView(AiUi.sheetTitle(ctx, ctx.getString(R.string.cv_pick_title)))
                root.addView(TextView(ctx).apply {
                    setText(R.string.cv_pick_sub)
                    textSize = 12.5f
                    setTextColor(AiUi.onVariant(ctx))
                    setPadding(d(20), 0, d(20), d(8))
                })
                root.addView(AiUi.divider(ctx))

                val (scroll, col) = AiUi.body(ctx)
                root.addView(scroll)

                val cards = ArrayList<LinearLayout>()
                val swatches = ArrayList<TextView>()

                fun repaint() {
                    for ((i, c) in cards.withIndex()) {
                        val on = TEMPLATES[i].first == template
                        (c.background as? GradientDrawable)?.setStroke(
                            d(if (on) 2 else 1),
                            if (on) AiUi.ACCENT else AiUi.surfaceVar(ctx)
                        )
                        c.alpha = if (on) 1f else 0.75f
                    }
                    for ((i, s) in swatches.withIndex()) {
                        s.text = if (COLORS[i].first == accent) "✓" else ""
                    }
                }

                for ((idx, t) in TEMPLATES.withIndex()) {
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(d(11), d(8), d(11), d(8))
                        background = GradientDrawable().apply {
                            cornerRadius = d(16).toFloat()
                            setColor(AiUi.surfaceVar(ctx))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = d(7) }
                        isClickable = true
                        setOnClickListener { template = t.first; repaint() }
                    }
                    card.addView(preview(ctx, t.first))
                    card.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        ).apply { marginStart = d(12) }
                        addView(TextView(ctx).apply {
                            text = ctx.getString(t.second)
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(AiUi.onSurface(ctx))
                        })
                        addView(TextView(ctx).apply {
                            text = ctx.getString(t.third)
                            textSize = 11.5f
                            setTextColor(AiUi.onVariant(ctx))
                            setPadding(0, d(2), 0, 0)
                        })
                    })
                    cards.add(card)
                    col.addView(card)
                }

                col.addView(TextView(ctx).apply {
                    setText(R.string.cv_pick_color)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(AiUi.onVariant(ctx))
                    setPadding(d(4), d(6), d(4), d(6))
                })
                col.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    for ((cname, color) in COLORS) {
                        val dot = TextView(ctx).apply {
                            text = ""
                            textSize = 15f
                            gravity = Gravity.CENTER
                            setTextColor(0xFFFFFFFF.toInt())
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(color)
                            }
                            layoutParams = LinearLayout.LayoutParams(d(34), d(34))
                                .apply { marginEnd = d(9) }
                            isClickable = true
                            setOnClickListener { accent = cname; repaint() }
                        }
                        swatches.add(dot)
                        addView(dot)
                    }
                    // v1.7.69 (user: «добавил бы выбор своего цвета или код
                    // цвета»). The palette covers the common answer; this
                    // covers the one a company brand book already decided.
                    addView(TextView(ctx).apply {
                        text = "+"
                        textSize = 17f
                        gravity = Gravity.CENTER
                        setTextColor(AiUi.onSurface(ctx))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(AiUi.surfaceVar(ctx))
                            setStroke(AiUi.dp(ctx, 1), AiUi.onVariant(ctx))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            AiUi.dp(ctx, 34), AiUi.dp(ctx, 34)
                        )
                        isClickable = true
                        setOnClickListener {
                            val field = android.widget.EditText(ctx).apply {
                                hint = "#1F4E79"
                                setText(if (accent.startsWith("#")) accent else "")
                                setSingleLine(true)
                            }
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                                .setTitle(R.string.cv_pick_custom)
                                .setView(LinearLayout(ctx).apply {
                                    setPadding(d(22), d(8), d(22), d(8))
                                    addView(field, LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT))
                                })
                                .setPositiveButton(R.string.ok) { _, _ ->
                                    val raw = field.text.toString().trim()
                                    val hex = if (raw.startsWith("#")) raw else "#$raw"
                                    // a malformed code would silently become
                                    // the default, so it is checked and refused
                                    if (Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$").matches(hex)) {
                                        accent = hex
                                        (background as? GradientDrawable)?.setColor(
                                            android.graphics.Color.parseColor(hex)
                                        )
                                        text = "✓"
                                        setTextColor(0xFFFFFFFF.toInt())
                                        repaint()
                                    } else {
                                        android.widget.Toast.makeText(
                                            ctx, R.string.cv_pick_bad_color,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                    })
                })

                // actions
                root.addView(AiUi.divider(ctx))
                root.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(d(14), d(10), d(14), d(14) + AiUi.navBarHeight(ctx))
                    addView(button(ctx, ctx.getString(R.string.cv_pick_auto), false) {
                        result = null
                        dialog.dismiss()
                    })
                    addView(button(ctx, ctx.getString(R.string.cv_pick_go), true) {
                        result = Choice(template, accent)
                        dialog.dismiss()
                    })
                })

                repaint()
                dialog.setOnDismissListener { latch.countDown() }
                dialog.show()
            } catch (_: Throwable) {
                latch.countDown()
            }
        }

        latch.await(180, TimeUnit.SECONDS)
        return result
    }

    /** A 44×58 sketch of the layout — faster to understand than its name. */
    private fun preview(ctx: android.content.Context, template: String): android.view.View {
        val d = { v: Int -> AiUi.dp(ctx, v) }
        val page = LinearLayout(ctx).apply {
            orientation = if (template == "sidebar") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(d(44), d(58))
            background = GradientDrawable().apply {
                cornerRadius = d(4).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(1, 0x33000000)
            }
            clipToOutline = true
        }
        fun bar(w: Int, h: Int, color: Int, top: Int = 0, start: Int = 0) = android.view.View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(d(w), d(h)).apply {
                topMargin = d(top); marginStart = d(start)
            }
            setBackgroundColor(color)
        }
        when (template) {
            "sidebar" -> {
                page.addView(android.view.View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(d(15), LinearLayout.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(AiUi.ACCENT)
                })
                page.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d(3), d(4), d(3), 0)
                    addView(bar(20, 3, 0xFF444444.toInt()))
                    addView(bar(24, 2, 0xFFBBBBBB.toInt(), 3))
                    addView(bar(22, 2, 0xFFBBBBBB.toInt(), 2))
                    addView(bar(18, 2, 0xFFBBBBBB.toInt(), 2))
                })
            }
            "classic" -> {
                page.setPadding(d(4), d(5), d(4), 0)
                page.addView(bar(26, 3, 0xFF444444.toInt()))
                page.addView(bar(36, 1, AiUi.ACCENT, 3))
                page.addView(bar(30, 2, 0xFFBBBBBB.toInt(), 4))
                page.addView(bar(33, 2, 0xFFBBBBBB.toInt(), 2))
                page.addView(bar(28, 2, 0xFFBBBBBB.toInt(), 2))
            }
            else -> {
                page.setPadding(d(5), d(7), d(4), 0)
                page.addView(bar(28, 4, 0xFF333333.toInt()))
                page.addView(bar(14, 2, AiUi.ACCENT, 3))
                page.addView(bar(32, 2, 0xFFCCCCCC.toInt(), 7))
                page.addView(bar(30, 2, 0xFFCCCCCC.toInt(), 3))
            }
        }
        return page
    }

    private fun button(
        ctx: android.content.Context, label: String, filled: Boolean, onTap: () -> Unit
    ) = TextView(ctx).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(AiUi.dp(ctx, 10), AiUi.dp(ctx, 12), AiUi.dp(ctx, 10), AiUi.dp(ctx, 12))
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else AiUi.onVariant(ctx))
        background = GradientDrawable().apply {
            cornerRadius = AiUi.dp(ctx, 14).toFloat()
            setColor(if (filled) AiUi.ACCENT else AiUi.surfaceVar(ctx))
        }
        isClickable = true
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = AiUi.dp(ctx, 4); marginEnd = AiUi.dp(ctx, 4) }
        setOnClickListener { onTap() }
    }
}
