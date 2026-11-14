package com.zephyr.browser

import android.content.Context
import android.webkit.CookieManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.db.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v1.7.55 (user: «в настройках эту опцию предварительно настроил бы, создав ей
 * окно, где сам выставил бы галочки, что будет стираться именно при нажатии, и
 * добавить пункты ВСЁ, закладки, история и т.д.; и тумблер, спрашивать ли
 * подтверждение»).
 *
 * Burning used to wipe a fixed list — tabs, cookies, storage, cache, history —
 * and deliberately spared bookmarks, passwords and cards. That default is still
 * right, but it was not a choice: someone who wants the bookmarks gone too had
 * no way to say so, and someone in a hurry had to confirm every time.
 *
 * So the targets are a set the user owns, and the confirmation is a switch.
 * Everything destructive beyond the original five is OFF by default — turning
 * on "пароли" has to be a decision somebody made, never something they inherit
 * from an update.
 */
object BurnOptions {

    /** Stable ids, stored in Prefs. Order here is the order in the chooser. */
    val ALL = listOf(
        "tabs", "cookies", "storage", "cache", "history", "sitedata", "formdata",
        "downloads", "offline", "reader", "trash", "bookmarks", "autofill",
        "passwords", "payments", "tasks"
    )

    /** What burning does unless the user says otherwise — the trail, not the keepsakes. */
    val DEFAULTS = setOf("tabs", "cookies", "storage", "cache", "history", "sitedata")

    /** These wipe things the user deliberately saved: never on by default. */
    private val HEAVY = setOf("bookmarks", "passwords", "payments", "autofill", "tasks")

    fun label(ctx: Context, id: String): String = ctx.getString(
        when (id) {
            "tabs" -> R.string.burn_t_tabs
            "cookies" -> R.string.burn_t_cookies
            "storage" -> R.string.burn_t_storage
            "cache" -> R.string.burn_t_cache
            "history" -> R.string.burn_t_history
            "sitedata" -> R.string.burn_t_sitedata
            "formdata" -> R.string.burn_t_formdata
            "downloads" -> R.string.burn_t_downloads
            "offline" -> R.string.burn_t_offline
            "reader" -> R.string.burn_t_reader
            "trash" -> R.string.burn_t_trash
            "bookmarks" -> R.string.burn_t_bookmarks
            "autofill" -> R.string.burn_t_autofill
            "passwords" -> R.string.burn_t_passwords
            "payments" -> R.string.burn_t_payments
            else -> R.string.burn_t_tasks
        }
    )

    fun isHeavy(id: String) = id in HEAVY

    fun selected(): Set<String> = Prefs.burnTargets.ifEmpty { DEFAULTS }

    /** One-line summary for the settings row: "Вкладки, куки, история…" */
    fun summary(ctx: Context): String {
        val on = ALL.filter { it in selected() }
        if (on.isEmpty()) return ctx.getString(R.string.burn_none)
        if (on.size == ALL.size) return ctx.getString(R.string.burn_everything)
        val head = on.take(3).joinToString(", ") { label(ctx, it).lowercase() }
        return if (on.size > 3) head + ctx.getString(R.string.burn_more_fmt, on.size - 3) else head
    }

    // ---------------- the chooser ----------------

    /**
     * v1.7.57 (user: «три кнопки скроллятся в окне — сделай их в ряд, а не в
     * столбик»).
     *
     * AlertDialog stacks its three buttons vertically as soon as their labels
     * do not fit side by side, and then the whole thing scrolls with the list.
     * So the dialog carries only the list, and the buttons are our own row
     * pinned under it — always horizontal, never scrolling away.
     */
    fun chooseDialog(ctx: Context, onDone: () -> Unit) {
        val current = selected().toMutableSet()
        val labels = ALL.map {
            label(ctx, it) + (if (isHeavy(it)) " ⚠" else "")
        }.toTypedArray()
        val dp = { v: Int -> (v * ctx.resources.displayMetrics.density + 0.5f).toInt() }

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // a bounded height, so the list scrolls and the buttons below it
            // never do
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.7f).toInt()
            )
        }
        root.addView(android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(10))
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.burn_pick_title)
                textSize = 19f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.burn_pick_hint)
                textSize = 12.5f
                alpha = 0.75f
                setPadding(0, dp(6), 0, 0)
            })
        })

        val listCol = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val boxes = ArrayList<android.widget.CheckBox>(ALL.size)
        for ((i, id) in ALL.withIndex()) {
            val cb = android.widget.CheckBox(ctx).apply {
                text = labels[i]
                textSize = 15f
                isChecked = id in current
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnCheckedChangeListener { _, on ->
                    if (on) current.add(id) else current.remove(id)
                }
            }
            boxes.add(cb)
            listCol.addView(cb)
        }
        root.addView(android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listCol)
        })

        val dialog = MaterialAlertDialogBuilder(ctx).setView(root).create()

        fun btn(labelRes: Int, bold: Boolean, onTap: () -> Unit) =
            android.widget.TextView(ctx).apply {
                setText(labelRes)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD
                else android.graphics.Typeface.DEFAULT
                setPadding(dp(6), dp(12), dp(6), dp(12))
                isClickable = true
                isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onTap() }
            }

        root.addView(android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(10))
            // "всё" ticks the boxes rather than saving and closing: the user can
            // still untick the two or three they did not mean
            addView(btn(R.string.burn_pick_all, false) {
                val turnOn = current.size < ALL.size
                for ((i, id) in ALL.withIndex()) {
                    boxes[i].isChecked = turnOn
                    if (turnOn) current.add(id) else current.remove(id)
                }
            })
            addView(btn(R.string.cancel, false) { dialog.dismiss() })
            addView(btn(R.string.save, true) {
                Prefs.setBurnTargets(current)
                dialog.dismiss()
                onDone()
            })
        })

        dialog.show()
    }

    // ---------------- the act ----------------

    /**
     * Wipe everything the user selected.
     *
     * The web layer is cleared on the caller's (UI) thread because CookieManager
     * and WebStorage insist on it; our own tables go to IO. Every step is
     * individually guarded — one failing store must not leave the rest standing.
     */
    fun burn(activity: MainActivity, io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        val on = selected()
        val app = activity.applicationContext

        if ("tabs" in on) try {
            for (t in activity.tabs.tabs.toList()) activity.tabs.closeTab(t, selectReplacement = false)
        } catch (_: Throwable) {}

        if ("cookies" in on) try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {}

        if ("storage" in on) try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}

        if ("formdata" in on) try {
            android.webkit.WebViewDatabase.getInstance(activity).clearFormData()
            android.webkit.WebViewDatabase.getInstance(activity).clearHttpAuthUsernamePassword()
        } catch (_: Throwable) {}

        if ("cache" in on) try {
            val wv = android.webkit.WebView(activity)
            wv.clearCache(true)
            wv.clearHistory()
            wv.destroy()
        } catch (_: Throwable) {}

        io.launch {
            val db = try { AppDb.get(app) } catch (_: Throwable) { return@launch }
            if ("history" in on) try { db.historyDao().clear() } catch (_: Throwable) {}
            if ("sitedata" in on) try { db.siteDataDao().clear() } catch (_: Throwable) {}
            if ("cache" in on) try { app.cacheDir.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Throwable) {}
            if ("downloads" in on) try {
                for (d in db.downloadDao().allOnce()) db.downloadDao().delete(d)
            } catch (_: Throwable) {}
            if ("offline" in on) try { db.offlinePageDao().clear() } catch (_: Throwable) {}
            if ("reader" in on) try { db.readerArticleDao().clear() } catch (_: Throwable) {}
            if ("trash" in on) try { db.trashDao().clear() } catch (_: Throwable) {}
            if ("bookmarks" in on) try { db.bookmarkDao().clear() } catch (_: Throwable) {}
            // autofill has no bulk delete — the table is a handful of rows
            if ("autofill" in on) try {
                for (a in db.autofillDao().all().first()) db.autofillDao().delete(a)
            } catch (_: Throwable) {}
            if ("passwords" in on) try { db.passwordDao().clear() } catch (_: Throwable) {}
            if ("payments" in on) try { db.paymentDao().clear() } catch (_: Throwable) {}
            if ("tasks" in on) try {
                // cancel each alarm before dropping the rows, or they fire on nothing
                for (t in db.reminderDao().allOnce()) {
                    com.zephyr.browser.tasks.Reminders.cancel(app, t.id)
                    db.reminderDao().delete(t)
                }
                // v1.9.14: burning the tasks must clear them off the home
                // screen too — a widget still listing what was just wiped is
                // the one place the data was supposed to be gone from
                com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
            } catch (_: Throwable) {}
        }
    }
}
