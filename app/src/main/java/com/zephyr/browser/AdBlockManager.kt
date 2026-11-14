package com.zephyr.browser

import android.content.Context
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Ad-block manager: builtin rules + user filter lists + whitelist + counters.
 * All matching is pure-data (safe from Chromium threads); disk/DB work happens on background.
 */
object AdBlockManager {

    private lateinit var appContext: Context

    val engine = AdBlockEngine()
    private val pendingBlocked = AtomicLong(0)
    @Volatile private var customReady = false

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        engine.addList(BUILTIN_RULES)
        // custom filter lists loaded async (they can be big)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO +
            kotlinx.coroutines.SupervisorJob()).launch {
            try {
                val db = appContext.db
                var anyStale = false
                for (fl in db.filterListDao().allOnce()) {
                    if (!fl.enabled) continue
                    val file = filterFile(fl.id)
                    if (file.exists()) {
                        engine.addList(file.readText())
                    }
                    // v1.7.8: auto-refresh installed lists that are older than 3 days —
                    // stale lists were silently rotting after the v1.7.2 install
                    if (System.currentTimeMillis() - fl.updatedAt > 3 * 24 * 3600_000L) anyStale = true
                }
                if (anyStale) refreshStaleLists()
            } catch (_: Exception) {
            } finally {
                customReady = true
            }
        }
    }

    /** v1.7.8: re-download every enabled list whose copy is stale, then rebuild the engine. */
    private suspend fun refreshStaleLists() {
        try {
            val db = appContext.db
            var changed = false
            for (fl in db.filterListDao().allOnce()) {
                if (!fl.enabled || !fl.url.startsWith("http")) continue
                if (System.currentTimeMillis() - fl.updatedAt < 3 * 24 * 3600_000L) continue
                try {
                    val count = downloadList(fl.id, fl.url)
                    db.filterListDao().updateStats(fl.id, count, System.currentTimeMillis())
                    changed = true
                } catch (_: Exception) {}
            }
            if (changed) reloadAll()
        } catch (_: Exception) {}
    }

    fun isCustomReady(): Boolean = customReady

    fun filterFile(id: Long): File = File(File(appContext.filesDir, "filters").apply { mkdirs() }, "$id.txt")

    /** Count of enabled rules in the engine. */
    fun ruleCount(): Int = engine.size()

    // ---------------- matching ----------------

    fun shouldBlock(requestUrl: String, pageUrl: String, contentType: String? = null): Boolean {
        // v1.7.8 (user: «режимы блокировки путают — либо включаем, либо выключаем»):
        // ONE switch. adBlockMode 0 = off, anything else = on (legacy values 1/2/3
        // from old installs all mean "on" now).
        if (Prefs.adBlockMode == 0) return false
        // v1.7.5 (user: «после VPN youtube периодически пишет — проверьте подключение»):
        // connectivity/captive-portal probes are NEVER blocked — a probe swallowed by
        // a filter makes the page (and YouTube) believe the whole network is down and
        // pop the "check your connection" banner even though everything else works.
        if (isConnectivityProbe(requestUrl)) return false
        val pageHost = Urls.domainOf(pageUrl)
        if (pageHost.isNotEmpty()) {
            // whitelist (site or any parent domain entry)
            for (d in Prefs.adBlockWhitelist) {
                if (pageHost == d || pageHost.endsWith(".$d")) return false
            }
        }
        // ---------------- v1.6.0 / v1.7.2: YouTube ad endpoints (network layer) ----------------
        // In-stream video ads are fetched via dedicated endpoints/hosts that NEVER
        // overlap with googlevideo.com (the real video CDN) — so blocking them is
        // playback-safe. This is the same approach Brave/AdGuard browsers use; the
        // JS layer (Scripts.YT_ADBLOCK + Scripts.SPONSORBLOCK) only handles what
        // slips through (skip-click, feed sponsored cards, overlay banners,
        // in-video sponsored segments).
        // v1.7.2: the endpoint check moved into isYouTubeAd() and grew —
        // googlesyndication + doubleclick ad hosts, pubads/static/googleads4
        // doubleclick variants, yt3 gtv-videos-bucket ad bundles, /ptrace and
        // /pagead /api/stats/ads path keywords. googlevideo.com (the real video
        // CDN) is matched ONLY by those three path keywords, never by host, so
        // playback cannot break. Blocked YT requests now also feed the
        // blocked-today counter (they were silently uncounted).
        if (Prefs.youtubeAdBlock && isYouTubePage(pageHost)) {
            if (isYouTubeAd(requestUrl)) {
                pendingBlocked.incrementAndGet()
                return true
            }
        }
        val blocked = engine.shouldBlock(requestUrl, pageUrl)
        if (blocked) {
            pendingBlocked.incrementAndGet()
            return true
        }
        return false
    }

    /** v1.7.5: generate_204 / gen_204 style probes + captive-portal check hosts.
     *  Pure data, no I/O — safe from Chromium threads. */
    fun isConnectivityProbe(requestUrl: String): Boolean {
        try {
            val u = requestUrl.lowercase()
            if (u.contains("generate_204") || u.contains("generate204") ||
                u.contains("/gen_204") || u.contains("connecttest") ||
                u.contains("captive") || u.contains("hotspot-detect")) return true
            val host = Urls.domainOf(requestUrl)
            return host == "connectivitycheck.gstatic.com" ||
                host == "connectivitycheck.android.com" ||
                host == "connect.rom.miui.com" ||
                host.endsWith(".msftconnecttest.com") ||
                host.endsWith(".msftncsi.com") ||
                host == "library.airtunes.com" ||
                host == "captive.apple.com"
        } catch (_: Exception) {
            return false
        }
    }

    /** v1.7.2: page host is a YouTube site (kept from the inline v1.6.0 check). */
    private fun isYouTubePage(pageHost: String): Boolean =
        pageHost == "youtube.com" || pageHost.endsWith(".youtube.com") ||
            pageHost == "youtu.be" || pageHost == "music.youtube.com"

    /**
     * v1.7.2: ad/tracking request hosts + path keywords that are safe to block
     * while a YouTube page is open — none of them ever serve real video content
     * (googlevideo.com hosts are matched ONLY by the /pagead/, /ptrace/ and
     * /api/stats/ads path keywords, which never appear on /videoplayback media
     * URLs, so playback is untouchable).
     * v1.7.3: the preroll ad servers are additionally pinned as exact hosts at
     * the top of the function; the JS layer (Scripts.YT_ADBLOCK v3, injected at
     * document-start) now answers ad fetches with an empty VAST instead of letting
     * them fail — the network layer stays the first line of defense, the JS layer
     * removes the black-screen timeout the pure network block caused in 1.7.2.
     * Pure data, no I/O — safe from Chromium threads. Called ONLY when the page
     * itself is a YouTube page, so the non-YouTube request path is untouched.
     */
    fun isYouTubeAd(requestUrl: String): Boolean {
        try {
            val u = requestUrl.lowercase()
            val host = Urls.domainOf(requestUrl)
            if (host.isEmpty()) return false
            // v1.7.3: explicit whole-host pins for the preroll ad servers — they
            // NEVER serve video content, so blocking them on YT pages is always
            // playback-safe. Every one is also matched by the rules below
            // (pagead2 via the googlesyndication suffix rule, www.googleadservices
            // arrives here already www-stripped by Urls.domainOf); pinned so a
            // future narrowing of those rules can never re-open the preroll flow.
            if (host == "pagead2.googlesyndication.com" || host == "googleads.g.doubleclick.net" ||
                host == "www.googleadservices.com" || host == "googleadservices.com" ||
                host == "adservice.google.com" || host == "ads.youtube.com") return true
            // whole-host ad servers (all paths are ads)
            if (host == "googlesyndication.com" || host.endsWith(".googlesyndication.com")) return true
            if (host == "googleadservices.com" || host.endsWith(".googleadservices.com")) return true
            if (host == "ads.youtube.com" || host == "adservice.google.com") return true
            if (host == "googleads.g.doubleclick.net" || host == "googleads4.g.doubleclick.net" ||
                host == "ad.doubleclick.net" || host == "pubads.g.doubleclick.net" ||
                host == "securepubads.g.doubleclick.net" || host == "static.doubleclick.net" ||
                host == "pagead2.l.doubleclick.net") return true
            // doubleclick hosts that also carry non-ad redirects: block ad paths only
            if (host == "doubleclick.net" || host.endsWith(".doubleclick.net")) {
                if (u.contains("/adj") || u.contains("/adi/") || u.contains("/adx") ||
                    u.contains("/ad/") || u.contains("/ddm/") || u.contains("/jump/") ||
                    u.contains("adserver") || u.contains("ad_type")) return true
            }
            // youtube ad/metrics endpoints + ad path keywords on youtube hosts
            if (host == "youtube.com" || host.endsWith(".youtube.com")) {
                if (u.contains("/api/stats/ads") || u.contains("/get_midroll_info") ||
                    u.contains("/pagead/") || u.contains("/ptracking") ||
                    u.contains("/ptrace/") || u.contains("/vast/") ||
                    u.contains("/adx/") || u.contains("/adserver/") ||
                    u.contains("video-ads")) return true
            }
            // yt3 ad bundles (gtv-videos-bucket ads)
            if (host == "yt3.ggpht.com" && u.contains("gtv-videos-bucket") && u.contains("ads")) return true
            // googlevideo.com is the REAL video CDN — block ONLY the ad/tracing
            // path keywords that never occur on /videoplayback media URLs;
            // anything host-wide here would break actual playback.
            if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) {
                if (u.contains("/pagead/") || u.contains("/ptrace/") ||
                    u.contains("/api/stats/ads")) return true
            }
            return false
        } catch (_: Exception) {
            return false
        }
    }

    fun elementHideCss(pageUrl: String): String =
        if (Prefs.adBlockMode == 0) "" else engine.elementHideCss(Urls.domainOf(pageUrl))

    // ---------------- counters ----------------

    fun onBlocked() { pendingBlocked.incrementAndGet() }

    /** Move pending counter into persistent storage (call on UI thread opportunistically). */
    fun flushBlocked() {
        val n = pendingBlocked.getAndSet(0)
        if (n > 0) Prefs.addBlockedToday(n)
    }

    fun blockedToday(): Long = Prefs.blockedToday
    fun blockedTotal(): Long = Prefs.blockedTotal

    // ---------------- filter list downloads ----------------

    /** Preset catalog shown in settings. */
    val catalog: List<Triple<String, String, String>> = listOf(
        Triple("EasyList", "https://easylist.to/easylist/easylist.txt", "ads"),
        Triple("EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", "privacy"),
        Triple("AdGuard Base", "https://filters.adtidy.org/extension/chromium/filters/2.txt", "ads"),
        Triple("AdGuard Mobile Ads", "https://filters.adtidy.org/extension/chromium/filters/11.txt", "ads"),
        Triple("AdGuard Tracking", "https://filters.adtidy.org/extension/chromium/filters/3.txt", "privacy"),
        Triple("Peter Lowe", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblock&showintro=1&mimetype=plaintext", "ads")
    )

    /** Download/refresh a filter list. Returns rule count or throws. */
    fun downloadList(id: Long, url: String): Int {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Zephyr")
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.length < 50) throw java.io.IOException("empty list")
            filterFile(id).writeText(text)
            return text.count { it == '\n' }
        } finally {
            conn.disconnect()
        }
    }

    fun importListFile(id: Long, uri: android.net.Uri): Int {
        val text = appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw java.io.IOException("cannot read")
        if (text.isEmpty()) throw java.io.IOException("empty file")
        filterFile(id).writeText(text)
        return text.count { it == '\n' }
    }

    /** Rebuild engine from builtin + enabled lists. */
    suspend fun reloadAll() {
        engine.clear()
        engine.addList(BUILTIN_RULES)
        val db = appContext.db
        for (fl in db.filterListDao().allOnce()) {
            if (!fl.enabled) continue
            val f = filterFile(fl.id)
            if (f.exists()) engine.addList(f.readText())
        }
    }

    fun deleteList(id: Long) {
        filterFile(id).delete()
    }

    // ---------------- builtin conservative list ----------------

    private val BUILTIN_RULES = """
||doubleclick.net^
||googleads.g.doubleclick.net^
||googlesyndication.com^
||google-analytics.com^
||googletagmanager.com^
||googletagservices.com^
||adservice.google.com^
||pagead2.googlesyndication.com^
||tpc.googlesyndication.com^
||adsystem.amazon.com^
||amazon-adsystem.com^
||adnxs.com^
||appnexus.com^
||criteo.com^
||criteo.net^
||outbrain.com^
||outbrainimg.com^
||taboola.com^
||adform.net^
||adition.com^
||adsrvr.org^
||adroll.com^
||bidswitch.net^
||casalemedia.com^
||pubmatic.com^
||rubiconproject.com^
||openx.net^
||smartadserver.com^
||spotxchange.com^
||teads.tv^
||yieldmo.com^
||zdbb.net^
||2mdn.net^
||admob.com^
||adsafeprotected.com^
||doubleverify.com^
||moatads.com^
||scorecardresearch.com^
||serving-sys.com^
||adcolony.com^
||unityads.unity3d.com^
||applovin.com^
||vungle.com^
||chartboost.com^
||inmobi.com^
||mopub.com^
||tapjoy.com^
||ironsrc.com^
||yandexadexchange.net^
||an.yandex.ru^
||yastatic.net^
||mc.yandex.ru^
||metrika.yandex.ru^
||adfox.ru^
||adriver.ru^
||buzzoola.com^
||adnxs-simple.com^
||adhigh.net^
||adlabs.ru^
||adriver.ru^
||apicdn.ru^
||betweendigital.com^
||adriver.ru^
||vk.com/rtrg^
||userapi.com/ads^
||tns-counter.ru^
||top-fwz1.mail.ru^
||ad.mail.ru^
||rb.mail.ru^
||top-mail.ru^
||adfox.ru^
||advertising.yandex.ru^
||ads.yahoo.com^
||advertising.com^
||adtechus.com^
||advertising.yandex.ru^
||yadro.ru^
||adriver.ru^
||adriver.ru^
||adskeeper.com^
||adskeeper.co.uk^
||mgid.com^
||revcontent.com^
||propellerads.com^
||propeller-tracking.com^
||zeropark.com^
||adcash.com^
||popads.net^
||popcash.net^
||clickadu.com^
||onclickads.net^
||onclasrv.com^
||adsrvmedia.net^
||adskeeper.uk^
||directrev.com^
||lonelyteenagers.com^
||adcash.com^
||exoclick.com^
||exosrv.com^
||realsrv.com^
||ero-advertising.com^
||adsterra.com^
||adsterratech.com^
||hilltopads.net^
||clickaine.com^
||juicyads.com^
||tsyndicate.com^
||trafficfactory.biz^
||trafficstars.com^
||coinzilla.com^
||a-ads.com^
||adbit.co^
||bitmedia.io^
||coinhive.com^
||coin-hive.com^
||jsecoin.com^
||minero.cc^
||authedmine.com^
||paceap.com^
||pacyad.com^
||profitableratecpm.com^
||btstatic.com^
||smaato.net^
||adwhirl.com^
||inmobicdn.net^
||ads.smartnews.com^
||staging.smartnews.com^
||advertising.staging.smartnews.com^
||shinystat.com^
||shinystat.cn^
||admob-cn.com^
||statcounter.com^
||histats.com^
||addthis.com^
||addthisedge.com^
||addthiscdn.com^
||sharethis.com^
||sharedcount.com^
||po.st^
||shareaholic.com^
||buzzsumo.com^
||clearbit.com^
||fullstory.com^
||hotjar.com^
||mouseflow.com^
||crazyegg.com^
||clicktale.net^
||inspectlet.com^
||sessioncam.com^
||quantserve.com^
||quantcount.com^
||chartbeat.com^
||chartbeat.net^
||parsely.com^
||parse.ly^
||newrelic.com^
||nr-data.net^
||bugsnag.com^
||sentry-cdn.com^
||browser.sentry-cdn.com^
||instana.io^
||instana-agent.io^
||raygun.io^
||logrocket.io^
||logrocket.com^
||pendo.io^
||appcues.com^
||walkme.com^
||intercom.io^
||intercomcdn.com^
||wistia.com^
||wistia.net^
||marketo.com^
||marketo.net^
||eloqua.com^
||eloquacdn.com^
||hubspot.com^
||hsforms.net^
||hs-scripts.com^
||hsadspixel.net^
||salesloft.com^
||outreach.io^
||segment.com^
||segment.io^
||cdn.segment.com^
||mixpanel.com^
||mxpnl.com^
||amplitude.com^
||ampltd.com^
||branch.io^
||app.link^
||appsflyer.com^
||kochava.com^
||tune.com^
||attributionapp.com^
||localytics.com^
||moengage.com^
||clevertap.com^
||pushwoosh.com^
||onesignal.com^
||hockeyapp.net^
||firebase-settings.crashlytics.com^
||crashlytics.com^
||cloudflareinsights.com^
||cloudflare.com/cdn-cgi/trace^
||abs.twimg.com^
||analytics.twitter.com^
||ads.twitter.com^
||static.ads-twitter.com^
||ads.facebook.com^
||connect.facebook.net^
||facebook.net^
||graph.facebook.com^
||pixel.facebook.com^
||an.facebook.com^
||platform-lookaside.fbsbx.com^
||ads.instagram.com^
||business.instagram.com^
||ads.tiktok.com^
||ads-eu.tiktok.com^
||analytics.tiktok.com^
||log.tiktok.com^
||ads.snapchat.com^
||sc-static.net^
||analytics.snapchat.com^
||tr.snapchat.com^
||ads.pinterest.com^
||ct.pinterest.com^
||log.pinterest.com^
||ads.reddit.com^
||events.reddit.com^
||pixel.redditmedia.com^
||redditstatic.com/aww-ads^
||ads.linkedin.com^
||px.ads.linkedin.com^
||snap.licdn.com^
||analytics.yahoo.com^
||ads.yahoo.com^
||gemini.yahoo.com^
||adtech.yahoo.com^
||beap.gemini.yahoo.com^
||yimg.com/pn^
||ads.ymail.com^
||ad.nl.doubleclick.net^
||adserver.yahoo.com^
||advertising.bbc.co.uk^
||ads.bbc.co.uk^
||ads.bbcnews.co.uk^
||metering.bbc.co.uk^
||analytics.bbc.co.uk^
||a8310.aws.cbc.ca^
||cbc.radio-canada.ca/ads^
||adtech.ca^
||ads.cnn.com^
||analytics.cnn.com^
||cdn.cnn.com/ads^
||ads.theguardian.com^
||ophan.theguardian.com^
||ophan.co.uk^
||ads.nytimes.com^
||mw.nyt.com^
||et.nyt.com^
||analytics.nytimes.com^
||ads.wsj.net^
||ads.washingtonpost.com^
||ads.forbes.com^
||images.forbes.com^
||adunit.forbes.com^
||ads.bloomberg.com^
||cdn.bloomberg.com/ads^
||ads.reuters.com^
||analytics.reuters.com^
||ads.reutersmedia.net^
||ads.sites.reuters.com^
||adfox.ru^
||ad.mail.ru^
||r.mradx.net^
||top-fwz1.mail.ru^
||st-top-fwz1.mail.ru^
||adplanner.ru^
||adsrvmedia.ru^
||adpone.com^
||am15.net^
||advg.agency^
||8coins.net^
||adnxs.com^
||bannerbank.ru^
||adriver.ru^
||adriver.ru^
||smi2.ru^
||adfox.ru^
||adspotmedia.ru^
||adhands.ru^
||adsensemaster.ru^
||adlibrium.ru^
||adlabs-media.ru^
||admitad.com^
||admitadconnect.com^
||adskeeper.com^
||target.my.com^
||ad.mail.ru^
||banner04.ru^
||advego.ru^
||advmaker.ru^
||adsensemaster.ru^
||bestzarabotok.com^
||bigmir.net^
||c8.net.ua^
||adriver.ru^
||adfox.ru^
||partner.2gis.ru^
||ads.drom.ru^
||auto.drom.ru/banner^
||banner.drom.ru^
||ads.auto.ru^
||auto.ru/ads^
||ads.avito.ru^
||avito.ru/ads^
||counter.avito.ru^
||avito.ru/stas^
||m.avito.ru/ads^
||advertising.kaspersky.com^
||kdentro.ru^
||an.yandex.ru^
||ads.yandex.ru^
||advertising.yandex.ru^
||yandexadexchange.net^
||an.yandex.ru^
||mc.yandex.ru^
||metrika.yandex.ru^
||adfox.ru^
||banner.kiev.ua^
||adriver.ru^
||adfox.ru^
||advideo.ru^
||videoadex.ru^
||adriver.ru^
||videoclick.ru^
||1l-hit.mail.ru^
||r.mradx.net^
||ad.mail.ru^
||top-fwz1.mail.ru^
||adfox.ru^
||exoclick.com^
||exdynsrv.com^
||exosrv.com^
||realsrv.com^
||smi2.ru^
||advsmi.ru^
||adfox.ru^
||adriver.ru^
||moat.com^
||moatads.com^
||adsafeprotected.com^
||adsafety.net^
||doubleverify.com^
||iad.an.ly^
||iadprod.an.ly^
||ads.yahoo.com^
||tagmanager.google.com^
||google-analytics.com^
||analytics.google.com^
||ssl.google-analytics.com^
||www.google-analytics.com^
||googletagmanager.com^
||ads.youtube.com^
||ads.yt^
||doubleclick.net^
||ad.doubleclick.net^
||stats.g.doubleclick.net^
||fls.doubleclick.net^
||googleads.g.doubleclick.net^
||mediaservice.bgr.com^
||clarity.ms^
||c.bing.com^
||bat.bing.com^
||adntra.com^
||adserwer.cz^
||adform.net^
||adfox.ru^
||adition.com^
||adnologies.com^
||adplan-ds.com^
||adpole.pl^
||adreactor.com^
||adroll.com^
||ads2srv.com^
||adspring.to^
||adsyst.biz^
||adtechus.com^
||adtruth.com^
||adverline.com^
||adverticum.net^
||advertserve.com^
||adxpower.com^
||afcyhf.com^
||affiligay.com^
||affiliate.futureagent.club^
||adfox.ru^
||admatic.com.tr^
||adnetde.com^
||adservia.com^
||adservme.com^
||adspeed.net^
||adspdbl.com^
||adspirit.net^
||adsrvmedia.net^
||adtegg.com^
||adthink.com^
||adtify.pl^
||adtrgt.com^
||adtruth.com^
||adx.adriver.ru^
||adxcore.com^
||adjuggler.net^
||adzerk.net^
||adziff.com^
||adzly.com^
||adzon.biz^
||affileads.co^
||affinity.com^
||agkn.com^
||aidata.io^
||aimatch.com^
||akqmp.io^
||amung.us^
||amungus.pro^
||anyclip.com^
||audience2media.com^
||audienceinsights.net^
||audtd.com^
||auto-track.ru^
||bannerbank.ru^
||bannerconnect.net^
||bannerflow.com^
||bannerhost.ru^
||bannerlot.com^
||bannersnack.com^
||bannersontxt.com^
||beacon.adsaco.net^
||beacon.fltms.com^
||betweendigital.com^
||bidtheatre.com^
||bidr.io^
||bidsystem.com^
||bidswitch.net^
||bidtheatre.com^
||bigbangmedia.com^
||bitmedia.io^
||bluestreak.com^
||boomads.com^
||brainlyads.com^
||brand-display.com^
||branch.io^
||brightads.openx.net^
||brsrvr.com^
||btrll.com^
||buildinternet.com^
||bullads.net^
||burstmedia.com^
||burstnet.com^
||business5.tv^
||buyflow.net^
||buysellads.com^
||buzzadexchange.com^
||buzzcity.com^
||byspot.com^
||c3tag.com^
||captifymedia.com^
||caraytech.com.ar^
||casalemedia.com^
||cdncache.net^
||cdnpk.com^
||checkm8.com^
||checkmystats.com.au^
||chitika.com^
||chitika.net^
||cible.biz^
||cityads.net^
||cityadspix.com^
||cjt1.net^
||claritytag.net^
||clearrequest.com^
||clickbooth.com^
||clickbank.net^
||clickcash.com^
||clickfuse.com^
||clickhype.com^
||clickintext.net^
||clickioads.com^
||clickmenia.com^
||clicksor.com^
||clicksor.net^
||clicktripda.com^
||clicmanager.fr^
||clnk.me^
||cltg.net^
||cltrda.com^
||clrstm.com^
||cndirectads.com^
||codeonclick.com^
||coinzilla.com^
||collective-media.net^
||colads.net^
||conjure-systems.com^
||connextra.com^
||contadd.com^
||contextweb.com^
||conversionjam.com^
||convergedirect.com^
||convergeselect.com^
||coolsprite.net^
||coostudio.com^
||cpx.to^
||cpxinteractive.com^
||crwdcntrl.net^
||ctasnet.com^
||ctnetwork.hu^
||cubics.com^
||cuscusbell.com^
||d.adroll.com^
||d.line-scdn.net^
||d1.n1d2.com^
||d2.n1d2.com^
||d3.n1d2.com^
||dataadm.com^
||datasrvr.com^
||de17a.com^
||dealsa.com^
||dealdiggs.com^
||dgitl.net^
||dgmaustralia.com^
||dgmaxinteractive.com^
||diaadv.com^
||digital-metric.com^
||digitalriver.com^
||digitick.com^
||directaclean.com^
||directtrack.com^
||dotandmedia.com^
||dotomi.com^
||doublepimpads.com^
||doubleclick.com^
||doubleclick.net^
||dpbolvw.net^
||drowle.com^
||dsnextgen.com^
||dsnr-affi.net^
||dt00.net^
||dulfy.net^
||e-planning.net^
||e-viral.com^
||ebannertraffic.com^
||ebayads.com^
||ebayobjects.com.au^
||edgeadsru.com^
||ednplus.com^
||effectiveads.in^
||effectiveitemlink.com^
||effectivegate.com^
||element-hk.com^
||em2n.net^
||emediate.eu^
||emediate.se^
||emjcd.com^
||emsservice.de^
||engago.com^
||enlivenhq.com^
||enterads.com^
||eorezo.com^
||ero-advertising.com^
||etology.com^
||euroclick.com^
||exoclick.com^
||exosrv.com^
||extensionfactory.com^
||eyereturn.com^
||eyewondernetwork.com^
||fairads.net^
||falkag.net^
||fastclick.com^
||fastclick.net^
||fbcashtrix.com^
||featurelink.com^
||filetarget.com^
||firstlightera.com^
||fmpub.net^
||forrestersurveys.com^
||freebiegb.co.uk^
||freedom.com^
||friendlyduck.com^
||ftjcfx.com^
||ftv-publicite.fr^
||funkintheirwork.com^
||fuse-cloud.com^
||fusingsoup.com^
||fxix.net^
||g-cash.net^
||g.doubleclick.net^
||gahuu.com^
||galaxyads.net^
||gamecetera.com^
||gammasense.com^
||gannett.gcion.com^
||gate.vn^
||gator.com^
||gb-world.net^
||gemius.pl^
||geo-ID.net^
||geobanner.adultfriendfinder.com^
||geopromotions.com^
||getlinkfast.com^
||gimmeanother.com^
||gittigidiyor.com^
||globaltakeoff.net^
||glomera.glem-
||globe7sttag.com^
||gmads.net^
||go2jump.org^
||go2speed.org^
||goldbach.com^
||googleadservices.com^
||googlesyndication.com^
||googletagservices.com^
||gostats.cn^
||goweb.org^
||gpartmedia.com^
||gridlockparanoia.com^
||groovyadz.com^
||guardiandigitalcomparison.co.uk^
||gwallet.com^
||hanapong.com^
||harrenmedianetwork.com^
||hcfycknblf.com^
||hd-gemius.js^
||hello.bar^
||hexon.hu^
||hgpnetwork.com^
||hiplatform.com^
||hits.linkoffer.info^
||hogayan.com^
||horyzonmedia.com^
||hotkeys.com^
||hotlog.ru^
||houseads.somethingawful.com^
||hsoub.com^
||httpads.com^
||huaban.com^
||hubads.com^
||hype-ads.com^
||i-ads.adobe.com^
||iclivepeek.com^
||icdirect.com^
||identads.com^
||igads.pmohmq.com^
||im.ov.yahoo.com^
||imageads.canoe.ca^
||imagesrv.adition.com^
||imgpromo.easyrencontre.com^
||imho.ru^
||improvedigital.com^
||inad.com^
||inadshow.com^
||infectiousmedia.com^
||innity.com^
||insightexpress.com^
||insightexpressai.com^
||instantsupercharge.com^
||intelseek.com^
||intergi.com^
||intermrkts.ig.com^
||internetbrands.com^
||interpolls.com^
||ipro.com^
||iv.doubleclick.net^
||ivwbox.de^
||jdoqocy.com^
||juicyads.rocks^
||jumboaffiliates.com^
||justpremium.com^
||kaducol.com^
||kargo.com^
||kavanga.ru^
||kemamad.com^
||kerg.net^
||ketoo.com^
||keva.ru^
||kiosked.com^
||kitnmedia.com^
||kixer.com^
||klipmart.com^
||knorexpd.com^
||knuddels.net^
||kontera.com^
||kqzyfj.com^
||krxd.net^
||l2wdxz6t.com^
||lakequincy.com^
||lanistaads.com^
||layer-ad.org^
||layerloop.com^
||lduhtrp.net^
||leadboltads.net^
||leadplace.fr^
||leadzilla.com^
||leftad.com^
||letsgoshopping.tk^
||lifeadvertising.com^
||liftdna.com^
||ligatus.com^
||lightningcast.net^
||lijit.com^
||linkadd.de^
||linkbuddies.com^
||linkexchange.ru^
||linkfeedback.com^
||linkhood.biz^
||linkprice.com^
||linkpulse.com^
||linkredirector.com^
||liqwid.net^
||liveadserver.net^
||liveuniversenetwork.com^
||loading-delivery.com^
||localytics.com^
||lockfad.com^
||log4j.org^
||logs134.atlogs.com^
||looneynet.com^
||loopme.me^
||lootasaurous.com^
||loply.com^
||lol-qc.cn^
||lowstratesonet.info^
||lucidmedia.com^
||lycadigital.com^
||madisonlogic.com^
||madmaniak.com^
||madserving.com^
||magnetic.com^
||mainadv.com^
||makeitone.com^
||market-fly.net^
||marketing.888.com^
||martiniadnetwork.com^
||masterads.org^
||matchnet.nl^
||maxiadv.com^
||maxbounty.com^
||maxonmedia.com^
||mb01.com^
||mbn.com.ua^
||mdialog.com^
||media-adrunner.net^
||media.net^
||media2020.eu^
||mediacate.com^
||mediacharger.com^
||mediacpm.pl^
||mediaforge.com^
||mediagrid.com^
||mediaimages.redpickle.com^
||mediaiqdigital.com^
||medialets.com^
||mediamath.com^
||mediamayhemcompletesolution.com^
||median.net^
||mediaplex.com^
||mediarct.com^
||mediatags.com^
||mediatraffic.com^
||mediavantage.de^
||mediavine.com^
||medleyads.com^
||meetic-partners.com^
||megaban.com.ua^
||mercuras.com^
||metaffiliation.com^
||metanetwork.com^
||methodcash.com^
||metricsdirect.com^
||microad.jp^
||microad.net^
||mirago.com^
||mirago.de^
||miva.com^
||mixpo.com^
||mkt51.net^
||mmnetwork.mobi^
||moat.com^
||moatads.com^
||mobanet.net^
||mobfox.com^
||mobile-ads.biz^
||mobitydirect.com^
||mochila.com^
||moceanmobile.com^
||mojiva.com^
||monetate.net^
||monetizer.co.kr^
||mookie1.com^
||morehitserver.com^
||mpora.com^
||mradx.net^
||mrskincash.com^
||msads.net^
||myadb.net^
||myfitnesspalads.com^
||mynewtab.com^
||mythings.com^
||myvads.com^
||n69.com^
||nai-systems.ru^
||narrowcastmedia.com^
||nav-links.com^
||nbcudigitaladvertising.com^
||ndparking.com^
||net3media.com^
||netavenir.com^
||netindonesia.net^
||netliker.net^
||netseer.com^
||netshelter.net^
||network.newfreemovies.com^
||newgentraffic.com^
||newsadstream.com^
||newstarads.com^
||newtention.net^
||nexac.com^
||ngbn.net^
||nitropay.com^
||nlpcaptcha.in^
||nobsnetwork.com^
||northmay.com^
||nowlooking.net^
||nspmotion.com^
||nuggad.net^
||nuseek.com^
||nvadn.com^
||nvero.net^
||nyadmcncserve-05y06a.com^
||obeus.com^
||obp1.com^
||objectopoly.com^
||ohmcasting.com^
||oinadserve.com^
||onclasrv.com^
||onead.tw^
||onenetworkdirect.com^
||onenetworkdirect.net^
||onerrorative.com^
||onlinesalesleads.com^
||onlineservles.com^
||onsitemarketplace.net^
||open.admotion.com^
||openx.net^
||openx2.net^
||opienetwork.com^
||opt-e.net^
||optimalcams.com^
||optimatic.com^
||optmd.com^
||oratios.net^
||othersonline.com^
||out-ads.ru^
||overkillrc.info^
||overture.com^
||oxado.com^
||p-advg.com^
||pagead2.googlesyndication.com^
||paid4ads.de^
||paidonresults.net^
||paislee.net^
||popads.net^
||popcash.net^
||popmyads.com^
||poponclick.com^
||popunder.ru^
||popupdomination.com^
||popuptraffic.com^
||precisionclick.com^
||predictad.com^
||pressly.com^
||pro-advertising.com^
||pro-market.net^
||proadsdirect.com^
||probannerswap.com^
||profitpeelers.com^
||projectwonderful.com^
||propellerads.com^
||propelleradsystem.com^
||popunder.net^
||psCLICKs.com^
||pub-fit.com^
||pub2.gold.mockery.net^
||pubmatic.com^
||pulse360.com^
||qksrv.net^
||qksz.net^
||qnsr.com^
||quantserve.com^
||quenserscoad.com^
||quintura.com^
||radiumroot.com^
||radiusmarketing.com^
||rainbowtzx.com^
||rapt.com^
||ratefun.net^
||ratingruby.com^
||reachjunction.com^
||reactx.com^
||readytotravel.com^
||realmatch.com^
||realmedia.com^
||recomendedsite.com^
||redolphin.com^
||redpineapplemedia.com^
||reklamport.com^
||reklamz.com^
||resultlinks.com^
||retargeter.com^
||revenuegiants.com^
||revenuemantra.com^
||revfusion.net^
||revmob.com^
||revrtb.com^
||rhythmxchange.com^
||richmedia247.com^
||richwebmedia.com^
||ringtoneme.com^
||ringtonepartner.com^
||rlcdn.com^
||rotacat.com^
||rovion.com^
||roxyaffiliates.com^
||rtbidder.net^
||rtbmediaad.com^
||rtbpop.com^
||rubiconproject.com^
||rummyaffiliates.com^
||run-syndicate.com^
||s5dura2.com^
||sa-iadsn.com^
||safescripts.com^
||samrack.com^
||sape.ru^
||sapientmdsd.com^
||sascdn.com^
||saymedia.com^
||scanscout.com^
||scratchaff.com^
||searchagency.ru^
||searchdealt.com^
||searchmyadvertising.com^
||secure-softwarekeeper.com^
||selfadvertiser.com^
||sensismediasmart.com.au^
||seo-ads.net^
||sepo.com^
||servedby-buysellads.com^
||servedbyadbutler.com^
||service.footprint.net^
||servid.com^
||sevenads.net^
||sexmoney.com^
||shareasale.com^
||sharegods.com^
||sherbonix.com^
||shinepp.com^
||shovetechnology.com^
||sitemaji.com^
||skaplink.com^
||skimresources.com^
||slimspot.com^
||smartadserver.com^
||smartcontext.pl^
||smarttarget.ru^
||smdp.pix.house^
||smiling.info^
||snaking.net^
||socialbirth.com^
||socialhirose.com^
||sovrn.com^
||spadynamics.com^
||sparkstudios.com^
||specialoffers.txt^
||specificclick.net^
||specificmedia.com^
||spectate.com^
||spinbox.net^
||spiral.media^
||splashclix.com^
||sponsorads.de^
||sponsorsus.com^
||spotexchange.com^
||spoty.tv^
||sprinkeljs.com^
||srtk.net^
||sta-ads.com^
||stargamesaffiliate.com^
||stat.clic2site.com^
||statsmylife.com^
||statted.net^
||steelhousemedia.com^
||stickyadstv.com^
||storebanner.net^
||streamate.com^
||strikead.com^
||strongmail.net^
||studiosplr.com^
||subnetworks.ru^
||suggesttool.com^
||summitmedia.us^
||supersonicads.com^
||supplyframe.com^
||swissads.net^
||switchadhub.com^
||symbiosting.com^
||tacoda.net^
||taggify.net^
||tangibleteam.com^
||tapad.com^
||tapgenius.com^
||targetbusca.com^
||targetnet.com^
||targetpoint.com^
||tb.ask.com^
||td502lineint.com^
||teasernet.com^
||technicmedia.com^
||techsailor.com^
||teenrevenue.com^
||tellapart.com^
||text-link-ads.com^
||textonlyads.com^
||tfag.de^
||theads.me^
||theadgatecompany.com^
||therichkids.com^
||thinglink.com^
||thinknonsense.com^
||tidaltv.com^
||tinypic.com/advert^
||tiser.com.au^
||tlvmedia.com^
||todacell.com^
||tonefuse.com^
||tonehive.com^
||top10-newresources.com^
||topaffnetworks.com^
||topsite.hu^
||topsolutions.me^
||total-media.net^
||tqlkg.com^
||traceadcentral.com^
||track.corbmailer.com^
||trackalyzer.com^
||tracking101.com^
||trackingsoft.com^
||trackthatad.com^
||tradedoubler.com^
||trafficfactory.biz^
||traffichaus.com^
||trafficjunky.net^
||trafficrevenue.net^
||trafficspaces.net^
||trafficstars.com^
||trafficstrategies.com^
||trafficwap.com^
||trafficzbssystem.com^
||travelscream.com^
||travidia.com^
||triadmedianetwork.com^
||tribalfusion.com^
||trigami.com^
||trker.com^
||tubemogul.com^
||tucows.com/ads^
||twinplan.com^
||twittad.com^
||tyroo.com^
||udmserve.net^
||ukbanners.com^
||unanimous.co^
||unrulymedia.com^
||upads.info^
||urtbk.com^
||usenetjunction.com^
||usleep.net^
||utarget.co.uk^
||v.fwmrm.net^
||valuead.com^
||valueclick.net^
||valuecommerce.com^
||valuepeer.com^
||vapedia.com^
||vdopia.com^
||veeseek.com^
||velmedia.net^
||vemba.com^
||veridian.cc^
||versetime.com^
||vibrantmedia.com^
||videoclick.ru^
||videohub.com^
||videologygroup.com^
||viewablemedia.net^
||viglink.com^
||viralcpm.com^
||viralninjas.com^
||vis Media^
||visiblemeasures.com^
||visitdetails.com^
||vitalads.net^
||vpico.com^
||vs20060817.com^
||vsservers.net^
||w4.com^
||wahoha.com^
||warezexo.net^
||webads.co.nz^
||webads.nl^
||webadvertise123.com^
||webgains.com^
||webmediaengines.com^
||weborama.com^
||weborama.fr^
||webseeds.com^
||webtraffic.ttinet.com^
||wgreatdream.com^
||widgetcentral.com^
||wipub.com^
||wonder-pl.com^
||worldwide-pixel.com^
||wsod2.com^
||wwbn.com^
||www.promoter.cc^
||wwwad.us^
||wwwpager.net^
||x.mochiads.com^
||x4300tac.com^
||xads.zedo.com^
||xcelltechout.com^
||xertive.com^
||xgraphs.net^
||xl415.com^
||xponsive.com^
||xtendmedia.com^
||xxxbanana.com^
||xxxvipporno.com^
||yieldads.com^
||yieldbuild.com^
||yieldmanager.com^
||yieldoptimizer.com^
||yieldselect.com^
||yocade.com^
||yoggrt.com^
||yottos.com^
||youlucky.net^
||youmi.net^
||yu12.net^
||zedo.com^
||zangocash.com^
||zapunited.com^
||zde-engage.com^
||zeads.com^
||zedo.com^
||zenhpx.com^
||zferral.com^
||zmedia.com^
||zmedia.ziffdavis.com^
||zypenetwork.com^
||banner${'$'}third-party
||banners${'$'}third-party
/adbanner.
/adbanners/
/adframe.
/adframes/
/adimage.
/adimages/
/adpics/
/adpicture/
/adsbanner/
/adsbanner2/
/adsframe/
/adsimage/
/adsimages/
/adspic/
/adview.
/adviews/
/banner468.
/banner728.
/banner468x60.
/banner728x90.
/bannerscript.
/banner-ad-
/banner_ad.
/banner_ads.
/bannerad-
/bannerads/
/bigbanner.
/bottombanner.
/bottomads/
/topbanner.
/topads/
-300x250.
-468x60.
-728x90.
_300x250.
_468x60.
_728x90.
/120x600_
/160x600_
/300x250_
/336x280_
/468x60_
/728x90_
/adsbygoogle.
/pagead/imgad?
/showad?
/showads.
/show_ads.
/show_banner.
/show_banner_
/sidebanner/
/skyscraper.
/skyscraper160.
/square-ads.
/squareads.
/textad.
/textads/
/top-ad-
/top_ad.
/top_ads.
/topad.
/topads.
!/ad.
!/ads.
/advert/
/adverts/
/advertisement.
/advertisements/
/advertising/
/adv/
/ads/
/ads1/
/ads2/
/ads3/
/popup.
/popunder.
/pop_up.
/pop_ads.
/exit-popup/
/interstitial-ad/
/preroll.
/pre-roll-
/preload-ads.
/premium_ad_
/promoads/
/promo/ad/
/promo/banners/
/promos/ad.
/rionativeads.
/rmenu-ad.
/rotads/
/rotatingads/
/rotban.
/rsads/
/rsadsz/
/sas-banner/
/script/ad_
/script/ads/
/scripts/ad_
/scrollads/
/selads/
/serverads/
/servpopup.
/shared/ads/
/shifted-ad_
/show-ad_
/showad_
/showads_
/showban_
/sidebar-ad/
/sidebar_ad_
/sidebarads/
/site_ads/
/siteads/
/skinad/
/skinads/
/skyad.
/skyad_
/slaverybanner.
/slider-ad/
/slider_ad/
/slideinad.
/slimbanner/
/smallad.
/smallads/
/smartad.
/smartads/
/snapshot_ad_
/sponsorad/
/square-ad.
/square-ads_
/static/ad_
/static/ads/
/staticad_
/sticky-ad.
/stickyad_
/streamad/
/studioads/
/swf/ad_
/swf/ads/
/swfads.
/sys/ad_
/sys/ads_
/system/ad_
/system/ads/
/taboola.
/taboolanews/
/tame.php?${'$'}
/taoads/
/tdads/
/teads/
/tempad_
/textad_
/textlinkads.
/thankyou-ad_
/theads.
/thrumybanner/
/tii ads/
/tiser/
/tizers/
/toads/
/top-hb-
/top_ad_
/topad_
/trackad_
/tracked_ad.
/tracker/ad.
/trafficads/
/tremorad.
/tttads/
/ttyads/
/tvxad.
/txad.
/tyoeads.
/ugoads_
/uk-ad-
/ukads/
/unad/
/underad_
/uniads.
/upads.
/updateads.
/userbanners/
/vad/
/valueclick.
/vertads/
/verticalbanner/
/viedeo_ad.
/video-ad-
/video_ad_
/videoads.
/videoads/
/videobanner.
/videobanners/
/view-ad-
/viewad.
/viewbanner.
/wads.
/wadstats.
/wbadge/
/wbadults/
/wbanner/
/web-ad_
/webad.
/webadimg.
/webads.
/webads/
/webadvert/
/webbanner/
/webbanner03/
/webbanner04/
/webbanners/
/webmasterads/
/welcomead.
/welcomeadroot/
/wgads.
/wheread.
/widget/ads/
/wipeads/
/wired/ads/
/wmads.
/wpadclick.
/wpads_
/wpxads/
/wt-ad-
/x-ad/
/xads.
/xads/
/xadsz.
/xboxads_
/xclipad.
/xtremead.
/xxbanner/
/xxxad_
/yahoo-ads/
/yahooad.
/yahooads.
/yahooads2/
/yadvs.
/yallax.
/yardirect/
/ybgads.
/yieldads.
/ylx-ad_
/ymailad.
/ynad.
/yolder-ad_
/yourad.
/yourads/
/yscga.
/yumenetworks.
/zadframe/
/zbanner/
/zeads_
/zone-ad.
/zoneads/
~adarea|
~adframe|
~adpartner
~adserver
~analytics
~click_track
~pagead
/count?${'$'}third-party
/counter?${'$'}third-party
/pixel?${'$'}third-party
/track?${'$'}third-party
/tracker?${'$'}third-party
/beacon?${'$'}third-party
/stat.${'$'}third-party
/stats.${'$'}third-party
/log?${'$'}third-party
/logger?${'$'}third-party
/analytics.${'$'}third-party
/telemetry.${'$'}third-party
/metric.${'$'}third-party
/metrics.${'$'}third-party
/pixel.${'$'}third-party
/tracking.${'$'}third-party
/collect?${'$'}third-party
/cdn-cgi/bird^
||googletagmanager.com/gtag/js^
||google-analytics.com/analytics.js^
||google-analytics.com/ga.js^
||google-analytics.com/collect^
||ssl.google-analytics.com^
||stats.g.doubleclick.net^
||mc.yandex.ru/watch^
||mc.yandex.ru/webvisor^
||metrika.yandex.ru^
||an.yandex.ru^
||top-fwz1.mail.ru/counter^
||ad.mail.ru^
||c.bing.com^
||clarity.ms^
||hm.baidu.com^
||stats.wp.com^
||pixel.wp.com^
||stats.wordpress.com^
||log.scribd.com^
||podbean.com/track^
||seedtrack.ru^
||onthe.io^
||api.mixpanel.com^
||cdn.mxpnl.com^
||cdn.segment.com^
||api.segment.io^
||a.amplitude.com^
||api2.amplitude.com^
||heapanalytics.com^
||cdn.heapanalytics.com^
||inspectlet.com^
||mouseflow.com^
||luckyorange.com^
||siteintercept.qualtrics.com^
||cdn.qualtrics.com^
||fullstory.com^
||fs.fullstory.com^
||hotjar.com^
||static.hotjar.com^
||script.hotjar.com^
||in.hotjar.com^
||crazyegg.com^
||script.crazyegg.com^
||track.dictionary.com^
||tags.crwdcntrl.net^
||partner.googleadservices.com^
||www.googleadservices.com^
||doubleclick.net^
||adservice.google.com^
||pagead2.googlesyndication.com/pagead/${'$'}third-party
||tpc.googlesyndication.com^
||s0.2mdn.net^
||s1.2mdn.net^
||googleads.g.doubleclick.net^
||ad.doubleclick.net^
||adunit/adtech^
||adtechus.com^
||advertising.com^
||adsonar.com^
||adtech.de^
||adtechus.net^
||adsafetyprotection.com^
||popunder.${'$'}third-party
/popunder${'$'}third-party
/pop-up${'$'}third-party
/popup${'$'}third-party
/exit.${'$'}domain=~exit-intent.com|~exitintel.com${'$'}third-party
/preroll${'$'}third-party
/pre-roll${'$'}third-party
||ads.${'$'}third-party
||ads-${'$'}third-party
||ad-_${'$'}third-party
||_ads.${'$'}third-party
||-ads.${'$'}third-party
##.ad-slot
##.ad-banner
##.ad-container
##.ad-wrapper
##.ad-unit
##.ad-zone
##.advertisement
##.advertisements
##.adsbygoogle
##.ads-content
##.ads-layout
##.ads-area
##.ads-box
##.ads-container
##.ads-frame
##.ads-header
##.ads-holder
##.ads-inline
##.ads-label
##.ads-loaded
##.ads-placeholder
##.ads-renew
##.ads-section
##.ads-sidebar
##.ads-title
##.ads-widget
##.adsense
##.adsbox
##.adslot
##.adv-banner
##.advert
##.advertise
##.advertising-banner
##.banner-ad
##.banner-ads
##.banner468
##.banner728
##.bottom-ad
##.bottom-banner
##.box-ad
##.column-ad
##.dfp-ad
##.dfp-ad-unit
##.header-ad
##.header-banner
##.home-ad
##.inline-ad
##.leaderboard-ad
##.m-ad
##.mobile-ad
##.promo-ad
##.reklama
##.reklama-banner
##.sidebar-ad
##.sponsored
##.sponsor-ad
##.top-ad
##.top-banner
##.video-ads
##.yandex-ad
##.adv_
##._ads
##.ad_1
##.ad_2
##.ad_3
##.ad_300
##.ad_728
##.ads_1
##.ads_2
##.adtext
##.adtitle
##.wa_ads
##.wmAds
##a[href*="/ad/"] > img
##a[href*="://ads."] > img
##a[href*="=adclick"] > img
##a[href*="adserver"] > img
##a[href*="adnetwork"] > img
##a[href*="doubleclick"] > img
##a[href*="googlesyndication"] > img
##a[href^="https://ad.doubleclick.net/"] > img
##div[id^="div-gpt-ad"]
##div[id^="google_ads"]
##div[id^="yandex_ad"]
##div[class*="advertise"]
##div[class^="adsby"]
##iframe[src*="doubleclick"]
##iframe[src*="googlesyndication"]
##iframe[src*="/ads/"]
##iframe[src*="adfox"]
##iframe[id^="google_ads"]
##ins.adsbygoogle
"""
}
