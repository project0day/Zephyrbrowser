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
 * v1.7.93 (user: «дела тоже можно как пилюлю… селва вправо бегать по желанию»).
 *
 * One task at a time, in the same pill as the search widget, with arrows to
 * step through what is due.
 *
 * Stepping rather than scrolling is not a compromise. RemoteViews cannot scroll
 * without a `RemoteViewsService` — a service, an adapter and a process that has
 * to stay answerable — and with three or four items due, stepping is what
 * people do anyway. It also keeps the widget one row tall, which is the whole
 * point of asking for a pill.
 *
 * The position is held per widget, so two pills on the same screen can show
 * different tasks, and it is clamped on every repaint: the list changes under
 * it constantly, and an index pointing past the end must show the last task,
 * never an empty pill.
 */
class TaskPillWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val SP = "zephyr_widget"
        private const val ACTION_STEP = "com.zephyr.browser.widget.PILL_STEP"
        private const val EXTRA_DELTA = "delta"

        fun themeFor(ctx: Context, id: Int): WidgetThemes.Theme =
            WidgetThemes.byId(
                ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                    .getString("pilltheme_$id", null)
            )

        fun setThemeFor(ctx: Context, id: Int, themeId: String) {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putString("pilltheme_$id", themeId).apply()
        }

        private fun indexOf(ctx: Context, id: Int): Int =
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("pillidx_$id", 0)

        private fun setIndex(ctx: Context, id: Int, v: Int) {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putInt("pillidx_$id", v).apply()
        }

        fun refreshAll(ctx: Context) {
            try {
                val app = ctx.applicationContext
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(app, TaskPillWidgetProvider::class.java))
                for (id in ids) updateWidget(app, id)
            } catch (_: Throwable) {}
        }

        fun updateWidget(ctx: Context, appWidgetId: Int) {
            val app = ctx.applicationContext
            // Room is off-limits on the main thread, and a widget update lands
            // there; the views are pushed once the rows are in hand.
            thread {
                val rows = try { upcoming(app) } catch (_: Throwable) { emptyList() }
                try { push(app, appWidgetId, rows) } catch (_: Throwable) {}
            }
        }

        private fun upcoming(ctx: Context): List<ReminderEntity> = try {
            kotlinx.coroutines.runBlocking { AppDb.get(ctx).reminderDao().allOnce() }
                .filter { !it.done }.sortedBy { it.dueAt }
        } catch (_: Throwable) { emptyList() }

        private fun push(ctx: Context, id: Int, rows: List<ReminderEntity>) {
            val theme = themeFor(ctx, id)
            // clamp here, not on the button: the list moves on its own as
            // reminders are added, completed and fired
            val idx = if (rows.isEmpty()) 0 else indexOf(ctx, id).coerceIn(0, rows.size - 1)
            if (rows.isNotEmpty() && idx != indexOf(ctx, id)) setIndex(ctx, id, idx)
            val current = rows.getOrNull(idx)

            val views = RemoteViews(ctx.packageName, R.layout.widget_task_pill).apply {
                setInt(R.id.tpRoot, "setBackgroundResource", theme.background)
                setTextColor(R.id.tpText, theme.foreground)
                for (icon in intArrayOf(R.id.tpIcon, R.id.tpPrev, R.id.tpNext)) {
                    setInt(icon, "setColorFilter", theme.foreground)
                }

                setTextViewText(
                    R.id.tpText,
                    if (current == null) ctx.getString(R.string.tw_empty)
                    else label(ctx, current, idx, rows.size)
                )

                // the text opens the list; with nothing due it opens the editor,
                // because an empty pill you cannot act on is just decoration
                setOnClickPendingIntent(
                    R.id.tpRoot,
                    open(ctx, id, if (current == null) "task:new" else "panel:tasks")
                )

                val many = rows.size > 1
                setViewVisibility(R.id.tpPrev, if (many) android.view.View.VISIBLE else android.view.View.GONE)
                setViewVisibility(R.id.tpNext, if (many) android.view.View.VISIBLE else android.view.View.GONE)
                if (many) {
                    setOnClickPendingIntent(R.id.tpPrev, step(ctx, id, -1))
                    setOnClickPendingIntent(R.id.tpNext, step(ctx, id, +1))
                }
            }
            AppWidgetManager.getInstance(ctx).updateAppWidget(id, views)
        }

        /** "2/5 · 09:30 · Call the client" — position first, so stepping reads. */
        private fun label(ctx: Context, r: ReminderEntity, idx: Int, total: Int): String {
            val whenText = try { Reminders.whenText(ctx, r.dueAt) } catch (_: Throwable) { "" }
            val head = if (total > 1) "${idx + 1}/$total · " else ""
            return head + (if (whenText.isBlank()) r.title else "$whenText · ${r.title}")
        }

        private fun step(ctx: Context, id: Int, delta: Int): PendingIntent {
            val i = Intent(ctx, TaskPillWidgetProvider::class.java).apply {
                action = ACTION_STEP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(EXTRA_DELTA, delta)
            }
            return PendingIntent.getBroadcast(
                ctx, id * 10 + (if (delta > 0) 1 else 2), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun open(ctx: Context, id: Int, action: String): PendingIntent {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                this.action = "com.zephyr.browser.widget.pill.$action"
                putExtra(MainActivity.EXTRA_ACTION, action)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                ctx, id * 100 + action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_STEP) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val delta = intent.getIntExtra(EXTRA_DELTA, 1)
                val app = ctx.applicationContext
                thread {
                    val rows = try { upcoming(app) } catch (_: Throwable) { emptyList() }
                    if (rows.isNotEmpty()) {
                        // wraps at both ends: on a pill, running out of arrow is
                        // more annoying than starting over
                        val next = (indexOf(app, id) + delta).let {
                            ((it % rows.size) + rows.size) % rows.size
                        }
                        setIndex(app, id, next)
                    }
                    try { push(app, id, rows) } catch (_: Throwable) {}
                }
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(ctx, id)
    }

    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
        for (id in appWidgetIds) { sp.remove("pilltheme_$id"); sp.remove("pillidx_$id") }
        sp.apply()
    }
}
