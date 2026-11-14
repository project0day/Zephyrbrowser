package com.zephyr.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v1.8.2 (user: «также в уведомление надо добавить отменить»).
 *
 * The Cancel button on a download's own notification.
 *
 * A broadcast rather than an activity: stopping a transfer should not open the
 * browser. The user is already looking at the one thing they wanted to act on,
 * and making them watch an app start to abandon a download is the opposite of
 * what the button is for.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CANCEL = "com.zephyr.browser.DL_CANCEL"
        const val EXTRA_ID = "entryId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0L) return
        try {
            // cancel() stops the transfer, drops the partial file and marks the
            // row cancelled; the notification is taken down with it, because a
            // progress bar for something that stopped is a lie
            DownloadEngine.init(context.applicationContext)
            DownloadEngine.cancel(id)
        } catch (_: Throwable) {}
    }
}
