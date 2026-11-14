package com.zephyr.browser.ai

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zephyr.browser.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.7.50: what the assistant did, in order.
 *
 * The tool statuses flash by in the status line and vanish. This keeps them:
 * every page click, opened tab, file read and bookmark added, with the
 * arguments it used and how it went. Green for done, red for refused or failed.
 */
object AiActionLogSheet {

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Also used by AiClient to name what an answer did before it ran out of steps. */
    val TITLES = mapOf(
        "web_search" to R.string.act_web_search,
        "open_page" to R.string.act_open_page,
        "page_text" to R.string.act_page_text,
        "page_extract" to R.string.act_page_extract,
        "page_click" to R.string.act_page_click,
        "page_fill" to R.string.act_page_fill,
        "page_scroll" to R.string.act_page_scroll,
        "page_forms" to R.string.act_page_forms,
        "page_screenshot" to R.string.act_page_screenshot,
        "remember_fact" to R.string.act_remember_fact,
        "list_memory" to R.string.act_list_memory,
        "forget_fact" to R.string.act_forget_fact,
        "open_tab" to R.string.act_open_tab,
        "browser_search" to R.string.act_browser_search,
        "list_tabs" to R.string.act_list_tabs,
        "close_tab" to R.string.act_close_tab,
        "search_history" to R.string.act_search_history,
        "search_bookmarks" to R.string.act_search_bookmarks,
        "add_bookmark" to R.string.act_add_bookmark,
        "list_downloads" to R.string.act_list_downloads,
        "find_file" to R.string.act_find_file,
        "read_file" to R.string.act_read_file,
        "list_ai_models" to R.string.act_list_ai_models,
        "add_ai_model" to R.string.act_add_ai_model,
        "use_ai_model" to R.string.act_use_ai_model,
        // v1.7.54
        "write_file" to R.string.act_write_file,
        "make_dir" to R.string.act_make_dir,
        "list_dir" to R.string.act_list_dir,
        "delete_path" to R.string.act_delete_path,
        "zip_project" to R.string.act_zip_project,
        "copy_path" to R.string.act_copy_path,
        "move_path" to R.string.act_move_path,
        "add_reminder" to R.string.act_add_reminder,
        "list_reminders" to R.string.act_list_reminders,
        "complete_reminder" to R.string.act_complete_reminder,
        "delete_reminder" to R.string.act_delete_reminder,
        // v1.7.60
        "run_js" to R.string.act_run_js,
        "add_userscript" to R.string.act_add_userscript,
        "list_userscripts" to R.string.act_list_userscripts,
        "toggle_userscript" to R.string.act_toggle_userscript,
        "delete_userscript" to R.string.act_delete_userscript,
        // v1.7.61
        "pdf_read" to R.string.act_pdf_read,
        "pdf_fields" to R.string.act_pdf_fields,
        "pdf_fill" to R.string.act_pdf_fill,
        "pdf_stamp" to R.string.act_pdf_stamp,
        "save_workflow" to R.string.act_save_workflow,
        "list_workflows" to R.string.act_list_workflows,
        "delete_workflow" to R.string.act_delete_workflow,
        "find_everything" to R.string.act_find_everything,
        // v1.7.62
        "office_read" to R.string.act_office_read,
        "office_fill" to R.string.act_office_fill,
        "office_create" to R.string.act_office_create,
        // v1.7.63
        "make_document" to R.string.act_make_document,
        "pdf_layout" to R.string.act_pdf_layout,
        "pdf_view" to R.string.act_pdf_view,
        "pdf_autofill" to R.string.act_pdf_autofill,
        "pdf_form_scan" to R.string.act_pdf_form_scan,
        // v1.7.67
        "cv_template" to R.string.act_cv_template,
        "tailor_cv" to R.string.act_tailor_cv,
        "render_html" to R.string.act_render_html,
        "make_cv" to R.string.act_make_cv,
        // v1.7.70
        "save_template" to R.string.act_save_template,
        "list_templates" to R.string.act_list_templates,
        "use_template" to R.string.act_use_template,
        "delete_template" to R.string.act_delete_template,
        "business_profile" to R.string.act_business_profile,
    )

    /**
     * The readable name of a tool, in the reader's language.
     *
     * v1.7.85: these were Russian literals in the map above, so an English or
     * French user opened "what the assistant did" and found a list in Russian.
     * A tool with no entry falls back to its own identifier — which is ugly but
     * accurate, and better than an empty row when a new tool is added and its
     * label is forgotten.
     */
    private fun titleOf(ctx: Context, tool: String): String {
        val res = TITLES[tool] ?: return tool
        return try { ctx.getString(res) } catch (_: Throwable) { tool }
    }

    fun show(ctx: Context) {
        val (dialog, root) = AiUi.sheet(ctx, 0.8f)
        val d = { v: Int -> AiUi.dp(ctx, v) }

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(AiUi.sheetTitle(ctx, ctx.getString(R.string.ai_actions)).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_delete)
                background = AiUi.ripple(ctx)
                setPadding(d(9), d(9), d(9), d(9))
                imageTintList = android.content.res.ColorStateList.valueOf(AiUi.onVariant(ctx))
                contentDescription = ctx.getString(R.string.delete)
                layoutParams = LinearLayout.LayoutParams(d(40), d(40)).apply { marginEnd = d(12) }
                // v1.7.60: the log is persistent now — only this button empties it
                setOnClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.ai_actions)
                        .setMessage(R.string.log_clear_confirm)
                        .setPositiveButton(R.string.delete) { _, _ -> AiActionLog.clear(); dialog.dismiss() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
        })
        root.addView(AiUi.divider(ctx))

        val (scroll, col) = AiUi.body(ctx)
        root.addView(scroll)

        val items = AiActionLog.all()
        if (items.isEmpty()) {
            col.addView(TextView(ctx).apply {
                setText(R.string.ai_actions_empty)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(AiUi.onVariant(ctx))
                setPadding(0, d(60), 0, d(60))
            })
        } else {
            for (e in items) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(d(13), d(11), d(13), d(11))
                    background = GradientDrawable().apply {
                        cornerRadius = d(16).toFloat()
                        setColor(AiUi.surfaceVar(ctx))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = d(8) }
                }
                row.addView(ImageView(ctx).apply {
                    setImageResource(if (e.ok) R.drawable.ic_check else R.drawable.ic_close)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        if (e.ok) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                    )
                    layoutParams = LinearLayout.LayoutParams(d(18), d(18))
                        .apply { marginEnd = d(11); topMargin = d(2) }
                })
                row.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        text = titleOf(ctx, e.tool) + "   " + fmt.format(Date(e.at)) +
                            (if (e.undone) "  · " + ctx.getString(R.string.log_undone) else "")
                        textSize = 13.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(AiUi.onSurface(ctx))
                    })
                    if (e.args.isNotBlank() && e.args != "{}") addView(TextView(ctx).apply {
                        text = e.args
                        textSize = 11.5f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, d(3), 0, 0)
                        setTextColor(AiUi.ACCENT)
                    })
                    if (e.result.isNotBlank()) addView(TextView(ctx).apply {
                        text = e.result
                        textSize = 11.5f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, d(3), 0, 0)
                        setTextColor(AiUi.onVariant(ctx))
                    })
                })
                // v1.7.60: an action that can be taken back says so, and does it
                if (e.undo != null && !e.undone) {
                    row.addView(TextView(ctx).apply {
                        setText(R.string.log_undo)
                        textSize = 12.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(AiUi.ACCENT)
                        setPadding(d(10), d(6), d(4), d(6))
                        isClickable = true
                        background = AiUi.ripple(ctx)
                        setOnClickListener {
                            val btn = this
                            btn.isEnabled = false
                            Thread {
                                val msg = AiActionLog.undo(ctx, e)
                                btn.post {
                                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    btn.visibility = android.view.View.GONE
                                }
                            }.start()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { gravity = Gravity.CENTER_VERTICAL }
                    })
                }
                col.addView(row)
            }
        }
        dialog.show()
    }
}
