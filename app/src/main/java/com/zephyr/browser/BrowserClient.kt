package com.zephyr.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.LruCache
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.SiteDataEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/** WebViewClient: ad-block interception (pure-data), app-link detection, snapshots for Chromium threads. */
open class BrowserClient(
    private val activity: MainActivity
) : WebViewClient() {

    companion object {
        /**
         * v1.8.0: the schemes the WebView itself renders. Everything outside
         * this set belongs to some other app — see [isExternalScheme].
         *
         * `javascript` is here deliberately: bookmarklets and injected scripts
         * run in the page, and handing one to the system would be both broken
         * and a way to smuggle script execution out of the browser.
         */
        private val WEB_SCHEMES = setOf(
            "http", "https", "file", "data", "about", "blob", "javascript", "content"
        )

        // v1.8.0: the old EXT_SCHEMES whitelist lived here — mailto, tel, tg,
        // whatsapp and a dozen more. It is gone rather than extended, because
        // an incomplete list was the bug: `fb://` was simply missing, and the
        // next missing scheme would have looked like a broken browser again.
        // See isExternalScheme, which now asks the opposite question.

        // curated app-link hosts (beyond generic package-manager detection)
        private val APP_HOSTS = mapOf(
            "maps.google.com" to "geo", "maps.app.goo.gl" to "geo",
            "yandex.ru" to "maps-if-path", "4pda.to" to "4pda", "forum.4pda.to" to "4pda", "4pda.me" to "4pda",
            "youtube.com" to "youtube", "m.youtube.com" to "youtube", "youtu.be" to "youtube", "music.youtube.com" to "youtube",
            "spotify.com" to "spotify", "open.spotify.com" to "spotify",
            "t.me" to "telegram", "telegram.me" to "telegram", "telegram.dog" to "telegram",
            "wa.me" to "whatsapp", "chat.whatsapp.com" to "whatsapp",
            "instagram.com" to "instagram", "tiktok.com" to "tiktok", "vm.tiktok.com" to "tiktok",
            "facebook.com" to "facebook", "m.facebook.com" to "facebook", "x.com" to "x", "twitter.com" to "x",
            "reddit.com" to "reddit", "m.reddit.com" to "reddit", "vk.com" to "vk", "m.vk.com" to "vk",
            "vk.ru" to "vk", "pinterest.com" to "pinterest", "twitch.tv" to "twitch", "m.twitch.tv" to "twitch"
        )
        private val hostCache = LruCache<String, String>(256)   // host -> package or ""

        // v1.7.9 «Данные сайтов»: per-site request counter shared by ALL WebView
        // clients. Request-path cost = ONE ConcurrentHashMap.merge; the DB round-trip
        // (read + upsert) runs on a shared IO scope and drains every count that piled
        // up for the host while a flush was in flight — bursts never queue hundreds
        // of transactions and nothing is dropped.
        private val counterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pendingCounts = ConcurrentHashMap<String, Long>()
        private val flushingHosts = ConcurrentHashMap<String, Boolean>()
    }

    // ---------------- per-site request counting (cache manager) ----------------

    /** Fire-and-forget: bump the request counter for [uri]'s host. NEVER blocks or
     *  throws on the interception path (every DB failure is swallowed). */
    private fun countRequest(uri: Uri) {
        try {
            val host = uri.host?.trim()?.lowercase()?.removePrefix("www.") ?: return
            if (host.isEmpty() || !host.contains('.')) return
            pendingCounts.merge(host, 1L, Long::plus)
            if (flushingHosts.putIfAbsent(host, true) != null) return   // drain already running
            val app = activity.applicationContext
            counterScope.launch {
                try { drainCounts(app, host) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** IO-side drain loop: writes accumulated counts, then re-arms if the handoff
     *  window (remove→flag-clear) let new counts slip in. */
    private suspend fun drainCounts(app: Context, host: String) {
        try {
            val dao = AppDb.get(app).siteDataDao()
            while (true) {
                val n = pendingCounts.remove(host) ?: break
                try {
                    val now = System.currentTimeMillis()
                    val row = dao.byDomain(host)
                    if (row == null) dao.upsert(SiteDataEntity(domain = host, requests = n, lastUsed = now))
                    else dao.upsert(row.copy(requests = row.requests + n, lastUsed = now))
                } catch (_: Exception) {}
                if (!pendingCounts.containsKey(host)) break
            }
        } finally {
            flushingHosts.remove(host)
        }
        if (pendingCounts.containsKey(host) && flushingHosts.putIfAbsent(host, true) == null) {
            drainCounts(app, host)
        }
    }

    // ---------------- request interception (adblock) ----------------

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return try {
            val url = request.url.toString()
            if (!url.startsWith("http")) return null
            val tab = activity.currentTabForThreads() ?: return null
            val pageUrl = tab.pageUrlSnapshot
            val isMain = request.isForMainFrame
            if (isMain) {
                // v1.7.9: main-frame requests pass through normally — they count too
                countRequest(request.url)
                return null
            }
            val blocked = AdBlockManager.shouldBlock(url, pageUrl)
            if (blocked) {
                val mime = request.requestHeaders?.get("Accept")?.let { a ->
                    when {
                        a.contains("image/") -> "image/gif"
                        else -> "text/plain"
                    }
                } ?: "text/plain"
                WebResourceResponse(mime, "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                // v1.7.9: normally passed-through request → per-site counter (cache manager)
                countRequest(request.url)
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- navigation ----------------

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return activity.handleUrlLoading(view, request.url)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        activity.onPageStarted(url)
        favicon?.let { FaviconLoader.store(activity.lastKnownPageUrl(), it) }
    }

    override fun onPageFinished(view: WebView, url: String) {
        activity.onPageFinished(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        activity.onVisitedHistory(url ?: view.url ?: "")
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
        if (request.isForMainFrame) activity.onMainFrameError(request.url.toString(), error.description?.toString() ?: "", error.errorCode)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) activity.onMainFrameHttpError(request.url.toString(), errorResponse.statusCode)
    }

    /**
     * v1.7.50 FIX (user: «жму „остаться" — окно не уходит, „покинуть" не
     * работает, а тап рядом закрывает»).
     *
     * The handler was cancelled BEFORE the user was asked, so the answer could
     * not be acted on: accepting reloaded the page, the same error fired again
     * and the dialog came straight back; declining did nothing at all. The
     * allow-list was written but never read, so it changed nothing either.
     *
     * Now the handler is kept alive and the decision is real: proceed() or
     * cancel(), exactly once, and a host the user already trusted this session
     * is let through without asking twice.
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (activity.isSslAccepted(error.url)) {
            handler.proceed()
            return
        }
        activity.onSslError(handler, view.url ?: "", error.url, error.primaryError)
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        return activity.onRenderGone()
    }

    // ---------------- external scheme handling ----------------

    /** True if the URL is an external scheme handled outside WebView. */
    /**
     * Should this link leave the browser?
     *
     * v1.8.0 (user: «ссылка facebook из WhatsApp — ERR_UNKNOWN_URL_SCHEME»).
     * This asked whether the scheme was on a hand-written list of about twenty.
     * `fb://` was not on it, so the link fell through to the WebView, which
     * cannot render a scheme it does not know and failed with a raw Chromium
     * error.
     *
     * A list of app schemes can never be complete — every app invents its own,
     * and each missing one looks to the user like a broken browser. So the
     * question is inverted: the WebView handles a short, fixed set of schemes,
     * and **everything else goes to the system**, which knows what is installed.
     * If nothing is, `handleExternal` says so plainly instead of the WebView
     * failing.
     *
     */
    fun isExternalScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme !in WEB_SCHEMES
    }

    /** Build a safe external intent for a URI (intent:// hardened, fallback URL extracted). */
    fun externalIntent(uri: Uri): Pair<Intent, String?>? {
        return try {
            if (uri.scheme?.lowercase() == "intent") {
                val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                val fallback = i.getStringExtra("browser_fallback_url")
                i to fallback
            } else {
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE) to null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Generic app detection: is there a non-browser app that can open [host]? Returns its package or null. */
    fun findAppHandler(url: String): String? {
        val host = Urls.domainOf(url)
        if (host.isEmpty()) return null
        hostCache.get(host)?.let { return it.ifEmpty { null } }
        var result: String? = null
        try {
            val pm = activity.packageManager
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val candidates = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            // probe: a "generic browser" resolves ANY http url — detect by resolving example.org
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")).addCategory(Intent.CATEGORY_BROWSABLE)
            val browserInfos = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(probe, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, 0)
            }
            val browserPkgs = browserInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
            for (ri in candidates) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == activity.packageName) continue
                if (pkg in browserPkgs) continue
                result = pkg
                break
            }
        } catch (_: Exception) {}
        hostCache.put(host, result ?: "")
        return result
    }

    fun isCuratedAppHost(host: String, url: String): Boolean {
        val h = host.removePrefix("www.")
        val special = APP_HOSTS[h] ?: APP_HOSTS[host]
        if (special == null) return false
        if (special == "maps-if-path") return url.contains("/maps")
        return true
    }
}
