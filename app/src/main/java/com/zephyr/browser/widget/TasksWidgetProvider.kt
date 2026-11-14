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
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.ReminderEntity
import com.zephyr.browser.tasks.Reminders
import kotlin.concurrent.thread

/**
 * v1.7.91 (user: «у меня только один виджет… хотелось бы больше виджетов»).
 *
 * What is due, on the home screen. This is the one widget worth having for its
 * own sake: everything else the browser offers is a shortcut to opening the
 * browser, while this is read without being touched — which is the whole point
 * of a widget, and the reason reminders otherwise get forgotten.
 *
 * Four rows and a counter, not a scrolling list. A RemoteViewsService would
 * scroll, at the cost of a service and an adapter that must stay answerable for
 * a surface nobody interacts with. Four is also about what fits before the text
 * stops being readable at a glance.
 */
class TasksWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val SP = "zephyr_widget"
        private const val ROWS = 4

        fun themeFor(ctx: Context, appWidgetId: Int): WidgetThemes.Theme =
            WidgetThemes.byId(
                ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                    .getString("tasktheme_$appWidgetId", null)
            )

        fun setThemeFor(ctx: Context, appWidgetId: Int, themeId: String) {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putString("tasktheme_$appWidgetId", themeId).apply()
        }

        /**
         * Repaint every placed tasks widget.
         *
         * Called whenever the reminder list changes, because a to-do widget
         * showing yesterday's list is worse than no widget: the user stops
         * trusting it and goes back to opening the app.
         */
        fun refreshAll(ctx: Context) {
            try {
                val app = ctx.applicationContext
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, TasksWidgetProvider::class.java))
                // no early return on an empty list: the pill below must still be
                // refreshed when only a pill is on the home screen
                for (id in ids) updateWidget(app, id)
            } catch (_: Throwable) {}
            // v1.7.93: the pill shows the same list, so it is refreshed from the
            // same place. Keeping one entry point means a future call site
            // cannot update one widget and forget the other.
            TaskPillWidgetProvider.refreshAll(ctx)
        }

        fun updateWidget(ctx: Context, appWidgetId: Int) {
            val app = ctx.applicationContext
            // Room must not be touched on the main thread, and a widget update
            // arrives on it. The views are pushed once the rows are in hand.
            thread {
                val rows = try { upcoming(app) } catch (_: Throwable) { emptyList() }
                try { push(app, appWidgetId, rows) } catch (_: Throwable) {}
                watchTable(app)
            }
        }

        private val watching = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * v1.9.14: the safety net under the "whoever changes the list repaints
         * it" contract.
         *
         * Five places changed reminders and never told the widget — the
         * notification's Done button among them — so the home screen kept
         * showing work that was already finished. Those five are fixed, but the
         * contract itself is the kind that quietly breaks again: nothing fails
         * when a new call site forgets, the list just goes stale.
         *
         * Room already knows when the table changed. Listening to it means a
         * future call site cannot forget, because it is no longer asked to
         * remember. Attached from a widget repaint, so it costs nothing on a
         * phone with no widget on its home screen — the database is open at
         * that point anyway.
         */
        private fun watchTable(app: Context) {
            if (!watching.compareAndSet(false, true)) return
            try {
                AppDb.get(app).invalidationTracker.addObserver(
                    object : androidx.room.InvalidationTracker.Observer(arrayOf("reminders")) {
                        override fun onInvalidated(tables: Set<String>) {
                            thread {
                                val rows = try { upcoming(app) } catch (_: Throwable) { emptyList() }
                                try {
                                    val mgr = AppWidgetManager.getInstance(app)
                                    for (id in mgr.getAppWidgetIds(
                                        ComponentName(app, TasksWidgetProvider::class.java)
                                    )) push(app, id, rows)
                                } catch (_: Throwable) {}
                                try { TaskPillWidgetProvider.refreshAll(app) } catch (_: Throwable) {}
                            }
                        }
                    }
                )
            } catch (_: Throwable) { watching.set(false) }
        }

        /** Not-done reminders, soonest first — overdue ones included. */
        private fun upcoming(ctx: Context): List<ReminderEntity> = try {
            kotlinx.coroutines.runBlocking {
                AppDb.get(ctx).reminderDao().allOnce()
            }.filter { !it.done }.sortedBy { it.dueAt }
        } catch (_: Throwable) { emptyList() }

        private fun push(ctx: Context, appWidgetId: Int, rows: List<ReminderEntity>) {
            val theme = themeFor(ctx, appWidgetId)
            val views = RemoteViews(ctx.packageName, R.layout.widget_tasks).apply {
                setInt(R.id.twRoot, "setBackgroundResource", theme.background)
                setTextColor(R.id.twTitle, theme.foreground)
                setInt(R.id.twIcon, "setColorFilter", theme.foreground)
                setInt(R.id.twAdd, "setColorFilter", theme.foreground)
                setTextColor(R.id.twMore, theme.foreground)

                // the whole card opens the task list; the + goes straight to a
                // new one, because "add a task" is why most people look at this
                setOnClickPendingIntent(R.id.twRoot, open(ctx, appWidgetId, "panel:tasks"))
                setOnClickPendingIntent(R.id.twAdd, open(ctx, appWidgetId, "task:new"))

                val ids = intArrayOf(R.id.twRow1, R.id.twRow2, R.id.twRow3, R.id.twRow4)
                for (i in ids.indices) {
                    val r = rows.getOrNull(i)
                    if (r == null) {
                        setViewVisibility(ids[i], android.view.View.GONE)
                    } else {
                        setViewVisibility(ids[i], android.view.View.VISIBLE)
                        setTextViewText(ids[i], line(ctx, r))
                        setTextColor(ids[i], theme.foreground)
                    }
                }

                val left = rows.size - ROWS
                when {
                    rows.isEmpty() -> {
                        setViewVisibility(R.id.twMore, android.view.View.VISIBLE)
                        setTextViewText(R.id.twMore, ctx.getString(R.string.tw_empty))
                    }
                    left > 0 -> {
                        setViewVisibility(R.id.twMore, android.view.View.VISIBLE)
                        setTextViewText(R.id.twMore, ctx.getString(R.string.tw_more_fmt, left))
                    }
                    else -> setViewVisibility(R.id.twMore, android.view.View.GONE)
                }
            }
            AppWidgetManager.getInstance(ctx).updateAppWidget(appWidgetId, views)
        }

        /** "09:30 · Call the client" — the time first, because that is what is scanned. */
        private fun line(ctx: Context, r: ReminderEntity): String {
            val whenText = try { Reminders.whenText(ctx, r.dueAt) } catch (_: Throwable) { "" }
            return if (whenText.isBlank()) r.title else "$whenText · ${r.title}"
        }

        private fun open(ctx: Context, appWidgetId: Int, action: String): PendingIntent {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                this.action = "com.zephyr.browser.widget.tasks.$action"
                putExtra(MainActivity.EXTRA_ACTION, action)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                ctx, appWidgetId * 100 + action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(ctx, id)
    }

    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
        for (id in appWidgetIds) sp.remove("tasktheme_$id")
        sp.apply()
    }
}
