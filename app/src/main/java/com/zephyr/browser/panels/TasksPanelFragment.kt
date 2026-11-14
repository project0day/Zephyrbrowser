package com.zephyr.browser.panels

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.ReminderEntity
import com.zephyr.browser.tasks.Reminders
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
 * v1.7.54 «Дела» (user: «внутренний календарь дел и рандеву, чтобы было удобно
 * по дням расписывать, и работал бы будильник»).
 *
 * Two halves on one screen, because a month view alone hides what matters today
 * and a flat list alone hides next week:
 *
 *  - a CALENDAR where every day carries a dot per task (up to three), so the
 *    shape of the weeks is visible before anything is opened. Tapping a day
 *    filters the list under it;
 *  - the LIST for the selected day, or — one tap away — everything still ahead,
 *    grouped by day with headers.
 *
 * v1.7.55 (user: «хотелось бы просмотреть календарь 3 месяца, 6 и 12 разом»):
 * the calendar shows 1, 3, 6 or 12 months at once. Past one month the whole
 * page scrolls as a single surface — a fixed calendar over a scrolling list
 * works for one month and traps eleven of them off-screen — and the cells go
 * compact so a year still fits a phone's width. The choice is remembered.
 *
 * Whether the alarm can fire to the minute is a system privilege on Android
 * 12+, so it is stated at the top with a way to grant it instead of quietly
 * scheduling something less precise than the user asked for.
 */
class TasksPanelFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var items: List<ReminderEntity> = emptyList()
    private var selectedDay: Long = Reminders.dayStart(System.currentTimeMillis())
    private var monthAnchor: Long = System.currentTimeMillis()
    /** false → the selected day only, true → everything still ahead. */
    private var showAll = false
    /** How many months are drawn at once: 1, 3, 6 or 12. */
    private var span = 1

    private val SPANS = intArrayOf(1, 3, 6, 12)

    private lateinit var monthLabel: TextView
    private lateinit var monthsBox: LinearLayout
    private lateinit var spanRow: LinearLayout
    private lateinit var listHeader: TextView
    private lateinit var modeBtn: TextView
    private lateinit var list: LinearLayout
    private lateinit var warning: LinearLayout
    private lateinit var warningText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        span = Prefs.tasksSpan.let { if (it in SPANS.toList()) it else 1 }

        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // v1.7.95 (F-016): reminders here are not the phone's own alarms — they
        // belong to the browser, the assistant can create them, and that is
        // worth saying once.
        PanelUi.hintBanner(ctx, "tasks_hint", R.drawable.ic_list,
            ctx.getString(R.string.tk_hint_title), ctx.getString(R.string.tk_hint_body))
            ?.let { page.addView(it) }

        // ── exact-alarm / notification notice ─────────────────────────────────
        warning = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x22F59E0B)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(8), dp(12), 0) }
            isClickable = true
            foreground = PanelUi.ripple(ctx)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_timer)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) }
                imageTintList = ColorStateList.valueOf(0xFFB45309.toInt())
            })
            warningText = TextView(ctx).apply {
                textSize = 12f
                setTextColor(0xFFB45309.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(warningText)
            setOnClickListener { fixWarning() }
            visibility = View.GONE
        }
        page.addView(warning)

        // ── period header ─────────────────────────────────────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(2))
        }
        header.addView(arrowButton(ctx, "‹") { shiftPeriod(-1) })
        monthLabel = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // tapping the period name jumps back to today
            isClickable = true
            setOnClickListener {
                monthAnchor = System.currentTimeMillis()
                selectedDay = Reminders.dayStart(monthAnchor)
                showAll = false
                render()
            }
        }
        header.addView(monthLabel)
        header.addView(arrowButton(ctx, "›") { shiftPeriod(1) })
        page.addView(header)

        // ── 1 / 3 / 6 / 12 months ─────────────────────────────────────────────
        spanRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(6))
        }
        page.addView(spanRow)

        monthsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(6))
        }
        page.addView(monthsBox)

        page.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                .apply { setMargins(dp(14), dp(6), dp(14), dp(2)) }
            setBackgroundColor(PanelUi.themedColor(ctx, R.attr.colorOutline, 0x14000000))
        })

        // ── list header + mode switch ─────────────────────────────────────────
        val listBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(4))
        }
        listHeader = TextView(ctx).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        modeBtn = TextView(ctx).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PanelUi.accent(ctx))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener { showAll = !showAll; render() }
        }
        listBar.addView(listHeader)
        listBar.addView(modeBtn)
        // v1.7.57: tasks leave the browser as .ics — the one format every
        // calendar reads, so a reminder is checkable from a laptop too
        listBar.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_download)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(2) }
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = ColorStateList.valueOf(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            isClickable = true
            foreground = PanelUi.ripple(ctx)
            contentDescription = getString(R.string.tk_export_ics)
            setOnClickListener { exportIcs() }
        })
        page.addView(listBar)

        // ── add button ────────────────────────────────────────────────────────
        page.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(10))
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(11), dp(16), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                background = GradientDrawable().apply {
                    setColor(PanelUi.accent(ctx))
                    cornerRadius = dp(16).toFloat()
                }
                isClickable = true
                clipToOutline = true
                foreground = PanelUi.ripple(ctx)
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_add)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                })
                addView(TextView(ctx).apply {
                    setText(R.string.tk_add)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFFFFFFFF.toInt())
                })
                setOnClickListener { editTask(null) }
            })
        })

        list = PanelUi.listColumn(ctx)
        page.addView(list)

        // v1.7.55: ONE scrolling surface. A twelve-month calendar pinned above a
        // scrolling list would put eleven months where nobody can reach them.
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            addView(page)
        }

        reload()

        // v1.7.61: arriving from «Сценарии» → straight into the editor with the
        // scenario already filled in as a job, so scheduling one is two taps
        val jobPrompt = activity?.intent?.getStringExtra("job_prompt")
        if (!jobPrompt.isNullOrBlank()) {
            val jobTitle = activity?.intent?.getStringExtra("job_title").orEmpty()
            activity?.intent?.removeExtra("job_prompt")
            page.post {
                if (isAdded) editTask(
                    com.zephyr.browser.tasks.AgentJobs.newJob(
                        jobTitle, jobPrompt, defaultTime(), Reminders.ONCE
                    )
                )
            }
        }

        // v1.7.91: the + on the home-screen widget. Same idea as the scenario
        // hand-off above — arrive with the editor already open, because the
        // person who tapped "add" has the task in their head right now and
        // should not have to find the button again.
        if (activity?.intent?.getBooleanExtra("new", false) == true) {
            activity?.intent?.removeExtra("new")
            page.post { if (isAdded) editTask(null) }
        }
        return scroll
    }

    override fun onResume() {
        super.onResume()
        refreshWarning()
        reload()
    }

    // ---------------- what could stop a reminder arriving ----------------

    /**
     * Two things silently break a reminder, and the user cannot see either from
     * this screen: notifications switched off (nothing appears at all) and exact
     * alarms denied (it appears late). Notifications come first — a late
     * reminder still beats no reminder.
     *
     * The app asks for notification permission once, at first launch. Someone
     * who declined it then has no way back except this banner.
     */
    private fun notificationsOn(): Boolean = try {
        androidx.core.app.NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
    } catch (_: Throwable) { true }

    private fun refreshWarning() {
        val ctx = context ?: return
        when {
            !notificationsOn() -> {
                warningText.setText(R.string.tk_notif_warn)
                warning.visibility = View.VISIBLE
            }
            !Reminders.canBeExact(ctx) -> {
                warningText.setText(R.string.tk_exact_warn)
                warning.visibility = View.VISIBLE
            }
            else -> warning.visibility = View.GONE
        }
    }

    private fun fixWarning() {
        if (!notificationsOn()) openNotificationSettings() else requestExactAlarms()
    }

    private fun openNotificationSettings() {
        val ctx = context ?: return
        try {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            )
        } catch (_: Throwable) {
            try {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + ctx.packageName))
                )
            } catch (_: Throwable) {}
        }
    }

    // ---------------- data ----------------

    private fun reload() {
        io.launch {
            val rows = try { AppDb.get(requireContext().applicationContext).reminderDao().allOnce() }
            catch (_: Throwable) { emptyList() }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                items = rows
                render()
            }
        }
    }

    private fun save(r: ReminderEntity, isNew: Boolean) {
        val app = requireContext().applicationContext
        io.launch {
            try {
                val dao = AppDb.get(app).reminderDao()
                val id = if (isNew) dao.insert(r) else { dao.update(r); r.id }
                Reminders.schedule(app, r.copy(id = id))
            } catch (_: Throwable) {}
            // v1.7.91: a to-do widget showing yesterday's list is worse than no
            // widget — the user stops trusting it and goes back to the app
            com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
            withContext(Dispatchers.Main) { if (isAdded) reload() }
        }
    }

    private fun remove(r: ReminderEntity) {
        val app = requireContext().applicationContext
        io.launch {
            try {
                Reminders.cancel(app, r.id)
                AppDb.get(app).reminderDao().delete(r)
            } catch (_: Throwable) {}
            com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
            withContext(Dispatchers.Main) { if (isAdded) reload() }
        }
    }

    // ---------------- rendering ----------------

    private fun render() {
        val ctx = context ?: return
        monthLabel.text = periodLabel()
        renderSpanChips(ctx)
        renderMonths(ctx)

        modeBtn.setText(if (showAll) R.string.tk_mode_day else R.string.tk_mode_all)
        listHeader.text =
            if (showAll) getString(R.string.tk_upcoming)
            else Reminders.dayTitle(selectedDay)

        list.removeAllViews()
        if (showAll) {
            val now = System.currentTimeMillis()
            val ahead = items.filter { !it.done && it.dueAt >= Reminders.dayStart(now) }
                .sortedBy { it.dueAt }
            if (ahead.isEmpty()) { list.addView(PanelUi.emptyView(ctx, R.string.tk_empty_all)); return }
            var lastDay = Long.MIN_VALUE
            for (r in ahead) {
                val d = Reminders.dayKey(r.dueAt)
                if (d != lastDay) {
                    lastDay = d
                    list.addView(PanelUi.sectionHeader(ctx, Reminders.dayTitle(r.dueAt)))
                }
                list.addView(taskCard(r))
            }
        } else {
            val from = selectedDay
            val to = from + 86_400_000L - 1
            val day = items.filter { it.dueAt in from..to }.sortedBy { it.dueAt }
            if (day.isEmpty()) { list.addView(PanelUi.emptyView(ctx, R.string.tk_empty_day)); return }
            for (r in day) list.addView(taskCard(r))
        }
    }

    /** "Сентябрь" for one month, "Сентябрь — Ноябрь 2026" for a range. */
    private fun periodLabel(): String {
        val start = monthStart(monthAnchor)
        if (span == 1) {
            return SimpleDateFormat(
                if (sameYear(start)) "LLLL" else "LLLL yyyy", Locale.getDefault()
            ).format(Date(start)).replaceFirstChar { it.uppercase() }
        }
        val end = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.MONTH, span - 1)
        }.timeInMillis
        val a = SimpleDateFormat(
            if (sameYear(start) && sameYear(end)) "LLLL" else "LLLL yyyy", Locale.getDefault()
        ).format(Date(start)).replaceFirstChar { it.uppercase() }
        val b = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
            .format(Date(end)).replaceFirstChar { it.uppercase() }
        return "$a — $b"
    }

    private fun renderSpanChips(ctx: android.content.Context) {
        spanRow.removeAllViews()
        for (s in SPANS) {
            val on = s == span
            spanRow.addView(TextView(ctx).apply {
                text = getString(R.string.tk_span_fmt, s)
                textSize = 12.5f
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextColor(if (on) 0xFFFFFFFF.toInt() else PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    if (on) setColor(PanelUi.accent(ctx))
                    else setColor(PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt()))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                isClickable = true
                clipToOutline = true
                foreground = PanelUi.ripple(ctx)
                setOnClickListener {
                    if (span == s) return@setOnClickListener
                    span = s
                    Prefs.setTasksSpan(s)
                    // keep the selected day visible: anchor on its month
                    monthAnchor = selectedDay
                    render()
                }
            })
        }
    }

    private fun renderMonths(ctx: android.content.Context) {
        monthsBox.removeAllViews()
        val compact = span > 1
        // counted once for the whole page rather than per cell
        val counts = HashMap<Long, Int>()
        for (r in items) if (!r.done) {
            val k = Reminders.dayKey(r.dueAt)
            counts[k] = (counts[k] ?: 0) + 1
        }
        val start = monthStart(monthAnchor)
        for (i in 0 until span) {
            val at = Calendar.getInstance().apply {
                timeInMillis = start
                add(Calendar.MONTH, i)
            }.timeInMillis
            if (compact) monthsBox.addView(TextView(ctx).apply {
                text = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
                    .format(Date(at)).replaceFirstChar { it.uppercase() }
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(PanelUi.accent(ctx))
                setPadding(dp(4), dp(if (i == 0) 4 else 14), dp(4), dp(2))
            })
            monthsBox.addView(weekdayRow(ctx, compact))
            monthsBox.addView(monthGrid(ctx, at, counts, compact))
        }
    }

    private fun weekdayRow(ctx: android.content.Context, compact: Boolean) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, dp(2))
        for (name in weekdayNames()) addView(TextView(ctx).apply {
            text = name
            textSize = if (compact) 9.5f else 11f
            gravity = Gravity.CENTER
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun monthGrid(
        ctx: android.content.Context,
        monthAt: Long,
        counts: Map<Long, Int>,
        compact: Boolean
    ): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val c = Calendar.getInstance().apply {
            timeInMillis = monthAt
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val monthNumber = c.get(Calendar.MONTH)
        // back up to the first cell of the week the 1st falls in
        var shift = c.get(Calendar.DAY_OF_WEEK) - c.firstDayOfWeek
        if (shift < 0) shift += 7
        c.add(Calendar.DAY_OF_MONTH, -shift)

        val todayKey = Reminders.dayKey(System.currentTimeMillis())
        val selectedKey = Reminders.dayKey(selectedDay)

        // 6 rows always for one month (stable height); a multi-month page drops
        // a trailing row that holds no day of this month, so a year stays short
        repeat(6) {
            val week = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            var anyInMonth = false
            repeat(7) {
                val at = c.timeInMillis
                val key = Reminders.dayKey(at)
                val inMonth = c.get(Calendar.MONTH) == monthNumber
                if (inMonth) anyInMonth = true
                week.addView(dayCell(
                    ctx, at, c.get(Calendar.DAY_OF_MONTH), inMonth,
                    key == todayKey, key == selectedKey, counts[key] ?: 0, compact
                ))
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
            if (anyInMonth || !compact) box.addView(week)
        }
        return box
    }

    private fun dayCell(
        ctx: android.content.Context,
        at: Long, number: Int,
        inMonth: Boolean, isToday: Boolean, isSelected: Boolean, count: Int,
        compact: Boolean
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(if (compact) 3 else 5), 0, dp(if (compact) 2 else 4))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(1), dp(if (compact) 1 else 2), dp(1), dp(if (compact) 1 else 2)) }
        isClickable = true
        foreground = PanelUi.ripple(ctx)
        if (isSelected) background = GradientDrawable().apply {
            cornerRadius = dp(if (compact) 9 else 12).toFloat()
            setColor((PanelUi.accent(ctx) and 0x00FFFFFF) or 0x28000000)
        }
        addView(TextView(ctx).apply {
            // an out-of-month cell in a multi-month page is noise: the next
            // month draws that day properly a few rows down
            text = if (compact && !inMonth) "" else number.toString()
            textSize = if (compact) 11f else 13.5f
            gravity = Gravity.CENTER
            typeface = if (isToday || isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(
                when {
                    isToday -> PanelUi.accent(ctx)
                    !inMonth -> PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant) and 0x66FFFFFF
                    else -> PanelUi.themedColor(ctx, R.attr.colorOnSurface)
                }
            )
        })
        // up to three dots — more than that and the row stops being readable
        val maxDots = if (compact) 2 else 3
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(if (compact) 6 else 8))
            if (inMonth || !compact) repeat(count.coerceAtMost(maxDots)) {
                addView(View(ctx).apply {
                    val d = if (compact) 3 else 4
                    layoutParams = LinearLayout.LayoutParams(dp(d), dp(d))
                        .apply { setMargins(dp(1), dp(if (compact) 1 else 2), dp(1), 0) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(PanelUi.accent(ctx))
                    }
                })
            }
        })
        setOnClickListener {
            selectedDay = Reminders.dayStart(at)
            showAll = false
            // in one-month mode a neighbouring month's day pulls the view along;
            // in a multi-month page that day is already on screen
            if (!inMonth && span == 1) monthAnchor = at
            render()
        }
    }

    private fun taskCard(r: ReminderEntity): View {
        val ctx = requireContext()
        val check = CheckBox(ctx).apply {
            isChecked = r.done
            setOnClickListener {
                // a repeating task is never "finished" — it moves to its next run
                if (!r.done && r.repeat != Reminders.ONCE) {
                    val next = Reminders.nextAfterNow(r.dueAt, r.repeat)
                    save(r.copy(dueAt = next), isNew = false)
                } else {
                    save(r.copy(done = !r.done), isNew = false)
                }
            }
        }
        val sub = buildString {
            if (com.zephyr.browser.tasks.AgentJobs.isJob(r)) {
                append(getString(R.string.tk_job_badge)).append(" · ")
            }
            append(Reminders.timeText(r.dueAt))
            if (r.repeat != Reminders.ONCE) append(" · ").append(Reminders.repeatLabel(ctx, r.repeat))
            if (!r.alarm) append(" · ").append(getString(R.string.tk_quiet))
            // v1.7.57: a task about a page shows which one, by host
            if (r.url.isNotBlank()) {
                append(" · ")
                append(try { android.net.Uri.parse(r.url).host ?: r.url } catch (_: Throwable) { r.url })
            }
            if (r.notes.isNotBlank()) append(" · ").append(r.notes)
        }
        val card = PanelUi.cardRow(
            ctx,
            leading = check,
            title = r.title,
            subtitle = sub,
            onLongClick = {
                // opening the attached page is offered first when there is one:
                // that is what a link reminder is for
                val actions = ArrayList<Pair<String, () -> Unit>>()
                if (r.url.isNotBlank()) actions.add(getString(R.string.tk_open_link) to { openUrl(r.url) })
                actions.add(getString(R.string.edit) to { editTask(r) })
                actions.add(getString(R.string.delete) to { remove(r) })
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(r.title)
                    .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                        actions[which].second()
                    }
                    .show()
            },
            onClick = { editTask(r) }
        )
        if (r.done) {
            card.alpha = 0.55f
            // strike through the title so a finished task reads as finished
            (((card.getChildAt(1)) as? LinearLayout)?.getChildAt(0) as? TextView)?.paintFlags =
                android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }
        return card
    }

    // ---------------- editor ----------------

    private fun editTask(existing: ReminderEntity?) {
        val ctx = requireContext()
        // a new task defaults to the next round hour on the selected day
        var at = existing?.dueAt ?: defaultTime()
        var repeat = existing?.repeat ?: Reminders.ONCE
        var alarm = existing?.alarm ?: true
        // v1.7.57: the same row is either a reminder or a job for the assistant
        var isJob = existing?.source == com.zephyr.browser.tasks.AgentJobs.SOURCE

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(4))
        }
        val titleEt = EditText(ctx).apply {
            setText(existing?.title ?: "")
            hint = getString(R.string.tk_title_hint)
            setSingleLine(true)
            textSize = 15f
        }
        val notesEt = EditText(ctx).apply {
            setText(existing?.notes ?: "")
            setSingleLine(false)
            maxLines = 4
            textSize = 13.5f
        }
        val jobNote = TextView(ctx).apply {
            setText(R.string.tk_job_note)
            textSize = 11.5f
            alpha = 0.7f
            setPadding(0, dp(6), 0, 0)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        }
        box.addView(titleEt)
        box.addView(notesEt)
        box.addView(jobNote)

        lateinit var whenRow: TextView
        lateinit var repeatRow: TextView
        lateinit var kindRow: TextView
        fun refresh() {
            whenRow.text = getString(R.string.tk_when) + ": " + Reminders.whenText(ctx, at)
            repeatRow.text = getString(R.string.tk_repeat) + ": " + Reminders.repeatLabel(ctx, repeat)
            kindRow.text = getString(R.string.tk_kind) + ": " +
                getString(if (isJob) R.string.tk_kind_job else R.string.tk_kind_task)
            // the second field means different things in the two modes, so it
            // says so rather than keeping one vague label
            notesEt.hint = getString(if (isJob) R.string.tk_prompt_hint else R.string.tk_notes_hint)
            jobNote.visibility = if (isJob) View.VISIBLE else View.GONE
        }

        kindRow = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, dp(6))
            setTextColor(PanelUi.accent(ctx))
            isClickable = true
            setOnClickListener {
                val labels = arrayOf(
                    getString(R.string.tk_kind_task), getString(R.string.tk_kind_job)
                )
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.tk_kind)
                    .setSingleChoiceItems(labels, if (isJob) 1 else 0) { d, which ->
                        isJob = which == 1
                        // a job announces nothing itself, so a loud alarm on it
                        // would just be noise before the answer arrives
                        if (isJob) alarm = false
                        refresh(); d.dismiss()
                    }
                    .show()
            }
        }
        box.addView(kindRow)

        whenRow = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, dp(18), 0, dp(6))
            setTextColor(PanelUi.accent(ctx))
            isClickable = true
            setOnClickListener {
                val c = Calendar.getInstance().apply { timeInMillis = at }
                DatePickerDialog(ctx, { _, y, m, d ->
                    c.set(y, m, d)
                    TimePickerDialog(ctx, { _, h, min ->
                        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min)
                        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                        at = c.timeInMillis
                        refresh()
                    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        repeatRow = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, dp(6))
            setTextColor(PanelUi.accent(ctx))
            isClickable = true
            setOnClickListener {
                val modes = intArrayOf(
                    Reminders.ONCE, Reminders.DAILY, Reminders.WEEKDAYS,
                    Reminders.WEEKLY, Reminders.MONTHLY
                )
                val labels = modes.map { Reminders.repeatLabel(ctx, it) }.toTypedArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.tk_repeat)
                    .setSingleChoiceItems(labels, modes.indexOf(repeat).coerceAtLeast(0)) { d, which ->
                        repeat = modes[which]; refresh(); d.dismiss()
                    }
                    .show()
            }
        }
        box.addView(whenRow)
        box.addView(repeatRow)
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(TextView(ctx).apply {
                setText(R.string.tk_alarm)
                textSize = 14f
                setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(ctx).apply {
                isChecked = alarm
                setOnCheckedChangeListener { _, on -> alarm = on }
            })
        })
        box.addView(TextView(ctx).apply {
            setText(R.string.tk_alarm_sub)
            textSize = 11.5f
            alpha = 0.65f
            setPadding(0, dp(4), 0, 0)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
        refresh()

        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.tk_add else R.string.tk_edit)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                val t = titleEt.text.toString().trim()
                if (t.isBlank()) {
                    Toast.makeText(ctx, R.string.tk_need_title, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val row = (existing ?: ReminderEntity(title = t, dueAt = at)).copy(
                    title = t,
                    notes = notesEt.text.toString().trim(),
                    dueAt = at,
                    repeat = repeat,
                    alarm = alarm,
                    done = false,
                    source = if (isJob) com.zephyr.browser.tasks.AgentJobs.SOURCE
                    else existing?.source.orEmpty().takeIf { it != com.zephyr.browser.tasks.AgentJobs.SOURCE }.orEmpty()
                )
                // a row handed in from «Сценарии» has no id yet — it is new,
                // even though "existing" is not null
                save(row, isNew = existing == null || existing.id == 0L)
                selectedDay = Reminders.dayStart(at)
                // say it now, not when the reminder fails to arrive
                if (!notificationsOn()) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.tk_notif_title)
                        .setMessage(R.string.tk_notif_msg)
                        .setPositiveButton(R.string.ok) { _, _ -> openNotificationSettings() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else if (!Reminders.canBeExact(ctx)) askExact()
            }
            .setNegativeButton(R.string.cancel, null)
        if (existing != null && existing.id != 0L) b.setNeutralButton(R.string.delete) { _, _ -> remove(existing) }
        b.show()
    }

    /** Hand the page to the browser; the panel sits over it, so it closes. */
    private fun openUrl(url: String) {
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)
                ).setClass(requireContext(), com.zephyr.browser.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            activity?.finish()
        } catch (_: Throwable) {}
    }

    private fun exportIcs() {
        val ctx = context ?: return
        val rows = items
        if (rows.isEmpty()) {
            Toast.makeText(ctx, R.string.tk_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        io.launch {
            val f = com.zephyr.browser.tasks.TaskExport.writeFile(ctx.applicationContext, rows)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(
                    requireContext(),
                    if (f != null) getString(R.string.tk_export_done_fmt, f.absolutePath)
                    else getString(R.string.rl_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Next round hour on the day the user is looking at. */
    private fun defaultTime(): Long {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply {
            timeInMillis = selectedDay
            set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY) + 1)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.HOUR_OF_DAY, 1)
        return c.timeInMillis
    }

    // ---------------- exact-alarm permission ----------------

    private fun askExact() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.tk_exact_title)
            .setMessage(R.string.tk_exact_msg)
            .setPositiveButton(R.string.ok) { _, _ -> requestExactAlarms() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:" + requireContext().packageName)
                )
            )
        } catch (_: Throwable) {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + requireContext().packageName)))
            } catch (_: Throwable) {}
        }
    }

    // ---------------- helpers ----------------

    /** Move by a whole page: one month, or the whole 3/6/12-month span. */
    private fun shiftPeriod(delta: Int) {
        monthAnchor = Calendar.getInstance().apply {
            timeInMillis = monthStart(monthAnchor)
            add(Calendar.MONTH, delta * span)
        }.timeInMillis
        render()
    }

    private fun monthStart(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun arrowButton(ctx: android.content.Context, glyph: String, onTap: () -> Unit) =
        TextView(ctx).apply {
            text = glyph
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            isClickable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener { onTap() }
        }

    /** Weekday captions in the locale's own week order. */
    private fun weekdayNames(): List<String> {
        val fmt = SimpleDateFormat("EEE", Locale.getDefault())
        val c = Calendar.getInstance()
        val shift = c.get(Calendar.DAY_OF_WEEK) - c.firstDayOfWeek
        c.add(Calendar.DAY_OF_MONTH, -(if (shift < 0) shift + 7 else shift))
        return (0 until 7).map {
            val s = fmt.format(c.time).replaceFirstChar { ch -> ch.uppercase() }
            c.add(Calendar.DAY_OF_MONTH, 1)
            s
        }
    }

    private fun sameYear(at: Long): Boolean =
        Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.YEAR) ==
            Calendar.getInstance().get(Calendar.YEAR)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
