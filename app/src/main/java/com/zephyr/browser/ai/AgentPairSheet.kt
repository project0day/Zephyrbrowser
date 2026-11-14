package com.zephyr.browser.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/**
 * v1.7.33: the pairing screen — «TeamViewer для ИИ-агента».
 *
 * Generates the code, shows what to hand the agent, and reports the live link
 * state. The agent needs exactly two things: the code and the connector script,
 * both copyable from here.
 */
object AgentPairSheet {

    fun show(ctx: Context, onChanged: () -> Unit = {}) {
        val (dialog, root) = AiUi.sheet(ctx, 0.88f)
        val d = { v: Int -> AiUi.dp(ctx, v) }

        root.addView(AiUi.sheetTitle(ctx, ctx.getString(R.string.agent_title)))
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.agent_sub)
            textSize = 12.5f
            setTextColor(AiUi.onVariant(ctx))
            setPadding(d(20), 0, d(20), d(10))
        })
        root.addView(AiUi.divider(ctx))

        val (scroll, col) = AiUi.body(ctx)
        root.addView(scroll)

        val statusDot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(d(9), d(9)).apply { marginEnd = d(9) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        val statusText = TextView(ctx).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AiUi.onSurface(ctx))
        }

        fun paintStatus() {
            val (color, label) = when (AgentLink.state) {
                AgentLink.State.ONLINE -> 0xFF10B981.toInt() to
                    ctx.getString(R.string.agent_state_online, AgentLink.agentName.ifBlank { "агент" })
                AgentLink.State.LISTENING -> 0xFFF59E0B.toInt() to ctx.getString(R.string.agent_state_waiting)
                else -> 0xFF9CA3AF.toInt() to ctx.getString(R.string.agent_state_off)
            }
            (statusDot.background as? GradientDrawable)?.setColor(color)
            statusText.text = label
        }

        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(14), d(12), d(14), d(12))
            background = GradientDrawable().apply {
                cornerRadius = d(16).toFloat()
                setColor(AiUi.surfaceVar(ctx))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(12) }
            addView(statusDot)
            addView(statusText)
        })

        // ── the code ─────────────────────────────────────────────────────────
        val codeView = TextView(ctx).apply {
            textSize = 22f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(AiUi.ACCENT)
            setPadding(d(12), d(18), d(12), d(18))
            background = GradientDrawable().apply {
                cornerRadius = d(18).toFloat()
                setColor(AiUi.withAlpha(AiUi.ACCENT, 0x1A))
                setStroke(d(1), AiUi.withAlpha(AiUi.ACCENT, 0x66))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(10) }
        }

        fun refreshCode() {
            codeView.text = Prefs.agentCode.ifBlank { ctx.getString(R.string.agent_no_code) }
            paintStatus()
        }
        refreshCode()
        col.addView(codeView)

        fun copy(label: String, value: String) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        fun instructions(): String {
            val code = Prefs.agentCode
            return ctx.getString(R.string.agent_prompt_template, code, Prefs.agentServer)
        }

        fun actionRow(titleRes: Int, subRes: Int, onClick: () -> Unit): View =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(14), d(13), d(14), d(13))
                background = GradientDrawable().apply {
                    cornerRadius = d(16).toFloat()
                    setColor(AiUi.surfaceVar(ctx))
                }
                foreground = AiUi.ripple(ctx, borderless = false)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = d(8) }
                addView(TextView(ctx).apply {
                    setText(titleRes)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(AiUi.onSurface(ctx))
                })
                addView(TextView(ctx).apply {
                    setText(subRes)
                    textSize = 12f
                    setPadding(0, d(3), 0, 0)
                    setTextColor(AiUi.onVariant(ctx))
                })
                setOnClickListener { onClick() }
            }

        col.addView(actionRow(R.string.agent_copy_code, R.string.agent_copy_code_sub) {
            if (Prefs.agentCode.isBlank()) Toast.makeText(ctx, R.string.agent_no_code, Toast.LENGTH_SHORT).show()
            else copy("zephyr-code", Prefs.agentCode)
        })
        col.addView(actionRow(R.string.agent_copy_prompt, R.string.agent_copy_prompt_sub) {
            if (Prefs.agentCode.isBlank()) Toast.makeText(ctx, R.string.agent_no_code, Toast.LENGTH_SHORT).show()
            else copy("zephyr-prompt", instructions())
        })
        col.addView(actionRow(R.string.agent_copy_script, R.string.agent_copy_script_sub) {
            val script = readScript(ctx)
            if (script.isBlank()) Toast.makeText(ctx, R.string.backup_failed, Toast.LENGTH_SHORT).show()
            else copy("zephyr-connector", script)
        })
        col.addView(actionRow(R.string.agent_share_all, R.string.agent_share_all_sub) {
            val payload = instructions() + "\n\n----- zephyr_link.py -----\n" + readScript(ctx)
            try {
                ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, payload)
                        }, ctx.getString(R.string.agent_share_all)
                    )
                )
            } catch (_: Throwable) { copy("zephyr-all", payload) }
        })

        col.addView(TextView(ctx).apply {
            setText(R.string.agent_note)
            textSize = 11.5f
            setTextColor(AiUi.onVariant(ctx))
            setPadding(d(4), d(12), d(4), d(4))
        })

        // ── controls ─────────────────────────────────────────────────────────
        val ping = TextView(ctx).apply {
            setText(R.string.agent_check)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(AiUi.onSurface(ctx))
            setPadding(d(14), d(12), d(14), d(12))
            background = GradientDrawable().apply {
                cornerRadius = d(16).toFloat()
                setColor(AiUi.surfaceVar(ctx))
            }
            foreground = AiUi.ripple(ctx, borderless = false)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(8) }
            setOnClickListener {
                AgentLink.ping()
                Toast.makeText(ctx, R.string.agent_check_sent, Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(ping)

        if (Prefs.agentCode.isNotBlank()) {
            col.addView(actionRow(R.string.agent_unpair, R.string.agent_unpair_sub) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.agent_unpair)
                    .setMessage(R.string.agent_unpair_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        AgentLink.stop()
                        Prefs.setAgentCode("")
                        AiModels.removeAgentProfile()
                        onChanged()
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            })
        }

        AgentLink.onState = { s -> (ctx as? android.app.Activity)?.runOnUiThread { paintStatus() } }
        dialog.setOnDismissListener { AgentLink.onState = null }

        root.addView(AiUi.primaryButton(ctx, ctx.getString(R.string.agent_new_code)) {
            val code = AgentCrypto.newCode()
            Prefs.setAgentCode(code)
            AiModels.ensureAgentProfile(code)
            AgentLink.restart()
            refreshCode()
            onChanged()
            Toast.makeText(ctx, R.string.agent_code_ready, Toast.LENGTH_SHORT).show()
        })

        dialog.show()
    }

    /** The connector script ships as an asset so it is always in sync with the app. */
    fun readScript(ctx: Context): String = try {
        ctx.assets.open("zephyr_agent_link.py").bufferedReader().use { it.readText() }
    } catch (_: Throwable) { "" }
}
