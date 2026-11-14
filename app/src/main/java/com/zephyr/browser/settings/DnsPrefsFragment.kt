package com.zephyr.browser.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.proxy.DnsCatalog
import com.zephyr.browser.proxy.DnsProbe
import com.zephyr.browser.proxy.DnsVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.7.0: built-in DNS screen — provider selection (10 presets + custom),
 * transport (plain / DoT / DoH), DNS-leak protection and the private DNS-only
 * VPN mode with a live status/counter card.
 */
class DnsPrefsFragment : Fragment() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var col: LinearLayout

    // live status views (updated in place)
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var statusCount: TextView? = null
    private var pulseAnim: ObjectAnimator? = null
    private var testResultView: TextView? = null

    private val RC_DNS_PREPARE = 806

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_dns)
        col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        build(ctx)
        poll()
        return root
    }

    private fun build(ctx: android.content.Context) {
        col.removeAllViews()

        // ---- live status card ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.dns_status)))
        col.addView(statusCard(ctx))

        // ---- provider picker ----
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.dns_provider)))
        val current = when {
            Prefs.dnsProvider == DnsCatalog.CUSTOM_ID -> getString(R.string.dns_custom)
            else -> DnsCatalog.byId(Prefs.dnsProvider)?.name ?: DnsCatalog.SYSTEM.name
        }
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_globe, getString(R.string.dns_provider), current) {
            pickProvider(ctx)
        })

        // ---- transport ----
        val protoName = when (Prefs.dnsProtocol) {
            0 -> getString(R.string.dns_proto_plain)
            1 -> getString(R.string.dns_proto_dot)
            else -> getString(R.string.dns_proto_doh)
        }
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_lock, getString(R.string.dns_protocol), protoName) {
            pickProtocol(ctx)
        })

        // ---- v1.7.1: provider health check ("проверьте подключение к интернету…" →
        //      now the user can SEE which provider/protocol works from THEIR network) ----
        val testRow = SettingsUi.clickRow(ctx, R.drawable.ic_shield, getString(R.string.dns_test),
            getString(R.string.dns_test_idle)) {
            runProviderTest(ctx)
        }
        testResultView = testRow.getChildAt(2) as? TextView
        col.addView(testRow)

        // ---- custom IP entry ----
        if (Prefs.dnsProvider == DnsCatalog.CUSTOM_ID) {
            col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_edit, getString(R.string.dns_custom_ip),
                Prefs.dnsCustomIp.ifEmpty { "1.2.3.4" }) {
                editCustomIp(ctx)
            })
        }

        // ---- leak protection ----
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_shield, getString(R.string.dns_leak),
            getString(R.string.dns_leak_sum), Prefs.dnsLeakProtection) {
            Prefs.setDnsLeakProtection(it)
            restartIfActive(ctx)
        })

        // ---- info ----
        col.addView(SettingsUi.gradientCard(ctx,
            getString(R.string.dns_apply_note),
            getString(R.string.dns_apply_note_sum),
            intArrayOf(0xFF7C5CE0.toInt(), 0xFF4A3488.toInt())) { })
    }

    // ---------------- status card with animated dot ----------------

    private fun statusCard(ctx: android.content.Context): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), 0, dp(10), dp(8))
            }
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF242032.toInt(), 0xFF17131F.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }
        statusDot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF6B7280.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(12) }
        }
        card.addView(statusDot)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = TextView(ctx).apply {
            text = getString(R.string.dns_state_inactive)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
        }
        texts.addView(statusText)
        statusCount = TextView(ctx).apply {
            text = getString(R.string.dns_queries_fmt, 0L, 0L)
            textSize = 11.5f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(statusCount)
        card.addView(texts)

        // toggle chip on the right
        val chip = TextView(ctx).apply {
            text = getString(if (DnsVpnService.isActive()) R.string.dns_turn_off else R.string.dns_turn_on)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = if (DnsVpnService.isActive()) intArrayOf(0xFFB91C1C.toInt(), 0xFFDC2626.toInt())
                else intArrayOf(0xFF047857.toInt(), 0xFF0E9F6E.toInt())
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            foreground = PanelUiRipple(ctx)
            setOnClickListener { togglePrivateMode(ctx) }
        }
        card.addView(chip)
        return card
    }

    private fun refreshStatus() {
        val ctx = context ?: return
        val dot = statusDot ?: return
        val active = DnsVpnService.isActive()
        val failed = DnsVpnService.state == DnsVpnService.STATE_FAILED
        (dot.background as? GradientDrawable)?.setColor(
            when {
                failed -> 0xFFDC2626.toInt()
                active -> 0xFF0E9F6E.toInt()
                else -> 0xFF6B7280.toInt()
            }
        )
        statusText?.text = when {
            failed -> getString(R.string.vpn_state_failed) + (DnsVpnService.lastError?.let { ": $it" } ?: "")
            DnsVpnService.state == DnsVpnService.STATE_CONNECTING -> getString(R.string.vpn_state_connecting)
            active -> getString(R.string.dns_state_active)
            else -> getString(R.string.dns_state_inactive)
        }
        statusCount?.text = when {
            active -> getString(R.string.dns_queries_fmt, DnsVpnService.queries, DnsVpnService.failed)
            else -> providerLine(ctx)
        }
        // gentle pulse while active
        if (active && pulseAnim == null) {
            pulseAnim = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = DecelerateInterpolator(1.2f)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) { dot.alpha = 1f }
                })
                start()
            }
        } else if (!active) {
            pulseAnim?.cancel(); pulseAnim = null
        }
    }

    private fun providerLine(ctx: android.content.Context): String {
        val p = DnsCatalog.byId(Prefs.dnsProvider)
        return when {
            Prefs.dnsProvider == DnsCatalog.CUSTOM_ID ->
                getString(R.string.dns_provider_line, Prefs.dnsCustomIp.ifEmpty { "—" })
            p != null && p.primary != null -> getString(R.string.dns_provider_line, p.name)
            else -> getString(R.string.dns_provider_line, getString(R.string.dns_desc_system_title))
        }
    }

    private fun poll() {
        ui.postDelayed({
            if (!isAdded) return@postDelayed
            refreshStatus()
            poll()
        }, 800)
    }

    // ---------------- actions ----------------

    private fun togglePrivateMode(ctx: android.content.Context) {
        if (DnsVpnService.isActive()) {
            DnsVpnService.stop(ctx)
            Prefs.setDnsPrivateMode(false)
            ui.postDelayed({ build(ctx) }, 300)
            return
        }
        if (Prefs.dnsProvider == 0 ||
            (Prefs.dnsProvider == DnsCatalog.CUSTOM_ID && Prefs.dnsCustomIp.isBlank())) {
            Toast.makeText(ctx, R.string.dns_pick_provider_first, Toast.LENGTH_LONG).show()
            pickProvider(ctx)
            return
        }
        try {
            val prep = android.net.VpnService.prepare(ctx)
            if (prep != null) {
                Toast.makeText(ctx, R.string.vpn_need_consent, Toast.LENGTH_SHORT).show()
                startActivityForResult(prep, RC_DNS_PREPARE)
            } else {
                startPrivateMode(ctx)
            }
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.vpn_no_vpn_support, Toast.LENGTH_LONG).show()
        }
    }

    private fun startPrivateMode(ctx: android.content.Context) {
        Prefs.setDnsPrivateMode(true)
        DnsVpnService.start(ctx)
        ui.postDelayed({ refreshStatus() }, 250)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_DNS_PREPARE) {
            val ctx = context ?: return
            if (resultCode == android.app.Activity.RESULT_OK) startPrivateMode(ctx)
            else Toast.makeText(ctx, R.string.vpn_consent_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartIfActive(ctx: android.content.Context) {
        if (DnsVpnService.isActive()) {
            DnsVpnService.stop(ctx)
            ui.postDelayed({ DnsVpnService.start(ctx) }, 400)
        }
    }

    // ---------------- v1.7.1: provider health check ----------------

    private fun runProviderTest(ctx: android.content.Context) {
        val provider: DnsCatalog.Provider = when {
            Prefs.dnsProvider == DnsCatalog.CUSTOM_ID -> {
                val ip = Prefs.dnsCustomIp.trim()
                if (ip.isEmpty()) {
                    Toast.makeText(ctx, R.string.dns_pick_provider_first, Toast.LENGTH_LONG).show()
                    pickProvider(ctx)
                    return
                }
                DnsCatalog.Provider(DnsCatalog.CUSTOM_ID, "Custom DNS", ip, null, null, null, null, R.string.dns_desc_custom)
            }
            Prefs.dnsProvider == 0 -> {
                Toast.makeText(ctx, R.string.dns_pick_provider_first, Toast.LENGTH_LONG).show()
                pickProvider(ctx)
                return
            }
            else -> DnsCatalog.byId(Prefs.dnsProvider) ?: run {
                Toast.makeText(ctx, R.string.dns_pick_provider_first, Toast.LENGTH_LONG).show()
                pickProvider(ctx)
                return
            }
        }
        testResultView?.text = getString(R.string.dns_testing)
        io.launch {
            val latency = DnsProbe.test(provider, Prefs.dnsProtocol)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                testResultView?.text = when {
                    latency != null -> getString(R.string.dns_test_ok_fmt, latency)
                    else -> getString(R.string.dns_test_fail)
                }
                if (latency == null && Prefs.dnsProtocol != 0) {
                    Toast.makeText(ctx, R.string.dns_test_try_plain, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pickProvider(ctx: android.content.Context) {
        val entries = mutableListOf<String>()
        for (p in DnsCatalog.ALL) entries.add(p.name)
        entries.add(getString(R.string.dns_custom))
        val items = entries.toTypedArray()
        val checked = if (Prefs.dnsProvider == DnsCatalog.CUSTOM_ID) DnsCatalog.ALL.size else Prefs.dnsProvider
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dns_provider)
            .setSingleChoiceItems(items, checked) { d, which ->
                val id = if (which == DnsCatalog.ALL.size) DnsCatalog.CUSTOM_ID else DnsCatalog.ALL[which].id
                Prefs.setDnsProvider(id)
                d.dismiss()
                if (id == DnsCatalog.CUSTOM_ID) editCustomIp(ctx)
                restartIfActive(ctx)
                build(ctx)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickProtocol(ctx: android.content.Context) {
        val prov = DnsCatalog.byId(Prefs.dnsProvider)
        val names = mutableListOf(getString(R.string.dns_proto_plain))
        if (prov?.dotHost != null) names.add(getString(R.string.dns_proto_dot))
        if (prov?.dohHost != null) names.add(getString(R.string.dns_proto_doh))
        val values = mutableListOf(0, 1, 2)
        // map back to prefs indices
        val currentIdx = when (Prefs.dnsProtocol) {
            0 -> 0
            1 -> if (names.size > 1) 1 else 0
            else -> names.size - 1
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dns_protocol)
            .setSingleChoiceItems(names.toTypedArray(), currentIdx) { d, which ->
                Prefs.setDnsProtocol(values[which])
                d.dismiss()
                restartIfActive(ctx)
                build(ctx)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editCustomIp(ctx: android.content.Context) {
        val field = com.zephyr.browser.panels.PanelUi.dialogField(ctx, getString(R.string.dns_custom_ip), Prefs.dnsCustomIp)
        field.inputType = android.text.InputType.TYPE_CLASS_TEXT
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(com.zephyr.browser.panels.PanelUi.fieldWrap(field))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dns_custom_ip)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val ip = field.text.toString().trim()
                val valid = ip.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) ||
                    ip.matches(Regex("^[0-9a-fA-F:]+:[0-9a-fA-F:]*$"))
                if (!valid) {
                    Toast.makeText(ctx, R.string.dns_bad_ip, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Prefs.setDnsCustomIp(ip)
                restartIfActive(ctx)
                build(ctx)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun PanelUiRipple(ctx: android.content.Context): android.graphics.drawable.Drawable? =
        SettingsUi.ripple(ctx)

    override fun onDestroyView() {
        pulseAnim?.cancel()
        super.onDestroyView()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
