package com.zephyr.browser.ai

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/**
 * v1.7.25b: the model picker, redesigned.
 *
 * It used to be an AlertDialog with a radio list and THREE stacked buttons
 * («Добавить модель» / «Управление» / «Отмена») eating half the dialog (user:
 * «три кнопки подряд много место занимают»). Now it is a proper sheet: one tap
 * on a row switches the model, the pencil on the row edits it, and a single
 * full-width button at the bottom adds a new one. Nothing else.
 */
object AiModelsSheet {

    fun show(ctx: Context, onChanged: () -> Unit) {
        val (dialog, root) = AiUi.sheet(ctx, 0.72f)
        val d = { v: Int -> AiUi.dp(ctx, v) }

        root.addView(AiUi.sheetTitle(ctx, ctx.getString(R.string.ai_choose_model)))
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.ai_choose_model_sub)
            textSize = 12.5f
            setTextColor(AiUi.onVariant(ctx))
            setPadding(d(20), 0, d(20), d(10))
        })
        root.addView(AiUi.divider(ctx))

        val (scroll, col) = AiUi.body(ctx)
        root.addView(scroll)

        fun rebuild() {
            col.removeAllViews()

            // ── the swarm sits at the top, as a pickable "model" of its own ──
            // v1.7.37 (user: «в выборе моделей нету роя — как мне писаться?»):
            // the swarm could only be switched on from Settings, so from the
            // chat it was invisible and there was no way to tell what would
            // answer. It is now the first entry here, exactly like a model.
            val team = AiSwarm.members()
            val swarmOn = Prefs.swarmEnabled && team.isNotEmpty()
            val swarmRow = AiUi.selectableRow(ctx, swarmOn)
            swarmRow.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_widgets)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setPadding(d(9), d(9), d(9), d(9))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(AiUi.ACCENT, AiUi.ACCENT_2)
                ).apply { cornerRadius = d(13).toFloat() }
                layoutParams = LinearLayout.LayoutParams(d(40), d(40))
                    .apply { marginEnd = d(13) }
            })
            swarmRow.addView(
                AiUi.twoLine(
                    ctx,
                    ctx.getString(R.string.swarm_title),
                    when {
                        team.isEmpty() -> ctx.getString(R.string.swarm_pick_members)
                        else -> team.joinToString(" → ") { it.name }
                    }
                )
            )
            if (swarmOn) swarmRow.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(AiUi.ACCENT)
                layoutParams = LinearLayout.LayoutParams(d(22), d(22)).apply { marginEnd = d(6) }
            })
            swarmRow.addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_tune)
                background = AiUi.ripple(ctx)
                imageTintList = android.content.res.ColorStateList.valueOf(AiUi.onVariant(ctx))
                setPadding(d(8), d(8), d(8), d(8))
                contentDescription = ctx.getString(R.string.swarm_title)
                layoutParams = LinearLayout.LayoutParams(d(36), d(36))
                setOnClickListener { openSwarmSettings(ctx); dialog.dismiss() }
            })
            swarmRow.setOnClickListener {
                if (team.isEmpty()) {
                    // nothing to run yet — send the user where they can pick
                    openSwarmSettings(ctx)
                    dialog.dismiss()
                } else {
                    Prefs.setSwarmEnabled(true)
                    onChanged()
                    dialog.dismiss()
                }
            }
            col.addView(swarmRow)

            col.addView(TextView(ctx).apply {
                setText(R.string.swarm_or_single)
                textSize = 11.5f
                setTextColor(AiUi.onVariant(ctx))
                setPadding(d(6), d(10), d(6), d(6))
            })

            val items = AiModels.list()
            val active = AiModels.activeIndex()
            items.forEachIndexed { i, p ->
                val selected = !swarmOn && i == active
                val row = AiUi.selectableRow(ctx, selected)
                row.addView(AiUi.initialAvatar(ctx, p.name))
                row.addView(AiUi.twoLine(ctx, p.name, p.model))

                if (selected) row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(AiUi.ACCENT)
                    layoutParams = LinearLayout.LayoutParams(d(22), d(22))
                        .apply { marginEnd = d(6) }
                })

                row.addView(ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_edit)
                    background = AiUi.ripple(ctx)
                    imageTintList = android.content.res.ColorStateList.valueOf(AiUi.onVariant(ctx))
                    setPadding(d(8), d(8), d(8), d(8))
                    contentDescription = ctx.getString(R.string.ai_edit_model)
                    layoutParams = LinearLayout.LayoutParams(d(36), d(36))
                    setOnClickListener {
                        AiModelDialogs.editProfile(ctx, i) { rebuild(); onChanged() }
                    }
                })

                row.setOnClickListener {
                    // picking one model means "not the swarm"
                    Prefs.setSwarmEnabled(false)
                    AiModels.setActive(i)
                    onChanged()
                    dialog.dismiss()
                }
                col.addView(row)
            }

            // inline hint when nothing is usable yet
            if (items.none { AiModels.isConfigured(it) }) {
                col.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.ai_no_key_msg)
                    textSize = 12f
                    setTextColor(AiUi.onVariant(ctx))
                    setPadding(d(14), d(10), d(14), d(4))
                    background = GradientDrawable().apply {
                        cornerRadius = d(14).toFloat()
                        setColor(AiUi.withAlpha(0xFFF59E0B.toInt(), 0x1F))
                    }
                    gravity = Gravity.START
                })
            }
        }
        rebuild()

        root.addView(AiUi.primaryButton(ctx, "＋  " + ctx.getString(R.string.ai_add_model)) {
            AiModelDialogs.editProfile(ctx, null) { rebuild(); onChanged() }
        })

        dialog.show()
    }

    /** Jump straight to the swarm screen in Settings. */
    private fun openSwarmSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(ctx, com.zephyr.browser.settings.SettingsActivity::class.java).apply {
                    putExtra(com.zephyr.browser.settings.SettingsActivity.EXTRA_SCREEN, "swarm")
                }
            )
        } catch (_: Throwable) {}
    }
}
