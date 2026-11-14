package com.zephyr.browser.settings

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v1.7.6 (user: «Автобэкап раз в неделю в Downloads + напоминание»).
 *
 * Weekly self-perpetuating one-shot alarm (setInexactRepeating only supports
 * day-granularity constants): [BackupReceiver] fires, writes the backup into the
 * public Downloads/Zephyr folder (BackupManager.exportAuto), posts the
 * "backup created" notification (the reminder), then schedules itself +7 days.
 * [BootReceiver] re-arms the chain after a reboot.
 */
object BackupScheduler {
    private const val RC = 9017
    const val WEEK_MS = 7L * 24 * 3600 * 1000

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, RC,
        Intent(ctx, BackupReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Arms the next weekly run [delayMs] from now. */
    fun schedule(ctx: Context, delayMs: Long = WEEK_MS) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        val at = System.currentTimeMillis() + delayMs
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            try { am.setWindow(AlarmManager.RTC_WAKEUP, at, AlarmManager.INTERVAL_HOUR, pi) }
            catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(pending(ctx)) } catch (_: Exception) {}
    }

    /** After a reboot: next run = last backup + 7 days (or tomorrow if never). */
    fun rescheduleFromLastBackup(ctx: Context) {
        if (!Prefs.autoBackup) return
        val next = Prefs.lastBackupAt + WEEK_MS
        val delay = (next - System.currentTimeMillis())
            .coerceIn(AlarmManager.INTERVAL_HOUR, WEEK_MS)
        schedule(ctx, delay)
    }
}

/** Fires weekly: backup → notification → next week. */
class BackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.autoBackup) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val name = BackupManager.exportAuto(context)
                notifyResult(context, name)
            } finally {
                BackupScheduler.schedule(context)   // perpetuate the weekly chain
                result.finish()
            }
        }
    }

    private fun notifyResult(context: Context, name: String?) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(
                    "zephyr_backup", context.getString(R.string.set_backup_screen),
                    NotificationManager.IMPORTANCE_DEFAULT))
            }
            val b = NotificationCompat.Builder(context, "zephyr_backup")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(if (name != null) R.string.bk_auto_done else R.string.bk_auto_failed))
                .setAutoCancel(true)
            if (name != null) b.setContentText(name)
            nm.notify(9017, b.build())
        } catch (_: Exception) {}
    }
}

/** Re-arms the weekly auto-backup after a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BackupScheduler.rescheduleFromLastBackup(context)
            // v1.7.54: alarms do not survive a reboot — put every pending task back
            com.zephyr.browser.tasks.Reminders.rescheduleAll(context)
        }
    }
}
