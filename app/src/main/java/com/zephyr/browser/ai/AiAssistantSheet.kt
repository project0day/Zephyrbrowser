package com.zephyr.browser.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.MainActivity
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Zephyr AI Assistant window.
 *
 * v1.7.25  — rewrite: Markdown replies with clickable links, photo and document
 *            attachments, multi-model support.
 * v1.7.25b — persistent conversations ([AiChatStore]) + minimize into the
 *            draggable [AiFloatingBubble].
 * v1.7.26  — the round of fixes the user reported after living with it:
 *   - FULL-SCREEN dialog instead of a bottom sheet: BottomSheetBehavior fought
 *     the keyboard and left the composer half-covered («диалог обрезанный…
 *     потом резко опустился и уже за клавой»);
 *   - STREAMING answers with a real status row — stage, elapsed seconds, Stop,
 *     and an expandable «Размышления» block («не вижу, что происходит»);
 *   - Stop keeps the partial answer and hands focus back to the composer
 *     («нажал стоп — в поле ввода написать уже не получается»);
 *   - the message actions are icon-only;
 *   - voice dictation in the composer;
 *   - работа в фоне: a request in flight survives closing the window and the
 *     answer arrives as a badge on the floating bubble.
 */
/** v1.7.28: UI hooks of the window currently showing a running request. */
private interface LiveUi {
    fun status(stage: String)
    fun reasoning(all: String)
    fun answer(all: String)
    /** [visible] = the window that owns this request is still on screen. */
    fun finished(msg: AiMessage, visible: Boolean)
}

/**
 * v1.7.28: a request in flight, held OUTSIDE the window.
 *
 * The status row, the elapsed clock and the partially streamed answer used to
 * be locals of the open window, so minimizing and re-opening threw them away
 * (user: «сворачиваю диалог, обратно открываю — оно всё пропадает»). The stream
 * now writes here and only mirrors into [onUi], which any freshly opened window
 * re-attaches to.
 */
private class LiveRequest(val chatId: String) {
    var job: kotlinx.coroutines.Job? = null
    var generation = 0
    val answer = StringBuilder()
    val reason = StringBuilder()
    var stage = ""
    var startedAt = 0L
    var active = true
    var onUi: LiveUi? = null
}

object AiAssistantSheet {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val ACCENT = AiUi.ACCENT
    private const val ACCENT_2 = AiUi.ACCENT_2

    /** Conversation the user is currently in — survives closing the window. */
    private var activeChatId: String? = null
    /** Set by [reopenNew] so the next [show] starts a fresh dialogue. */
    private var pendingNew = false
    /** Replies that arrived while the window was minimized. */
    private var unread = 0
    /** Bumped on every send/stop so a stale stream cannot write into the UI. */
    private var requestGeneration = 0
    /** The request currently in flight, if any — survives closing the window. */
    private var live: LiveRequest? = null

    /** The window currently on screen, so a tool can collapse it. */
    @Volatile
    private var openDialog: android.app.Dialog? = null

    /**
     * v1.7.46: collapse the assistant so a browser action is visible.
     *
     * The chat covers the whole screen. When the model opened a page, the page
     * loaded underneath and the user saw nothing but the chat still saying
     * «открыл» — which read as a lie. Now the window folds into the floating
     * bubble, the page is right there, and one tap brings the conversation back
     * with everything intact.
     */
    fun minimizeForAction(activity: MainActivity) {
        val d = openDialog ?: return
        try {
            if (d.isShowing) {
                d.dismiss()
                AiFloatingBubble.show(activity, unread)
            }
        } catch (_: Throwable) {}
    }

    /** Text waiting to be sent (or pre-filled) the next time the chat opens. */
    @Volatile
    private var seeded: String? = null
    @Volatile
    private var seededAsk = false

    /**
     * v1.7.48: send a page selection straight to the assistant.
     *
     * Selecting text and asking about it used to mean copy, open the chat,
     * paste, type a question. Now the selection arrives with the question
     * already written — or, with [ask], sits in the composer so the user can
     * say what they want done with it.
     */
    fun askAbout(activity: MainActivity, text: String, ask: Boolean = false) {
        if (text.isBlank()) return
        seeded = text
        seededAsk = ask
        activity.openAiChat("")
    }

    /** Files that arrived with the question — a camera shot, for now. */
    @Volatile
    private var seededFiles: List<android.net.Uri>? = null

    /**
     * v1.7.76: open the chat with files already attached and the question
     * already asked.
     *
     * The camera uses this. Reading an attachment is I/O, so the send waits for
     * it — otherwise the question would leave without its picture, which is the
     * one thing that must not happen.
     */
    fun askWithFiles(activity: MainActivity, uris: List<android.net.Uri>, text: String) {
        if (uris.isEmpty()) { askAbout(activity, text); return }
        seededFiles = uris
        seeded = text
        seededAsk = false
        activity.openAiChat("")
    }

    /**
     * v1.7.77 (user: «я сам буду его спрашивать что делать, и потом сам
     * отправлять кнопкой»).
     *
     * Photos arrive in the composer as attachments and stop there. The question
     * is the user's to write — the camera only supplies the picture, and
     * guessing the intention for them was the mistake of the first version.
     */
    fun attachFiles(activity: MainActivity, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        seededFiles = uris
        seeded = null
        seededAttachOnly = true
        activity.openAiChat("")
    }

    @Volatile
    private var seededAttachOnly = false

    /** Re-open the last conversation (used by the floating bubble). */
    fun reopen(activity: MainActivity) = activity.openAiChat("")

    /** Start a brand-new conversation (used by the floating bubble's menu). */
    fun reopenNew(activity: MainActivity) {
        pendingNew = true
        activity.openAiChat("")
    }

    fun show(activity: MainActivity, currentUrl: String = "", pageTitle: String = "", pageText: String = "") {
        val ctx: Context = activity
        val dp = { v: Int -> AiUi.dp(ctx, v) }

        AiFloatingBubble.hide(activity)
        // v1.7.33: bring the agent link up whenever the assistant is opened, so
        // the pairing is live by the time the first question is typed
        if (AgentLink.isPaired()) AgentLink.start()
        // v1.7.52: warm the TLS connection while the user is still typing, so
        // the handshake does not land inside «Подключаюсь…» on the first ask
        try {
            if (AiSwarm.active()) AiSwarm.members().take(2).forEach { AiClient.prewarm(it) }
            else AiClient.prewarm(AiModels.active())
        } catch (_: Throwable) {}
        unread = 0

        val onSurface = AiUi.onSurface(ctx)
        val onVariant = AiUi.onVariant(ctx)
        val surfaceVar = AiUi.surfaceVar(ctx)
        val codeBg = AiUi.blend(surfaceVar, onSurface, 0.10f)

        // ── conversation ──────────────────────────────────────────────────────
        var chat: AiChat = if (pendingNew) AiChatStore.newChat(ctx)
        else AiChatStore.byId(ctx, activeChatId)
            ?: AiChatStore.latestNonEmpty(ctx)
            ?: AiChatStore.newChat(ctx)
        pendingNew = false
        activeChatId = chat.id
        AiChatStore.pruneEmpty(ctx, chat.id)

        val pending = ArrayList<AiAttachment>()
        /**
         * v1.7.99 (user: «как у Claude — не ждать, а отправить ещё одно
         * сообщение, и он подхватит»).
         *
         * Messages typed while an answer is still being produced. The request
         * already in flight cannot be changed, but the assistant's tool loop
         * starts a new request every round, so a queued line can ride along
         * with the next one and reach the model mid-task.
         *
         * Synchronised because it is filled on the UI thread and drained from
         * the request's IO thread.
         */
        val queued = java.util.Collections.synchronizedList(ArrayList<String>())
        var busy = false
        var currentJob: Job? = null
        /** v1.7.48: the next answer continues the previous one, not replaces it. */
        var mergeContinuation = false
        /** true → closing collapses into the bubble; false → really close. */
        var minimize = true

        var dialogRef: android.app.Dialog? = null
        val (dialog, root) = AiUi.fullScreen(ctx, 0.05f) {
            minimize = true
            dialogRef?.dismiss()
        }
        dialogRef = dialog
        openDialog = dialog

        // ── header ────────────────────────────────────────────────────────────
        val modelLabel = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(ACCENT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val titleView = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(onSurface)
        }

        fun refreshHeader() {
            // v1.7.37: the header has to say WHO will answer — with the swarm on
            // that is a team, not a model.
            modelLabel.text = if (AiSwarm.active()) {
                val team = AiSwarm.members()
                "▾  " + ctx.getString(R.string.swarm_title) + "  ·  " +
                    ctx.getString(R.string.swarm_count, team.size)
            } else {
                val p = AiModels.active()
                // v1.7.51: show the reasoning mode when it is not the default,
                // so a slow answer is never a mystery
                val mode = if (p.thinking == "auto") ""
                else "  ·  " + AiModelDialogs.thinkLabel(ctx, p.thinking)
                "▾  " + p.name + "  ·  " + p.model + mode
            }
            titleView.text = chat.title.ifBlank { ctx.getString(R.string.set_ai_assistant) }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), dp(8))
        }
        header.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_ai)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT_2)
            ).apply { cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        })

        header.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
            isClickable = true
            setPadding(dp(2), dp(4), dp(2), dp(4))
            addView(titleView)
            addView(modelLabel)
            setOnClickListener { AiModelsSheet.show(ctx) { refreshHeader() } }
        })

        fun headerBtn(icon: Int, desc: Int, onClick: (View) -> Unit) = ImageButton(ctx).apply {
            setImageResource(icon)
            background = AiUi.ripple(ctx)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
            contentDescription = ctx.getString(desc)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { onClick(it) }
        }

        // ── message area ──────────────────────────────────────────────────────
        val messagesContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        val chatScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(messagesContainer)
        }

        /**
         * v1.7.27 — THE input bug (user: «при тапе в поле на мгновение
         * появляется иконка смайликов и тут же пропадает, в поле не пишется»).
         *
         * It was never a keyboard problem: `ScrollView.fullScroll(FOCUS_DOWN)`
         * does not only scroll — it MOVES FOCUS to a focusable child in that
         * direction, and every reply bubble hosts a selectable (= focusable)
         * TextView. So: tap the composer → focus listener → scrollToEnd() →
         * focus jumps to the last bubble → the IME connection is torn down
         * right after it was created. A perfect loop.
         *
         * Plain [android.widget.ScrollView.scrollTo] scrolls without touching
         * focus, which is all this ever needed.
         */
        fun scrollToEnd() = chatScroll.post {
            val target = (messagesContainer.bottom - chatScroll.height).coerceAtLeast(0)
            chatScroll.scrollTo(0, target)
        }

        fun openLink(url: String) {
            minimize = false
            dialog.dismiss()
            try { activity.navigate(url) } catch (_: Throwable) {}
        }

        var retryHook: (() -> Unit)? = null
        var continueHook: (() -> Unit)? = null
        var editHook: ((AiMessage) -> Unit)? = null

        fun attachmentsOf(m: AiMessage): View? {
            if (m.attachments.isEmpty()) return null
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            /** One picture, sized by its row rather than by a fixed number. */
            fun thumb(a: AiAttachment, height: Int, weight: Float) = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, height, weight)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(surfaceVar)
                }
                try { setImageURI(a.uri) } catch (_: Throwable) {}
                // v1.7.40 (user: «отправленное фото в бабле открыть уже никак
                // нельзя»): a tap opens it in the browser's own fullscreen
                // viewer — zoom, share, info, the lot.
                isClickable = true
                foreground = AiUi.ripple(ctx, borderless = false)
                setOnClickListener { openAttachment(ctx, m, a) }
            }

            // v1.9.9 (user: «баблы некрасивые, две фотки одна под другой, а
            // справа столько же пустоты»).
            //
            // Every picture was a fixed 150×150 in a single column, while the
            // bubble is as wide as its text — so a message with two photographs
            // showed a narrow stack down the left and a band of empty bubble
            // down the right.
            //
            // They now fill the width they are given: one picture spans the
            // bubble, several tile two to a row. Nothing is measured against a
            // fixed number any more, so it looks the same on a small phone and
            // a wide one.
            val images = m.attachments.filter { it.isImage }
            val others = m.attachments.filter { !it.isImage }

            if (images.size == 1) {
                col.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    addView(thumb(images[0], dp(200), 1f))
                })
            } else {
                for (row in images.chunked(2)) {
                    col.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(6) }
                        addView(thumb(row[0], dp(140), 1f))
                        if (row.size > 1) {
                            addView(View(ctx).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(6), dp(1))
                            })
                            addView(thumb(row[1], dp(140), 1f))
                        } else {
                            // a lone picture on the last row keeps its half, so
                            // the grid stays a grid instead of stretching
                            addView(View(ctx).apply {
                                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                            })
                        }
                    })
                }
            }
            for (a in others) col.addView(fileChip(ctx, a.name, onSurface, surfaceVar, dp, null))
            return col
        }


        /**
         * v1.7.50: a path the assistant mentioned becomes a proper file card —
         * icon by type, name, size, and the folder underneath. Reads as a file
         * instead of a run of grey text, and carries its own actions.
         */
        fun attachFileCards(into: LinearLayout, text: String) {
            val paths = Regex("""(?<![\w/])(/(?:storage|sdcard|mnt)/[\w.\-@+ /]{2,180})""")
                .findAll(text)
                .map { it.groupValues[1].trimEnd('.', ',', ';', ':', ')', '»', '"') }
                .distinct().take(4).toList()
            for (p in paths) {
                val f = java.io.File(p)
                if (!f.exists()) continue
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(13).toFloat()
                        setColor(AiUi.withAlpha(onVariant, 0x16))
                    }
                    foreground = AiUi.ripple(ctx, borderless = false)
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                }
                row.addView(ImageView(ctx).apply {
                    setImageResource(iconForFile(f))
                    imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        .apply { marginEnd = dp(11) }
                })
                row.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        // `this.` is required: the enclosing function has a
                        // parameter named `text`, which would shadow it here
                        this.text = f.name
                        textSize = 13.5f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setTextColor(onSurface)
                    })
                    addView(TextView(ctx).apply {
                        val size = if (f.isDirectory) ctx.getString(R.string.ai_path_folder)
                        else humanSize(f.length())
                        this.text = listOf(size, f.parent.orEmpty()).filter { it.isNotBlank() }
                            .joinToString("  ·  ")
                        textSize = 11f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setPadding(0, dp(2), 0, 0)
                        setTextColor(onVariant)
                    })
                })
                row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                })
                row.setOnClickListener { openInFiles(activity, p) }
                row.setOnLongClickListener { pathMenu(activity, it, p); true }
                into.addView(row)
            }
        }


        /** Zephyr's own "being generated" block — a travelling wave of dots. */
        fun mediaPlaceholder(): Pair<View, TextView> {
            val caption = TextView(ctx).apply {
                setText(R.string.ai_media_working)
                textSize = 12f
                setTextColor(onVariant)
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(AiUi.withAlpha(ACCENT, 0x14))
                    setStroke(dp(1), AiUi.withAlpha(ACCENT, 0x3A))
                }
                // v1.9.8 (user: «анимация не под размер фото»): the placeholder
                // now occupies exactly the frame the picture will occupy —
                // MATCH_PARENT × dp(220), the same as buildImageView. It used to
                // wrap its content, so the bubble grew when the image landed and
                // the chat jumped under the reader's thumb.
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
                ).apply { topMargin = dp(8) }
                addView(com.zephyr.browser.ui.GenerativeWaveView(ctx).apply {
                    tintStart = ACCENT
                    tintEnd = ACCENT_2
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
                addView(caption)
            }
            return box to caption
        }

        fun buildImageView(bmp: android.graphics.Bitmap): ImageView = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            ).apply { topMargin = dp(8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(surfaceVar)
            }
            isClickable = true
            foreground = AiUi.ripple(ctx, borderless = false)
            setImageBitmap(bmp)
            setOnClickListener { openImageBitmap(ctx, bmp) }
            // v1.7.48: a generated picture you cannot keep is only half a
            // feature — long press saves it to Pictures/Zephyr
            setOnLongClickListener { saveGeneratedImage(activity, bmp); true }
        }

        fun buildAudioRow(url: String): View {
            val player = android.media.MediaPlayer()
            val icon = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_play)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                    .apply { marginEnd = dp(10) }
            }
            val label = TextView(ctx).apply {
                setText(R.string.ai_audio_play)
                textSize = 13f
                setTextColor(onSurface)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            var prepared = false
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(AiUi.withAlpha(ACCENT, 0x2E))
                }
                foreground = AiUi.ripple(ctx, borderless = false)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                addView(icon)
                addView(label)
                setOnClickListener {
                    try {
                        if (player.isPlaying) {
                            player.pause()
                            icon.setImageResource(R.drawable.ic_play)
                            label.setText(R.string.ai_audio_play)
                        } else if (!prepared) {
                            label.setText(R.string.ai_audio_loading)
                            player.setDataSource(url)
                            player.setOnPreparedListener {
                                prepared = true; it.start()
                                icon.setImageResource(R.drawable.ic_pause)
                                label.setText(R.string.ai_audio_pause)
                            }
                            player.setOnCompletionListener {
                                icon.setImageResource(R.drawable.ic_play)
                                label.setText(R.string.ai_audio_play)
                            }
                            player.setOnErrorListener { _, _, _ -> label.setText(R.string.ai_audio_failed); true }
                            player.prepareAsync()
                        } else {
                            player.start()
                            icon.setImageResource(R.drawable.ic_pause)
                            label.setText(R.string.ai_audio_pause)
                        }
                    } catch (_: Throwable) { label.setText(R.string.ai_audio_failed) }
                }
            }
        }


        /**
         * Media already shown in this conversation.
         *
         * v1.9.7 (user: «под плеером дублирующая картинка, которая была
         * сгенерирована до этого»). The model had echoed an earlier image's URL
         * at the top of a later answer, and every answer was scanned on its own,
         * so the same picture was drawn a second time. One id, one bubble.
         *
         * Cleared with the chat, not with the message — the point is the
         * conversation.
         */
        val shownMedia = LinkedHashSet<String>()

        /** Probe [url], keep retrying while it is still being generated. */
        fun attachAnswerMedia(into: LinearLayout, text: String, isError: Boolean = false) {
            // v1.7.53 FIX (user: «на сообщении об ошибке внизу два окна
            // „Генерирую 127 сек"»): an error text QUOTES the endpoint it failed
            // to reach. That url sits on the model's own host, so it passed the
            // media filter, answered 404, and 404 meant «still being drawn».
            // Error messages never carry media — do not look.
            if (isError) return
            for (url in mediaCandidates(text)) {
                // v1.9.7: one id, one bubble, for the whole conversation — a
                // model that quotes an earlier picture's URL must not redraw it
                if (!shownMedia.add(url)) continue
                val (holder, caption) = mediaPlaceholder()
                holder.visibility = View.GONE          // nothing shown until we know
                into.addView(holder)

                // v1.9.9 (user: «захожу обратно — картинок нет, опять идёт
                // генерация, ждать долго, иногда не появляется вовсе»).
                //
                // 1.9.8 started caching the bytes, but this loop still began
                // with a network probe, so reopening a chat re-ran the whole
                // ceremony — placeholder, request, wait — for a picture already
                // sitting on disk. And once the bridge expires its copy (about
                // three days) the probe fails and the picture never returns,
                // even though we have it.
                //
                // The cache is therefore consulted BEFORE anything touches the
                // network. An old answer now paints instantly and offline.
                val onDisk = mediaCacheFile(url)?.takeIf { it.length() > 0 }
                if (onDisk != null) {
                    ioScope.launch {
                        val bmp = try {
                            android.graphics.BitmapFactory.decodeFile(onDisk.absolutePath)
                        } catch (_: Throwable) { null }
                        withContext(Dispatchers.Main) {
                            into.removeView(holder)
                            into.addView(
                                if (bmp != null) buildImageView(bmp)
                                else buildAudioRow(onDisk.absolutePath)
                            )
                        }
                    }
                    continue
                }

                ioScope.launch {
                    val started = System.currentTimeMillis()
                    var shown = false
                    var attempt = 0
                    while (attempt < 45 && !shown) {        // ~3 minutes of patience
                        val probe = AiClient.probeMedia(url)
                        when {
                            probe.kind == AiClient.MediaKind.IMAGE -> {
                                val bmp = loadAnswerImage(url)
                                withContext(Dispatchers.Main) {
                                    if (bmp != null) {
                                        into.removeView(holder)
                                        into.addView(buildImageView(bmp))
                                        scrollToEnd()
                                    } else holder.visibility = View.GONE
                                }
                                shown = true
                            }
                            probe.kind == AiClient.MediaKind.AUDIO -> {
                                // v1.9.8 (user: «жму плей, второй раз плей, а в
                                // это время идёт генерация»). The player used to
                                // appear the moment the URL answered, and then
                                // MediaPlayer streamed the file on the first
                                // press — so the first press did nothing visible
                                // and the user pressed again.
                                //
                                // The bytes are fetched here instead, while the
                                // generating animation is still up, and the
                                // player is built on the local copy. It plays on
                                // the first press, and it plays offline
                                // afterwards.
                                val local = fetchToCache(url)
                                withContext(Dispatchers.Main) {
                                    into.removeView(holder)
                                    into.addView(buildAudioRow(local ?: url))
                                    scrollToEnd()
                                }
                                shown = true
                            }
                            probe.kind == AiClient.MediaKind.PENDING -> {
                                // the file is being made right now — show the
                                // shimmer and come back in a moment
                                withContext(Dispatchers.Main) {
                                    if (holder.visibility != View.VISIBLE) {
                                        holder.visibility = View.VISIBLE
                                        scrollToEnd()
                                    }
                                    val secs = (System.currentTimeMillis() - started) / 1000
                                    caption.text = ctx.getString(R.string.ai_media_working) +
                                        "   " + secs + " " + ctx.getString(R.string.ai_seconds)
                                }
                                kotlinx.coroutines.delay(4000)
                            }
                            else -> {
                                // an ordinary link — leave it as text
                                withContext(Dispatchers.Main) { into.removeView(holder) }
                                shown = true
                            }
                        }
                        attempt++
                    }
                    if (!shown) withContext(Dispatchers.Main) {
                        caption.setText(R.string.ai_media_timeout)
                    }
                }
            }
        }

        fun renderMessage(m: AiMessage, animate: Boolean): View {
            val isUser = m.role == "user"
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (isUser) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val maxW = (ctx.resources.displayMetrics.widthPixels * 0.84f).toInt()

            val bubble = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = GradientDrawable().apply {
                    val r = dp(18).toFloat()
                    val small = dp(6).toFloat()
                    cornerRadii = if (isUser)
                        floatArrayOf(r, r, r, r, small, small, r, r)
                    else
                        floatArrayOf(r, r, r, r, r, r, small, small)
                    if (isUser) {
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors = intArrayOf(ACCENT, ACCENT_2)
                    } else if (m.error) {
                        setColor(AiUi.blend(surfaceVar, 0xFFD93025.toInt(), 0.16f))
                    } else {
                        setColor(surfaceVar)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            attachmentsOf(m)?.let { bubble.addView(it) }

            // assistant bubbles ALWAYS get a text view — an empty one is the
            // target the streaming tokens are appended to
            var textView: TextView? = null
            if (m.text.isNotBlank() || !isUser) {
                val tv = TextView(ctx).apply {
                    tag = "body"
                    textSize = 14f
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setTextColor(if (isUser) Color.WHITE else onSurface)
                    setTextIsSelectable(true)
                    this.maxWidth = maxW
                }
                if (isUser) {
                    tv.text = m.text
                } else if (m.text.isNotBlank()) {
                    // v1.9.7: the URLs that are about to become bubbles never
                    // appear in the prose — see stripMediaUrls
                    val bodyForDisplay =
                        if (isUser || m.error) m.text
                        else stripMediaUrls(m.text, mediaCandidates(m.text))
                    tv.text = AiMarkdown.render(
                        bodyForDisplay, onSurface, onVariant, ACCENT, codeBg,
                        onLink = { openLink(it) },
                        onPath = { openInFiles(activity, it) }
                    )
                    tv.movementMethod = LinkMovementMethod.getInstance()
                    tv.highlightColor = AiUi.withAlpha(ACCENT, 0x40)
                }
                bubble.addView(tv)
                textView = tv
                if (!isUser && !m.error) {
                    attachAnswerMedia(bubble, m.text, m.error)
                    attachFileCards(bubble, m.text)
                }
            }

            // v1.9.8 (user: «жму поделиться — а там внутри ссылка на мой мост»).
            // The bubble hides media URLs, but copy and share were still sending
            // the raw text — so sharing a picture handed over the address of a
            // private bridge, and anyone receiving it could open its root.
            // What leaves the app is what the user can see.
            val shareText = {
                AiMarkdown.stripMarkdown(
                    if (isUser || m.error) m.text
                    else stripMediaUrls(m.text, mediaCandidates(m.text))
                )
            }
            fun copyAll() {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ai", shareText()))
                Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            fun shareAll() {
                ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText())
                        }, ctx.getString(R.string.ai_share)
                    )
                )
            }
            /** Raise the system selection toolbar so a FRAGMENT can be copied. */
            fun selectText() {
                val tv = textView ?: return
                try {
                    tv.requestFocus()
                    android.text.Selection.selectAll(tv.text as? android.text.Spannable ?: return)
                    tv.performLongClick()
                } catch (_: Throwable) {}
            }

            bubble.isLongClickable = true
            bubble.setOnLongClickListener {
                val menu = PopupMenu(ctx, it)
                menu.menu.add(0, 1, 0, ctx.getString(R.string.ai_copy))
                menu.menu.add(0, 2, 1, ctx.getString(R.string.ai_select_text))
                menu.menu.add(0, 3, 2, ctx.getString(R.string.ai_share))
                if (!isUser) menu.menu.add(0, 4, 3, ctx.getString(R.string.ai_retry))
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> copyAll(); 2 -> selectText(); 3 -> shareAll(); 4 -> retryHook?.invoke()
                    }
                    true
                }
                menu.show()
                true
            }

            wrap.addView(bubble)

            // ── icon-only action strip (user: «можно не писать слова») ────────
            if (m.text.isNotBlank()) {
                fun actionBtn(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(ctx).apply {
                    setImageResource(icon)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(11).toFloat()
                        setColor(AiUi.withAlpha(onVariant, 0x14))
                    }
                    foreground = AiUi.ripple(ctx, borderless = false)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
                    contentDescription = ctx.getString(desc)
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                        .apply { marginEnd = dp(6) }
                    setOnClickListener { onClick() }
                }
                val strip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (isUser) Gravity.END else Gravity.START
                    setPadding(dp(2), dp(4), dp(2), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                strip.addView(actionBtn(R.drawable.ic_copy, R.string.ai_copy) { copyAll() })
                strip.addView(actionBtn(R.drawable.ic_text_file, R.string.ai_select_text) { selectText() })
                strip.addView(actionBtn(R.drawable.ic_share, R.string.ai_share) { shareAll() })
                if (!isUser) strip.addView(
                    actionBtn(R.drawable.ic_replay, R.string.ai_retry) { retryHook?.invoke() }
                )
                // v1.7.48: the model hit its token ceiling mid-sentence. Offer
                // to carry on instead of leaving the user to spot the cut and
                // type "продолжи" themselves.
                if (!isUser && m.truncated) strip.addView(
                    actionBtn(R.drawable.ic_arrow_down, R.string.ai_continue) { continueHook?.invoke() }
                )
                // v1.7.48: fix a typo without retyping the whole question —
                // the message goes back into the composer and everything after
                // it is dropped, so the conversation re-runs from that point.
                if (isUser) strip.addView(
                    actionBtn(R.drawable.ic_edit, R.string.ai_edit_msg) { editHook?.invoke(m) }
                )
                // v1.7.27: token / timing readout (Settings → «Показывать
                // расход токенов»), the Roo-Code style accounting the user asked
                // about. Providers report it in the stream's final frame; a
                // provider that reports nothing simply shows the elapsed time.
                // v1.7.44: WHO answered is shown on every reply, always. The
                // swarm quietly answering in place of the selected profile cost
                // an entire evening of debugging precisely because nothing on
                // screen ever named the responder.
                val showFooter = !isUser &&
                    (m.via.isNotBlank() || (Prefs.aiShowUsage && (m.tokens > 0 || m.elapsedMs > 0)))
                if (showFooter) {
                    val bits = ArrayList<String>()
                    if (m.via.isNotBlank()) bits.add(m.via)
                    if (Prefs.aiShowUsage && m.requests > 1)
                        bits.add(ctx.getString(R.string.ai_requests_fmt, m.requests))
                    if (Prefs.aiShowUsage && m.tokens > 0) bits.add(ctx.getString(R.string.ai_tokens_fmt, m.tokens))
                    if (Prefs.aiShowUsage && m.elapsedMs > 0)
                        bits.add((m.elapsedMs / 1000).toString() + " " + ctx.getString(R.string.ai_seconds))
                    strip.addView(TextView(ctx).apply {
                        text = bits.joinToString(" · ")
                        textSize = 10.5f
                        setTextColor(AiUi.withAlpha(onVariant, 0xB0))
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    })
                }
                wrap.addView(strip)
            }

            if (animate) {
                wrap.alpha = 0f
                wrap.translationY = dp(10).toFloat()
                wrap.animate().alpha(1f).translationY(0f).setDuration(220).start()
            }
            return wrap
        }

        /**
         * v1.7.38 (user: «добавить число и дату по середине в маленьком
         * бейджике в чате»): a centred day badge, inserted whenever the next
         * message falls on a different calendar day than the previous one.
         */
        var lastDayStamp = ""

        fun dayLabel(ts: Long): String {
            val cal = java.util.Calendar.getInstance()
            val now = java.util.Calendar.getInstance()
            cal.timeInMillis = ts
            val sameYear = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
            val dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - cal.get(java.util.Calendar.DAY_OF_YEAR)
            return when {
                sameYear && dayDiff == 0 -> ctx.getString(R.string.ai_today)
                sameYear && dayDiff == 1 -> ctx.getString(R.string.ai_yesterday)
                sameYear -> java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault())
                    .format(java.util.Date(ts))
                else -> java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date(ts))
            }
        }

        fun dayKey(ts: Long): String =
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(ts))

        fun dayBadge(ts: Long): View = TextView(ctx).apply {
            text = dayLabel(ts)
            textSize = 11f
            setTextColor(onVariant)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(AiUi.withAlpha(onVariant, 0x1F))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4); bottomMargin = dp(10)
            }
        }

        /** Add a message, prefixing a day badge when the date rolls over. */
        fun appendMessage(m: AiMessage, animate: Boolean) {
            val key = dayKey(m.at)
            if (key != lastDayStamp) {
                lastDayStamp = key
                messagesContainer.addView(dayBadge(m.at))
            }
            messagesContainer.addView(renderMessage(m, animate))
        }

        /**
         * v1.7.99: a message typed while the assistant is still answering.
         *
         * Drawn straight away, faded, so the user can see it was accepted and
         * stop wondering — but visibly not yet part of the conversation. It is
         * the same bubble the message will become, so nothing jumps when it is
         * picked up; only the fade lifts.
         */
        val queuedViews = HashMap<String, View>()

        fun appendQueued(text: String) {
            val view = renderMessage(AiMessage("user", text), true)
            view.alpha = 0.5f
            queuedViews[text] = view
            messagesContainer.addView(view)
        }

        /** The loop took it: it is a real turn now. */
        fun promoteQueued(text: String) {
            queuedViews.remove(text)?.animate()?.alpha(1f)?.setDuration(180)?.start()
        }

        /**
         * Take the faded bubble away again.
         *
         * Used when the message could not join the running answer and has to be
         * sent as a fresh turn instead — that path draws its own bubble, and
         * without this the same line would appear twice.
         */
        fun discardQueued(text: String) {
            queuedViews.remove(text)?.let { messagesContainer.removeView(it) }
        }

        // ── empty state ───────────────────────────────────────────────────────

        lateinit var sendText: (String) -> Unit
        var emptyState: View? = null

        fun buildEmptyState(): View {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(24), dp(4), dp(8))
            }
            col.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.ai_greeting_title)
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(onSurface)
                gravity = Gravity.CENTER
            })
            col.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.ai_greeting_sub)
                textSize = 13f
                setTextColor(onVariant)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(20))
            })

            val hasPage = currentUrl.isNotBlank() && !currentUrl.startsWith("about:")
            if (!hasPage) return col
            val suggestions = listOf(
                "📝" to ctx.getString(R.string.ai_sug_summary),
                "🌐" to ctx.getString(R.string.ai_sug_translate),
                "💡" to ctx.getString(R.string.ai_sug_explain),
                "🎯" to ctx.getString(R.string.ai_sug_key)
            )
            for ((emoji, label) in suggestions) {
                col.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(surfaceVar)
                    }
                    foreground = AiUi.ripple(ctx, borderless = false)
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    addView(TextView(ctx).apply {
                        text = emoji
                        textSize = 17f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(12) }
                    })
                    addView(TextView(ctx).apply {
                        text = label
                        textSize = 14f
                        setTextColor(onSurface)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_chevron_right)
                        imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                    })
                    setOnClickListener {
                        sendText(
                            // v1.7.86: these are instructions for the MODEL, so
                            // they are English — but the LANGUAGE they ask for
                            // is the user's. "Translate this page" used to say
                            // «на русский язык» outright, so a French user who
                            // tapped "Traduire la page" got Russian back.
                            when (label) {
                                ctx.getString(R.string.ai_sug_summary) ->
                                    "Give a short, structured summary of the open page \"$pageTitle\". " +
                                        "Answer in " + AiLang.name(ctx) + "."
                                ctx.getString(R.string.ai_sug_translate) ->
                                    "Translate the main content of the open page into " +
                                        AiLang.name(ctx) + "."
                                ctx.getString(R.string.ai_sug_explain) ->
                                    "Explain in plain words what this page is about. " +
                                        "Answer in " + AiLang.name(ctx) + "."
                                else -> "List the key points of this page. " +
                                    "Answer in " + AiLang.name(ctx) + "."
                            }
                        )
                    }
                })
            }
            return col
        }

        fun showEmptyState() {
            if (chat.messages.isEmpty() && emptyState == null) {
                emptyState = buildEmptyState().also { messagesContainer.addView(it) }
            }
        }
        fun hideEmptyState() {
            emptyState?.let { messagesContainer.removeView(it) }
            emptyState = null
        }

        /** Swap the whole window over to another saved conversation. */
        fun loadChat(c: AiChat) {
            chat = c
            activeChatId = c.id
            messagesContainer.removeAllViews()
            emptyState = null
            lastDayStamp = ""
            // v1.9.7: the "shown once" rule is per conversation, and the views
            // are being rebuilt from scratch here — without this, reopening a
            // chat would suppress every picture it already contains.
            shownMedia.clear()
            for (m in c.messages) appendMessage(m, false)
            showEmptyState()
            refreshHeader()
            scrollToEnd()
        }

        // ── live status line (replaces the blind three dots) ──────────────────
        // v1.7.26 (user: «я отправил сообщение и не вижу, что происходит — ИИ
        // думает или нет, или проблемы какие-то»): a real status row — spinner,
        // stage text, elapsed seconds, a Stop button and an expandable
        // «Размышления» block fed by the model's reasoning stream.
        val statusText = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(ACCENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        }
        val statusSpinner = android.widget.ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        val stopBtn = TextView(ctx).apply {
            text = ctx.getString(R.string.ai_stop)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(AiUi.withAlpha(onVariant, 0x2A))
            }
            isClickable = true
        }
        val reasoningBody = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(onVariant)
            visibility = View.GONE
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }
        val reasoningToggle = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(onVariant)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            isClickable = true
            text = "▸ " + ctx.getString(R.string.ai_thinking_block)
            setOnClickListener {
                val open = reasoningBody.visibility == View.VISIBLE
                reasoningBody.visibility = if (open) View.GONE else View.VISIBLE
                text = (if (open) "▸ " else "▾ ") + ctx.getString(R.string.ai_thinking_block)
            }
        }
        val statusBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(2), dp(14), dp(6))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(AiUi.withAlpha(ACCENT, 0x1A))
                }
                addView(statusSpinner)
                addView(statusText)
                // v1.9.12 (user: «в этом поле есть кнопка отмена, хотя у нас
                // уже есть круглая кнопка отмены»). The "Stop" chip used to sit
                // here, next to the status. It did the same thing as the round
                // composer button, one row below it, so the window offered two
                // ways to cancel the same answer and neither looked primary.
                //
                // stopBtn itself stays — it is where the cancel logic lives,
                // and the round button drives it through performClick(). Only
                // the duplicate chip is gone from the layout.
            })
            addView(reasoningToggle)
            addView(reasoningBody)
        }

        // ── composer ──────────────────────────────────────────────────────────

        val attachRow = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val attachStrip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(6))
        }
        attachRow.addView(attachStrip)

        fun refreshAttachStrip() {
            attachStrip.removeAllViews()
            attachRow.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
            for (a in pending.toList()) {
                val chip = if (a.isImage) {
                    FrameLayout(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
                            .apply { marginEnd = dp(8) }
                        addView(ImageView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(60), dp(60))
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            clipToOutline = true
                            background = GradientDrawable().apply {
                                cornerRadius = dp(12).toFloat(); setColor(surfaceVar)
                            }
                            try { setImageURI(a.uri) } catch (_: Throwable) {}
                        })
                        addView(ImageView(ctx).apply {
                            setImageResource(R.drawable.ic_close)
                            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(0xCC000000.toInt())
                            }
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END)
                            setOnClickListener { pending.remove(a); refreshAttachStrip() }
                        })
                    }
                } else {
                    fileChip(ctx, a.name, onSurface, surfaceVar, dp) {
                        pending.remove(a); refreshAttachStrip()
                    }
                }
                attachStrip.addView(chip)
            }
        }

        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.ai_ask_hint)
            textSize = 14f
            maxLines = 5
            background = null
            setTextColor(onSurface)
            setHintTextColor(onVariant)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isFocusable = true
            isFocusableInTouchMode = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        /**
         * v1.7.26 FIX (user: «нажал стоп, но в поле ввода написать уже не
         * получается — клава появилась, а в поле не пишется»): every reply
         * bubble hosts a SELECTABLE TextView, and a selectable TextView is
         * focusable in touch mode — adding one steals the focus from the
         * composer while the keyboard stays open, so the typing went nowhere.
         * The composer takes its focus back after every list mutation.
         */
        fun reclaimInput() {
            input.post {
                input.isEnabled = true
                input.isFocusableInTouchMode = true
                if (!input.hasFocus()) input.requestFocus()
            }
        }

        val sendBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_arrow_up)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT_2)
            ).apply { shape = GradientDrawable.OVAL }
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }

        val attachBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_add)
            background = AiUi.ripple(ctx)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
            contentDescription = ctx.getString(R.string.ai_attach)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        }

        // v1.7.26: dictation
        val micBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_mic)
            background = AiUi.ripple(ctx)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
            contentDescription = ctx.getString(R.string.ai_voice)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                activity.startAiVoiceInput { spoken ->
                    val cur = input.text.toString()
                    input.setText(if (cur.isBlank()) spoken else cur.trimEnd() + " " + spoken)
                    input.setSelection(input.text.length)
                    reclaimInput()
                }
            }
        }

        fun ingest(uris: List<Uri>) {
            if (uris.isEmpty()) return
            Toast.makeText(ctx, R.string.ai_reading_file, Toast.LENGTH_SHORT).show()
            ioScope.launch {
                val read = uris.mapNotNull { AiClient.readAttachment(ctx, it) }
                withContext(Dispatchers.Main) {
                    if (read.isEmpty()) {
                        Toast.makeText(ctx, R.string.ai_attachment_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        pending.addAll(read)
                        refreshAttachStrip()
                    }
                }
            }
        }

        /**
         * v1.7.77 (user: «в чём разница фото и изображение? „снять камерой" —
         * а чем я могу снять, жопой?»).
         *
         * Both fair. "Фото или изображение" named one thing twice, and the
         * camera entry described the instrument instead of the action. Three
         * sources, three icons, three words: Камера · Изображение · Документ.
         */
        /**
         * v1.7.78 (user: «почему ты не сохранил маленькое меню, а пошёл делать
         * выезжающее снизу?»).
         *
         * Right — the ask was for icons in the menu that was already there, not
         * for a different kind of surface. A popup next to the button is the
         * correct weight for three choices; a sheet climbing up from the bottom
         * of the screen is what you use for a screenful of options.
         *
         * Icons in a PopupMenu are hidden by the platform unless asked for, so
         * they are asked for.
         */
        attachBtn.setOnClickListener { v ->
            val menu = PopupMenu(ctx, v)
            menu.menu.add(0, 1, 0, ctx.getString(R.string.ai_attach_camera))
                .setIcon(R.drawable.ic_camera)
            menu.menu.add(0, 2, 1, ctx.getString(R.string.ai_attach_photo))
                .setIcon(R.drawable.ic_image)
            menu.menu.add(0, 3, 2, ctx.getString(R.string.ai_attach_doc))
                .setIcon(R.drawable.ic_file)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try { menu.setForceShowIcon(true) } catch (_: Throwable) {}
            }
            // v1.7.79 (user: «иконки чёрные, а названия белые»). A PopupMenu
            // tints its TEXT from the theme but leaves the icon's own colour
            // alone — which is black here, and invisible on a dark menu. Each
            // icon is tinted to match the label it sits beside.
            val menuTint = AiUi.onSurface(ctx)
            for (i in 0 until menu.menu.size()) {
                menu.menu.getItem(i).icon?.mutate()?.setTint(menuTint)
            }
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> activity.openAiCamera()
                    2 -> activity.pickAiAttachment(true) { uris -> ingest(uris) }
                    else -> activity.pickAiAttachment(false) { uris -> ingest(uris) }
                }
                true
            }
            menu.show()
        }

        // ── send pipeline ─────────────────────────────────────────────────────

        fun notConfigured() {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.ai_no_key_title)
                .setMessage(R.string.ai_no_key_msg)
                .setPositiveButton(R.string.ai_add_model) { _, _ ->
                    AiModelDialogs.editProfile(ctx, null) { refreshHeader() }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        /** The bubble currently being streamed into, and its text view. */
        var liveView: View? = null
        var liveText: TextView? = null

        val ticker = object : Runnable {
            override fun run() {
                val l = live ?: return
                if (!l.active) return
                val secs = (System.currentTimeMillis() - l.startedAt) / 1000
                statusText.text = l.stage + "   " + secs + " " + ctx.getString(R.string.ai_seconds)
                statusText.postDelayed(this, 1000)
            }
        }

        /**
         * v1.7.99.1 (user: «кнопка с крестика на отправку не меняется, когда
         * текст набираю»).
         *
         * The icon is now a function of two things — whether an answer is
         * running and whether there is anything to send — instead of being
         * stamped once when the request started. It has to be re-evaluated on
         * every keystroke, because during an answer the same button means
         * "stop" with an empty box and "send" with text in it, and a button
         * whose picture disagrees with what it does is worse than either.
         */
        fun refreshSendIcon() {
            val hasText = input.text?.toString()?.trim().isNullOrEmpty().not()
            sendBtn.setImageResource(
                if (busy && !hasText) R.drawable.ic_close else R.drawable.ic_arrow_up
            )
        }

        fun clearBusyUi() {
            busy = false
            statusBar.visibility = View.GONE
            statusText.removeCallbacks(ticker)
            refreshSendIcon()
            reasoningBody.text = ""
            reclaimInput()
        }

        /** Make the streaming placeholder exist and show [text] in it. */
        fun ensureLiveBubble(text: String) {
            if (liveView == null) {
                val holder = renderMessage(AiMessage("assistant", ""), false)
                liveView = holder
                liveText = holder.findViewWithTag<TextView>("body")
                messagesContainer.addView(holder)
            }
            // v1.9.12 (user: «когда ищет, в бабле появляются пустые строки и
            // бабл спускается вниз, как будто что-то пишется, а там пусто»).
            //
            // Before a tool call the model usually emits a couple of bare
            // newlines. They are real content deltas, so the bubble grew by a
            // blank line each time and the list scrolled after it. Trailing
            // whitespace has nothing to show while streaming; the finished
            // message is rendered separately and keeps its own formatting.
            val shown = text.trimEnd()
            liveView?.visibility = if (shown.isBlank()) View.GONE else View.VISIBLE
            liveText?.text = shown
        }

        /**
         * v1.7.28 (user: «свернул диалог, открыл — статус и часы пропали, так не
         * должно быть»): the in-flight request lives in [live], an object-level
         * holder, not in this window's locals. Re-opening the window simply
         * re-attaches its views to the running request — the status row, the
         * elapsed clock, the reasoning block and the partially streamed answer
         * all come back exactly where they were.
         */
        fun bindLive() {
            val l = live ?: return
            l.onUi = object : LiveUi {
                override fun status(stage: String) { statusText.text = stage }

                override fun reasoning(all: String) {
                    if (reasoningToggle.visibility != View.VISIBLE)
                        reasoningToggle.visibility = View.VISIBLE
                    reasoningBody.text = all.takeLast(1200)
                }

                override fun answer(all: String) {
                    ensureLiveBubble(all)
                    scrollToEnd()
                }

                override fun finished(msg: AiMessage, visible: Boolean) {
                    clearBusyUi()
                    liveView?.let { messagesContainer.removeView(it) }
                    liveView = null; liveText = null
                    if (visible) {
                        appendMessage(msg, false)
                        refreshHeader()
                        scrollToEnd()
                        reclaimInput()
                    }
                }
            }
            // paint the current state immediately
            busy = true
            statusBar.visibility = View.VISIBLE
            statusText.text = l.stage
            statusText.removeCallbacks(ticker)
            statusText.postDelayed(ticker, 250)
            refreshSendIcon()
            if (l.reason.isNotEmpty()) {
                reasoningToggle.visibility = View.VISIBLE
                reasoningBody.text = l.reason.takeLast(1200)
            }
            if (l.answer.isNotEmpty()) ensureLiveBubble(l.answer.toString())
        }

        fun runRequest() {
            val snapshot = ArrayList(chat.messages)
            val target = chat
            val profile = AiModels.active()
            val l = LiveRequest(chat.id)
            l.stage = ctx.getString(R.string.ai_connecting)
            l.startedAt = System.currentTimeMillis()
            live = l
            val generation = ++requestGeneration
            l.generation = generation

            reasoningToggle.visibility = View.GONE
            reasoningBody.visibility = View.GONE
            bindLive()
            scrollToEnd()

            l.job = ioScope.launch {
                AiClient.chatStream(
                    snapshot, profile, pageContextOf(pageTitle, currentUrl, pageText),
                    object : AiClient.StreamSink {
                        override fun isActive(): Boolean =
                            l.active && generation == requestGeneration

                        /**
                         * v1.7.99: hand the loop whatever the user typed while
                         * this answer was running.
                         *
                         * Taken, not peeked — the message leaves the queue here
                         * and becomes part of the conversation, so it is shown
                         * as a real user turn rather than staying greyed out.
                         */
                        override fun takePendingUserMessage(): String? {
                            val next = synchronized(queued) {
                                if (queued.isEmpty()) null else queued.removeAt(0)
                            } ?: return null
                            val msg = AiMessage("user", next)
                            target.messages.add(msg)
                            AiChatStore.touch(ctx, target)
                            activity.runOnUiThread {
                                if (generation == requestGeneration) promoteQueued(next)
                            }
                            return next
                        }

                        override fun onStatus(text: String) {
                            l.stage = text
                            activity.runOnUiThread { l.onUi?.status(text) }
                        }

                        override fun onReasoning(delta: String) {
                            l.reason.append(delta)
                            val all = l.reason.toString()
                            activity.runOnUiThread { l.onUi?.reasoning(all) }
                        }

                        override fun onDelta(delta: String) {
                            l.answer.append(delta)
                            val all = l.answer.toString()
                            activity.runOnUiThread { l.onUi?.answer(all) }
                        }

                        override fun onDone(result: AiResult) {
                            // v1.7.48: a continuation is folded into the answer
                            // it continues, and the synthetic "carry on"
                            // instruction is dropped from the history
                            if (mergeContinuation) {
                                mergeContinuation = false
                                val idx = target.messages.indexOfLast { it.role == "assistant" }
                                if (idx >= 0 && result.ok) {
                                    val prev = target.messages[idx]
                                    // drop the synthetic user turn that asked to continue
                                    while (target.messages.size > idx + 1) {
                                        target.messages.removeAt(target.messages.size - 1)
                                    }
                                    target.messages[idx] = prev.copy(
                                        text = prev.text.trimEnd() + " " + result.text.trimStart(),
                                        truncated = result.truncated,
                                        tokens = prev.tokens + (result.usage?.total ?: 0)
                                    )
                                    AiChatStore.touch(ctx, target)
                                    // v1.7.58 CRASH FIX (user: «вышел краш —
                                    // CalledFromWrongThreadException… clearBusyUi
                                    // … DefaultDispatcher-worker-4»).
                                    //
                                    // onDone arrives on the request's IO thread.
                                    // The main branch below always hopped to the
                                    // main thread; THIS early return did not, so
                                    // a continuation that merged into its answer
                                    // called setVisibility from a worker and took
                                    // the app down. Same hop, same guards.
                                    activity.runOnUiThread {
                                        val ui = l.onUi
                                        if (live === l) live = null
                                        if (ui != null && generation == requestGeneration) {
                                            clearBusyUi()
                                            liveView?.let { messagesContainer.removeView(it) }
                                            liveView = null; liveText = null
                                            loadChat(target)
                                            reclaimInput()
                                        }
                                        // a merged continuation is still a
                                        // finished answer — announce it too
                                        if (!com.zephyr.browser.MainActivity.onScreen) {
                                            AiReplyNotice.post(ctx, plainAnswerText(result.text))
                                        }
                                    }
                                    return
                                }
                            }
                            val msg = AiMessage(
                                "assistant", result.text, error = !result.ok,
                                tokens = result.usage?.total ?: 0,
                                elapsedMs = System.currentTimeMillis() - l.startedAt,
                                via = result.via,
                                requests = result.requests,
                                truncated = result.truncated
                            )
                            target.messages.add(msg)
                            AiChatStore.touch(ctx, target)
                            l.active = false
                            activity.runOnUiThread {
                                val ui = l.onUi
                                if (live === l) live = null
                                if (ui != null && generation == requestGeneration) {
                                    ui.finished(msg, true)
                                } else {
                                    // answered while the chat was closed → badge the bubble
                                    unread++
                                    AiFloatingBubble.show(activity, unread)
                                    Toast.makeText(ctx, R.string.ai_reply_ready, Toast.LENGTH_SHORT).show()
                                }
                                // v1.7.80: whether or not the chat is open, the
                                // browser itself may not be the window in front
                                // of the user — and then none of the above is
                                // seen by anyone. Both branches fall through to
                                // the same check.
                                if (result.ok && !com.zephyr.browser.MainActivity.onScreen) {
                                    AiReplyNotice.post(ctx, plainAnswerText(result.text))
                                }
                                // v1.7.99: anything still queued was typed too
                                // late for this answer — the loop had already
                                // run out of rounds, or the request stopped. It
                                // becomes the next turn rather than being lost,
                                // which is the difference between a queue and a
                                // black hole.
                                if (ui != null && generation == requestGeneration) {
                                    val leftover = synchronized(queued) {
                                        val all = ArrayList(queued); queued.clear(); all
                                    }
                                    if (leftover.isNotEmpty()) {
                                        for (q in leftover) discardQueued(q)
                                        sendText(leftover.joinToString("\n"))
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }

        stopBtn.setOnClickListener {
            val l = live
            requestGeneration++
            l?.active = false
            l?.job?.cancel()
            l?.onUi = null
            live = null
            clearBusyUi()
            // keep whatever was streamed so far instead of losing it
            val partial = l?.answer?.toString()?.trim().orEmpty()
            liveView?.let { messagesContainer.removeView(it) }
            liveView = null; liveText = null
            if (partial.isNotEmpty()) {
                val msg = AiMessage("assistant", partial, elapsedMs = System.currentTimeMillis() - (l?.startedAt ?: 0L))
                chat.messages.add(msg)
                AiChatStore.touch(ctx, chat)
                messagesContainer.addView(renderMessage(msg, false))
            }
            scrollToEnd()
            reclaimInput()
        }

        retryHook = {
            if (!busy && chat.messages.isNotEmpty() && chat.messages.last().role == "assistant") {
                chat.messages.removeAt(chat.messages.size - 1)
                AiChatStore.touch(ctx, chat)
                val last = messagesContainer.childCount - 1
                if (last >= 0) messagesContainer.removeViewAt(last)
                runRequest()
            }
        }

        /**
         * v1.7.48: carry on from where the token limit cut the model off.
         *
         * The follow-up instruction never appears in the chat — it is a
         * mechanism, not something the user said — and the continuation is
         * merged into the SAME bubble, so the answer reads as one piece.
         */
        continueHook = {
            val last = chat.messages.lastOrNull()
            if (!busy && last != null && last.role == "assistant" && last.text.isNotBlank()) {
                val tail = last.text.takeLast(400)
                chat.messages.add(
                    AiMessage(
                        "user",
                        "Продолжи ровно с места обрыва, без вступлений и без повтора уже " +
                            "написанного. Последние строки твоего ответа:\n\n" + tail
                    )
                )
                mergeContinuation = true
                AiChatStore.touch(ctx, chat)
                runRequest()
            }
        }

        editHook = { msg ->
            if (!busy) {
                val at = chat.messages.indexOf(msg)
                if (at >= 0) {
                    // everything from this turn onwards is replaced by the edit
                    while (chat.messages.size > at) chat.messages.removeAt(chat.messages.size - 1)
                    AiChatStore.touch(ctx, chat)
                    loadChat(chat)
                    input.setText(msg.text)
                    input.setSelection(input.text.length)
                    reclaimInput()
                    pending.clear()
                    pending.addAll(msg.attachments)
                    refreshAttachStrip()
                }
            }
        }

        sendText = fun(text: String) {
            // v1.7.99: typing during an answer no longer means waiting. The
            // message joins the queue, is drawn faded so it is clearly
            // accepted-but-not-yet-sent, and the tool loop picks it up between
            // rounds. Attachments are not queued: they belong to a turn of
            // their own, and silently dropping them would be worse than making
            // the user wait for one.
            if (busy) {
                if (text.isBlank()) return
                queued.add(text)
                appendQueued(text)
                scrollToEnd()
                return
            }
            if (!AiSwarm.active() && !AiModels.isConfigured()) { notConfigured(); return }
            val atts = ArrayList(pending)
            if (text.isBlank() && atts.isEmpty()) return
            pending.clear(); refreshAttachStrip()
            hideEmptyState()
            val msg = AiMessage("user", text.ifBlank { "Опиши вложение." }, atts)
            chat.messages.add(msg)
            AiChatStore.touch(ctx, chat)
            refreshHeader()
            appendMessage(msg, true)
            scrollToEnd()
            reclaimInput()
            runRequest()
        }

        // v1.7.99.1: the button's meaning changes with the box's contents while
        // an answer is running, so its picture has to follow the typing.
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refreshSendIcon() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refreshSendIcon()

        sendBtn.setOnClickListener {
            val q = input.text.toString().trim()
            // v1.7.99: while an answer is running the button means "stop" only
            // when there is nothing to say. With text in the box it sends —
            // otherwise the one key you reach for to add a thought would throw
            // the answer away instead.
            if (busy && q.isEmpty()) { stopBtn.performClick(); return@setOnClickListener }
            input.setText("")
            sendText(q)
        }

        // v1.9.12: with the duplicate "Stop" chip gone, this is the only way to
        // cancel while there is text in the box — there the tap means "send".
        sendBtn.setOnLongClickListener {
            if (!busy) false
            else { stopBtn.performClick(); true }
        }

        // ── header buttons (need the closures above) ──────────────────────────
        header.addView(headerBtn(R.drawable.ic_history, R.string.ai_chats) {
            AiChatsSheet.show(
                ctx, chat.id,
                onOpen = { c -> loadChat(c) },
                onNew = {
                    val c = AiChatStore.newChat(ctx)
                    AiChatStore.pruneEmpty(ctx, c.id)
                    loadChat(c)
                }
            )
        })
        header.addView(headerBtn(R.drawable.ic_edit, R.string.ai_new_chat) {
            if (chat.messages.isNotEmpty() && !busy) {
                val c = AiChatStore.newChat(ctx)
                AiChatStore.pruneEmpty(ctx, c.id)
                pending.clear(); refreshAttachStrip()
                loadChat(c)
            }
        })
        header.addView(headerBtn(R.drawable.ic_arrow_down, R.string.ai_minimize) {
            minimize = true
            dialog.dismiss()
        })
        header.addView(headerBtn(R.drawable.ic_more_vert, R.string.settings) { v ->
            val menu = PopupMenu(ctx, v)
            menu.menu.add(0, 1, 0, ctx.getString(R.string.ai_choose_model))
            menu.menu.add(0, 8, 1, ctx.getString(R.string.think_title))
            menu.menu.add(0, 6, 2, ctx.getString(R.string.preset_title))
            menu.menu.add(0, 7, 2, ctx.getString(R.string.ai_actions))
            menu.menu.add(0, 2, 3, ctx.getString(R.string.ai_chats))
            menu.menu.add(0, 3, 2, ctx.getString(R.string.ai_delete_chat))
            menu.menu.add(0, 4, 3, ctx.getString(R.string.set_ai_assistant))
            menu.menu.add(0, 5, 4, ctx.getString(R.string.close))
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> AiModelsSheet.show(ctx) { refreshHeader() }
                    8 -> {
                        // v1.7.51: change the active model's reasoning effort
                        // without a trip through Settings
                        val idx = AiModels.activeIndex()
                        val p = AiModels.list().getOrNull(idx)
                        if (p != null) AiModelDialogs.pickThinking(ctx, p.thinking, p.model) { mode ->
                            AiModels.update(idx, p.copy(thinking = mode))
                            refreshHeader()
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.think_title) + ": " +
                                    AiModelDialogs.thinkLabel(ctx, mode),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    6 -> AiPromptPresets.pick(ctx) { refreshHeader() }
                    7 -> AiActionLogSheet.show(ctx)
                    2 -> AiChatsSheet.show(ctx, chat.id, { c -> loadChat(c) }, {
                        loadChat(AiChatStore.newChat(ctx))
                    })
                    3 -> {
                        AiChatStore.delete(ctx, chat)
                        loadChat(AiChatStore.newChat(ctx))
                    }
                    4 -> {
                        minimize = false
                        dialog.dismiss()
                        ctx.startActivity(Intent(ctx, SettingsActivity::class.java).apply {
                            putExtra("screen", "ai")
                        })
                    }
                    else -> { minimize = false; dialog.dismiss() }
                }
                true
            }
            menu.show()
        })

        root.addView(header)
        root.addView(AiUi.divider(ctx))
        // v1.7.29 (user: «добавим кнопку стрелку вниз, которая появляется когда
        // поднимаемся почитать — нажал, быстро спустился»): a jump-to-latest
        // button that fades in only while the view is scrolled away from the
        // bottom. It shares a FrameLayout with the message list so it floats
        // over the conversation instead of taking layout space.
        val jumpBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_arrow_down)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT_2)
            ).apply { shape = GradientDrawable.OVAL }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
            elevation = dp(6).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = ctx.getString(R.string.ai_jump_bottom)
            alpha = 0f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.END or Gravity.BOTTOM)
                .apply { marginEnd = dp(14); bottomMargin = dp(12) }
            setOnClickListener { scrollToEnd() }
        }

        fun refreshJumpBtn() {
            val distance = messagesContainer.bottom - (chatScroll.scrollY + chatScroll.height)
            val show = distance > dp(160)
            if (show && jumpBtn.visibility != View.VISIBLE) {
                jumpBtn.visibility = View.VISIBLE
                jumpBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
            } else if (!show && jumpBtn.visibility == View.VISIBLE) {
                jumpBtn.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(140)
                    .withEndAction { jumpBtn.visibility = View.GONE }.start()
            }
        }
        chatScroll.viewTreeObserver.addOnScrollChangedListener { refreshJumpBtn() }

        root.addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(chatScroll.apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            addView(jumpBtn)
        })
        root.addView(statusBar)
        root.addView(attachRow)
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(surfaceVar)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(14); marginEnd = dp(14)
                topMargin = dp(2); bottomMargin = dp(14)
            }
            addView(attachBtn)
            addView(input)
            addView(micBtn)
            addView(sendBtn)
        })

        // keep the newest message visible when the keyboard pushes the sheet up
        input.setOnFocusChangeListener { _, has -> if (has) scrollToEnd() }
        chatScroll.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) scrollToEnd()
        }

        // restore the conversation
        for (m in chat.messages) appendMessage(m, false)
        showEmptyState()
        refreshHeader()
        scrollToEnd()

        // v1.7.28: if a request is still running, re-attach this window to it so
        // the status row, the clock and the half-streamed answer come back
        live?.let { if (it.active && it.chatId == chat.id) bindLive() }

        dialog.setOnDismissListener {
            if (openDialog === dialog) openDialog = null
            // detach the views, but let the request itself keep running
            live?.onUi = null
            // a request in flight keeps running — the answer lands on the bubble
            if (minimize && chat.messages.isNotEmpty()) AiFloatingBubble.show(activity, unread)
            else if (busy) AiFloatingBubble.show(activity, unread)
        }
        dialog.setOnCancelListener { minimize = true }   // back button = minimize
        dialog.show()

        // v1.7.77: photos from the camera, waiting for their question
        if (seededAttachOnly) {
            seededAttachOnly = false
            val files = seededFiles
            seededFiles = null
            if (files != null) {
                Toast.makeText(ctx, R.string.ai_reading_file, Toast.LENGTH_SHORT).show()
                ioScope.launch {
                    val read = files.mapNotNull { AiClient.readAttachment(ctx, it) }
                    withContext(Dispatchers.Main) {
                        if (read.isEmpty()) {
                            Toast.makeText(ctx, R.string.ai_attachment_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            pending.addAll(read)
                            refreshAttachStrip()
                            reclaimInput()
                        }
                    }
                }
            }
        }

        // v1.7.48: a page selection handed over by the browser
        seeded?.let { text ->
            seeded = null
            val files = seededFiles
            seededFiles = null
            when {
                // v1.7.76: a camera shot — read it, then ask, in that order
                files != null -> {
                    input.setText("")
                    Toast.makeText(ctx, R.string.ai_reading_file, Toast.LENGTH_SHORT).show()
                    ioScope.launch {
                        val read = files.mapNotNull { AiClient.readAttachment(ctx, it) }
                        withContext(Dispatchers.Main) {
                            if (read.isEmpty()) {
                                Toast.makeText(ctx, R.string.ai_attachment_failed, Toast.LENGTH_SHORT).show()
                                input.setText(text)
                                reclaimInput()
                            } else {
                                pending.addAll(read)
                                refreshAttachStrip()
                                sendText(text)
                            }
                        }
                    }
                }
                seededAsk -> {
                    input.setText(text)
                    input.setSelection(input.text.length)
                    reclaimInput()
                }
                else -> {
                    input.setText("")
                    sendText(text)
                }
            }
        }
    }

    // ---------------- small helpers ----------------

    /**
     * v1.7.40: open a chat attachment fullscreen.
     *
     * Images go to the browser's own [PhotoViewerActivity] — every image in the
     * same message is handed over, so swiping between them works. Documents are
     * handed to whatever app on the phone can open that type.
     */
    /**
     * Decode a `data:` image or download an http one. Blocking — off the UI thread.
     *
     * v1.9.8 (user: «выхожу из чата, захожу обратно — фотки нету, появится
     * потом»). Nothing was kept: every time the chat was drawn, the picture was
     * fetched again over the network, so an old answer showed the generating
     * placeholder before showing a picture that had existed for hours. Worse,
     * bridges expire their media — retention there is about three days — so
     * eventually the picture would simply stop coming back.
     *
     * The bytes are now written to the cache on first download and read from
     * there afterwards. A reopened chat paints immediately and offline, and the
     * picture outlives the bridge's own copy.
     */
    private fun loadAnswerImage(url: String): android.graphics.Bitmap? = try {
        if (url.startsWith("data:image", true)) {
            val b64 = url.substringAfter(",", "")
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            val cached = mediaCacheFile(url)
            if (cached != null && cached.length() > 0) {
                android.graphics.BitmapFactory.decodeFile(cached.absolutePath)
            } else {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Zephyr")
                }
                if (conn.responseCode in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    // written first, so a decode failure does not cost the bytes
                    try { cached?.writeBytes(bytes) } catch (_: Throwable) {}
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
            }
        }
    } catch (_: Throwable) { null }

    /**
     * Where a downloaded generated file lives, keyed by its URL.
     *
     * The name is a hash rather than the id, because two bridges can hand out
     * the same id and a URL is the only thing that is actually unique here.
     */
    /**
     * Download a generated file into the cache and return a local path.
     *
     * Returns null on any failure, so the caller falls back to the remote URL —
     * a player streaming the original is worse than one playing a local copy,
     * but far better than nothing.
     */
    private fun fetchToCache(url: String): String? = try {
        if (url.startsWith("file://") || url.startsWith("/")) url
        else {
            val f = mediaCacheFile(url)
            when {
                f == null -> null
                f.length() > 0 -> f.absolutePath
                else -> {
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 12000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Zephyr")
                    }
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                        f.absolutePath
                    } else null
                }
            }
        }
    } catch (_: Throwable) { null }

    private fun mediaCacheFile(url: String): java.io.File? = try {
        val ctx = com.zephyr.browser.App.ctx
        if (ctx == null) null else {
            val dir = java.io.File(ctx.cacheDir, "ai-media").apply { mkdirs() }
            java.io.File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length)
        }
    } catch (_: Throwable) { null }

    /** Save a generated image to the cache and open it in the fullscreen viewer. */
    private fun openImageBitmap(ctx: Context, bmp: android.graphics.Bitmap) {
        try {
            val dir = java.io.File(ctx.cacheDir, "ai_images").apply { mkdirs() }
            val f = java.io.File(dir, "ai_" + System.currentTimeMillis() + ".png")
            java.io.FileOutputStream(f).use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", f
            )
            ctx.startActivity(
                Intent(ctx, com.zephyr.browser.viewer.PhotoViewerActivity::class.java).apply {
                    putStringArrayListExtra("uris", arrayListOf(uri.toString()))
                    putStringArrayListExtra("names", arrayListOf(f.name))
                    putExtra("index", 0)
                    putExtra(Intent.EXTRA_TITLE, f.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (_: Throwable) {
            Toast.makeText(ctx, R.string.app_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v1.7.48: keep a generated picture (user: «сгенерировал — а забрать
     * некуда»). Saved into Pictures/Zephyr, where the gallery finds it.
     */
    /**
     * v1.7.49: tapping a path in an answer opens the file manager on it.
     *
     * The assistant names where a file lives; the user should not then have to
     * remember that path and walk the folder tree by hand. The chat folds into
     * the floating bubble so Files is actually visible behind it — the same
     * reason opening a page collapses the window.
     */
    private fun openInFiles(activity: MainActivity, path: String) {
        try {
            activity.startActivity(
                Intent(activity, com.zephyr.browser.files.FilesActivity::class.java).apply {
                    putExtra(com.zephyr.browser.files.FilesActivity.EXTRA_PATH, path)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            )
            minimizeForAction(activity)
        } catch (_: Throwable) {
            Toast.makeText(activity, R.string.app_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v1.7.50: long-press a path for the full set of things you might want.
     *
     * A single tap shows the file in the browser's file manager; this is the
     * rest — open it in whatever app handles the type, copy the path, share it.
     */
    private fun pathMenu(activity: MainActivity, anchor: View, path: String) {
        val f = java.io.File(path)
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, 1, 0, activity.getString(R.string.ai_open_in_files))
        if (f.isFile) menu.menu.add(0, 2, 1, activity.getString(R.string.ai_path_open))
        menu.menu.add(0, 3, 2, activity.getString(R.string.ai_path_copy))
        if (f.isFile) menu.menu.add(0, 4, 3, activity.getString(R.string.ai_share))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openInFiles(activity, path)
                2 -> openFileExternally(activity, f)
                3 -> {
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("path", path))
                    Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                4 -> shareFile(activity, f)
            }
            true
        }
        menu.show()
    }

    private fun fileUri(activity: MainActivity, f: java.io.File) =
        androidx.core.content.FileProvider.getUriForFile(
            activity, activity.packageName + ".fileprovider", f
        )

    private fun openFileExternally(activity: MainActivity, f: java.io.File) {
        try {
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri(activity, f), mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            minimizeForAction(activity)
        } catch (_: Throwable) {
            Toast.makeText(activity, R.string.app_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(activity: MainActivity, f: java.io.File) {
        try {
            activity.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_STREAM, fileUri(activity, f))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, activity.getString(R.string.ai_share)
                )
            )
        } catch (_: Throwable) {
            Toast.makeText(activity, R.string.app_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /** Human size for a file card. */
    private fun humanSize(bytes: Long): String {
        // v1.7.88: the units were Russian literals (Б/КБ/МБ/ГБ), so an English
        // user's attachment read "1.4 МБ".
        val ctx = com.zephyr.browser.App.ctx
        fun unit(res: Int, fallback: String) =
            try { ctx?.getString(res).orEmpty().ifBlank { fallback } } catch (_: Throwable) { fallback }
        return when {
            bytes <= 0 -> ""
            bytes < 1024 -> "$bytes " + unit(R.string.unit_b, "B")
            bytes < 1024 * 1024 ->
                (bytes / 1024).toString() + " " + unit(R.string.unit_kb, "KB")
            bytes < 1024L * 1024 * 1024 ->
                String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0) +
                    " " + unit(R.string.unit_mb, "MB")
            else ->
                String.format(java.util.Locale.US, "%.2f", bytes / 1073741824.0) +
                    " " + unit(R.string.unit_gb, "GB")
        }
    }

    private fun iconForFile(f: java.io.File): Int = when {
        f.isDirectory -> R.drawable.ic_folder
        f.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> R.drawable.ic_image
        f.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm") -> R.drawable.ic_video
        f.extension.lowercase() in listOf("mp3", "wav", "ogg", "m4a", "aac", "flac") -> R.drawable.ic_music_note
        f.extension.lowercase() in listOf("zip", "rar", "7z", "tar", "gz") -> R.drawable.ic_archive
        f.extension.lowercase() == "apk" -> R.drawable.ic_android_file
        f.extension.lowercase() in listOf("txt", "md", "json", "xml", "log") -> R.drawable.ic_text_file
        else -> R.drawable.ic_file
    }

    private fun saveGeneratedImage(activity: MainActivity, bmp: android.graphics.Bitmap) {
        ioScope.launch {
            val ok = try {
                val name = "zephyr_ai_" + System.currentTimeMillis()
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zephyr")
                    }
                }
                val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
                    android.provider.MediaStore.Images.Media.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = activity.contentResolver.insert(collection, values)
                if (uri == null) false else {
                    activity.contentResolver.openOutputStream(uri)?.use {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, it)
                    }
                    true
                }
            } catch (_: Throwable) { false }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    activity,
                    if (ok) R.string.ai_image_saved else R.string.backup_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openAttachment(ctx: Context, m: AiMessage, tapped: AiAttachment) {
        try {
            if (tapped.isImage) {
                val images = m.attachments.filter { it.isImage }
                val uris = ArrayList(images.map { it.uri.toString() })
                val names = ArrayList(images.map { it.name })
                ctx.startActivity(
                    Intent(ctx, com.zephyr.browser.viewer.PhotoViewerActivity::class.java).apply {
                        putStringArrayListExtra("uris", uris)
                        putStringArrayListExtra("names", names)
                        putExtra("index", images.indexOf(tapped).coerceAtLeast(0))
                        putExtra(Intent.EXTRA_TITLE, tapped.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            } else {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(tapped.uri, tapped.mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        } catch (_: Throwable) {
            Toast.makeText(ctx, R.string.app_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pageContextOf(title: String, url: String, text: String): String {
        if (title.isBlank() && url.isBlank() && text.isBlank()) return ""
        return buildString {
            if (title.isNotBlank()) append("Заголовок: ").append(title).append('\n')
            if (url.isNotBlank()) append("URL: ").append(url).append('\n')
            if (text.isNotBlank()) append("Текст:\n").append(text)
        }
    }

    private fun fileChip(
        ctx: Context,
        name: String,
        onSurface: Int,
        surfaceVar: Int,
        dp: (Int) -> Int,
        onRemove: (() -> Unit)?
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(AiUi.blend(surfaceVar, onSurface, 0.06f))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8); bottomMargin = dp(6) }
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_file)
            imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
        })
        addView(TextView(ctx).apply {
            text = name
            textSize = 12.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            this.maxWidth = dp(160)
            setTextColor(onSurface)
        })
        if (onRemove != null) addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(onSurface)
            alpha = 0.6f
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) }
            setOnClickListener { onRemove() }
        })
    }
}
