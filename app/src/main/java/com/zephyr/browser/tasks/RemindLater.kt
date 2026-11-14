package com.zephyr.browser.tasks

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * v1.7.57: "remind me about this page later", from a long press on a link or
 * from the page menu.
 *
 * Deliberately not the full task editor. The whole value is that it takes one
 * tap — an interesting link found mid-scroll is closed, not filed — so it
 * offers four times and nothing else. Anything more detailed is a task, and the
 * task screen already exists for that.
 */
object RemindLater {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ask(activity: Activity, url: String, pageTitle: String) {
        if (!url.startsWith("http", true)) return
        val ctx = activity
        val labels = arrayOf(
            ctx.getString(R.string.rl_1h),
            ctx.getString(R.string.rl_evening),
            ctx.getString(R.string.rl_tomorrow),
            ctx.getString(R.string.rl_week)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ctx_remind)
            .setItems(labels) { _, which ->
                save(activity, url, pageTitle, when (which) {
                    0 -> System.currentTimeMillis() + 3_600_000
                    1 -> atHour(19)
                    2 -> atHour(9, plusDays = 1)
                    else -> atHour(9, plusDays = 7)
                })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** [hour] today, or on a later day; never in the past. */
    private fun atHour(hour: Int, plusDays: Int = 0): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (plusDays > 0) add(Calendar.DAY_OF_MONTH, plusDays)
        }
        // "сегодня вечером" asked for after 19:00 means tomorrow evening
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun save(activity: Activity, url: String, pageTitle: String, at: Long) {
        val app = activity.applicationContext
        // the title is what the user will read in the notification, so prefer
        // the page's own over a bare address
        val title = pageTitle.trim().ifBlank {
            try { android.net.Uri.parse(url).host ?: url } catch (_: Throwable) { url }
        }
        val row = ReminderEntity(
            title = title.take(120),
            notes = "",
            dueAt = at,
            url = url,
            source = "link"
        )
        io.launch {
            var ok = true
            try {
                val id = AppDb.get(app).reminderDao().insert(row)
                com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
                Reminders.schedule(app, row.copy(id = id))
            } catch (_: Throwable) { ok = false }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    activity,
                    if (ok) activity.getString(R.string.rl_saved_fmt, Reminders.whenText(activity, at))
                    else activity.getString(R.string.rl_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
