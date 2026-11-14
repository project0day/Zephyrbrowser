package com.zephyr.browser.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.text.TextUtils.TruncateAt
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebStorage
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.FaviconLoader
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.SiteDataEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.7.9 «Данные сайтов» (user: «Кэш-менеджер — сколько занимает каждый сайт,
 * очистка выборочно»):
 *  - teal gradient HERO at the top: total per-site storage + total requests
 *    (updated in place on every rebuild);
 *  - GLOBAL BROWSER CACHE card: live on-disk size of cacheDir + codeCacheDir
 *    (HTTP cache, app_webview…), one tap wipes their contents (files only,
 *    directory skeleton kept — safe, everything is recreated on demand);
 *  - PER-SITE list sorted by storage: favicon tile + domain + bytes/cookies +
 *    request counter; tap or 🗑 → confirm → WebStorage.deleteOrigin (localStorage/
 *    IndexedDB), cookie expiry for that origin, site_data row deleted;
 *  - sizes are ENRICHED from WebStorage.getOrigins (real localStorage/IndexedDB
 *    usage per origin) + live cookie counts on every screen open.
 */
class CachePrefsFragment : Fragment() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var items: List<SiteDataEntity> = emptyList()
    private lateinit var col: LinearLayout
    private lateinit var sections: LinearLayout

    // hero (built ONCE in onCreateView; subtitle totals updated in place)
    private lateinit var heroCard: LinearLayout
    private lateinit var heroSub: TextView

    // global-cache row (size value updated in place)
    private var globalSize: TextView? = null

    // per-site section container (rebuilt on every rebuild())
    private lateinit var siteCol: LinearLayout

    /** Rows built before the first frame; the rest follow immediately after. */
    private val FIRST_CHUNK = 25

    /**
     * v1.7.69: how many sites the list asks for at all.
     *
     * The screen is a "which sites are heaviest, clear them" tool — nobody
     * scrolls to site nine hundred. The totals still describe everything,
     * because SQLite sums the whole table in one statement.
     */
    private val TOP_LIMIT = 60

    private var totalSites = 0
    private var totalStorage = 0L
    private var totalRequests = 0L

    private val rc get() = requireContext().resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * rc + 0.5f).toInt()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val act = activity as? SettingsActivity ?: return View(context)
        val ctx = requireContext()
        val root = act.buildScreen(R.string.cache_title)
        col = SettingsUi.screen(ctx)
        root.addView(SettingsUi.scrollOf(col), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        buildHero(ctx)             // teal gradient + totals
        buildGlobalRow(ctx)        // browser-wide HTTP cache card
        siteCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sections = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(siteCol)
        }
        col.addView(sections)

        // v1.7.69 (user: «всё ещё открывается трудно»).
        //
        // v1.7.58 read the table synchronously here so the entrance animation
        // would carry a finished screen. That assumed a handful of domains —
        // but site_data gains a row for every domain ever requested, so on a
        // real phone it is thousands, and a blocking read of all of them on the
        // main thread is precisely the stall it was meant to remove.
        //
        // The screen never shows more than the heaviest few dozen, so that is
        // now all it asks the database for, and it asks off the main thread.
        refresh()
        return root
    }

    // =====================================================================
    // HERO: teal gradient card — title + total storage · total requests
    // =====================================================================

    private fun buildHero(ctx: Context) {
        heroCard = SettingsUi.gradientCard(ctx, getString(R.string.cache_title),
            getString(R.string.cache_sub), TEAL_GRAD) { refresh() }
        // subtitle is grabbed BEFORE restructuring (gradientCard: 0=title, 1=subtitle)
        heroSub = heroCard.getChildAt(1) as TextView
        heroSub.textSize = 13f
        // premium touch: icon + title on one row (gradientCard ships a bare title)
        val title = heroCard.getChildAt(0) as? TextView
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_storage)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        })
        if (title != null) {
            heroCard.removeView(title)
            title.textSize = 16.5f
            titleRow.addView(title, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        heroCard.addView(titleRow, 0)
    }

    private fun updateHero() {
        if (!this::heroSub.isInitialized) return
        // totals come from the database, not from the shown rows
        val bytes = totalStorage
        val reqs = totalRequests
        heroSub.text = if (bytes > 0 || reqs > 0)
            Formatter.formatFileSize(context, bytes) + "  ·  " + getString(R.string.cache_requests_fmt, reqs)
        else getString(R.string.cache_sub)
    }

    // =====================================================================
    // GLOBAL BROWSER CACHE: on-disk size of cacheDir + codeCacheDir, 1-tap wipe
    // =====================================================================

    private fun buildGlobalRow(ctx: Context) {
        col.addView(SettingsUi.sectionHeader(ctx, getString(R.string.cache_global)))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(12), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), 0, dp(12), dp(4))
            }
            background = SettingsUi.rounded(ctx, 16)
            clipToOutline = true
            isClickable = true
            isFocusable = true
            foreground = SettingsUi.ripple(ctx)
            setOnClickListener { clearGlobalCache() }
        }
        // accent icon chip (mirror of the private SettingsUi.baseRow chip)
        val chip = if (Prefs.customAccent != 0)
            (SettingsUi.accent(ctx) and 0x00FFFFFF) or 0x20000000
        else SettingsUi.color(ctx, R.attr.colorPrimaryContainer)
        card.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(chip) }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_delete)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                imageTintList = ColorStateList.valueOf(SettingsUi.accent(ctx))
            })
        })
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = getString(R.string.cache_global_clear)
                textSize = 15f
                maxLines = 2
                setTextColor(SettingsUi.color(ctx, R.attr.colorOnSurface))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.cache_global_sub)
                textSize = 12.5f
                maxLines = 3
                setLineSpacing(dp(1).toFloat(), 1f)
                setPadding(0, dp(4), 0, 0)
                setTextColor(SettingsUi.color(ctx, R.attr.colorOnSurfaceVariant))
            })
        })
        globalSize = TextView(ctx).apply {
            text = "…"
            textSize = 13f
            maxLines = 1
            gravity = Gravity.END
            setTextColor(SettingsUi.color(ctx, R.attr.colorOnSurfaceVariant))
        }
        card.addView(globalSize)
        card.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) }
            imageTintList = ColorStateList.valueOf(SettingsUi.color(ctx, R.attr.colorOnSurfaceVariant))
        })
        col.addView(card)
    }

    /** cacheDir (HTTP cache + app_webview live here) + codeCacheDir. */
    private fun cacheDirs(ctx: Context): List<File> =
        try {
            listOfNotNull(ctx.cacheDir, ctx.codeCacheDir).distinct()
        } catch (_: Exception) { emptyList() }

    private fun dirSize(f: File): Long = try {
        if (f.isFile) f.length()
        else f.listFiles()?.sumOf { dirSize(it) } ?: 0L
    } catch (_: Exception) { 0L }

    /** Recursively delete FILES, keep the directory skeleton (all recreated on demand). */
    private fun deleteFilesKeepDirs(f: File) {
        try {
            if (f.isFile) { f.delete(); return }
            f.listFiles()?.forEach { deleteFilesKeepDirs(it) }
        } catch (_: Exception) {}
    }

    private fun clearGlobalCache() {
        val ctx = context ?: return
        io.launch {
            for (d in cacheDirs(ctx)) deleteFilesKeepDirs(d)
            val freed = cacheDirs(ctx).sumOf { dirSize(it) }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                globalSize?.text = Formatter.formatFileSize(ctx, freed)
                Toast.makeText(ctx, R.string.cache_global_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =====================================================================
    // PER-SITE rows
    // =====================================================================

    private fun siteRow(ctx: Context, s: SiteDataEntity): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), 0, dp(12), dp(6))
            }
            background = SettingsUi.rounded(ctx, 16)
            clipToOutline = true
            isClickable = true
            isFocusable = true
            foreground = SettingsUi.ripple(ctx)
            setOnClickListener { confirmClear(ctx, s.domain) }
        }
        // 34dp rounded favicon tile.
        //
        // v1.7.57 FIX (user: «окно Данные сайтов открывается очень плохо — на
        // половину, застрянет, потом появится»). FaviconLoader.load() stats a
        // file and runs BitmapFactory.decodeFile — per row, on the MAIN thread,
        // while the screen was mid-entrance. Thirty sites meant thirty disk
        // reads and thirty decodes before the first frame could land.
        //
        // The letter avatar is pure arithmetic, so it is drawn at once and the
        // real icon replaces it when the disk answers.
        row.addView(ImageView(ctx).apply {
            setImageBitmap(FaviconLoader.letterAvatar("https://" + s.domain, s.domain))
            val iv = this
            io.launch {
                val real = try { FaviconLoader.load(ctx, "https://" + s.domain, s.domain) }
                catch (_: Exception) { null }
                if (real != null) withContext(Dispatchers.Main) {
                    if (isAdded && iv.isAttachedToWindow) iv.setImageBitmap(real)
                }
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(SettingsUi.color(ctx, R.attr.colorSurfaceVariant))
            }
            clipToOutline = true
        })
        // domain + "size · cookies" + requests
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = s.domain
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TruncateAt.END
                setTextColor(SettingsUi.color(ctx, R.attr.colorOnSurface))
            })
            addView(TextView(ctx).apply {
                text = Formatter.formatFileSize(ctx, s.storageBytes) + " · " +
                    getString(R.string.cache_cookies_fmt, s.cookies)
                textSize = 12.5f
                maxLines = 1
                setPadding(0, dp(3), 0, 0)
                setTextColor(SettingsUi.color(ctx, R.attr.colorOnSurfaceVariant))
            })
            addView(TextView(ctx).apply {
                text = getString(R.string.cache_requests_fmt, s.requests)
                textSize = 12f
                maxLines = 1
                setPadding(0, dp(1), 0, 0)
                setTextColor(SettingsUi.color(ctx, R.attr.colorOnSurfaceVariant))
            })
        })
        // selective cleanup button (same confirm as a row tap)
        row.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_delete)
            background = SettingsUi.ripple(ctx)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = ColorStateList.valueOf(SettingsUi.color(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(R.string.cache_clear_site)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { confirmClear(ctx, s.domain) }
        })
        return row
    }

    private fun confirmClear(ctx: Context, domain: String) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(domain)
            .setMessage(getString(R.string.cache_clear_confirm, domain))
            .setPositiveButton(R.string.cache_clear_site) { _, _ -> cleanupSite(domain) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Selective cleanup: site_data row → WebStorage origin (https+http) → cookies. */
    private fun cleanupSite(domain: String) {
        val ctx = context ?: return
        io.launch {
            try { AppDb.get(ctx.applicationContext).siteDataDao().deleteDomain(domain) } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                try { WebStorage.getInstance().deleteOrigin("https://$domain") } catch (_: Exception) {}
                try { WebStorage.getInstance().deleteOrigin("http://$domain") } catch (_: Exception) {}
                clearCookies(domain)
                Toast.makeText(ctx, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    /** Expire every cookie of the origin (no per-domain remove API — Max-Age=0 trick). */
    private fun clearCookies(domain: String) {
        try {
            val cm = CookieManager.getInstance()
            for (url in listOf("https://$domain", "http://$domain")) {
                try {
                    val raw = cm.getCookie(url) ?: continue
                    for (part in raw.split(';')) {
                        val name = part.substringBefore('=', "").trim()
                        if (name.isEmpty()) continue
                        cm.setCookie(url,
                            "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    }
                } catch (_: Exception) {}
            }
            try { cm.flush() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    // =====================================================================
    // Data / refresh / storage-size enrichment
    // =====================================================================

    /**
     * v1.7.25c LAG FIX (user: «при тапе по „Данные сайтов" какая-то поломанная
     * пустая страница пытается открыться, и только потом открывается окно»):
     * two things stuttered the screen's entrance animation.
     *  1. [enrichFromWebStorage] touches `WebStorage.getInstance()`, and that
     *     first touch inside SettingsActivity boots the whole WebView provider
     *     ON THE MAIN THREAD — hundreds of milliseconds right while the
     *     fragment was animating in. It now runs AFTER the transition settles.
     *  2. the first frame rendered the «no data» empty state before the DB
     *     answered, so the screen visibly flipped from empty to full. A
     *     [loaded] flag keeps the list blank until the real rows are in.
     */
    private var loaded = false

    private fun refresh() {
        val ctx = context ?: return
        // v1.7.57: the list no longer waits on the cache measurement. Walking
        // cacheDir recursively can take a while on a full cache, and it used to
        // sit between "screen opened" and "rows drawn" for no reason — the two
        // have nothing to do with each other.
        io.launch {
            val dao = try { AppDb.get(ctx.applicationContext).siteDataDao() } catch (_: Exception) { null }
            // the heaviest few dozen, plus the totals — computed by SQLite over
            // the whole table, which is what it is good at, instead of by
            // summing thousands of objects in Kotlin
            val rows = try { dao?.top(TOP_LIMIT) ?: emptyList() } catch (_: Exception) { emptyList() }
            val total = try { dao?.count() ?: 0 } catch (_: Exception) { 0 }
            val bytes = try { dao?.totalBytes() ?: 0L } catch (_: Exception) { 0L }
            val reqs = try { dao?.totalRequests() ?: 0L } catch (_: Exception) { 0L }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                items = rows
                totalSites = total
                totalStorage = bytes
                totalRequests = reqs
                loaded = true
                rebuild()
                // size enrichment waits for the entrance animation to finish
                view?.postDelayed({ if (isAdded) enrichFromWebStorage() }, 420)
            }
        }
        io.launch {
            val cacheBytes = try { cacheDirs(ctx).sumOf { dirSize(it) } } catch (_: Exception) { 0L }
            withContext(Dispatchers.Main) {
                if (isAdded) globalSize?.text = Formatter.formatFileSize(ctx, cacheBytes)
            }
        }
    }

    private fun rebuild() {
        val ctx = context ?: return
        updateHero()
        siteCol.removeAllViews()
        if (!loaded) return
        if (items.isEmpty()) {
            siteCol.addView(com.zephyr.browser.panels.PanelUi.emptyView(ctx, R.string.cache_empty))
            return
        }
        siteCol.addView(SettingsUi.sectionHeader(ctx, getString(R.string.cache_storage)))
        // say plainly that this is the heaviest slice, not everything
        if (totalSites > items.size) siteCol.addView(
            SettingsUi.note(ctx, getString(R.string.cache_top_fmt, items.size, totalSites)))
        val sorted = items.sortedByDescending { it.storageBytes }
        // a full screen's worth now, the tail once the frame is on screen —
        // a hundred domains must not delay the first frame
        for (s in sorted.take(FIRST_CHUNK)) siteCol.addView(siteRow(ctx, s))
        if (sorted.size > FIRST_CHUNK) {
            siteCol.post {
                if (!isAdded) return@post
                for (s in sorted.drop(FIRST_CHUNK)) siteCol.addView(siteRow(ctx, s))
            }
        }
    }

    /** Merge WebStorage origin usage (real bytes) + live cookie counts into site_data. */
    @Suppress("DEPRECATION")
    private fun enrichFromWebStorage() {
        val ctx = context ?: return
        try {
            WebStorage.getInstance().getOrigins(object : ValueCallback<Map<*, *>> {
                override fun onReceiveValue(value: Map<*, *>) {
                    if (!isAdded) return
                    val usage = HashMap<String, Long>()
                    for ((k, v) in value) {
                        try {
                            val host = hostOf(k as? String ?: continue) ?: continue
                            val o = v as? WebStorage.Origin ?: continue
                            usage[host] = (usage[host] ?: 0L) + o.usage
                        } catch (_: Exception) {}
                    }
                    io.launch {
                        try {
                            val dao = AppDb.get(ctx.applicationContext).siteDataDao()
                            for ((host, bytes) in usage) {
                                val row = dao.byDomain(host) ?: SiteDataEntity(domain = host)
                                dao.upsert(row.copy(storageBytes = bytes, cookies = cookieCount(host)))
                            }
                            // v1.7.69: cookie counts are refreshed only for the
                            // rows this screen actually shows. The old loop
                            // walked the WHOLE table and issued one cookie
                            // lookup and one write per domain — thousands of
                            // round trips to keep a number nobody was looking
                            // at up to date.
                            for (row in dao.top(TOP_LIMIT)) {
                                if (row.domain !in usage) {
                                    val c = cookieCount(row.domain)
                                    if (c != row.cookies) dao.upsert(row.copy(cookies = c))
                                }
                            }
                            val fresh = dao.top(TOP_LIMIT)
                            val bytesAll = dao.totalBytes() ?: 0L
                            val reqsAll = dao.totalRequests() ?: 0L
                            withContext(Dispatchers.Main) {
                                if (!isAdded) return@withContext
                                items = fresh
                                totalStorage = bytesAll
                                totalRequests = reqsAll
                                rebuild()
                            }
                        } catch (_: Exception) {}
                    }
                }
            })
        } catch (_: Exception) {}
    }

    /** "https://www.example.com/" → "example.com" (scheme/www/port stripped, needs a dot). */
    private fun hostOf(origin: String): String? {
        return try {
            val o = origin.trim()
            val withScheme = if (o.startsWith("http")) o else "https://$o"
            val host = Uri.parse(withScheme).host?.lowercase()?.removePrefix("www.") ?: return null
            if (host.contains('.')) host else null
        } catch (_: Exception) { null }
    }

    private fun cookieCount(host: String): Int {
        return try {
            val raw = CookieManager.getInstance().getCookie("https://$host") ?: return 0
            raw.split(';').count { it.trim().isNotEmpty() }
        } catch (_: Exception) { 0 }
    }

    companion object {
        /** teal premium hero gradient */
        private val TEAL_GRAD = intArrayOf(0xFF0E7490.toInt(), 0xFF14B8A6.toInt())
    }
}
