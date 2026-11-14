package com.zephyr.browser.ai

import android.content.Context
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.7.25b: the list of saved conversations.
 *
 * The pencil in the chat header starts a NEW dialogue — this sheet is where the
 * previous ones live (user: «есть кнопка карандашик сверху это открыть новый чат
 * а старый где?»). Tap a row to continue it, use the ⋮ to rename or delete.
 */
object AiChatsSheet {

    private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    fun show(ctx: Context, activeId: String?, onOpen: (AiChat) -> Unit, onNew: () -> Unit) {
        val (dialog, root) = AiUi.sheet(ctx, 0.78f)
        val d = { v: Int -> AiUi.dp(ctx, v) }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(AiUi.sheetTitle(ctx, ctx.getString(R.string.ai_chats)).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_delete)
                background = AiUi.ripple(ctx)
                setPadding(d(9), d(9), d(9), d(9))
                imageTintList = android.content.res.ColorStateList.valueOf(AiUi.onVariant(ctx))
                contentDescription = ctx.getString(R.string.ai_clear_all_chats)
                layoutParams = LinearLayout.LayoutParams(d(40), d(40)).apply { marginEnd = d(12) }
                setOnClickListener {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.ai_clear_all_chats)
                        .setMessage(R.string.ai_clear_all_chats_msg)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            AiChatStore.deleteAll(ctx)
                            dialog.dismiss()
                            onNew()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
        }
        root.addView(headerRow)
        root.addView(AiUi.divider(ctx))

        // v1.7.48 (user: «диалогов уже десятки, найти старый можно только
        // листая»): search across titles AND message bodies.
        var query = ""
        val search = EditText(ctx).apply {
            hint = ctx.getString(R.string.ai_search_chats)
            textSize = 14f
            setSingleLine(true)
            setTextColor(AiUi.onSurface(ctx))
            setHintTextColor(AiUi.onVariant(ctx))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(16).toFloat()
                setColor(AiUi.surfaceVar(ctx))
            }
            setPadding(d(14), d(10), d(14), d(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(d(16), d(4), d(16), d(8)) }
        }
        root.addView(search)

        val (scroll, col) = AiUi.body(ctx)
        root.addView(scroll)

        fun rebuild() {
            col.removeAllViews()
            val q = query.trim()
            val chats = AiChatStore.all(ctx).filter { c ->
                c.messages.isNotEmpty() && (
                    q.isEmpty() ||
                        c.title.contains(q, true) ||
                        c.messages.any { it.text.contains(q, true) }
                    )
            }
            if (chats.isEmpty()) {
                col.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.ai_no_chats)
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(AiUi.onVariant(ctx))
                    setPadding(0, d(60), 0, d(60))
                })
                return
            }
            for (c in chats) {
                val row = AiUi.selectableRow(ctx, c.id == activeId)
                row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_ai)
                    imageTintList = android.content.res.ColorStateList.valueOf(AiUi.ACCENT)
                    setPadding(d(9), d(9), d(9), d(9))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = d(12).toFloat()
                        setColor(AiUi.withAlpha(AiUi.ACCENT, 0x24))
                    }
                    layoutParams = LinearLayout.LayoutParams(d(38), d(38))
                        .apply { marginEnd = d(13) }
                })
                val last = c.messages.lastOrNull()
                val preview = plainAnswerText(last?.text.orEmpty())
                    .replace("\n", " ").take(64)
                row.addView(
                    AiUi.twoLine(
                        ctx,
                        c.title.ifBlank { ctx.getString(R.string.ai_untitled_chat) },
                        fmt.format(Date(c.updated)) + " · " + preview
                    )
                )
                row.addView(ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_more_vert)
                    background = AiUi.ripple(ctx)
                    imageTintList = android.content.res.ColorStateList.valueOf(AiUi.onVariant(ctx))
                    setPadding(d(8), d(8), d(8), d(8))
                    layoutParams = LinearLayout.LayoutParams(d(36), d(36))
                    setOnClickListener { anchor ->
                        val m = PopupMenu(ctx, anchor)
                        m.menu.add(0, 1, 0, ctx.getString(R.string.ai_rename_chat))
                        m.menu.add(0, 2, 1, ctx.getString(R.string.delete))
                        m.setOnMenuItemClickListener { item ->
                            if (item.itemId == 1) {
                                val et = EditText(ctx).apply { setText(c.title); setSingleLine(true) }
                                MaterialAlertDialogBuilder(ctx)
                                    .setTitle(R.string.ai_rename_chat)
                                    .setView(LinearLayout(ctx).apply {
                                        setPadding(d(22), d(8), d(22), d(8))
                                        addView(et)
                                    })
                                    .setPositiveButton(R.string.save) { _, _ ->
                                        AiChatStore.rename(ctx, c, et.text.toString())
                                        rebuild()
                                    }
                                    .setNegativeButton(R.string.cancel, null)
                                    .show()
                            } else {
                                AiChatStore.delete(ctx, c)
                                rebuild()
                            }
                            true
                        }
                        m.show()
                    }
                })
                row.setOnClickListener {
                    dialog.dismiss()
                    onOpen(c)
                }
                col.addView(row)
            }
        }
        rebuild()
        // v1.7.49 FIX: this listener landed inside the rename dialog on the
        // first attempt, so typing in the field did nothing at all.
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                rebuild()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        root.addView(AiUi.primaryButton(ctx, "＋  " + ctx.getString(R.string.ai_new_chat)) {
            dialog.dismiss()
            onNew()
        })

        dialog.show()
    }
}
