package com.zephyr.browser.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zephyr.browser.MainActivity
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v1.7.54: the alarm side of «Дела» — reminders and rendezvous.
 *
 * One exact alarm per pending reminder, keyed by its row id, so editing or
 * deleting a task re-targets exactly that alarm and nothing else. The
 * weekly-backup chain next door proved the shape; this differs in three ways:
 *
 *  - REPEATS re-arm from the fired time, not from "now", so a daily 09:00
 *    reminder does not drift a few seconds later every day;
 *  - EXACT alarms are a privilege on Android 12+, not a given. When the system
 *    refuses, the reminder still fires — through an inexact window — instead of
 *    silently never arriving. [canBeExact] lets the UI say which one the user
 *    is getting rather than promising precision it cannot deliver;
 *  - the notification CARRIES the task, so tapping it opens the list and
 *    "Сделано" completes it without opening anything.
 */
object Reminders {

    const val CHANNEL_ALARM = "zephyr_tasks_alarm"
    const val CHANNEL_QUIET = "zephyr_tasks"

    const val EXTRA_ID = "reminder_id"
    const val ACTION_FIRE = "com.zephyr.browser.REMINDER_FIRE"
    const val ACTION_DONE = "com.zephyr.browser.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.zephyr.browser.REMINDER_SNOOZE"

    /** Repeat modes, matching ReminderEntity.repeat. */
    const val ONCE = 0
    const val DAILY = 1
    const val WEEKDAYS = 2
    const val WEEKLY = 3
    const val MONTHLY = 4

    const val SNOOZE_MS = 10L * 60 * 1000

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------- scheduling ----------------

    private fun pending(ctx: Context, id: Long, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            // the request code must be unique per (task, action) or the three
            // intents would overwrite each other's alarm slot
            (id.toInt() * 8) + when (action) {
                ACTION_DONE -> 1
                ACTION_SNOOZE -> 2
                else -> 0
            },
            Intent(ctx, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** True when the system lets us fire to the minute. */
    fun canBeExact(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 31) true
        else (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    } catch (_: Throwable) { true }

    /** Arms (or re-arms) the alarm for [r]. Completed tasks are cancelled instead. */
    fun schedule(ctx: Context, r: ReminderEntity) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, r.id, ACTION_FIRE)
        if (r.done || r.dueAt <= 0) {
            try { am.cancel(pi) } catch (_: Throwable) {}
            return
        }
        // A repeating task whose time already passed is rolled forward instead
        // of firing a burst of missed alarms at once.
        val target =
            if (r.dueAt > System.currentTimeMillis() || r.repeat == ONCE) r.dueAt
            else nextAfterNow(r.dueAt, r.repeat)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pi)
        } catch (_: SecurityException) {
            try { am.setWindow(AlarmManager.RTC_WAKEUP, target, 5L * 60 * 1000, pi) } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(pending(ctx, id, ACTION_FIRE)) } catch (_: Throwable) {}
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(notifId(id))
        } catch (_: Throwable) {}
    }

    /** After a reboot or an app update every alarm is gone — put them all back. */
    fun rescheduleAll(ctx: Context) {
        val app = ctx.applicationContext
        io.launch {
            try {
                for (r in AppDb.get(app).reminderDao().pending()) schedule(app, r)
            } catch (_: Throwable) {}
        }
    }

    // ---------------- repeat arithmetic ----------------

    /** The next occurrence of [from] under [repeat], strictly after [from]. */
    fun nextAfter(from: Long, repeat: Int): Long {
        val c = Calendar.getInstance().apply { timeInMillis = from }
        when (repeat) {
            DAILY -> c.add(Calendar.DAY_OF_MONTH, 1)
            WEEKDAYS -> {
                do { c.add(Calendar.DAY_OF_MONTH, 1) } while (
                    c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                    c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                )
            }
            WEEKLY -> c.add(Calendar.WEEK_OF_YEAR, 1)
            MONTHLY -> c.add(Calendar.MONTH, 1)
            else -> return from
        }
        return c.timeInMillis
    }

    /** Roll [from] forward by [repeat] until it lands in the future. */
    fun nextAfterNow(from: Long, repeat: Int): Long {
        if (repeat == ONCE) return from
        var t = from
        val now = System.currentTimeMillis()
        var guard = 0
        while (t <= now && guard++ < 4000) t = nextAfter(t, repeat)
        return t
    }

    fun repeatLabel(ctx: Context, repeat: Int): String = ctx.getString(
        when (repeat) {
            DAILY -> R.string.tk_rep_daily
            WEEKDAYS -> R.string.tk_rep_weekdays
            WEEKLY -> R.string.tk_rep_weekly
            MONTHLY -> R.string.tk_rep_monthly
            else -> R.string.tk_rep_once
        }
    )

    // ---------------- formatting ----------------

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault())

    fun timeText(at: Long): String = fmt("HH:mm").format(Date(at))

    /** "Сегодня, 14:30" / "Завтра, 09:00" / "12 марта, 09:00". */
    fun whenText(ctx: Context, at: Long): String {
        val day = dayKey(at)
        val today = dayKey(System.currentTimeMillis())
        val prefix = when (day - today) {
            0L -> ctx.getString(R.string.tk_today)
            1L -> ctx.getString(R.string.tk_tomorrow)
            -1L -> ctx.getString(R.string.tk_yesterday)
            else -> fmt(if (sameYear(at)) "d MMMM" else "d MMMM yyyy").format(Date(at))
        }
        return prefix + ", " + timeText(at)
    }

    fun dayTitle(at: Long): String =
        fmt(if (sameYear(at)) "EEEE, d MMMM" else "EEEE, d MMMM yyyy").format(Date(at))
            .replaceFirstChar { it.uppercase() }

    private fun sameYear(at: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.YEAR)
        val b = Calendar.getInstance().get(Calendar.YEAR)
        return a == b
    }

    /** Days since epoch in LOCAL time — the grouping key for "one day" in the list. */
    fun dayKey(at: Long): Long = dayStart(at) / 86_400_000L

    /** Midnight of the day [at] falls on. */
    fun dayStart(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---------------- notification ----------------

    fun notifId(id: Long): Int = 7700 + (id.toInt() and 0xFFFF)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALARM, ctx.getString(R.string.tk_channel_alarm),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = ctx.getString(R.string.tk_channel_alarm_sub)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_QUIET, ctx.getString(R.string.tk_channel_quiet),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = ctx.getString(R.string.tk_channel_quiet_sub) }
            )
        } catch (_: Throwable) {}
    }

    fun notify(ctx: Context, r: ReminderEntity) {
        try {
            ensureChannels(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // v1.7.57: a reminder about a page opens THAT page; one about a
            // task opens the list. Landing on a list and having to remember
            // which link it was defeats the point of attaching one.
            val target = Intent(ctx, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (r.url.startsWith("http", true)) target.data = android.net.Uri.parse(r.url)
            else target.putExtra("open_panel", "tasks")
            val open = PendingIntent.getActivity(
                ctx, notifId(r.id), target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val b = NotificationCompat.Builder(ctx, if (r.alarm) CHANNEL_ALARM else CHANNEL_QUIET)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(r.title)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setWhen(r.dueAt)
                .setShowWhen(true)
            if (r.notes.isNotBlank()) {
                b.setContentText(r.notes)
                b.setStyle(NotificationCompat.BigTextStyle().bigText(r.notes))
            } else {
                b.setContentText(timeText(r.dueAt))
            }
            if (r.alarm) {
                b.priority = NotificationCompat.PRIORITY_HIGH
                b.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            }
            b.addAction(0, ctx.getString(R.string.tk_done), pending(ctx, r.id, ACTION_DONE))
            b.addAction(0, ctx.getString(R.string.tk_snooze), pending(ctx, r.id, ACTION_SNOOZE))
            nm.notify(notifId(r.id), b.build())
        } catch (_: Throwable) {}
    }
}

/**
 * Fires a reminder, and handles the two notification buttons.
 *
 * A repeating task is never marked done by firing — it is re-armed for its next
 * occurrence and its stored dueAt moves with it, so the list always shows when
 * it will next happen rather than when it last did.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        if (id < 0) return
        val app = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(app).reminderDao()
                val r = dao.byId(id) ?: return@launch
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                when (intent.action) {
                    Reminders.ACTION_DONE -> {
                        if (r.repeat == Reminders.ONCE) {
                            dao.update(r.copy(done = true))
                            Reminders.cancel(app, r.id)
                        } else {
                            val next = Reminders.nextAfterNow(r.dueAt, r.repeat)
                            dao.update(r.copy(dueAt = next))
                            Reminders.schedule(app, r.copy(dueAt = next))
                        }
                        try { nm.cancel(Reminders.notifId(r.id)) } catch (_: Throwable) {}
                    }
                    Reminders.ACTION_SNOOZE -> {
                        val later = System.currentTimeMillis() + Reminders.SNOOZE_MS
                        dao.update(r.copy(dueAt = later, done = false))
                        Reminders.schedule(app, r.copy(dueAt = later, done = false))
                        try { nm.cancel(Reminders.notifId(r.id)) } catch (_: Throwable) {}
                    }
                    else -> {
                        if (r.done) return@launch
                        // v1.7.57: a job does the work instead of announcing it.
                        // Same alarm, same repeats — only the payload differs.
                        if (AgentJobs.isJob(r)) AgentJobs.run(app, r)
                        else Reminders.notify(app, r)
                        if (r.repeat != Reminders.ONCE) {
                            val next = Reminders.nextAfter(r.dueAt, r.repeat)
                            dao.update(r.copy(dueAt = next))
                            Reminders.schedule(app, r.copy(dueAt = next))
                        }
                        // a one-shot stays "not done": the user decides, and no
                        // alarm is left armed for it
                    }
                }
                // v1.9.14 (user: «в виджетах не обновляется инфа и дела»).
                //
                // The widget's contract is "whoever changed the list repaints
                // it" (see tasks_widget_info.xml). This receiver is where a
                // task is most often finished — the Done and Snooze buttons on
                // the notification — and it was the one place that changed the
                // list and never said so. Tick a task off from the shade and
                // the home screen went on showing it.
                com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
            } catch (_: Throwable) {
            } finally {
                result.finish()
            }
        }
    }
}
