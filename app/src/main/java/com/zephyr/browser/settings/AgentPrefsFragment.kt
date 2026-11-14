package com.zephyr.browser.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.ai.AgentCrypto
import com.zephyr.browser.ai.AgentLink
import com.zephyr.browser.ai.AgentPairSheet
import com.zephyr.browser.ai.AiModels

/**
 * v1.7.36: agent pairing as a REAL settings screen.
 *
 * It started life as a bottom sheet and outgrew it immediately — the content no
 * longer fitted, the scroll fought the sheet, and the accent button ended up
 * under the gesture bar (user: «окно испортилось… предлагаю сделать обычное
 * окно, это же как настройка»). Right call: this is configuration, so it now
 * lives in the same scrolling, grouped-card screen as everything else.
 */
class AgentPrefsFragment : Fragment() {

    private var col: LinearLayout? = null
    private var statusRow: LinearLayout? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.agent_title)
        val column = SettingsUi.screen(ctx)
        col = column
        root.addView(SettingsUi.scrollOf(column), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        rebuild()
        AgentLink.onState = { activity?.runOnUiThread { rebuild() } }
        return root
    }

    override fun onDestroyView() {
        AgentLink.onState = null
        super.onDestroyView()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rebuild() {
        val ctx = context ?: return
        val c = col ?: return
        c.removeAllViews()

        // ── live status ──────────────────────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.agent_status_section)))
        val statusCard = SettingsUi.groupCard(ctx)
        statusCard.addView(buildStatus(ctx))
        c.addView(statusCard)

        // ── the code ─────────────────────────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.agent_code_section)))
        val codeCard = SettingsUi.groupCard(ctx)
        codeCard.addView(TextView(ctx).apply {
            text = Prefs.agentCode.ifBlank { getString(R.string.agent_no_code) }
            textSize = if (Prefs.agentCode.isBlank()) 14f else 20f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setTextColor(
                if (Prefs.agentCode.isBlank()) SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant)
                else SettingsUi.accent(ctx)
            )
            setPadding(dp(12), dp(18), dp(12), dp(18))
        })
        codeCard.addView(SettingsUi.rowDivider(ctx))
        codeCard.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_sync, getString(R.string.agent_new_code),
            getString(R.string.agent_new_code_sub)
        ) {
            if (Prefs.agentCode.isBlank()) generate() else confirmRegenerate()
        })
        c.addView(codeCard)

        if (Prefs.agentCode.isNotBlank()) {
            // ── handing it to the agent ──────────────────────────────────────
            c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.agent_handoff_section)))
            val give = SettingsUi.groupCard(ctx)
            give.addView(SettingsUi.clickRowSummary(
                ctx, R.drawable.ic_share, getString(R.string.agent_share_all),
                getString(R.string.agent_share_all_sub)
            ) { shareAll() })
            give.addView(SettingsUi.rowDivider(ctx))
            give.addView(SettingsUi.clickRowSummary(
                ctx, R.drawable.ic_copy, getString(R.string.agent_copy_prompt),
                getString(R.string.agent_copy_prompt_sub)
            ) { copy("zephyr-prompt", instructions()) })
            give.addView(SettingsUi.rowDivider(ctx))
            give.addView(SettingsUi.clickRowSummary(
                ctx, R.drawable.ic_script, getString(R.string.agent_copy_script),
                getString(R.string.agent_copy_script_sub)
            ) {
                val script = AgentPairSheet.readScript(ctx)
                if (script.isBlank()) toast(getString(R.string.backup_failed))
                else copy("zephyr-connector", script)
            })
            give.addView(SettingsUi.rowDivider(ctx))
            give.addView(SettingsUi.clickRowSummary(
                ctx, R.drawable.ic_key, getString(R.string.agent_copy_code),
                getString(R.string.agent_copy_code_sub)
            ) { copy("zephyr-code", Prefs.agentCode) })
            c.addView(give)

            // ── link behaviour ───────────────────────────────────────────────
            c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.agent_link_section)))
            val link = SettingsUi.groupCard(ctx)
            link.addView(SettingsUi.clickRowSummary(
                ctx, R.drawable.ic_refresh_list, getString(R.string.agent_check),
                getString(R.string.agent_check_sub)
            ) {
                AgentLink.ping()
                toast(getString(R.string.agent_check_sent))
            })
            link.addView(SettingsUi.rowDivider(ctx))
            link.addView(SettingsUi.clickRow(
                ctx, R.drawable.ic_website, getString(R.string.agent_relay), Prefs.agentServer
            ) { editRelay() })
            link.addView(SettingsUi.rowDivider(ctx))
            link.addView(SettingsUi.clickRowSummary(
                ctx, R.drawable.ic_delete, getString(R.string.agent_unpair),
                getString(R.string.agent_unpair_sub)
            ) { confirmUnpair() })
            c.addView(link)
        }

        c.addView(TextView(ctx).apply {
            setText(R.string.agent_note)
            textSize = 11.5f
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            setPadding(dp(20), dp(14), dp(20), dp(20))
        })
    }

    private fun buildStatus(ctx: Context): View {
        val (color, label) = when (AgentLink.state) {
            AgentLink.State.ONLINE -> 0xFF10B981.toInt() to
                getString(R.string.agent_state_online, AgentLink.agentName.ifBlank { "агент" })
            AgentLink.State.LISTENING -> 0xFFF59E0B.toInt() to getString(R.string.agent_state_waiting)
            else -> 0xFF9CA3AF.toInt() to getString(R.string.agent_state_off)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            })
            addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
            })
        }.also { statusRow = it }
    }

    // ---------------- actions ----------------

    private fun generate() {
        val code = AgentCrypto.newCode()
        Prefs.setAgentCode(code)
        AiModels.ensureAgentProfile(code)
        AgentLink.restart()
        rebuild()
        toast(getString(R.string.agent_code_ready))
    }

    private fun confirmRegenerate() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.agent_new_code)
            .setMessage(R.string.agent_regenerate_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> generate() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmUnpair() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.agent_unpair)
            .setMessage(R.string.agent_unpair_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                AgentLink.stop()
                Prefs.setAgentCode("")
                AiModels.removeAgentProfile()
                rebuild()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editRelay() {
        val ctx = context ?: return
        val field = android.widget.EditText(ctx).apply {
            setText(Prefs.agentServer)
            setSingleLine(true)
            hint = "https://ntfy.sh"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.agent_relay)
            .setMessage(R.string.agent_relay_hint)
            .setView(LinearLayout(ctx).apply {
                setPadding(dp(22), dp(10), dp(22), dp(4)); addView(field)
            })
            .setPositiveButton(R.string.save) { _, _ ->
                Prefs.setAgentServer(field.text.toString().trim().ifBlank { "https://ntfy.sh" })
                AgentLink.restart()
                rebuild()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun instructions(): String {
        val ctx = context ?: return ""
        return ctx.getString(R.string.agent_prompt_template, Prefs.agentCode, Prefs.agentServer)
    }

    private fun shareAll() {
        val ctx = context ?: return
        val payload = instructions() + "\n\n----- zephyr_link.py -----\n" + AgentPairSheet.readScript(ctx)
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, payload)
                    }, getString(R.string.agent_share_all)
                )
            )
        } catch (_: Throwable) { copy("zephyr-all", payload) }
    }

    private fun copy(label: String, value: String) {
        val ctx = context ?: return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        toast(getString(R.string.copied))
    }

    private fun toast(s: String) {
        context?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() }
    }
}
