package com.zephyr.browser.tasks

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
import com.zephyr.browser.ai.AiChatStore
import com.zephyr.browser.ai.AiClient
import com.zephyr.browser.ai.AiMessage
import com.zephyr.browser.ai.AiModels
import com.zephyr.browser.ai.AiResult
import com.zephyr.browser.db.ReminderEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.7.57 (my own proposal, accepted): a reminder that does the work instead of
 * asking you to.
 *
 * «Каждое утро в 8 собери сводку с трёх сайтов» needs three things that already
 * exist separately — an exact alarm that survives reboots, repeat rules, and an
 * assistant that can browse. This is the wire between them, and it is
 * deliberately thin: a job IS a reminder, with [ReminderEntity.source] set to
 * "job" and the prompt living in `notes`. Everything else — scheduling,
 * repeats, the boot re-arm, the calendar UI, deletion — is reused untouched.
 *
 * The answer arrives as a notification AND as a saved chat, because a long
 * answer does not fit a notification and a notification cannot be scrolled
 * back to tomorrow.
 *
 * Two limits are honest ones. The browser may not be running when a job fires,
 * so page tools will refuse — jobs are for web_search and reasoning, not page
 * automation. And there is no foreground service: Android gives a broadcast
 * receiver about ten seconds, so the request runs on a background thread with a
 * hard timeout and the result is posted whenever it lands.
 */
object AgentJobs {

    const val SOURCE = "job"
    const val CHANNEL = "zephyr_agent_jobs"

    /** How long a scheduled request may take before it is abandoned. */
    private const val TIMEOUT_SEC = 180L

    fun isJob(r: ReminderEntity) = r.source == SOURCE

    /** Build a job row; [prompt] is what the assistant will be asked. */
    fun newJob(title: String, prompt: String, at: Long, repeat: Int): ReminderEntity =
        ReminderEntity(
            title = title.trim().ifBlank { prompt.take(40) },
            notes = prompt.trim(),
            dueAt = at,
            repeat = repeat,
            alarm = false,
            source = SOURCE
        )

    /**
     * Run one job to completion, then notify.
     *
     * Blocking: called from the receiver's goAsync() coroutine, which is already
     * off the main thread.
     */
    fun run(ctx: Context, job: ReminderEntity) {
        val app = ctx.applicationContext
        val prompt = job.notes.ifBlank { job.title }
        if (prompt.isBlank()) return

        val profile = try { AiModels.active() } catch (_: Throwable) { null }
        if (profile == null) {
            notify(app, job, app.getString(R.string.job_no_model), null)
            return
        }

        val answer = StringBuilder()
        val latch = CountDownLatch(1)
        var failed: String? = null

        try {
            AiClient.chatStream(
                history = listOf(AiMessage(role = "user", text = prompt)),
                profile = profile,
                pageContext = "",
                sink = object : AiClient.StreamSink {
                    override fun onStatus(text: String) {}
                    override fun onReasoning(delta: String) {}
                    override fun onDelta(delta: String) { answer.append(delta) }
                    override fun onDone(result: AiResult) {
                        if (!result.ok) failed = result.text
                        else if (answer.isEmpty()) answer.append(result.text)
                        latch.countDown()
                    }
                    override fun isActive(): Boolean = true
                }
            )
        } catch (t: Throwable) {
            failed = t.localizedMessage ?: t.javaClass.simpleName
            latch.countDown()
        }

        if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            notify(app, job, app.getString(R.string.job_timeout), null)
            return
        }

        val text = failed ?: answer.toString().trim()
        if (text.isBlank()) {
            notify(app, job, app.getString(R.string.job_empty), null)
            return
        }

        // save as a chat, so a long answer can be read and continued properly
        val chatId = try { saveChat(app, job, prompt, text) } catch (_: Throwable) { null }
        notify(app, job, text, chatId)
    }

    private fun saveChat(ctx: Context, job: ReminderEntity, prompt: String, answer: String): String {
        val chat = AiChatStore.newChat(ctx)
        chat.title = ctx.getString(R.string.job_chat_fmt, job.title).take(60)
        chat.messages.add(AiMessage(role = "user", text = prompt))
        chat.messages.add(AiMessage(role = "assistant", text = answer))
        AiChatStore.touch(ctx, chat)
        AiChatStore.persist(ctx)
        return chat.id
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, ctx.getString(R.string.job_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = ctx.getString(R.string.job_channel_sub) }
            )
        } catch (_: Throwable) {}
    }

    private fun notify(ctx: Context, job: ReminderEntity, text: String, chatId: String?) {
        try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val open = PendingIntent.getActivity(
                ctx, Reminders.notifId(job.id),
                Intent(ctx, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra("open_ai_chat", chatId ?: "")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_ai)
                .setContentTitle(job.title)
                .setContentText(text.lineSequence().firstOrNull()?.take(120) ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
                .setContentIntent(open)
                .setAutoCancel(true)
            nm.notify(Reminders.notifId(job.id), b.build())
        } catch (_: Throwable) {}
    }
}
