package com.zephyr.browser.ai

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.zephyr.browser.R
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.7.55 (user: «а если окно будет супер большим — 1000 строк кода — буду
 * листать и жать ОК? а если проект 100 файлов, то сто окон закрывать? нельзя
 * сделать в стиле IDE, где внизу зелёным сколько строк, красным сколько убрал,
 * и справа — создать всё или отклонить?»).
 *
 * The old confirmation was a plain alert holding 600 characters of raw content,
 * which asked the user to proof-read a file to approve it. This asks the
 * question a code review actually asks — what changes, and by how much:
 *
 *  - a HEADER with the file name, its folder, and +added / −removed counts;
 *  - a DIFF, not a dump: for an existing file only the changed region is shown
 *    with a few lines of context around it, so a one-line edit in a
 *    thousand-line file is one screen, not a thousand;
 *  - THREE answers instead of two. "Применить всё" covers every remaining file
 *    in this one answer, so a hundred-file project is one decision; "Отклонить"
 *    refuses this file and keeps asking about the next.
 *
 * The decision is made on the UI thread while the tool thread waits on a latch,
 * exactly like the other confirmations — the model must receive a result for
 * the call it made.
 */
object AiWriteReview {

    /** A string resource; this object draws UI but is handed no Context. */
    private fun s(res: Int, vararg args: Any): String = try {
        val c = com.zephyr.browser.App.ctx
        if (c == null) "" else if (args.isEmpty()) c.getString(res) else c.getString(res, *args)
    } catch (_: Throwable) { "" }

    /** What the user chose. */
    enum class Choice { APPLY, APPLY_ALL, REJECT, REJECT_ALL }

    /** Set by APPLY_ALL / REJECT_ALL; cleared when a new question starts. */
    @Volatile private var blanket: Choice? = null

    /**
     * What the current answer changed, with enough to put it back.
     *
     * [before] is null when the file did not exist — reverting then means
     * deleting it. The previous content is held in memory rather than copied to
     * disk: these are source files the assistant just composed, small by
     * nature, and a backup folder the user never asked for is its own mess.
     */
    class Change(val path: String, val before: String?, val added: Int, val removed: Int)

    private val session = ArrayList<Change>()

    /** Called when a new user question begins: a blanket yes must not outlive it. */
    @Synchronized
    fun beginTurn() {
        blanket = null
        session.clear()
    }

    @Synchronized
    fun record(path: String, before: String?, added: Int, removed: Int) {
        session.add(Change(path, before, added, removed))
    }

    @Synchronized
    fun sessionChanges(): List<Change> = ArrayList(session)

    @Synchronized
    fun clearSession() = session.clear()

    /**
     * Undo every write of the last answer, newest first.
     *
     * Newest first matters: the same file may have been written twice in one
     * answer, and restoring in reverse order lands on the content it had before
     * the answer started rather than on its state halfway through.
     */
    @Synchronized
    fun revertSession(): Int {
        var n = 0
        for (c in session.asReversed()) {
            try {
                val f = File(c.path)
                if (c.before == null) { if (f.delete()) n++ }
                else { f.writeText(c.before); n++ }
            } catch (_: Throwable) {}
        }
        session.clear()
        return n
    }

    /** A standing answer from earlier in this turn, if the user gave one. */
    fun standing(): Choice? = blanket

    // ---------------- diff ----------------

    class Diff(
        val added: Int,
        val removed: Int,
        /** Rendered lines: '+' added, '-' removed, ' ' context, '…' elision. */
        val lines: List<Pair<Char, String>>
    )

    /**
     * A prefix/suffix diff, not an LCS.
     *
     * A full LCS over two thousand-line files is a million-cell table built on
     * the UI thread to draw a preview — not worth it. Trimming the shared head
     * and tail leaves exactly the region that changed, which is what an
     * assistant's rewrite looks like anyway, and it can never be wrong about
     * the counts it reports for the whole file.
     */
    fun diff(oldText: String?, newText: String, context: Int = 3): Diff {
        val neu = newText.split("\n")
        if (oldText == null) {
            val shown = neu.take(400).map { '+' to it }
            return Diff(
                neu.size, 0,
                if (neu.size > 400) shown + listOf('…' to s(R.string.diff_more_lines, neu.size - 400)) else shown
            )
        }
        val old = oldText.split("\n")
        var head = 0
        while (head < old.size && head < neu.size && old[head] == neu[head]) head++
        var tail = 0
        while (tail < old.size - head && tail < neu.size - head &&
            old[old.size - 1 - tail] == neu[neu.size - 1 - tail]
        ) tail++

        val removedLines = old.subList(head, old.size - tail)
        val addedLines = neu.subList(head, neu.size - tail)

        val out = ArrayList<Pair<Char, String>>()
        val beforeFrom = (head - context).coerceAtLeast(0)
        if (beforeFrom > 0) out.add('…' to s(R.string.diff_lines_above, beforeFrom))
        for (i in beforeFrom until head) out.add(' ' to neu[i])
        for (l in removedLines.take(300)) out.add('-' to l)
        if (removedLines.size > 300) out.add('…' to s(R.string.diff_more_removed, removedLines.size - 300))
        for (l in addedLines.take(300)) out.add('+' to l)
        if (addedLines.size > 300) out.add('…' to s(R.string.diff_more_added, addedLines.size - 300))
        val afterTo = (neu.size - tail + context).coerceAtMost(neu.size)
        for (i in (neu.size - tail) until afterTo) out.add(' ' to neu[i])
        if (afterTo < neu.size) out.add('…' to s(R.string.diff_lines_below, neu.size - afterTo))

        return Diff(addedLines.size, removedLines.size, out)
    }

    // ---------------- sheet ----------------

    /**
     * Show the review and block until the user answers.
     *
     * Returns the standing answer immediately when one was given earlier in the
     * same turn — that is the whole point of "Применить всё".
     */
    fun ask(activity: Activity, file: File, content: String, append: Boolean): Choice {
        val existing = try { if (file.isFile) file.readText() else null } catch (_: Throwable) { null }
        return review(
            activity,
            title = file.name,
            subtitle = (file.parent ?: "") + "/",
            before = existing,
            // appending shows only what is being added, which is literally the case
            after = if (append && existing != null) existing + content else content,
            kindRes = when {
                existing == null -> R.string.rv_new
                append -> R.string.rv_append
                else -> R.string.rv_overwrite
            },
            blanketApplies = true
        )
    }

    /**
     * v1.7.61 (user: «не лучше использовать уже существующий движок, как мы
     * сделали для кодинга? лучше одна универсальная система, чем сто разных»).
     *
     * Exactly right, and the script tool proved it: creating a permanent
     * userscript — arguably the most consequential thing the assistant can do,
     * since it then runs on every matching page unattended — asked with a plain
     * alert and an OK button, while writing a throwaway .txt got the full diff.
     * The review is not about files; it is about "here is what will change,
     * decide". So it takes text, and a file is one caller among others.
     *
     * [blanketApplies] is how a script differs from a file: a blanket
     * "применить всё" given for a batch of project files must not silently
     * approve installing code that keeps running afterwards.
     */
    fun review(
        activity: Activity,
        title: String,
        subtitle: String,
        before: String?,
        after: String,
        kindRes: Int,
        blanketApplies: Boolean
    ): Choice {
        if (blanketApplies) standing()?.let { return it }

        var result = Choice.REJECT
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                build(activity, title, subtitle, before, after, kindRes, blanketApplies) { c ->
                    result = c; latch.countDown()
                }
            } catch (_: Throwable) { latch.countDown() }
        }
        latch.await(180, TimeUnit.SECONDS)
        if (blanketApplies && (result == Choice.APPLY_ALL || result == Choice.REJECT_ALL)) blanket = result
        return result
    }

    private fun build(
        activity: Activity,
        titleText: String,
        subtitleText: String,
        before: String?,
        after: String,
        kindRes: Int,
        blanketApplies: Boolean,
        done: (Choice) -> Unit
    ) {
        val ctx = activity
        val d = { v: Int -> AiUi.dp(ctx, v) }
        // v1.7.56 FIX (user: «окно выходит высоко, чуть выше середины, а не
        // выезжает снизу, а если свернуть — пропадёт окончательно»).
        //
        // This was a BottomSheetDialog opened on top of the chat, which is
        // itself a full-screen Dialog. Two stacked windows, and the sheet's
        // behaviour re-settles itself against a window it does not own, so it
        // came to rest mid-screen and had no way back once dismissed. The chat
        // hit the same problem in v1.7.26 and was moved to a plain dialog then;
        // the review does the same now — and reading a diff wants the height
        // anyway. Tapping the strip above it is a refusal, like a swipe away.
        val (dialog, root) = AiUi.fullScreen(ctx, 0.06f) { }

        val diff = diff(before, after)

        // ── header ────────────────────────────────────────────────────────────
        root.addView(AiUi.sheetTitle(ctx, titleText))
        root.addView(TextView(ctx).apply {
            text = subtitleText
            textSize = 11.5f
            setTextColor(AiUi.onVariant(ctx))
            setPadding(d(20), 0, d(20), d(6))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(20), 0, d(20), d(10))
            addView(badge(ctx, "+" + diff.added, 0xFF10B981.toInt()))
            if (diff.removed > 0) addView(badge(ctx, "−" + diff.removed, 0xFFEF4444.toInt()))
            addView(TextView(ctx).apply {
                setText(kindRes)
                textSize = 12f
                // amber when something already there is being replaced
                setTextColor(if (before == null) AiUi.onVariant(ctx) else 0xFFF59E0B.toInt())
                setPadding(d(10), 0, 0, 0)
            })
            // which file of this answer — only worth saying once there are several
            if (session.size + 1 > 1) addView(TextView(ctx).apply {
                text = ctx.getString(R.string.rv_count_fmt, session.size + 1)
                textSize = 11.5f
                setTextColor(AiUi.onVariant(ctx))
                setPadding(d(10), 0, 0, 0)
            })
        })
        root.addView(AiUi.divider(ctx))

        // ── diff body ─────────────────────────────────────────────────────────
        val (scroll, col) = AiUi.body(ctx)
        val code = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for ((mark, text) in diff.lines) {
            code.addView(TextView(ctx).apply {
                this.text = (if (mark == '…') "   " else "$mark ") + text
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setPadding(d(10), d(1), d(10), d(1))
                setTextColor(
                    when (mark) {
                        '+' -> 0xFF10B981.toInt()
                        '-' -> 0xFFEF4444.toInt()
                        '…' -> AiUi.onVariant(ctx)
                        else -> AiUi.onSurface(ctx)
                    }
                )
                when (mark) {
                    '+' -> setBackgroundColor(0x1410B981)
                    '-' -> setBackgroundColor(0x14EF4444)
                }
                setTextIsSelectable(false)
            })
        }
        // code does not wrap: a wrapped line is unreadable, a scrolled one is not
        col.addView(HorizontalScrollView(ctx).apply {
            isFillViewport = true
            addView(code)
        })
        root.addView(scroll)

        // ── actions ───────────────────────────────────────────────────────────
        root.addView(AiUi.divider(ctx))
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(14), d(10), d(14), d(14))
        }
        bar.addView(actionBtn(ctx, ctx.getString(R.string.rv_reject), filled = false) {
            dialog.dismiss(); done(Choice.REJECT)
        })
        bar.addView(actionBtn(ctx, ctx.getString(R.string.rv_apply), filled = true) {
            dialog.dismiss(); done(Choice.APPLY)
        })
        // v1.7.68 (user: «если это один файл, зачем повторяющие кнопки?»).
        //
        // Right: "применить всё" on the very first file is a question about a
        // set that does not exist yet. The count IS knowable — this is the
        // second review of the turn, or the fifth — so the blanket row appears
        // only from the second file onward, and the header says which one this
        // is out of how many so far.
        val nth = session.size + 1
        val offerBlanket = blanketApplies && nth > 1

        if (offerBlanket) {
            bar.setPadding(d(14), d(10), d(14), d(4))
            root.addView(bar)
            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                // keep the last row clear of the gesture bar — fullScreen runs to
                // the bottom edge of the window
                setPadding(d(14), 0, d(14), d(16) + AiUi.navBarHeight(ctx))
                addView(actionBtn(ctx, ctx.getString(R.string.rv_reject_all), filled = false, small = true) {
                    dialog.dismiss(); done(Choice.REJECT_ALL)
                })
                addView(actionBtn(ctx, ctx.getString(R.string.rv_apply_all), filled = false, small = true, accentText = true) {
                    dialog.dismiss(); done(Choice.APPLY_ALL)
                })
            })
        } else {
            // no "всё" here: this decision is not one of a batch
            bar.setPadding(d(14), d(10), d(14), d(16) + AiUi.navBarHeight(ctx))
            root.addView(bar)
        }

        // dismissing without choosing is a refusal, not an approval
        dialog.setOnCancelListener { done(Choice.REJECT) }
        dialog.show()
    }

    private fun badge(ctx: android.content.Context, text: String, color: Int) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        setPadding(AiUi.dp(ctx, 9), AiUi.dp(ctx, 3), AiUi.dp(ctx, 9), AiUi.dp(ctx, 3))
        background = GradientDrawable().apply {
            cornerRadius = AiUi.dp(ctx, 10).toFloat()
            setColor((color and 0x00FFFFFF) or 0x1F000000)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = AiUi.dp(ctx, 6) }
    }

    private fun actionBtn(
        ctx: android.content.Context,
        label: String,
        filled: Boolean,
        small: Boolean = false,
        accentText: Boolean = false,
        onTap: () -> Unit
    ) = TextView(ctx).apply {
        text = label
        textSize = if (small) 12.5f else 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(AiUi.dp(ctx, 10), AiUi.dp(ctx, if (small) 9 else 12), AiUi.dp(ctx, 10), AiUi.dp(ctx, if (small) 9 else 12))
        setTextColor(
            when {
                filled -> 0xFFFFFFFF.toInt()
                accentText -> AiUi.ACCENT
                else -> AiUi.onVariant(ctx)
            }
        )
        background = GradientDrawable().apply {
            cornerRadius = AiUi.dp(ctx, 14).toFloat()
            if (filled) setColor(AiUi.ACCENT) else setColor(AiUi.surfaceVar(ctx))
        }
        isClickable = true
        clipToOutline = true
        foreground = AiUi.ripple(ctx, borderless = false)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = AiUi.dp(ctx, 4); marginEnd = AiUi.dp(ctx, 4) }
        setOnClickListener { onTap() }
    }
}
