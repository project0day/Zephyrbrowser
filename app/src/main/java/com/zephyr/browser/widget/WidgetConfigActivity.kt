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
import com.zephyr.browser.SearchEngines

/**
 * Widget configuration: the search engine the widget uses, and (v1.7.82) its
 * colour.
 *
 * Before 1.7.82 tapping an engine saved and closed the screen immediately.
 * That cannot work once there is a second thing to choose, so selection and
 * confirmation are now separate: taps change the preview at the top, and the
 * button at the bottom is what finishes. The preview matters more here than it
 * would elsewhere — on first setup the widget is not on the home screen yet, so
 * without it the colour would be picked blind.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selected = "google"
    private var theme = WidgetThemes.DEFAULT

    private var previewPill: LinearLayout? = null
    private var previewText: TextView? = null
    private val previewIcons = ArrayList<ImageView>()
    private val engineDots = HashMap<String, View>()
    private val themeDots = HashMap<String, View>()

    public override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        setTheme(Prefs.themeStyle())
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        selected = SearchWidgetProvider.engineFor(this, appWidgetId)
        theme = SearchWidgetProvider.themeFor(this, appWidgetId)

        val ctx = this
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        col.addView(buildPreview())
        col.addView(heading(R.string.wg_theme_pick))
        col.addView(buildThemeStrip())
        col.addView(heading(R.string.wg_engine_pick))
        for (e in SearchEngines.builtIn) col.addView(engineRow(e.id))

        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildDoneButton())
        setContentView(root)
        refresh()
    }

    // ---------------- preview ----------------

    /** A stand-in for the real widget, drawn with the same pieces. */
    private fun buildPreview(): View {
        val ctx = this
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { bottomMargin = dp(20) }
        }
        pill.addView(icon(R.drawable.ic_widget_search, 20))
        val label = TextView(ctx).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        }
        pill.addView(label)
        pill.addView(icon(R.drawable.ic_widget_camera, 40))
        pill.addView(icon(R.drawable.ic_widget_mic, 40))
        pill.addView(icon(R.drawable.ic_widget_ai, 40))
        previewPill = pill
        previewText = label
        return pill
    }

    private fun icon(res: Int, sizeDp: Int): ImageView {
        val v = ImageView(this).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (sizeDp >= 40) setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
        previewIcons.add(v)
        return v
    }

    private fun refresh() {
        previewPill?.setBackgroundResource(theme.background)
        previewText?.setTextColor(theme.foreground)
        for (v in previewIcons) v.setColorFilter(theme.foreground)
        val engine = SearchEngines.byId(selected)
        val name = try { getString(engine.nameRes) } catch (_: Exception) { "" }
        previewText?.text = getString(R.string.wg_search_hint, name)
        for ((id, dot) in engineDots) dot.background = dotBg(id == selected)
        for ((id, dot) in themeDots) dot.background = swatchBg(WidgetThemes.byId(id), id == theme.id)
    }

    // ---------------- pickers ----------------

    private fun heading(res: Int) = TextView(this).apply {
        setText(res)
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun buildThemeStrip(): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(18))
        }
        for (t in WidgetThemes.ALL) {
            val cell = LinearLayout(ctx).apply {
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
                    SearchWidgetProvider.setThemeFor(ctx, appWidgetId, t.id)
                    refresh()
                }
            }
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                background = swatchBg(t, t.id == theme.id)
            }
            themeDots[t.id] = dot
            cell.addView(dot)
            cell.addView(TextView(ctx).apply {
                text = WidgetThemes.nameOf(ctx, t)
                textSize = 11f
                setPadding(0, dp(6), 0, 0)
            })
            row.addView(cell)
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun engineRow(id: String): View {
        val ctx = this
        val engine = SearchEngines.byId(id)
        val name = try { getString(engine.nameRes) } catch (_: Exception) { id }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            minimumHeight = dp(52)
            isClickable = true
            val mask = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0x1A767680)
            }
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x331A73E8), null, mask)
            setOnClickListener {
                selected = id
                getSharedPreferences("zephyr_widget", MODE_PRIVATE).edit()
                    .putString("engine_$appWidgetId", id).apply()
                refresh()
            }
        }
        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            background = dotBg(id == selected)
        }
        engineDots[id] = dot
        row.addView(dot)
        row.addView(TextView(ctx).apply {
            text = name
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(14) }
        })
        row.addView(TextView(ctx).apply {
            text = engine.urlTemplate.substringAfter("://").substringBefore('/')
            textSize = 12f
        })
        return row
    }

    private fun buildDoneButton(): View {
        val ctx = this
        return TextView(ctx).apply {
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
                SearchWidgetProvider.updateWidget(ctx, appWidgetId)
                setResult(RESULT_OK, Intent().putExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            }
        }
    }

    // ---------------- swatches ----------------

    private fun dotBg(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (selected) {
            setColor(0xFF1A73E8.toInt())
        } else {
            setColor(0x00000000)
            setStroke(dp(2), 0xFF9AA0A6.toInt())
        }
    }

    /** The theme's own colour, ringed when it is the chosen one. */
    private fun swatchBg(t: WidgetThemes.Theme, isSelected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(t.swatch)
            // a light swatch on a light sheet needs an outline of its own, or it
            // disappears into the background entirely
            if (isSelected) setStroke(dp(3), 0xFF1A73E8.toInt())
            else setStroke(dp(1), 0x66808080)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
