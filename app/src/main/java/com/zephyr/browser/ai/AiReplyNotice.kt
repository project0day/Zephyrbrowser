package com.zephyr.browser.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zephyr.browser.MainActivity
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/**
 * v1.7.80 (user: «надо, чтобы уведомление Push вышло, когда я свернул приложение
 * и ушёл в другое, а ИИ мне ответил»).
 *
 * Inside the browser the answer announces itself — the bubble badges, the chat
 * scrolls. Outside it, nothing did: [AiKeepAliveService] keeps the request
 * alive while the app is in the background, so the answer really does arrive,
 * but it arrived silently into a window nobody was looking at.
 *
 * This is the other half of that: when the answer lands and the app is not on
 * screen, it is announced the way anything else on the phone would announce
 * itself, with the first lines of the reply readable without opening anything.
 *
 * Off by default? No — an answer you asked for is worth telling you about. But
 * it has a switch, like every other thing the assistant does.
 */
object AiReplyNotice {

    private const val CHANNEL = "zephyr_ai_reply"
    private const val NOTIF_ID = 8322

    /**
     * Post the "your answer is ready" notification.
     *
     * Deliberately silent when the app is on screen: the caller checks that,
     * because "on screen" is a property of the Activity, not of this object.
     */
    fun post(ctx: Context, reply: String) {
        if (!Prefs.aiReplyNotify) return
        if (reply.isBlank()) return
        if (!allowed(ctx)) return
        try {
            val app = ctx.applicationContext
            ensureChannel(app)

            val open = PendingIntent.getActivity(
                app, NOTIF_ID,
                Intent(app, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_ACTION, "ai")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Strip the markup rather than show it: a notification renders none
            // of it, and raw ** and ``` in the preview read as corruption.
            val preview = plain(reply)

            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_ai)
                .setContentTitle(app.getString(R.string.ai_notify_title))
                .setContentText(preview.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(600)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, n)
        } catch (_: Throwable) {
            // a notification that cannot be posted must never break the answer
        }
    }

    /** Called when the user is looking at the assistant again. */
    fun clear(ctx: Context) {
        try {
            (ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager).cancel(NOTIF_ID)
        } catch (_: Throwable) {}
    }

    private fun allowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, ctx.getString(R.string.ai_notify_channel),
                    // HIGH, unlike the quiet "working…" channel: this one is the
                    // event the user is waiting for, and it is worth a buzz
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = ctx.getString(R.string.ai_notify_channel_sub)
                }
            )
        } catch (_: Throwable) {}
    }

    /** Markdown down to something a one-line preview can show honestly. */
    private fun plain(s: String): String = s
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
