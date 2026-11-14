package com.zephyr.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.ai.AgentLink
import com.zephyr.browser.ai.AiModels
import com.zephyr.browser.ai.AiSwarm

/**
 * v1.7.36: «Рой ИИ-агентов» — pick who answers.
 *
 * The assistant used to be pinned to one profile, so every outage was a dead
 * end: the agent's sandbox resets daily, a free tier runs out, a heavy model
 * times out. Here the user marks which of their models take part, and the
 * assistant asks the team instead of an individual — availability becomes a
 * property of the setup rather than a property of one provider.
 */
class SwarmPrefsFragment : Fragment() {

    private var col: LinearLayout? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.swarm_title)
        val column = SettingsUi.screen(ctx)
        col = column
        root.addView(SettingsUi.scrollOf(column), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        rebuild()
        return root
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rebuild() {
        val ctx = context ?: return
        val c = col ?: return
        c.removeAllViews()

        c.addView(TextView(ctx).apply {
            setText(R.string.swarm_intro)
            textSize = 12.5f
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            setPadding(dp(20), dp(6), dp(20), dp(4))
        })

        // ── master switch + mode ─────────────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.swarm_mode_section)))
        val head = SettingsUi.groupCard(ctx)
        head.addView(SettingsUi.switchRow(
            ctx, R.drawable.ic_widgets, getString(R.string.swarm_enable),
            getString(R.string.swarm_enable_sub), Prefs.swarmEnabled
        ) { on -> Prefs.setSwarmEnabled(on); rebuild() })
        head.addView(SettingsUi.rowDivider(ctx))
        head.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_sort, getString(R.string.swarm_mode),
            if (Prefs.swarmMode == AiSwarm.MODE_COUNCIL) getString(R.string.swarm_mode_council_sub)
            else getString(R.string.swarm_mode_chain_sub)
        ) { pickMode() })
        c.addView(head)

        // ── the team ─────────────────────────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.swarm_members_section)))
        val profiles = AiModels.list()
        val card = SettingsUi.groupCard(ctx)
        var added = 0
        profiles.forEach { p ->
            val configured = AiModels.isConfigured(p)
            val isAgent = AiModels.isAgent(p)
            val sub = when {
                isAgent && AgentLink.state == AgentLink.State.ONLINE ->
                    getString(R.string.swarm_member_agent_online)
                isAgent -> getString(R.string.swarm_member_agent_offline)
                !configured -> getString(R.string.swarm_member_nokey)
                else -> p.model
            }
            if (added > 0) card.addView(SettingsUi.rowDivider(ctx))
            card.addView(SettingsUi.switchRow(
                ctx,
                if (isAgent) R.drawable.ic_sync else R.drawable.ic_ai,
                p.name, sub, AiSwarm.isMember(p)
            ) { on -> AiSwarm.setMember(p, on); rebuild() })
            added++
        }
        if (added == 0) {
            card.addView(TextView(ctx).apply {
                setText(R.string.swarm_no_models)
                textSize = 13f
                setPadding(dp(16), dp(20), dp(16), dp(20))
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            })
        }
        c.addView(card)

        // ── live summary ─────────────────────────────────────────────────────
        val team = AiSwarm.members()
        c.addView(TextView(ctx).apply {
            text = if (!Prefs.swarmEnabled) getString(R.string.swarm_off_note)
            else if (team.isEmpty()) getString(R.string.swarm_empty_note)
            else getString(R.string.swarm_order_note, team.joinToString(" → ") { it.name })
            textSize = 12f
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            setPadding(dp(20), dp(8), dp(20), dp(6))
        })

        // ── where to get the free keys ───────────────────────────────────────
        c.addView(SettingsUi.sectionHeader(ctx, getString(R.string.swarm_free_section)))
        val free = SettingsUi.groupCard(ctx)
        free.addView(TextView(ctx).apply {
            setText(R.string.swarm_free_body)
            textSize = 12.5f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
        })
        free.addView(SettingsUi.rowDivider(ctx))
        free.addView(SettingsUi.clickRowSummary(
            ctx, R.drawable.ic_add, getString(R.string.ai_add_model),
            getString(R.string.swarm_add_sub)
        ) {
            com.zephyr.browser.ai.AiModelDialogs.editProfile(ctx, null) { rebuild() }
        })
        c.addView(free)
    }

    private fun pickMode() {
        val ctx = context ?: return
        val labels = arrayOf(
            getString(R.string.swarm_mode_chain) + "\n" + getString(R.string.swarm_mode_chain_sub),
            getString(R.string.swarm_mode_council) + "\n" + getString(R.string.swarm_mode_council_sub)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.swarm_mode)
            .setSingleChoiceItems(labels, Prefs.swarmMode) { d, which ->
                Prefs.setSwarmMode(which)
                d.dismiss()
                rebuild()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
