package com.zephyr.browser.settings

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.BurnOptions
import com.zephyr.browser.AdBlockManager
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.SearchEngines
import com.zephyr.browser.VaultCrypto
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.panels.PanelUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.SecureRandom

// =====================================================================
// General (v1.7.2: the Advanced screen was merged INTO this fragment — user:
// «Дополнительно вообще лучше влить в Основное»; its rows live below under
// the «Дополнительно» section header)
// =====================================================================

class GeneralPrefsFragment : Fragment() {

    /** v1.7.2: IO scope for the reset action (moved over from AdvancedPrefsFragment). */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_general)
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // search engine
        fun engineValue(): String {
            val e = Prefs.currentEngine()
            return try { getString(e.nameRes) } catch (_: Exception) { "" }
        }
        val engineRow = SettingsUi.clickRow(ctx, R.drawable.ic_search, getString(R.string.set_search_engine), engineValue()) {
            val engines = SearchEngines.builtIn
            val names = engines.map { getString(it.nameRes) }.toTypedArray()
            val checked = engines.indexOfFirst { it.id == Prefs.engineId }.let { if (it < 0) 0 else it }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.set_search_engine)
                .setSingleChoiceItems(names, checked) { d, which ->
                    Prefs.setEngine(engines[which].id)
                    d.dismiss()
                    (root.getChildAt(1) as? LinearLayout)?.removeAllViews()
                }
                .setNeutralButton(R.string.engine_custom) { _, _ -> customEngineDialog(ctx) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        col.addView(engineRow)

        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_globe, getString(R.string.set_custom_engine)) {
            customEngineDialog(ctx)
        })

        // language — applies IMMEDIATELY via per-app locales (no restart)
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_translate, getString(R.string.set_language), languageValue()) {
            val tags = arrayOf("system", "ru", "en", "fr")
            val names = arrayOf(getString(R.string.lang_system), "Русский", "English", "Français")
            val checked = tags.indexOf(Prefs.language).let { if (it < 0) 0 else it }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.lang_title)
                .setSingleChoiceItems(names, checked) { d, which ->
                    Prefs.setLanguage(tags[which])
                    // instant application for ALL activities (pre-13 backport via appcompat)
                    try {
                        AppCompatDelegate.setApplicationLocales(
                            if (tags[which] == "system") LocaleListCompat.getEmptyLocaleList()
                            else LocaleListCompat.forLanguageTags(tags[which])
                        )
                    } catch (_: Exception) {}
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })

        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_replay, getString(R.string.set_restore_tabs), getString(R.string.h_restore_tabs),
            Prefs.restoreTabs) { Prefs.setRestoreTabs(it) })

        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_desktop, getString(R.string.set_pc_default), getString(R.string.h_pc_default),
            Prefs.desktopDefault) { Prefs.setDesktopDefault(it) })

        // open in apps
        fun oiaValue(): String = when (Prefs.openInApps) {
            1 -> getString(R.string.open_in_apps_never)
            2 -> getString(R.string.open_in_apps_always)
            else -> getString(R.string.open_in_apps_ask)
        }
        val oiaRow = SettingsUi.clickRow(ctx, R.drawable.ic_external, getString(R.string.set_open_in_apps), oiaValue()) {
            val modes = arrayOf(getString(R.string.open_in_apps_ask), getString(R.string.open_in_apps_never), getString(R.string.open_in_apps_always))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.set_open_in_apps)
                .setSingleChoiceItems(modes, Prefs.openInApps) { d, which ->
                    Prefs.setOpenInApps(which)
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        col.addView(oiaRow)

        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_search, getString(R.string.set_suggestions), getString(R.string.h_suggestions),
            Prefs.searchSuggestions) { Prefs.setSearchSuggestions(it) })

        // =====================================================
        // v1.7.2: «Дополнительно» merged into «Основное» — rows moved
        // verbatim from the removed AdvancedPrefsFragment screen.
        // =====================================================
        col.addView(SettingsUi.rowDivider(ctx))
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_advanced_section)))

        // ---- user agent mode ----
        fun uaValue(): String = when (Prefs.uaMode) {
            1 -> getString(R.string.ua_mobile)
            2 -> getString(R.string.ua_desktop)
            3 -> Prefs.customUa.ifEmpty { getString(R.string.ua_custom) }
            else -> getString(R.string.ua_system)
        }
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_desktop, getString(R.string.set_user_agent), uaValue()) {
            val names = arrayOf(getString(R.string.ua_system), getString(R.string.ua_mobile),
                getString(R.string.ua_desktop), getString(R.string.ua_custom))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.set_user_agent)
                .setSingleChoiceItems(names, Prefs.uaMode) { d, which ->
                    Prefs.setUaMode(which)
                    d.dismiss()
                    if (which == 3) customUaDialog(ctx)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })

        // custom UA editor
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_edit, getString(R.string.ua_custom),
            Prefs.customUa.ifEmpty { null }) {
            customUaDialog(ctx)
        })

        // ---- Do Not Track ----
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_shield, getString(R.string.set_do_not_track),
            getString(R.string.set_do_not_track_sum), Prefs.doNotTrack) { Prefs.setDoNotTrack(it) })

        // ---- v1.7.5: GESTURES (edge-swipe navigation + v1.7.6 shortcuts) ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_gestures)))
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_replay, getString(R.string.set_edge_gestures),
            getString(R.string.set_edge_gestures_sum), Prefs.edgeGestures) {
            Prefs.setEdgeGestures(it)
        })
        // v1.7.6: pull-to-refresh + gesture shortcuts (each can be switched off)
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_reload, getString(R.string.set_pull_refresh), getString(R.string.h_pull_refresh),
            Prefs.pullToRefresh) { Prefs.setPullToRefresh(it) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_arrow_up, getString(R.string.set_omnibox_swipe), getString(R.string.h_omnibox_swipe),
            Prefs.omniboxSwipeRefresh) { Prefs.setOmniboxSwipeRefresh(it) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_reader, getString(R.string.set_double_tap), getString(R.string.h_double_tap),
            Prefs.doubleTapScroll) { Prefs.setDoubleTapScroll(it) })

        // ---- reset ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_reset_all)))
        col.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_replay, getString(R.string.set_reset_all), getString(R.string.h_reset_all)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.set_reset_all)
                .setMessage(R.string.set_reset_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    io.launch {
                        try {
                            ctx.dataStorePrefsReset()
                        } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, R.string.set_reset_done, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })

        return root
    }

    private fun languageValue(): String = when (Prefs.language) {
        "ru" -> "Русский"; "en" -> "English"; "fr" -> "Français"; else -> getString(R.string.lang_system)
    }

    private fun customEngineDialog(ctx: android.content.Context) {
        val name = PanelUi.dialogField(ctx, getString(R.string.set_custom_engine_name), Prefs.customEngineName)
        val url = PanelUi.dialogField(ctx, getString(R.string.set_custom_engine_url), Prefs.customEngineUrl)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            addView(PanelUi.fieldWrap(name))
            addView(PanelUi.fieldWrap(url))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.set_custom_engine)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isNotEmpty() && u.contains("%s")) {
                    Prefs.setCustomEngine(n, u)
                    Prefs.setEngine("custom")
                } else {
                    Toast.makeText(ctx, R.string.set_custom_engine_url, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** v1.7.2: moved from AdvancedPrefsFragment — custom User-Agent editor dialog. */
    private fun customUaDialog(ctx: android.content.Context) {
        val input = PanelUi.dialogField(ctx, getString(R.string.ua_custom), Prefs.customUa).apply {
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.set_user_agent)
            .setView(PanelUi.fieldWrap(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val ua = input.text.toString().trim()
                if (ua.isNotEmpty()) {
                    Prefs.setCustomUa(ua)
                    Prefs.setUaMode(3)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

// =====================================================================
// Appearance (v1.4.1: theme GALLERY with live previews + page appearance live-apply)
// =====================================================================

class AppearancePrefsFragment : Fragment() {

    /** Theme catalogue: id → (nameRes, mockup bg, mockup pill, accent). v1.5.0: ids 12–17 added. */
    private val themes = listOf(
        Triple(0, R.string.theme_system, Triple(0xFFFFFFFF.toInt(), 0xFFF1F3F4.toInt(), 0xFF1A73E8.toInt())),
        Triple(1, R.string.theme_dark, Triple(0xFF212121.toInt(), 0xFF2E2E2E.toInt(), 0xFF8AB4F8.toInt())),
        Triple(2, R.string.theme_amoled, Triple(0xFF000000.toInt(), 0xFF141414.toInt(), 0xFF8AB4F8.toInt())),
        Triple(3, R.string.theme_sepia, Triple(0xFFF1E4D0.toInt(), 0xFFE8D8BC.toInt(), 0xFF7A4A2E.toInt())),
        Triple(4, R.string.theme_slate, Triple(0xFF1C1F22.toInt(), 0xFF2E3338.toInt(), 0xFF7CC4B8.toInt())),
        Triple(5, R.string.theme_rose, Triple(0xFFFDF5F6.toInt(), 0xFFF6E4E8.toInt(), 0xFFC25573.toInt())),
        Triple(6, R.string.theme_forest, Triple(0xFF141A16.toInt(), 0xFF26302A.toInt(), 0xFF8FD6A0.toInt())),
        Triple(7, R.string.theme_ocean, Triple(0xFF0F1D1E.toInt(), 0xFF1F3436.toInt(), 0xFF5FD3C4.toInt())),
        Triple(8, R.string.theme_plum, Triple(0xFF191223.toInt(), 0xFF2C2143.toInt(), 0xFFB79BF0.toInt())),
        Triple(9, R.string.theme_sand, Triple(0xFFF6F0DA.toInt(), 0xFFEFE7C8.toInt(), 0xFF94741A.toInt())),
        Triple(10, R.string.theme_midnight, Triple(0xFF101318.toInt(), 0xFF212734.toInt(), 0xFF9DB8E8.toInt())),
        Triple(11, R.string.theme_porcelain, Triple(0xFFFAFAFC.toInt(), 0xFFEEF0F4.toInt(), 0xFF3E4A5C.toInt())),
        Triple(12, R.string.theme_aurora, Triple(0xFF0B1620.toInt(), 0xFF1B2F3D.toInt(), 0xFF4FD1C5.toInt())),
        Triple(13, R.string.theme_crimson, Triple(0xFF1D1013.toInt(), 0xFF3A1D24.toInt(), 0xFFEF6E6E.toInt())),
        Triple(14, R.string.theme_mono, Triple(0xFFF4F4F5.toInt(), 0xFFE9E9EB.toInt(), 0xFF5B5B66.toInt())),
        Triple(15, R.string.theme_mint, Triple(0xFFF0F7F2.toInt(), 0xFFE3EFE7.toInt(), 0xFF2F9E68.toInt())),
        Triple(16, R.string.theme_sunset, Triple(0xFFFBF3EA.toInt(), 0xFFF5E6D3.toInt(), 0xFFD97742.toInt())),
        Triple(17, R.string.theme_violet_night, Triple(0xFF171226.toInt(), 0xFF2C2445.toInt(), 0xFFA78BFA.toInt())),
        Triple(18, R.string.theme_cyberpunk, Triple(0xFF0F0B1E.toInt(), 0xFF1D1438.toInt(), 0xFF00E5FF.toInt())),
        Triple(19, R.string.theme_sakura, Triple(0xFFFFF0F5.toInt(), 0xFFFFE4E1.toInt(), 0xFFFF69B4.toInt())),
        Triple(20, R.string.theme_espresso, Triple(0xFF1B1412.toInt(), 0xFF2E221F.toInt(), 0xFFD7A15C.toInt())),
        Triple(21, R.string.theme_nord, Triple(0xFF1E222A.toInt(), 0xFF282C34.toInt(), 0xFF81A1C1.toInt())),
        Triple(22, R.string.theme_emerald, Triple(0xFF0A1C16.toInt(), 0xFF143026.toInt(), 0xFF2ECC71.toInt())),
        Triple(23, R.string.theme_amber, Triple(0xFFFFFDF5.toInt(), 0xFFF7F0D8.toInt(), 0xFFF39C12.toInt()))
    )

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_appearance)
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- theme gallery (grid of live preview mockups) ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_theme)))
        col.addView(themeGallery(ctx, act))

        // ---- v1.5.0: custom accent («Свой стиль») — applied to FAB / tab stroke ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.accent_section)))
        fun accentValue(): String =
            if (Prefs.customAccent != 0) "#%06X".format(0xFFFFFF and Prefs.customAccent)
            else getString(R.string.accent_default)
        val accentRow = SettingsUi.clickRow(ctx, R.drawable.ic_palette, getString(R.string.accent_color), accentValue()) {
            accentDialog(ctx)
        }
        accentValueView = accentRow.getChildAt(2) as? TextView
        col.addView(accentRow)

        // ---- page appearance (applies LIVE to the open page) ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_page_appearance)))
        fun appearanceValue(): String = when (Prefs.pageAppearance) {
            1 -> getString(R.string.appearance_light)
            2 -> getString(R.string.appearance_dark)
            3 -> getString(R.string.appearance_sepia)
            4 -> getString(R.string.appearance_gray)
            5 -> getString(R.string.appearance_contrast)
            else -> getString(R.string.appearance_system)
        }
        val appearanceRow = SettingsUi.clickRow(ctx, R.drawable.ic_palette, getString(R.string.set_page_appearance), appearanceValue()) {
            val names = arrayOf(
                getString(R.string.appearance_system), getString(R.string.appearance_light),
                getString(R.string.appearance_dark), getString(R.string.appearance_sepia),
                getString(R.string.appearance_gray), getString(R.string.appearance_contrast))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.set_page_appearance)
                .setSingleChoiceItems(names, Prefs.pageAppearance) { d, which ->
                    Prefs.setPageAppearance(which)
                    d.dismiss()
                    // v1.4.1 fix: apply IMMEDIATELY to the already-loaded page
                    (activity as? com.zephyr.browser.MainActivity)?.applyPageAppearance(which)
                    appearanceValueView?.text = appearanceValue()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        appearanceValueView = appearanceRow.getChildAt(2) as? TextView
        col.addView(appearanceRow)

        // ---- v1.7.24: bottom search bar ----
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_search, getString(R.string.set_bottom_search_bar),
            getString(R.string.set_bottom_search_bar_sum), Prefs.bottomSearchBar) {
            Prefs.setBottomSearchBar(it)
            (activity as? com.zephyr.browser.MainActivity)?.applyBottomSearchBarPosition()
        })

        // ---- toolbar buttons (localized value + complete id list) ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_toolbar_buttons)))
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_widgets, getString(R.string.set_toolbar_buttons), toolbarValue()) {
            pickToolbarActions(ctx)
        })

        // ---- v1.7.6: night mode by schedule (app theme + pages flip automatically) ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_night_schedule)))
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_palette, getString(R.string.set_night_schedule),
            getString(R.string.set_night_schedule_sum), Prefs.nightSchedule) {
            Prefs.setNightSchedule(it)
            (activity as? com.zephyr.browser.MainActivity)?.applyNightSchedule()
        })
        fun nightTime(min: Int): String = String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60)
        val fromRow = SettingsUi.clickRow(ctx, R.drawable.ic_arrow_up, getString(R.string.set_night_from),
            nightTime(Prefs.nightStart)) { pickNightTime(start = true) }
        val toRow = SettingsUi.clickRow(ctx, R.drawable.ic_arrow_up, getString(R.string.set_night_to),
            nightTime(Prefs.nightEnd)) { pickNightTime(start = false) }
        nightFromValueView = fromRow.getChildAt(2) as? TextView
        nightToValueView = toRow.getChildAt(2) as? TextView
        col.addView(fromRow)
        col.addView(toRow)

        return root
    }

    private var appearanceValueView: TextView? = null
    private var accentValueView: TextView? = null
    private var nightFromValueView: TextView? = null
    private var nightToValueView: TextView? = null

    /** v1.7.6: Material time picker for the night-mode window boundaries. */
    private fun pickNightTime(start: Boolean) {
        val ctx = context ?: return
        val current = if (start) Prefs.nightStart else Prefs.nightEnd
        val builder = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(if (android.text.format.DateFormat.is24HourFormat(ctx))
                com.google.android.material.timepicker.TimeFormat.CLOCK_24H
            else com.google.android.material.timepicker.TimeFormat.CLOCK_12H)
            .setHour(current / 60)
            .setMinute(current % 60)
            .setTitleText(if (start) R.string.set_night_from else R.string.set_night_to)
            .build()
        builder.addOnPositiveButtonClickListener {
            val minutes = builder.hour * 60 + builder.minute
            if (start) {
                Prefs.setNightWindow(minutes, Prefs.nightEnd)
                nightFromValueView?.text = String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
            } else {
                Prefs.setNightWindow(Prefs.nightStart, minutes)
                nightToValueView?.text = String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
            }
            (activity as? com.zephyr.browser.MainActivity)?.applyNightSchedule()
        }
        builder.show(childFragmentManager, "nightTime")
    }

    /** Grid (3 columns) of theme preview mockups with names + selection mark. */
    private fun themeGallery(ctx: android.content.Context, act: SettingsActivity): LinearLayout {
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(4))
        }
        val selected = Prefs.themeSnapshot(ctx)
        var row: LinearLayout? = null
        for ((index, t) in themes.withIndex()) {
            if (index % 3 == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row)
            }
            row?.addView(themeCard(ctx, act, t.first, t.second, t.third, t.first == selected))
        }
        return grid
    }

    /** One theme card: mini browser mockup (status bar + omnibox pill + accent FAB) + name. */
    private fun themeCard(
        ctx: android.content.Context,
        act: SettingsActivity,
        id: Int, nameRes: Int,
        palette: Triple<Int, Int, Int>,
        isSelected: Boolean
    ): LinearLayout {
        val bg = palette.first
        val pill = palette.second
        val accent = palette.third
        // v1.5.0: custom accent override wins over the theme's own accent for the selection ring
        val ringColor = Prefs.accentOrDefault(accent)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = ctx.dp(5); bottomMargin = ctx.dp(5)
            }
        }

        // mockup
        val mock = android.widget.FrameLayout(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(14).toFloat()
                setColor(bg)
                setStroke(ctx.dp(1).coerceAtLeast(1), if (isSelected) ringColor else 0x1A888888)
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(84))
        }
        // top toolbar strip
        mock.addView(View(ctx).apply {
            setBackgroundColor(pill)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, ctx.dp(12),
                android.view.Gravity.TOP)
        })
        // omnibox pill
        mock.addView(View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(9).toFloat()
                setColor(pill)
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ctx.dp(74), ctx.dp(18),
                android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP).apply {
                topMargin = ctx.dp(20)
            }
        })
        // two tiny "tile" squares
        mock.addView(View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(4).toFloat()
                setColor(pill)
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(14),
                android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP).apply {
                topMargin = ctx.dp(46); leftMargin = ctx.dp(0)
            }
        })
        // accent dot (FAB)
        mock.addView(View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(accent)
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14),
                android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
                rightMargin = ctx.dp(8); bottomMargin = ctx.dp(8)
            }
        })
        card.addView(mock)

        // name + selection marker
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, ctx.dp(5), 0, 0)
            addView(TextView(ctx).apply {
                text = getString(nameRes)
                textSize = 10.5f
                maxLines = 1
                gravity = android.view.Gravity.CENTER
                setTextColor(SettingsUi.themedColor(ctx, if (isSelected) R.attr.colorPrimary else R.attr.colorOnSurfaceVariant))
            })
        })

        card.isClickable = true
        card.isFocusable = true
        card.foreground = SettingsUi.ripple(ctx)
        card.setOnClickListener {
            Prefs.setTheme(id)
            // v1.5.0 FIX: remember the screen BEFORE recreating — SettingsActivity.onCreate
            // re-opens it instead of dumping the user at the settings root menu.
            SettingsActivity.recreateScreen = "appearance"
            // instant visual feedback in settings, MainActivity re-themes on resume
            act.recreate()
        }
        return card
    }

    // ==== v1.5.0: custom accent color («Свой стиль») ====

    /** Material-ish accent presets offered in the dialog (4-column grid). */
    private val accentPresets = intArrayOf(
        0xFF0E9F6E.toInt(), 0xFF14B8A6.toInt(), 0xFFD97706.toInt(), 0xFFEA580C.toInt(),
        0xFFDC2626.toInt(), 0xFFE11D48.toInt(), 0xFF7C3AED.toInt(), 0xFFA78BFA.toInt(),
        0xFF0891B2.toInt(), 0xFF15803D.toInt(), 0xFF5B5B66.toInt(), 0xFF92400E.toInt())

    /** Parses "#RRGGBB" / "#AARRGGBB" (leading # optional); null when invalid. */
    private fun parseAccentHex(raw: String): Int? {
        val hex = raw.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        return try {
            val c = Color.parseColor("#$hex")
            if (hex.length == 6) c or 0xFF000000.toInt() else c
        } catch (_: IllegalArgumentException) { null }
    }

    /** Perceived brightness 0..1 — used to tint the check mark on a swatch. */
    private fun luminance(c: Int): Float =
        0.299f * ((c shr 16 and 0xFF) / 255f) +
        0.587f * ((c shr 8 and 0xFF) / 255f) +
        0.114f * ((c and 0xFF) / 255f)

    /** Accent picker: live preview strip + 12 swatches + «По умолчанию» + HEX input. */
    private fun accentDialog(ctx: android.content.Context) {
        val themeDefault = SettingsUi.themedColor(ctx, R.attr.colorPrimary)
        var pending = Prefs.customAccent            // 0 = theme default
        fun effective(): Int = if (pending != 0) pending else themeDefault

        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(6), ctx.dp(20), ctx.dp(2))
        }

        // ---- live preview strip: mini FAB + switch-like pill ----
        val fabDot = View(ctx)
        val pillTrack = View(ctx)
        wrap.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(12))
            addView(fabDot.apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(effective())
                }
                layoutParams = LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34))
            })
            addView(android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(30)).apply {
                    marginStart = ctx.dp(14)
                }
                addView(pillTrack.apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = ctx.dp(15).toFloat()
                        setColor(effective())
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                })
                addView(View(ctx).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFFFFFFFF.toInt())
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24),
                        android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END).apply {
                        marginEnd = ctx.dp(3)
                    }
                })
            })
        })

        // ---- 12 accent swatches in a 4-column grid; selected = ring + check ----
        val swatchDrawables = mutableListOf<android.graphics.drawable.GradientDrawable>()
        val swatchChecks = mutableListOf<android.widget.ImageView>()
        val cells = mutableListOf<android.widget.FrameLayout>()
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var srow: LinearLayout? = null
        for (c in accentPresets) {
            if (cells.size % 4 == 0) {
                srow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(srow)
            }
            val cell = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ctx.dp(46), 1f).apply {
                    marginEnd = ctx.dp(6); bottomMargin = ctx.dp(6)
                }
                isClickable = true
                foreground = SettingsUi.ripple(ctx)
            }
            val circleDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(c)
            }
            cell.addView(View(ctx).apply {
                background = circleDrawable
                layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40),
                    android.view.Gravity.CENTER)
            })
            val check = android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (luminance(c) > 0.6f) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20),
                    android.view.Gravity.CENTER)
                visibility = View.INVISIBLE
            }
            cell.addView(check)
            cells.add(cell)
            swatchDrawables.add(circleDrawable)
            swatchChecks.add(check)
            srow?.addView(cell)
        }
        wrap.addView(grid)

        // ---- «По умолчанию» row: clears the override back to the theme accent ----
        val defaultCircleDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(themeDefault)
        }
        val defaultCheck = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = android.content.res.ColorStateList.valueOf(themeDefault)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20))
            visibility = View.INVISIBLE
        }
        val defaultRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(2))
            isClickable = true
            foreground = SettingsUi.ripple(ctx)
            addView(View(ctx).apply {
                background = defaultCircleDrawable
                layoutParams = LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.accent_default)
                textSize = 14.5f
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = ctx.dp(12)
                }
            })
            addView(defaultCheck)
        }
        wrap.addView(defaultRow)

        // ---- HEX input row (HomePrefsFragment.hexDialog pattern) ----
        val hexInput = PanelUi.dialogField(ctx, getString(R.string.accent_hex_hint),
            if (Prefs.customAccent != 0) "#%06X".format(0xFFFFFF and Prefs.customAccent) else "")
        wrap.addView(PanelUi.fieldWrap(hexInput))

        // re-renders check/ring marks + live preview for the current [pending]
        fun renderSelection() {
            val onSurface = SettingsUi.themedColor(ctx, R.attr.colorOnSurface)
            for (i in accentPresets.indices) {
                val sel = pending == accentPresets[i]
                swatchDrawables[i].setStroke(ctx.dp(2), if (sel) onSurface else 0x00000000)
                swatchChecks[i].visibility = if (sel) View.VISIBLE else View.INVISIBLE
            }
            val def = pending == 0
            defaultCircleDrawable.setStroke(ctx.dp(2), if (def) onSurface else 0x00000000)
            defaultCheck.visibility = if (def) View.VISIBLE else View.INVISIBLE
            val a = effective()
            (fabDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(a)
            (pillTrack.background as? android.graphics.drawable.GradientDrawable)?.setColor(a)
        }

        // wire interactions AFTER renderSelection is declared (local funs are not hoisted)
        for (i in cells.indices) {
            cells[i].setOnClickListener {
                pending = accentPresets[i]
                hexInput.setText("#%06X".format(0xFFFFFF and accentPresets[i]))
                renderSelection()
            }
        }
        defaultRow.setOnClickListener {
            pending = 0
            hexInput.setText("")
            renderSelection()
        }
        hexInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = s?.toString()?.trim() ?: return
                if (t.isEmpty()) return          // keep the swatch/default selection
                parseAccentHex(t)?.let { parsed ->
                    pending = parsed
                    renderSelection()
                }
            }
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        renderSelection()

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.accent_color)
            .setView(wrap)
            .setPositiveButton(R.string.ok) { _, _ ->
                val typed = hexInput.text.toString().trim()
                if (typed.isNotEmpty()) {
                    val parsed = parseAccentHex(typed)
                    if (parsed == null) {
                        Toast.makeText(ctx, R.string.accent_hex_bad, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    pending = parsed
                }
                Prefs.setCustomAccent(pending)
                accentValueView?.text = if (pending != 0) "#%06X".format(0xFFFFFF and pending)
                    else getString(R.string.accent_default)
                // cheap full re-render (same pattern as HomePrefsFragment): the theme
                // gallery selection rings pick up the new accent as well
                (activity as? SettingsActivity)?.openScreen("appearance")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickToolbarActions(ctx: android.content.Context) {
        // v1.7.31: files / video / adblock / vpn / settings / new tab were
        // callable from the menu but had no toolbar slot (user: «ряд функций
        // отсутствует, добавь Файлы»).
        val ids = listOf("forward", "reload", "newtab", "search", "voice", "bookmarks", "history",
            "downloads", "files", "share", "addbookmark", "reader", "incognito", "desktop",
            "find", "translate", "ai", "image", "video", "burn", "adblock", "vpn", "screenshot",
            "pdf", "source", "settings")
        val labels = mapOf(
            "forward" to R.string.nav_forward, "reload" to R.string.nav_reload,
            "bookmark" to R.string.bookmarks, "bookmarks" to R.string.bookmarks,
            "search" to R.string.nav_search, "voice" to R.string.voice_search,
            "share" to R.string.nav_share, "history" to R.string.history,
            "downloads" to R.string.downloads, "reader" to R.string.reader_mode,
            "incognito" to R.string.new_private_tab, "desktop" to R.string.pc_mode,
            "find" to R.string.find_on_page, "translate" to R.string.translate,
            "ai" to R.string.ai_chat, "image" to R.string.image_search,
            "addbookmark" to R.string.add_bookmark,
            "files" to R.string.files_title, "video" to R.string.sniff_menu,
            "adblock" to R.string.menu_adblock, "vpn" to R.string.menu_vpn,
            "settings" to R.string.menu_settings, "newtab" to R.string.menu_new_tab,
            "screenshot" to R.string.menu_screenshot, "pdf" to R.string.menu_save_pdf,
            "source" to R.string.page_source, "burn" to R.string.burn_title)
        val selected = Prefs.toolbarActions.split(',').map { it.trim() }
            .filter { it.isNotEmpty() && it in ids }.toMutableSet()
        val names = ids.map { getString(labels[it] ?: R.string.ok) }.toTypedArray()
        val checks = ids.map { it in selected }.toBooleanArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.toolbar_picker_title)
            .setMultiChoiceItems(names, checks) { _, which, isChecked ->
                val id = ids[which]
                if (isChecked) { if (selected.size < 4) selected.add(id) }
                else selected.remove(id)
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val ordered = ids.filter { it in selected }
                Prefs.setToolbarActions(ordered.joinToString(","))
                Toast.makeText(ctx, R.string.toolbar_applied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Localized toolbar actions value ("Закладки, Обновить" instead of raw ids). */
    private fun toolbarValue(): String {
        val labels = mapOf(
            "forward" to R.string.nav_forward, "reload" to R.string.nav_reload,
            "bookmark" to R.string.bookmarks, "bookmarks" to R.string.bookmarks,
            "search" to R.string.nav_search, "voice" to R.string.voice_search,
            "share" to R.string.nav_share, "history" to R.string.history,
            "downloads" to R.string.downloads, "reader" to R.string.reader_mode,
            "incognito" to R.string.new_private_tab, "desktop" to R.string.pc_mode,
            "find" to R.string.find_on_page, "translate" to R.string.translate,
            "ai" to R.string.ai_chat, "image" to R.string.image_search,
            "addbookmark" to R.string.add_bookmark,
            "files" to R.string.files_title, "video" to R.string.sniff_menu,
            "adblock" to R.string.menu_adblock, "vpn" to R.string.menu_vpn,
            "settings" to R.string.menu_settings, "newtab" to R.string.menu_new_tab,
            "screenshot" to R.string.menu_screenshot, "pdf" to R.string.menu_save_pdf,
            "source" to R.string.page_source, "burn" to R.string.burn_title)
        val ids = Prefs.toolbarActions.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (ids.isEmpty()) getString(R.string.bg_default)
        else ids.mapNotNull { id -> labels[id]?.let { getString(it) } }.joinToString(", ")
    }
}

// =====================================================================
// Home screen customization (v1.4.1: background preview, HEX, grid, search bar;
// v1.7.14: home page VARIANTS — standard / minimal / clock / sites grid / custom HTML)
// =====================================================================

class HomePrefsFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_home_screen)
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- background: big live PREVIEW of what is applied now ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_home_bg)))
        col.addView(backgroundPreview(ctx))

        // presets row (color swatches)
        col.addView(colorSwatchRow(ctx))

        // hex input
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_edit, getString(R.string.bg_pick_color), hexValue()) {
            hexDialog(ctx)
        })
        // photo pick / clear
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_image, getString(R.string.bg_pick_wallpaper), photoValue()) {
            pickWallpaper()
        })
        col.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_close, getString(R.string.bg_wallpaper_clear), getString(R.string.h_wallpaper_clear)) {
            Prefs.setWallpaper("")
            Prefs.setHomeBgColor(0)
            Toast.makeText(ctx, R.string.bg_photo_applied, Toast.LENGTH_SHORT).show()
            refreshScreen(act)
        })

        // ---- search bar on home ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_search)))
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_search, getString(R.string.set_home_search_bar),
            getString(R.string.set_home_search_bar_sum), Prefs.homeSearchBar) {
            Prefs.setHomeSearchBar(it)
        })

        // ---- grid & sizes ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_home_grid)))
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_grid, getString(R.string.set_grid_columns), columnsValue()) {
            val names = arrayOf("4", "5", "6")
            val cur = when (Prefs.homeGridColumns) { 5 -> 1; 6 -> 2; else -> 0 }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.set_grid_columns)
                .setSingleChoiceItems(names, cur) { d, which ->
                    Prefs.setHomeGridColumns(names[which].toInt())
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_widgets, getString(R.string.set_tile_size), tileValue()) {
            val names = arrayOf(getString(R.string.size_small), getString(R.string.size_medium), getString(R.string.size_large))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.set_tile_size)
                .setSingleChoiceItems(names, Prefs.homeTileSize) { d, which ->
                    Prefs.setHomeTileSize(which)
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })

        // ---- sections ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_home_sections)))
        // v1.7.14: master HOME PAGE VARIANT picker — the overall look of the home screen
        col.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_home, getString(R.string.home_page_variant), variantValue()) {
            showVariantSheet(ctx)
        })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_grid, getString(R.string.home_quick_access), getString(R.string.h_home_quick_access),
            Prefs.homeQuick) { Prefs.setHomeToggles(it, Prefs.homeRecents, Prefs.homeBookmarks) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_history, getString(R.string.home_recents), getString(R.string.h_home_recents),
            Prefs.homeRecents) { Prefs.setHomeToggles(Prefs.homeQuick, it, Prefs.homeBookmarks) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_bookmark, getString(R.string.home_bookmarks), getString(R.string.h_home_bookmarks),
            Prefs.homeBookmarks) { Prefs.setHomeToggles(Prefs.homeQuick, Prefs.homeRecents, it) })

        // ---- v1.7.9: recents/bookmarks feed direction (horizontal swipe vs vertical grid) ----
        // v1.7.56: the label is long enough that title + value on one line
        // truncated both — the mode reads as a second line instead
        col.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_list, getString(R.string.home_feed_mode), feedValue()) {
            val names = arrayOf(getString(R.string.home_feed_horiz), getString(R.string.home_feed_vert))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.home_feed_mode)
                .setSingleChoiceItems(names, if (Prefs.homeFeedMode == 1) 1 else 0) { d, which ->
                    Prefs.setHomeFeedMode(which)
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })

        return root
    }

    private fun refreshScreen(act: SettingsActivity) {
        // cheap full re-render of this settings screen (keeps back stack)
        act.openScreen("home")
    }

    private fun hexValue(): String =
        if (Prefs.homeBgColor != 0 && Prefs.wallpaperUri.isEmpty()) "#%06X".format(0xFFFFFF and Prefs.homeBgColor)
        else getString(R.string.bg_hex_hint)

    private fun photoValue(): String = when {
        Prefs.wallpaperUri.isNotEmpty() -> getString(R.string.bg_photo_set)
        else -> getString(R.string.bg_none)
    }

    /** Live preview card: shows the CURRENT applied background (photo thumbnail / color / default). */
    private fun backgroundPreview(ctx: android.content.Context): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ctx.dp(10)
            }
        }
        val preview = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(140))
        }
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ctx.dp(16).toFloat()
            val base = if (Prefs.wallpaperUri.isEmpty() && Prefs.homeBgColor != 0) Prefs.homeBgColor
            else SettingsUi.themedColor(ctx, R.attr.zBackground, 0xFFFFFFFF.toInt())
            setColor(base)
        }
        val photo = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
            background = bgDrawable
            clipToOutline = true
        }
        preview.addView(photo)
        // caption pill
        preview.addView(TextView(ctx).apply {
            text = when {
                Prefs.wallpaperUri.isNotEmpty() -> getString(R.string.bg_photo_set)
                Prefs.homeBgColor != 0 -> "#%06X".format(0xFFFFFF and Prefs.homeBgColor)
                else -> getString(R.string.bg_default)
            }
            textSize = 11.5f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(14).toFloat()
                setColor(0x99000000.toInt())
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
                leftMargin = ctx.dp(10); bottomMargin = ctx.dp(10)
            }
        })
        card.addView(preview)

        // async decode the current wallpaper into the preview (quality visible)
        val path = Prefs.wallpaperUri
        if (path.isNotEmpty()) {
            io.launch {
                val bmp = try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    val ins = if (path.startsWith("content:")) ctx.contentResolver.openInputStream(android.net.Uri.parse(path))
                    else java.io.File(path).inputStream()
                    ins?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                    var sample = 1
                    while (opts.outWidth / (sample * 2) >= 360) sample *= 2
                    val ins2 = if (path.startsWith("content:")) ctx.contentResolver.openInputStream(android.net.Uri.parse(path))
                    else java.io.File(path).inputStream()
                    ins2?.use { android.graphics.BitmapFactory.decodeStream(it, null,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                } catch (_: Exception) { null }
                withContext(Dispatchers.Main) { bmp?.let { photo.setImageBitmap(it) } }
            }
        }
        return card
    }

    /** Quick color presets as tappable round swatches. */
    private fun colorSwatchRow(ctx: android.content.Context): android.widget.HorizontalScrollView {
        val colors = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFF1F3F4.toInt(), 0xFFE8F0FE.toInt(), 0xFFFCE8E6.toInt(),
            0xFFE6F4EA.toInt(), 0xFFFEF7E0.toInt(), 0xFFF3E8FD.toInt(), 0xFF151023.toInt())
        val scroll = android.widget.HorizontalScrollView(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(8))
        }
        for (c in colors) {
            val selected = Prefs.homeBgColor == c && Prefs.wallpaperUri.isEmpty()
            row.addView(View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    setStroke(if (selected) ctx.dp(3) else ctx.dp(1).coerceAtLeast(1),
                        if (selected) SettingsUi.themedColor(ctx, R.attr.colorPrimary) else 0xFF9AA0A6.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply {
                    marginEnd = ctx.dp(10)
                }
                isClickable = true
                setOnClickListener {
                    Prefs.setWallpaper("")
                    Prefs.setHomeBgColor(c)
                    (activity as? SettingsActivity)?.openScreen("home")
                }
                contentDescription = "#%06X".format(0xFFFFFF and c)
            })
        }
        scroll.addView(row)
        return scroll
    }

    private fun hexDialog(ctx: android.content.Context) {
        val input = PanelUi.dialogField(ctx, getString(R.string.bg_hex_hint), hexValue().removePrefix("#"))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.bg_pick_color)
            .setView(PanelUi.fieldWrap(input))
            .setPositiveButton(R.string.ok) { _, _ ->
                var hex = input.text.toString().trim().removePrefix("#")
                if (hex.length == 6) {
                    try {
                        val color = android.graphics.Color.parseColor("#$hex") or 0xFF000000.toInt()
                        Prefs.setWallpaper("")
                        Prefs.setHomeBgColor(color)
                        (activity as? SettingsActivity)?.openScreen("home")
                    } catch (_: Exception) {
                        Toast.makeText(ctx, R.string.bg_hex_bad, Toast.LENGTH_SHORT).show()
                    }
                } else Toast.makeText(ctx, R.string.bg_hex_bad, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickWallpaper() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try { startActivityForResult(i, SettingsActivity.RC_WALLPAPER) } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SettingsActivity.RC_WALLPAPER && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri -> copyWallpaper(uri) }
        }
    }

    /** Copy the picked image into filesDir — survives restarts, no fragile SAF grants. */
    private fun copyWallpaper(uri: Uri) {
        val ctx = context ?: return
        io.launch {
            val ok = try {
                val target = File(ctx.filesDir, "home_wallpaper")
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    target.outputStream().use { ins.copyTo(it) }
                } != null && target.length() > 0
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) {
                    Prefs.setWallpaper(File(ctx.filesDir, "home_wallpaper").absolutePath)
                    Prefs.setHomeBgColor(0)
                    Toast.makeText(ctx, R.string.bg_photo_applied, Toast.LENGTH_SHORT).show()
                    (activity as? SettingsActivity)?.openScreen("home")
                } else {
                    Toast.makeText(ctx, R.string.bg_photo_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun tileValue(): String = when (Prefs.homeTileSize) {
        0 -> getString(R.string.size_small)
        2 -> getString(R.string.size_large)
        else -> getString(R.string.size_medium)
    }

    private fun columnsValue(): String = Prefs.homeGridColumns.toString()

    /** v1.7.9: current recents/bookmarks feed direction shown as the row's value text. */
    private fun feedValue(): String =
        getString(if (Prefs.homeFeedMode == 1) R.string.home_feed_vert else R.string.home_feed_horiz)

    /** v1.7.14: current home variant shown as the row's value text (feedValue pattern). */
    private fun variantValue(): String {
        if (Prefs.homeVariant == 4) {
            val path = Prefs.homeCustomUrl.trim()
            val short = if (path.length > 24) "…" + path.takeLast(24) else path
            return getString(R.string.home_variant_custom) + " · $short"
        }
        return getString(when (Prefs.homeVariant) {
            1 -> R.string.home_variant_minimal
            2 -> R.string.home_variant_clock
            3 -> R.string.home_variant_sites
            else -> R.string.home_variant_standard
        })
    }

    /**
     * v1.7.16 (user: «твое окно открывается теперь, функции Главная страница, но
     * выходит маленькое окно… сделай лучше ПОЛНОЦЕННОЕ ОКНО С МАКЕТАМИ как ты
     * сделал макеты в окне sheets выбор цвета страницы. И там же кнопку
     * ПРИМЕНИТЬ — это будет куда профессиональнее. И пункты где много написано
     * сделай в два ряда: оглавление большим сверху, а пояснение снизу мелким»):
     * the small v1.7.15 single-choice dialog is replaced with a premium PREVIEW
     * SHEET built on the exact idioms of MenuSheets.showAppearancePickerSheet
     * (BottomSheetDialog, STATE_EXPANDED + skipCollapsed, themedColor/dp,
     * weight-based card rows, a renderCards()-style re-render closure) — a
     * 2×3 grid of 5 cards, each with a MINI HOME-SCREEN MOCKUP, a bold NAME on
     * top and a small DESCRIPTION below (the two-line layout the user asked
     * for). Tapping a card only moves the selection; the full-width «Применить»
     * pill applies it (applyVariantChoice keeps handling the variant-4 chained
     * URL dialog + validation). Whole body armored like every dialog since
     * v1.7.10: any device-specific failure degrades to a toast, never a crash.
     */
    private fun showVariantSheet(ctx: android.content.Context) {
        try {
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true

            // insets-aware root + ScrollView (showSiteInfo skeleton) — the apply
            // button can never clip under the nav bar, tall content just scrolls
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                fitsSystemWindows = true
            }
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
                v.setPadding(v.paddingLeft, ins.systemWindowInsetTop + v.paddingTop,
                    v.paddingRight, ins.systemWindowInsetBottom + v.paddingBottom)
                androidx.core.view.ViewCompat.onApplyWindowInsets(v, ins)
            }
            val scroll = android.widget.ScrollView(ctx)
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(6))
            }
            scroll.addView(col)
            root.addView(scroll)

            col.addView(TextView(ctx).apply {
                text = getString(R.string.home_page_variant)
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
                setPadding(ctx.dp(4), 0, ctx.dp(4), ctx.dp(4))
            })

            // ---- selection state + muted, theme-neutral palette for the mini mocks ----
            var selected = Prefs.homeVariant.coerceIn(0, 4)
            val names = intArrayOf(
                R.string.home_variant_standard, R.string.home_variant_minimal,
                R.string.home_variant_clock, R.string.home_variant_sites, R.string.home_variant_custom)
            val descs = intArrayOf(
                R.string.home_variant_standard_desc, R.string.home_variant_minimal_desc,
                R.string.home_variant_clock_desc, R.string.home_variant_sites_desc,
                R.string.home_variant_custom_desc)
            val onSurface = SettingsUi.themedColor(ctx, R.attr.colorOnSurface)
            val onSurfaceVar = SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant)
            val quiet = (onSurfaceVar and 0x00FFFFFF) or 0x40000000       // mock blocks @ 25% alpha
            val strong = (onSurfaceVar and 0x00FFFFFF) or 0x66000000      // search pills / toolbar @ 40%

            /** Tiny rounded mock block (the appearance sheet's fake-line idiom). */
            fun mock(color: Int, radiusDp: Int) = View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ctx.dp(radiusDp).toFloat()
                    setColor(color)
                }
            }

            /** Centers [v] and spans it across [weight] of the mock width (weight trick —
             *  stays proportional at any card/screen width, like the appearance cards). */
            fun span(weight: Float, hDp: Int, v: View, topDp: Int = 0, bottomDp: Int = 0) =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = ctx.dp(topDp); bottomMargin = ctx.dp(bottomDp)
                    }
                    addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, (1f - weight) / 2f)
                    })
                    addView(v.apply {
                        layoutParams = LinearLayout.LayoutParams(0, ctx.dp(hDp), weight)
                    })
                    addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, (1f - weight) / 2f)
                    })
                }

            /** The mini home-screen mock for variant [idx] (pure programmatic views). */
            fun mockContent(idx: Int): LinearLayout = when (idx) {
                1 -> LinearLayout(ctx).apply {   // minimal: wordmark + search pill
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    addView(TextView(ctx).apply {
                        text = "Zephyr"
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(onSurface)
                        letterSpacing = 0.04f
                    })
                    addView(span(0.62f, 10, mock(strong, 5), topDp = 7))
                }
                2 -> LinearLayout(ctx).apply {   // clock: big time + date line + search pill
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    addView(TextView(ctx).apply {
                        text = "12:34"
                        textSize = 16f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(onSurface)
                    })
                    addView(span(0.4f, 4, mock(quiet, 2), topDp = 4))
                    addView(span(0.62f, 10, mock(strong, 5), topDp = 7))
                }
                3 -> LinearLayout(ctx).apply {   // sites grid: 3×2 rounded squares
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    for (r in 0 until 2) {
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                if (r == 1) topMargin = ctx.dp(3)
                            }
                            for (c in 0 until 3) addView(mock(quiet, 4).apply {
                                layoutParams = LinearLayout.LayoutParams(ctx.dp(12), ctx.dp(12)).apply {
                                    marginEnd = if (c < 2) ctx.dp(3) else 0
                                }
                            })
                        })
                    }
                }
                4 -> LinearLayout(ctx).apply {   // custom page: mini browser frame
                    orientation = LinearLayout.VERTICAL
                    addView(mock(strong, 2).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(6))
                    })
                    addView(span(0.95f, 5, mock(quiet, 2), topDp = 5))
                    addView(span(0.85f, 5, mock(quiet, 2), topDp = 4))
                    addView(span(0.6f, 5, mock(quiet, 2), topDp = 4))
                    addView(TextView(ctx).apply {
                        text = ".html"
                        textSize = 10f
                        setTextColor(onSurfaceVar)
                        gravity = android.view.Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = ctx.dp(5)
                        }
                    })
                }
                else -> LinearLayout(ctx).apply {   // 0 standard: search + 4 tiles + list lines
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    addView(span(0.7f, 12, mock(strong, 6), bottomDp = 8))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        for (c in 0 until 4) addView(mock(quiet, 4).apply {
                            layoutParams = LinearLayout.LayoutParams(ctx.dp(10), ctx.dp(10)).apply {
                                marginEnd = if (c < 3) ctx.dp(4) else 0
                            }
                        })
                    })
                    addView(span(0.9f, 5, mock(quiet, 2), topDp = 7))
                    addView(span(0.7f, 5, mock(quiet, 2), topDp = 4))
                }
            }

            // ---- 2×3 preview grid (renderCards idiom: tap only moves the selection) ----
            val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun render() {
                grid.removeAllViews()
                var row: LinearLayout? = null
                for (i in 0 until 5) {
                    if (i % 2 == 0) {
                        row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                        grid.addView(row)
                    }
                    val idx = i
                    val sel = i == selected
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        setPadding(ctx.dp(6), ctx.dp(10), ctx.dp(6), ctx.dp(10))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        isClickable = true
                        foreground = SettingsUi.ripple(ctx)
                        // mockup: fixed 88dp rounded area — primaryContainer + 2dp primary
                        // stroke when selected, plain surfaceVariant tile otherwise
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = android.view.Gravity.CENTER
                            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(88))
                            background = if (sel) android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = ctx.dp(12).toFloat()
                                setColor(SettingsUi.themedColor(ctx, R.attr.colorPrimaryContainer))
                                setStroke(ctx.dp(2), SettingsUi.themedColor(ctx, R.attr.colorPrimary))
                            } else SettingsUi.roundedFillAttr(ctx, ctx.dp(12), R.attr.colorSurfaceVariant)
                            addView(mockContent(idx).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            })
                        })
                        // name big on top, description small below (the user's two-line layout)
                        addView(TextView(ctx).apply {
                            text = getString(names[idx])
                            textSize = 13f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(onSurface)
                            maxLines = 1
                            gravity = android.view.Gravity.CENTER
                            setPadding(0, ctx.dp(8), 0, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        })
                        addView(TextView(ctx).apply {
                            text = getString(descs[idx])
                            textSize = 10.5f
                            setTextColor(onSurfaceVar)
                            maxLines = 2
                            gravity = android.view.Gravity.CENTER
                            setLineSpacing(ctx.dp(1).toFloat(), 1f)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        })
                        setOnClickListener {
                            if (selected != idx) {
                                selected = idx
                                render()
                            }
                        }
                    }
                    row?.addView(card)
                }
                // 5th card rides alone in the last row → empty weight-1 spacer keeps
                // it half-width so the grid stays aligned (appearance-sheet idiom)
                row?.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            render()
            col.addView(grid)

            // ---- explicit apply pill (v1.7.16: no apply-on-tap anymore) ----
            col.addView(TextView(ctx).apply {
                text = getString(R.string.apply_action)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setPadding(ctx.dp(16), ctx.dp(13), ctx.dp(16), ctx.dp(13))
                background = SettingsUi.roundedFill(ctx, ctx.dp(22))
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnPrimary))
                isClickable = true
                setOnClickListener {
                    try { sheet.dismiss() } catch (_: Throwable) {}
                    applyVariantChoice(ctx, selected)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(12)
                    bottomMargin = ctx.dp(24)
                }
            })

            sheet.setContentView(root)
            sheet.show()
        } catch (t: Throwable) {
            try {
                Toast.makeText(ctx, t.javaClass.simpleName + ": " + (t.message ?: ""), Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Applies the picked variant; variant 4 asks for (and validates) the custom page.
     * v1.7.15: armored like every dialog since v1.7.10 — the whole body (including
     * the chained custom-URL dialog construction and its OK-handler) degrades to a
     * toast on any device-specific failure, never a crash.
     * v1.7.17 (user: «есть баг открытия какой-то страницы справа но сразу
     * закрытия… но выбор происходит»): NO openScreen("home") here anymore — the
     * sheet is opened FROM the Home settings screen, so re-opening the SAME screen
     * replayed the slide-from-right animation (the "ghost window" that flashed
     * open and instantly closed). A short home_variant_applied toast confirms the
     * choice instead; the row summary refreshes on the next screen re-render.
     */
    private fun applyVariantChoice(ctx: android.content.Context, sel: Int) {
        try {
            if (sel != 4) {
                Prefs.setHomeVariant(sel)
                Toast.makeText(ctx, R.string.home_variant_applied, Toast.LENGTH_SHORT).show()
                return
            }
            // custom page → second dialog for the HTML file path / URL (panelField pattern)
            val input = PanelUi.dialogField(ctx, getString(R.string.home_custom_url_hint), Prefs.homeCustomUrl)
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.home_variant_custom)
                .setView(PanelUi.fieldWrap(input))
                .setPositiveButton(R.string.ok) { _, _ ->
                    try {
                        val path = input.text.toString().trim()
                        when {
                            path.isEmpty() ->   // blank → nothing applied, variant unchanged
                                Toast.makeText(ctx, R.string.home_custom_empty, Toast.LENGTH_SHORT).show()
                            path.startsWith("/") && !File(path).exists() ->   // local file not visible
                                Toast.makeText(ctx, R.string.home_custom_missing, Toast.LENGTH_SHORT).show()
                            else -> {
                                Prefs.setHomeCustomUrl(path)
                                Prefs.setHomeVariant(4)
                                // v1.7.17: toast instead of openScreen("home") — the ghost-window fix
                                Toast.makeText(ctx, R.string.home_variant_applied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (t: Throwable) {
                        try {
                            Toast.makeText(ctx, t.javaClass.simpleName + ": " + (t.message ?: ""), Toast.LENGTH_LONG).show()
                        } catch (_: Throwable) {}
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (t: Throwable) {
            try {
                Toast.makeText(ctx, t.javaClass.simpleName + ": " + (t.message ?: ""), Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }
}

/** Wipes all preference keys (DataStore) — the settings-only reset. */
private fun android.content.Context.dataStorePrefsReset() {
    try {
        val dsFile = java.io.File(filesDir.parentFile, "datastore")
        // DataStore file lives at /data/data/<pkg>/files/datastore/zephyr_prefs.preferences_pb
        val target = java.io.File(dsFile, "zephyr_prefs.preferences_pb")
        if (target.exists()) target.delete()
        // synchronous mirrors
        getSharedPreferences("zephyr_ui", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("zephyr_lang", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    } catch (_: Exception) {}
}

// =====================================================================
// Privacy
// =====================================================================

class PrivacyPrefsFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_privacy)
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_private, getString(R.string.set_always_incognito),
            getString(R.string.set_always_incognito_sum), Prefs.alwaysIncognito) { Prefs.setAlwaysIncognito(it) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_shield, getString(R.string.set_block_webrtc),
            getString(R.string.set_block_webrtc_sum), Prefs.blockWebRTC) { Prefs.setBlockWebRTC(it) })
        // v1.7.84: this one sends a domain name off the device, so it belongs
        // on the privacy screen and its summary says plainly what it costs
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_globe, getString(R.string.set_favicon_net),
            getString(R.string.set_favicon_net_sum), Prefs.faviconNetwork) { Prefs.setFaviconNetwork(it) })

        // v1.7.55: the burn button is only as useful as it is predictable —
        // both what it wipes and whether it asks are the user's to set
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.burn_title)))
        // v1.7.56 (user: «название и настройки всё в одну строку — надо в две»):
        // clickRow puts its value to the RIGHT of the title, so a long list of
        // targets squeezed the name. clickRowSummary puts it underneath.
        val burnRow = SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_fire, getString(R.string.burn_settings), BurnOptions.summary(ctx)
        ) {}
        // the summary is the SECOND line of the row's text column (title first),
        // so reach it through that column rather than scanning the row itself
        val burnValue = (0 until burnRow.childCount)
            .mapNotNull { burnRow.getChildAt(it) as? LinearLayout }
            .firstOrNull { it.orientation == LinearLayout.VERTICAL && it.childCount >= 2 }
            ?.getChildAt(1) as? android.widget.TextView
        burnRow.setOnClickListener {
            BurnOptions.chooseDialog(ctx) {
                burnValue?.text = BurnOptions.summary(ctx)
            }
        }
        col.addView(burnRow)
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_shield, getString(R.string.burn_ask),
            getString(R.string.burn_ask_sub), Prefs.burnConfirm) { Prefs.setBurnConfirm(it) })

        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.set_site)))
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_script, getString(R.string.site_js), getString(R.string.h_site_js),
            Prefs.jsEnabled) { Prefs.setJs(it) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_cookie, getString(R.string.site_cookies), getString(R.string.h_site_cookies),
            Prefs.cookiesEnabled) { Prefs.setCookies(it) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_cookie, getString(R.string.third_party_cookies), getString(R.string.h_third_party_cookies),
            Prefs.thirdPartyCookies) { Prefs.setThirdPartyCookies(it) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_image, getString(R.string.site_images), getString(R.string.h_site_images),
            Prefs.imagesEnabled) { Prefs.setImages(it) })
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_block, getString(R.string.site_popups), getString(R.string.h_site_popups),
            Prefs.popupsEnabled) { Prefs.setPopups(it) })
        return root
    }
}

// =====================================================================
// Downloads
// =====================================================================

class DownloadsPrefsFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_downloads)
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val dir = java.io.File(
            ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, "Zephyr")
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_download, getString(R.string.panel_downloads),
            dir.absolutePath) {
            // open the panel
            startActivity(Intent(ctx, com.zephyr.browser.panels.PanelsActivity::class.java).apply {
                putExtra("panel", "downloads")
            })
        })
        return root
    }
}

// =====================================================================
// About
// =====================================================================

class AboutPrefsFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_about)
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // ── identity ─────────────────────────────────────────────────────────
        val idCard = SettingsUi.groupCard(ctx)
        try {
            val pm = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            idCard.addView(SettingsUi.clickRow(ctx, R.drawable.ic_info, getString(R.string.about_version),
                "${pm.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) pm.longVersionCode else pm.versionCode.toLong()})") {})
        } catch (_: Exception) {}
        // v1.7.84: the engine row named WebView but never said which one. The
        // version matters — most "this site is broken" reports are a WebView
        // that is years out of date, and it is the first thing to ask about.
        idCard.addView(SettingsUi.clickRow(ctx, R.drawable.ic_globe,
            getString(R.string.about_engine), webViewVersion(ctx)) {})
        col.addView(idCard)
        col.addView(SettingsUi.note(ctx, R.string.about_desc))

        // ── the documents a paid listing must carry (v1.7.84) ────────────────
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.about_legal)))
        val legal = SettingsUi.groupCard(ctx)
        legal.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_shield,
            getString(R.string.about_privacy), getString(R.string.about_privacy_sum)) {
            (activity as? SettingsActivity)?.openScreen("legal_privacy")
        })
        legal.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_info,
            getString(R.string.about_terms), getString(R.string.about_terms_sum)) {
            (activity as? SettingsActivity)?.openScreen("legal_terms")
        })
        legal.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_list,
            getString(R.string.about_licenses), getString(R.string.about_licenses_sum)) {
            (activity as? SettingsActivity)?.openScreen("legal_licenses")
        })
        col.addView(legal)

        // ── support ──────────────────────────────────────────────────────────
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.about_support)))
        val help = SettingsUi.groupCard(ctx)
        // v1.7.11: one-tap crash-report channel — if ANYTHING ever misbehaves on
        // the user's device, this row shares the exact stack trace the app
        // logged to filesDir/last_crash.txt (no logcat/ADB needed).
        help.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_bug,
            getString(R.string.crash_report_row), getString(R.string.crash_report_sum)) {
            shareLastCrashReport(ctx)
        })
        col.addView(help)
        return root
    }

    private fun dpx(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Which WebView is rendering pages, and which version of it.
     *
     * On most phones this is a separately-updated system component, so it is
     * the app's single largest unknown and the usual explanation when one site
     * misbehaves while every other one works.
     */
    private fun webViewVersion(ctx: android.content.Context): String = try {
        val pkg = if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.webkit.WebView.getCurrentWebViewPackage()
        } else null
        if (pkg != null) "${pkg.versionName}" else getString(R.string.about_engine_unknown)
    } catch (_: Throwable) {
        getString(R.string.about_engine_unknown)
    }

    /** The running version, asked of the package manager rather than hard-coded. */
    private fun appVersion(ctx: android.content.Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** v1.7.11: sends last_crash.txt through any messenger/mail app. */
    private fun shareLastCrashReport(ctx: android.content.Context) {
        try {
            val f = File(ctx.filesDir, "last_crash.txt")
            if (!f.exists() || f.length() == 0L) {
                Toast.makeText(ctx, R.string.crash_report_none, Toast.LENGTH_SHORT).show()
                return
            }
            val text = try { f.readText().take(60_000) } catch (_: Exception) { "" }
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                // v1.7.82: was the literal "Zephyr 1.7.17" — every report sent
                // since then was labelled with a version dozens of releases old,
                // which is exactly the field that makes a report triageable
                putExtra(Intent.EXTRA_SUBJECT, "Zephyr " + appVersion(ctx) + " crash report")
                putExtra(Intent.EXTRA_TEXT, text)
            })
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.crash_report_none, Toast.LENGTH_SHORT).show()
        }
    }
}

// =====================================================================
// Clear data — v1.7.3 premium redesign; v1.7.4 HARDENED after user reports
// («либо кидает на главную, либо вылетаю из приложения»):
//  - the WHOLE view construction is wrapped in try/catch with a minimal
//    fallback screen — this fragment can no longer crash the app, whatever
//    the device/theme combination throws at it;
//  - MaterialCheckBox → plain androidx-free android.widget.CheckBox (the
//    material variant was the only one in the app and the sole component
//    needing the full M3 inflation path on this screen);
//  - cache clearing no longer constructs a throwaway WebView (its creation
//    on some devices hard-crashes with an UnsatisfiedLinkError — an Error,
//    which the per-tag catch(Exception) could not even catch): the app cache
//    dirs (which ARE the WebView HTTP/disk cache location) are wiped
//    manually, best-effort, on the IO dispatcher.
// The premium layout itself is unchanged: grouped card with round icon
// tiles + live per-category subtitles + checkboxes, select-all/none chips,
// full-width danger button with an inline progress state.
// =====================================================================

class ClearDataPrefsFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One clearable category: stable tag + icon + label + default check state. */
    private class Cat(val tag: String, val icon: Int, val labelRes: Int, val defaultOn: Boolean)

    // v1.7.3: passwords + bookmarks default OFF (dangerous, user hostile if wiped by accident).
    private val cats = listOf(
        Cat("history", R.drawable.ic_history, R.string.clear_history, true),
        Cat("cookies", R.drawable.ic_cookie, R.string.clear_cookies, true),
        Cat("cache", R.drawable.ic_folder, R.string.clear_cache, true),
        Cat("forms", R.drawable.ic_edit, R.string.clear_forms, true),
        Cat("passwords", R.drawable.ic_key, R.string.clear_pwds, false),
        Cat("bookmarks", R.drawable.ic_bookmark, R.string.clear_bookmarks, false),
        Cat("quick", R.drawable.ic_grid, R.string.clear_quick_sites, true),
        Cat("scripts", R.drawable.ic_script, R.string.clear_scripts, true),
        Cat("downloads", R.drawable.ic_download, R.string.clear_dl_records, true)
    )

    /** v1.7.5 (user: «нет возможности удалить за последние 1 час 6 часов 12 и более»):
     *  time ranges for the wipe. 0 ms = all time. History + download JOURNAL rows
     *  honor the range; cookies/cache/etc. are always wiped fully (there is no
     *  timestamped store for them) — the note under the chips says so. */
    private class Range(val labelRes: Int, val millis: Long)
    private val ranges = listOf(
        Range(R.string.clear_range_all, 0L),
        Range(R.string.clear_range_1h, 3600_000L),
        Range(R.string.clear_range_6h, 6 * 3600_000L),
        Range(R.string.clear_range_12h, 12 * 3600_000L),
        Range(R.string.clear_range_24h, 24 * 3600_000L)
    )
    private var selectedRange = 0

    private val checks = HashMap<String, CheckBox>()
    private val subs = HashMap<String, TextView>()
    private val rangeChips = mutableListOf<TextView>()
    private var clearBtn: LinearLayout? = null
    private var clearIcon: android.widget.ImageView? = null
    private var clearProgress: android.widget.ProgressBar? = null
    private var clearing = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        // v1.7.5 REWRITE (user: «пункты впритык к краям, галочки впритык, пункты
        // тонкие, кнопки назад не видно»): the toolbar (with BACK) is built LOCALLY
        // for every variant — it no longer depends on the activity cast that could
        // silently drop it — and the content area still has the v1.7.4 crash
        // cascade (premium → compact fallback → dead-simple view).
        val ctx = safeContext() ?: return View(context)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buildToolbar(ctx))
        val content: View = try {
            buildContentView(ctx)
        } catch (_: Throwable) {
            try { buildFallbackView(ctx) } catch (_: Throwable) { View(ctx) }
        }
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    /** Local toolbar with a ALWAYS-present back arrow (v1.7.5). */
    private fun buildToolbar(ctx: android.content.Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
            addView(android.widget.ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                background = SettingsUi.ripple(ctx)
                setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44))
                contentDescription = getString(R.string.pl_back)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
                setOnClickListener {
                    try { activity?.onBackPressed() } catch (_: Throwable) { activity?.finish() }
                }
            })
            addView(TextView(ctx).apply {
                setText(R.string.set_clear)
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(ctx, 6)
                }
            })
        }
    }

    private fun buildFallbackView(ctx: android.content.Context): View {
        // compact but correctly padded fallback (v1.7.5: proper margins + range chips)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 16))
        }
        val scroll = android.widget.ScrollView(ctx)
        scroll.addView(col)
        addRangeChips(ctx, col, compact = true)
        col.addView(TextView(ctx).apply {
            setText(R.string.clear_pick_hint)
            textSize = 12.5f
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 10))
        })
        for (cat in cats) {
            val cb = CheckBox(ctx).apply { isChecked = cat.defaultOn }
            checks[cat.tag] = cb
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
                minimumHeight = dp(ctx, 52)
                setOnClickListener { cb.isChecked = !cb.isChecked }
            }
            row.addView(cb)
            row.addView(TextView(ctx).apply {
                setText(cat.labelRes)
                textSize = 15.5f
                setPadding(dp(ctx, 10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            col.addView(row)
        }
        buildClearButton(ctx, col)
        return scroll
    }

    /** Context that survives a detached fragment. */
    private fun safeContext(): android.content.Context? =
        context?.applicationContext ?: activity?.applicationContext

    private fun buildContentView(ctx: android.content.Context): View {
        val col = SettingsUi.screen(ctx)
        val scroll = SettingsUi.scrollOf(col)

        // ---- v1.7.5: time-range card (Всё время / 1 ч / 6 ч / 12 ч / 24 ч) ----
        col.addView(sectionLabel(ctx, getString(R.string.clear_time_title)))
        addRangeChips(ctx, col, compact = false)
        col.addView(TextView(ctx).apply {
            setText(R.string.clear_time_note)
            textSize = 12f
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(ctx, 18), dp(ctx, 4), dp(ctx, 18), dp(ctx, 8))
            }
        })

        // ---- quick chips: select all / deselect all ----
        val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 2))
            }
        }
        chips.addView(chip(ctx, getString(R.string.clear_select_all)) { setAll(true) })
        chips.addView(chip(ctx, getString(R.string.clear_select_none)) { setAll(false) }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(ctx, 8)
        })
        col.addView(chips)

        // ---- the category card: [checkbox][icon tile][title + subtitle] ----
        col.addView(sectionLabel(ctx, getString(R.string.clear_pick_hint)))
        val card = SettingsUi.groupCard(ctx)
        for ((idx, cat) in cats.withIndex()) {
            if (idx > 0) card.addView(SettingsUi.rowDivider(ctx))
            card.addView(catRow(ctx, cat))
        }
        col.addView(card)

        buildClearButton(ctx, col)
        try { refreshSubtitles() } catch (_: Throwable) {}
        return scroll
    }

    /** Bold section label (v1.7.5 — the old screen had none). */
    private fun sectionLabel(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 6))
            }
        }

    /** Selectable time-range chips. [compact] drops outer margins (fallback view). */
    private fun addRangeChips(ctx: android.content.Context, col: LinearLayout, compact: Boolean) {
        rangeChips.clear()
        // horizontal chip strip inside a HorizontalScrollView (no theme dependency, API-proof)
        val scroller = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (!compact) setMargins(dp(ctx, 14), 0, dp(ctx, 14), 0)
            }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        scroller.addView(row)
        ranges.forEachIndexed { idx, r ->
            val chip = rangeChip(ctx, getString(r.labelRes), idx == selectedRange) {
                selectedRange = idx
                styleRangeChips()
                try { refreshSubtitles() } catch (_: Throwable) {}
                updateClearButton()
            }
            rangeChips.add(chip)
            row.addView(chip)
            if (idx < ranges.size - 1) (chip.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(ctx, 8)
        }
        col.addView(scroller)
    }

    private fun rangeChip(ctx: android.content.Context, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            foreground = SettingsUi.ripple(ctx)
            setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 20).toFloat()
                setColor(if (selected) SettingsUi.accent(ctx)
                else (SettingsUi.accent(ctx) and 0x00FFFFFF) or 0x1A000000)
            }
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else SettingsUi.accent(ctx))
            setOnClickListener { onClick() }
        }

    private fun styleRangeChips() {
        for ((idx, c) in rangeChips.withIndex()) {
            val sel = idx == selectedRange
            (c.background as? android.graphics.drawable.GradientDrawable)?.apply {
                setColor(if (sel) SettingsUi.accent(c.context)
                else (SettingsUi.accent(c.context) and 0x00FFFFFF) or 0x1A000000)
            }
            c.setTextColor(if (sel) 0xFFFFFFFF.toInt() else SettingsUi.accent(c.context))
        }
    }

    // ---------------- widgets ----------------

    /** Tinted rounded pill used for the two quick actions. */
    private fun chip(ctx: android.content.Context, label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 12.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(SettingsUi.accent(ctx))
            setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 18).toFloat()
                setColor((SettingsUi.accent(ctx) and 0x00FFFFFF) or 0x1F000000)
            }
            isClickable = true
            isFocusable = true
            foreground = SettingsUi.ripple(ctx)
            setOnClickListener { onClick() }
        }

    /** v1.7.5 ROW LAYOUT (user: «галочки впритык к краю, пункты тонкие»):
     *  checkbox FIRST with real padding, then the icon tile, then the flexible
     *  title+subtitle column. 56dp minimum height, 16dp side padding — reads
     *  like a Material list row instead of the cramped old one. */
    private fun catRow(ctx: android.content.Context, cat: Cat): LinearLayout {
        val cb = CheckBox(ctx).apply {
            isChecked = cat.defaultOn
            setPadding(dp(ctx, 2), 0, 0, 0)
            setOnCheckedChangeListener { _, _ -> updateClearButton() }
        }
        checks[cat.tag] = cb
        val sub = TextView(ctx).apply {
            textSize = 12.5f
            maxLines = 2
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(ctx, 2), 0, 0)
        }
        subs[cat.tag] = sub
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            minimumHeight = dp(ctx, 56)
            isClickable = true
            isFocusable = true
            foreground = SettingsUi.ripple(ctx)
            setOnClickListener { cb.isChecked = !cb.isChecked }
        }
        row.addView(cb)
        row.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(SettingsUi.themedColor(ctx, R.attr.colorPrimaryContainer, 0x1F1A73E8.toInt()))
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply {
                marginStart = dp(ctx, 14)
                marginEnd = dp(ctx, 14)
            }
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(cat.icon)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18))
                imageTintList = android.content.res.ColorStateList.valueOf(SettingsUi.accent(ctx))
            })
        })
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                setText(cat.labelRes)
                textSize = 15.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
            })
            addView(sub)
        })
        return row
    }

    /** Full-width danger button; swaps its icon for a progress bar while clearing. */
    private fun buildClearButton(ctx: android.content.Context, col: LinearLayout) {
        val icon = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_delete)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        val label = TextView(ctx).apply {
            setText(R.string.set_clear)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(ctx, 8)
            }
        }
        val progress = android.widget.ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20))
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        val btn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12))
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(SettingsUi.themedColor(ctx, R.attr.colorError, 0xFFD93025.toInt()))
            }
            clipToOutline = true
            foreground = SettingsUi.ripple(ctx)
            addView(icon)
            addView(label)
            addView(progress)
            setOnClickListener { confirmClear(ctx) }
        }
        clearIcon = icon
        clearProgress = progress
        clearBtn = btn
        col.addView(btn)
        updateClearButton()
    }

    // ---------------- behaviour ----------------

    private fun setAll(on: Boolean) {
        for (cb in checks.values) cb.isChecked = on
        updateClearButton()
    }

    /** Danger button: normal (alpha 1, clickable) / empty (alpha 0.5, dead) / clearing (spinner). */
    private fun updateClearButton() {
        val btn = clearBtn ?: return
        val any = checks.values.any { it.isChecked }
        if (clearing) {
            btn.alpha = 1f
            btn.isClickable = false
            btn.isEnabled = false
            clearIcon?.visibility = View.INVISIBLE
            clearProgress?.visibility = View.VISIBLE
        } else {
            clearIcon?.visibility = View.VISIBLE
            clearProgress?.visibility = View.GONE
            btn.alpha = if (any) 1f else 0.5f
            btn.isClickable = any
            btn.isEnabled = any
        }
    }

    private fun confirmClear(ctx: android.content.Context) {
        val selected = checks.filter { it.value.isChecked }.keys
        if (selected.isEmpty() || clearing) return
        val names = cats.filter { it.tag in selected }.joinToString(", ") { getString(it.labelRes) }
        val rangeName = getString(ranges[selectedRange].labelRes)
        try {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.set_clear)
                .setMessage(getString(R.string.clear_confirm) + "\n\n" + names +
                    "\n\n" + getString(R.string.clear_range_label_fmt, rangeName))
                .setPositiveButton(R.string.delete) { _, _ -> runClear(ctx, selected) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (_: Throwable) {
            runClear(ctx, selected)
        }
    }

    private fun runClear(ctx: android.content.Context, selected: Set<String>) {
        if (clearing) return
        clearing = true
        updateClearButton()
        io.launch {
            val appCtx = ctx.applicationContext
            val since = System.currentTimeMillis() - ranges[selectedRange].millis
            for (tag in selected) {
                try {
                    when (tag) {
                        // v1.7.5: history + downloads respect the selected TIME RANGE
                        "history" -> if (ranges[selectedRange].millis > 0) {
                            AppDb.get(appCtx).historyDao().deleteSince(since)
                        } else AppDb.get(appCtx).historyDao().clear()
                        "cookies" -> {
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            android.webkit.CookieManager.getInstance().flush()
                            android.webkit.WebStorage.getInstance().deleteAllData()
                        }
                        // v1.7.4: NO throwaway WebView is constructed anymore — creating
                        // one could hard-crash with UnsatisfiedLinkError (an Error, not an
                        // Exception, invisible to the old catch). The app cache dirs ARE
                        // where the WebView keeps its HTTP/disk cache, so wiping them
                        // manually (best-effort — live files may be locked) is equivalent.
                        "cache" -> {
                            wipeDir(java.io.File(appCtx.cacheDir, ""))
                            wipeDir(appCtx.externalCacheDir)
                        }
                        "forms" -> { /* mapped onto the cookies/WebStorage wipe above, as before */ }
                        "passwords" -> AppDb.get(appCtx).passwordDao().clear()
                        "bookmarks" -> AppDb.get(appCtx).bookmarkDao().clear()
                        "quick" -> AppDb.get(appCtx).quickSiteDao().clear()
                        // ScriptDao has no bulk clear — delete row by row
                        // (download FILES are never touched, only the journal).
                        "scripts" -> for (s in AppDb.get(appCtx).scriptDao().all().first()) AppDb.get(appCtx).scriptDao().delete(s)
                        "downloads" -> if (ranges[selectedRange].millis > 0) {
                            AppDb.get(appCtx).downloadDao().deleteSince(since)
                        } else for (d in AppDb.get(appCtx).downloadDao().all().first()) AppDb.get(appCtx).downloadDao().delete(d)
                    }
                } catch (_: Throwable) {}
            }
            withContext(Dispatchers.Main) {
                clearing = false
                updateClearButton()
                try {
                    Toast.makeText(appCtx, R.string.clear_done, Toast.LENGTH_SHORT).show()
                    refreshSubtitles()
                } catch (_: Throwable) {}
            }
        }
    }

    /** Best-effort recursive wipe (locked files are simply skipped). */
    private fun wipeDir(dir: java.io.File?) {
        if (dir == null || !dir.exists()) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) wipeDir(f)
            try { f.delete() } catch (_: Throwable) {}
        }
    }

    /** Async per-category subtitles: entry counts (RANGE-AWARE for history and
     *  downloads — v1.7.5), saved-password count, cache size… */
    private fun refreshSubtitles() {
        val ctx = safeContext() ?: return
        io.launch {
            try {
                val db = AppDb.get(ctx)
                val since = System.currentTimeMillis() - ranges[selectedRange].millis
                val ranged = ranges[selectedRange].millis > 0
                val histN = try {
                    if (ranged) db.historyDao().countSince(since) else db.historyDao().count()
                } catch (_: Exception) { 0 }
                val pwdN = try { db.passwordDao().allOnce().size } catch (_: Exception) { 0 }
                val bmN = try { db.bookmarkDao().allOnce().size } catch (_: Exception) { 0 }
                val quickN = try { db.quickSiteDao().allOnce().size } catch (_: Exception) { 0 }
                val scriptsN = try { db.scriptDao().enabled().size } catch (_: Exception) { 0 }
                val dlN = try {
                    if (ranged) db.downloadDao().countSince(since) else db.downloadDao().all().first().size
                } catch (_: Exception) { 0 }
                val cacheBytes = try { dirSize(ctx.cacheDir) + dirSize(ctx.externalCacheDir) } catch (_: Exception) { 0L }
                withContext(Dispatchers.Main) {
                    subs["history"]?.text = ctx.getString(R.string.clear_entries_fmt, histN)
                    subs["cookies"]?.setText(R.string.clear_cookies_sub)
                    subs["cache"]?.text = ctx.getString(
                        R.string.clear_cache_mb, android.text.format.Formatter.formatFileSize(ctx, cacheBytes))
                    subs["forms"]?.setText(R.string.clear_forms_sub)
                    subs["passwords"]?.text = ctx.getString(R.string.clear_pwds_fmt, pwdN)
                    subs["bookmarks"]?.text = bmN.toString()
                    subs["quick"]?.text = ctx.getString(R.string.clear_quick_fmt, quickN)
                    subs["scripts"]?.text = ctx.getString(R.string.clear_scripts_fmt, scriptsN)
                    subs["downloads"]?.text = ctx.getString(R.string.clear_entries_fmt, dlN) +
                        " · " + ctx.getString(R.string.clear_dl_sub)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) total += if (f.isDirectory) dirSize(f) else f.length()
        return total
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

// =====================================================================
// v1.5.1: Main menu customization — order & visibility of the horizontal
// quick-action tiles and of the vertical menu rows.
// =====================================================================

class MenuPrefsFragment : Fragment() {

    companion object {
        /**
         * id → (icon, label) for the VERTICAL rows of the main menu.
         * v1.6.0: +13 rows — VPN, adblock, privacy, site settings, panels, AI, voice,
         * image search, page source, screenshot, PDF ("в вертикальных пунктах нет
         * VPN, прокси и других опций браузера").
         * v1.7.0: +"dns" (built-in DNS settings screen) after "vpn".
         * v1.7.3: +"addquick" ("add to quick access" home-screen tile) after "addbookmark".
         */
        val ROW_SPECS: Map<String, Pair<Int, Int>> = mapOf(
            "passwords" to (R.drawable.ic_key to R.string.menu_passwords),
            "scripts" to (R.drawable.ic_script to R.string.menu_scripts),
            "tasks" to (R.drawable.ic_calendar to R.string.menu_tasks),
            "workspace" to (R.drawable.ic_folder_open to R.string.panel_workspace),
            "workflows" to (R.drawable.ic_script to R.string.panel_workflows),
            "templates" to (R.drawable.ic_file to R.string.panel_templates),
            "camai" to (R.drawable.ic_camera to R.string.cam_title),
            "autofill" to (R.drawable.ic_user to R.string.menu_autofill),
            "payments" to (R.drawable.ic_desktop to R.string.menu_payments),
            "desktop" to (R.drawable.ic_desktop to R.string.menu_desktop),
            "find" to (R.drawable.ic_search to R.string.menu_find),
            "addbookmark" to (R.drawable.ic_star_add to R.string.menu_add_bookmark),
            "addquick" to (R.drawable.ic_grid to R.string.menu_add_quick),
            "translate" to (R.drawable.ic_translate to R.string.menu_translate),
            "reader" to (R.drawable.ic_reader to R.string.menu_reader),
            "addhomescreen" to (R.drawable.ic_add_homescreen to R.string.menu_add_home),
            "share" to (R.drawable.ic_share to R.string.menu_share),
            "copylink" to (R.drawable.ic_copy to R.string.menu_copy_link),
            "external" to (R.drawable.ic_external to R.string.menu_open_external),
            "clearsite" to (R.drawable.ic_delete to R.string.menu_clear_site),
            "closeall" to (R.drawable.ic_close to R.string.ts_close_all),
            "appearance" to (R.drawable.ic_palette to R.string.menu_page_appearance),
            "settings" to (R.drawable.ic_settings to R.string.menu_settings),
            "vpn" to (R.drawable.ic_vpn to R.string.menu_vpn),
            "dns" to (R.drawable.ic_globe to R.string.menu_dns),
            "adblock" to (R.drawable.ic_block to R.string.menu_adblock),
            "privacy" to (R.drawable.ic_shield to R.string.menu_privacy),
            "site" to (R.drawable.ic_website to R.string.menu_site_settings),
            "history" to (R.drawable.ic_history to R.string.menu_history),
            "downloads" to (R.drawable.ic_download to R.string.menu_downloads),
            "bookmarks" to (R.drawable.ic_bookmark to R.string.menu_bookmarks),
            "ai" to (R.drawable.ic_ai to R.string.ai_chat),
            "voice" to (R.drawable.ic_mic to R.string.voice_search),
            "imagesearch" to (R.drawable.ic_camera to R.string.image_search),
            "source" to (R.drawable.ic_script to R.string.page_source),
            "screenshot" to (R.drawable.ic_camera to R.string.menu_screenshot),
            "pdf" to (R.drawable.ic_download to R.string.menu_save_pdf),
            // v1.7.7: files app / QR scanner / site styles — pinnable menu actions
            "files" to (R.drawable.ic_folder_open to R.string.files_title),
            "qr" to (R.drawable.ic_qr to R.string.qr_title),
            "styles" to (R.drawable.ic_palette to R.string.menu_styles),
            // v1.7.8: video sniffer sheet
            "video" to (R.drawable.ic_video to R.string.sniff_menu),
            // v1.7.17: floating player (mini window with the live tab over the browser)
            "floating" to (R.drawable.ic_float_player to R.string.fl_menu),
            // v1.7.56 (user: «Сжечь всё не добавлена в меню браузера, а также дела,
            // чтобы я мог выбирать в горизонтальном или вертикальном окне»): both
            // existed as menu rows but were missing from the customization lists,
            // so neither could be placed anywhere. Offline rows were missing too.
            "burn" to (R.drawable.ic_fire to R.string.burn_title),
            "saveoffline" to (R.drawable.ic_download to R.string.menu_save_offline),
            "offline" to (R.drawable.ic_reader to R.string.set_offline)
        )

        /**
         * id → (icon, label) for the HORIZONTAL quick tiles of the main menu.
         * v1.7.0 UNIFIED: the tile row accepts ANY menu action — the full ROW_SPECS
         * set (translate, VPN, DNS, screenshot…) plus the two tab actions that only
         * ever existed as tiles. Same ids/labels/icons as MenuSheets.actionSpecs.
         */
        val TILE_SPECS: Map<String, Pair<Int, Int>> = ROW_SPECS + mapOf(
            "newtab" to (R.drawable.ic_add to R.string.menu_new_tab),
            "private" to (R.drawable.ic_private to R.string.menu_new_private)
        )

        const val DEFAULT_TILES = "newtab,private,history,downloads,bookmarks"
        const val DEFAULT_ROWS =
            "passwords,scripts,autofill,payments,desktop,find,addbookmark,addquick,translate,reader," +
                "addhomescreen,share,copylink,external,clearsite,closeall,appearance,settings," +
                "vpn,dns,screenshot,pdf"
    }

    private lateinit var tilesList: LinearLayout
    private lateinit var rowsList: LinearLayout
    private lateinit var previewCard: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_menu)
        val col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // explainer card
        col.addView(SettingsUi.gradientCard(ctx,
            getString(R.string.menu_customize_title),
            getString(R.string.menu_customize_sum_v17),
            intArrayOf(0xFF7C5CE0.toInt(), 0xFF4A3488.toInt())) { })

        // ---- LIVE PREVIEW of the resulting menu ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.menu_preview)))
        previewCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBg(ctx, dp(16), themedColor(ctx, R.attr.zBackground), themedColor(ctx, R.attr.colorOutline))
        }
        col.addView(previewCard)

        // ---- horizontal tiles ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.menu_tiles_section)))
        tilesList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tilesList)

        // ---- vertical rows ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.menu_rows_section)))
        rowsList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rowsList)

        // ---- reset ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.menu_actions_section)))
        col.addView(SettingsUi.clickRowSummary(ctx, R.drawable.ic_reload, getString(R.string.menu_reset), getString(R.string.h_menu_reset)) {
            Prefs.setMenuTilesOrder(DEFAULT_TILES)
            Prefs.setMenuRowsOrder(DEFAULT_ROWS)
            rebuildAll()
            Toast.makeText(ctx, R.string.menu_reset_done, Toast.LENGTH_SHORT).show()
        })

        rebuildAll()
        return root
    }

    // ---------------- state helpers ----------------

    /** saved order first, then any spec ids missing from the saved string appended after. */
    private fun workingOrder(saved: String, specs: Map<String, Pair<Int, Int>>): MutableList<String> {
        val order = saved.split(',').map { it.trim() }.filter { it in specs }.toMutableList()
        for (id in specs.keys) if (id !in order) order.add(id)
        return order
    }

    private fun visibleIds(saved: String, specs: Map<String, Pair<Int, Int>>): Set<String> =
        saved.split(',').map { it.trim() }.filter { it in specs }.toSet()

    // ---------------- rendering ----------------

    private fun rebuildAll() {
        rebuildTiles()
        rebuildRows()
        rebuildPreview()
    }

    private fun rebuildTiles() {
        val ctx = requireContext()
        tilesList.removeAllViews()
        val order = workingOrder(Prefs.menuTilesOrder, TILE_SPECS)
        val visible = visibleIds(Prefs.menuTilesOrder, TILE_SPECS).toMutableSet()
        buildEditorList(ctx, tilesList, TILE_SPECS, order, visible) { o, v ->
            Prefs.setMenuTilesOrder(o.filter { v.contains(it) }.joinToString(","))
        }
    }

    private fun rebuildRows() {
        val ctx = requireContext()
        rowsList.removeAllViews()
        val order = workingOrder(Prefs.menuRowsOrder, ROW_SPECS)
        val visible = visibleIds(Prefs.menuRowsOrder, ROW_SPECS).toMutableSet()
        buildEditorList(ctx, rowsList, ROW_SPECS, order, visible) { o, v ->
            Prefs.setMenuRowsOrder(o.filter { v.contains(it) }.joinToString(","))
        }
    }

    /**
     * v1.6.0: the old orderPicker was a MaterialAlertDialog + fragment-recreate
     * (openScreen) — on the user's device interactions there kicked them out of the
     * app. This is a plain in-place editor: every ↑/↓/checkbox touch re-renders ONLY
     * these rows and persists IMMEDIATELY — no dialogs, no fragment transactions,
     * nothing to crash or to "kick" anywhere.
     */
    private fun buildEditorList(
        ctx: android.content.Context,
        container: LinearLayout,
        specs: Map<String, Pair<Int, Int>>,
        order: MutableList<String>,
        visible: MutableSet<String>,
        persist: (List<String>, Set<String>) -> Unit
    ) {
        container.removeAllViews()
        for ((idx, id) in order.withIndex()) {
            val (iconRes, labelRes) = specs[id] ?: continue
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                background = roundedBg(ctx, dp(12),
                    themedColor(ctx, R.attr.colorSurface),
                    if (visible.contains(id)) themedColor(ctx, R.attr.colorOutline) else 0x33999999)
            }
            (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)

            val cb = CheckBox(ctx).apply {
                isChecked = visible.contains(id)
                setPadding(0, 0, 0, 0)
                contentDescription = getString(labelRes)
            }
            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) visible.add(id) else visible.remove(id)
                persist(order, visible)
                row.background = roundedBg(ctx, dp(12),
                    themedColor(ctx, R.attr.colorSurface),
                    if (checked) themedColor(ctx, R.attr.colorOutline) else 0x33999999)
                rebuildPreview()
            }
            row.addView(cb)

            row.addView(android.widget.ImageView(ctx).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (visible.contains(id)) themedColor(ctx, R.attr.colorPrimary)
                    else themedColor(ctx, R.attr.colorOutline))
            }.also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10) })

            row.addView(TextView(ctx).apply {
                text = getString(labelRes)
                textSize = 14f
                alpha = if (visible.contains(id)) 1f else 0.55f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(themedColor(ctx, R.attr.colorOnSurface))
            })

            fun arrow(arrowIcon: Int, descRes: Int, enabled: Boolean, move: () -> Unit) =
                android.widget.ImageButton(ctx).apply {
                    setImageResource(arrowIcon)
                    background = PanelUi.ripple(ctx)
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        themedColor(ctx, if (enabled) R.attr.colorOnSurfaceVariant else R.attr.colorOutline))
                    contentDescription = getString(descRes)
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.3f
                    setOnClickListener {
                        move()
                        persist(order, visible)
                        if (container === tilesList) rebuildTiles() else rebuildRows()
                        rebuildPreview()
                    }
                }
            row.addView(arrow(R.drawable.ic_arrow_up, R.string.menu_move_up, idx > 0) {
                if (idx > 0) order.add(idx - 1, order.removeAt(idx))
            })
            row.addView(arrow(R.drawable.ic_arrow_down, R.string.menu_move_down, idx < order.size - 1) {
                if (idx < order.size - 1) order.add(idx + 1, order.removeAt(idx))
            })
            container.addView(row)
        }
    }

    /** Mini mockup of the browser menu: the tiles (first 5 — the real menu scrolls
     *  horizontally beyond that, v1.7.0) + first visible rows. */
    private fun rebuildPreview() {
        val ctx = requireContext()
        previewCard.removeAllViews()
        val primary = themedColor(ctx, R.attr.colorPrimary)
        val onSurface = themedColor(ctx, R.attr.colorOnSurface)
        val variant = themedColor(ctx, R.attr.colorOnSurfaceVariant)

        // tiles row
        val tileRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tileOrder = Prefs.menuTilesOrder.split(',').map { it.trim() }.filter { it in TILE_SPECS }
        for (id in tileOrder.take(5)) {
            val (iconRes, labelRes) = TILE_SPECS[id] ?: continue
            tileRow.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(android.widget.ImageView(ctx).apply {
                    setImageResource(iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(primary); shape = android.graphics.drawable.GradientDrawable.OVAL
                    }
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                })
                addView(TextView(ctx).apply {
                    text = getString(labelRes)
                    textSize = 8f
                    maxLines = 1
                    gravity = android.view.Gravity.CENTER
                    setTextColor(variant)
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }
        previewCard.addView(tileRow)
        // v1.7.0: more than 5 pinned tiles — the REAL menu renders a horizontally
        // scrollable strip; the static preview caps at the first 5 + count hint.
        if (tileOrder.size > 5) {
            previewCard.addView(TextView(ctx).apply {
                text = getString(R.string.menu_more_tiles, tileOrder.size - 5)
                textSize = 10f
                maxLines = 1
                gravity = android.view.Gravity.CENTER
                setTextColor(variant)
                setPadding(0, dp(2), 0, 0)
            })
        }

        // separator
        previewCard.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, dp(8), 0, dp(8))
            }
            setBackgroundColor(themedColor(ctx, R.attr.colorOutline))
            alpha = 0.5f
        })

        // rows preview (first 6 visible)
        val rowOrder = Prefs.menuRowsOrder.split(',').map { it.trim() }.filter { it in ROW_SPECS }
        var shown = 0
        for (id in rowOrder) {
            if (shown >= 6) break
            val (iconRes, labelRes) = ROW_SPECS[id] ?: continue
            shown++
            previewCard.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
                addView(android.widget.ImageView(ctx).apply {
                    setImageResource(iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(variant)
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
                }.also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
                addView(TextView(ctx).apply {
                    text = getString(labelRes)
                    textSize = 11f
                    maxLines = 1
                    setTextColor(onSurface)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }
        if (shown == 0) {
            previewCard.addView(TextView(ctx).apply {
                text = getString(R.string.menu_empty_preview)
                textSize = 12f
                setTextColor(variant)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            })
        } else if (rowOrder.size > 6) {
            previewCard.addView(TextView(ctx).apply {
                text = getString(R.string.menu_more_items, rowOrder.size - 6)
                textSize = 11f
                setTextColor(variant)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    // ---------------- small helpers ----------------

    private fun roundedBg(ctx: android.content.Context, radius: Int, fill: Int, stroke: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }

    private fun themedColor(ctx: android.content.Context, attr: Int): Int =
        SettingsUi.themedColor(ctx, attr)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
