package com.zephyr.browser.widget

import android.content.Context
import com.zephyr.browser.R

/**
 * v1.7.82: colour themes for the home-screen widget.
 *
 * A widget is drawn by the launcher, in the launcher's process, from a
 * RemoteViews description — so it cannot be styled the way the rest of the app
 * is. A shape drawable's fill cannot be changed at runtime through RemoteViews;
 * only whole resources can be swapped, and only a short list of view methods
 * may be called. That is why each theme is a pre-built drawable plus two plain
 * colour integers, rather than anything computed.
 *
 * The choice is stored per widget, not per app: two widgets on the same home
 * screen can legitimately want different colours.
 */
object WidgetThemes {

    data class Theme(
        val id: String,
        val nameRes: Int,
        val background: Int,
        /** Label colour, and the tint applied to the four action icons. */
        val foreground: Int,
        /** The swatch drawn in the picker — the fill the user will see. */
        val swatch: Int
    )

    val ALL = listOf(
        Theme("dark", R.string.wg_theme_dark, R.drawable.widget_pill_dark,
            0xFFFFFFFF.toInt(), 0xFF212121.toInt()),
        Theme("light", R.string.wg_theme_light, R.drawable.widget_pill_light,
            0xFF1B1B1F.toInt(), 0xFFFFFFFF.toInt()),
        Theme("black", R.string.wg_theme_black, R.drawable.widget_pill_black,
            0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
        Theme("blue", R.string.wg_theme_blue, R.drawable.widget_pill_blue,
            0xFFFFFFFF.toInt(), 0xFF1A73E8.toInt()),
        Theme("violet", R.string.wg_theme_violet, R.drawable.widget_pill_violet,
            0xFFFFFFFF.toInt(), 0xFF6B4BD6.toInt()),
        Theme("glass", R.string.wg_theme_glass, R.drawable.widget_pill_glass,
            0xFFFFFFFF.toInt(), 0x66FFFFFF),
        // v1.7.91 (user: «только тёмный, хотелось бы больше цветов»)
        Theme("ocean", R.string.wg_theme_ocean, R.drawable.widget_pill_ocean,
            0xFFFFFFFF.toInt(), 0xFF1B3A6B.toInt()),
        Theme("teal", R.string.wg_theme_teal, R.drawable.widget_pill_teal,
            0xFFFFFFFF.toInt(), 0xFF0F6E6E.toInt()),
        Theme("green", R.string.wg_theme_green, R.drawable.widget_pill_green,
            0xFFFFFFFF.toInt(), 0xFF157347.toInt()),
        Theme("plum", R.string.wg_theme_plum, R.drawable.widget_pill_plum,
            0xFFFFFFFF.toInt(), 0xFF532B6B.toInt()),
        Theme("rose", R.string.wg_theme_rose, R.drawable.widget_pill_rose,
            0xFFFFFFFF.toInt(), 0xFFB03060.toInt()),
        Theme("amber", R.string.wg_theme_amber, R.drawable.widget_pill_amber,
            0xFFFFFFFF.toInt(), 0xFFA9761A.toInt()),
        Theme("graphite", R.string.wg_theme_graphite, R.drawable.widget_pill_graphite,
            0xFFFFFFFF.toInt(), 0xFF3A3F47.toInt()),
        // the only light-on-dark-text entry besides "light": a warm paper tone
        Theme("sand", R.string.wg_theme_sand, R.drawable.widget_pill_sand,
            0xFF3A3226.toInt(), 0xFFEFE7DA.toInt()),
        // v1.7.92 (user: «добавить цвета и прозрачности на выбор»). Steps of
        // translucency rather than a slider: RemoteViews cannot set alpha on a
        // shape at runtime, so every level has to exist as its own drawable,
        // and four well-chosen ones cover what a slider would.
        Theme("clear25", R.string.wg_theme_clear25, R.drawable.widget_pill_clear25,
            0xFFFFFFFF.toInt(), 0x40000000),
        Theme("clear50", R.string.wg_theme_clear50, R.drawable.widget_pill_clear50,
            0xFFFFFFFF.toInt(), 0x80000000.toInt()),
        Theme("clear75", R.string.wg_theme_clear75, R.drawable.widget_pill_clear75,
            0xFFFFFFFF.toInt(), 0xBF000000.toInt()),
        Theme("frost", R.string.wg_theme_frost, R.drawable.widget_pill_frost,
            0xFF1B1B1F.toInt(), 0x59FFFFFF),
        // v1.7.95 (user: «во всех виджетах добавил бы больше цветов»)
        Theme("indigo", R.string.wg_theme_indigo, R.drawable.widget_pill_indigo,
            0xFFFFFFFF.toInt(), 0xFF2E2F7A.toInt()),
        Theme("steel", R.string.wg_theme_steel, R.drawable.widget_pill_steel,
            0xFFFFFFFF.toInt(), 0xFF26323C.toInt()),
        Theme("slate", R.string.wg_theme_slate, R.drawable.widget_pill_slate,
            0xFFFFFFFF.toInt(), 0xFF465A6B.toInt()),
        Theme("forest", R.string.wg_theme_forest, R.drawable.widget_pill_forest,
            0xFFFFFFFF.toInt(), 0xFF1C4A2B.toInt()),
        Theme("moss", R.string.wg_theme_moss, R.drawable.widget_pill_moss,
            0xFFFFFFFF.toInt(), 0xFF4A5A24.toInt()),
        Theme("crimson", R.string.wg_theme_crimson, R.drawable.widget_pill_crimson,
            0xFFFFFFFF.toInt(), 0xFFA01B2E.toInt()),
        Theme("wine", R.string.wg_theme_wine, R.drawable.widget_pill_wine,
            0xFFFFFFFF.toInt(), 0xFF591330.toInt()),
        Theme("copper", R.string.wg_theme_copper, R.drawable.widget_pill_copper,
            0xFFFFFFFF.toInt(), 0xFF8A4B22.toInt()),
        // the two pale ones take dark text, like "sand" and "light"
        Theme("mint", R.string.wg_theme_mint, R.drawable.widget_pill_mint,
            0xFF1E3A2B.toInt(), 0xFFD7F0E4.toInt()),
        Theme("blush", R.string.wg_theme_blush, R.drawable.widget_pill_blush,
            0xFF3A2430.toInt(), 0xFFF3DCE4.toInt())
    )

    val DEFAULT = ALL[0]

    fun byId(id: String?): Theme = ALL.firstOrNull { it.id == id } ?: DEFAULT

    fun nameOf(ctx: Context, theme: Theme): String =
        try { ctx.getString(theme.nameRes) } catch (_: Exception) { theme.id }
}
