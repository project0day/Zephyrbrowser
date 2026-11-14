package com.zephyr.browser.panels

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.FaviconLoader
import com.zephyr.browser.MainActivity
import com.zephyr.browser.R
import com.zephyr.browser.Urls
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.BookmarkEntity
import com.zephyr.browser.db.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * History panel (v1.7.3 premium v2):
 * - v1.7.3 FIX (user: «в истории при нажатии на три кнопки справа окно появляется
 *   слева снизу!»): the entry PopupMenu used the WHOLE list as its anchor, so it
 *   popped at the bottom-left corner. It is now anchored on the ⋮ button itself
 *   (long-press anchors on the row) with END gravity.
 * - v1.7.3 premium: 44dp favicon tiles, bold titles + gray single-line URLs, a
 *   time/date column above the ⋮ button («14:32» for today/yesterday, «13 сен»
 *   otherwise), period headers with per-period counters («Сегодня · 12»), a rich
 *   entry menu (open / new tab / bookmark / copy link / delete / delete domain),
 *   a real empty state and a search field with a leading search icon.
 * - kept from v1.4.1: human period grouping (Сегодня / Вчера / За 7 дней / За 30
 *   дней / Ранее), per-period deletion, clear-all, swipe-to-delete, live search.
 */
class HistoryPanelFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var query = ""
    private var all: List<HistoryEntity> = emptyList()

    private lateinit var search: EditText
    private lateinit var list: LinearLayout

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }

        // search + clear-all
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(10), dp(6))
        }
        search = EditText(ctx).apply {
            hint = getString(R.string.hist_search_hint)
            textSize = 14f
            background = PanelUi.searchFieldBg(ctx)
            setSingleLine(true)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            // v1.7.3: leading search icon inside the field (gray-tinted compound drawable).
            try {
                val icon = ctx.getDrawable(R.drawable.ic_search)?.mutate()
                icon?.setTint(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            } catch (_: Exception) {}
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun afterTextChanged(p0: android.text.Editable?) {
                    query = p0?.toString() ?: ""; rebuild()
                }
            })
        }
        top.addView(search)
        top.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_delete)
            background = PanelUi.ripple(ctx)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            imageTintList = android.content.res.ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(R.string.hist_clear_all)
            setOnClickListener { confirmClearAll() }
        })
        root.addView(top)

        val scroll = android.widget.ScrollView(ctx)
        list = PanelUi.listColumn(ctx)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload()
    }

    private fun reload() {
        val ctx = context ?: return
        io.launch {
            all = try {
                AppDb.get(ctx.applicationContext).historyDao().search("%")
            } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { rebuild() }
        }
    }

    // ------------------------------------------------------------------
    // period bucketing
    // ------------------------------------------------------------------

    private fun periodOf(ts: Long): Int {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - 24 * 3600_000L
        val weekStart = todayStart - 6 * 24 * 3600_000L
        val monthStart = todayStart - 29 * 24 * 3600_000L
        return when {
            ts >= todayStart -> 0
            ts >= yesterdayStart -> 1
            ts >= weekStart -> 2
            ts >= monthStart -> 3
            else -> 4
        }
    }

    private fun periodTitle(period: Int): String = getString(when (period) {
        0 -> R.string.hist_today
        1 -> R.string.hist_yesterday
        2 -> R.string.hist_week
        3 -> R.string.hist_month
        else -> R.string.hist_older
    })

    /** [from, to] deletion window for a period; null = clear everything older. */
    private fun periodRange(period: Int): Pair<Long, Long>? {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        val todayEnd = cal.timeInMillis
        return when (period) {
            0 -> 0L to todayEnd
            1 -> 0L to (todayEnd - 24 * 3600_000L)
            2 -> 0L to (todayEnd - 2 * 24 * 3600_000L)
            3 -> 0L to (todayEnd - 7 * 24 * 3600_000L)
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // rendering
    // ------------------------------------------------------------------

    private fun rebuild() {
        val ctx = context ?: return
        list.removeAllViews()
        val filtered = if (query.isBlank()) all else all.filter {
            it.title.contains(query, true) || it.url.contains(query, true)
        }
        if (filtered.isEmpty()) {
            // v1.7.3: real empty state (tinted icon circle + title + gray subtext).
            // While searching, the old plain "Пусто" stays — the list filtered to nothing.
            if (query.isBlank()) list.addView(emptyState(ctx))
            else list.addView(PanelUi.emptyView(ctx, R.string.empty_list))
            return
        }

        // v1.7.3: per-period entry counters for the «Сегодня · N» headers.
        val counts = HashMap<Int, Int>()
        for (h in filtered) {
            val p = periodOf(h.visitedAt)
            counts[p] = (counts[p] ?: 0) + 1
        }

        // v1.7.3: light fade-in for freshly (re)built cards — only outside active
        // search (per-keystroke rebuilds would flicker) and only for sane list sizes,
        // so a 2000-entry history never spawns 2000 animators at once.
        val animate = query.isBlank() && filtered.size <= 60

        var lastPeriod = -1
        for (h in filtered) {
            val period = periodOf(h.visitedAt)
            if (period != lastPeriod) {
                lastPeriod = period
                list.addView(periodHeader(ctx, period, counts[period] ?: 0))
            }
            list.addView(entryCard(ctx, h, period))
        }
        if (animate) {
            for (idx in 0 until list.childCount) {
                val child = list.getChildAt(idx)
                child.alpha = 0f
                child.animate().alpha(1f).setDuration(150).start()
            }
        }
    }

    /** Premium v2 entry card: favicon tile + bold title + gray URL + time column + ⋮. */
    private fun entryCard(ctx: android.content.Context, h: HistoryEntity, period: Int): View {
        // 44dp favicon tile, 12dp corners (v1.5.1 tile look, kept)
        val tile = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt()))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(14)
            }
            clipToOutline = true
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }.also { it.setImageBitmap(FaviconLoader.load(ctx, h.url, h.title)) })
        }

        // ⋮ button — the ANCHOR of the entry menu (v1.7.3 popup fix)
        val moreBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            background = PanelUi.ripple(ctx)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            imageTintList = android.content.res.ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(R.string.menu)
            setOnClickListener { v -> entryMenu(h, v) }
        }

        // right column: time («14:32» for today/yesterday, «13 сен» otherwise) above ⋮
        val timeText = TextView(ctx).apply {
            text = if (period <= 1) timeFmt.format(Date(h.visitedAt))
            else dateFmt.format(Date(h.visitedAt))
            textSize = 12f
            gravity = Gravity.END
            setPadding(dp(8), dp(2), dp(8), dp(0))
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        }
        val sideCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(timeText)
            addView(moreBtn)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(6), dp(11))
            background = PanelUi.cardBg(ctx)
            isClickable = true
            isFocusable = true
            clipToOutline = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener { openInBrowser(h.url) }
            // long-press opens the same menu, anchored on the ROW itself
            setOnLongClickListener { v -> entryMenu(h, v); true }
        }
        row.addView(tile)
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = h.title.ifEmpty { h.url }
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            })
            addView(TextView(ctx).apply {
                text = h.url.removePrefix("https://").removePrefix("http://")
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
        })
        row.addView(sideCol)

        val wrap = PanelUi.swipeable(ctx, row) {
            io.launch {
                try { AppDb.get(ctx.applicationContext).historyDao().delete(h.id) } catch (_: Exception) {}
                withContext(Dispatchers.Main) { reload() }
            }
        }
        // v1.5.1 FIX: explicit spacing between cards (the swipe wrapper used to
        // swallow cardRow's bottom margin → touching borders).
        wrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
        return wrap
    }

    /** Accent period header with an entry counter («Сегодня · 12») + delete action. */
    private fun periodHeader(ctx: android.content.Context, period: Int, count: Int): LinearLayout {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(14), dp(4), dp(6))
        }
        header.addView(TextView(ctx).apply {
            // v1.7.3: «Сегодня · N» — the period label plus its entry count
            text = periodTitle(period) + " · " + getString(R.string.hist_visits_fmt, count)
            textSize = 12.5f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PanelUi.accent(ctx))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = getString(R.string.hist_delete_period)
            textSize = 11.5f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            background = PanelUi.ripple(ctx)
            setOnClickListener { confirmDeletePeriod(period) }
        })
        return header
    }

    /** v1.7.3: tinted icon circle + bold title + gray subtext (no results at all). */
    private fun emptyState(ctx: android.content.Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(56), dp(20), dp(56))
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(PanelUi.themedColor(ctx, R.attr.colorPrimaryContainer, 0x1F1A73E8.toInt()))
            }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_history)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                imageTintList = android.content.res.ColorStateList.valueOf(PanelUi.accent(ctx))
            })
        })
        addView(TextView(ctx).apply {
            setText(R.string.hist_empty_title)
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            setPadding(0, dp(14), 0, 0)
        })
        addView(TextView(ctx).apply {
            setText(R.string.hist_empty_sub)
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(4), 0, 0)
        })
    }

    // ------------------------------------------------------------------
    // menus & actions
    // ------------------------------------------------------------------

    /**
     * v1.7.3 ROOT FIX (user: «окно появляется слева снизу!»): the PopupMenu is
     * anchored on the PRESSED VIEW (the ⋮ button, or the card on long-press)
     * with END gravity — previously the anchor was the whole list container,
     * so the menu appeared at the list's bottom-left corner.
     */
    private fun entryMenu(h: HistoryEntity, anchor: View) {
        val ctx = context ?: return
        val menu = android.widget.PopupMenu(ctx, anchor, Gravity.END)
        menu.menu.add(0, 1, 0, getString(R.string.ctx_open))
        menu.menu.add(0, 2, 0, getString(R.string.ctx_open_new))
        menu.menu.add(0, 3, 0, getString(R.string.hist_add_bookmark))
        menu.menu.add(0, 4, 0, getString(R.string.hist_copy_link))
        menu.menu.add(0, 5, 0, getString(R.string.delete))
        menu.menu.add(0, 6, 0, getString(R.string.hist_delete_site, Urls.domainOf(h.url)))
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> openInBrowser(h.url)
                2 -> openInBrowser(h.url, newTab = true)
                3 -> addBookmarkFromHistory(ctx, h)
                4 -> copyLink(ctx, h.url)
                5 -> io.launch {
                    try { AppDb.get(ctx.applicationContext).historyDao().delete(h.id) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
                6 -> io.launch {
                    try { AppDb.get(ctx.applicationContext).historyDao().deleteHost(Urls.domainOf(h.url)) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            true
        }
        try { menu.show() } catch (_: Exception) {}
    }

    /** v1.7.3: one-tap bookmark from a history entry (duplicate-guarded). */
    private fun addBookmarkFromHistory(ctx: android.content.Context, h: HistoryEntity) {
        io.launch {
            val db = AppDb.get(ctx.applicationContext)
            val exists = try { db.bookmarkDao().byUrl(h.url) } catch (_: Exception) { null }
            if (exists == null) {
                try {
                    db.bookmarkDao().insert(
                        BookmarkEntity(
                            title = h.title.ifEmpty { Urls.domainOf(h.url) },
                            url = h.url,
                            incognito = false
                        )
                    )
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, if (exists == null) R.string.hist_bookmarked else R.string.bm_exists,
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyLink(ctx: android.content.Context, url: String) {
        try {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            Toast.makeText(ctx, R.string.hist_copied, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun confirmDeletePeriod(period: Int) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.hist_delete_period)
            .setMessage(periodTitle(period))
            .setPositiveButton(R.string.delete) { _, _ ->
                io.launch {
                    try {
                        val dao = AppDb.get(ctx.applicationContext).historyDao()
                        val range = periodRange(period)
                        if (range == null) dao.clear()
                        else dao.deleteRange(range.first, range.second)
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.hist_clear_all)
            .setMessage(R.string.hist_clear_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.launch {
                    try { AppDb.get(ctx.applicationContext).historyDao().clear() } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openInBrowser(url: String, newTab: Boolean = false) {
        val ctx = context ?: return
        // v1.5.1 ROOT FIX (same as bookmarks): REORDER_TO_FRONT + a delayed finish()
        // instead of CLEAR_TOP + immediate finish() (which raced the singleTask
        // relaunch and could swallow the ACTION_VIEW intent).
        // v1.7.20: PLUS the PendingOpen backup channel (see PendingOpen.kt) —
        // if the shell still loses onNewIntent, MainActivity.onResume() opens it.
        // v1.7.21: PRIMARY — hand the URL straight into the live MainActivity
        // (same process); the intent is then a payload-free bring-to-front only.
        com.zephyr.browser.PendingOpen.put(url, newTab)
        val delivered = com.zephyr.browser.PendingOpen.deliver(url, newTab)
        startActivity(Intent(ctx, MainActivity::class.java).apply {
            if (!delivered) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                putExtra("zephyr.newtab", newTab)
            }
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        // v1.7.22: capture the host NOW — same hardening as bookmarks
        val host = activity
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { host?.finish() } catch (_: Exception) {}
        }, 260)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
