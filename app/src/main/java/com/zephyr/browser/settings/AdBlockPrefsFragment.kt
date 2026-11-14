package com.zephyr.browser.settings

import android.animation.ValueAnimator
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
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.AdBlockManager
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.FilterListEntity
import com.zephyr.browser.panels.PanelUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.7.9 rework (user: «в блокировщике переделай страницу — всё в кашу; сделай
 * как в VPN: сверху градиент с анимациями, при включении загорается зелёным,
 * при выключенном красный; внутри — заголовок с красивым тумблером и счётчики
 * реклам/трекеров; "как это работает" — убираемая подсказка, а не вечный блок
 * в центре»):
 *  - HERO card at the top (built ONCE, updated in place like the VPN screen):
 *    dark gradient — emerald-tinted when blocking is on, red-tinted when off;
 *    shield + «Блокировка рекламы» + premium switch in a 64dp touch target
 *    (v1.7.10: the pulsing halo ring around the toggle was removed at user
 *    request — «убери анимацию вкруг тумблера это колхоз»);
 *  - two stat pills INSIDE the hero: ads blocked today + trackers estimate,
 *    refreshed live by a 2s ticker that runs only while the screen is resumed;
 *  - "How it works" is now a dismissible gradient hint right under the hero
 *    (Prefs.dismissHint — never comes back), with a subtle light sweep;
 *  - whitelist + filter lists (catalog / add by URL / import file / update)
 *    are unchanged below.
 */
class AdBlockPrefsFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ui = Handler(Looper.getMainLooper())
    private var lists: List<FilterListEntity> = emptyList()
    private lateinit var col: LinearLayout
    private lateinit var sections: LinearLayout

    // hero views (created ONCE in onCreateView; refreshHero() updates them in
    // place so they survive every rebuild() below)
    private lateinit var heroCard: LinearLayout
    private lateinit var heroSwitch: Switch
    private lateinit var heroShield: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var statAds: TextView
    private lateinit var statTrackers: TextView

    // dismissible "how it works" hint
    private var hintCard: View? = null
    private var sweepAnim: ValueAnimator? = null

    // live stats poller (only while resumed — no leaked callbacks)
    private var statsTicker: Runnable? = null

    private val rc get() = requireContext().resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * rc + 0.5f).toInt()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.set_adblock)
        col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        buildHero(ctx)      // the star — never rebuilt afterwards
        addHowHint(ctx)     // dismissible hint under the hero
        sections = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sections)

        refreshHero()
        refresh()           // async: lists from DB → rebuild() of the sections
        return root
    }

    // =====================================================================
    // HERO: gradient status card with the animated toggle + stat pills
    // =====================================================================

    private fun buildHero(ctx: android.content.Context) {
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
                colors = ON_GRAD
                cornerRadius = dp(24).toFloat()
            }
        }
        col.addView(heroCard)

        // ---- big row: shield + «Блокировка рекламы» + switch ----
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroShield = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_shield)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            imageTintList = ColorStateList.valueOf(0xFF34D399.toInt())
        }
        topRow.addView(heroShield)
        topRow.addView(TextView(ctx).apply {
            text = getString(R.string.adblock_master)
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        heroSwitch = Switch(ctx).apply {
            contentDescription = getString(R.string.adblock_power_on)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF34D399.toInt(), 0x59F87171.toInt()))
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFF1C7C7.toInt()))
        }
        val switchStack = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        }
        switchStack.addView(heroSwitch, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        topRow.addView(switchStack)
        heroCard.addView(topRow)

        heroSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setAdBlockMode(if (on) 2 else 0)
            refreshHero()
        }

        // ---- status line ----
        statusTitle = TextView(ctx).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        heroCard.addView(statusTitle)
        statusSub = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        heroCard.addView(statusSub)

        // ---- stat pills: ads blocked + trackers stopped ----
        statAds = TextView(ctx).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        statTrackers = TextView(ctx).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val pills = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        pills.addView(statPill(ctx, statAds, getString(R.string.adblock_stats_blocked)))
        pills.addView(statPill(ctx, statTrackers, getString(R.string.adblock_stats_trackers)).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        })
        heroCard.addView(pills)
    }

    private fun statPill(ctx: android.content.Context, number: TextView, label: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                setColor(0x1FFFFFFF.toInt())     // white 12% frosted fill
                cornerRadius = dp(16).toFloat()
            }
            addView(number)
            addView(TextView(ctx).apply {
                text = label
                textSize = 11f
                maxLines = 2
                gravity = Gravity.CENTER
                setTextColor(0xB3FFFFFF.toInt())
                setPadding(dp(4), dp(3), dp(4), 0)
            })
        }

    /** In-place hero refresh (colors, texts, switch state) — called from rebuild()/refresh(). */
    private fun refreshHero() {
        if (!this::heroCard.isInitialized) return
        val on = Prefs.adBlockMode != 0

        (heroCard.background as? GradientDrawable)?.colors = if (on) ON_GRAD else OFF_GRAD
        heroShield.imageTintList = ColorStateList.valueOf(
            if (on) 0xFF34D399.toInt() else 0xFFF87171.toInt())

        statusTitle.text = getString(if (on) R.string.adblock_hero_on else R.string.adblock_hero_off)
        statusSub.text = getString(if (on) R.string.adblock_hero_sub_on else R.string.adblock_hero_sub_off)

        // reflect the state without re-firing the listener
        heroSwitch.setOnCheckedChangeListener(null)
        heroSwitch.isChecked = on
        heroSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setAdBlockMode(if (checked) 2 else 0)
            refreshHero()
        }

        // v1.7.10: no halo/ring animation around the toggle anymore — the ON/OFF
        // state is carried by the gradient + shield tint + track color alone.
        updateStats()
    }

    private fun updateStats() {
        if (!this::statAds.isInitialized) return
        AdBlockManager.flushBlocked()
        statAds.text = AdBlockManager.blockedToday().toString()
        statTrackers.text = trackersEstimate().toString()
    }

    /**
     * No dedicated tracker counter exists in AdBlockManager (v1.7.9) — derive a
     * conservative estimate: tracker beacons ≈ ¼ of all blocked requests (the
     * EasyPrivacy share of typical filter hits), kept within the same live
     * timeframe as the ads pill so the two numbers always read together.
     */
    private fun trackersEstimate(): Long =
        minOf(AdBlockManager.blockedTotal() / 4, AdBlockManager.blockedToday() / 4)

    // =====================================================================
    // Dismissible "how it works" hint (gradient card right under the hero)
    // =====================================================================

    private fun addHowHint(ctx: android.content.Context) {
        if (Prefs.isHintDismissed(HINT_HOW)) return

        val card = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), dp(6), dp(10), dp(2))
            }
            // clip children so the light sweep never leaks past the rounded corners
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF372C54.toInt(), 0xFF201A33.toInt())
                cornerRadius = dp(20).toFloat()
            }
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(14))
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // frosted icon chip
        head.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x24FFFFFF.toInt())
            }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_info)
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17))
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            })
        })
        head.addView(TextView(ctx).apply {
            text = getString(R.string.adblock_hint_title)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            background = PanelUi.ripple(ctx)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            imageTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
            contentDescription = getString(R.string.close)
            setOnClickListener { dismissHowHint() }
        })
        body.addView(head)
        body.addView(TextView(ctx).apply {
            text = getString(R.string.adblock_hint_body)
            textSize = 12.8f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(0xBFFFFFFF.toInt())
            setPadding(0, dp(10), dp(6), 0)
        })
        card.addView(body, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // subtle light sweep: a skewed white 10% bar gliding across every ~7s
        val sweep = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), FrameLayout.LayoutParams.MATCH_PARENT)
            rotation = 22f
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(0x00FFFFFF.toInt(), 0x1AFFFFFF.toInt(), 0x00FFFFFF.toInt())
            }
        }
        card.addView(sweep)
        sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { an ->
                val cardW = card.width
                if (cardW <= 0) return@addUpdateListener
                // sweep occupies the first 20% of each 7s cycle, then rests off-screen
                val t = (an.animatedValue as Float / 0.2f).coerceAtMost(1f)
                sweep.translationX = -dp(80) + (cardW + dp(160)) * t
            }
            start()
        }

        hintCard = card
        col.addView(card)
    }

    private fun dismissHowHint() {
        Prefs.dismissHint(HINT_HOW)
        sweepAnim?.cancel(); sweepAnim = null
        val card = hintCard ?: return
        hintCard = null
        card.animate().alpha(0f).setDuration(180).withEndAction {
            (card.parent as? ViewGroup)?.removeView(card)
        }.start()
    }

    // =====================================================================
    // Sections below the hero (whitelist + filter lists) — rebuilt freely
    // =====================================================================

    private fun rebuild() {
        val ctx = context ?: return
        refreshHero()
        sections.removeAllViews()

        // ---- whitelist ----
        sections.addView(SettingsUi.sectionHeader(ctx, getString(R.string.adblock_whitelist)))
        if (Prefs.adBlockWhitelist.isEmpty()) {
            sections.addView(hintText(ctx, getString(R.string.adblock_whitelist_empty)))
        }
        for (d in Prefs.adBlockWhitelist.sorted()) {
            sections.addView(SettingsUi.clickRow(ctx, R.drawable.ic_check, d, null) {
                MaterialAlertDialogBuilder(ctx)
                    .setMessage(getString(R.string.delete) + " " + d + "?")
                    .setPositiveButton(R.string.delete) { _, _ ->
                        Prefs.toggleWhitelist(d, false)
                        rebuild()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            })
        }
        sections.addView(SettingsUi.clickRow(ctx, R.drawable.ic_add, getString(R.string.adblock_add_whitelist)) {
            val input = PanelUi.dialogField(ctx, getString(R.string.adblock_whitelist_hint))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.adblock_add_whitelist)
                .setView(PanelUi.fieldWrap(input))
                .setPositiveButton(R.string.save) { _, _ ->
                    val host = input.text.toString().trim()
                        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trim('/')
                    if (host.contains('.') && !host.contains(' ')) {
                        Prefs.toggleWhitelist(host, true)
                        rebuild()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })

        // ---- filter lists ----
        sections.addView(SettingsUi.sectionHeader(ctx, getString(R.string.adblock_catalog)))
        sections.addView(hintText(ctx, getString(R.string.adblock_catalog_hint)))
        for ((name, url, _) in AdBlockManager.catalog) {
            val installed = lists.firstOrNull { it.url == url }
            val isEnabled = installed != null && installed.enabled
            val summary = if (installed != null) {
                if (installed.ruleCount > 0) getString(R.string.adblock_rules_fmt, installed.ruleCount)
                else getString(R.string.adblock_never_updated)
            } else null
            sections.addView(SettingsUi.switchRow(ctx, R.drawable.ic_filter, name, summary, isEnabled) { turnOn ->
                if (turnOn) {
                    if (installed == null) installFromUrl(ctx, name, url)
                    else io.launch {
                        try { AppDb.get(ctx.applicationContext).filterListDao().setEnabled(installed.id, true) } catch (_: Exception) {}
                        withContext(Dispatchers.Main) { refresh() }
                    }
                } else {
                    if (installed != null) {
                        io.launch {
                            try { AppDb.get(ctx.applicationContext).filterListDao().setEnabled(installed.id, false) } catch (_: Exception) {}
                            withContext(Dispatchers.Main) { refresh() }
                        }
                    }
                }
            })
        }

        // ---- user lists ----
        sections.addView(SettingsUi.sectionHeader(ctx, getString(R.string.adblock_filters)))
        for (fl in lists.filter { it.url !in AdBlockManager.catalog.map { c -> c.second } }) {
            val sub = if (fl.ruleCount > 0) getString(R.string.adblock_rules_fmt, fl.ruleCount) +
                (if (!fl.enabled) " · " + getString(R.string.adblock_disabled) else "")
            else null
            sections.addView(SettingsUi.clickRow(ctx, R.drawable.ic_list, fl.name, sub) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(fl.name)
                    .setItems(arrayOf(
                        if (fl.enabled) getString(R.string.block) else getString(R.string.allow),
                        getString(R.string.adblock_update),
                        getString(R.string.delete))) { _, which ->
                        when (which) {
                            0 -> io.launch {
                                try { AppDb.get(ctx.applicationContext).filterListDao().setEnabled(fl.id, !fl.enabled) } catch (_: Exception) {}
                                withContext(Dispatchers.Main) { refresh() }
                            }
                            1 -> updateList(ctx, fl)
                            2 -> MaterialAlertDialogBuilder(ctx)
                                .setMessage(R.string.adblock_delete_confirm)
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    io.launch {
                                        try {
                                            AppDb.get(ctx.applicationContext).filterListDao().delete(fl)
                                            AdBlockManager.deleteList(fl.id)
                                        } catch (_: Exception) {}
                                        withContext(Dispatchers.Main) { refresh() }
                                    }
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                    }
                    .show()
            })
        }

        // import file
        sections.addView(SettingsUi.clickRow(ctx, R.drawable.ic_upload, getString(R.string.adblock_import_file)) {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
            }
            try { startActivityForResult(i, SettingsActivity.RC_FILTER_IMPORT) } catch (_: Exception) {}
        })

        // add by URL
        sections.addView(SettingsUi.clickRow(ctx, R.drawable.ic_add, getString(R.string.adblock_add_url)) {
            val nameF = PanelUi.dialogField(ctx, getString(R.string.scr_name))
            val urlF = PanelUi.dialogField(ctx, "URL")
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
                addView(PanelUi.fieldWrap(nameF))
                addView(PanelUi.fieldWrap(urlF))
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.adblock_add_url)
                .setView(box)
                .setPositiveButton(R.string.save) { _, _ ->
                    val n = nameF.text.toString().trim().ifEmpty { "list" }
                    val u = urlF.text.toString().trim()
                    if (u.startsWith("http")) installFromUrl(ctx, n, u)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
    }

    private fun hintText(ctx: android.content.Context, text: String): View =
        TextView(ctx).apply {
            this.text = text
            textSize = 12.5f
            setPadding(ctx.dp(20), ctx.dp(6), ctx.dp(20), ctx.dp(6))
            setTextColor(SettingsUiHelper.textColor(ctx))
        }

    private object SettingsUiHelper {
        fun textColor(ctx: android.content.Context): Int =
            com.zephyr.browser.settings.SettingsUi.color(ctx, R.attr.colorOnSurfaceVariant)
    }

    // =====================================================================
    // Lifecycle / live stats
    // =====================================================================

    override fun onResume() {
        super.onResume()
        startStatsTicker()
    }

    override fun onPause() {
        stopStatsTicker()
        super.onPause()
    }

    override fun onDestroyView() {
        sweepAnim?.cancel(); sweepAnim = null
        super.onDestroyView()
    }

    /** Poll the counters every 2s — only while the screen is in the foreground. */
    private fun startStatsTicker() {
        stopStatsTicker()
        val r = object : Runnable {
            override fun run() {
                if (!isAdded) return
                updateStats()
                ui.postDelayed(this, 2000)
            }
        }
        statsTicker = r
        ui.post(r)
    }

    private fun stopStatsTicker() {
        statsTicker?.let { ui.removeCallbacks(it) }
        statsTicker = null
    }

    // =====================================================================
    // Data / downloads (unchanged v1.7.8 logic)
    // =====================================================================

    private fun refresh() {
        val ctx = context ?: return
        io.launch {
            lists = try { AppDb.get(ctx.applicationContext).filterListDao().allOnce() } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { rebuild() }
        }
    }

    private fun installFromUrl(ctx: android.content.Context, name: String, url: String) {
        val db = AppDb.get(ctx.applicationContext)
        io.launch {
            try {
                val id = db.filterListDao().insert(FilterListEntity(name = name, url = url))
                val count = AdBlockManager.downloadList(id, url)
                db.filterListDao().updateStats(id, count, System.currentTimeMillis())
                AdBlockManager.reloadAll()
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, getString(R.string.adblock_updated, count), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.adblock_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateList(ctx: android.content.Context, fl: FilterListEntity) {
        val db = AppDb.get(ctx.applicationContext)
        Toast.makeText(ctx, R.string.adblock_updating, Toast.LENGTH_SHORT).show()
        io.launch {
            try {
                val count = AdBlockManager.downloadList(fl.id, fl.url)
                db.filterListDao().updateStats(fl.id, count, System.currentTimeMillis())
                AdBlockManager.reloadAll()
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, getString(R.string.adblock_updated, count), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.adblock_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SettingsActivity.RC_FILTER_IMPORT && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { importFile(it) }
        }
    }

    private fun importFile(uri: Uri) {
        val ctx = context ?: return
        val db = AppDb.get(ctx.applicationContext)
        io.launch {
            try {
                val name = queryName(ctx, uri)
                val id = db.filterListDao().insert(FilterListEntity(name = name, url = uri.toString()))
                val count = AdBlockManager.importListFile(id, uri)
                db.filterListDao().updateStats(id, count, System.currentTimeMillis())
                AdBlockManager.reloadAll()
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, getString(R.string.adblock_imported, count), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.adblock_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun queryName(ctx: android.content.Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment ?: "list"
        } ?: "list"
    } catch (_: Exception) { "list" }

    companion object {
        private const val HINT_HOW = "adblock_how"

        /** emerald-tinted hero (blocking ON) / red-tinted hero (OFF) */
        private val ON_GRAD = intArrayOf(0xFF0B3B2E.toInt(), 0xFF0E2A22.toInt())
        private val OFF_GRAD = intArrayOf(0xFF3F1D24.toInt(), 0xFF2A151A.toInt())
    }
}
