package com.zephyr.browser.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zephyr.browser.MainActivity
import com.zephyr.browser.R

/**
 * v1.7.68 (user: «я не могу смотреть долго, когда ИИ думает — сворачиваю
 * приложение, и работа прерывается; надо, чтобы помощник работал всегда, пока
 * не закончит»).
 *
 * A backgrounded app is not killed immediately, but Android is free to freeze
 * its threads and drop its sockets whenever it needs the memory — and a long
 * answer is exactly when that happens. Nothing in the app told the system that
 * something was still in flight.
 *
 * A foreground service does. While a request is running there is a quiet
 * notification saying so, and the process keeps its CPU and its connection
 * until the answer lands. It stops the moment the request finishes, so it never
 * sits in the status bar for longer than the work it represents.
 *
 * Reference-counted, because the swarm runs several requests at once and the
 * last one to finish must be the one that stops it.
 */
class AiKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(this))
        // not sticky: if the process dies the request died with it, and a
        // restarted service would only show a notification for nothing
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "zephyr_ai_work"
        private const val NOTIF_ID = 8321

        private val lock = Any()
        private var running = 0

        /** Call when a request starts. Safe from any thread. */
        fun begin(ctx: Context) {
            synchronized(lock) {
                running++
                if (running > 1) return
            }
            try {
                val app = ctx.applicationContext
                ensureChannel(app)
                val i = Intent(app, AiKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            } catch (_: Throwable) {
                // a refused start must not take the request down with it
                synchronized(lock) { if (running > 0) running-- }
            }
        }

        /** Call when a request ends, whatever the outcome. */
        fun end(ctx: Context) {
            synchronized(lock) {
                if (running > 0) running--
                if (running > 0) return
            }
            try {
                val app = ctx.applicationContext
                app.stopService(Intent(app, AiKeepAliveService::class.java))
            } catch (_: Throwable) {}
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, ctx.getString(R.string.ai_work_channel),
                        // LOW: it must be visible, not attention-seeking
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = ctx.getString(R.string.ai_work_channel_sub)
                        setShowBadge(false)
                    }
                )
            } catch (_: Throwable) {}
        }

        fun buildNotification(ctx: Context): android.app.Notification {
            ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, NOTIF_ID,
                Intent(ctx, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_ACTION, "ai")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_ai)
                .setContentTitle(ctx.getString(R.string.ai_work_title))
                .setContentText(ctx.getString(R.string.ai_work_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()
        }
    }
}
