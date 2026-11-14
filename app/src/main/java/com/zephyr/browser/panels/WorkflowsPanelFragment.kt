package com.zephyr.browser.panels

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.MainActivity
import com.zephyr.browser.R
import com.zephyr.browser.ai.AiAssistantSheet
import com.zephyr.browser.ai.AiWorkflows

/**
 * v1.7.61 «Сценарии» — the user's own commands, in one list.
 *
 * Running one is a single tap: the saved instruction goes to the assistant
 * exactly as if it had been typed, and the assistant picks its own tools. There
 * is no separate engine to understand, which is the point — a scenario is a
 * sentence you stopped having to retype.
 *
 * The other two ways in stay where they belong: the assistant writes scenarios
 * through save_workflow, and «Дела» schedules them, because a scheduled job is
 * the same thing with a clock attached.
 */
class WorkflowsPanelFragment : Fragment() {

    private lateinit var list: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // v1.7.95 (F-016): the user asked outright what a "scenario" is —
        // «обьясни мне как работает Сценарий опция на пальцах».
        PanelUi.hintBanner(ctx, "workflows_hint", R.drawable.ic_list,
            ctx.getString(R.string.wf_hint_title), ctx.getString(R.string.wf_hint_body))
            ?.let { root.addView(it) }

        root.addView(TextView(ctx).apply {
            setText(R.string.wf_hint)
            textSize = 12.5f
            alpha = 0.75f
            setPadding(dp(18), dp(12), dp(18), dp(4))
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })

        root.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(6))
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
                    setText(R.string.wf_add)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFFFFFFFF.toInt())
                })
                setOnClickListener { edit(null) }
            })
        })

        list = PanelUi.listColumn(ctx)
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        })

        render()
        return root
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val ctx = context ?: return
        list.removeAllViews()
        val flows = AiWorkflows.all()
        if (flows.isEmpty()) {
            list.addView(PanelUi.emptyState(ctx, R.drawable.ic_list,
                R.string.empty_workflows_title, R.string.empty_workflows_hint))
            return
        }
        for (f in flows) {
            list.addView(
                PanelUi.cardRow(
                    ctx,
                    iconRes = R.drawable.ic_script,
                    title = "/" + f.name,
                    subtitle = f.prompt.replace("\n", " ").take(90),
                    onLongClick = { menu(f) },
                    onClick = { run(f) }
                )
            )
        }
    }

    /**
     * Send the scenario to the assistant.
     *
     * [AiAssistantSheet.askAbout] already exists for "ask about this selection";
     * a scenario is the same motion with the text coming from storage, so it
     * reuses that path rather than growing a second one.
     */
    private fun run(f: AiWorkflows.Flow) {
        val act = activity
        // the panel sits over the browser, so it steps aside for the chat
        activity?.finish()
        val main = com.zephyr.browser.ai.AiTools.host
        if (main == null) {
            Toast.makeText(act ?: return, R.string.wf_no_browser, Toast.LENGTH_SHORT).show()
            return
        }
        AiAssistantSheet.askAbout(main, f.prompt, ask = false)
    }

    private fun menu(f: AiWorkflows.Flow) {
        val ctx = context ?: return
        val actions = listOf(
            getString(R.string.wf_run) to { run(f) },
            getString(R.string.edit) to { edit(f) },
            getString(R.string.wf_schedule) to { schedule(f) },
            getString(R.string.delete) to { AiWorkflows.remove(f.id); render() }
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle("/" + f.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, w -> actions[w].second() }
            .show()
    }

    /** Hand the scenario to «Дела» as a scheduled job. */
    private fun schedule(f: AiWorkflows.Flow) {
        try {
            startActivity(
                android.content.Intent(requireContext(), PanelsActivity::class.java)
                    .putExtra(PanelsActivity.EXTRA_PANEL, "tasks")
                    .putExtra("job_title", f.name)
                    .putExtra("job_prompt", f.prompt)
            )
        } catch (_: Throwable) {}
    }

    private fun edit(existing: AiWorkflows.Flow?) {
        val ctx = context ?: return
        val nameEt = EditText(ctx).apply {
            setText(existing?.name ?: "")
            hint = getString(R.string.wf_name_hint)
            setSingleLine(true)
            textSize = 15f
        }
        val promptEt = EditText(ctx).apply {
            setText(existing?.prompt ?: "")
            hint = getString(R.string.wf_prompt_hint)
            setSingleLine(false)
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            textSize = 13.5f
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            addView(nameEt)
            addView(promptEt)
            addView(TextView(ctx).apply {
                setText(R.string.wf_prompt_note)
                textSize = 11.5f
                alpha = 0.65f
                setPadding(0, dp(8), 0, 0)
            })
        }
        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.wf_add else R.string.wf_edit)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                val saved = AiWorkflows.put(nameEt.text.toString(), promptEt.text.toString())
                if (saved == null) Toast.makeText(ctx, R.string.wf_need_both, Toast.LENGTH_SHORT).show()
                // renaming leaves the old entry behind, so drop it explicitly
                else if (existing != null && !existing.name.equals(saved.name, true)) {
                    AiWorkflows.remove(existing.id)
                }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) b.setNeutralButton(R.string.delete) { _, _ ->
            AiWorkflows.remove(existing.id); render()
        }
        b.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
