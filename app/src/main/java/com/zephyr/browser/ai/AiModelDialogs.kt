package com.zephyr.browser.ai

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R

/**
 * v1.7.25: shared add/edit/pick dialogs for AI model profiles.
 * Used both by the chat window (quick switch) and by Settings → ИИ Помощник.
 */
object AiModelDialogs {

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun field(ctx: Context, label: String, value: String, hint: String, password: Boolean = false): Pair<LinearLayout, EditText> {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        col.addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            alpha = 0.7f
        })
        val et = EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setSingleLine(true)
            textSize = 14f
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        col.addView(et)
        return col to et
    }

    /** Add a new profile, or edit the one at [index] when it is not null. */
    fun editProfile(ctx: Context, index: Int?, onDone: () -> Unit) {
        val existing = index?.let { AiModels.list().getOrNull(it) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 4), dp(ctx, 22), dp(ctx, 4))
        }
        val (nameCol, nameEt) = field(ctx, ctx.getString(R.string.ai_field_name), existing?.name ?: "", "NVIDIA Llama 3.3")
        val (epCol, epEt) = field(ctx, ctx.getString(R.string.ai_field_endpoint), existing?.endpoint ?: "", "https://integrate.api.nvidia.com/v1")
        val (modelCol, modelEt) = field(ctx, ctx.getString(R.string.ai_field_model), existing?.model ?: "", "meta/llama-3.3-70b-instruct")
        val (keyCol, keyEt) = field(ctx, ctx.getString(R.string.ai_field_key), existing?.key ?: "", "nvapi-… / sk-…", password = true)
        val (promptCol, promptEt) = field(
            ctx, ctx.getString(R.string.ai_field_prompt), existing?.prompt ?: "",
            ctx.getString(R.string.ai_field_prompt_hint)
        )
        promptEt.setSingleLine(false)
        promptEt.maxLines = 4

        // v1.7.51: reasoning effort, chosen per model
        var think = existing?.thinking ?: "auto"
        val thinkRow = TextView(ctx).apply {
            text = ctx.getString(R.string.think_title) + ": " + thinkLabel(ctx, think)
            textSize = 13f
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
            setTextColor(0xFF6366F1.toInt())
            setOnClickListener {
                pickThinking(ctx, think, modelEt.text.toString()) { picked ->
                    think = picked
                    text = ctx.getString(R.string.think_title) + ": " + thinkLabel(ctx, picked)
                }
            }
        }

        // provider preset shortcut
        val presetBtn = TextView(ctx).apply {
            text = ctx.getString(R.string.ai_pick_provider)
            textSize = 13f
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            setTextColor(0xFF6366F1.toInt())
            setOnClickListener {
                val names = AiModels.PRESETS.map { it.first }.toTypedArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.set_ai_provider)
                    .setItems(names) { _, which ->
                        val (label, ep, model) = AiModels.PRESETS[which]
                        if (ep.isNotBlank()) {
                            epEt.setText(ep)
                            modelEt.setText(model)
                            if (nameEt.text.isNullOrBlank()) nameEt.setText(label)
                        }
                    }
                    .show()
            }
        }

        box.addView(presetBtn)
        box.addView(nameCol); box.addView(epCol); box.addView(modelCol); box.addView(keyCol)
        box.addView(promptCol)
        box.addView(thinkRow)
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.ai_endpoint_hint)
            textSize = 11f
            alpha = 0.6f
            setPadding(0, dp(ctx, 10), 0, 0)
        })

        val scroll = android.widget.ScrollView(ctx).apply { addView(box) }

        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.ai_add_model else R.string.ai_edit_model)
            .setView(scroll)
            .setPositiveButton(R.string.save) { _, _ ->
                val model = modelEt.text.toString().trim()
                val p = AiProfile(
                    name = nameEt.text.toString().trim().ifBlank { model.ifBlank { ctx.getString(R.string.ai_default_model_name) } },
                    endpoint = epEt.text.toString().trim(),
                    model = model,
                    key = keyEt.text.toString().trim(),
                    prompt = promptEt.text.toString().trim(),
                    thinking = think
                )
                if (index == null) AiModels.add(p) else AiModels.update(index, p)
                onDone()
            }
            .setNegativeButton(R.string.cancel, null)
        if (index != null && AiModels.list().size > 1) {
            b.setNeutralButton(R.string.delete) { _, _ ->
                AiModels.remove(index)
                onDone()
            }
        }
        b.show()
    }

    /**
     * Model picker. v1.7.25b: the old AlertDialog with a radio list and three
     * stacked buttons was replaced by [AiModelsSheet] — one tap selects, the
     * row's pencil edits, one button at the bottom adds.
     */
    fun pickModel(ctx: Context, onChanged: () -> Unit) = AiModelsSheet.show(ctx, onChanged)

    /** Picking and managing live in the same sheet now. */
    fun manage(ctx: Context, onChanged: () -> Unit) = AiModelsSheet.show(ctx, onChanged)

    /** v1.7.51: the reasoning levels, in the order they appear in the picker. */
    val THINK_MODES = listOf("auto", "off", "low", "medium", "high")

    fun thinkLabel(ctx: Context, mode: String): String = ctx.getString(
        when (mode.lowercase()) {
            "off" -> R.string.think_off
            "low" -> R.string.think_low
            "medium" -> R.string.think_medium
            "high" -> R.string.think_high
            else -> R.string.think_auto
        }
    )

    /**
     * Pick how hard a model should think.
     *
     * Support cannot be detected up front — providers do not advertise it — so
     * every level is offered and a provider that refuses is handled silently on
     * the first request (see AiClient.applyThinking). The screen says as much
     * rather than pretending the list is verified.
     */
    fun pickThinking(ctx: Context, current: String, onPick: (String) -> Unit) =
        pickThinking(ctx, current, "", onPick)

    fun pickThinking(ctx: Context, current: String, model: String, onPick: (String) -> Unit) {
        val labels = THINK_MODES.map { thinkLabel(ctx, it) }.toTypedArray()
        // v1.7.53: say up front whether this model is likely to honour it
        val note = when {
            model.isBlank() -> ""
            AiClient.looksLikeReasoningModel(model) ->
                "\n\n" + ctx.getString(R.string.think_known, model)
            else -> "\n\n" + ctx.getString(R.string.think_unknown, model)
        }
        MaterialAlertDialogBuilder(ctx)
            // v1.7.98: same fix as pickNumber — a message would have replaced
            // the list of depths entirely.
            .setCustomTitle(com.zephyr.browser.settings.SettingsUi.dialogTitle(
                ctx, ctx.getString(R.string.think_title),
                ctx.getString(R.string.think_hint) + note))
            .setSingleChoiceItems(labels, THINK_MODES.indexOf(current).coerceAtLeast(0)) { d, which ->
                onPick(THINK_MODES[which])
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
