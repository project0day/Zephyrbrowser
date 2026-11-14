package com.zephyr.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.zephyr.browser.ui.SelWebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/** Tab model + LRU-evicting manager. WebView access ONLY from UI thread. */
class TabManager(private val context: Context) {

    companion object {
        const val MAX_TABS = 32
        // v1.4.2 FIX 5: more live tabs kept in memory (6 → 8) before LRU eviction;
        // evicted tabs are restorable from savedState instead of reloading.
        const val MAX_LIVE = 8
    }

    class Tab(
        val id: Long,
        var startUrl: String,
        var incognito: Boolean = false
    ) {
        var webView: WebView? = null
        @Volatile var pageUrlSnapshot: String = startUrl   // read by Chromium threads for adblock
        var title: String = ""
        var faviconUrl: String? = null
        var scrollY: Int = 0
        var desktop: Boolean = false
        var appearance: Int = -1   // -1 = global default
        var lastUsed: Long = System.currentTimeMillis()
        var thumbnail: Bitmap? = null   // page preview for the tab switcher
        // v1.7.6: tab group name in the switcher ("" = ungrouped); persisted with the session
        var group: String = ""
        // v1.7.7: PINNED tab — first section in the switcher, never LRU-evicted,
        // skipped by "close all" unless explicitly selected in the group-aware dialog
        var pinned: Boolean = false
        // v1.7.7: inline page-translation state (originals are kept page-side)
        var translated: Boolean = false
        var translationTarget: String = ""
        // v1.4.2 FIX 5: WebView session snapshot — saved on LRU eviction, restored on select,
        // so switching back NEVER reloads the page (back/forward history is preserved too).
        var savedState: Bundle? = null
        // True while the tab has never been shown (session restore / evicted backup) and
        // still needs its initial loadUrl when its WebView is (re)created.
        var needsInitialLoad: Boolean = false
        var pendingScrollRestore: Boolean = false
        // v1.4.1 FIX: home state must track the CURRENT url, not the birth url —
        // `startUrl == DEFAULT_HOME` kept every new tab "home" forever, which blanked the
        // omnibox URL text and hid the lock icon even after navigating to a real site.
        val isHome: Boolean get() = pageUrlSnapshot == Urls.DEFAULT_HOME || pageUrlSnapshot.isEmpty() && startUrl == Urls.DEFAULT_HOME
    }

    private val main = Handler(Looper.getMainLooper())
    val tabs = mutableListOf<Tab>()
    var current: Tab? = null
    var listener: ((Tab?) -> Unit)? = null   // called on tab switch/create/close
    // v1.7.17: the FLOATING-PLAYER tab (set by MainActivity while its WebView is
    // detached into the floating overlay window). LRU eviction and the MAX_TABS
    // overflow below would destroy the playing WebView, killing the video/music
    // mid-playback — the floating tab is protected for as long as it floats.
    var evictProtected: Tab? = null

    // v1.7.7: registry of named groups — groups survive even while EMPTY (created
    // via the «Создать группу» button), persisted with the session
    val groupNames = mutableListOf<String>()
    /** Set while the tab switcher overlay is on screen — persisted so a process
     *  death while browsing the switcher restores the switcher, not the page. */
    var switcherOpen: Boolean = false

    private var seq = System.currentTimeMillis() / 1000

    fun newTab(url: String, incognito: Boolean, select: Boolean = true): Tab {
        val tab = Tab(++seq, url, incognito)
        tab.desktop = Prefs.desktopDefault   // honor the global toggle at birth
        if (tabs.size >= MAX_TABS) {
            // close least recently used non-current tab
            // v1.7.17: the floating-player tab is never the overflow victim —
            // its WebView plays the media inside the floating window.
            val victim = tabs.filter { it !== current && it !== tab && it !== evictProtected }
                .minByOrNull { it.lastUsed }
            if (victim != null) closeTab(victim, selectReplacement = false)
        }
        tabs.add(tab)
        if (select) { current = tab; tab.lastUsed = System.currentTimeMillis(); listener?.invoke(tab) }
        return tab
    }

    fun restoreTab(url: String, incognito: Boolean, savedId: Long = 0): Tab {
        val id = if (savedId > 0) { seq = maxOf(seq, savedId); savedId } else ++seq
        val tab = Tab(id, url, incognito)
        // v1.4.2 FIX 5: restored session tabs load their URL the first time they are shown
        tab.needsInitialLoad = true
        tabs.add(tab)
        return tab
    }

    fun select(tab: Tab) {
        if (current === tab) return
        // capture preview of the tab we are leaving (for the switcher)
        current?.let { captureThumbnail(it) }
        current = tab
        tab.lastUsed = System.currentTimeMillis()
        ensureWebView(tab)
        listener?.invoke(tab)
    }

    fun closeTab(tab: Tab, selectReplacement: Boolean = true) {
        val idx = tabs.indexOf(tab)
        captureThumbnail(tab)
        deleteThumbnail(tab)   // v1.4.2 FIX 4: no orphaned preview files
        destroy(tab)
        tabs.remove(tab)
        tab.savedState = null
        if (current === tab) {
            current = if (selectReplacement && tabs.isNotEmpty()) {
                val next = tabs.getOrNull(idx.coerceAtMost(tabs.size - 1)) ?: tabs.last()
                ensureWebView(next)
                next.lastUsed = System.currentTimeMillis()
                listener?.invoke(next)
                next
            } else null
        } else {
            // a background tab was closed: notify listeners so switcher lists refresh
            listener?.invoke(current)
        }
    }

    fun regularTabs(): List<Tab> = tabs.filter { !it.incognito }
    fun privateTabs(): List<Tab> = tabs.filter { it.incognito }

    /** v1.7.7: every group that exists (registry + any tab still carrying the name). */
    fun allGroups(): List<String> =
        (groupNames + tabs.map { it.group }).filter { it.isNotEmpty() }.distinct().sorted()

    /** v1.7.7: creates a named group (visible even while empty) — returns false on dupes.
     *  v1.7.8 (user: «группы-папки с иконками и цветами — выбор эмодзи/цвета»):
     *  every group carries an EMOJI + COLOR badge. */
    class GroupMeta(val emoji: String, val color: Int)

    /** name → emoji + color (v1.7.8); survives restarts via the session JSON. */
    val groupMeta = HashMap<String, GroupMeta>()

    fun createGroup(name: String, emoji: String = "📁", color: Int = 0xFF10B981.toInt()): Boolean {
        val n = name.trim().take(32)
        if (n.isEmpty() || groupNames.contains(n)) return false
        groupNames.add(n)
        groupMeta[n] = GroupMeta(emoji, color)
        return true
    }

    fun renameGroup(old: String, new: String): Boolean {
        val n = new.trim().take(32)
        if (n.isEmpty() || n == old) return false
        if (groupNames.contains(n)) return false
        if (groupNames.remove(old)) groupNames.add(n)
        groupMeta.remove(old)?.let { groupMeta[n] = it }
        for (t in tabs) if (t.group == old) t.group = n
        return true
    }

    /** v1.7.8: change just the badge of an existing group. */
    fun setGroupMeta(name: String, emoji: String, color: Int) {
        if (!groupNames.contains(name) && tabs.none { it.group == name }) return
        groupMeta[name] = GroupMeta(emoji, color)
    }

    /** Removes the group REGISTRY entry; tabs keep the name only if [closeTabs] is false. */
    fun deleteGroup(name: String, closeTabs: Boolean) {
        groupNames.remove(name)
        groupMeta.remove(name)
        if (closeTabs) {
            tabs.toList().filter { it.group == name }.forEach { closeTab(it, selectReplacement = false) }
        } else {
            tabs.filter { it.group == name }.forEach { it.group = "" }
        }
    }

    fun ensureWebView(tab: Tab): WebView {
        tab.webView?.let { return it }
        val wv = createWebView(tab)
        tab.webView = wv
        // v1.4.2 FIX 5/6: bring an evicted/restored tab back WITHOUT a reload —
        // 1) restore the saved WebView state (back/forward list + current entry);
        // 2) only as a LAST resort load the URL (first show of a restored session tab).
        val st = tab.savedState
        var restored = false
        if (st != null) {
            try { wv.restoreState(st); restored = true } catch (_: Exception) {}
            tab.savedState = null
        }
        if (!restored && tab.needsInitialLoad) {
            val url = tab.pageUrlSnapshot.ifEmpty { tab.startUrl }
            if (url.isNotEmpty() && url != Urls.DEFAULT_HOME) wv.loadUrl(url)
        }
        if (restored) tab.pendingScrollRestore = true
        tab.needsInitialLoad = false
        evictLRU()
        return wv
    }

    private fun createWebView(tab: Tab): WebView {
        // v1.7.15: SelWebView — text-selection ActionMode with the extra actions
        // (Перевести / Найти / Поделиться / Вслух). The context TabManager holds
        // IS the MainActivity that constructed it; the safe cast keeps every
        // other caller shape working. Everything below still treats it as a
        // plain WebView (it is one).
        val wv = SelWebView(context) { action, text ->
            (context as? MainActivity)?.onSelectionAction(action, text)
        }
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val s: WebSettings = wv.settings
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(true)
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        // User-Agent: DEFAULT (system WebView mobile UA) unless desktop/custom explicitly set.
        // v1.3.2 bug: unconditional desktopUA() made Google serve PC pages with the toggle OFF.
        s.userAgentString = uaFor(tab)
        applySettingsFor(wv, tab)
        // appearance: algorithmic darkening for "dark page" look
        val appearance = effectiveAppearance(tab)
        val wantDark = when (appearance) {
            2 -> true   // dark
            else -> false
        }
        if (wantDark && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            try { WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, true) } catch (_: Exception) {}
        }
        return wv
    }

    fun applySettingsFor(wv: WebView, tab: Tab) {
        val s = wv.settings
        val host = Urls.domainOf(tab.pageUrlSnapshot.ifEmpty { tab.startUrl })
        val ds = runBlockingDomain(host)
        // v1.7.5: PER-SITE page appearance (user: «взял сепию на википедии — на ютубе
        // она тоже сепия, вернуть не получалось»). domain_settings.appearance wins,
        // no override → the global pref. "System/Light" now really resets (the old
        // menu picker injected an EMPTY script for them, so the sepia style tag
        // stayed in the DOM forever — that was the leak).
        tab.appearance = ds?.appearance ?: -1
        val eff = effectiveAppearance(tab)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, eff == 2)
            }
        } catch (_: Exception) {}
        wv.setBackgroundColor(when {
            tab.incognito -> 0xFF241A3D.toInt()
            eff == 2 -> 0xFF16181D.toInt()
            eff == 3 -> 0xFFF3E7D3.toInt()
            else -> 0xFFFFFFFF.toInt()
        })
        val js = ds?.javaScript ?: Prefs.jsEnabled
        val images = ds?.images ?: Prefs.imagesEnabled
        val popups = ds?.popups ?: Prefs.popupsEnabled
        // per-domain desktop override (site settings)
        if (ds?.desktop == true && !tab.desktop) {
            tab.desktop = true
            s.userAgentString = desktopUA()
        }
        s.javaScriptEnabled = js
        s.loadsImagesAutomatically = images
        s.blockNetworkImage = !images
        s.javaScriptCanOpenWindowsAutomatically = popups
        s.setSupportMultipleWindows(true)
        try {
            CookieManager.getInstance().setAcceptCookie(Prefs.cookiesEnabled)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, Prefs.thirdPartyCookies)
        } catch (_: Exception) {}
    }

    fun desktopUA(): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun mobileUA(): String =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Resolve the UA for a tab: explicit desktop > UA-mode pref (custom/mobile/desktop/system). */
    fun uaFor(tab: Tab): String? = when {
        tab.desktop -> desktopUA()
        Prefs.uaMode == 1 -> mobileUA()
        Prefs.uaMode == 2 -> desktopUA()
        Prefs.uaMode == 3 -> Prefs.customUa.ifEmpty { null }
        else -> null   // system default (mobile on real devices)
    }

    fun setDesktopMode(tab: Tab, on: Boolean) {
        tab.desktop = on
        val wv = tab.webView ?: return
        // uaFor respects the fresh tab.desktop value (null = system mobile default)
        wv.settings.userAgentString = uaFor(tab)
    }

    /** Capture a scaled page preview of the tab's WebView (called on switch/close/pause). */
    fun captureThumbnail(tab: Tab) {
        val wv = tab.webView ?: return
        if (tab.isHome) { tab.thumbnail = null; return }
        try {
            if (wv.width <= 0 || wv.height <= 0) return
            val w = 480
            val h = (wv.height * (w.toFloat() / wv.width)).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(w.toFloat() / wv.width, h.toFloat() / wv.height)
            wv.draw(canvas)
            tab.thumbnail?.recycle()
            tab.thumbnail = bmp
        } catch (_: Exception) {}
    }

    /**
     * v1.5.0 FIX 3: capture the HOME screen (no WebView there) as the tab preview.
     * captureThumbnail() keeps returning null for home tabs — this sibling method is
     * called by MainActivity.openTabSwitcher() for the CURRENT home tab so its card
     * shows a real screenshot instead of the branded home mock.
     * UI thread only (draws a live view).
     */
    fun captureHomeThumbnail(tab: Tab, view: android.view.View) {
        if (!tab.isHome) return
        try {
            if (view.width <= 0 || view.height <= 0) return
            val w = 480
            val h = (view.height * (w.toFloat() / view.width)).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(w.toFloat() / view.width, h.toFloat() / view.height)
            view.draw(canvas)
            tab.thumbnail?.recycle()
            tab.thumbnail = bmp
        } catch (_: Exception) {}
    }

    /** Freshen previews of every LIVE tab (called when the switcher opens). */
    fun captureAllThumbnails() {
        for (t in tabs) {
            if (t.webView != null) captureThumbnail(t)
        }
    }

    // ---------------- v1.4.2 FIX 4: thumbnails persisted to filesDir/thumbs/<tabId>.png ----------------
    // Survive LRU eviction AND app restarts (tab ids are saved with the session).

    private fun thumbsDir(): File = File(context.filesDir, "thumbs").apply { mkdirs() }
    private fun thumbFile(id: Long): File = File(thumbsDir(), "$id.png")
    private fun thumbUrlFile(id: Long): File = File(thumbsDir(), "$id.url")

    /** Async write of the tab's in-memory preview to disk (fire-and-forget).
     *  v1.5.1 PRIVACY FIX: incognito previews are NEVER written to disk —
     *  the private section shows a blurred teaser instead of a real screenshot. */
    fun persistThumbnail(tab: Tab) {
        val bmp = tab.thumbnail ?: return
        val f = thumbFile(tab.id)
        if (tab.incognito) {
            // also sweep any stale file left by earlier versions
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { if (f.exists()) f.delete() } catch (_: Exception) {}
            }
            return
        }
        // v1.7.5: the URL is written next to the image — loadPersistedThumbnail only
        // trusts a file whose recorded URL matches (stale previews from a re-used
        // tab id could otherwise put ANOTHER site's screenshot on a fresh tab card).
        val url = tab.pageUrlSnapshot.ifEmpty { tab.startUrl }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                thumbUrlFile(tab.id).writeText(url)
            } catch (_: Exception) { f.delete() }
        }
    }

    /** Async load of a persisted preview — cb on the MAIN thread (null if none).
     *  v1.5.1: incognito tabs never have persisted previews. */
    fun loadPersistedThumbnail(tab: Tab, cb: (Bitmap?) -> Unit) {
        if (tab.thumbnail != null) { cb(tab.thumbnail); return }
        if (tab.incognito) { cb(null); return }
        val f = thumbFile(tab.id)
        val expectedUrl = tab.pageUrlSnapshot.ifEmpty { tab.startUrl }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var bmp: Bitmap? = null
            try {
                if (f.exists()) {
                    // v1.7.5: sidecar URL check — a preview from a DIFFERENT page
                    // (e.g. an old id collision) is discarded instead of shown.
                    val uf = thumbUrlFile(tab.id)
                    val ok = if (uf.exists()) {
                        try { uf.readText() == expectedUrl } catch (_: Exception) { false }
                    } else {
                        // pre-1.7.5 file without a sidecar: only trusted for NON-home
                        // tabs — a fresh home tab must never inherit an old screenshot.
                        expectedUrl != Urls.DEFAULT_HOME
                    }
                    if (ok) bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                }
            } catch (_: Exception) {}
            val result = bmp
            main.post { cb(result) }
        }
    }

    private fun deleteThumbnail(tab: Tab) {
        val f = thumbFile(tab.id)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { if (f.exists()) f.delete() } catch (_: Exception) {}
        }
    }

    /** Delete preview files that belong to no live tab (startup sweep). */
    fun sweepOrphanThumbnails() {
        val dir = File(context.filesDir, "thumbs")
        if (!dir.isDirectory) return
        val liveIds = tabs.map { it.id }.toHashSet()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { dir.listFiles()?.forEach { f ->
                val n = f.name.removeSuffix(".png").toLongOrNull()
                if (n == null || n !in liveIds) f.delete()
            } } catch (_: Exception) {}
        }
    }

    fun effectiveAppearance(tab: Tab): Int = if (tab.appearance >= 0) tab.appearance else Prefs.pageAppearance

    private fun evictLRU() {
        val live = tabs.filter { it.webView != null }.sortedByDescending { it.lastUsed }
        if (live.size <= MAX_LIVE) return
        for (victim in live.drop(MAX_LIVE)) {
            if (victim === current) continue
            if (victim.pinned) continue   // v1.7.7: pinned tabs stay alive
            if (victim === evictProtected) continue   // v1.7.17: floating player stays alive
            val wv = victim.webView ?: continue
            victim.scrollY = wv.scrollY
            captureThumbnail(victim)
            persistThumbnail(victim)   // v1.4.2 FIX 4: preview survives eviction
            // v1.4.2 FIX 5: snapshot WebView state BEFORE destroying it — selecting the
            // tab again restores the session (no reload, history kept).
            try {
                val b = Bundle()
                wv.saveState(b)
                if (!b.isEmpty) victim.savedState = b
            } catch (_: Exception) {}
            victim.needsInitialLoad = true   // backup path if restoreState fails
            destroy(victim)
        }
    }

    private fun destroy(tab: Tab) {
        val wv = tab.webView ?: return
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.onPause()
            wv.clearHistory()
            wv.destroy()
        } catch (_: Exception) {}
        tab.webView = null
    }

    fun destroyAll() {
        tabs.toList().forEach {
            deleteThumbnail(it)
            destroy(it)
        }
        tabs.clear()
        current = null
    }

    // domain settings cache (host -> settings); refreshed by MainActivity on changes
    @Volatile private var domainCache = mapOf<String, com.zephyr.browser.db.DomainSettingsEntity?>()
    private val domainPending = ArrayDeque<String>()

    fun refreshDomainSettingsAsync() {
        val ctx = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val map = HashMap<String, com.zephyr.browser.db.DomainSettingsEntity?>()
                for (d in ctx.db.domainSettingsDao().allOnce()) map[d.domain] = d
                domainCache = map
            } catch (_: Exception) {}
        }
    }

    private fun runBlockingDomain(host: String): com.zephyr.browser.db.DomainSettingsEntity? {
        if (host.isEmpty()) return null
        domainCache[host]?.let { return it }
        // best suffix match
        for ((d, v) in domainCache) {
            if (host.endsWith(".$d")) return v
        }
        return null
    }

    fun domainSettingFor(host: String): com.zephyr.browser.db.DomainSettingsEntity? = runBlockingDomain(host)

    // ---------------- session persistence ----------------
    // v1.7.7 FIX (user: «свернул браузер на экране вкладок — вернулся на главную»):
    // the OLD save went through DataStore's ASYNC editor — a process kill right after
    // onPause could land BEFORE the write, wiping the whole session (fresh start = home).
    // The session is now ALSO written to a plain file SYNCHRONOUSLY (few KB, sub-ms),
    // and it carries the CURRENT tab id + the switcher-open flag + pinned flags + the
    // group registry, so a kill anywhere restores the exact state (page or switcher).

    private fun sessionFile(): File = File(context.filesDir, "session.json")

    fun saveSession() {
        try {
            val arr = JSONArray()
            for (t in tabs) {
                arr.put(JSONObject().apply {
                    put("id", t.id)   // v1.4.2 FIX 4: stable ids → persisted thumbnails match after restart
                    put("url", t.pageUrlSnapshot.ifEmpty { t.startUrl })
                    put("incognito", t.incognito)
                    put("desktop", t.desktop)
                    if (t.group.isNotEmpty()) put("g", t.group)   // v1.7.6: tab groups
                    if (t.pinned) put("p", true)   // v1.7.7: pinned tabs
                })
            }
            val root = JSONObject().apply {
                put("tabs", arr)
                current?.let { put("cur", it.id) }   // v1.7.7: WHICH tab was open
                put("sw", switcherOpen)              // v1.7.7: switcher on screen?
                val gs = JSONArray()
                for (g in groupNames) gs.put(g)
                put("groups", gs)                    // v1.7.7: empty-group registry
                // v1.7.8: emoji+color badges: {name: [emoji, colorInt]}
                if (groupMeta.isNotEmpty()) {
                    val gm = JSONObject()
                    for ((name, meta) in groupMeta) {
                        val pair = JSONArray()
                        pair.put(meta.emoji); pair.put(meta.color)
                        gm.put(name, pair)
                    }
                    put("gm", gm)
                }
            }
            val json = root.toString()
            Prefs.setSaveTabs(json)   // legacy copy (DataStore) kept for downgrade safety
            try {
                val f = sessionFile()
                val tmp = File(f.absolutePath + ".tmp")
                tmp.writeText(json)          // atomic-ish: write temp, then rename
                if (!tmp.renameTo(f)) { f.writeText(json); tmp.delete() }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** v1.7.7: id of the tab that should be selected after a restore (0 = first non-private). */
    var restoreCurrentId: Long = 0
    /** v1.7.7: whether the switcher overlay was on screen when the session was saved. */
    var restoreSwitcherOpen: Boolean = false

    fun restoreSession(): List<Tab> {
        if (!Prefs.restoreTabs) return emptyList()
        var json = ""
        try {
            val f = sessionFile()
            if (f.exists()) json = f.readText()
        } catch (_: Exception) {}
        if (json.isEmpty()) json = Prefs.saveTabs   // pre-1.7.7 sessions (bare array)
        if (json.isEmpty()) return emptyList()
        return try {
            val (arr, root) = if (json.trimStart().startsWith("[")) {
                JSONArray(json) to null
            } else {
                val r = JSONObject(json)
                JSONArray(r.optJSONArray("tabs") ?: return emptyList()) to r
            }
            val restored = mutableListOf<Tab>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = restoreTab(o.optString("url"), o.optBoolean("incognito"), o.optLong("id", 0))
                t.desktop = o.optBoolean("desktop", Prefs.desktopDefault)
                t.group = o.optString("g", "")   // v1.7.6: tab groups survive restarts
                t.pinned = o.optBoolean("p", false)   // v1.7.7: pinned tabs survive restarts
                restored.add(t)
            }
            if (root != null) {
                restoreCurrentId = root.optLong("cur", 0)
                restoreSwitcherOpen = root.optBoolean("sw", false)
                val gs = root.optJSONArray("groups")
                if (gs != null) for (i in 0 until gs.length()) {
                    val g = gs.optString(i)
                    if (g.isNotEmpty() && !groupNames.contains(g)) groupNames.add(g)
                }
                // v1.7.8: group badges
                val gm = root.optJSONObject("gm")
                if (gm != null) {
                    for (key in gm.keys()) {
                        val pair = gm.optJSONArray(key) ?: continue
                        if (pair.length() >= 2) groupMeta[key] = GroupMeta(
                            pair.optString(0, "📁"), pair.optLong(1, 0xFF10B981.toInt().toLong()).toInt())
                    }
                }
            }
            restored
        } catch (_: Exception) { emptyList() }
    }
}
