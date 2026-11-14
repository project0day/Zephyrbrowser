package com.zephyr.browser.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/**
 * v1.7.91: colour for the tasks widget.
 *
 * Only a colour to choose here — unlike the search widget there is no engine —
 * so the screen is the preview, the swatches and the button, in that order.
 */
class TasksWidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var theme = WidgetThemes.DEFAULT
    private var isPill = false

    private var previewCard: LinearLayout? = null
    private val previewText = ArrayList<TextView>()
    private val previewIcons = ArrayList<ImageView>()
    private val swatches = HashMap<String, View>()

    public override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        setTheme(Prefs.themeStyle())
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // v1.7.93: this screen now serves both task widgets. Which one is asking
        // is read from the widget itself rather than passed in, because the
        // system launches a configure activity with nothing but the widget id.
        isPill = try {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
                ?.provider?.className?.contains("TaskPill") == true
        } catch (_: Throwable) { false }
        theme = if (isPill) TaskPillWidgetProvider.themeFor(this, appWidgetId)
                else TasksWidgetProvider.themeFor(this, appWidgetId)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        col.addView(buildPreview())
        col.addView(TextView(this).apply {
            setText(R.string.wg_theme_pick)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(12))
        })
        col.addView(buildSwatches())

        root.addView(ScrollView(this).apply { addView(col) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(doneButton())
        setContentView(root)
        repaint()
    }

    /** A stand-in card, built from the same pieces the widget uses. */
    private fun buildPreview(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@TasksWidgetConfigActivity).apply {
                setImageResource(R.drawable.ic_list)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                previewIcons.add(this)
            })
            addView(TextView(this@TasksWidgetConfigActivity).apply {
                setText(R.string.tw_title)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, 0, 0)
                previewText.add(this)
            })
        })
        for (sample in intArrayOf(R.string.tw_sample1, R.string.tw_sample2)) {
            card.addView(TextView(this).apply {
                setText(sample)
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
                previewText.add(this)
            })
        }
        previewCard = card
        return card
    }

    private fun repaint() {
        previewCard?.setBackgroundResource(theme.background)
        for (t in previewText) t.setTextColor(theme.foreground)
        for (i in previewIcons) i.setColorFilter(theme.foreground)
        for ((id, dot) in swatches) {
            dot.background = swatchBg(WidgetThemes.byId(id), id == theme.id)
        }
    }

    private fun buildSwatches(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(18))
        }
        for (t in WidgetThemes.ALL) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isClickable = true
                val mask = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(0x1A767680)
                }
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x331A73E8), null, mask)
                setOnClickListener {
                    theme = t
                    val self = this@TasksWidgetConfigActivity
                    if (isPill) TaskPillWidgetProvider.setThemeFor(self, appWidgetId, t.id)
                    else TasksWidgetProvider.setThemeFor(self, appWidgetId, t.id)
                    repaint()
                }
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                background = swatchBg(t, t.id == theme.id)
            }
            swatches[t.id] = dot
            cell.addView(dot)
            cell.addView(TextView(this).apply {
                text = WidgetThemes.nameOf(this@TasksWidgetConfigActivity, t)
                textSize = 11f
                setPadding(0, dp(6), 0, 0)
            })
            row.addView(cell)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun doneButton(): View = TextView(this).apply {
        setText(R.string.wg_done)
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(0xFF1A73E8.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) }
        setOnClickListener {
            val self = this@TasksWidgetConfigActivity
            if (isPill) TaskPillWidgetProvider.updateWidget(self, appWidgetId)
            else TasksWidgetProvider.updateWidget(self, appWidgetId)
            setResult(RESULT_OK, Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }

    private fun swatchBg(t: WidgetThemes.Theme, isSelected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(t.swatch)
            if (isSelected) setStroke(dp(3), 0xFF1A73E8.toInt())
            else setStroke(dp(1), 0x66808080)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
