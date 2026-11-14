package com.zephyr.browser.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zephyr.browser.MainActivity
import com.zephyr.browser.R
import com.zephyr.browser.SearchEngines

/** Home-screen search widget: engine label + search / image / voice / AI quick actions. */
class SearchWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val SP = "zephyr_widget"
        fun engineFor(context: Context, appWidgetId: Int): String {
            val id = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .getString("engine_$appWidgetId", "google") ?: "google"
            return if (SearchEngines.builtIn.any { it.id == id }) id else "google"
        }

        /** v1.7.82: the colour theme chosen for this particular widget. */
        fun themeFor(context: Context, appWidgetId: Int): WidgetThemes.Theme =
            WidgetThemes.byId(
                context.getSharedPreferences(SP, Context.MODE_PRIVATE)
                    .getString("theme_$appWidgetId", null)
            )

        fun setThemeFor(context: Context, appWidgetId: Int, themeId: String) {
            context.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putString("theme_$appWidgetId", themeId).apply()
        }

        fun updateWidget(context: Context, appWidgetId: Int) {
            val engine = SearchEngines.byId(engineFor(context, appWidgetId))
            val name = try { context.getString(engine.nameRes) } catch (_: Exception) { "" }
            val theme = themeFor(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_search).apply {
                // Only a handful of view methods may be driven from here, and
                // these are the ones that are: swap the whole background
                // resource, set a text colour, tint each icon.
                setInt(R.id.wgRoot, "setBackgroundResource", theme.background)
                setTextColor(R.id.wgSearchText, theme.foreground)
                for (icon in intArrayOf(
                    R.id.wgSearchIcon, R.id.wgCameraIcon, R.id.wgMicIcon, R.id.wgAiIcon
                )) {
                    setInt(icon, "setColorFilter", theme.foreground)
                }
                setTextViewText(R.id.wgSearchText, context.getString(R.string.wg_search_hint, name))
                setOnClickPendingIntent(R.id.wgRoot, pi(context, appWidgetId, "search", engine.id))
                setOnClickPendingIntent(R.id.wgSearchIcon, pi(context, appWidgetId, "search", engine.id))
                setOnClickPendingIntent(R.id.wgSearchText, pi(context, appWidgetId, "search", engine.id))
                setOnClickPendingIntent(R.id.wgCameraIcon, pi(context, appWidgetId, "image", engine.id))
                setOnClickPendingIntent(R.id.wgMicIcon, pi(context, appWidgetId, "voice", engine.id))
                setOnClickPendingIntent(R.id.wgAiIcon, pi(context, appWidgetId, "ai", engine.id))
            }
            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
        }

        private fun pi(context: Context, appWidgetId: Int, action: String, engine: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                this.action = "com.zephyr.browser.widget.action.$action"
                putExtra(MainActivity.EXTRA_ACTION, action)
                putExtra(MainActivity.EXTRA_ENGINE, engine)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                context, appWidgetId * 10 + action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .remove("engine_$id")
                .remove("theme_$id")
                .apply()
        }
    }
}
