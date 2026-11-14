package com.zephyr.browser.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.panels.PanelUi
import com.zephyr.browser.proxy.DnsCatalog
import com.zephyr.browser.proxy.DnsVpnService
import com.zephyr.browser.proxy.ProxyHelper
import com.zephyr.browser.proxy.WireGuardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * v1.7.4 VPN screen — WireGuard + AmneziaWG only, clean and premium.
 *
 * Cloudflare WARP was removed in v1.7.4 (user: registration stalled on the
 * device and .conf import covers the need); OpenVPN went earlier, in v1.7.3.
 * What remains is a clean, premium tunnel screen:
 *  - HERO status card with the big animated power button (idle → connecting
 *    arc → connected pulse → failed red), in-place updates only (no flicker);
 *  - unified WireGuard profile list (WG / AWG badges), tolerant validation,
 *    multi-select delete, SAF .conf import;
 *  - kill-switch (auto-reconnect), split tunneling, browser proxy section and
 *    the built-in DNS row (leak protection lives on the DNS screen).
 */
class ProxyPrefsFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var col: LinearLayout

    private val rc get() = requireContext().resources.displayMetrics.density

    // hero views (updated in place — never rebuilt while polling)
    private lateinit var powerBtn: LinearLayout
    private lateinit var powerIcon: ImageView
    private lateinit var powerRing: View
    private lateinit var powerSpin: ProgressBar
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var heroCard: LinearLayout

    // profiles
    private lateinit var profilesList: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private val selected = mutableSetOf<String>()   // absolute file paths

    private var pulseAnim: ObjectAnimator? = null

    // consent flow (WireGuard tunnels of every flavor share it)
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val pc = pendingConnect
        pendingConnect = null
        if (res.resultCode == android.app.Activity.RESULT_OK && pc != null) {
            actuallyConnect(pc)
        } else if (pc != null) {
            context?.let {
                Toast.makeText(it, R.string.vpn_consent_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // v1.7.3: SAF .conf/.awg import via the modern activity-result API
    private val confImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importConf(uri)
    }

    private class PendingConnect(val file: File)
    private var pendingConnect: PendingConnect? = null

    private val uiPrefs by lazy { requireContext().getSharedPreferences("vpn_ui", android.content.Context.MODE_PRIVATE) }
    private fun lastProfile(): PendingConnect? {
        // v1.7.3: WireGuard-only reconnect; a stale "ovpn" entry from older
        // versions is ignored (its engine no longer exists)
        val kind = uiPrefs.getString("last_kind", null) ?: return null
        if (kind != "wg") return null
        val path = uiPrefs.getString("last_path", null) ?: return null
        val f = File(path)
        return if (f.isFile) PendingConnect(f) else null
    }
    private fun rememberProfile(pc: PendingConnect) {
        uiPrefs.edit().putString("last_kind", "wg").putString("last_path", pc.file.absolutePath).apply()
    }

    // v1.7.3: WG connection journal (successor of the removed engine log) —
    // state transitions observed by the poller, timestamped, capped ring buffer.
    private val connLog = ArrayDeque<String>()
    private var lastPolledWg = WireGuardManager.STATE_IDLE

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_proxy)
        col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        build(ctx)
        pollStatus()
        return root
    }

    // =====================================================================
    // Screen construction (built ONCE; status updates are in-place)
    // =====================================================================

    private fun build(ctx: android.content.Context) {
        col.removeAllViews()

        if (!ProxyHelper.proxySupported()) {
            col.addView(SettingsUi.gradientCard(ctx, getString(R.string.proxy_not_supported),
                getString(R.string.about_engine), intArrayOf(0xFFEA580C.toInt(), 0xFFEC4899.toInt())) { })
        }

        addHeroCard(ctx)
        // v1.7.5 (user: «пункт Встроенный DNS тоже убери — для него отдельный
        // пункт уже есть в настройках»): the DNS row is GONE from the VPN page —
        // DNS lives in its own Settings → DNS screen.
        addProfilesSection(ctx)
        addBrowserProxySection(ctx)
    }

    // ---------------- hero: the big animated on/off ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addHeroCard(ctx: android.content.Context) {
        heroCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), dp(10), dp(10), dp(4))
            }
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF2A2438.toInt(), 0xFF17131F.toInt())
                cornerRadius = dp(24).toFloat()
            }
        }
        col.addView(heroCard)

        // pulsing halo behind the button
        powerRing = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0x660E9F6E.toInt())
            }
            visibility = View.INVISIBLE
        }

        powerBtn = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0xFF4B5563.toInt(), 0xFF374151.toInt())
                cornerRadius = dp(46).toFloat()
            }
            elevation = dp(6).toFloat()
        }
        powerIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_vpn)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        powerBtn.addView(powerIcon)
        powerSpin = ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
            indeterminateTintList = ColorStateList.valueOf(0xFFFBBF24.toInt())
            visibility = View.INVISIBLE
        }

        // stack: ring + button + spinner overlay
        val stack = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val ringLp = android.widget.FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER)
        stack.addView(powerRing, ringLp)
        val btnLp = android.widget.FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER)
        stack.addView(powerBtn, btnLp)
        stack.addView(powerSpin, btnLp)
        heroCard.addView(stack)

        powerBtn.setOnClickListener { onPowerTap() }
        powerSpin.setOnClickListener { onPowerTap() }

        statusTitle = TextView(ctx).apply {
            text = getString(R.string.vpn_state_idle)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        heroCard.addView(statusTitle)
        statusSub = TextView(ctx).apply {
            text = getString(R.string.vpn_hero_hint)
            textSize = 12.5f
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            // tap the status line → connection journal (what failed / how it went)
            setOnClickListener { showConnectionLog() }
        }
        heroCard.addView(statusSub)
    }

    // v1.7.5: addDnsRow removed — the DNS entry point lives in Settings → DNS.
    // (kept as a comment anchor; the function was deleted)

    // ---------------- profiles ----------------

    private fun addProfilesSection(ctx: android.content.Context) {
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.vpn_profiles_all)))

        // selection toolbar (visible only in selection mode)
        selectionBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(SettingsUi.themedColor(ctx, R.attr.colorPrimaryContainer, 0x337C5CE0.toInt()))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), 0, dp(10), dp(8))
            }
            visibility = View.GONE
        }
        selectionCount = TextView(ctx).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectionBar.addView(selectionCount)
        selectionBar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_check)
            background = PanelUi.ripple(ctx)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = ColorStateList.valueOf(SettingsUi.themedColor(ctx, R.attr.colorPrimary))
            contentDescription = getString(R.string.vpn_select_all)
            setOnClickListener { selectAll() }
        })
        selectionBar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_delete)
            background = PanelUi.ripple(ctx)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = ColorStateList.valueOf(0xFFDC2626.toInt())
            contentDescription = getString(R.string.vpn_delete_selected)
            setOnClickListener { confirmDeleteSelected() }
        })
        selectionBar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            background = PanelUi.ripple(ctx)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = getString(android.R.string.cancel)
            setOnClickListener { exitSelection() }
        })
        col.addView(selectionBar)

        // import button row (v1.7.3: single .conf pill — WG/AWG/WARP configs)
        val importRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(6))
        }
        importRow.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                setColor(SettingsUi.themedColor(ctx, R.attr.colorSurface))
                setStroke(dp(1), SettingsUi.themedColor(ctx, R.attr.colorOutline))
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            foreground = PanelUi.ripple(ctx)
            setOnClickListener { pickConf() }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_download)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                imageTintList = ColorStateList.valueOf(SettingsUi.themedColor(ctx, R.attr.colorPrimary))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.vpn_import_conf)
                textSize = 12f
                setPadding(dp(6), 0, 0, 0)
                maxLines = 1
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
            })
        })
        col.addView(importRow)

        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_website, getString(R.string.vpn_browser_only),
            getString(R.string.vpn_browser_only_sum), uiPrefs.getBoolean("browser_only", false)) {
            uiPrefs.edit().putBoolean("browser_only", it).apply()
        })

        // v1.7.2: kill-switch — auto-reconnect when the tunnel drops/fails
        col.addView(SettingsUi.switchRow(ctx, R.drawable.ic_shield, getString(R.string.vpn_kill_switch),
            getString(R.string.vpn_kill_switch_sum), uiPrefs.getBoolean("vpn_kill_switch", false)) {
            uiPrefs.edit().putBoolean("vpn_kill_switch", it).apply()
            if (it) killSwitchArmed = true else reconnectAttempts = 0
        })

        // v1.7.2: split tunneling — apps whose traffic bypasses the VPN
        val splitRow = SettingsUi.clickRow(ctx, R.drawable.ic_widgets, getString(R.string.vpn_split_apps),
            splitAppsSummary()) { showSplitAppsDialog() }
        // clickRow layout: [icon][title][value][chevron] — value is childCount-2
        splitRowSummaryRef = splitRow.getChildAt(splitRow.childCount - 2) as? android.widget.TextView
        col.addView(splitRow)

        profilesList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(profilesList)
        reloadProfiles()
    }

    private fun addBrowserProxySection(ctx: android.content.Context) {
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.proxy_browser_section)))
        fun modeValue(): String = when (Prefs.proxyType) {
            1 -> getString(R.string.proxy_http)
            2 -> getString(R.string.proxy_orbot)
            3 -> getString(R.string.proxy_i2p)
            else -> getString(R.string.proxy_none)
        }
        col.addView(SettingsUi.clickRow(ctx, R.drawable.ic_vpn, getString(R.string.proxy_mode), modeValue()) {
            val modes = arrayOf(getString(R.string.proxy_none), getString(R.string.proxy_http),
                getString(R.string.proxy_orbot), getString(R.string.proxy_i2p))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.proxy_mode)
                .setSingleChoiceItems(modes, Prefs.proxyType) { d, which ->
                    Prefs.setProxy(which, Prefs.proxyHost, Prefs.proxyPort)
                    d.dismiss()
                    when (which) {
                        2 -> maybeOrbot(ctx)
                        1 -> hostPortDialog(ctx)
                    }
                    ProxyHelper.applyFromPrefs(ctx)
                    build(ctx)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
        if (Prefs.proxyType == 2) {
            col.addView(SettingsUi.gradientCard(ctx, getString(R.string.proxy_orbot),
                getString(R.string.proxy_orbot_note),
                intArrayOf(0xFF4C1D95.toInt(), 0xFF7C3AED.toInt())) {
                if (!ProxyHelper.openOrbot(ctx)) {
                    Toast.makeText(ctx, R.string.app_not_found, Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    // =====================================================================
    // Profile list (stable, sorted, multi-select)
    // =====================================================================

    private data class ProfileRow(
        val file: File,
        val isAmnezia: Boolean,
        val title: String,
        val subtitle: String
    )

    private fun loadRows(): List<ProfileRow> {
        val ctx = context ?: return emptyList()
        val rows = WireGuardManager.listConfigs(ctx).map { f ->
            // v1.7.1: tolerant validator (Amnezia/unknown lines no longer mark a
            // perfectly usable config as «unparsed»)
            val check = WireGuardManager.validate(ctx, f)
            val title = f.nameWithoutExtension
            val subtitle = when {
                check.error != null -> getString(R.string.vpn_unparsed)
                check.amnezia -> getString(R.string.vpn_row_sub, "AmneziaWG", "UDP", f.nameWithoutExtension.take(18))
                else -> getString(R.string.vpn_row_sub, "WireGuard", "UDP", f.nameWithoutExtension.take(18))
            }
            ProfileRow(f, check.amnezia && check.error == null, title, subtitle)
        }
        return when (Prefs.vpnSortMode) {
            1 -> rows.sortedByDescending { it.file.lastModified() }
            else -> rows.sortedBy { it.title.lowercase() }
        }
    }

    /** v1.7.1: rebuild ONLY when something actually changed — rebuilding the
     *  whole row list on every connect/import tick is what the user saw as the
     *  list «моргает» (flicker) while connecting. */
    private var lastListSig: String? = null

    private fun reloadProfiles() {
        val ctx = context ?: return
        io.launch {
            val rows = loadRows()
            val sig = rows.joinToString("|") {
                "${it.title}:${it.subtitle}:${selected.contains(it.file.absolutePath)}"
            } + "|wg=${WireGuardManager.state}:${WireGuardManager.activeProfile}"
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (sig == lastListSig) return@withContext
                lastListSig = sig
                profilesList.removeAllViews()
                if (rows.isEmpty()) {
                    profilesList.addView(PanelUi.emptyView(ctx, R.string.vpn_empty))
                    return@withContext
                }
                for (r in rows) profilesList.addView(profileRow(ctx, r))
            }
        }
    }

    private fun profileRow(ctx: android.content.Context, r: ProfileRow): View {
        val active = WireGuardManager.isActive() &&
            WireGuardManager.activeProfile.equals(r.file.nameWithoutExtension, ignoreCase = true)
        val inSelection = selected.contains(r.file.absolutePath)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), 0, dp(10), dp(8))
            }
            background = GradientDrawable().apply {
                setColor(SettingsUi.themedColor(ctx, R.attr.colorSurface))
                setStroke(
                    if (active) dp(2) else dp(1),
                    if (active) 0xFF0E9F6E.toInt() else SettingsUi.themedColor(ctx, R.attr.colorOutline)
                )
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            isLongClickable = true
            foreground = PanelUi.ripple(ctx)
        }

        // protocol badge: AWG (purple) / WG (rose)
        val badge = if (r.isAmnezia) "AWG" else "WG"
        val badgeColor = when {
            r.isAmnezia -> 0xFF6D28D9.toInt()
            else -> 0xFF881337.toInt()
        }
        row.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(badgeColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
            addView(TextView(ctx).apply {
                text = badge
                textSize = if (r.isAmnezia) 10f else 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
            })
        })

        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = r.title
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurface))
            })
            addView(TextView(ctx).apply {
                text = r.subtitle
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(SettingsUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(2), 0, 0)
            })
        })

        if (active) {
            row.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF0E9F6E.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(6) }
            })
        }

        if (inSelection) {
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(0xFF0E9F6E.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
        }

        fun toggleSelf() {
            if (selected.contains(r.file.absolutePath)) selected.remove(r.file.absolutePath)
            else selected.add(r.file.absolutePath)
            refreshSelectionBar()
            reloadProfiles()
        }

        row.setOnLongClickListener {
            if (selected.isEmpty()) { selected.add(r.file.absolutePath); refreshSelectionBar(); reloadProfiles(); true }
            else { toggleSelf(); true }
        }
        row.setOnClickListener {
            if (selected.isNotEmpty()) toggleSelf()
            else profileMenu(ctx, r)
        }
        return row
    }

    private fun refreshSelectionBar() {
        if (selected.isEmpty()) {
            selectionBar.visibility = View.GONE
        } else {
            selectionBar.visibility = View.VISIBLE
            selectionCount.text = getString(R.string.vpn_selected_fmt, selected.size)
        }
    }

    private fun selectAll() {
        io.launch {
            val rows = loadRows()
            withContext(Dispatchers.Main) {
                selected.clear()
                rows.forEach { selected.add(it.file.absolutePath) }
                refreshSelectionBar()
                reloadProfiles()
            }
        }
    }

    private fun exitSelection() {
        selected.clear()
        refreshSelectionBar()
        reloadProfiles()
    }

    private fun confirmDeleteSelected() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vpn_delete_selected)
            .setMessage(getString(R.string.vpn_delete_selected_msg, selected.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                io.launch {
                    for (p in selected.toList()) {
                        try { File(p).delete() } catch (_: Exception) {}
                    }
                    selected.clear()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.vpn_deleted, Toast.LENGTH_SHORT).show()
                        refreshSelectionBar()
                        reloadProfiles()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun profileMenu(ctx: android.content.Context, r: ProfileRow) {
        val active = WireGuardManager.isActive() &&
            WireGuardManager.activeProfile.equals(r.file.nameWithoutExtension, ignoreCase = true)
        val actions = mutableListOf(
            if (active) getString(R.string.vpn_disconnect) else getString(R.string.vpn_connect),
            getString(R.string.vpn_share),
            getString(R.string.delete))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(r.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (active) WireGuardManager.disconnect(ctx)
                         else ensureVpnPermissionThen(PendingConnect(r.file))
                    1 -> shareConfig(ctx, r.file)
                    2 -> confirmDeleteProfile(ctx, r)
                }
            }
            .show()
    }

    private fun confirmDeleteProfile(ctx: android.content.Context, r: ProfileRow) {
        MaterialAlertDialogBuilder(ctx)
            .setMessage(R.string.vpn_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (WireGuardManager.isActive() &&
                    WireGuardManager.activeProfile.equals(r.file.nameWithoutExtension, ignoreCase = true)) {
                    WireGuardManager.disconnect(ctx)
                }
                r.file.delete()
                reloadProfiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareConfig(ctx: android.content.Context, file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    // =====================================================================
    // Connect flow (consent-gated)
    // =====================================================================

    private fun ensureVpnPermissionThen(pc: PendingConnect) {
        val ctx = context ?: return
        try {
            val prep = android.net.VpnService.prepare(ctx)
            if (prep != null) {
                pendingConnect = pc
                Toast.makeText(ctx, R.string.vpn_need_consent, Toast.LENGTH_SHORT).show()
                consentLauncher.launch(prep)
            } else {
                actuallyConnect(pc)
            }
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.vpn_no_vpn_support, Toast.LENGTH_LONG).show()
        }
    }

    private fun actuallyConnect(pc: PendingConnect) {
        val ctx = context ?: return
        rememberProfile(pc)
        WireGuardManager.connectAsync(ctx, pc.file) {
            ui.post { refreshHero(); reloadProfiles() }
        }
    }

    // ---------------- the big power button ----------------

    private fun onPowerTap() {
        val ctx = context ?: return
        when (WireGuardManager.state) {
            WireGuardManager.STATE_CONNECTING -> {
                // cancel whatever is connecting
                WireGuardManager.disconnect(ctx)
                Toast.makeText(ctx, R.string.vpn_cancelled, Toast.LENGTH_SHORT).show()
            }
            WireGuardManager.STATE_CONNECTED -> {
                WireGuardManager.disconnect(ctx)
            }
            else -> {
                // off or failed → (re)connect the last profile, or pick one
                val last = lastProfile()
                if (last != null && last.file.isFile) {
                    ensureVpnPermissionThen(last)
                } else {
                    pickProfileToConnect()
                }
            }
        }
        ui.postDelayed({ refreshHero() }, 250)
    }

    /** v1.7.1: full connection journal — state transitions the poller saw. */
    private fun showConnectionLog() {
        val ctx = context ?: return
        val log = synchronized(connLog) { connLog.joinToString("\n") }
        val text = buildString {
            append(log.ifEmpty { getString(R.string.vpn_log_empty) })
            val err = WireGuardManager.lastError
            if (WireGuardManager.state == WireGuardManager.STATE_FAILED && !err.isNullOrEmpty()) {
                append("\n\n").append(err)
            }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vpn_log_title)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun logConn(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(connLog) {
            connLog.addLast("$ts  $msg")
            while (connLog.size > 60) connLog.removeFirst()
        }
    }

    private fun pickProfileToConnect() {
        val ctx = context ?: return
        io.launch {
            val rows = loadRows()
            withContext(Dispatchers.Main) {
                if (rows.isEmpty()) {
                    // v1.7.5 (user: «при тапе по логотипу выдаёт сообщение
                    // импортируйте ovpn — убери это»): no profiles + power tap
                    // now jumps STRAIGHT to the .conf import picker instead of a
                    // dead-end toast. No .ovpn wording anywhere anymore.
                    try { pickConf() } catch (_: Exception) {}
                } else {
                    val names = rows.map {
                        val proto = if (it.isAmnezia) "AmneziaWG" else "WireGuard"
                        "${it.title}  ($proto)"
                    }.toTypedArray()
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.vpn_pick_profile)
                        .setItems(names) { _, which -> ensureVpnPermissionThen(PendingConnect(rows[which].file)) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    // =====================================================================
    // Status polling — IN-PLACE hero updates only (no flicker)
    // =====================================================================

    private fun pollStatus() {
        ui.postDelayed({
            if (!isAdded) return@postDelayed
            WireGuardManager.pollStats()
            val st = WireGuardManager.state
            if (st != lastPolledWg) {
                val prof = WireGuardManager.activeProfile
                logConn("$lastPolledWg → $st" + (prof?.let { " · $it" } ?: ""))
                lastPolledWg = st
            }
            refreshHero()
            maybeAutoReconnect()
            pollStatus()
        }, 700)
    }

    // =====================================================================
    // v1.7.2: kill-switch (auto-reconnect) — v1.7.3: WireGuard-only
    // =====================================================================

    private var lastSeenWg = WireGuardManager.STATE_IDLE
    private var reconnectAttempts = 0
    private var lastReconnectAt = 0L
    private var killSwitchArmed = false
    private var splitRowSummaryRef: android.widget.TextView? = null

    /**
     * Kill-switch: a CONNECTED tunnel that just transitioned to FAILED (dead-tunnel
     * watchdog, handshake timeout, engine error) reconnects itself — up to 5
     * consecutive attempts, min 4 s apart. A successful connect resets the counter.
     */
    private fun maybeAutoReconnect() {
        val wg = WireGuardManager.state
        val wasConnected = lastSeenWg == WireGuardManager.STATE_CONNECTED
        val nowFailed = wg == WireGuardManager.STATE_FAILED
        val busy = WireGuardManager.isActive()
        if (wasConnected && (nowFailed || wg == WireGuardManager.STATE_DISCONNECTED)) killSwitchArmed = true
        if (uiPrefs.getBoolean("vpn_kill_switch", false) && killSwitchArmed && nowFailed && !busy &&
            reconnectAttempts < 5 && System.currentTimeMillis() - lastReconnectAt > 4000) {
            val last = lastProfile()
            if (last != null && last.file.isFile) {
                reconnectAttempts++
                lastReconnectAt = System.currentTimeMillis()
                killSwitchArmed = false
                android.util.Log.i("ZephyrVPN", "kill-switch: auto-reconnect #$reconnectAttempts")
                ensureVpnPermissionThen(last)
            }
        }
        if (wg == WireGuardManager.STATE_CONNECTED) {
            reconnectAttempts = 0
        }
        lastSeenWg = wg
    }

    // =====================================================================
    // v1.7.2: split tunneling — apps whose traffic BYPASSES the VPN
    // =====================================================================

    private fun excludedAppsPref(): MutableSet<String> =
        uiPrefs.getStringSet("vpn_excluded_apps", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun splitAppsSummary(): String {
        val n = excludedAppsPref().size
        return if (n == 0) getString(R.string.vpn_split_apps_sum)
        else getString(R.string.vpn_split_apps_sum) + " · $n"
    }

    private fun showSplitAppsDialog() {
        val ctx = context ?: return
        io.launch {
            val pm = ctx.packageManager
            val apps = pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    // this browser's own traffic MUST stay routable; skip ourselves
                    if (pkg == ctx.packageName) return@mapNotNull null
                    Triple(pkg, ri.loadLabel(pm)?.toString() ?: pkg, ri.activityInfo.applicationInfo)
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
            withContext(Dispatchers.Main) {
                if (apps.isEmpty()) {
                    Toast.makeText(ctx, R.string.vpn_split_empty, Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val selected = excludedAppsPref()
                val names = apps.map { "${it.second}  (${it.first})" }.toTypedArray()
                val checks = apps.map { selected.contains(it.first) }.toBooleanArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.vpn_split_title)
                    .setMultiChoiceItems(names, checks) { _, which, isChecked ->
                        if (isChecked) selected.add(apps[which].first)
                        else selected.remove(apps[which].first)
                    }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        uiPrefs.edit().putStringSet("vpn_excluded_apps", selected).apply()
                        splitRowSummaryRef?.text = splitAppsSummary()
                        Toast.makeText(ctx, R.string.saved, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun refreshHero() {
        val ctx = context ?: return
        if (!this::statusTitle.isInitialized) return

        val wgState = WireGuardManager.state

        val (title, sub, color, spin, pulse) = when (wgState) {
            WireGuardManager.STATE_CONNECTING ->
                Five(getString(R.string.vpn_state_connecting),
                    getString(R.string.vpn_hero_connecting),
                    intArrayOf(0xFFB45309.toInt(), 0xFFF59E0B.toInt()), true, false)
            WireGuardManager.STATE_CONNECTED -> {
                val display = WireGuardManager.activeProfile ?: ""
                val proto = if (WireGuardManager.usingAmnezia) "AmneziaWG" else "WireGuard"
                val rx = android.text.format.Formatter.formatFileSize(ctx, WireGuardManager.bytesIn)
                val tx = android.text.format.Formatter.formatFileSize(ctx, WireGuardManager.bytesOut)
                Five(getString(R.string.vpn_state_connected),
                    "$proto · $display\n↓$rx ↑$tx",
                    intArrayOf(0xFF047857.toInt(), 0xFF0E9F6E.toInt()), false, true)
            }
            WireGuardManager.STATE_FAILED -> {
                val err = WireGuardManager.lastError ?: ""
                Five(getString(R.string.vpn_state_failed),
                    "$err\n· ${getString(R.string.vpn_log_hint)}",
                    intArrayOf(0xFFB91C1C.toInt(), 0xFFDC2626.toInt()), false, false)
            }
            else -> Five(getString(R.string.vpn_state_idle),
                getString(R.string.vpn_hero_hint),
                intArrayOf(0xFF4B5563.toInt(), 0xFF374151.toInt()), false, false)
        }

        statusTitle.text = title
        statusSub.text = sub
        (powerBtn.background as? GradientDrawable)?.colors = color
        powerSpin.visibility = if (spin) View.VISIBLE else View.INVISIBLE
        powerIcon.visibility = if (spin) View.INVISIBLE else View.VISIBLE

        // connecting: spinner overlay; connected: soft pulsing halo; else: static
        if (pulse) {
            if (pulseAnim == null) {
                powerRing.visibility = View.VISIBLE
                pulseAnim = ObjectAnimator.ofFloat(powerRing, "alpha", 0.9f, 0.15f).apply {
                    duration = 1200
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = DecelerateInterpolator(1.2f)
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: Animator) {
                            powerRing.visibility = View.INVISIBLE
                            powerRing.alpha = 1f
                        }
                    })
                    start()
                }
            }
        } else {
            pulseAnim?.cancel(); pulseAnim = null
            powerRing.visibility = View.INVISIBLE
        }
    }

    private data class Five(val t: String, val s: String, val c: IntArray, val spin: Boolean, val pulse: Boolean)

    // =====================================================================
    // Imports / misc
    // =====================================================================

    /** v1.7.3: SAF picker for .conf/.awg (WireGuard / AmneziaWG) files. */
    private fun pickConf() {
        try {
            confImportLauncher.launch(arrayOf("*/*", "text/plain", "application/octet-stream"))
        } catch (_: Exception) {}
    }

    private fun importConf(uri: Uri) {
        val ctx = context ?: return
        io.launch {
            try {
                val name = queryName(ctx, uri)
                val file = WireGuardManager.importConfig(ctx, uri, name)
                // v1.7.1: tolerant validation — Amnezia/standard .conf all import
                // (unknown + Amnezia lines are skipped with a note, not rejected)
                val res = WireGuardManager.validate(ctx, file)
                if (res.error != null) {
                    file.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, getString(R.string.vpn_wg_invalid, res.error), Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val note = buildString {
                            append(getString(R.string.vpn_imported, file.nameWithoutExtension))
                            if (res.amnezia) append(" · ").append(getString(R.string.vpn_wg_amnezia_note))
                            else if (res.skipped > 0) append(" · ").append(getString(R.string.vpn_wg_skipped_fmt, res.skipped))
                        }
                        Toast.makeText(ctx, note, Toast.LENGTH_LONG).show()
                        reloadProfiles()
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, getString(R.string.vpn_wg_invalid, t.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun maybeOrbot(ctx: android.content.Context) {
        if (!ProxyHelper.orbotInstalled(ctx)) {
            MaterialAlertDialogBuilder(ctx)
                .setMessage(R.string.proxy_orbot_missing)
                .setPositiveButton(R.string.ok) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=${ProxyHelper.ORBOT_PKG}")))
                    } catch (_: Exception) {}
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            Toast.makeText(ctx, R.string.proxy_orbot_note, Toast.LENGTH_LONG).show()
        }
    }

    private fun hostPortDialog(ctx: android.content.Context) {
        val host = PanelUi.dialogField(ctx, getString(R.string.proxy_host), Prefs.proxyHost)
        val port = PanelUi.dialogField(ctx, getString(R.string.proxy_port), Prefs.proxyPort.toString())
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(PanelUi.fieldWrap(host))
            addView(PanelUi.fieldWrap(port))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.proxy_http)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val h = host.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull() ?: 8080
                Prefs.setProxy(1, h, p)
                ProxyHelper.applyFromPrefs(ctx)
                Toast.makeText(ctx, R.string.proxy_applied, Toast.LENGTH_SHORT).show()
                build(ctx)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun queryName(ctx: android.content.Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment ?: "profile"
        } ?: "profile"
    } catch (_: Exception) { "profile" }

    override fun onDestroyView() {
        pulseAnim?.cancel()
        super.onDestroyView()
    }

    private fun dp(v: Int): Int = (v * rc + 0.5f).toInt()
}
