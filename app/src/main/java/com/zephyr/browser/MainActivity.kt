package com.zephyr.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.db.BookmarkEntity
import com.zephyr.browser.db.PasswordEntity
import com.zephyr.browser.db.SitePermissionEntity
import com.zephyr.browser.ui.Anim
import com.zephyr.browser.ui.HomePageView
import com.zephyr.browser.ui.MenuSheets
import com.zephyr.browser.ui.SuggestionsAdapter
import com.zephyr.browser.ui.TabSwitcherView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * v1.7.80: is the browser the window the user is actually looking at?
         *
         * Read from the assistant's IO threads to decide whether a finished
         * answer needs a status-bar notification, so it is deliberately a
         * plain volatile flag rather than anything that needs a lifecycle
         * owner or a main-thread hop.
         */
        @Volatile
        @JvmStatic
        var onScreen: Boolean = false
            private set

        const val EXTRA_ACTION = "zephyr.action"
        const val EXTRA_ENGINE = "engine"
        const val VOICE_RC = 501
        const val FILE_RC = 502
        const val AUTH_RC = 701   // v1.4.2 FIX 7: keyguard/biometric gate for login autofill
        const val CAMERA_PERM_RC = 503
        const val GEO_PERM_RC = 504
        const val MIC_PERM_RC = 505
        const val STARTUP_PERMS_RC = 717   // v1.7.1 FIX 3: one-time storage/media+notifications ask
        const val AI_ATTACH_RC = 730       // v1.7.25: photo/document picker for the AI chat
        const val AI_VOICE_RC = 731        // v1.7.26: dictation into the AI composer
        const val CAMERA_AI_RC = 733       // v1.7.76: camera as an input to the assistant
    }

    private val main = Handler(Looper.getMainLooper())
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    lateinit var tabs: TabManager
    private lateinit var browserClient: BrowserClient
    private lateinit var chromeClient: ChromeClient

    // views
    private lateinit var rootView: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var omniboxPill: LinearLayout
    private lateinit var omniboxInput: EditText
    private lateinit var btnSiteSecurity: ImageButton
    private lateinit var imgFavicon: ImageView
    private lateinit var toolbarSlots: LinearLayout
    private lateinit var tabCounter: TextView
    private lateinit var webContainer: FrameLayout
    private lateinit var homeView: HomePageView
    private lateinit var tabSwitcher: TabSwitcherView
    private lateinit var errorOverlay: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    // suggestions panel
    private lateinit var suggestionsPanel: LinearLayout
    private lateinit var suggestionsList: androidx.recyclerview.widget.RecyclerView
    private var suggestionsAdapter: SuggestionsAdapter? = null

    // v1.7.8: Chrome-style INLINE url bar (the row appears BELOW the omnibox in the
    // suggestions area — NOT a bottom sheet; user: «не меню sheets делай, а сверху
    // строкой ниже ссылки, там же где подсказки, кнопки слева от самой ссылки»)
    private var urlOverlayPanel: LinearLayout? = null
    private var urlOverlayScrim: View? = null

    // v1.7.8: sniffed video candidates with parsed quality labels.
    // v1.7.9: kind — 0 = video with sound (muxed/progressive or plain file),
    // 1 = adaptive video WITHOUT its audio track (muxed in on download),
    // 2 = audio-only stream (needsAudio stays false; it IS the audio).
    // v1.7.25b: + probed metadata (real resolution, duration, preview frame) and
    // the embed-resolver state for kind 3 rows.
    class SniffedMedia(
        val url: String, val label: String, val isHls: Boolean, val size: Long,
        val needsAudio: Boolean = false, var audioUrl: String? = null, val kind: Int = 0
    ) {
        var width: Int = 0
        var height: Int = 0
        var durationMs: Long = 0L
        var thumb: android.graphics.Bitmap? = null
        /** 0 = not probed, 1 = probing, 2 = done */
        var probeState: Int = 0
        /** kind 3 only: 0 = untouched, 1 = resolving, 2 = done, 3 = nothing found */
        var resolveState: Int = 0
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var lastGeoOrigin: String? = null
    private var lastGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var findIndex = 0
    private var findTotal = 0

    private val pageHistoryUrls = ArrayDeque<String>()

    // v1.7.6: omnibox swipe-down gesture state
    private var omniboxSwipeDownY = 0f
    private var omniboxSwipeDownX = 0f
    private var omniboxSwipeFired = false

    // v1.7.6: double-tap page jump (top ↔ bottom) state
    private var dtapDownX = 0f
    private var dtapDownY = 0f
    private var dtapLastUpAt = 0L
    private var dtapLastUpX = 0f
    private var dtapLastUpY = 0f
    private var dtapLastUpClean = false

    /** Theme generation seen at onCreate; if it changes while we are paused → recreate(). */
    private var appliedThemeId = 0

    /** v1.7.21: in-process delivery sink for the panels (bookmarks / history).
     *  Registered for as long as THIS instance is alive — see PendingOpen. */
    private val panelSink: (String, Boolean) -> Boolean =
        { url, newTab -> openFromPanelDirect(url, newTab) }

    // ---------------- lifecycle ----------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        appliedThemeId = Prefs.themeSnapshot(this)
        setTheme(Prefs.themeStyle())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // v1.7.1 FIX 3: polite one-time runtime permission ask (media/storage for
        // downloads + notifications on 13+). Flag-first → never re-asked; denial is
        // silent — the app fully works without these permissions.
        maybeAskStartupPermissions()
        AdBlockManager.init(this)
        FaviconLoader.init(this)
        DownloadEngine.init(this)
        tabs = TabManager(this)
        browserClient = BrowserClient(this)
        chromeClient = ChromeClient(this)
        bindViews()
        setupOmnibox()
        setupToolbarSlots()
        setupTabs()
        setupHome()
        setupTabSwitcher()
        setupFindBar()
        setupErrorOverlay()
        tabs.refreshDomainSettingsAsync()
        refreshSiteStylesAsync()   // v1.7.7: Stylus-like site styles cache
        com.zephyr.browser.proxy.ProxyHelper.applyFromPrefs(this)

        val restored = tabs.restoreSession()
        if (restored.isEmpty()) {
            tabs.newTab(Urls.DEFAULT_HOME, Prefs.alwaysIncognito, select = true)
        } else {
            // v1.7.7 FIX (user: «свернул на экране вкладок — вернулся на главную»):
            // the session now remembers WHICH tab was current and whether the tab
            // switcher was on screen — restore the exact state, not just tab #1.
            val sel = restored.firstOrNull { it.id == tabs.restoreCurrentId && !it.incognito }
                ?: restored.firstOrNull { !it.incognito }
                ?: restored.first()
            tabs.select(sel)
            if (tabs.restoreSwitcherOpen) {
                tabSwitcher.post { openTabSwitcher() }
            }
        }
        // v1.4.2 FIX 4: drop preview files of tabs that are no longer in the session
        tabs.sweepOrphanThumbnails()
        // v1.7.21: the panels can now hand URLs straight into this live instance
        // (same process — no intent system involved in carrying the payload).
        PendingOpen.sink = panelSink
        handleWidgetIntent(intent)
        maybeFirstRun()
        if (!Prefs.firstRunDone) Prefs.setFirstRunDone()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // v1.9.4: Android does NOT update getIntent() by itself here — the
        // activity keeps returning the intent it was first created with, for
        // the rest of its life. Several things read getIntent() in onResume
        // (the tasks panel's "new" flag, a workflow hand-off, the Files launch
        // probe), so without this they either never see a new intent's extras
        // or act on stale ones a second time. onResume always follows
        // onNewIntent, which is precisely when that matters.
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent == null) return
        // v1.7.54: tapping a reminder notification lands here — open Дела.
        // Consumed immediately so a later onResume does not re-open the panel.
        intent.getStringExtra("open_panel")?.let { p ->
            intent.removeExtra("open_panel")
            openPanel(p)
            return
        }
        val action = intent.getStringExtra(EXTRA_ACTION)
        val engineId = intent.getStringExtra(EXTRA_ENGINE) ?: Prefs.engineId
        when (action) {
            "voice" -> { startVoiceSearch(); return }
            "image" -> { openImageSearch(engineId); return }
            "ai" -> { openAiChat(engineId); return }
            // v1.7.7: QR scanner «найти в интернете» for plain scanned text
            "searchText" -> {
                val q = intent.getStringExtra("q") ?: return
                loadInCurrentTab(Urls.normalize(q) { s ->
                    SearchEngines.searchUrl(engineId, s) })
                return
            }
            "search" -> {
                if (tabs.current?.isHome == true) omniboxRequestFocus()
                return
            }
            // v1.7.91: the tasks widget. Tapping the card opens the list;
            // tapping the + goes straight to a new task, because "add one" is
            // what most people reach for the widget to do.
            "panel:tasks", "task:new" -> {
                startActivity(
                    Intent(this, com.zephyr.browser.panels.PanelsActivity::class.java)
                        .putExtra("panel", "tasks")
                        .putExtra("new", action == "task:new")
                )
                return
            }
            // v1.7.6: offline pages — open the saved archive (path) or the live URL
            "offline" -> {
                val path = intent.getStringExtra("path")
                val target = if (!path.isNullOrEmpty() && File(path).exists()) "file://$path"
                else intent.getStringExtra("url")?.takeIf { it.isNotEmpty() } ?: return
                tabs.newTab(target, Prefs.alwaysIncognito)
                navigate(target)
                return
            }
        }
        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { url ->
                // v1.7.7: shared site-style import — zephyr-style://import?d=domain&c=b64
                if (url.startsWith("zephyr-style://")) {
                    handleStyleImportIntent(url)
                    return
                }
                // v1.7.21: armored — a navigate() hiccup must never escape
                // onNewIntent (an uncaught exception here kills the process).
                try {
                    // v1.5.1: bookmarks/history send zephyr.newtab=true — a tap opens a
                    // dedicated tab instead of replacing whatever the current tab shows.
                    val wantNewTab = intent.getBooleanExtra("zephyr.newtab", false)
                    // v1.7.22 (user: «открывается, но экран белый»): navigate ONLY
                    // from a VISIBLE state. Every earlier fix still called
                    // newTab+navigate while MainActivity was STOPPED under the
                    // panels activity — on some shells a WebView created+loaded in
                    // a stopped activity never starts rendering → white tab. When
                    // not resumed: keep the pending record; onResume() — which
                    // ALWAYS follows onNewIntent/onCreate — performs the open
                    // while the window is actually on screen.
                    // v1.9.3 (user: «подтвердил email из почты — страница
                    // открылась в той же вкладке, где я был»).
                    //
                    // `zephyr.newtab` is set only by our OWN panels, so a link
                    // arriving from another app had it false and replaced
                    // whatever the user was looking at. In his case that was a
                    // registration form he had just filled in — gone, mid
                    // sign-up, with no way back to it.
                    //
                    // A link from another app is a new thing to look at, not a
                    // replacement for the current one; every browser opens it
                    // in its own tab. The exception is the home page, where
                    // there is nothing to preserve and a fresh tab would only
                    // leave an empty one behind.
                    //
                    // Decided HERE, before the resumed check, so the deferred
                    // path gets the same answer — otherwise a link that arrived
                    // while the window was covered would still overwrite the
                    // page, which is the same bug one branch later.
                    val onHome = tabs.current?.isHome != false
                    val openInNewTab = wantNewTab || !onHome
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        if (openInNewTab) {
                            tabs.newTab(url, Prefs.alwaysIncognito)
                            navigate(url)   // newTab() selects the tab; navigate drives the load
                        } else {
                            loadInCurrentTab(url)
                        }
                        PendingOpen.clear()
                    } else {
                        PendingOpen.put(url, openInNewTab)
                    }
                } catch (_: Throwable) {}
                // v1.7.20: the intent DID arrive — drop the PendingOpen backup so
                // onResume() does not open it a second time (only when consumed;
                // the deferred path needs the record intact).
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) PendingOpen.clear()
            }
        }
        // v1.7.22: the old no-payload else branch (consumePendingOpen() right in
        // onNewIntent) is GONE — onNewIntent fires while we are still covered by
        // the panel (Stopped), i.e. the exact state that produced white tabs.
        // onResume() → consumePendingOpen() owns ALL deferred opens now.
    }

    /** v1.7.21→22: the channel for panel taps (bookmarks / history).
     *  PendingOpen.deliver() calls this on the live instance, same process.
     *  v1.7.22 (user: «закладки не открываются / белая вкладка»): the open is
     *  performed ONLY from a RESUMED state. When the panel taps us we are
     *  virtually always STOPPED under the panels activity — creating the tab
     *  and loadUrl() right here was the common denominator of ALL previous
     *  attempts (1.7.20 intent, 1.7.21 in-process) and the likely white-tab
     *  source. Returns true ONLY when applied; false keeps the pending record
     *  and the panel falls back to the ACTION_VIEW intent — onResume() then
     *  opens the URL while the window is really on screen. */
    private fun openFromPanelDirect(url: String, newTab: Boolean): Boolean = try {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            performPanelOpen(url, newTab)
            PendingOpen.clear()  // delivered — onResume's fallback must no-op
            true
        } else {
            false                // keep the record — onResume() opens it visibly
        }
    } catch (_: Throwable) {
        false
    }

    /** v1.7.22: the ONE open routine for panel taps — always runs while the
     *  activity is resumed and visible (from openFromPanelDirect when already
     *  resumed, and from consumePendingOpen on the deferred path). */
    private fun performPanelOpen(url: String, newTab: Boolean) {
        closeTabSwitcher()   // the opened page must be VISIBLE, not behind the switcher
        if (newTab) {
            tabs.newTab(url, Prefs.alwaysIncognito)
            navigate(url)
        } else {
            loadInCurrentTab(url)
        }
    }

    /** v1.7.20: self-healing fallback for bookmark/history taps — see PendingOpen.
     *  v1.7.22: THE single deferred-open point — runs from onResume() only
     *  (activity resumed + visible). onNewIntent no longer consumes here, so
     *  the «open while stopped» white-tab path is unreachable by construction. */
    private fun consumePendingOpen() {
        val (url, newTab) = PendingOpen.take() ?: return
        try {
            performPanelOpen(url, newTab)
        } catch (_: Exception) {}
    }

    /** v1.7.7: zephyr-style://import?d=domain&c=urlsafe-base64-css — a style shared
     *  from another Zephyr opens the site-style editor pre-filled. */
    private fun handleStyleImportIntent(url: String) {
        try {
            val u = Uri.parse(url)
            val domain = u.getQueryParameter("d") ?: ""
            val cssB64 = u.getQueryParameter("c") ?: ""
            val css = String(android.util.Base64.decode(cssB64,
                android.util.Base64.URL_SAFE or android.util.Base64.DEFAULT), Charsets.UTF_8)
            if (domain.isNotEmpty() && css.isNotEmpty()) {
                MenuSheets.showSiteStyleEditor(this, domain, css)
            }
        } catch (_: Exception) {}
    }

    /**
     * Load into the tab the user is already on, creating one only when there
     * is none at all.
     *
     * v1.9.3: this was called `loadInNewOrCurrent`, which promised a behaviour
     * it never had — it opens no new tab. The name is why an external link was
     * routed through here in the first place, and why it quietly replaced the
     * page the user was on. External links now decide for themselves; this is
     * for navigations that genuinely belong in the current tab, such as a
     * voice search the user just spoke.
     */
    private fun loadInCurrentTab(url: String) {
        if (tabs.current == null) tabs.newTab(Urls.DEFAULT_HOME, Prefs.alwaysIncognito)
        navigate(url)
    }

    override fun onPause() {
        super.onPause()
        // v1.7.80: from here on, a finished answer is announced in the status
        // bar instead of only inside a window nobody is looking at
        onScreen = false
        main.removeCallbacks(nightTick)   // v1.7.6: stop the night-schedule minute ticker
        tabs.current?.let { tabs.captureThumbnail(it) }
        // v1.7.4 (user: "жму домой — звук ютуба играет дальше"): WebView.onPause()
        // suspends JS/layout but does NOT reliably stop HTML5 media — a YouTube
        // video kept playing audio in the background. Every tab's media elements
        // are paused from JS as well, and a fullscreen video is collapsed.
        pauseAllMedia()
        chromeClient.hideCustomViewIfShown()
        tabs.current?.webView?.onPause()
        tabs.saveSession()
        AdBlockManager.flushBlocked()
    }

    override fun onResume() {
        super.onResume()
        onScreen = true
        // the answer is in front of the user now; the status bar copy is stale
        com.zephyr.browser.ai.AiReplyNotice.clear(this)
        // v1.7.31 FIX (user: «включил тумблер, а панель как была сверху, так и
        // осталась»): the Settings switch called applyBottomSearchBarPosition on
        // `activity as? MainActivity` — but that `activity` IS SettingsActivity,
        // so the cast quietly returned null and nothing ever moved. The browser
        // re-applies the choice itself every time it comes back to the front.
        applyBottomSearchBarPosition()
        // v1.7.41: the assistant's browser tools act through this activity
        com.zephyr.browser.ai.AiTools.host = this
        // v1.7.20: bookmark/history tap whose ACTION_VIEW intent was lost by the
        // shell — the panel left a fresh PendingOpen record; open it now.
        // (The normal onNewIntent path already cleared the record — no double open.)
        consumePendingOpen()
        // v1.7.12: Files launch probe — Settings/Menu write files_launch_probe.txt
        // right before starting FilesActivity; the activity deletes it in its first
        // line. If the file is still here (and fresh), the launch DIED before any
        // FilesActivity code ran (class load / super.onCreate) — surface the saved
        // crash report so the user can send the exact trace in one tap.
        try {
            val probe = java.io.File(filesDir, "files_launch_probe.txt")
            if (probe.exists()) {
                val ts = probe.readText().trim().toLongOrNull()
                probe.delete()
                if (ts != null && System.currentTimeMillis() - ts < 120_000) {
                    val crash = try {
                        java.io.File(filesDir, "last_crash.txt").takeIf { it.exists() }?.readText().orEmpty()
                    } catch (_: Exception) { "" }
                    val body = getString(R.string.files_probe_body) +
                        (if (crash.isNotBlank()) "\n\n" + crash.lineSequence().take(6).joinToString("\n") else "")
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.files_probe_title)
                        .setMessage(body)
                        .setPositiveButton(R.string.crash_share_report) { _, _ -> shareCrashText(crash) }
                        .setNegativeButton(R.string.ok, null)
                        .show()
                }
            }
        } catch (_: Throwable) {}
        // theme changed in settings while we were paused → full re-theme
        val themeNow = Prefs.themeSnapshot(this)
        if (themeNow != appliedThemeId) {
            recreate()
            return
        }
        // v1.7.10 FIX 1d: on the home screen every WebView stays suspended —
        // resuming the one hidden behind the opaque home view would let the
        // auto-resuming players sound again (home = everything paused).
        if (homeView.visibility != View.VISIBLE) tabs.current?.webView?.onResume()
        // v1.4.1: toolbar buttons / background / home settings may have changed in Settings —
        // apply them without a restart (user report: unchecked buttons stayed visible)
        recreateUi()
        // v1.7.6: night-mode schedule — checked right now, then every minute while
        // the browser is on screen (app theme + page appearance flip automatically).
        applyNightSchedule()
        main.postDelayed(nightTick, 60_000)
        if (homeView.visibility == View.VISIBLE) homeView.reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        // v1.7.21: this instance is going away — panels must fall back to the
        // classic ACTION_VIEW intent until a new MainActivity registers.
        if (PendingOpen.sink === panelSink) PendingOpen.sink = null
        // v1.7.10 FIX 1b/3c: drop pending delayed runnables (the media second
        // pass and the fullscreen bar re-asserts) — the activity is going away.
        suspendMediaSecondPass?.let { main.removeCallbacks(it) }
        fullscreenReassert1?.let { main.removeCallbacks(it) }
        fullscreenReassert2?.let { main.removeCallbacks(it) }
        // v1.7.15: selection read-aloud engine — stop and release
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        // v1.7.17: drop the floating-player overlay (the window belongs to THIS
        // activity's content view; the WebViews die with the process anyway —
        // do NOT destroy them here, closeTab/TabManager own that).
        detachFloatingUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreenContainer.visibility == View.VISIBLE -> chromeClient.hideCustomViewIfShown()
            // v1.7.8: the inline url bar closes first (like Chrome's suggestion drop);
            // v1.7.9: it also leaves edit mode (omnibox focus + keyboard go away)
            urlOverlayPanel?.visibility == View.VISIBLE -> {
                hideUrlBarOverlay()
                if (omniboxInput.hasFocus()) omniboxInput.clearFocus()
            }
            suggestionsPanel.visibility == View.VISIBLE -> hideSuggestions()
            errorOverlay.visibility == View.VISIBLE -> { errorOverlay.visibility = View.GONE }
            tabSwitcher.visibility == View.VISIBLE -> closeTabSwitcher()
            findBar.visibility == View.VISIBLE -> hideFindBar()
            else -> {
                val wv = tabs.current?.webView
                if (wv != null && wv.canGoBack()) {
                    // v1.7.10 FIX 1c: back from home returns to the page behind it
                    // WITHOUT attachPageView — resume a WebView the home screen
                    // left suspended, or the returning page would be frozen.
                    try { wv.onResume() } catch (_: Exception) {}
                    wv.goBack()
                }
                else if (tabs.current?.isHome == true) {
                    if (tabs.tabs.size > 1) tabs.closeTab(tabs.current!!)
                    else finish()
                } else goHome()
            }
        }
    }

    // ---------------- view wiring ----------------

    private fun bindViews() {
        rootView = findViewById(R.id.rootView)
        topBar = findViewById(R.id.topBar)
        omniboxPill = findViewById(R.id.omniboxPill)
        omniboxInput = findViewById(R.id.omniboxInput)
        btnSiteSecurity = findViewById(R.id.btnSiteSecurity)
        imgFavicon = findViewById(R.id.imgFavicon)
        toolbarSlots = findViewById(R.id.toolbarSlots)
        tabCounter = findViewById(R.id.tabCounter)
        webContainer = findViewById(R.id.webContainer)
        homeView = findViewById(R.id.homeView)
        tabSwitcher = findViewById(R.id.tabSwitcher)
        errorOverlay = findViewById(R.id.errorOverlay)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { MenuSheets.showMainMenu(this) }
        tabCounter.setOnClickListener { openTabSwitcher() }
        btnSiteSecurity.setOnClickListener { showSiteInfo() }
        imgFavicon.setOnClickListener { showSiteInfo() }
        imgFavicon.isLongClickable = false
        btnSiteSecurity.isLongClickable = false

        // v1.7.5: edge-swipe navigation — floating arrow indicator over the content
        setupEdgeGestureIndicator()
        try { requestEdgeGestureExclusion() } catch (_: Exception) {}
        // v1.7.10 FIX 2d (user: «жесты назад/вперёд то работают то нет»): the
        // exclusion rects were only re-claimed on window focus — a rotation or
        // resize silently dropped them until the next focus change, so the
        // system back gesture ate the strips again. Re-claim on every root
        // layout pass (API 29+ only, guarded).
        rootView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try { requestEdgeGestureExclusion() } catch (_: Exception) {}
            }
        }

        // suggestions panel (dropdown overlay directly below the top bar)
        suggestionsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 12f
            // themed rounded background (was fully transparent over page content)
            background = android.graphics.drawable.GradientDrawable().apply {
                val ta = theme.obtainStyledAttributes(intArrayOf(R.attr.zToolbar))
                setColor(ta.getColor(0, 0xFF202124.toInt()))
                ta.recycle()
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
        }
        suggestionsList = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = SuggestionsAdapter(this@MainActivity).also { suggestionsAdapter = it }
        }
        suggestionsPanel.addView(suggestionsList)
        suggestionsPanel.visibility = View.GONE
        // FIX for v1.3.0 startup ClassCastException: the panel was previously added into the
        // vertical LinearLayout child of rootView, which rewrites LayoutParams to
        // LinearLayout.LayoutParams — the subsequent cast to FrameLayout.LayoutParams crashed.
        // Attach it to the contentStage FrameLayout instead: type matches AND the panel
        // now correctly overlays page content right below the top bar.
        findViewById<FrameLayout>(R.id.contentStage).addView(suggestionsPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            marginStart = dp(8)
            marginEnd = dp(8)
        })

        updateTabCounter()
        applyBottomSearchBarPosition()
    }

    fun applyBottomSearchBarPosition() {
        try {
            val parent = topBar.parent as? LinearLayout ?: return
            val contentStage = findViewById<View>(R.id.contentStage) ?: return
            val wantBottom = Prefs.bottomSearchBar
            val topIndex = parent.indexOfChild(topBar)
            val contentIndex = parent.indexOfChild(contentStage)
            val isBottomNow = topIndex > contentIndex
            if (wantBottom != isBottomNow) {
                parent.removeView(topBar)
                if (wantBottom) {
                    parent.addView(topBar)
                } else {
                    parent.addView(topBar, 0)
                }
            }
            // v1.7.31: the suggestion list lives inside the content stage — it has
            // to hang off the SAME edge as the bar, otherwise the omnibox sits at
            // the bottom while its suggestions drop from the top of the page.
            (suggestionsPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val g = if (wantBottom) Gravity.BOTTOM else Gravity.TOP
                if (lp.gravity != g) {
                    lp.gravity = g
                    lp.topMargin = if (wantBottom) 0 else dp(6)
                    lp.bottomMargin = if (wantBottom) dp(6) else 0
                    suggestionsPanel.layoutParams = lp
                }
            }
        } catch (_: Throwable) {}
    }

    private fun omniboxRequestFocus() {
        // v1.7.7: the input is non-focusable while browsing (tap → URL sheet);
        // editing requires flipping this on first
        omniboxInput.isFocusable = true
        omniboxInput.isFocusableInTouchMode = true
        omniboxInput.requestFocus()
        // v1.7.9: a programmatic focus does not reliably raise the IME while the
        // touch cycle is still settling — ask again from a post{} (SHOW_IMPLICIT)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        omniboxInput.post { imm.showSoftInput(omniboxInput, InputMethodManager.SHOW_IMPLICIT) }
    }

    /** v1.7.7: public entry for the URL bottom sheet (edit button / URL row tap). */
    fun omniboxRequestFocusPublic() = omniboxRequestFocus()

    /**
     * v1.7.9 (user: «верни как было — ссылка выделяется и клавиатура доступна,
     * где я смогу сразу удалить ссылку и писать что хочу в поле ссылки! а то ты
     * только дал возможность выбирать снизу три кнопки»): a tap on the omnibox
     * does BOTH at once — enters the classic edit mode (raw URL, select-all,
     * keyboard) AND keeps the inline action row (share / copy / pencil)
     * visible below the omnibox while editing. The row hides together with the
     * suggestions when the input loses focus.
     */
    private fun enterOmniboxEdit() {
        omniboxRequestFocus()
        showUrlBarOverlay()
    }

    /**
     * v1.7.8: Chrome-style INLINE url bar — a compact row DIRECTLY BELOW the
     * omnibox, in the same place where search suggestions appear (user: «не
     * меню sheets — сверху строкой ниже ссылки, там же где подсказки появляются,
     * в том окне, кнопки слева от самой ссылки»). v1.7.16 (user: «эти опции
     * переставь вправо, а не влево»): the row now carries the URL text on
     * the LEFT (weight=1) and the actions [share] [copy] [pencil]
     * right-aligned; the ⋮ button is GONE (user: «эти три точки не нужны,
     * они дублируют уже три опции» — its popup only repeated copy/share).
     * v1.7.9: the row no longer REPLACES edit mode — it coexists with it (see
     * enterOmniboxEdit): the omnibox is selected+editable at the same time and
     * the suggestions dropdown is pushed BELOW the action row so both are usable.
     */
    fun showUrlBarOverlay() {
        val tab = tabs.current ?: return
        if (tab.isHome) { omniboxRequestFocus(); return }
        val url = tab.webView?.url ?: tab.pageUrlSnapshot
        if (url.isEmpty()) return
        if (urlOverlayPanel == null) buildUrlOverlay()
        val panel = urlOverlayPanel ?: return
        val scrim = urlOverlayScrim ?: return
        val row = panel.getChildAt(0) as? LinearLayout ?: return
        // (re)bind the URL text — v1.7.16: index 0, the URL now leads the row
        // and the three action buttons sit after it (right-aligned)
        (row.getChildAt(0) as? TextView)?.text = url
        panel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        panel.bringToFront(); scrim.bringToFront(); panel.bringToFront()
        // v1.7.9: the action row lives in the suggestions area — push the
        // suggestions dropdown below it so nothing overlaps while editing
        try {
            val lp = suggestionsPanel.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.topMargin = dp(6) + dp(56)
                suggestionsPanel.layoutParams = lp
            }
        } catch (_: Exception) {}
        panel.post(object : Runnable {
            override fun run() {
                // retry until the row is actually measured (panel GONE → stop)
                if (panel.visibility != View.VISIBLE) return
                if (panel.height <= 0) { panel.post(this); return }
                try {
                    val lp = suggestionsPanel.layoutParams as? FrameLayout.LayoutParams ?: return
                    lp.topMargin = dp(6) + panel.height + dp(2)
                    suggestionsPanel.layoutParams = lp
                    suggestionsPanel.bringToFront()
                } catch (_: Exception) {}
            }
        })
        panel.translationY = -dp(14).toFloat()
        panel.alpha = 0f
        panel.animate().cancel()
        panel.animate().translationY(0f).alpha(1f)
            .setDuration(190)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))
            .start()
    }

    private fun buildUrlOverlay() {
        val stage = findViewById<FrameLayout>(R.id.contentStage)
        // transparent scrim: first tap anywhere on the page closes the overlay
        // (v1.7.9: also leaves edit mode — the omnibox loses focus + keyboard)
        val scrim = View(this).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                hideUrlBarOverlay()
                omniboxInput.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(omniboxInput.windowToken, 0)
            }
        }
        urlOverlayScrim = scrim
        stage.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                val ta = theme.obtainStyledAttributes(intArrayOf(R.attr.zToolbar))
                setColor(ta.getColor(0, 0xFF202124.toInt()))
                ta.recycle()
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
            visibility = View.GONE
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // v1.7.9: dismiss=false keeps the row on screen (used by actions that do
        // NOT leave edit mode — copy / pencil). Dismissing actions also drop
        // the omnibox focus so the row does not linger without editing.
        fun circleBtn(iconRes: Int, desc: String, dismiss: Boolean = true, onClick: (View) -> Unit): ImageButton =
            ImageButton(this).apply {
                setImageResource(iconRes)
                background = com.zephyr.browser.ui.MenuSheets.roundedRipple(this@MainActivity, 22)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(2) }
                imageTintList = android.content.res.ColorStateList.valueOf(
                    com.zephyr.browser.settings.SettingsUi.color(this@MainActivity, R.attr.colorOnSurfaceVariant))
                contentDescription = desc
                setOnClickListener {
                    if (dismiss) {
                        hideUrlBarOverlay()
                        omniboxInput.clearFocus()
                    }
                    onClick(it)
                }
            }
        // v1.7.16 (user: «эти опции переставь вправо, а не влево»): the URL
        // text leads the row (weight=1) and the three actions — share, copy,
        // pencil — sit right-aligned after it; same circleBtn style and the
        // same dismiss semantics as v1.7.15. The ⋮ button was REMOVED
        // (user: «эти три точки не нужны, они дублируют уже три опции»).
        val urlText = TextView(this).apply {
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.content.res.ColorStateList.valueOf(
                com.zephyr.browser.settings.SettingsUi.color(this@MainActivity, R.attr.colorOnSurface)))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            isClickable = true
            // v1.7.9: the omnibox above already holds focus while this row is
            // visible — a tap on the URL text must not re-request focus or
            // close anything (the user edits directly in the omnibox field)
            setOnClickListener { }
        }
        row.addView(urlText)
        row.addView(circleBtn(R.drawable.ic_share, getString(R.string.nav_share)) {
            tabs.current?.let { shareLink(it.webView?.url ?: it.pageUrlSnapshot, it.title) }
        })
        // v1.7.9: copy does not interrupt editing — the row + keyboard stay
        row.addView(circleBtn(R.drawable.ic_copy, getString(R.string.menu_copy_link), dismiss = false) {
            copyCurrentLink()
        })
        // v1.7.9: redundant now (a tap on the omnibox already edits) but kept
        // working as a harmless fallback for muscle memory
        row.addView(circleBtn(R.drawable.ic_edit, getString(R.string.url_edit), dismiss = false) {
            enterOmniboxEdit()
        })
        panel.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        urlOverlayPanel = panel
        stage.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            marginStart = dp(8)
            marginEnd = dp(8)
        })
    }

    fun hideUrlBarOverlay() {
        val panel = urlOverlayPanel ?: return
        panel.visibility = View.GONE
        urlOverlayScrim?.visibility = View.GONE
        // v1.7.9: restore the suggestions dropdown position (it was pushed
        // below the action row while the row was on screen)
        try {
            val lp = suggestionsPanel.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.topMargin != dp(6)) {
                lp.topMargin = dp(6)
                suggestionsPanel.layoutParams = lp
            }
        } catch (_: Exception) {}
    }

    /** Omnibox: text + cursor ALWAYS visible; tap anywhere in the pill focuses input; click selects-all (Chrome). */
    private fun setupOmnibox() {
        // v1.7.1 FIX 1: tap → FULL selection in one action (user report: tapping the URL
        // did not visibly select it). Plain selectAll() inside the click/focus callbacks was
        // undone by the framework: when an EditText gains focus BY TOUCH, Android's touch
        // handling repositions the cursor AFTER those callbacks run, silently clearing the
        // selection. setSelectAllOnFocus(true) covers the focus-gain path, and post{}
        // re-asserts the selection after the touch sequence settles (Chrome-style).
        omniboxInput.setSelectAllOnFocus(true)
        // v1.7.7→v1.7.9: the omnibox input is NOT focusable by touch, so a tap
        // is a CLICK — and that click now enters the classic edit mode (raw URL
        // + select-all + keyboard, like before v1.7.7) WHILE ALSO dropping the
        // inline action row below the omnibox (share / copy / ⋮ — see
        // enterOmniboxEdit). The row hides again when the input loses focus.
        omniboxInput.isFocusable = false
        omniboxInput.isFocusableInTouchMode = false
        omniboxInput.isClickable = true
        // v1.7.9: tap → edit mode + action row AT ONCE (user: «верни как было —
        // ссылка выделяется и клавиатура доступна… но кнопки снизу оставь»)
        omniboxInput.setOnClickListener { enterOmniboxEdit() }
        omniboxPill.setOnClickListener { enterOmniboxEdit() }
        omniboxInput.setOnFocusChangeListener { _, hasFocus ->
            omniboxPill.background = if (hasFocus) {
                ContextCompat.getDrawable(this, R.drawable.bg_omnibox_edit)
            } else {
                ContextCompat.getDrawable(this, R.drawable.bg_omnibox)
            }
            if (hasFocus) {
                // v1.4.2 FIX 1 (Chrome behavior): edit mode shows the RAW url (with %XX),
                // display mode shows the decoded pretty form
                val t = tabs.current
                if (t != null && !t.isHome && t.pageUrlSnapshot.isNotEmpty()) {
                    omniboxInput.setText(t.pageUrlSnapshot)
                }
                // v1.7.1 FIX 1: posted — setText() above resets the selection AND the
                // touch-driven cursor placement lands after this callback; re-assert async.
                omniboxInput.post { if (omniboxInput.text.isNotEmpty()) omniboxInput.selectAll() }
                showSuggestions()
            } else {
                hideSuggestions()
                // v1.7.9: leaving edit mode also drops the inline action row
                hideUrlBarOverlay()
                updateOmniboxForTab(tabs.current)
                // v1.7.7: edit mode over → taps open the URL sheet again
                omniboxInput.isFocusable = false
                omniboxInput.isFocusableInTouchMode = false
            }
        }
        omniboxInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitOmnibox()
                true
            } else false
        }
        omniboxInput.doAfterTextChanged { s ->
            if (omniboxInput.hasFocus()) querySuggestions(s?.toString() ?: "")
        }
        // v1.7.6: SWIPE DOWN ON THE OMNIBOX → refresh (Chrome-style shortcut, toggle
        // in Settings → Жесты). Works on the pill AND the input while browsing
        // (input not focused); returning false keeps every normal touch behavior.
        val omniboxSwipe = View.OnTouchListener { _, ev ->
            if (!Prefs.omniboxSwipeRefresh) return@OnTouchListener false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    omniboxSwipeDownY = ev.rawY; omniboxSwipeDownX = ev.rawX
                    omniboxSwipeFired = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!omniboxSwipeFired && !omniboxInput.hasFocus()) {
                        val dy = ev.rawY - omniboxSwipeDownY
                        val dx = kotlin.math.abs(ev.rawX - omniboxSwipeDownX)
                        if (dy > dp(28) && dy > dx * 2f) {
                            omniboxSwipeFired = true
                            reload()
                            return@OnTouchListener true
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> omniboxSwipeFired = false
            }
            false
        }
        omniboxPill.setOnTouchListener(omniboxSwipe)
        omniboxInput.setOnTouchListener(omniboxSwipe)
        // security icon state
        updateSecurityIcon()
    }

    fun submitOmnibox() {
        val text = omniboxInput.text.toString().trim()
        if (text.isEmpty()) return
        hideSuggestions()
        omniboxInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(omniboxInput.windowToken, 0)
        val engine = Prefs.currentEngine()
        navigate(Urls.normalize(text) { q -> SearchEngines.searchUrl(engine.id, q) })
    }

    private fun setupToolbarSlots() {
        toolbarSlots.removeAllViews()
        val ids = Prefs.toolbarActions.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(4)
        for (id in ids) {
            val spec = slotSpec(id) ?: continue
            val btn = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_nav_button)
                setImageResource(spec.first)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
                contentDescription = getString(spec.second)
                setOnClickListener { spec.third() }
            }
            toolbarSlots.addView(btn)
        }
        applyIconTints()
    }

    private fun slotSpec(id: String): Triple<Int, Int, () -> Unit>? = when (id) {
        "forward" -> Triple(R.drawable.ic_forward, R.string.nav_forward) {
            tabs.current?.webView?.let { wv ->
                if (wv.canGoForward()) {
                    // v1.7.10 FIX 1c: forward pressed while home is up drives a
                    // possibly suspended WebView — resume before navigating.
                    try { wv.onResume() } catch (_: Exception) {}
                    wv.goForward()
                }
            }
        }
        "reload" -> Triple(R.drawable.ic_reload, R.string.nav_reload) { reload() }
        "bookmarks" -> Triple(R.drawable.ic_bookmark, R.string.bookmarks) { openPanel("bookmarks") }
        "bookmark" -> Triple(R.drawable.ic_bookmark, R.string.bookmarks) { openPanel("bookmarks") }
        "search" -> Triple(R.drawable.ic_search, R.string.nav_search) { omniboxRequestFocus() }
        "voice" -> Triple(R.drawable.ic_mic, R.string.voice_search) { startVoiceSearch() }
        "share" -> Triple(R.drawable.ic_share, R.string.nav_share) { shareCurrent() }
        "history" -> Triple(R.drawable.ic_history, R.string.history) { openPanel("history") }
        "downloads" -> Triple(R.drawable.ic_download, R.string.downloads) { openPanel("downloads") }
        "reader" -> Triple(R.drawable.ic_reader, R.string.reader_mode) { openReader() }
        "incognito" -> Triple(R.drawable.ic_private, R.string.new_private_tab) { tabs.newTab(Urls.DEFAULT_HOME, true) }
        "desktop" -> Triple(R.drawable.ic_desktop, R.string.pc_mode) { toggleDesktopMode() }
        "find" -> Triple(R.drawable.ic_search, R.string.find_on_page) { showFindBar() }
        "translate" -> Triple(R.drawable.ic_translate, R.string.translate) { translateCurrent() }
        "ai" -> Triple(R.drawable.ic_ai, R.string.ai_chat) { openAiChat(Prefs.engineId) }
        "image" -> Triple(R.drawable.ic_camera, R.string.image_search) { openImageSearch(Prefs.engineId) }
        "addbookmark" -> Triple(R.drawable.ic_star_add, R.string.add_bookmark) { addBookmark() }
        // v1.6.0: the "print" slot was a DEAD action (empty lambda) — now opens the
        // page-source viewer; new slots: screenshot / pdf.
        "print" -> Triple(R.drawable.ic_script, R.string.page_source) { showPageSource() }
        "source" -> Triple(R.drawable.ic_script, R.string.page_source) { showPageSource() }
        "screenshot" -> Triple(R.drawable.ic_camera, R.string.menu_screenshot) { captureScreenshot() }
        "pdf" -> Triple(R.drawable.ic_download, R.string.menu_save_pdf) { saveAsPdf() }
        // v1.7.31 (user: «добавь Файлы — из панели нажму кнопку и сразу смогу
        // просматривать файлы на телефоне»): the toolbar slots were missing
        // several capabilities the menu already had.
        "burn" -> Triple(R.drawable.ic_fire, R.string.burn_title) { burnEverything() }
        "files" -> Triple(R.drawable.ic_folder, R.string.files_title) {
            startActivity(Intent(this, com.zephyr.browser.files.FilesActivity::class.java))
        }
        "video" -> Triple(R.drawable.ic_video, R.string.sniff_menu) { showVideoSheet() }
        "adblock" -> Triple(R.drawable.ic_block, R.string.menu_adblock) {
            startActivity(Intent(this, com.zephyr.browser.settings.SettingsActivity::class.java).apply {
                putExtra(com.zephyr.browser.settings.SettingsActivity.EXTRA_SCREEN, "adblock")
            })
        }
        "vpn" -> Triple(R.drawable.ic_vpn, R.string.menu_vpn) {
            startActivity(Intent(this, com.zephyr.browser.settings.SettingsActivity::class.java).apply {
                putExtra(com.zephyr.browser.settings.SettingsActivity.EXTRA_SCREEN, "proxy")
            })
        }
        "settings" -> Triple(R.drawable.ic_settings, R.string.menu_settings) {
            startActivity(Intent(this, com.zephyr.browser.settings.SettingsActivity::class.java))
        }
        "newtab" -> Triple(R.drawable.ic_add, R.string.menu_new_tab) { tabs.newTab(Urls.DEFAULT_HOME, Prefs.alwaysIncognito) }
        else -> null
    }

    private fun setupTabs() {
        tabs.listener = { tab -> onTabSwitched(tab) }
    }

    private fun onTabSwitched(tab: TabManager.Tab?) {
        // v1.7.17 floating player, guard 1: the floating tab was CLOSED somewhere
        // (switcher, close-all, group delete) — closeTab already destroyed its
        // WebView, so only the overlay window remains to clean up.
        if (floatingTab != null && tabs.tabs.none { it === floatingTab }) detachFloatingUi()
        // v1.7.17 floating player, guard 2: the floating tab is being SELECTED
        // (switcher tap, expand button, session restore). Tear the overlay down
        // FIRST — the normal flow below then re-attaches the SAME live WebView
        // (attachPageView wraps it in a fresh SmartPull: its parent is the
        // floating window, not a SwipeRefreshLayout).
        if (tab != null && tab === floatingTab) detachFloatingUi()
        webContainer.removeAllViews()
        hideError()
        if (tab == null) {
            showHome()
            return
        }
        tab.lastUsed = System.currentTimeMillis()
        if (tab.isHome) {
            showHome()
        } else {
            hideHome()
            val wv = tabs.ensureWebView(tab)
            wireWebView(wv, tab)
            // v1.7.6: the WebView is wrapped in a SwipeRefreshLayout (pull-to-refresh)
            attachPageView(wv)
            // apply per-site settings when host known
            tabs.applySettingsFor(wv, tab)
            // v1.5.0 FIX 7: quick cross-fade of the newly attached page
            Anim.fadeIn(wv, 120)
        }
        applyIncognitoUi(tab.incognito)
        updateOmniboxForTab(tab)
        updateTabCounter()
        if (tabSwitcher.visibility == View.VISIBLE) tabSwitcher.refresh()
        homeView.setIncognito(tab.incognito)
    }

    private fun wireWebView(wv: WebView, tab: TabManager.Tab) {
        if (wv.tag == null) {
            wv.tag = tab.id
            wv.webViewClient = browserClient
            wv.webChromeClient = chromeClient
            wv.addJavascriptInterface(ZephyrBridge(tab), "Zephyr")
            wv.setDownloadListener { url, userAgent, contentDisposition, mime, size ->
                onDownloadRequested(url, userAgent, contentDisposition, mime, size)
            }
            // v1.7.5 (user: «при переводе страница на секунду становится белой»):
            // the WebView paints ITS OWN background before any page content — a
            // white base flashed through on every dark-page navigation and during
            // translation. The base now follows the effective page appearance.
            val appAppearance = tabs.effectiveAppearance(tab)
            wv.setBackgroundColor(when {
                tab.incognito -> 0xFF241A3D.toInt()
                appAppearance == 2 -> 0xFF16181D.toInt()
                appAppearance == 3 -> 0xFFF3E7D3.toInt()
                else -> 0xFFFFFFFF.toInt()
            })
            // v1.7.14 (user: «долгий тап по слову в Гугле не выделяет текст»):
            // on editable fields hitTestResult returns EDIT_TYPE (not UNKNOWN),
            // so the old "type != UNKNOWN → consume" branch returned true while
            // showContextMenu() bailed on its null extra — the native selection
            // flow never ran. Consume ONLY when we truly have a link/image.
            wv.setOnLongClickListener { view ->
                val hit = (view as WebView).hitTestResult
                val t = hit.type
                val isLink = t == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    t == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                val isImage = t == android.webkit.WebView.HitTestResult.IMAGE_TYPE ||
                    t == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                if ((isLink || isImage) && !hit.extra.isNullOrBlank()) {
                    showContextMenu(view, hit)
                    true
                } else false   // EDIT/UNKNOWN/… → native selection & paste keep working
            }
        }
    }

    // ---------------- navigation ----------------

    fun navigate(url: String) {
        hideUrlBarOverlay()
        val tab = tabs.current ?: tabs.newTab(Urls.DEFAULT_HOME, Prefs.alwaysIncognito)
        if (url == Urls.DEFAULT_HOME) {
            tab.pageUrlSnapshot = url
            tab.startUrl = url
            showHome()
            updateOmniboxForTab(tab)
            return
        }
        hideHome()
        val wv = tabs.ensureWebView(tab)
        wireWebView(wv, tab)
        // v1.7.6: offline archives load via file:// — file access is granted ONLY
        // for paths inside our own offline dir, never for arbitrary file URLs.
        //
        // v1.7.70: the assistant's own workspace joins that short list, so an
        // HTML mini-app it wrote can actually open. Still a whitelist of two
        // directories, not a switch: a page from the open web must never be
        // able to read the phone's files, which is why this is not simply
        // enabled in TabManager.
        if (url.startsWith("file://")) {
            val offline = File(filesDir, "offline").absolutePath
            val apps = File(com.zephyr.browser.ai.AiWorkspace.root(), "Apps").absolutePath
            if (url.contains(offline) || url.contains(apps)) {
                wv.settings.allowFileAccess = true
            }
        }
        attachPageView(wv)
        // v1.7.39: the offline case is reported by the error overlay itself
        // (see onMainFrameError) — no second banner competing with it.
        showOfflineNotice(false)
        wv.loadUrl(url)
        tab.pageUrlSnapshot = url
        updateOmniboxForTab(tab)
    }

    fun goHome() {
        hideSuggestions()
        omniboxInput.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(omniboxInput.windowToken, 0)
        closeTabSwitcher()
        hideFindBar()
        // v1.7.4: leaving a page for the home screen also stops its sound — the
        // media of the page we are leaving is paused before the home shows.
        // v1.7.17: the FLOATING tab keeps playing (that is the whole point of the
        // mini window — surf + listen/watch at the same time).
        pauseAllMedia(skipFloating = true)
        chromeClient.hideCustomViewIfShown()
        navigate(Urls.DEFAULT_HOME)
    }

    /** v1.7.4: pauses every <video>/<audio> element in every live tab. Runs from
     *  onPause() (leaving the app) and goHome() — WebView.onPause() alone does
     *  not stop HTML5 media playback, so YouTube kept sounding in the background.
     *  v1.7.10 FIX 1a (user: «смотрю киного фильм, жму на дом — играет видео и
     *  звук, хотя я на домашней уже!»): the old script only touched the TOP
     *  document — kinogo-style players live in SAME-ORIGIN IFRAMES and/or
     *  auto-resume right after the pause event. [pauseMediaJs] now pauses media
     *  in the top document AND inside every reachable iframe, disables autoplay
     *  so a player cannot resurrect itself, and still pokes the YouTube
     *  #movie_player API (top document and iframes). */
    private fun pauseAllMedia(skipFloating: Boolean = false) {
        try {
            val js = pauseMediaJs()
            for (t in tabs.tabs) {
                // v1.7.17: the floating player's media keeps playing when the
                // browser itself pauses things (home screen / background pass) —
                // only LEAVING THE APP (onPause default) pauses everything.
                if (skipFloating && t === floatingTab) continue
                val wv = t.webView ?: continue
                try { wv.evaluateJavascript(js, null) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** v1.7.10 FIX 1a: the media-pause script (see [pauseAllMedia]). */
    private fun pauseMediaJs(): String =
        "(function(){try{" +
            "function zp(d){try{var m=d.querySelectorAll('video,audio');" +
            "for(var i=0;i<m.length;i++){try{m[i].pause();" +
            "if(m[i].tagName==='VIDEO')m[i].autoplay=false;}catch(e){}}}catch(e){}}" +
            "zp(document);" +
            "try{var fr=document.querySelectorAll('iframe');" +
            "for(var j=0;j<fr.length;j++){" +
            "try{zp(fr[j].contentDocument);}catch(e){}" +
            "try{var d2=fr[j].contentDocument;if(d2){var mp=d2.getElementById('movie_player');" +
            "if(mp&&mp.pauseVideo)mp.pauseVideo();}}catch(e){}}" +
            "}catch(e){}" +
            "try{var p=document.getElementById('movie_player');" +
            "if(p&&p.pauseVideo)p.pauseVideo();}catch(e){}" +
            "}catch(e){}})();"

    // v1.7.10 FIX 1b: pending delayed second media-pause pass (see suspendBackgroundMedia)
    private var suspendMediaSecondPass: Runnable? = null

    /** v1.7.10 FIX 1b: the home screen is OPAQUE and covers every tab, but the
     *  WebViews behind it stay alive — after the first JS pass kinogo-style
     *  players either sat in iframes or auto-resumed on a timer and kept sounding.
     *  Called whenever the home screen becomes visible:
     *    1. re-runs [pauseAllMedia] (iframe-aware now),
     *    2. calls wv.onPause() on EVERY tab's WebView (home covers them all),
     *    3. posts a delayed (1200 ms) second JS pass for tabs that are STILL not
     *       visible — kills players that resurrect themselves after the pause.
     *  [attachPageView]/onPageStarted/onBackPressed resume a WebView the moment
     *  its page becomes visible again; [onDestroy] drops the pending pass. */
    private fun suspendBackgroundMedia() {
        // v1.7.17: the floating player is ON SCREEN (its overlay sits above the
        // home view) — its media and its WebView stay live.
        pauseAllMedia(skipFloating = true)
        try {
            for (t in tabs.tabs) {
                if (t === floatingTab) continue   // v1.7.17: keep the floated WebView running
                val wv = t.webView ?: continue
                try { wv.onPause() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        suspendMediaSecondPass?.let { main.removeCallbacks(it) }
        val secondPass = Runnable {
            suspendMediaSecondPass = null
            try {
                val js = pauseMediaJs()
                for (t in tabs.tabs) {
                    if (isTabPageVisibleNow(t)) continue   // never pause what's on screen
                    val wv = t.webView ?: continue
                    try { wv.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        suspendMediaSecondPass = secondPass
        main.postDelayed(secondPass, 1200)
    }

    /** v1.7.10 FIX 1b: is this tab's page actually on screen right now? Home and
     *  the tab switcher cover all pages; a fullscreen video IS the current tab's
     *  page (its player must not be paused by the background pass). */
    private fun isTabPageVisibleNow(t: TabManager.Tab): Boolean {
        if (t === floatingTab) return true   // v1.7.17: the floating overlay IS on screen
        if (t !== tabs.current) return false
        if (fullscreenContainer.visibility == View.VISIBLE) return true
        if (homeView.visibility == View.VISIBLE) return false
        if (tabSwitcher.visibility == View.VISIBLE) return false
        return true
    }

    private fun reload() {
        tabs.current?.webView?.reload()
            ?: tabs.current?.let { navigate(it.pageUrlSnapshot) }
    }

    private fun stopLoading() {
        tabs.current?.webView?.stopLoading()
    }

    private fun showHome() {
        // v1.6.0 FLASH FIX: the home view sits ON TOP of webContainer, and the old
        // Anim.fadeIn(homeView, 120) made it semi-transparent for 120 ms — the page
        // behind (e.g. YouTube) showed through right after pressing the home button
        // ("мелькает youtube"). The home view is now OPAQUE from the very first frame;
        // the motion moved INSIDE it (HomePageView.animateContentIn animates the
        // content column over an opaque background — nothing can bleed through).
        homeView.alpha = 1f
        homeView.animate().cancel()
        homeView.visibility = View.VISIBLE
        homeView.reload()
        homeView.animateContentIn()
        // v1.7.10 FIX 1b: the home screen covers EVERY tab — suspend all the
        // WebViews behind it (JS pause + onPause + delayed second pass for
        // auto-resuming kinogo-style players; see suspendBackgroundMedia).
        suspendBackgroundMedia()
    }

    private fun hideHome() {
        homeView.visibility = View.GONE
    }

    /**
     * v1.7.8 FIX (user: «жест свайпом вниз для обновления срабатывает даже если
     * снизу тяну вверх — срабатывает по всей траектории сверху вниз»). The plain
     * SwipeRefreshLayout only checks "can the child scroll up" — pages that
     * scroll inside inner containers (body fixed, div scrolls) always report
     * "at top", so ANY touch could start a refresh, even an upward one.
     * [SmartPullRefresh] additionally requires:
     *  - the page must be at top AT THE MOMENT THE TOUCH STARTS, and
     *  - the FIRST significant vertical movement of the gesture must be DOWNWARD.
     * Scrolling up first disarms the gesture for the whole touch, so a
     * bottom-of-page pull-up can never trigger a refresh.
     *
     * v1.7.15 (user: «на сайте чата потянуть вверх уже срабатывает обновление
     * страницы вместо прокрутки»): inner-container scroll pages now report
     * their state via Scripts.SCROLL_GATE (ZephyrBridge.reportScroll →
     * [MainActivity.pageInnerScroll]); the gesture is disarmed when any
     * ancestor of the touched element or the page itself is scrolled — a
     * mid-chat pull-up scrolls the chat instead of refreshing the page.
     *
     * v1.7.16 (user: «чуть приподнять вверх и вниз, потом опять вверх, очень
     * деликатно не отпуская тач — иначе обновление. На Гугл норм»): the
     * SCROLL_GATE report is ASYNC — the touchstart report lands a few ms
     * AFTER ACTION_DOWN already latched `armed`, so the value read at DOWN
     * was from the PREVIOUS touch (e.g. a tap on the chat input that sits
     * OUTSIDE the scroller → stale false → refresh armed on a scrolled
     * chat). The gate is therefore RE-CHECKED on every ACTION_MOVE and
     * disarms ONE-WAY (never re-arms mid-gesture): once the page reports
     * inner scroll, the whole remaining gesture belongs to the page. The
     * direction decision also stopped being hair-trigger: a deliberate
     * DOWNWARD pull needs 24dp of travel AND 1.5× vertical dominance (an
     * accidental diagonal dip while starting an upward scroll can no
     * longer arm the refresh), while UP still decides at 12dp so real
     * scrolling is never delayed.
     */
    private class SmartPull(private val act: MainActivity, val web: WebView) :
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout(act) {
        private var armed = false
        private var firstDir = 0   // 0 unknown, 1 down, -1 up
        private var downY = 0f
        private var downX = 0f

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // v1.7.15: !pageInnerScroll — the SCROLL_GATE JS says the
                    // page/element chain is scrolled inside → never arm here
                    armed = !web.canScrollVertically(-1) && !act.pageInnerScroll
                    firstDir = 0
                    downY = ev.rawY; downX = ev.rawX
                    return super.onInterceptTouchEvent(ev)   // let SRL record its own down
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // v1.7.16 (user: «чуть приподнять вверх и вниз — очень
                    // деликатно, иначе обновление»): the SCROLL_GATE report
                    // is ASYNC — it can land a few ms AFTER ACTION_DOWN
                    // latched `armed` (the value read at DOWN was from the
                    // PREVIOUS touch, e.g. on the chat input outside the
                    // scroller). Re-check on every MOVE and disarm ONE-WAY
                    // (never re-arm mid-gesture): if the page now reports
                    // inner scroll, this whole gesture belongs to the page.
                    if (act.pageInnerScroll) { armed = false; firstDir = -1 }
                    if (firstDir == 0) {
                        val dy = ev.rawY - downY
                        val dx = kotlin.math.abs(ev.rawX - downX)
                        // v1.7.16: a deliberate DOWNWARD pull now needs 24dp
                        // of travel AND 1.5× vertical dominance (an accidental
                        // diagonal dip while starting an upward scroll can no
                        // longer arm the refresh); UP still decides at 12dp so
                        // real scrolling is never delayed.
                        if (dy > dp(24) || dy < -dp(12)) firstDir = if (dy > 0 && dy > dx * 1.5f) 1 else -1
                    }
                }
            }
            if (!armed || firstDir < 0) return false
            return super.onInterceptTouchEvent(ev)
        }

        private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun attachPageView(wv: WebView) {
        // v1.7.15: fresh-page assumption until SCROLL_GATE reports the real
        // inner-container scroll state (a tab switch to an already-loaded,
        // scrolled-inside page must not keep the previous page's state).
        pageInnerScroll = false
        val curParent = wv.parent as? ViewGroup
        val srl: SwipeRefreshLayout = if (curParent is SwipeRefreshLayout) curParent else {
            // v1.7.18 FIX (user: «жму раскрыть… выдает ошибку
            // illegalstateexception и вкладка пустая»): a WebView floated in the
            // mini window still hangs in the (orphaned) floating window — adding
            // it to a SmartPull then threw "child already has a parent". Release
            // ANY non-SRL parent before wrapping.
            curParent?.removeView(wv)
            SmartPull(this, wv).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                setOnChildScrollUpCallback { _, _ -> wv.canScrollVertically(-1) }
                setOnRefreshListener {
                    reload()
                    postDelayed({ isRefreshing = false }, 1500)
                }
                addView(wv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
        srl.isEnabled = Prefs.pullToRefresh
        if (srl.parent == null) webContainer.addView(srl)
        // v1.7.10 FIX 1c: the home screen leaves every WebView suspended
        // (suspendBackgroundMedia) — the page being attached is on screen again
        // and MUST resume, or it would stay frozen (no JS timers, no media, no
        // rendering). Covers navigate() and onTabSwitched's page branch; the
        // popups too (they select a tab → attachPageView).
        try { wv.onResume() } catch (_: Exception) {}
        // v1.7.15: tab switch to an already-loaded page — the gate script did
        // not run again on its own (no fresh onPageFinished), so re-arm it;
        // the initial rep(document.body) reports the current scroll state.
        try { wv.evaluateJavascript(Scripts.SCROLL_GATE, null) } catch (_: Exception) {}
    }

    fun currentTabForThreads(): TabManager.Tab? = tabs.current

    fun lastKnownPageUrl(): String = tabs.current?.pageUrlSnapshot ?: ""

    // ---------------- WebViewClient callbacks (UI thread) ----------------

    fun handleUrlLoading(view: WebView, uri: Uri): Boolean {
        val url = uri.toString()
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            return maybeAppLink(url)
        }
        if (browserClient.isExternalScheme(uri)) {
            handleExternal(uri)
            return true
        }
        return false
    }

    /** v1.7.5 (user: «выбираю открыть в браузере — окно спрашивает ещё раз»):
     *  hosts the user opened IN THE BROWSER this session. Picking "Открыть в
     *  браузере" memorizes the host until the app restarts, so the redirect chain
     * right after the choice (youtube.com → consent.youtube.com / m.youtube.com)
     * can no longer re-trigger the ask-dialog a second time. */
    private val appLinkSessionBrowser = HashSet<String>()

    private fun maybeAppLink(url: String): Boolean {
        if (Prefs.openInApps == 1) return false
        val host = Urls.domainOf(url)
        if (host.isEmpty()) return false
        // v1.7.5: browser chosen for this host (or a parent) this session → straight to the WebView
        if (appLinkSessionBrowser.any { host == it || host.endsWith(".$it") }) return false
        // always hosts
        for (h in Prefs.appLinkAlways) {
            if (host == h || host.endsWith(".$h")) {
                launchExternalFor(url)
                return true
            }
        }
        for (h in Prefs.appLinkNever) {
            if (host == h || host.endsWith(".$h")) return false
        }
        val curated = browserClient.isCuratedAppHost(host, url)
        val handler = browserClient.findAppHandler(url)
        if (curated || handler != null) {
            if (Prefs.openInApps == 2) {
                launchExternalFor(url)
                return true
            }
            showOpenInAppDialog(url, host, handler)
            return true
        }
        return false
    }

    private fun launchExternalFor(url: String): Boolean {
        val uri = Uri.parse(url)
        val pair = browserClient.externalIntent(uri) ?: return false
        return try {
            startActivity(pair.first)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            val fb = pair.second
            if (fb != null) {
                navigate(fb)
                true
            } else {
                Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun showOpenInAppDialog(url: String, host: String, pkg: String?) {
        val appLabel = try {
            pkg?.let { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
        } catch (_: Exception) { null } ?: getString(R.string.external_app)
        val box = CheckBox(this).apply {
            text = getString(R.string.dont_ask_again)
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.open_in_app_title, host))
            .setMessage(getString(R.string.open_in_app_msg, appLabel))
            .setView(box)
            .setPositiveButton(R.string.always) { _, _ ->
                Prefs.toggleAppLinkAlways(host, true)
                launchExternalFor(url)
            }
            .setNegativeButton(R.string.open_once) { _, _ -> launchExternalFor(url) }
            .setNeutralButton(R.string.open_in_browser) { _, _ ->
                if (box.isChecked) Prefs.toggleAppLinkNever(host, true)
                else appLinkSessionBrowser.add(host)   // v1.7.5: session memory — no re-ask
                navigate(url)
            }
            .show()
    }

    private fun handleExternal(uri: Uri) {
        val pair = browserClient.externalIntent(uri) ?: return
        try {
            startActivity(pair.first)
        } catch (_: Exception) {
            // FIX (user: Play page → "open in app" → the microG Companion page
            // opened instead of the app). Play pages pin their intent:// links
            // to com.android.vending. When that package is missing or cannot
            // handle the link (microG Companion, no Play Store), the fallback
            // below opened market://details?id=com.android.vending — the store
            // page of the PLAY STORE itself, the real app id lost. First try
            // the very same link unpinned: any app that handles it (alternative
            // stores for market://details?id=<app>) gets it via the chooser.
            if (pair.first.`package` != null) {
                val unpinned = Intent(pair.first).setPackage(null)
                try {
                    startActivity(unpinned)
                    return
                } catch (_: Exception) {}
            }
            if (pair.second != null) {
                navigate(pair.second!!)
            } else {
                // try market for package of intent://
                val pkg = pair.first.`package`
                    ?: (pair.first.getStringExtra("browser_fallback_url")?.let { null })
                if (pkg != null) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                        return
                    } catch (_: Exception) {}
                }
                Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onPageStarted(url: String) {
        // v1.7.15: new page = fresh scroll state until SCROLL_GATE reports
        // (see pageInnerScroll / SmartPull)
        pageInnerScroll = false
        hideUrlBarOverlay()
        hideError()
        val tab = tabs.current ?: return
        tab.pageUrlSnapshot = url
        if (url != Urls.DEFAULT_HOME) {
            // v1.7.10 FIX 1c: back/forward from the home screen drives the current
            // WebView DIRECTLY (goBack/goForward — attachPageView never runs on
            // that path), so a WebView the home screen left suspended must be
            // resumed right here or the returning page would be frozen.
            if (homeView.visibility == View.VISIBLE) {
                tab.webView?.let { wv -> try { wv.onResume() } catch (_: Exception) {} }
            }
            hideHome()
        }
        updateOmniboxForTab(tab)
        updateSecurityIcon()
        loadFaviconFor(url)
        val wv = tab.webView ?: return
        // v1.7.5: per-site appearance + per-site JS/images/popups are re-evaluated
        // on EVERY page start — navigating wikipedia→youtube now swaps the look.
        tabs.applySettingsFor(wv, tab)
        wv.evaluateJavascript(Scripts.BRIDGE, null)
        // v1.7.7: the inline translator must exist before the user taps «Перевести»
        // (it defines window.__zephyrTranslate / TrRestore / TrCb page-side).
        wv.evaluateJavascript(Scripts.PAGE_TRANSLATOR, null)
        if (Prefs.blockWebRTC) wv.evaluateJavascript(Scripts.BLOCK_WEBRTC, null)
        if (Prefs.doNotTrack) wv.evaluateJavascript(Scripts.DO_NOT_TRACK, null)
        // v1.7.2: userscript engine (Tampermonkey-style) — kick off the async script
        // cache on the first navigation, then inject scripts whose run-at is
        // document-start. Fully-qualified name: this file's import block is not
        // part of the allowed edit surface. Incognito tabs are skipped.
        com.zephyr.browser.userscripts.ScriptInjector.ensureLoaded(applicationContext)
        if (!tab.incognito) {
            com.zephyr.browser.userscripts.ScriptInjector.injectFor(url, "document-start")
                .takeIf { it.isNotEmpty() }?.let { wv.evaluateJavascript(it, null) }
        }
        // v1.7.3: YT adblock v3 hooks MUST run before page scripts — inject at
        // document-start (onPageStarted), not onPageFinished. The fetch/XHR
        // empty-VAST responses + JSON.parse surgery only prevent the preroll
        // black screen if they are installed before the player boots.
        if (Prefs.adBlockMode > 0 && Prefs.youtubeAdBlock) {
            val host = Urls.domainOf(url).removePrefix("www.")
            val whitelisted = Prefs.adBlockWhitelist.any { host == it || host.endsWith(".$it") }
            if (!whitelisted && (host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtu.be" || host == "music.youtube.com")) {
                wv.evaluateJavascript(Scripts.YT_ADBLOCK, null)
            }
        }
    }

    fun onPageFinished(view: WebView, url: String) {
        val tab = tabs.current ?: return
        tab.pageUrlSnapshot = url
        if (url != Urls.DEFAULT_HOME && url != "about:blank") {
            recordHistory(url, tab.title.ifEmpty { url })
        }
        // v1.4.2 FIX 5: after a saveState restore the scroll offset is NOT part of the
        // bundle — put the tab back exactly where the user left it (once per restore).
        if (tab.pendingScrollRestore) {
            tab.pendingScrollRestore = false
            if (tab.scrollY > 0) view.post { view.scrollTo(0, tab.scrollY) }
        }
        updateOmniboxForTab(tab)
        updateSecurityIcon()
        loadFaviconFor(url)
        AdBlockManager.flushBlocked()
        // inject page appearance + element hiding + favicon sniff
        val appearance = tabs.effectiveAppearance(tab)
        Scripts.appearanceCss(appearance).takeIf { it.isNotEmpty() }?.let { view.evaluateJavascript(it, null) }
        if (Prefs.adBlockMode > 0) {
            val css = AdBlockManager.elementHideCss(url)
            if (css.isNotEmpty()) {
                view.evaluateJavascript("(function(){try{var s=document.createElement('style');s.textContent=" +
                    JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);}catch(e){}})();", null)
            }
        }
        // v1.5.1: YouTube ad blocker — video ads share googlevideo.com hosts with real
        // content (network filters would break playback), so YT gets a dedicated JS
        // layer: CSS hiding + skip-clicking + fast-forward, behind its own toggle.
        // v1.7.3: Scripts.YT_ADBLOCK (v3) moved to onPageStarted — its fetch/XHR
        // empty-VAST hooks + JSON.parse surgery must precede every page script;
        // only the SponsorBlock injection below remains at document-idle.
        if (Prefs.adBlockMode > 0 && Prefs.youtubeAdBlock) {
            val host = Urls.domainOf(url).removePrefix("www.")
            val whitelisted = Prefs.adBlockWhitelist.any { host == it || host.endsWith(".$it") }
            if (!whitelisted && (host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtu.be" || host == "music.youtube.com")) {
                // v1.7.2: SponsorBlock — skip sponsored segments inside the video
                // (sponsor.ajay.app community database; acts only on watch pages).
                // Categories come from the user's Settings → AdBlock → SponsorBlock
                // toggles; all-off → no injection at all.
                Scripts.sponsorBlockJs(Prefs.sbSponsor, Prefs.sbSelfpromo, Prefs.sbInteraction, Prefs.sbIntro)
                    .takeIf { it.isNotEmpty() }?.let { view.evaluateJavascript(it, null) }
            }
        }
        // v1.7.2: userscript engine — run-at document-end and document-idle (the
        // default phase). onPageFinished ≈ idle; end runs right before it so
        // both timings stay Tampermonkey-compatible. Incognito tabs are skipped.
        if (!tab.incognito) {
            com.zephyr.browser.userscripts.ScriptInjector.injectFor(url, "document-end")
                .takeIf { it.isNotEmpty() }?.let { view.evaluateJavascript(it, null) }
            com.zephyr.browser.userscripts.ScriptInjector.injectFor(url, "document-idle")
                .takeIf { it.isNotEmpty() }?.let { view.evaluateJavascript(it, null) }
        }
        view.evaluateJavascript(Scripts.FAVICON_SNIFF, null)
        // v1.7.6: a finished page clears the pull-to-refresh spinner
        (view.parent as? SwipeRefreshLayout)?.isRefreshing = false
        if (!tab.incognito && !Prefs.alwaysIncognito) {
            view.evaluateJavascript(Scripts.FORM_WATCHER, null)
            view.evaluateJavascript(Scripts.PAYMENT_WATCHER, null)
            // v1.7.0: card SAVE watcher — Luhn-valid PAN submitted → "Сохранить карту?" offer
            view.evaluateJavascript(Scripts.CARD_SAVE, null)
            // v1.5.1: identity-form watcher → autofill PROFILE offer (name/email/phone/…)
            view.evaluateJavascript(Scripts.IDENTITY_WATCHER, null)
            // v1.4.2 FIX 7: the old SILENT maybeAutofill() call was removed — filling saved
            // credentials now goes exclusively through the gated flow:
            // password-field focus → chip dialog → biometric/PIN → inject.
        }
        if (tab.desktop) view.evaluateJavascript(Scripts.DESKTOP_VIEWPORT, null)
        // v1.7.7: Stylus-like user CSS for this domain + the video sniffer hook.
        // The style is cached per-host (same pattern as domain settings); the sniffer
        // hooks src setters + fetch/XHR for m3u8/mp4/webm and reports via the bridge.
        injectSiteStyle(view, url)
        if (Prefs.videoSniffer) view.evaluateJavascript(Scripts.MEDIA_SNIFFER, null)
        // v1.7.15: scroll-state gate for pull-to-refresh — UNCONDITIONAL (not
        // pref-gated): SmartPull needs the inner-container scroll state on
        // every page, chat SPAs first of all (see pageInnerScroll).
        try { view.evaluateJavascript(Scripts.SCROLL_GATE, null) } catch (_: Exception) {}
        updateTabCounter()
    }

    // ---------------- v1.7.7: site styles (Stylus-like) ----------------

    /** host → user CSS (enabled only); refreshed async like the domain-settings cache. */
    @Volatile private var styleCache = mapOf<String, String>()

    fun refreshSiteStylesAsync() {
        val ctx = applicationContext
        ioScope.launch {
            try {
                val map = HashMap<String, String>()
                for (s in ctx.db.siteStyleDao().allOnce()) {
                    if (s.enabled && s.css.isNotBlank()) map[s.domain] = s.css
                }
                styleCache = map
            } catch (_: Exception) {}
        }
    }

    private fun injectSiteStyle(view: WebView, url: String) {
        val host = Urls.domainOf(url).removePrefix("www.")
        if (host.isEmpty()) return
        val css = styleCache[host] ?: return
        view.evaluateJavascript("(function(){try{" +
            "var s=document.getElementById('__zephyrUserStyle');" +
            "if(!s){s=document.createElement('style');s.id='__zephyrUserStyle';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=" + JSONObject.quote(css) + ";" +
            "}catch(e){}})();", null)
    }

    /** Menu → «Стили сайта»: editor sheet for the current domain. */
    fun openSiteStyles() {
        val tab = tabs.current ?: return
        val url = tab.webView?.url ?: tab.pageUrlSnapshot
        val host = Urls.domainOf(url).removePrefix("www.")
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.styles_need_site, Toast.LENGTH_SHORT).show()
            return
        }
        ioScope.launch {
            val existing = try { db.siteStyleDao().byDomain(host) } catch (_: Exception) { null }
            main.post { MenuSheets.showSiteStyleEditor(this@MainActivity, host, existing?.css ?: "") }
        }
    }

    /** Called by the style editor sheet: persists + live-applies the CSS. */
    fun saveSiteStyle(domain: String, css: String, enabled: Boolean, apply: Boolean) {
        ioScope.launch {
            try {
                if (css.isBlank()) db.siteStyleDao().delete(domain)
                else db.siteStyleDao().insert(com.zephyr.browser.db.SiteStyleEntity(
                    domain = domain, css = css, enabled = enabled))
            } catch (_: Exception) {}
            refreshSiteStylesAsync()
        }
        if (apply) {
            val wv = tabs.current?.webView ?: return
            // removal path: strip the tag, else inject/refresh
            if (css.isBlank() || !enabled) {
                wv.evaluateJavascript("(function(){try{var s=document.getElementById('__zephyrUserStyle');if(s)s.remove();}catch(e){}})();", null)
            } else {
                injectSiteStyle(wv, tabs.current?.webView?.url ?: tabs.current?.pageUrlSnapshot ?: "")
            }
        }
    }

    // ---------------- v1.7.7→v1.7.9: video sniffer ----------------

    /** Parsed media candidates found on the current page (deduped; the sheet
     *  re-sorts them for display). v1.7.9: adaptive (video-only / audio-only)
     *  YouTube streams are kept too — the sheet muxes video+audio on download. */
    private val foundMedia = ArrayDeque<SniffedMedia>()

    /** v1.7.15 (user: «на сайте чата потянуть вверх уже срабатывает обновление
     *  страницы вместо прокрутки — всё зависит от сайта»): SPA pages (z.ai chat
     *  etc.) scroll INSIDE inner containers, so the WebView itself always
     *  reports "at top" and SmartPull's canScrollVertically(-1) check armed the
     *  refresh while the chat history was scrolled mid-way. Scripts.SCROLL_GATE
     *  (injected on every page finish + attach) reports via
     *  ZephyrBridge.reportScroll whether the page itself or any ancestor of the
     *  touched element is scrolled — SmartPull then refuses to arm. Fresh-page
     *  assumption (false) until the JS reports otherwise. */
    @Volatile private var pageInnerScroll = false

    /** v1.7.14: belt-and-braces reopen cap for the self-refreshing video sheet
     *  (native dedupe already makes reopen loops impossible — the probe adds
     *  nothing new the second time, so foundMedia stops growing). */
    private var videoSheetReopens = 0

    /** YouTube itags that carry BOTH audio+video (progressive). */
    private val YT_MUXED = mapOf(
        18 to "360p", 22 to "720p", 37 to "1080p", 35 to "480p", 34 to "360p", 36 to "240p")

    /** v1.7.9: adaptive VIDEO-only itags → quality label (kind=1; the audio
     *  track is muxed in on download). Modern YouTube serves everything above
     *  360p ONLY like this — rejecting these itags is exactly why the sheet
     *  used to find "nothing" on current watch pages. */
    private val YT_VIDEO = mapOf(
        133 to "240p", 134 to "360p", 135 to "480p", 136 to "720p", 137 to "1080p",
        160 to "144p", 242 to "360p", 243 to "480p", 244 to "480p", 247 to "720p",
        248 to "1080p", 271 to "1440p", 313 to "2160p",
        298 to "720p60", 299 to "1080p60", 302 to "720p60", 303 to "1080p60",
        308 to "1440p60", 315 to "2160p60")

    /** v1.7.9: adaptive AUDIO-only itags → bitrate label (kind=2). */
    private val YT_AUDIO = mapOf(
        139 to "48 kbps", 140 to "128 kbps", 258 to "160 kbps", 256 to "192 kbps")

    /** v1.7.9: the shared audio track that adaptive (video-only) rows download
     *  together with their video stream — itag 140 preferred, then 139/258. */
    @Volatile private var bestAudioUrl: String? = null

    /** v1.7.9: YouTube itag of a googlevideo URL (null for everything else). */
    private fun ytItagOf(url: String): Int? = try {
        val u = Uri.parse(url)
        if (u.host?.lowercase()?.endsWith("googlevideo.com") == true)
            u.getQueryParameter("itag")?.toIntOrNull() else null
    } catch (_: Exception) { null }

    /** Preference rank of an audio-only googlevideo URL for the mux download. */
    private fun audioRank(url: String): Int = when (ytItagOf(url)) {
        140 -> 4; 139 -> 3; 258 -> 3; 256 -> 2; else -> 0
    }

    /**
     * v1.7.9: MSE players fetch googlevideo files in RANGE CHUNKS — the same
     * itag shows up as many different URLs (...&range=0-9999&...). Dropping the
     * range param turns a chunk URL back into the FULL-file URL (everything
     * else stays byte-for-byte identical, so signatures keep working).
     */
    private fun stripRangeParam(url: String): String {
        try {
            if (!url.contains("range=")) return url
            val q = url.indexOf('?')
            if (q < 0) return url
            val base = url.substring(0, q)
            val params = url.substring(q + 1).split('&')
                .filter { it.isNotEmpty() && !it.startsWith("range=") }
            return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
        } catch (_: Exception) { return url }
    }

    /** Bridge entry — the page reported a playable/streamable media URL. */
    private fun onMediaFoundInternal(rawUrl: String) {
        if (rawUrl.length < 8 || !rawUrl.startsWith("http")) return
        // v1.7.9: chunked MSE requests become their full-file URL before parsing
        val url = if (rawUrl.contains("googlevideo.com")) stripRangeParam(rawUrl) else rawUrl
        val candidate = parseSniffed(url) ?: return
        main.post {
            // v1.7.9: remember the best audio-only track…
            if (candidate.kind == 2) {
                val cur = bestAudioUrl
                if (cur == null || audioRank(candidate.url) >= audioRank(cur)) {
                    bestAudioUrl = candidate.url
                    // …and attach it to every already-collected video-only row
                    foundMedia.forEach { if (it.kind == 1) it.audioUrl = bestAudioUrl }
                }
            } else if (candidate.kind == 1 && candidate.audioUrl == null) {
                candidate.audioUrl = bestAudioUrl
            }
            // v1.7.9: dedup — by exact URL always, googlevideo streams
            // additionally by ITAG (the same itag legitimately arrives with
            // different query params from different page paths)
            val itag = ytItagOf(candidate.url)
            foundMedia.removeAll { m ->
                m.url == candidate.url || (itag != null && ytItagOf(m.url) == itag)
            }
            foundMedia.addFirst(candidate)
            while (foundMedia.size > 12) foundMedia.removeLast()
        }
    }

    /** v1.7.15: bridge entry — the page reported a cross-origin iframe PLAYER
     *  source (see Scripts.MEDIA_PROBE / MEDIA_SNIFFER embeds()). Movie sites
     *  embed players from other origins; the DOM walk cannot enter them, so
     *  the sheet offers opening the player page in a new tab instead, where
     *  the sniffer then catches the direct media URLs. */
    private fun onEmbedFoundInternal(rawUrl: String) {
        if (rawUrl.length < 8 || !rawUrl.startsWith("http")) return
        try {
            // dedupe by exact URL + cap: max 5 embed rows, 12 rows total
            // (the same total cap onMediaFoundInternal keeps)
            if (foundMedia.any { it.url == rawUrl }) return
            if (foundMedia.count { it.kind == 3 } >= 5) return
            val host = (Uri.parse(rawUrl).host ?: "").removePrefix("www.")
            val candidate = SniffedMedia(rawUrl, getString(R.string.sniff_embed, host),
                isHls = false, size = 0, kind = 3)
            foundMedia.addFirst(candidate)
            while (foundMedia.size > 12) foundMedia.removeLast()
        } catch (_: Exception) {}
    }

    /** v1.7.8: raw URL → labeled candidate; null = junk to ignore.
     *  v1.7.9: googlevideo URLs now map through the FULL itag table — muxed,
     *  adaptive video (kind=1) and audio-only (kind=2) streams all survive. */
    private fun parseSniffed(url: String): SniffedMedia? {
        return try {
            val u = Uri.parse(url)
            val host = u.host?.lowercase() ?: ""
            val lower = url.lowercase()
            when {
                host.endsWith("googlevideo.com") -> {
                    // YouTube CDN — the itag decides what the stream carries
                    val itag = u.getQueryParameter("itag")?.toIntOrNull() ?: return null
                    val size = u.getQueryParameter("clen")?.toLongOrNull() ?: 0L
                    when {
                        YT_MUXED.containsKey(itag) ->
                            SniffedMedia(url, "YouTube · " + YT_MUXED[itag] + " · MP4",
                                isHls = false, size = size, kind = 0)
                        YT_VIDEO.containsKey(itag) ->
                            SniffedMedia(url, "YouTube · " + YT_VIDEO[itag],
                                isHls = false, size = size, needsAudio = true, kind = 1)
                        YT_AUDIO.containsKey(itag) ->
                            SniffedMedia(url, "YouTube · M4A · " + YT_AUDIO[itag],
                                isHls = false, size = size, kind = 2)
                        else -> null
                    }
                }
                lower.contains(".mpd") -> null   // DASH manifest — can't stitch without muxing
                lower.contains(".m3u8") -> SniffedMedia(url, "HLS поток", isHls = true, size = 0)
                lower.contains(".mp4") || lower.contains("videoplayback") && !host.endsWith("googlevideo.com") ->
                    SniffedMedia(url, "MP4 видео · " + host.removePrefix("www."), isHls = false, size = 0)
                lower.contains(".webm") -> SniffedMedia(url, "WebM видео · " + host.removePrefix("www."), isHls = false, size = 0)
                lower.contains(".m4v") -> SniffedMedia(url, "M4V видео · " + host.removePrefix("www."), isHls = false, size = 0)
                // v1.7.14: movie sites hand out these direct-link extensions too
                lower.contains(".mov") -> SniffedMedia(url, "MOV видео · " + host.removePrefix("www."), isHls = false, size = 0)
                lower.contains(".mkv") -> SniffedMedia(url, "MKV видео · " + host.removePrefix("www."), isHls = false, size = 0)
                lower.contains(".flv") -> SniffedMedia(url, "FLV видео · " + host.removePrefix("www."), isHls = false, size = 0)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** v1.7.9: sheet ordering helpers — resolution from the label ("720p60"→720). */
    private fun sniffRes(m: SniffedMedia): Int =
        Regex("""(\d{3,4})p""").find(m.label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** v1.7.9: bitrate from an audio-only label ("M4A · 128 kbps"→128). */
    private fun sniffKbps(m: SniffedMedia): Int =
        Regex("""(\d+)\s*kbps""").find(m.label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * v1.7.8 (user: «кнопка выскакивает везде — что за чушь? поставь в попап с
     * возможностью убрать сниффер»): the floating pill is GONE. The sniffer now
     * lives in the main menu (row «Видео со страницы») and opens a premium sheet:
     * quality-labeled rows + download / external-player actions + the sniffer
     * toggle right inside. v1.7.14: every open fires a one-shot MEDIA_PROBE and
     * the sheet self-reopens once if the probe lands new finds. v1.7.15:
     * cross-origin iframe players appear as «Плеер · host» rows (kind 3 — the
     * pill opens the player in a new tab), the refresh row is rendered ALWAYS
     * (even on an empty sheet — "start playback, then retry"), and the empty
     * state explains that flow under the scanning line.
     */
    fun showVideoSheet(fromMenu: Boolean = true) {
        // v1.7.14 (user: «⋮ → Видео — пусто на киносайтах»): opening the sheet
        // fires a ONE-SHOT on-demand probe (Scripts.MEDIA_PROBE). The passive
        // sniffer pref governs only the always-on hooks — the sheet itself
        // always scans the current page on demand.
        if (fromMenu) videoSheetReopens = 0
        val wvProbe = tabs.current?.webView
        try { wvProbe?.evaluateJavascript(Scripts.MEDIA_PROBE, null) } catch (_: Exception) {}

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        val onSurface = com.zephyr.browser.settings.SettingsUi.color(this, R.attr.colorOnSurface)
        val onVariant = com.zephyr.browser.settings.SettingsUi.color(this, R.attr.colorOnSurfaceVariant)
        val primary = com.zephyr.browser.settings.SettingsUi.color(this, R.attr.colorPrimary)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        val host = Urls.domainOf(tabs.current?.webView?.url ?: tabs.current?.pageUrlSnapshot ?: "")
        root.addView(TextView(this).apply {
            text = getString(R.string.sniff_title)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
            setPadding(dp(4), 0, dp(4), 0)
        })
        val subLine = TextView(this).apply {
            text = if (host.isEmpty()) getString(R.string.sniff_sheet_sub)
            else getString(R.string.sniff_sheet_sub_site, host)
            textSize = 12.5f
            setPadding(dp(4), 0, dp(4), dp(10))
            setTextColor(onVariant)
        }
        root.addView(subLine)

        // rows live in their own column so background probes can refresh in place
        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.58f).toInt()
            )
        }
        root.addView(scroll)

        val tab = tabs.current
        val pageUa = tab?.webView?.settings?.userAgentString
        val pageRef = tab?.pageUrlSnapshot
        val pageCookies = try { CookieManager.getInstance().getCookie(pageRef ?: "") } catch (_: Exception) { null }

        lateinit var rebuild: () -> Unit

        /** Background: real resolution + preview frame straight from the stream. */
        fun probeMeta(m: SniffedMedia) {
            if (m.probeState != 0 || m.kind == 3) return
            MediaThumbs.cached(m.url)?.let {
                m.width = it.width; m.height = it.height
                m.durationMs = it.durationMs; m.thumb = it.frame
                m.probeState = 2
                return
            }
            m.probeState = 1
            Thread {
                val meta = MediaThumbs.probe(m.url, pageUa, pageRef, pageCookies)
                main.post {
                    if (meta != null) {
                        m.width = meta.width; m.height = meta.height
                        m.durationMs = meta.durationMs; m.thumb = meta.frame
                    }
                    m.probeState = 2
                    if (sheet.isShowing) rebuild()
                }
            }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }

        /** Background: dig the real media links out of an iframe player page. */
        fun resolveEmbed(m: SniffedMedia) {
            if (m.resolveState != 0) return
            m.resolveState = 1
            rebuild()
            Thread {
                val found = EmbedResolver.resolve(m.url, pageRef, pageUa, pageCookies)
                main.post {
                    m.resolveState = if (found.isEmpty()) 3 else 2
                    for (f in found) {
                        if (foundMedia.any { it.url == f.url }) continue
                        val fHost = (Uri.parse(f.url).host ?: "").removePrefix("www.")
                        val label = buildString {
                            if (f.quality.isNotBlank()) append(f.quality).append(" · ")
                            append(if (f.isHls) "HLS" else f.url.substringAfterLast('.')
                                .substringBefore('?').uppercase().take(4))
                            if (fHost.isNotBlank()) append(" · ").append(fHost)
                        }
                        foundMedia.addFirst(SniffedMedia(f.url, label, isHls = f.isHls, size = 0, kind = 0))
                    }
                    while (foundMedia.size > 16) foundMedia.removeLast()
                    if (found.isNotEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.sniff_found_n, found.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (sheet.isShowing) rebuild()
                }
            }.apply { isDaemon = true }.start()
        }

        /** 16:9 preview tile: the probed frame, or a placeholder glyph. */
        fun thumbTile(m: SniffedMedia): View {
            val frame = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(58)).apply { marginEnd = dp(12) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(com.zephyr.browser.settings.SettingsUi.color(this@MainActivity, R.attr.colorSurfaceVariant))
                }
                clipToOutline = true
            }
            val img = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val bmp = m.thumb
            if (bmp != null && !bmp.isRecycled) {
                img.setImageBitmap(bmp)
            } else {
                img.scaleType = ImageView.ScaleType.CENTER
                img.setImageResource(
                    when {
                        m.kind == 3 -> R.drawable.ic_website
                        m.kind == 2 -> R.drawable.ic_music_note
                        else -> R.drawable.ic_video
                    }
                )
                img.imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
                if (m.probeState == 1) img.alpha = 0.35f
            }
            frame.addView(img)

            // duration chip in the corner, once we know it
            val dur = MediaThumbs.formatDuration(m.durationMs)
            if (dur.isNotEmpty()) frame.addView(TextView(this).apply {
                text = dur
                textSize = 9.5f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(4), dp(1), dp(4), dp(1))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(0xCC000000.toInt())
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply { bottomMargin = dp(4); marginEnd = dp(4) }
            })
            return frame
        }

        /** Small accent pill used for the download / open actions. */
        fun actionPill(labelRes: Int, iconRes: Int, onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(14), dp(8))
                background = com.zephyr.browser.settings.SettingsUi.roundedFill(this@MainActivity, dp(18))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        com.zephyr.browser.settings.SettingsUi.color(this@MainActivity, R.attr.colorOnPrimary))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
                })
                addView(TextView(this@MainActivity).apply {
                    text = getString(labelRes)
                    textSize = 12.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(com.zephyr.browser.settings.SettingsUi.color(this@MainActivity, R.attr.colorOnPrimary))
                })
                setOnClickListener { onClick() }
            }

        rebuild = fun() {
            listCol.removeAllViews()

            if (!Prefs.videoSniffer) {
                listCol.addView(TextView(this).apply {
                    text = getString(R.string.sniff_disabled_note)
                    textSize = 13.5f
                    setPadding(dp(4), 0, dp(4), dp(8))
                    setTextColor(onVariant)
                })
            }

            // v1.7.9 ordering: playable rows first by resolution, audio next,
            // unresolved players last.
            val ordered = foundMedia.toList().sortedWith(
                compareBy<SniffedMedia> { it.kind }
                    .thenByDescending {
                        when (it.kind) {
                            2 -> sniffKbps(it)
                            3 -> 0
                            else -> if (it.height > 0) it.height else sniffRes(it)
                        }
                    })

            if (ordered.isEmpty() && Prefs.videoSniffer) {
                listCol.addView(TextView(this).apply {
                    text = getString(R.string.sniff_scanning)
                    textSize = 13.5f
                    setPadding(dp(4), dp(6), dp(4), dp(4))
                    setTextColor(onVariant)
                })
                listCol.addView(TextView(this).apply {
                    text = getString(R.string.sniff_none_hint)
                    textSize = 12.5f
                    setPadding(dp(4), 0, dp(4), dp(8))
                    setTextColor(onVariant)
                })
            }

            for (m in ordered) {
                if (m.kind != 3) probeMeta(m)

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = com.zephyr.browser.settings.SettingsUi.rounded(this@MainActivity, dp(18))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                card.addView(thumbTile(m))

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                // title: the PROBED resolution wins over the guessed label
                val badge = MediaThumbs.resolutionBadge(m.width, m.height)
                val title = when {
                    m.kind == 3 -> m.label
                    badge.isNotEmpty() && !m.label.contains(badge) -> badge + " · " + m.label
                    else -> if (m.kind == 0 && m.label.startsWith("YouTube"))
                        m.label + " · " + getString(R.string.sniff_mux_badge) else m.label
                }
                textCol.addView(TextView(this).apply {
                    text = title
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(onSurface)
                })
                // subtitle: exact pixels + size, else the bare url
                val bits = ArrayList<String>()
                if (m.width > 0 && m.height > 0) bits.add(m.width.toString() + "×" + m.height)
                if (m.size > 0) bits.add(android.text.format.Formatter.formatFileSize(this, m.size))
                if (bits.isEmpty()) bits.add(
                    m.url.substringAfter("//").let { if (it.length > 42) it.take(39) + "…" else it }
                )
                textCol.addView(TextView(this).apply {
                    text = bits.joinToString(" · ")
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(0, dp(2), 0, 0)
                    setTextColor(onVariant)
                })
                // adaptive / audio-only / resolver hints
                val hint = when {
                    m.kind == 1 -> getString(R.string.sniff_mux_needed)
                    m.kind == 2 -> getString(R.string.sniff_mux_audio_only)
                    m.kind == 3 && m.resolveState == 1 -> getString(R.string.sniff_resolving)
                    m.kind == 3 && m.resolveState == 3 -> getString(R.string.sniff_resolve_empty)
                    m.kind == 3 -> getString(R.string.sniff_resolve_hint)
                    else -> null
                }
                if (hint != null) textCol.addView(TextView(this).apply {
                    text = hint
                    textSize = 11.5f
                    maxLines = 2
                    setPadding(0, dp(1), 0, 0)
                    setTextColor(primary)
                })
                card.addView(textCol)

                if (m.kind == 3) {
                    // player page: try to dig the real links out of it, or open it
                    card.addView(ImageButton(this).apply {
                        setImageResource(R.drawable.ic_open_new)
                        background = com.zephyr.browser.ui.MenuSheets.roundedRipple(this@MainActivity, 22)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                        imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
                        contentDescription = getString(R.string.sniff_open_tab)
                        setOnClickListener {
                            sheet.dismiss()
                            openEmbedInTab(m.url)
                        }
                    })
                    if (m.resolveState != 1) card.addView(
                        actionPill(R.string.sniff_find_video, R.drawable.ic_search) { resolveEmbed(m) }
                    )
                } else {
                    card.addView(ImageButton(this).apply {
                        setImageResource(R.drawable.ic_play)
                        background = com.zephyr.browser.ui.MenuSheets.roundedRipple(this@MainActivity, 22)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                        imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
                        contentDescription = getString(R.string.sniff_open_player)
                        setOnClickListener {
                            try {
                                startActivity(android.content.Intent.createChooser(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(m.url), "video/*")
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }, getString(R.string.sniff_open_player)))
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, R.string.app_not_found, Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                    card.addView(actionPill(R.string.dm_download, R.drawable.ic_download) {
                        sheet.dismiss()
                        if (m.kind == 1) enqueueMuxDownloadFor(m) else downloadMediaUrl(m.url, m.label, m.kind)
                    })
                }
                listCol.addView(card)
            }
        }
        rebuild()

        // auto-resolve the player rows as soon as the sheet opens — this is the
        // whole point: the user wants the VIDEO, not a link to the player page
        foundMedia.toList().filter { it.kind == 3 && it.resolveState == 0 }
            .take(3).forEach { resolveEmbed(it) }

        // sniffer toggle INSIDE the sheet (user: «с возможностью убрать этот сниффер»)
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            isClickable = true
        }
        val sw = android.widget.Switch(this).apply { isChecked = Prefs.videoSniffer }
        toggleRow.addView(TextView(this).apply {
            text = getString(R.string.sniff_toggle)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(onSurface)
        })
        toggleRow.addView(sw)
        toggleRow.setOnClickListener { sw.toggle() }
        sw.setOnCheckedChangeListener { _, on -> Prefs.setVideoSniffer(on); rebuild() }
        root.addView(toggleRow)

        // «scan again» — re-probes the page in place, no sheet flicker
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = com.zephyr.browser.ui.MenuSheets.roundedRipple(this@MainActivity, 18)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_refresh_list)
                setPadding(0, 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                imageTintList = android.content.res.ColorStateList.valueOf(primary)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.sniff_refresh)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(primary)
            })
            setOnClickListener {
                try { tabs.current?.webView?.evaluateJavascript(Scripts.MEDIA_PROBE, null) } catch (_: Exception) {}
                main.postDelayed({ if (sheet.isShowing) rebuild() }, 900)
            }
        })

        sheet.setContentView(root)
        sheet.show()
        // v1.7.25b: results from the probe land through the bridge a moment
        // later — refresh the rows IN PLACE instead of reopening the sheet.
        main.postDelayed({ try { if (sheet.isShowing) rebuild() } catch (_: Exception) {} }, 900)
        main.postDelayed({ try { if (sheet.isShowing) rebuild() } catch (_: Exception) {} }, 2200)
    }

    /**
     * v1.7.26 (user: «нажал открыть — он открывает в новой вкладке и белый
     * экран»): an embedded player refuses to render as a TOP-LEVEL page — it
     * checks the Referer and serves a blank body to direct hits. Opening it in
     * a tab now replays the page it was embedded on as the Referer, which is
     * exactly what the player expects.
     */
    fun openEmbedInTab(embedUrl: String) {
        val referer = tabs.current?.pageUrlSnapshot
        val tab = tabs.newTab(embedUrl, tabs.current?.incognito ?: false)
        val wv = tabs.ensureWebView(tab)
        wireWebView(wv, tab)
        attachPageView(wv)
        hideHome()
        try {
            if (!referer.isNullOrBlank()) {
                wv.loadUrl(embedUrl, mapOf("Referer" to referer))
            } else {
                wv.loadUrl(embedUrl)
            }
        } catch (_: Exception) {
            try { wv.loadUrl(embedUrl) } catch (_: Exception) {}
        }
        tab.pageUrlSnapshot = embedUrl
    }

    /** Downloads a sniffed media URL (HLS → segment stitcher, direct → normal chain). */
    fun downloadMediaUrl(url: String, label: String = "", kind: Int = 0) {
        val tab = tabs.current
        val ua = tab?.webView?.settings?.userAgentString
        val referer = tab?.pageUrlSnapshot
        val cookies = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        if (url.contains(".m3u8", ignoreCase = true)) {
            DownloadEngine.enqueueHls(url, "video_$stamp.ts", ua, cookies, referer)
            Toast.makeText(this, R.string.dl_started, Toast.LENGTH_SHORT).show()
        } else {
            // v1.7.8: youtube CDN hits get a meaningful name (the old chain saved
            // them as the useless "videoplayback" without extension).
            // v1.7.9: audio itags get an .m4a name; adaptive video names resolve
            // through the video table (used when no audio track was sniffed).
            val name = if (url.contains("googlevideo.com")) {
                val itag = Regex("""\bitag=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
                when {
                    kind == 2 || YT_AUDIO.containsKey(itag) -> "youtube_audio_$stamp.m4a"
                    else -> {
                        val q = YT_MUXED[itag] ?: YT_VIDEO[itag] ?: "video"
                        "youtube_${q}_$stamp.mp4"
                    }
                }
            } else {
                Urls.guessName(url).ifBlank { "video_$stamp" }.let {
                    if (it.contains('.')) it else "$it.mp4"
                }
            }
            startDownload(url, ua, null, name)
        }
    }

    /**
     * v1.7.9 (user: «Найденные видео — опция хорошая, но ничего не парсится»):
     * an ADAPTIVE (video-only) YouTube stream is downloaded together with the
     * best sniffed audio track — DownloadEngine.enqueueMuxDownload fetches both
     * and merges them into a single .mp4 (MediaMuxer).
     */
    private fun enqueueMuxDownloadFor(m: SniffedMedia) {
        val tab = tabs.current
        val ua = tab?.webView?.settings?.userAgentString
        val referer = tab?.pageUrlSnapshot
        val audio = m.audioUrl ?: bestAudioUrl
        if (audio == null) {
            // no audio track was sniffed on this page — download the video
            // alone rather than nothing (name says the quality)
            downloadMediaUrl(m.url, m.label, kind = 1)
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val q = Regex("""(\d{3,4}p\d*)""").find(m.label)?.groupValues?.get(1) ?: "video"
        val name = "youtube_${q}_$stamp.mp4"
        val cookies = try { CookieManager.getInstance().getCookie(m.url) } catch (_: Exception) { null }
        DownloadEngine.enqueueMuxDownload(
            videoUrl = m.url,
            audioUrl = audio,
            fileName = name,
            userAgent = ua,
            cookies = cookies,
            referer = referer
        )
        Toast.makeText(this, R.string.sniff_mux_progress, Toast.LENGTH_SHORT).show()
    }

    fun onVisitedHistory(url: String) {
        val tab = tabs.current ?: return
        if (url.isNotEmpty() && url != Urls.DEFAULT_HOME) {
            tab.pageUrlSnapshot = url
            updateOmniboxForTab(tab)
            updateSecurityIcon()
        }
    }

    fun onReceivedTitle(title: String) {
        tabs.current?.let { it.title = title }
        // v1.7.5: the letter-avatar fallback uses the tab title's initial — refresh
        // the omnibox icon when the title lands (Google SERP → "G" instead of the
        // previous page's leftover).
        tabs.current?.pageUrlSnapshot?.takeIf { it.isNotEmpty() && it != Urls.DEFAULT_HOME }?.let { loadFaviconFor(it) }
    }

    private fun recordHistory(url: String, title: String) {
        val tab = tabs.current ?: return
        if (tab.incognito || Prefs.alwaysIncognito) return
        // v1.7.6: offline archives (file://) never land in history
        if (url.startsWith("file:") || url.startsWith("content:")) return
        if (pageHistoryUrls.isNotEmpty() && pageHistoryUrls.last() == url) return
        pageHistoryUrls.addLast(url)
        if (pageHistoryUrls.size > 400) pageHistoryUrls.removeFirst()
        ioScope.launch {
            try {
                this@MainActivity.db.historyDao().insert(com.zephyr.browser.db.HistoryEntity(url = url, title = title))
            } catch (_: Exception) {}
        }
    }

    /** v1.7.5: displayed-favicon key — the omnibox icon is re-set on EVERY call and
     *  the async network refresh can only land while its key is still current.
     *  (user: «на странице результатов Google иконка оставалась ютубовской до входа
     *  на сайт» — whatever path missed the refresh, the icon can no longer survive
     *  a navigation now: the synchronous letter-avatar/cache image is set first.) */
    private var displayedFaviconKey: String? = null

    private fun loadFaviconFor(url: String) {
        if (url == Urls.DEFAULT_HOME || url.isEmpty()) {
            displayedFaviconKey = null
            imgFavicon.visibility = View.GONE
            return
        }
        val key = FaviconLoader.keyFor(url)
        displayedFaviconKey = key
        // immediate: cached or letter avatar (colored!) — never tinted
        val bmp = FaviconLoader.load(this, url, tabs.current?.title ?: Urls.domainOf(url))
        imgFavicon.setImageBitmap(bmp)   // COLORED original favicon — no tint even in private mode
        imgFavicon.visibility = View.VISIBLE
        // v1.7.84: the network refresh asks a third party for this site's icon,
        // which tells that third party the domain is being visited. Two rules
        // follow, and neither existed before:
        //   · never in a private tab — a browsing mode that quietly announced
        //     every domain to an outside service would be lying to the user;
        //   · never when the user has turned it off.
        // The letter avatar and whatever WebView itself reports still work, so
        // switching this off costs appearance, not function.
        val privateTab = tabs.current?.incognito == true || Prefs.alwaysIncognito
        if (FaviconLoader.cached(url) == null && Prefs.faviconNetwork && !privateTab) {
            ioScope.launch {
                val net = FaviconLoader.fetchNetwork(url)
                if (net != null) FaviconLoader.store(url, net)
                main.post {
                    if (tabs.current?.pageUrlSnapshot == url && displayedFaviconKey == key) {
                        imgFavicon.setImageBitmap(FaviconLoader.load(this@MainActivity, url, tabs.current?.title ?: ""))
                    }
                }
            }
        }
    }

    fun onMainFrameError(url: String, description: String, code: Int) {
        if (url == "about:blank") return
        // v1.7.39 (user: «появилось окно „Не удалось загрузить страницу", а
        // уведомления нет; жму Повторить — сзади мелькает белый фон и красный
        // баннер»): there were TWO error UIs fighting each other — this overlay
        // and a separate offline banner. One is enough, so the overlay itself
        // now states the real reason when the device is simply offline.
        if (!isOnline()) {
            showOfflineNotice(false)
            showError(getString(R.string.net_offline), getString(R.string.net_offline_hint))
        } else {
            showError(getString(R.string.err_cannot_load), "$description ($code)\n$url")
        }
    }

    fun onMainFrameHttpError(url: String, statusCode: Int) {
        if (statusCode >= 500 || statusCode == 404) {
            showError(getString(R.string.err_http, statusCode), url)
        }
    }

    /**
     * v1.7.50: the certificate warning, now with working buttons.
     *
     * The dialog is not cancellable: a tap beside it used to "close" the
     * warning while the page silently stayed blocked, which read as the
     * least-broken of three broken options. Either answer is now a real
     * decision on the live handler.
     */
    fun onSslError(handler: android.webkit.SslErrorHandler, pageUrl: String, errorUrl: String, error: Int) {
        var answered = false
        val reason = when (error) {
            android.net.http.SslError.SSL_EXPIRED -> getString(R.string.ssl_reason_expired)
            android.net.http.SslError.SSL_IDMISMATCH -> getString(R.string.ssl_reason_mismatch)
            android.net.http.SslError.SSL_UNTRUSTED -> getString(R.string.ssl_reason_untrusted)
            android.net.http.SslError.SSL_NOTYETVALID -> getString(R.string.ssl_reason_notyet)
            else -> getString(R.string.ssl_reason_other)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ssl_error_title)
            .setMessage(
                getString(R.string.ssl_error_msg, Urls.domainOf(errorUrl)) + "\n\n" + reason
            )
            .setCancelable(false)
            .setPositiveButton(R.string.continue_anyway) { _, _ ->
                if (answered) return@setPositiveButton
                answered = true
                // trusted for this session only — never persisted to disk
                sslAccepted.add(Urls.originOf(errorUrl))
                try { handler.proceed() } catch (_: Throwable) {}
            }
            .setNegativeButton(R.string.back_to_safety) { _, _ ->
                if (answered) return@setNegativeButton
                answered = true
                try { handler.cancel() } catch (_: Throwable) {}
                showError(getString(R.string.ssl_error_title), getString(R.string.ssl_blocked, Urls.domainOf(errorUrl)))
            }
            .setOnDismissListener {
                // a dismissal we did not handle must still release the handler,
                // otherwise the page hangs forever waiting for an answer
                if (!answered) {
                    answered = true
                    try { handler.cancel() } catch (_: Throwable) {}
                }
            }
            .show()
    }

    /** True while the user has accepted this origin's certificate this session. */
    fun isSslAccepted(url: String): Boolean = try {
        Urls.originOf(url) in sslAccepted
    } catch (_: Throwable) { false }

    private val sslAccepted = HashSet<String>()

    fun onRenderGone(): Boolean {
        val tab = tabs.current ?: return true
        val url = tab.pageUrlSnapshot
        tabs.closeTab(tab, selectReplacement = false)
        Toast.makeText(this, R.string.renderer_crashed, Toast.LENGTH_SHORT).show()
        navigate(url)
        return true
    }

    private fun showError(title: String, text: String) {
        errorOverlay.findViewById<TextView>(R.id.errorTitle).text = title
        errorOverlay.findViewById<TextView>(R.id.errorText).text = text
        errorOverlay.visibility = View.VISIBLE
        errorOverlay.bringToFront()
    }

    private fun hideError() {
        errorOverlay.visibility = View.GONE
    }

    // ---------------- v1.7.5: EDGE-SWIPE NAVIGATION GESTURES ----------------
    // (the promised feature from the 1.7.x roadmap — swipe from the left edge to
    // go BACK, from the right edge to go FORWARD, with a live arrow indicator).
    // Implemented in dispatchTouchEvent: events flow to the page UNCHANGED until
    // the gesture is confirmed (horizontal displacement beats vertical 2:1 and
    // passes the engage threshold) — only then the rest of the stream is consumed
    // so the page stops scrolling and the release performs the navigation.

    private var edgeDownX = -1f
    private var edgeDownY = -1f
    private var edgeSide = 0            // 0 none, 1 left(back), 2 right(forward)
    private var edgeTracking = false
    private var edgeConsumed = false
    private var edgeIndicator: LinearLayout? = null

    /** Floating accent pill with a back/forward arrow, shown mid-edge while dragging.
     *  v1.7.6 (user: «убери тень у кнопок!»): elevation removed — the pill is now a
     *  FLAT rounded chip without the drop shadow. */
    private fun setupEdgeGestureIndicator() {
        val ind = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            // v1.7.38 (user: «кнопки назад-вперёд крупные, сделай аккуратнее»):
            // 40dp chip with a 20dp glyph read as a button rather than a hint.
            // 28dp chip / 15dp glyph, and a softer fill.
            setPadding(dp(7), dp(7), dp(7), dp(7))
            elevation = 0f
            translationZ = 0f
            visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(0xE6182A38.toInt())
            }
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_back)
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15))
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            })
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        rootView.addView(ind, lp)
        edgeIndicator = ind
    }

    /** API 29+: claim the two edge strips from the system gesture zone.
     *  v1.7.10 FIX 2a: 38dp (was 28dp) — the system back gesture was still eating
     *  most of the detection strip; the detection strip itself is 34dp now. */
    private fun requestEdgeGestureExclusion() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val w = dp(38)
            val h = rootView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            val rects = listOf(
                android.graphics.Rect(0, 0, w, h),
                android.graphics.Rect(rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels - w, 0,
                    (rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels), h)
            )
            rootView.setSystemGestureExclusionRects(rects)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && android.os.Build.VERSION.SDK_INT >= 29) {
            try { requestEdgeGestureExclusion() } catch (_: Exception) {}
        }
        // v1.7.11: canonical immersive defense — dialogs, the WebView's own media
        // controls, keyboard and video overlay transitions all churn window focus
        // and used to resurrect the status bar DURING fullscreen video with
        // nothing left to re-hide it («строка состояния не исчезает»).
        if (hasFocus) keepSystemBarsHiddenIfFullscreen()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        try {
            if (handleEdgeGesture(ev)) return true
        } catch (_: Exception) {}
        try {
            trackDoubleTap(ev)   // v1.7.6: double-tap → top/bottom of page
        } catch (_: Exception) {}
        return super.dispatchTouchEvent(ev)
    }

    /** v1.7.6 (user: «двойной тап — топ/низ страницы»): two clean taps within 320 ms
     *  toggle the page between top and bottom. Skipped while the toolbar/sheets are
     *  hit, on the home screen, and on VIDEO sites — their players own double-tap
     *  gestures (YouTube seek ±10 s) which must keep working. */
    private fun trackDoubleTap(ev: android.view.MotionEvent) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { dtapDownX = ev.x; dtapDownY = ev.y }
            android.view.MotionEvent.ACTION_UP -> {
                val cleanTap = kotlin.math.abs(ev.x - dtapDownX) < dp(18) &&
                    kotlin.math.abs(ev.y - dtapDownY) < dp(18)
                val now = android.os.SystemClock.uptimeMillis()
                val isDouble = cleanTap && dtapLastUpClean &&
                    now - dtapLastUpAt < 320 &&
                    kotlin.math.abs(ev.x - dtapLastUpX) < dp(48) &&
                    kotlin.math.abs(ev.y - dtapLastUpY) < dp(48)
                dtapLastUpAt = now; dtapLastUpX = ev.x; dtapLastUpY = ev.y
                dtapLastUpClean = cleanTap
                if (isDouble && doubleTapScrollAllowed(ev)) {
                    dtapLastUpClean = false   // no triple-tap chains
                    togglePageTopBottom()
                }
            }
        }
    }

    private fun doubleTapScrollAllowed(ev: android.view.MotionEvent): Boolean {
        if (!Prefs.doubleTapScroll) return false
        if (fullscreenContainer.visibility == View.VISIBLE ||
            tabSwitcher.visibility == View.VISIBLE ||
            suggestionsPanel.visibility == View.VISIBLE ||
            errorOverlay.visibility == View.VISIBLE) return false
        if (ev.y < topBar.bottom) return false   // toolbar taps never scroll pages
        val tab = tabs.current ?: return false
        if (tab.isHome) return false
        if (tab.webView == null) return false
        val host = Urls.domainOf(tab.pageUrlSnapshot).removePrefix("www.")
        val videoHosts = listOf("youtube.com", "youtu.be", "music.youtube.com", "youtube-nocookie.com",
            "vimeo.com", "twitch.tv", "rutube.ru", "vk.com", "vkvideo.ru", "tiktok.com", "dzen.ru", "ok.ru")
        return videoHosts.none { host == it || host.endsWith(".$it") }
    }

    private fun togglePageTopBottom() {
        val wv = tabs.current?.webView ?: return
        try {
            val contentH = (wv.contentHeight * wv.scale).toInt()
            val target = if (wv.scrollY > wv.height / 3) 0 else (contentH - wv.height).coerceAtLeast(0)
            wv.scrollTo(0, target)
        } catch (_: Exception) {}
    }

    /** Returns true when the gesture is CONFIRMED and the stream should be consumed. */
    private fun handleEdgeGesture(ev: android.view.MotionEvent): Boolean {
        if (!Prefs.edgeGestures) { resetEdgeGesture(); return false }
        // never in fullscreen video / tabs screen / error overlay
        if (fullscreenContainer.visibility == View.VISIBLE ||
            tabSwitcher.visibility == View.VISIBLE) { resetEdgeGesture(); return false }
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // v1.7.10 FIX 2a: 26dp was too thin — on Android 10+ the system
                // back gesture ate most of the strip before this handler could
                // engage. Widened to 34dp (exclusion rect follows at 38dp).
                val edge = dp(34)
                val w = rootView.width
                // v1.7.10 FIX 2e: a touch that starts over the toolbar never
                // becomes an edge gesture — toolbar drags (omnibox, slot row)
                // belong to the toolbar, not to page back/forward.
                val overToolbar = ev.y < topBar.bottom
                edgeSide = when {
                    overToolbar -> 0
                    ev.x <= edge -> 1
                    ev.x >= w - edge -> 2
                    else -> 0
                }
                edgeTracking = edgeSide != 0
                edgeConsumed = false
                edgeDownX = ev.x
                edgeDownY = ev.y
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!edgeTracking) return false
                val dx = ev.x - edgeDownX
                val dy = ev.y - edgeDownY
                // direction: left edge must swipe RIGHT (back), right edge swipe LEFT (forward)
                val dirOk = if (edgeSide == 1) dx > 0 else dx < 0
                val adx = kotlin.math.abs(dx)
                val ady = kotlin.math.abs(dy)
                // v1.7.93 (user: «в ПК режиме не всегда могу двигать страницу
                // влево-вправо»). In desktop mode the page is wider than the
                // screen and has to be panned sideways — and you grab near an
                // edge to do it, which is exactly where this gesture lives. The
                // drag was going to back/forward instead of the page, which is
                // why it worked from the middle and not from the sides.
                //
                // The page wins while it still has somewhere to scroll. Once it
                // reaches its edge the gesture engages as before, so nothing is
                // lost on an ordinary page — those cannot scroll sideways at
                // all, and this check is false for them from the start.
                if (edgeTracking && !edgeConsumed && dirOk) {
                    val wv = tabs.current?.webView
                    // left edge swipes right → the page would scroll LEFT
                    val towards = if (edgeSide == 1) -1 else 1
                    if (wv != null && wv.canScrollHorizontally(towards)) {
                        edgeTracking = false
                        return false
                    }
                }
                if (!edgeConsumed) {
                    // v1.7.10 FIX 2c: engage at 40dp with a slightly relaxed 1.8:1
                    // horizontal dominance (was 46dp / 2:1 — page scroll often won).
                    if (dirOk && adx > dp(40) && adx > ady * 1.8f) {
                        edgeConsumed = true
                        showEdgeIndicator()
                    } else if (ady > dp(30) && ady > adx * 1.4f) {
                        // v1.7.10 FIX 2b: only a CLEARLY VERTICAL drag cancels
                        // tracking (the page is scrolling). The old rule bailed on
                        // any 18dp wrong-direction jitter — that is exactly why the
                        // gestures "то работают, то нет". Wrong-way horizontal moves
                        // simply don't engage anymore.
                        edgeTracking = false
                    }
                }
                if (edgeConsumed) {
                    moveEdgeIndicator(adx)
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (edgeConsumed) {
                    val dx = ev.x - edgeDownX
                    val fired = kotlin.math.abs(dx) > dp(84)
                    hideEdgeIndicator()
                    if (ev.actionMasked != android.view.MotionEvent.ACTION_CANCEL && fired) {
                        val wv = tabs.current?.webView
                        if (edgeSide == 1 && wv != null && wv.canGoBack()) wv.goBack()
                        else if (edgeSide == 2 && wv != null && wv.canGoForward()) wv.goForward()
                    }
                    val consumed = edgeConsumed
                    resetEdgeGesture()
                    return consumed
                }
                resetEdgeGesture()
            }
        }
        return false
    }

    private fun resetEdgeGesture() {
        edgeTracking = false
        edgeConsumed = false
        edgeSide = 0
        hideEdgeIndicator()
    }

    private fun showEdgeIndicator() {
        val ind = edgeIndicator ?: return
        (ind.getChildAt(0) as? android.widget.ImageView)?.setImageResource(
            if (edgeSide == 1) R.drawable.ic_back else R.drawable.ic_forward)
        val lp = ind.layoutParams as FrameLayout.LayoutParams
        lp.gravity = android.view.Gravity.CENTER_VERTICAL or
            (if (edgeSide == 1) android.view.Gravity.START else android.view.Gravity.END)
        ind.layoutParams = lp
        ind.translationX = if (edgeSide == 1) -dp(8).toFloat() else dp(8).toFloat()
        ind.alpha = 1f
        ind.visibility = View.VISIBLE
        ind.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start()
    }

    private fun moveEdgeIndicator(dist: Float) {
        val ind = edgeIndicator ?: return
        val clamped = dist.coerceAtMost(dp(110).toFloat())
        // the pill peeks out further the harder the drag; full peek = threshold met
        ind.translationX = if (edgeSide == 1) (-dp(8) + clamped * 0.35f)
        else (dp(8) - clamped * 0.35f)
        ind.scaleX = 1f + (clamped / 420f)
        ind.scaleY = 1f + (clamped / 420f)
    }

    private fun hideEdgeIndicator() {
        val ind = edgeIndicator ?: return
        ind.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(130)
            .withEndAction { ind.visibility = View.GONE }.start()
    }

    private fun setupErrorOverlay() {
        errorOverlay.findViewById<Button>(R.id.btnErrorRetry).setOnClickListener {
            // v1.7.39: retrying while still offline used to reload anyway, which
            // flashed the WebView's own white error page behind this overlay on
            // every single tap. Check first, and just say so.
            if (!isOnline()) {
                errorOverlay.findViewById<TextView>(R.id.errorTitle).text =
                    getString(R.string.net_offline)
                errorOverlay.findViewById<TextView>(R.id.errorText).text =
                    getString(R.string.net_offline_hint)
                Toast.makeText(this, R.string.net_still_offline, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideError()
            reload()
        }
    }

    // ---------------- omnibox state ----------------

    private fun updateOmniboxForTab(tab: TabManager.Tab?) {
        if (tab == null || tab.isHome) {
            // v1.5.0 FIX 6: clear UNCONDITIONALLY. The old `!omniboxInput.hasFocus()`
            // guard left the PREVIOUS page's URL in the field when a new (incognito) home
            // tab opened while the omnibox was focused — github.com leaked into the
            // fresh incognito tab. Focus is never stolen here, only the text is cleared.
            val engine = Prefs.currentEngine()
            omniboxInput.hint = getString(R.string.omnibox_engine_hint, getString(engine.nameRes))
            omniboxInput.setText("")
            imgFavicon.visibility = View.GONE
        } else {
            val url = tab.pageUrlSnapshot
            // v1.4.2 FIX 1: percent-DECODED display URL (yandex.ru/search/?text=котики, not %D0%BA…)
            if (!omniboxInput.hasFocus()) omniboxInput.setText(Urls.prettyUrl(url))
            loadFaviconFor(url)
        }
        updateSecurityIcon()
    }

    private fun updateSecurityIcon() {
        val tab = tabs.current
        val url = tab?.pageUrlSnapshot ?: ""
        val secure = url.startsWith("https://")
        btnSiteSecurity.setImageResource(if (secure) R.drawable.ic_lock else R.drawable.ic_lock_open)
        btnSiteSecurity.visibility = if (tab == null || tab.isHome) View.GONE else View.VISIBLE
    }

    private fun updateTabCounter() {
        tabCounter.text = tabs.tabs.size.toString()
    }

    // ---------------- incognito styling (TOR-like purple gradient) ----------------

    private fun applyIncognitoUi(incognito: Boolean) {
        if (incognito) {
            rootView.setBackgroundResource(R.drawable.bg_incognito_root)
            // v1.5.0 FIX 4: the toolbar is the TOP of the gradient (status bar tint
            // 0xFF2E1E4F continues seamlessly into it); was a flat #241A3D.
            topBar.setBackgroundResource(R.drawable.bg_incognito_bar)
            omniboxPill.setBackgroundResource(R.drawable.bg_incognito_omnibox)
            omniboxInput.setTextColor(0xFFEDE7FB.toInt())
            omniboxInput.setHintTextColor(0xFFB7A7DA.toInt())
            tabCounter.setTextColor(0xFFEDE7FB.toInt())
            // v1.6.0 PURPLE-LEAK FIX: setBackgroundResource(same resid) early-returns in
            // View (the existing drawable is kept) — so the purple tint set here SURVIVED
            // the switch back to a regular tab ("иконка вкладок фиолетовая"). The tint is
            // now applied EXPLICITLY (mutate!) in BOTH branches.
            tabCounter.setBackgroundResource(R.drawable.bg_tab_counter)
            tabCounter.background?.mutate()?.setTint(0xFF3B2B5E.toInt())
            // v1.5.0 FIX 4: system bars are part of the gradient now — clearly purple,
            // not the old flat grey-ish #1B1230.
            window.statusBarColor = 0xFF2E1E4F.toInt()
            window.navigationBarColor = 0xFF1F1740.toInt()
            setLightStatusIcons(false)   // dark purple bg → white icons
            setLightNavIcons(false)      // v1.6.0: nav bar icons too (was white-on-dark by luck)
            homeView.setIncognito(true)
            tabSwitcher.setIncognito(true)
        } else {
            rootView.setBackgroundResource(0)
            val ta = obtainStyledAttributes(Prefs.themeStyle(), intArrayOf(R.attr.zBackground))
            val bg = ta.getColor(0, 0xFFFFFFFF.toInt())
            ta.recycle()
            rootView.setBackgroundColor(bg)
            topBar.setBackgroundColor(resolveThemeColor(R.attr.zToolbar))
            omniboxPill.setBackgroundResource(R.drawable.bg_omnibox)
            omniboxInput.setTextColor(resolveThemeColor(R.attr.colorOnSurface))
            omniboxInput.setHintTextColor(resolveThemeColor(R.attr.colorOnSurfaceVariant))
            tabCounter.setTextColor(resolveThemeColor(R.attr.colorOnSurfaceVariant))
            // v1.6.0 PURPLE-LEAK FIX (see the incognito branch): clear the tint explicitly.
            tabCounter.setBackgroundResource(R.drawable.bg_tab_counter)
            tabCounter.background?.mutate()?.setTintList(null)
            window.statusBarColor = bg
            window.navigationBarColor = bg
            // v1.5.0 FIX 4: restore the THEME's status bar icon appearance
            // (light theme → dark icons, dark theme → white icons).
            setLightStatusIcons(themeUsesLightStatusIcons())
            // v1.6.0 NAV-BAR FIX: light themes got a LIGHT navigation bar with the
            // DEFAULT (white) nav icons — invisible buttons ("внизу кнопки белые и их
            // не видно"). Icon appearance now follows the theme, same as the status bar.
            setLightNavIcons(themeUsesLightStatusIcons())
            homeView.setIncognito(false)
            tabSwitcher.setIncognito(false)
        }
        applyIconTints()
        // v1.7.10 FIX 3b: incognito/theme repaints write statusBarColor /
        // systemUiVisibility and RESURRECTED the hidden status bar while a
        // fullscreen video was playing — re-assert the immersive hide then.
        keepSystemBarsHiddenIfFullscreen()
    }

    /** v1.5.0 FIX 4: status bar icon appearance. `light` = "light status bar" = DARK icons
     *  (light backgrounds); false → white icons (dark backgrounds). API 30+ uses
     *  WindowInsetsControllerCompat, older devices fall back to systemUiVisibility. */
    private fun setLightStatusIcons(light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = light
        } else {
            @Suppress("DEPRECATION")
            val flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        // v1.7.10 FIX 3b: this write can resurrect the hidden status bar during
        // fullscreen video — re-hide the bars if that is on screen.
        keepSystemBarsHiddenIfFullscreen()
    }

    /** v1.6.0: navigation bar icon appearance — the mirror of [setLightStatusIcons]
     *  for the BOTTOM system bar (API 26+; below that Android never light-styled nav). */
    private fun setLightNavIcons(light: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = light
            } else {
                @Suppress("DEPRECATION")
                val flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (light)
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
        // v1.7.10 FIX 3b: see keepSystemBarsHiddenIfFullscreen
        keepSystemBarsHiddenIfFullscreen()
    }

    /** v1.7.10 FIX 3: painting statusBarColor / systemUiVisibility from theme or incognito
     *  code paths resurrected the hidden status bar during fullscreen video. */
    private fun keepSystemBarsHiddenIfFullscreen() {
        if (fullscreenContainer.visibility != View.VISIBLE) return
        try {
            // v1.7.11: a theme/incognito repaint may also have re-tinted the bars
            // back to the theme color — repaint BLACK so a peeked bar always
            // matches the navigation bar (user: «не чёрная как панель навигации»).
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK
            // v1.7.12 nuclear-option re-asserts (idempotent + cheap): the window-
            // level status-bar hide and the black window background survive any
            // insets churn even when the hide above is defeated by the OEM ROM.
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.statusBars())
            c.hide(WindowInsetsCompat.Type.navigationBars())
            c.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {}
    }

    /** The current theme's `windowLightStatusBar` (true = dark icons on light bg). */
    private fun themeUsesLightStatusIcons(): Boolean {
        val ta = obtainStyledAttributes(Prefs.themeStyle(), intArrayOf(android.R.attr.windowLightStatusBar))
        val light = ta.getBoolean(0, false)
        ta.recycle()
        return light
    }

    private fun applyIconTints() {
        val tab = tabs.current
        val incognito = tab?.incognito == true
        val tint = if (incognito) 0xFFB7A7DA.toInt() else resolveThemeColor(R.attr.colorOnSurfaceVariant)
        val lockTint = if (incognito) 0xFFEDE7FB.toInt() else resolveThemeColor(R.attr.colorOnSurfaceVariant)
        findViewById<ImageButton>(R.id.btnHome).imageTintList = android.content.res.ColorStateList.valueOf(tint)
        findViewById<ImageButton>(R.id.btnMenu).imageTintList = android.content.res.ColorStateList.valueOf(tint)
        btnSiteSecurity.imageTintList = android.content.res.ColorStateList.valueOf(lockTint)
        for (i in 0 until toolbarSlots.childCount) {
            (toolbarSlots.getChildAt(i) as? ImageButton)?.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        }
        // favicon stays UNTINTED (colored original) — deliberate
    }

    private fun resolveThemeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(Prefs.themeStyle(), intArrayOf(attr))
        val c = ta.getColor(0, 0xFF000000.toInt())
        ta.recycle()
        return c
    }

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- home & tab switcher ----------------

    private fun setupHome() {
        homeView.onNavigate = { url -> navigate(url) }
        homeView.onVoice = { startVoiceSearch() }
        homeView.onImageSearch = { openImageSearch(Prefs.engineId) }
        homeView.onAiChat = { openAiChat(Prefs.engineId) }
        homeView.onEngineClick = { showEnginePicker() }
    }

    private fun setupTabSwitcher() {
        tabSwitcher.onSelect = { tab -> tabs.select(tab); closeTabSwitcher() }
        tabSwitcher.onClose = { tab -> tabs.closeTab(tab) }
        tabSwitcher.onNewTab = { incognito -> tabs.newTab(Urls.DEFAULT_HOME, incognito); closeTabSwitcher() }
        // v1.7.4: header back arrow — a VISIBLE way out of the tabs screen
        tabSwitcher.onBack = { closeTabSwitcher() }
        // v1.7.7 (user: «при очистке главной можно было выбирать какие группы вкладок
        // очистить, какие нет»): «Закрыть все» is now a SELECTIVE checkbox dialog —
        // ungrouped tabs, every named group, and pinned tabs each get a row; pick
        // what dies and what survives. Pinned rows are unchecked by default.
        tabSwitcher.onCloseAll = fun(incognito: Boolean) {
            val section = if (incognito) tabs.privateTabs() else tabs.regularTabs()
            if (section.isEmpty()) {
                closeTabSwitcher(); return
            }
            val pinned = section.filter { it.pinned }
            val ungrouped = section.filter { !it.pinned && it.group.isEmpty() }
            val groups = section.filter { !it.pinned && it.group.isNotEmpty() }
                .groupBy { it.group }

            val labels = mutableListOf<String>()
            val buckets = mutableListOf<List<TabManager.Tab>>()
            if (ungrouped.isNotEmpty()) {
                labels.add(getString(R.string.ts_close_ungrouped, ungrouped.size)); buckets.add(ungrouped)
            }
            for ((name, tabsInGroup) in groups) {
                labels.add(getString(R.string.ts_close_group_fmt, name, tabsInGroup.size)); buckets.add(tabsInGroup)
            }
            if (pinned.isNotEmpty()) {
                labels.add(getString(R.string.ts_close_pinned, pinned.size)); buckets.add(pinned)
            }
            val checked = BooleanArray(labels.size) { it < labels.size - (if (pinned.isNotEmpty()) 1 else 0) }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for ((i, label) in labels.withIndex()) {
                box.addView(android.widget.CheckBox(this).apply {
                    text = label
                    textSize = 14.5f
                    isChecked = checked[i]
                    setPadding(dp(18), dp(10), dp(14), dp(10))
                    setOnCheckedChangeListener { _, c -> checked[i] = c }
                })
            }
            val scroll = android.widget.ScrollView(this).apply { addView(box) }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ts_close_all)
                .setView(scroll)
                .setPositiveButton(R.string.delete) { _, _ ->
                    var closed = 0
                    for ((i, b) in buckets.withIndex()) {
                        if (checked[i]) {
                            closed += b.size
                            for (t in b) tabs.closeTab(t, selectReplacement = false)
                        }
                    }
                    // empty group REGISTRY entries of fully-closed groups are dropped too
                    for (gName in groups.keys) {
                        if (tabs.tabs.none { t -> t.group == gName }) tabs.groupNames.remove(gName)
                    }
                    if (closed > 0) {
                        Toast.makeText(this, getString(R.string.ts_closed_n, closed), Toast.LENGTH_SHORT).show()
                    }
                    if (tabs.current == null) tabs.newTab(Urls.DEFAULT_HOME, Prefs.alwaysIncognito)
                    tabs.saveSession()
                    closeTabSwitcher()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        // v1.5.0 FIX 4: browsing the PRIVATE section inside the switcher re-tints the
        // system bars + toolbar purple while the switcher is open (home content is
        // hidden behind it, so a full applyIncognitoUi is safe here).
        tabSwitcher.onSectionChange = { inc -> applyIncognitoUi(inc) }
    }

    /** Logical switcher state — true while open; guards the async close animation. */
    private var switcherVisible = false

    fun openTabSwitcher() {
        hideUrlBarOverlay()
        switcherVisible = true
        tabs.switcherOpen = true   // v1.7.7: persisted — survives process death
        tabSwitcher.setSection(tabs.current?.incognito == true)
        // v1.4.1: freshen page previews right before showing — the current tab's WebView
        // is attached & sized, so its card gets a live screenshot (user report: "только домик")
        tabs.captureAllThumbnails()
        // v1.5.0 FIX 3: the CURRENT home tab has no WebView — capture the home screen
        // itself so its card shows a real screenshot. MUST run before refresh() below;
        // other (non-current) home tabs fall back to the branded home mock in cards.
        tabs.current?.let { cur ->
            if (cur.isHome && homeView.visibility == View.VISIBLE) {
                tabs.captureHomeThumbnail(cur, homeView)
            }
        }
        // v1.4.2 FIX 4: previews also go to disk → cards have images after an app restart
        for (t in tabs.tabs.toList()) tabs.persistThumbnail(t)
        // v1.5.0 FIX 7: pop-in (fade + scale). Initial state is set BEFORE the view
        // becomes visible so the first frame is already faded/scaled.
        tabSwitcher.alpha = 0f
        tabSwitcher.scaleX = 0.96f
        tabSwitcher.scaleY = 0.96f
        tabSwitcher.visibility = View.VISIBLE
        tabSwitcher.bringToFront()
        tabSwitcher.refresh()
        Anim.popIn(tabSwitcher, duration = 200, fromScale = 0.96f)
    }

    fun closeTabSwitcher() {
        switcherVisible = false
        tabs.switcherOpen = false   // v1.7.7: persisted — survives process death
        tabs.saveSession()
        if (tabSwitcher.visibility == View.VISIBLE) {
            // v1.5.0 FIX 7: fade out, then hide (withEndAction)
            Anim.fadeOut(tabSwitcher, 140) {
                if (!switcherVisible) tabSwitcher.visibility = View.GONE
            }
        } else {
            tabSwitcher.visibility = View.GONE
        }
        // Restore the CURRENT tab's chrome — the user may have been browsing the other
        // section in the switcher (onSectionChange temporarily re-tinted the bars).
        applyIncognitoUi(tabs.current?.incognito == true)
    }

    // ---------------- engine actions ----------------

    private fun showEnginePicker() {
        val engines = SearchEngines.builtIn
        val current = Prefs.engineId
        val names = engines.map { getString(it.nameRes) }.toTypedArray()
        val checked = engines.indexOfFirst { it.id == current }.let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_engine)
            .setSingleChoiceItems(names, checked) { d, which ->
                Prefs.setEngine(engines[which].id)
                d.dismiss()
                updateOmniboxForTab(tabs.current)
                homeView.reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * v1.7.55 (user on microG: «Голосовой поиск недоступен, у меня нет гугл-хлама»).
     *
     * Voice input on Android is a RecognitionService, and a de-Googled phone has
     * none — the intent simply has nobody to answer it, which is why the button
     * only ever produced "недоступен". So the system recogniser is now the
     * FIRST choice, not the only one: without it, the microphone is recorded
     * here and transcribed by the user's own AI provider (see [AiVoice]).
     */
    fun startVoiceSearch() {
        if (useSystemRecogniser()) {
            try {
                val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search))
                }
                startActivityForResult(i, VOICE_RC)
                return
            } catch (_: Exception) { /* fall through to the on-device route */ }
        }
        voiceWithoutSystem(R.string.voice_search, VOICE_RC) { text ->
            loadInCurrentTab(Urls.normalize(text) { s -> SearchEngines.searchUrl(Prefs.engineId, s) })
        }
    }

    /**
     * v1.7.79: is the Google/system recogniser the one to use?
     *
     * It is the default wherever it exists — it is the best recogniser on the
     * phone and costs nothing — but a user who does not want to talk to Google
     * can now pin a different engine in Settings, and a user without Google
     * services falls through to the on-device model instead of a dead button.
     */
    private fun useSystemRecogniser(): Boolean =
        Prefs.voiceEngine != "offline" && Prefs.voiceEngine != "cloud" &&
            com.zephyr.browser.ai.AiVoice.systemAvailable(this)

    /**
     * Everything after the system recogniser: the downloaded offline model
     * first (no network, no account), then transcription through the user's
     * own AI provider, and if neither is set up, a dialog that says so and
     * opens the screen where it is fixed.
     *
     * Shared by the URL bar and the assistant composer so the two buttons can
     * never drift apart again — which they did once already.
     */
    private fun voiceWithoutSystem(titleRes: Int, rc: Int, onText: (String) -> Unit) {
        val offlineReady = Prefs.voiceEngine != "system" && Prefs.voiceEngine != "cloud" &&
            com.zephyr.browser.ai.AiVosk.ready(this)
        val cloudReady = Prefs.voiceEngine != "offline" &&
            com.zephyr.browser.ai.AiVoice.cloudReady()

        if (!offlineReady && !cloudReady) {
            MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(R.string.voice_setup_msg)
                .setPositiveButton(R.string.ok) { _, _ ->
                    startActivity(Intent(this, com.zephyr.browser.settings.SettingsActivity::class.java)
                        .putExtra("screen", "ai"))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // re-entering through the grant callback lands in this same branch
            if (rc == AI_VOICE_RC) pendingAiVoice = onText
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), rc)
            return
        }
        if (offlineReady) com.zephyr.browser.ai.AiVosk.listen(this, onText)
        else com.zephyr.browser.ai.AiVoice.listen(this, onText)
    }

    fun openImageSearch(engineId: String) {
        val engine = SearchEngines.byId(engineId)
        navigate(engine.imageSearchUrl)
    }

    /**
     * v1.7.25b FIX (user: «закрыл приложение, открыл вновь, жму ИИ — вообще не
     * открывается… открыл страницу — открылся»): the sheet used to be shown
     * ONLY from the evaluateJavascript callback. On a cold start the home page
     * has no WebView yet, so `webView?.evaluateJavascript` short-circuited on
     * the null-safe call — no exception, no callback, nothing happened. The
     * window now ALWAYS opens; the page text, when there is a page, is folded
     * in as soon as the callback answers (guarded against a double open).
     */
    fun openAiChat(engineId: String) {
        val currentTab = tabs.current
        val url = currentTab?.pageUrlSnapshot ?: ""
        val title = currentTab?.title ?: ""
        val wv = currentTab?.webView
        if (wv == null || url.isBlank() || url.startsWith("about:")) {
            com.zephyr.browser.ai.AiAssistantSheet.show(this, url, title, "")
            return
        }
        var opened = false
        // never let a silent JS engine keep the assistant shut
        val fallback = Runnable {
            if (!opened) {
                opened = true
                com.zephyr.browser.ai.AiAssistantSheet.show(this, url, title, "")
            }
        }
        main.postDelayed(fallback, 700)
        try {
            wv.evaluateJavascript(
                "(function(){ return document.body ? document.body.innerText.substring(0, 3000) : ''; })()"
            ) { res ->
                if (opened) return@evaluateJavascript
                opened = true
                main.removeCallbacks(fallback)
                val pageText = res?.removeSurrounding("\"")
                    ?.replace("\\n", "\n")?.replace("\\t", " ") ?: ""
                com.zephyr.browser.ai.AiAssistantSheet.show(this, url, title, pageText)
            }
        } catch (_: Throwable) {
            main.removeCallbacks(fallback)
            fallback.run()
        }
    }

    // ---------------- v1.7.38: page-load progress + offline notice ----------------

    private var pageProgress: android.widget.ProgressBar? = null
    private var offlineBar: LinearLayout? = null

    /**
     * Chrome-style hairline under the omnibox. Fills left to right while the
     * page loads, then fades out — the browser had no loading feedback at all.
     */
    fun onPageProgress(percent: Int) {
        val bar = pageProgress ?: findViewById<android.widget.ProgressBar>(R.id.pageProgress)
            ?.also {
                pageProgress = it
                it.progressTintList = android.content.res.ColorStateList.valueOf(
                    com.zephyr.browser.settings.SettingsUi.accent(this))
                it.progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(0x00000000)
            } ?: return
        if (percent in 1..99) {
            if (bar.visibility != View.VISIBLE) {
                bar.visibility = View.VISIBLE
                bar.alpha = 1f
                bar.progress = 0
            }
            bar.animate().cancel()
            // animate to the new value so the line glides instead of jumping
            android.animation.ObjectAnimator.ofInt(bar, "progress", bar.progress, percent)
                .setDuration(180).start()
        } else if (percent >= 100) {
            android.animation.ObjectAnimator.ofInt(bar, "progress", bar.progress, 100)
                .setDuration(120).start()
            bar.animate().alpha(0f).setStartDelay(160).setDuration(200)
                .withEndAction { bar.visibility = View.GONE; bar.progress = 0 }.start()
        }
    }

    /** True when the device currently has a usable network. */
    fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            // v1.7.39: INTERNET alone only means "this network is supposed to
            // have internet" — a Wi-Fi with no uplink still reports it.
            // VALIDATED is the one that means traffic actually gets through.
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) { true }
    }

    /**
     * Slim banner under the omnibox: «Нет подключения к интернету».
     * Shown when a navigation is attempted offline, and auto-hidden the moment
     * connectivity is back.
     */
    fun showOfflineNotice(show: Boolean) {
        if (!show) {
            offlineBar?.let { bar ->
                bar.animate().alpha(0f).setDuration(180)
                    .withEndAction { bar.visibility = View.GONE }.start()
            }
            return
        }
        val parent = findViewById<FrameLayout>(R.id.contentStage) ?: return
        val bar = offlineBar ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF2B3261E.toInt())
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_block)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                    .apply { marginEnd = dp(10) }
            })
            addView(TextView(this@MainActivity).apply {
                setText(R.string.net_offline)
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                setText(R.string.nav_reload)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(10), dp(4), dp(4), dp(4))
                isClickable = true
                setOnClickListener {
                    if (isOnline()) { showOfflineNotice(false); reload() }
                }
            })
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { setMargins(dp(10), dp(10), dp(10), 0) }
            parent.addView(this, lp)
            offlineBar = this
        }
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.animate().alpha(1f).setDuration(180).start()
        // disappear on its own once the network comes back
        main.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                if (isOnline()) showOfflineNotice(false)
                else if (bar.visibility == View.VISIBLE) main.postDelayed(this, 2000)
            }
        }, 2000)
    }

    fun shareCurrent() {
        val url = tabs.current?.pageUrlSnapshot ?: return
        shareLink(url, tabs.current?.title ?: url)
    }

    // ---------------- v1.6.0 new actions: screenshot / PDF / page source ----------------

    /**
     * v1.6.0: capture the VISIBLE page as a PNG, save it into the system
     * Pictures/Zephyr collection (MediaStore on API 29+, legacy path below) and
     * offer a share sheet right away.
     */
    fun captureScreenshot() {
        val wv = tabs.current?.webView
        if (wv == null || tabs.current?.isHome == true) {
            Toast.makeText(this, R.string.shot_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bmp = android.graphics.Bitmap.createBitmap(wv.width, wv.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            wv.draw(canvas)
            val name = "zephyr_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            ioScope.launch {
                val uri = try { saveScreenshot(bmp, name) } catch (_: Exception) { null }
                main.post {
                    if (uri == null) {
                        Toast.makeText(this@MainActivity, R.string.shot_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, R.string.shot_saved, Toast.LENGTH_SHORT).show()
                        try {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(share, getString(R.string.menu_screenshot)))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.shot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveScreenshot(bmp: android.graphics.Bitmap, name: String): Uri? {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zephyr")
        }
        val resolver = contentResolver
        val collection =
            if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, out) }
        return uri
    }

    /** v1.6.0: save the current page as PDF through the system print pipeline. */
    fun saveAsPdf() {
        val wv = tabs.current?.webView
        if (wv == null || tabs.current?.isHome == true) {
            Toast.makeText(this, R.string.shot_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            val jobName = (tabs.current?.title?.ifEmpty { Urls.domainOf(wv.url ?: "") }.takeIf { !it.isNullOrEmpty() } ?: "page")
            pm.print(jobName, wv.createPrintDocumentAdapter(jobName), android.print.PrintAttributes.Builder().build())
        } catch (_: Exception) {
            Toast.makeText(this, R.string.pdf_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** v1.6.0: page-source viewer — the old "print" menu action was a dead empty lambda. */
    fun showPageSource() {
        val wv = tabs.current?.webView
        if (wv == null || tabs.current?.isHome == true) {
            Toast.makeText(this, R.string.shot_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        wv.evaluateJavascript("(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})();") { html ->
            val src = html?.removePrefix("\"")?.removeSuffix("\"")
                ?.replace("\\u003C", "<")?.replace("\\u003E", ">")?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")?.replace("\\t", "\t")?.replace("\\\\", "\\") ?: ""
            if (src.isEmpty()) {
                Toast.makeText(this, R.string.shot_need_page, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            val tv = TextView(this).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setTextColor(resolveThemeColor(R.attr.colorOnSurface))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                text = src.take(400_000)
                setTextIsSelectable(true)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.page_source)
                .setView(android.widget.ScrollView(this).apply { addView(tv) })
                .setPositiveButton(R.string.copy) { _, _ ->
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("html", src))
                        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /** Menu: copy current page URL to clipboard (1.2.1 parity). */
    fun copyCurrentLink() {
        val url = tabs.current?.pageUrlSnapshot ?: return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", url))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    /** Menu: open current page in an external browser / default handler (1.2.1 parity). */
    fun openInExternalBrowser() {
        val url = tabs.current?.pageUrlSnapshot ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /** Menu: pin a launcher shortcut to the current page (Android 8+, 1.2.1 parity). */
    fun addToHomeScreen() {
        val tab = tabs.current ?: return
        val url = tab.pageUrlSnapshot
        if (url.isEmpty() || tab.isHome) {
            Toast.makeText(this, R.string.add_home_need_page, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, R.string.add_home_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val sm = try {
            getSystemService(android.content.pm.ShortcutManager::class.java)
        } catch (_: Exception) { null }
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, R.string.add_home_not_supported, Toast.LENGTH_SHORT).show()
            return
        }

        val title = tab.title.ifEmpty { url }
        // v1.7.96 (user: «у ярлыка иконка моего браузера, можно чтобы была
        // иконка сайта»). Quite right: a row of shortcuts that all wear the
        // browser's own mark is a row you cannot read. The site's icon is
        // fetched first — off the main thread, because it may need the network
        // — and only then is the pin requested.
        ioScope.launch {
            val site = try { siteIconFor(url, title) } catch (_: Throwable) { null }
            val icon = try { shortcutIcon(site) } catch (_: Throwable) { null }
            main.post {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        setPackage(packageName)
                    }
                    val info = android.content.pm.ShortcutInfo.Builder(this@MainActivity, "page_" + url.hashCode())
                        .setShortLabel(title.take(20))
                        .setLongLabel(title)
                        .setIcon(
                            icon ?: android.graphics.drawable.Icon
                                .createWithResource(this@MainActivity, R.mipmap.ic_launcher)
                        )
                        .setIntent(intent)
                        .build()
                    sm.requestPinShortcut(info, null)
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, R.string.add_home_not_supported, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * The site's own icon, or the coloured letter avatar that stands in for it.
     *
     * Never null in practice: [FaviconLoader.load] falls back to the letter
     * avatar, which is still far more recognisable on a home screen than the
     * browser's mark repeated five times.
     */
    private fun siteIconFor(url: String, title: String): android.graphics.Bitmap? {
        // a sharper icon than the cache holds is worth one request — but only
        // when the user has left that switch on (see Settings → Privacy)
        if (Prefs.faviconNetwork && FaviconLoader.cached(url) == null) {
            try { FaviconLoader.fetchNetwork(url)?.let { FaviconLoader.store(url, it) } }
            catch (_: Throwable) {}
        }
        return try { FaviconLoader.load(this, url, title) } catch (_: Throwable) { null }
    }

    /**
     * Dress a favicon as a launcher icon.
     *
     * A favicon is 32–64px of artwork meant to sit next to text, and a launcher
     * will happily scale it to 192 and show the blur. So it is drawn at a little
     * over half size on a light plate, which is what a launcher expects an
     * adaptive icon to look like: content in the middle, margin for the mask to
     * crop. Without the plate, every launcher shape would clip a square favicon
     * at its corners.
     */
    private fun shortcutIcon(site: android.graphics.Bitmap?): android.graphics.drawable.Icon? {
        if (site == null) return null
        val size = 192
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        // 56% leaves the 18/108 margin an adaptive icon reserves on each side,
        // so no launcher mask can bite into the artwork
        val inner = (size * 0.56f).toInt()
        val off = (size - inner) / 2
        canvas.drawBitmap(
            site,
            android.graphics.Rect(0, 0, site.width, site.height),
            android.graphics.Rect(off, off, off + inner, off + inner),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            android.graphics.drawable.Icon.createWithAdaptiveBitmap(out)
        } else {
            android.graphics.drawable.Icon.createWithBitmap(out)
        }
    }

    fun shareLink(url: String, title: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            startActivity(Intent.createChooser(i, getString(R.string.share_via)))
        } catch (_: Exception) {}
    }

    /** v1.7.12: shares the raw crash-report text (Files launch probe dialog). */
    private fun shareCrashText(text: String) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Zephyr 1.7.17 crash report")
                putExtra(android.content.Intent.EXTRA_TEXT, text.take(60_000))
            })
        } catch (_: Exception) {}
    }

    fun translateCurrent() {
        val tab = tabs.current ?: return
        val url = tab.pageUrlSnapshot
        if (url.isEmpty() || url == Urls.DEFAULT_HOME || url == "about:blank" || tab.isHome) {
            Toast.makeText(this, R.string.translate_home_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        // v1.7.7 (user: «автоперевод всей страницы инъекционно, как Chrome, без
        // обёртки»): the Google-wrapper navigation is GONE. The page now translates
        // IN PLACE — a script collects visible text nodes, native batches them
        // through TranslationApi and swaps the text back. No navigation, no flash,
        // the original is one tap away (restore kept on the tab).
        if (tab.translated) {
            restoreOriginalText()
            return
        }
        val langs = resources.getStringArray(R.array.translate_langs)
        val codes = resources.getStringArray(R.array.translate_lang_codes)
        val def = codes.indexOf("ru").let { if (it < 0) 0 else it }
        // v1.7.14 (user: «перевод срабатывает при тапе по языку — топорно,
        // добавь кнопку Перевести»): tapping a language now only SELECTS it;
        // translation starts from the «Перевести» confirm button.
        var sel = def
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translate)
            .setSingleChoiceItems(langs, def) { _, which -> sel = which }
            .setPositiveButton(R.string.translate_do) { _, _ -> startInlineTranslation(codes[sel]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** v1.7.7: injects the collector; chunks arrive via the bridge. */
    private fun startInlineTranslation(target: String) {
        val wv = tabs.current?.webView ?: return
        tabs.current?.translationTarget = target
        wv.evaluateJavascript("(function(){try{window.__zephyrTranslate&&window.__zephyrTranslate(" +
            JSONObject.quote(target) + ");}catch(e){}})();", null)
        Toast.makeText(this, R.string.translating, Toast.LENGTH_SHORT).show()
    }

    /** v1.7.10 REWORK (user: «Перевод плохо работает и вообще переводит пару
     *  слов и все!»): the bridge now receives whole GROUPS of text (the injected
     *  collector batches ~1400 chars per call) and translates the ENTIRE batch
     *  with ONE HTTP round-trip via TranslationApi.translateBatch — a normal
     *  page used to fire 100-500 sequential requests (8s timeout each) and the
     *  user saw only the first few words change. Batches may still arrive in
     *  any order: each reply carries its own cbId, and the JS callback only
     *  touches its own groups. */
    private fun translateChunkInternal(jsonArray: String, cbId: Int) {
        val tab = tabs.current ?: return
        val target = tab.translationTarget.ifEmpty { "ru" }
        ioScope.launch {
            val srcs = mutableListOf<String>()
            try {
                val arr = org.json.JSONArray(jsonArray)
                for (i in 0 until arr.length()) srcs.add(arr.optString(i))
            } catch (_: Exception) {}
            val translated = if (srcs.isEmpty()) emptyList()
                else com.zephyr.browser.TranslationApi.translateBatch(srcs, target)
            // safety: never send fewer answers than asked (JS indexes by position)
            val out = org.json.JSONArray()
            for (i in srcs.indices) {
                val t = translated.getOrElse(i) { srcs[i] }
                out.put(t ?: srcs[i])
            }
            main.post {
                tab.webView?.evaluateJavascript(
                    "window.__zephyrTrCb&&window.__zephyrTrCb($cbId," + out.toString() + ");", null)
            }
        }
    }

    /** v1.7.10: live progress — the page script reports every finished batch;
     *  a single reused Toast shows «Перевод… N%» so the user sees it moving. */
    private var trProgressToast: Toast? = null
    private fun translationProgressInternal(done: Int, total: Int) {
        if (total <= 0) return
        try {
            val pct = (done * 100 / total).coerceIn(0, 100)
            val t = trProgressToast ?: Toast.makeText(this, "", Toast.LENGTH_SHORT).also { trProgressToast = it }
            t.setText(getString(R.string.translate_progress_fmt, pct))
            t.show()
        } catch (_: Exception) {}
    }

    /** v1.7.7: the last chunk reported done → mark the tab translated. */
    fun translationFinished() {
        tabs.current?.translated = true
        trProgressToast?.cancel(); trProgressToast = null
        Toast.makeText(this, R.string.translated_done, Toast.LENGTH_SHORT).show()
    }

    /** v1.7.7: restores the original page text (originals kept by the injected script). */
    fun restoreOriginalText() {
        val wv = tabs.current?.webView ?: return
        wv.evaluateJavascript("(function(){try{window.__zephyrTrRestore&&window.__zephyrTrRestore();}catch(e){}})();", null)
        tabs.current?.translated = false
    }

    // ---------------- v1.7.15: text-selection actions (SelWebView) ----------------

    /** v1.7.15 (user: «можно ещё добавить в браузере при выделении куска текста
     *  дополнительные функции: перевод выбранного итд, разные функции»):
     *  SelWebView appends Перевести / Найти / Поделиться / Вслух to the system
     *  text-selection ActionMode and routes the extracted selection here.
     *  action 0 = the JS could not capture the selection → toast, don't act. */
    fun onSelectionAction(action: Int, text: String) {
        when (action) {
            com.zephyr.browser.ui.SelWebView.ACT_TRANSLATE -> translateSelection(text)
            com.zephyr.browser.ui.SelWebView.ACT_SEARCH -> {
                // new tab with the CURRENT search engine (the same builder the
                // voice-search flow uses)
                tabs.newTab(SearchEngines.searchUrl(Prefs.engineId, text),
                    tabs.current?.incognito ?: false)
            }
            com.zephyr.browser.ui.SelWebView.ACT_SHARE -> {
                try {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(i, getString(R.string.share_via)))
                } catch (_: Exception) {}
            }
            com.zephyr.browser.ui.SelWebView.ACT_TTS -> speakSelection(text)
            // v1.7.48: the selection goes straight into the assistant, with the
            // question already written — no copy-paste dance
            com.zephyr.browser.ui.SelWebView.ACT_AI_EXPLAIN ->
                com.zephyr.browser.ai.AiAssistantSheet.askAbout(
                    this,
                    "Объясни простыми словами:\n\n«" + text.take(3000) + "»"
                )
            com.zephyr.browser.ui.SelWebView.ACT_AI ->
                com.zephyr.browser.ai.AiAssistantSheet.askAbout(this, text.take(3000), ask = true)
            else -> Toast.makeText(this, R.string.sel_empty, Toast.LENGTH_SHORT).show()
        }
    }

    /** v1.7.15: «Перевести» on a selection — one TranslationApi call on the IO
     *  scope (the same dispatch pattern translateChunkInternal uses); the result
     *  lands in a Material dialog with a one-tap copy. Capped at 2000 chars —
     *  the free endpoint's comfortable single-request size. */
    private fun translateSelection(text: String) {
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.sel_empty, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.sel_translating, Toast.LENGTH_SHORT).show()
        // target: the same default the page-translate flow starts from
        // (translateCurrent's def — "ru", or the first code when absent)
        val codes = resources.getStringArray(R.array.translate_lang_codes)
        val target = codes.getOrNull(codes.indexOf("ru").let { if (it < 0) 0 else it }) ?: "ru"
        val src = text.take(2000)
        ioScope.launch {
            val translated = try {
                com.zephyr.browser.TranslationApi.translate(src, target)
            } catch (_: Exception) { null }
            main.post {
                if (isFinishing) return@post
                if (translated.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.sel_translate_failed, Toast.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.sel_translate_title)
                        .setMessage(translated)
                        .setPositiveButton(R.string.sel_copy_result) { _, _ -> copyToClipboard(translated) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    /** v1.7.15: lazy TTS engine for «Вслух» on a selection. */
    private var tts: android.speech.tts.TextToSpeech? = null

    /** v1.7.15: reads the selection aloud (system TextToSpeech, default locale).
     *  First use initializes the engine asynchronously — the utterance starts
     *  from the init callback once it reports SUCCESS. */
    private fun speakSelection(text: String) {
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.sel_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val speakNow: () -> Unit = {
            try {
                val engine = tts
                if (engine != null) {
                    try { engine.setLanguage(java.util.Locale.getDefault()) } catch (_: Exception) {}
                    engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "zephyr_sel")
                    Toast.makeText(this, R.string.sel_tts_on, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.sel_tts_fail, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.sel_tts_fail, Toast.LENGTH_SHORT).show()
            }
        }
        val cur = tts
        if (cur != null) {
            speakNow()
        } else {
            try {
                tts = android.speech.tts.TextToSpeech(this) { status ->
                    // init callback arrives on a TTS thread — hop to main
                    main.post {
                        if (status == android.speech.tts.TextToSpeech.SUCCESS) speakNow()
                        else Toast.makeText(this, R.string.sel_tts_fail, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.sel_tts_fail, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Menu: clear current-site data (cookies, storage, cache) with confirmation. */
    /**
     * v1.7.38: the fire button (user: «добавить кнопку быстрое сгорание как у
     * duckduckgo»). One tap closes every tab and wipes the browsing trail —
     * history, cookies, cache, site storage, form data — then drops you on a
     * fresh home page.
     *
     * v1.7.55: WHAT it wipes is now the user's list (Настройки → Приватность →
     * Настроить кнопку) and the confirmation is a switch, so the button can be
     * either "ask me, then clear the trail" or a silent panic button. The
     * defaults are unchanged: the trail goes, bookmarks and passwords stay,
     * and anything heavier has to be switched on deliberately.
     */
    fun burnEverything() {
        if (!Prefs.burnConfirm) { doBurn(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.burn_title)
            // the list is built from the user's own selection, so the warning
            // can never promise something different from what happens
            .setMessage(getString(R.string.burn_confirm_fmt, BurnOptions.summary(this)))
            .setPositiveButton(R.string.burn_go) { _, _ -> doBurn() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doBurn() {
        BurnOptions.burn(this, ioScope)
        // a clean slate, whatever was selected
        try {
            if (tabs.tabs.isEmpty()) {
                tabs.newTab(Urls.DEFAULT_HOME, Prefs.alwaysIncognito)
                navigate(Urls.DEFAULT_HOME)
            }
        } catch (_: Throwable) {}
        Toast.makeText(this, R.string.burn_done, Toast.LENGTH_SHORT).show()
    }

    fun clearSiteDataCurrent() {
        val url = tabs.current?.pageUrlSnapshot ?: return
        if (url.isEmpty() || url == Urls.DEFAULT_HOME) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_clear_site)
            .setMessage(getString(R.string.clear_site_confirm))
            .setPositiveButton(R.string.delete) { _, _ -> clearSiteData(url) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- context menu (rich, with icons) ----------------

    private fun showContextMenu(webView: WebView, hit: android.webkit.WebView.HitTestResult) {
        val url = hit.extra ?: return
        val isImage = hit.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE ||
            hit.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isLink = hit.type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE ||
            hit.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        if (!isImage && !isLink) return
        // enrich image-anchor with the link URL
        if (hit.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            val msg = main.obtainMessage(9001)
            msg.data.putString("imgUrl", url)
            webView.requestFocusNodeHref(msg)
        }
        MenuSheets.showContextMenu(this, url, isImage, isLink, ::contextAction)
    }

    private val hrefHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            if (m.what == 9001) {
                val img = m.data.getString("imgUrl")
                val link = m.data.getString("url")
                if (img != null && link != null && link.startsWith("http")) {
                    MenuSheets.updateLinkForImageMenu(this@MainActivity, link)
                }
            }
        }
    }

    fun contextAction(action: String, url: String, linkUrl: String?) {
        when (action) {
            "open_new" -> { tabs.newTab(url, tabs.current?.incognito ?: false); }
            "open_bg" -> { tabs.newTab(url, tabs.current?.incognito ?: false, select = false) }
            "open_private" -> { tabs.newTab(url, true) }
            "open" -> navigate(url)
            "download" -> startDownload(url)
            "copy" -> copyToClipboard(url)
            "copy_link" -> copyToClipboard(linkUrl ?: url)
            "share" -> shareLink(url, url)
            "search_image" -> openImageSearch(Prefs.engineId)
            "remind" -> com.zephyr.browser.tasks.RemindLater.ask(this, url, tabs.current?.title.orEmpty())
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Zephyr", text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            if (Prefs.clipboardClear) {
                main.postDelayed({
                    try { if (cm.primaryClip?.itemCount ?: 0 > 0) cm.clearPrimaryClip() } catch (_: Exception) {}
                }, 30000)
            }
        } catch (_: Exception) {}
    }

    // ---------------- downloads ----------------

    fun onDownloadRequested(url: String, userAgent: String?, contentDisposition: String?, mime: String?, size: Long) {
        var name = contentDisposition?.let { cd ->
            Regex("filename\\*?=\"?(?:UTF-8'')?([^\"]+)\"?").find(cd)?.groupValues?.get(1)
        } ?: Urls.guessName(url)
        if (name.isEmpty()) name = "file"
        val sizeText = if (size > 0) android.text.format.Formatter.formatFileSize(this, size) else getString(R.string.dl_unknown_size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dl_title)
            .setMessage(getString(R.string.dl_msg, name, sizeText))
            .setPositiveButton(R.string.dl_download_btn) { _, _ -> startDownload(url, userAgent, mime, name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun startDownload(url: String, userAgent: String? = null, mime: String? = null, name: String = Urls.guessName(url)) {
        // v1.7.6 (user: «ссылки типа …/images?q=tbn:…»): query-only CDN URLs produce
        // useless names ("images"); extension-less generic names get a timestamped
        // image_ name — the engine's mime sniffing appends the real extension.
        val dlName = if (name.isBlank() || name == "file" || name == "images" || name == "image" || name == "get")
            "image_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        else name
        // v1.7.4: data: URIs (inline base64 images — what many modern sites hand to a
        // long-press "save image") never touch the network; they are decoded and
        // stored directly. The old engine died on them with «Загрузка не удалась».
        if (url.startsWith("data:", ignoreCase = true)) {
            val parsed = DownloadEngine.parseDataUri(url)
            if (parsed == null) {
                Toast.makeText(this, R.string.dl_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val nm = if (dlName.isBlank() || dlName == "file") "image_" +
                java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            else dlName
            DownloadEngine.enqueueBytes(url, nm, parsed.first, parsed.second, onFinish = { ok ->
                Toast.makeText(this, if (ok) R.string.dl_complete else R.string.dl_failed, Toast.LENGTH_SHORT).show()
            })
            return
        }
        // v1.7.4: blob: URLs only exist inside the page's JS context — the WebView
        // fetches the bytes and hands them to the native bridge as base64.
        if (DownloadEngine.isBlobUrl(url)) {
            downloadBlobViaPage(url, name, mime)
            return
        }
        val cookies = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        val referer = tabs.current?.pageUrlSnapshot
        val ua = userAgent ?: tabs.current?.webView?.settings?.userAgentString
        val m = mime ?: DownloadEngine.guessMime(dlName)
        // v1.7.7 (user: «уведомление загрузка началась → загрузка не удалась → фото
        // скачал системный загрузчик»): the chain is now QUIET — the native attempt
        // no longer posts a failure notification while fallbacks are still running.
        // One progress notification stays alive; the single FINAL verdict is posted
        // by whichever stage actually finishes the job.
        DownloadEngine.enqueue(
            DownloadEngine.Request(url, dlName, m, ua, cookies, referer),
            quiet = true,
            onStarted = { entryId -> chainEntryIds[url] = entryId },
            onFinish = { ok ->
                if (ok) {
                    chainEntryIds.remove(url)
                    Toast.makeText(this, R.string.dl_complete, Toast.LENGTH_SHORT).show()
                } else if (url.startsWith("http")) {
                    // v1.7.5: retry the SAME URL INSIDE the page — the fetch runs on
                    // the real browser network stack (cookies, TLS fingerprint, HTTP/2),
                    // which is exactly what CDNs 403-ing our HttpURLConnection accept.
                    // v1.7.7: final resort — the SYSTEM downloader with its own network
                    // stack; its native notification is HIDDEN and our receiver posts
                    // the completion from Zephyr (feels like a Zephyr download).
                    downloadViaPageFetch(url, dlName, m)
                } else {
                    val quietId = chainEntryIds.remove(url)
                    if (quietId != null) {
                        DownloadEngine.finishQuiet(quietId, false, dlName, "", 0, m)
                    }
                    Toast.makeText(this, R.string.dl_failed, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /** v1.7.7: download-chain journal rows per URL (quiet notifications resolved later). */
    private val chainEntryIds = HashMap<String, Long>()

    /** v1.7.5: page-side fetch fallback — runs fetch(url) INSIDE the current page and
     *  delivers the payload via [ZephyrBridge.onFetchData]. Fires the system
     *  download manager if the fetch never reports back (CSP / dead page).
     *  v1.7.6 CORS FIX (gstatic photo downloads): the old fetch used
     *  credentials:'include' — public image CDNs answer Access-Control-Allow-Origin:*
     *  which is REJECTED together with credentials, so every thumbnail fetch died
     *  and fell through to the SYSTEM downloader («фотки скачались моим мобильным
     *  загрузчиком»!). The fetch now goes WITHOUT credentials first (works for
     *  ACAO:* hosts) and retries WITH credentials only if that fails. */
    private fun downloadViaPageFetch(url: String, name: String, mime: String?) {
        val wv = tabs.current?.webView
        if (wv == null) { downloadViaSystem(url, name, tabs.current?.pageUrlSnapshot); return }
        pendingFetchUrl = url
        val urlQ = org.json.JSONObject.quote(url)
        val nameQ = org.json.JSONObject.quote(name.ifBlank { "file" })
        val mimeQ = org.json.JSONObject.quote(mime ?: "")
        val js = "(function(){try{" +
            "function go(inc){return fetch(" + urlQ + ",inc?{credentials:'include'}:undefined)" +
            ".then(function(r){if(!r.ok)throw 0;return r.blob();});}" +
            "go(false).catch(function(){return go(true);})" +
            ".then(function(b){" +
            "if(b.size>14*1024*1024){try{window.Zephyr.onFetchData(" + urlQ + ",'','');}catch(e){}return;}" +
            "var fr=new FileReader();" +
            "fr.onload=function(){try{window.Zephyr.onFetchData(" + urlQ + "," + nameQ + "," + mimeQ + "," +
            "String(fr.result).split(',')[1]||'');}catch(e){}};" +
            "fr.onerror=function(){try{window.Zephyr.onFetchData(" + urlQ + ",'','');}catch(e){}};" +
            "fr.readAsDataURL(b);})" +
            ".catch(function(){try{window.Zephyr.onFetchData(" + urlQ + ",'','');}catch(e){}});}catch(e){}})();"
        wv.evaluateJavascript(js, null)
        Toast.makeText(this, R.string.dl_started, Toast.LENGTH_SHORT).show()
        // watchdog: 20 s without a report → hand the URL to the system downloader
        main.postDelayed({
            if (pendingFetchUrl == url) {
                pendingFetchUrl = null
                downloadViaSystem(url, name, tabs.current?.pageUrlSnapshot)
            }
        }, 20000)
    }

    /** v1.7.5 entry from the JS bridge — the page-side fetch reported back. */
    fun acceptFetchData(url: String, name: String, mime: String, b64: String) {
        if (pendingFetchUrl == url) pendingFetchUrl = null
        if (b64.isBlank() || name.isBlank()) {
            // v1.7.12: keep the REAL page-provided name (query-style photo URLs make
            // Urls.guessName produce junk like "images" with no extension) and tell
            // the system downloader which page asked for the file — anti-hotlink
            // CDNs 403 requests without a Referer.
            downloadViaSystem(url, name.ifBlank { Urls.guessName(url) }, tabs.current?.pageUrlSnapshot)
            return
        }
        val bytes = try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            downloadViaSystem(url, name); return
        }
        if (bytes.isEmpty()) { downloadViaSystem(url, name); return }
        // v1.7.7: the page-fetch stage succeeded — resolve the chain's quiet row
        // with the success (a fresh row is created by enqueueBytes for the record).
        val quietId = chainEntryIds.remove(url)
        if (quietId != null) DownloadEngine.clearNotification(quietId)
        DownloadEngine.enqueueBytes("fetch:", name, mime, bytes, onFinish = { ok ->
            Toast.makeText(this, if (ok) R.string.dl_complete else R.string.dl_failed, Toast.LENGTH_SHORT).show()
        })
    }

    /** v1.7.5 LAST RESORT: the system DownloadManager (its own network stack, own
     *  notification). v1.7.6: the file now lands in the PUBLIC Downloads folder
     * (the old app-external target was undiscoverable for the user) and the row is
     * journaled in our Downloads panel — App.kt's ACTION_DOWNLOAD_COMPLETE receiver
     * finalizes it when the system finishes. */
    private fun downloadViaSystem(url: String, name: String, referer: String? = null) {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        try {
            val rq = android.app.DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_HIDDEN)
                .setTitle(safe)
                .setMimeType(DownloadEngine.guessMime(safe))
                .setDescription(url.take(80))
            var publicDir = false
            try {
                rq.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, safe)
            } catch (_: Exception) {
                rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe)
                publicDir = true
            }
            tabs.current?.webView?.settings?.userAgentString?.let {
                try { rq.addRequestHeader("User-Agent", it) } catch (_: Exception) {}
            }
            try {
                CookieManager.getInstance().getCookie(url)?.let {
                    if (it.isNotBlank()) rq.addRequestHeader("Cookie", it)
                }
            } catch (_: Exception) {}
            // v1.7.12: anti-hotlink CDNs 403 the system downloader without a
            // Referer — pass the page URL along like the native engine does.
            if (!referer.isNullOrBlank()) {
                try { rq.addRequestHeader("Referer", referer) } catch (_: Exception) {}
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            val id = dm.enqueue(rq)
            ioScope.launch {
                try {
                    db.downloadDao().insert(com.zephyr.browser.db.DownloadEntry(
                        url = url, fileName = safe, mime = DownloadEngine.guessMime(safe),
                        path = "dm:$id", size = 0, status = 0))
                } catch (_: Exception) {}
            }
            // v1.7.7: resolve the QUIET chain row — the system stage took over; the
            // App.kt ACTION_DOWNLOAD_COMPLETE receiver finishes the journal entry and
            // posts the completion as a ZEPHYR notification (system one is hidden).
            val quietId = chainEntryIds.remove(url)
            if (quietId != null) DownloadEngine.clearNotification(quietId)
            Toast.makeText(this, R.string.dl_system_queued, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            val quietId = chainEntryIds.remove(url)
            if (quietId != null) {
                DownloadEngine.finishQuiet(quietId, false, safe, "", 0, DownloadEngine.guessMime(safe))
            } else {
                Toast.makeText(this, R.string.dl_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** URL of the download currently being retried inside the page (watchdog slot). */
    private var pendingFetchUrl: String? = null

    /** v1.7.4: fetches a blob: URL inside the page (the ONLY place it exists) and
     *  delivers the bytes to [com.zephyr.browser.MainActivity.ZephyrBridge.onBlobData]. */
    private fun downloadBlobViaPage(url: String, name: String, mime: String?) {
        val wv = tabs.current?.webView
        if (wv == null) {
            Toast.makeText(this, R.string.dl_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val urlQ = org.json.JSONObject.quote(url)
        val nameQ = org.json.JSONObject.quote(name.ifBlank { "file" })
        val mimeQ = org.json.JSONObject.quote(mime ?: "")
        val js = "(function(){try{fetch(" + urlQ + ").then(function(r){return r.blob();})" +
            ".then(function(b){var fr=new FileReader();" +
            "fr.onload=function(){try{window.Zephyr.onBlobData(" + nameQ + "," + mimeQ + "," +
            "String(fr.result).split(',')[1]||'');}catch(e){}};" +
            "fr.onerror=function(){};fr.readAsDataURL(b);})" +
            ".catch(function(){});}catch(e){}})();"
        wv.evaluateJavascript(js, null)
        Toast.makeText(this, R.string.dl_started, Toast.LENGTH_SHORT).show()
    }

    /** v1.7.4: entry point from the JS bridge — a blob: payload fetched by the page. */
    fun acceptBlobDownload(name: String, mime: String, b64: String) {
        if (b64.isBlank()) {
            Toast.makeText(this, R.string.dl_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.dl_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (bytes.isEmpty()) {
            Toast.makeText(this, R.string.dl_failed, Toast.LENGTH_SHORT).show()
            return
        }
        DownloadEngine.enqueueBytes("blob:", name, mime, bytes, onFinish = { ok ->
            Toast.makeText(this, if (ok) R.string.dl_complete else R.string.dl_failed, Toast.LENGTH_SHORT).show()
        })
    }

    // ---------------- find in page ----------------

    private fun setupFindBar() {
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doFind(true)
                true
            } else false
        }
        findViewById<ImageButton>(R.id.btnFindNext).setOnClickListener { doFind(true) }
        findViewById<ImageButton>(R.id.btnFindPrev).setOnClickListener { doFind(false) }
        findViewById<ImageButton>(R.id.btnFindClose).setOnClickListener { hideFindBar() }
    }

    fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(findInput, 0)
    }

    private fun hideFindBar() {
        findBar.visibility = View.GONE
        findInput.setText("")
        findCount.text = ""
        tabs.current?.webView?.evaluateJavascript("window.stop&&window.stop();", null)
        clearFind()
    }

    private fun clearFind() {
        tabs.current?.webView?.evaluateJavascript(
            "(function(){try{var z=document.getElementById('__zephyrFind');if(z){z.remove();}}catch(e){}})();", null)
    }

    private fun doFind(next: Boolean) {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val wv = tabs.current?.webView ?: return
        wv.findAllAsync(q)
        wv.findNext(next)
        findCount.text = "?"
    }

    // ---------------- permissions & geo ----------------

    fun requestGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        lastGeoOrigin = origin
        lastGeoCallback = callback
        val originHost = Urls.domainOf(origin)
        ioScope.launch {
            var decision: Int? = null
            try { decision = db.sitePermissionDao().byOrigin(origin)?.location } catch (_: Exception) {}
            main.post {
                when (decision) {
                    1 -> geoRespond(true)
                    0 -> geoRespond(false)
                    else -> {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.geo_title)
                            .setMessage(getString(R.string.geo_msg, originHost))
                            .setPositiveButton(R.string.allow) { _, _ ->
                                rememberPermission(origin, "location", 1)
                                geoRespond(true)
                            }
                            .setNegativeButton(R.string.block) { _, _ ->
                                rememberPermission(origin, "location", 0)
                                geoRespond(false)
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun geoRespond(allow: Boolean) {
        val cb = lastGeoCallback ?: return
        val origin = lastGeoOrigin ?: return
        cb.invoke(origin, allow, false)
        if (allow) {
            // also needs app-level permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), GEO_PERM_RC)
            }
        }
    }

    fun requestWebPermission(request: PermissionRequest) {
        pendingPermissionRequest = request
        val perms = request.resources
        val needsCamera = perms.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val needsMic = perms.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val origin = (request.origin?.toString() ?: "").removeSuffix("/")
        if (!needsCamera && !needsMic) {
            request.deny()
            return
        }
        val what = when {
            needsCamera && needsMic -> getString(R.string.perm_camera_mic)
            needsCamera -> getString(R.string.perm_camera)
            else -> getString(R.string.perm_mic)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_title)
            .setMessage(getString(R.string.perm_msg, what, Urls.domainOf(origin)))
            .setPositiveButton(R.string.allow) { _, _ ->
                val appPerms = buildList {
                    if (needsCamera) add(Manifest.permission.CAMERA)
                    if (needsMic) add(Manifest.permission.RECORD_AUDIO)
                }
                val missing = appPerms.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    pendingPermissionRequest?.grant(request.resources)
                } else {
                    requestPermissions(missing.toTypedArray(), if (needsCamera) CAMERA_PERM_RC else MIC_PERM_RC)
                }
            }
            .setNegativeButton(R.string.block) { _, _ -> pendingPermissionRequest?.deny() }
            .show()
    }

    fun cancelPermissionRequest(request: PermissionRequest?) {
        request?.deny()
        if (pendingPermissionRequest === request) pendingPermissionRequest = null
    }

    private fun rememberPermission(origin: String, field: String, value: Int) {
        ioScope.launch {
            try {
                val dao = db.sitePermissionDao()
                val existing = dao.byOrigin(origin)
                val updated = when (field) {
                    "location" -> (existing ?: SitePermissionEntity(origin)).copy(location = value)
                    "camera" -> (existing ?: SitePermissionEntity(origin)).copy(camera = value)
                    else -> (existing ?: SitePermissionEntity(origin)).copy(mic = value)
                }
                dao.insert(updated)
            } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            CAMERA_PERM_RC -> pendingPermissionRequest?.let {
                if (granted) it.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                else it.deny()
            }
            MIC_PERM_RC -> pendingPermissionRequest?.let {
                if (granted) it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                else it.deny()
            }
            // v1.7.55: the provider-side voice route asks for the microphone on
            // first use — carry on into it instead of making the user tap again
            VOICE_RC -> if (granted) startVoiceSearch()
            CAMERA_AI_RC -> if (granted) openAiCamera()
            // v1.7.69: the same resume for the AI composer microphone
            AI_VOICE_RC -> {
                val cb = pendingAiVoice
                pendingAiVoice = null
                if (granted && cb != null) startAiVoiceInput(cb)
            }
        }
    }

    /**
     * v1.7.1 FIX 3: one-time polite ask for the permissions a browser actually needs —
     * media/storage access for downloads and (on Android 13+) notifications. The
     * "asked_v171" flag is written BEFORE the ask, so this fires exactly once ever and
     * denial is final (and silent). Already-granted entries are filtered out; when the
     * platform auto-satisfies everything, no dialog is shown at all. No rationale UI —
     * the system dialog names the permissions itself.
     */
    private fun maybeAskStartupPermissions() {
        val sp = getSharedPreferences("zephyr_perms", Context.MODE_PRIVATE)
        if (sp.getBoolean("asked_v171", false)) return
        sp.edit().putBoolean("asked_v171", true).apply()
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            wanted.add(Manifest.permission.READ_MEDIA_IMAGES)
            wanted.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT >= 29) {
            wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), STARTUP_PERMS_RC)
    }

    // ---------------- file chooser ----------------

    fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams?) {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val intents = mutableListOf<Intent>()
        // gallery/documents
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*", "application/pdf", "text/*"))
        }.let { intents.add(it) }
        // camera capture
        try {
            cameraFile = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", cameraFile!!)
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri("photo", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { intents.add(it) }
        } catch (_: Exception) {}
        val chooser = Intent.createChooser(intents.removeAt(0), getString(R.string.choose_file))
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
        try {
            startActivityForResult(chooser, FILE_RC)
        } catch (_: Exception) {
            fileCallback = null
        }
    }

    // ---------------- v1.7.25: AI chat attachments ----------------

    /** Set by the AI chat sheet right before it opens the system picker. */
    var aiAttachCallback: ((List<Uri>) -> Unit)? = null

    /** Open the system picker for the AI chat: images only, or any document. */
    /**
     * v1.7.76: the camera as an input to the assistant.
     *
     * Asks for the permission first when it is missing, because CameraX simply
     * shows a black preview otherwise and the user is left guessing.
     */
    fun openAiCamera() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_AI_RC)
            return
        }
        try {
            startActivity(Intent(this, com.zephyr.browser.ui.CameraAiActivity::class.java))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.cam_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun pickAiAttachment(imagesOnly: Boolean, onPicked: (List<Uri>) -> Unit) {
        aiAttachCallback = onPicked

        // v1.7.78 (user: «в пункте изображения открывается внутренняя память,
        // а приложения галереи нет — так удобнее найти фото быстрее»).
        //
        // ACTION_OPEN_DOCUMENT speaks only to document providers, which is why
        // it showed a file tree and no gallery. For pictures the right verb is
        // GET_CONTENT through a chooser: the gallery, the photo apps and the
        // file browser all offer themselves, and the user picks the one they
        // think in.
        if (imagesOnly) {
            val get = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try {
                startActivityForResult(
                    Intent.createChooser(get, getString(R.string.ai_attach_photo)),
                    AI_ATTACH_RC
                )
                return
            } catch (_: Exception) {
                // fall through to the document picker below
            }
        }

        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (imagesOnly) "image/*" else "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // v1.7.69 FIX (user: «выбираешь Документы — а там и фото тоже»).
            // "image/*" was in this list, so the document picker offered the
            // whole gallery. Documents are documents; photos have their own
            // entry in the menu.
            if (!imagesOnly) putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/*", "application/pdf", "application/json",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/zip", "application/xml", "text/csv"
                )
            )
        }
        try {
            startActivityForResult(i, AI_ATTACH_RC)
        } catch (_: Exception) {
            aiAttachCallback = null
            Toast.makeText(this, R.string.choose_file, Toast.LENGTH_SHORT).show()
        }
    }


    /** v1.7.26: dictate straight into the AI chat composer. */
    var aiVoiceCallback: ((String) -> Unit)? = null

    /**
     * v1.7.69 FIX (user: «на главном экране голос работает, а в диалоге ИИ
     * пишет „недоступен" — там две разные реализации?»).
     *
     * There were. v1.7.55 gave [startVoiceSearch] a fallback for phones without
     * Google's recogniser — record here, transcribe through the user's own AI
     * provider — and this one, written earlier and living a few lines away,
     * never got it. Fixing one button and not looking for the other was my
     * oversight; both now take the same route.
     */
    fun startAiVoiceInput(onText: (String) -> Unit) {
        if (useSystemRecogniser()) {
            aiVoiceCallback = onText
            try {
                startActivityForResult(
                    Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.ai_voice))
                    }, AI_VOICE_RC
                )
                return
            } catch (_: Exception) {
                aiVoiceCallback = null
            }
        }
        voiceWithoutSystem(R.string.ai_voice, AI_VOICE_RC, onText)
    }

    /** Set while the microphone permission dialog is up for the AI composer. */
    private var pendingAiVoice: ((String) -> Unit)? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            AUTH_RC -> {
                // v1.4.2 FIX 7: device credential confirmed → inject the saved login
                if (resultCode == Activity.RESULT_OK) performPendingAutofill()
                else { pendingAutofillTab = null; pendingAutofillCreds = null }
            }
            VOICE_RC -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()?.let { spoken ->
                            navigate(Urls.normalize(spoken) { q -> SearchEngines.searchUrl(Prefs.engineId, q) })
                        }
                }
            }
            AI_VOICE_RC -> {
                val cb = aiVoiceCallback
                aiVoiceCallback = null
                if (resultCode == Activity.RESULT_OK) {
                    data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()?.let { cb?.invoke(it) }
                }
            }
            AI_ATTACH_RC -> {
                val cb = aiAttachCallback
                aiAttachCallback = null
                val uris = ArrayList<Uri>()
                if (resultCode == Activity.RESULT_OK && data != null) {
                    data.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                    } ?: data.data?.let { uris.add(it) }
                }
                for (u in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}
                }
                cb?.invoke(uris)
            }
            FILE_RC -> {
                var results: Array<Uri>? = null
                if (resultCode == Activity.RESULT_OK) {
                    if (data?.data != null) results = arrayOf(data.data!!)
                    else if (data?.clipData != null) {
                        val n = data.clipData!!.itemCount
                        results = (0 until n).map { data.clipData!!.getItemAt(it).uri }.toTypedArray()
                    } else if (cameraFile?.exists() == true) {
                        results = arrayOf(android.net.Uri.fromFile(cameraFile))
                    }
                }
                fileCallback?.onReceiveValue(results)
                fileCallback = null
            }
        }
    }

    // ---------------- popups & fullscreen ----------------

    fun createPopupWebView(parent: WebView?): WebView {
        val incognito = tabs.current?.incognito ?: false
        val tab = tabs.newTab("about:blank", incognito)
        val wv = tabs.ensureWebView(tab)
        wireWebView(wv, tab)
        return wv
    }

    fun closePopup(webView: WebView) {
        tabs.tabs.firstOrNull { it.webView === webView }?.let { tabs.closeTab(it) }
    }

    // v1.7.10 FIX 3c: delayed re-asserts that re-hide the system bars while a
    // fullscreen video plays (removed in exitFullscreen / onDestroy).
    private var fullscreenReassert1: Runnable? = null
    private var fullscreenReassert2: Runnable? = null

    fun enterFullscreen(view: View) {
        fullscreenContainer.removeAllViews()
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenContainer.visibility = View.VISIBLE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreenChrome()
        addFullscreenFloatButton()
    }

    /** v1.7.18: the immersive / flag / black-paint walls of enterFullscreen,
     *  extracted so expandFloatingVideo can put the SAME walls back up when the
     *  floated fullscreen video returns to the overlay (nothing else changed). */
    private fun enterFullscreenChrome() {
        // the fullscreen film keeps the screen awake (re-asserted — exitFullscreen
        // clears the flag and expandFloatingVideo comes through here)
        try { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
        // v1.7.7 FIX (user: «фильм на весь экран — панель состояния и навигации не
        // уходят, вертикально смотреть неудобно»): a fullscreen video now enters
        // true immersive mode — status + navigation bars hide with the swipe-to-
        // reveal behavior, and the display cutout switches to shortEdges so the
        // video isn't clipped by a notch in landscape/portrait fullscreen.
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {}
        // v1.7.12 NUCLEAR OPTION (user: «строка состояния всё ещё тёмная — в цвет
        // темы» after two immersive fixes): the insets hide alone can be defeated by
        // OEM ROMs, and a peeking/visible bar can render the WINDOW BACKGROUND
        // (theme color) when statusBarColor writes are ignored. Three more walls:
        // 1) FLAG_FULLSCREEN — the window-level status-bar hide that no insets
        //    churn can resurrect (works on every ROM);
        // 2) the WINDOW BACKGROUND itself painted black — a transparent bar can
        //    only ever show black through it;
        // 3) the fullscreen container paints black — letterboxed video shows
        //    black everywhere around it.
        try {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        } catch (_: Exception) {}
        try { fullscreenContainer.setBackgroundColor(android.graphics.Color.BLACK) } catch (_: Exception) {}
        // v1.7.11 FIX (user: «в полноэкранном строка состояния не ЧЁРНАЯ как панель
        // навигации, а тёмная — в цвет темы»): the window kept painting the THEME
        // color into statusBarColor (applyIncognitoUi) — so whenever the bar was
        // visible (peeked, or resurrected by a late clobber) it showed the theme
        // tint while the nav bar looked properly black. In fullscreen BOTH bars
        // are now painted pure BLACK with white icons — identical appearance,
        // exactly like the navigation bar.
        paintBarsForFullscreen()
        // v1.7.10 FIX 3c (user: «при полноэкранном строка состояния в тёмный
        // красится и не исчезает! а нижняя панель навигации всё правильно —
        // уходит»): page-load / theme / incognito callbacks firing right after the
        // video goes fullscreen re-write statusBarColor & systemUiVisibility and
        // resurrect the status bar one shot (the nav bar survived only because
        // nothing re-painted it). Two delayed re-asserts defeat those clobbers;
        // exitFullscreen and onDestroy remove the runnables so they can never
        // hide the bars AFTER the video ends.
        fullscreenReassert1?.let { main.removeCallbacks(it) }
        fullscreenReassert2?.let { main.removeCallbacks(it) }
        fullscreenReassert1 = Runnable { fullscreenReassert1 = null; keepSystemBarsHiddenIfFullscreen() }
            .also { main.postDelayed(it, 250) }
        fullscreenReassert2 = Runnable { fullscreenReassert2 = null; keepSystemBarsHiddenIfFullscreen() }
            .also { main.postDelayed(it, 1000) }
    }

    /** v1.7.17 (user: «добавь... плавающий плеер для видео будет удобно для
     *  серфинга и просмотра в тоже время видео или музыки»): a compact button
     *  over the fullscreen video (top-end, clear of the cutout) shrinks THIS
     *  tab into the draggable mini window. LAST child → draws above the video;
     *  exitFullscreen's removeAllViews drops it together with the video view.
     *  v1.7.18: extracted — expandFloatingVideo re-adds the button when the
     *  film returns to the overlay, so it can be minimized again. */
    private fun addFullscreenFloatButton() {
        try {
            val floatBtn = ImageButton(this).apply {
                tag = "floatBtn"
                setImageResource(R.drawable.ic_float_player)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                contentDescription = getString(R.string.fl_float_hint)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                }
                layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
                    marginEnd = dp(16)
                    topMargin = dp(48)   // below the status-bar/cutout zone
                }
                setOnClickListener { floatCurrentTab() }
            }
            fullscreenContainer.addView(floatBtn)
        } catch (_: Exception) {}
    }

    /** v1.7.11: while a fullscreen video plays, both system bars are painted
     *  BLACK (like the navigation bar) with white icons — no theme tint can
     *  leak into the peek scrim anymore. */
    private fun paintBarsForFullscreen() {
        try {
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK
        } catch (_: Exception) {}
        // white icons on the black bars (setLight* re-hides the bars too —
        // harmless: we're in fullscreen and WANT them hidden)
        setLightStatusIcons(false)
        setLightNavIcons(false)
    }

    /** v1.7.11: restores the REAL bar colors after fullscreen — incognito keeps
     *  its purple gradient pair, normal tabs get the theme background + the
     *  theme's icon appearance (mirrors applyIncognitoUi). */
    private fun restoreBarsAfterFullscreen() {
        try {
            if (tabs.current?.incognito == true) {
                window.statusBarColor = 0xFF2E1E4F.toInt()
                window.navigationBarColor = 0xFF1F1740.toInt()
                setLightStatusIcons(false)
                setLightNavIcons(false)
            } else {
                val ta = obtainStyledAttributes(Prefs.themeStyle(), intArrayOf(R.attr.zBackground))
                val bg = ta.getColor(0, 0xFFFFFFFF.toInt())
                ta.recycle()
                window.statusBarColor = bg
                window.navigationBarColor = bg
                setLightStatusIcons(themeUsesLightStatusIcons())
                setLightNavIcons(themeUsesLightStatusIcons())
            }
        } catch (_: Exception) {}
    }

    fun exitFullscreen() {
        // v1.7.10 FIX 3c: cancel any pending bar re-assert FIRST — it would
        // re-hide the bars right after this exit shows them.
        fullscreenReassert1?.let { main.removeCallbacks(it) }
        fullscreenReassert2?.let { main.removeCallbacks(it) }
        fullscreenReassert1 = null
        fullscreenReassert2 = null
        // v1.7.18: the SITE ended fullscreen while the video was floated (player
        // finished / its own ✕ / navigation) — the float overlay (its live view
        // already removed by onHideCustomView) must come down with it.
        if (floatingVideoMode) {
            floatingTab?.webView?.let { wv ->
                try { wv.evaluateJavascript(pauseMediaJs(), null) } catch (_: Exception) {}
            }
            detachFloatingUi()
        }
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // v1.7.7: restore the normal window — bars back, default cutout mode, and
        // the light/dark icon scheme the theme had before the video.
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = lp
            }
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.show(WindowInsetsCompat.Type.systemBars())
            // v1.7.11: bars get their REAL colors back — incognito purple pair or
            // the theme background (was: flat !isDarkTheme() which ignored both
            // incognito and the theme's own light-bar attr).
            restoreBarsAfterFullscreen()
            // v1.7.12: bring the window back — clear FLAG_FULLSCREEN and restore the
            // theme window background (black leaked through the window background
            // otherwise, e.g. behind transparent bars after fullscreen).
            try {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                val wbg = if (tabs.current?.incognito == true) 0xFF241A3D.toInt() else run {
                    val ta = obtainStyledAttributes(Prefs.themeStyle(), intArrayOf(R.attr.zBackground))
                    val c = ta.getColor(0, 0xFFFFFFFF.toInt()); ta.recycle(); c
                }
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(wbg))
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    // ---------------- v1.7.17/1.7.18 floating player ----------------
    // (user: «добавь опцию как ты и предложил плавающий плеер для видео будет
    // удобно для серфинга и просмотра в тоже время видео или музыки») — a small
    // draggable window floats over the browser while the media keeps playing.
    //
    // v1.7.18 REWORK (user bug report):
    //  • «фильм с полноэкранного отключился и я в мини окне увидел сайт
    //    страницу» → a fullscreen video now floats AS THE VIDEO: the live
    //    fullscreen view (chromium custom view, site controls included) moves
    //    INTO the mini window — the film stays exactly as it was, nothing
    //    reloads, no page is shown. Chromium keeps its fullscreen state the
    //    whole time; the pending CustomViewCallback is honored on close.
    //  • «плавающий плеер очень мелкий увеличь его» → the window grew from
    //    42%×180dp-cap to 56% width (300dp cap), height-capped at 42%.
    //  • «жму раскрыть… выдает ошибку illegalstateexception и вкладка пустая»
    //    → the floated WebView kept the floating window as parent, so
    //    attachPageView's addView threw IllegalStateException; detach now
    //    releases every child (plus attachPageView is armored).
    //  • «должна полноэкрано вернуть меня в фильм ровно также как я и нажал
    //    кнопку свернуть» → expand moves the live view back into the fullscreen
    //    overlay and re-raises all the immersive walls.
    //  • «закрыть перетащив вниз по центру к кнопке крестик» → dragging shows a
    //    ✕ disc at bottom-center; dropping the window on it closes the player.

    /** The tab whose WebView / fullscreen video currently lives in the floating
     *  window (null = no floating player). Honored by: onTabSwitched,
     *  pauseAllMedia, suspendBackgroundMedia, isTabPageVisibleNow,
     *  TabManager.evictProtected. */
    private var floatingTab: TabManager.Tab? = null
    private var floatingWindow: FrameLayout? = null
    /** v1.7.18: true → the floating window hosts the LIVE FULLSCREEN VIDEO view
     *  (chromium custom view); false → the whole page (menu entry path). */
    private var floatingVideoMode = false
    /** v1.7.18: the drag-down-to-close ✕ disc (kept to remove it on detach). */
    private var floatCloseTarget: View? = null

    /** Shrinks the current tab into the floating window — or expands it back
     *  when one already floats (toggle). Fully armored (house convention). */
    fun floatCurrentTab() {
        if (floatingTab != null) { expandFloatingPlayer(); return }
        try {
            // v1.7.18: fullscreen VIDEO floats as the video itself — the live
            // fullscreen view moves into the mini window (media keeps playing,
            // no reload, the site page never appears in the window).
            val cv = chromeClient.activeCustomView()
            if (cv != null) { floatFullscreenVideo(cv); return }
            val tab = tabs.current
            if (tab == null || tab.isHome || tab.webView == null) {
                Toast.makeText(this, R.string.fl_no_media, Toast.LENGTH_SHORT).show()
                return
            }
            val wv = tab.webView ?: return
            // Detach the page: the WebView out of its SwipeRefreshLayout, and the
            // (now empty) SmartPull out of the content stage. attachPageView later
            // re-wraps the WebView in a FRESH SmartPull (its parent is no SRL).
            (wv.parent as? ViewGroup)?.removeView(wv)
            webContainer.removeAllViews()
            val dm = resources.displayMetrics
            val marginX = dp(12)
            val topSafe = dp(24)     // status bar / cutout zone
            val bottomSafe = dp(80)  // nav bar / gesture area
            val size = floatWindowSize(dm)
            val width = size.first
            val height = size.second
            val win = buildFloatWindow(dm, width, height, marginX, topSafe, bottomSafe)
            // The page itself — MATCH_PARENT: it re-lays out to the mini size and
            // keeps playing (a WebView resize never stops HTML5 media).
            win.addView(wv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attachFloatControls(win, width, height)
            // android.R.id.content LAST → the window draws above EVERYTHING in the
            // activity (home view, tab switcher and error overlay included).
            (window.findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(win)
            // state FIRST, animation second — nothing throwing sits between the
            // addView and the bookkeeping, so the overlay can never be orphaned.
            floatingWindow = win
            floatingTab = tab
            tabs.evictProtected = tab
            Anim.popIn(win, 200)
            // Switch the browser away: the omnibox/tab counter follow the new
            // current tab; the floated tab (and its media) stays live untouched.
            switchAwayFromFloated(tab)
        } catch (t: Throwable) {
            // armor-recovery: a mid-build failure must not leave the just-detached
            // page blank — run the standard re-attach for the tab we tried to float
            // (only when the overlay never came up).
            try {
                if (floatingTab == null) {
                    val cur = tabs.current
                    val curWv = cur?.webView
                    if (cur != null && !cur.isHome && curWv != null && curWv.parent == null) {
                        attachPageView(curWv)
                    }
                }
            } catch (_: Exception) {}
            try { Toast.makeText(this, t.javaClass.simpleName, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /** v1.7.18: a fullscreen video is on → the LIVE fullscreen view (site
     *  player with its own controls) becomes the floating window content. The
     *  page never shows, the film never leaves its state, the time keeps running. */
    private fun floatFullscreenVideo(cv: View) {
        try {
            val tab = tabs.current ?: return
            // 1) take the live fullscreen view out of the overlay. Chromium's
            //    fullscreen state and the pending CustomViewCallback stay ALIVE —
            //    a proper exit (callback fired) happens on close/tab-select.
            (cv.parent as? ViewGroup)?.removeView(cv)
            // 2) collapse the app-level fullscreen chrome (bars back for browsing)
            fullscreenContainer.visibility = View.GONE
            exitFullscreen()
            // the film keeps playing — the screen must not sleep mid-float
            try { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
            // 3) the mini window around the live video view
            val dm = resources.displayMetrics
            val marginX = dp(12)
            val topSafe = dp(24)
            val bottomSafe = dp(80)
            val size = floatWindowSize(dm)
            val width = size.first
            val height = size.second
            val win = buildFloatWindow(dm, width, height, marginX, topSafe, bottomSafe)
            win.addView(cv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attachFloatControls(win, width, height)
            (window.findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(win)
            floatingWindow = win
            floatingTab = tab
            floatingVideoMode = true
            tabs.evictProtected = tab
            Anim.popIn(win, 200)
            // 4) switch away — browsing continues, the film floats along
            switchAwayFromFloated(tab)
        } catch (t: Throwable) {
            // armor-recovery: put the film back into the fullscreen overlay
            try {
                if (floatingTab == null) {
                    (cv.parent as? ViewGroup)?.removeView(cv)
                    fullscreenContainer.removeAllViews()
                    fullscreenContainer.addView(cv, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    fullscreenContainer.visibility = View.VISIBLE
                    enterFullscreenChrome()
                }
            } catch (_: Exception) {}
            try { Toast.makeText(this, t.javaClass.simpleName, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /** v1.7.18: comfortable mini size — the old 42% width / 180dp cap rendered a
     *  postage stamp (user: «плавающий плеер очень мелкий увеличь его»). */
    private fun floatWindowSize(dm: android.util.DisplayMetrics): Pair<Int, Int> {
        var width = minOf(dm.widthPixels * 0.56f, dp(300).toFloat()).toInt()
        var height = width * 9 / 16
        val maxH = (dm.heightPixels * 0.42f).toInt()
        if (height > maxH) { height = maxH; width = height * 16 / 9 }
        return Pair(width.coerceAtLeast(dp(120)), height.coerceAtLeast(dp(68)))
    }

    /** Rounded black container with elevation + outline clip, bottom-end initial
     *  position. Shared by the video and the page float path. */
    private fun buildFloatWindow(
        dm: android.util.DisplayMetrics, width: Int, height: Int,
        marginX: Int, topSafe: Int, bottomSafe: Int,
    ): FrameLayout {
        val corner = dp(16).toFloat()
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(width, height)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.BLACK)
                cornerRadius = corner
            }
            elevation = dp(12).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, corner)
                }
            }
            clipToOutline = true   // the content stays inside the rounded corners
            // initial position: bottom-end, comfortably above the nav bar
            translationX = (dm.widthPixels - width - marginX).toFloat()
            translationY = (dm.heightPixels * 0.55f).coerceIn(
                topSafe.toFloat(), (dm.heightPixels - height - bottomSafe).toFloat())
        }
    }

    /** Control chip (top strip: drag handle + expand/close) and the
     *  drag-down-to-close ✕ disc. Shared by both float paths. */
    private fun attachFloatControls(win: FrameLayout, width: Int, height: Int) {
        val dm = resources.displayMetrics
        val marginX = dp(12)
        val topSafe = dp(24)
        val bottomSafe = dp(80)
        // the window may dip below the bottom bound so its center can reach the ✕
        val dragMaxY = (dm.heightPixels - height * 0.35f).toFloat()

        // ---- drag-down-to-close target (v1.7.28: shared premium drop target) ----
        // user: «там кнопка X не красивая, сделай что-то лучше» — the flat grey
        // disc is replaced by com.zephyr.browser.ui.DragCloseTarget: frosted
        // disc + halo + caption, arming into a red gradient with a haptic tick.
        val content = window.findViewById<View>(android.R.id.content) as? ViewGroup
        val dropTarget = content?.let {
            com.zephyr.browser.ui.DragCloseTarget.attach(this, it, bottomMarginDp = 28)
        }
        floatCloseTarget = dropTarget?.view
        var armed = false
        fun setArmed(a: Boolean) {
            if (a == armed) return
            armed = a
            dropTarget?.setArmed(a)
        }
        fun discCenterOnScreen(): FloatArray? = dropTarget?.discCenter()

        // ---- top control chip: [expand][close], the strip is the drag handle ----
        var downRawX = 0f
        var downRawY = 0f
        var downTx = 0f
        var downTy = 0f
        var dragged = false
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x66000000)
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX; downRawY = ev.rawY
                        downTx = win.translationX; downTy = win.translationY
                        dragged = false
                        dropTarget?.show()
                        setArmed(false)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val ddx = ev.rawX - downRawX
                        val ddy = ev.rawY - downRawY
                        if (!dragged && (abs(ddx) > dp(6) || abs(ddy) > dp(6))) dragged = true
                        win.translationX = (downTx + ddx).coerceIn(
                            marginX.toFloat(), (dm.widthPixels - width - marginX).toFloat())
                        win.translationY = (downTy + ddy).coerceIn(topSafe.toFloat(), dragMaxY)
                        // armed = the window center hovers over the ✕ disc
                        val c = discCenterOnScreen()
                        if (c != null) {
                            val loc = IntArray(2)
                            win.getLocationOnScreen(loc)
                            val wx = loc[0] + width / 2f
                            val wy = loc[1] + height / 2f
                            val dist = kotlin.math.hypot(wx - c[0].toDouble(), wy - c[1].toDouble())
                            setArmed(dist < (dropTarget?.catchRadius() ?: dp(84).toFloat()).toDouble())
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        // v1.7.18: capture BEFORE setArmed(false) resets the flag
                        val wasArmed = armed
                        dropTarget?.hide()
                        setArmed(false)
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && wasArmed) {
                            closeFloatingPlayer()
                            return@setOnTouchListener true
                        }
                        // snap to the nearest screen half (chrome-PiP feel) and
                        // back into the normal vertical band
                        val toLeft = win.translationX + width / 2f < dm.widthPixels / 2f
                        val targetX = if (toLeft) marginX.toFloat()
                            else (dm.widthPixels - width - marginX).toFloat()
                        val targetY = win.translationY.coerceIn(
                            topSafe.toFloat(), (dm.heightPixels - height - bottomSafe).toFloat())
                        win.animate().translationX(targetX).translationY(targetY)
                            .setDuration(180)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                            .start()
                        true
                    }
                    else -> false
                }
            }
        }
        fun chipBtn(iconRes: Int, descRes: Int, onClick: () -> Unit): ImageButton =
            ImageButton(this).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                contentDescription = getString(descRes)
                background = null   // the chip is the visual container
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                setOnClickListener { onClick() }
            }
        chip.addView(chipBtn(R.drawable.ic_fullscreen, R.string.fl_expand) { expandFloatingPlayer() })
        chip.addView(chipBtn(R.drawable.ic_close, R.string.fl_close) { closeFloatingPlayer() })
        win.addView(chip)
    }

    /** The browser switches to the most recently used OTHER tab (or a fresh one)
     *  so the user keeps surfing while the media floats. */
    private fun switchAwayFromFloated(tab: TabManager.Tab) {
        val others = tabs.tabs.filter { it !== tab }
        val next = others.maxByOrNull { it.lastUsed }
        if (next != null) tabs.select(next)
        else tabs.newTab(Urls.DEFAULT_HOME, tab.incognito, select = true)
    }

    /** Returns the floating player to where it came from: the video mode goes
     *  back to the REAL fullscreen (exactly as before the minimize), the page
     *  mode re-attaches its tab through the standard switch flow (no reload). */
    fun expandFloatingPlayer() {
        val tab = floatingTab ?: return
        try {
            if (floatingVideoMode) expandFloatingVideo(tab) else expandFloatingPage(tab)
        } catch (t: Throwable) {
            try { Toast.makeText(this, t.javaClass.simpleName, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /** v1.7.18: the floated FULLSCREEN VIDEO returns to the real fullscreen —
     *  the live view moves back into the overlay and every immersive wall goes
     *  back up (user: «должна полноэкрано вернуть меня в фильм ровно также как
     *  я и нажал кнопку свернуть»). */
    private fun expandFloatingVideo(tab: TabManager.Tab) {
        val cv = chromeClient.activeCustomView()
        if (cv == null || tabs.tabs.none { it === tab }) {
            // the site ended fullscreen while floating — degrade to a plain expand
            detachFloatingUi()
            val wv = tab.webView
            if (wv == null || tabs.tabs.none { it === tab }) return
            if (tabs.current === tab) { hideHome(); attachPageView(wv) } else tabs.select(tab)
            return
        }
        // 1) the live video view out of the float window
        (cv.parent as? ViewGroup)?.removeView(cv)
        // 2) drop the overlay + clear the state WITHOUT touching the fullscreen
        //    state (no hideCustomViewIfShown here — the film stays "fullscreen")
        try {
            floatingWindow?.let { win ->
                (win.parent as? ViewGroup)?.removeView(win)
                for (i in win.childCount - 1 downTo 0) win.removeViewAt(i)
            }
        } catch (_: Exception) {}
        try { floatCloseTarget?.let { (it.parent as? ViewGroup)?.removeView(it) } } catch (_: Exception) {}
        floatCloseTarget = null
        floatingWindow = null
        floatingTab = null
        tabs.evictProtected = null
        floatingVideoMode = false
        // 3) back into the fullscreen overlay + all the immersive walls
        fullscreenContainer.removeAllViews()
        fullscreenContainer.addView(cv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenContainer.visibility = View.VISIBLE
        enterFullscreenChrome()
        addFullscreenFloatButton()
        // 4) select the video tab — omnibox and back navigation match the film
        if (tabs.tabs.none { it === tab }) return
        if (tabs.current !== tab) tabs.select(tab)
    }

    /** Page-mode expand: overlay off, same live WebView re-attached through the
     *  standard switch flow (no reload). */
    private fun expandFloatingPage(tab: TabManager.Tab) {
        val wv = tab.webView
        // Overlay down FIRST, then clear the state, then select — onTabSwitched's
        // normal flow re-attaches the WebView (detach released it from the
        // floating window, so attachPageView wraps it in a fresh SmartPull).
        detachFloatingUi()
        if (wv == null) return   // evicted while floating — nothing to re-attach
        if (tabs.tabs.none { it === tab }) return   // closed meanwhile
        if (tabs.current === tab) {
            // defensive: never left the tab (should not happen — floating always
            // switches away); re-attach inline because tabs.select would no-op.
            hideHome()
            attachPageView(wv)
        } else {
            tabs.select(tab)
        }
    }

    /** The ✕ chip button / drop-on-✕: pauses the media and drops the overlay,
     *  but KEEPS the tab (its WebView stays alive detached; picking the tab in
     *  the switcher later re-attaches the same live page via attachPageView). */
    fun closeFloatingPlayer() {
        if (floatingTab == null) return
        try {
            val tab = floatingTab
            if (floatingVideoMode) {
                // a PROPER fullscreen exit: onHideCustomView fires, the page is
                // notified, the pending callback is honored
                chromeClient.hideCustomViewIfShown()
            }
            tab?.webView?.let { wv ->
                try { wv.evaluateJavascript(pauseMediaJs(), null) } catch (_: Exception) {}
            }
            detachFloatingUi()
        } catch (t: Throwable) {
            try { Toast.makeText(this, t.javaClass.simpleName, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /** Tears the overlay down and clears all floating state. v1.7.18: in video
     *  mode Chromium's fullscreen state goes down FIRST (proper callback); in
     *  every mode the floated children are RELEASED from the window — the old
     *  code left the WebView hanging in the removed window, and the next
     *  attachPageView addView threw IllegalStateException (user: «жму
     *  раскрыть… выдает ошибку illegalstateexception и вкладка пустая»). */
    private fun detachFloatingUi() {
        if (floatingVideoMode) {
            floatingVideoMode = false
            try { chromeClient.hideCustomViewIfShown() } catch (_: Exception) {}
        }
        try {
            floatingWindow?.let { win ->
                (win.parent as? ViewGroup)?.removeView(win)
                for (i in win.childCount - 1 downTo 0) win.removeViewAt(i)
            }
        } catch (_: Exception) {}
        try { floatCloseTarget?.let { (it.parent as? ViewGroup)?.removeView(it) } } catch (_: Exception) {}
        floatCloseTarget = null
        floatingWindow = null
        floatingTab = null
        tabs.evictProtected = null
    }

    /** Unused since v1.7.11 (bar restore now mirrors applyIncognitoUi); kept for
     *  the next theme-aware feature that needs it. */
    @Suppress("unused")
    private fun isDarkTheme(): Boolean {
        val tv = intArrayOf(android.R.attr.isLightTheme)
        val ta = theme.obtainStyledAttributes(tv)
        val light = ta.getBoolean(0, true)
        ta.recycle()
        return !light
    }

    // ---------------- JS dialogs ----------------

    fun showJsDialog(isAlert: Boolean, message: String, result: JsResult) {
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.javascript)
            .setMessage(message)
            .setOnCancelListener { result.cancel() }
        if (isAlert) {
            b.setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
        } else {
            b.setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
        }
        b.show()
    }

    fun showJsPrompt(message: String, defaultValue: String, result: JsPromptResult) {
        val input = EditText(this).apply {
            setText(defaultValue)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.javascript)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
    }

    // ---------------- bridge ----------------

    inner class ZephyrBridge(private val tab: TabManager.Tab) {
        @JavascriptInterface
        fun onLoginSubmitted(user: String, pass: String) {
            val url = tab.pageUrlSnapshot
            if (user.isEmpty() || pass.isEmpty()) return
            if (tab.incognito || Prefs.alwaysIncognito || !Prefs.savePasswords) return
            val origin = Urls.originOf(url)
            if (origin.isEmpty()) return
            main.post { offerSavePassword(origin, user, pass) }
        }

        /**
         * v1.4.2 FIX 7: user FOCUSED a password field → offer to autofill.
         *
         * SECURITY (audit A-001): the argument is a signal only. It used to
         * select which saved credential was offered, which let any page ask for
         * somebody else's — see [offerLoginAutofill]. The lookup now uses the
         * tab's own origin, so what the page passes here cannot change the
         * answer; it is kept solely because an empty string still means "no
         * host", which is not worth a dialog.
         */
        @JavascriptInterface
        fun onLoginFocus(domain: String) {
            if (tab.incognito || Prefs.alwaysIncognito) return
            if (domain.isEmpty()) return
            main.post { offerLoginAutofill() }
        }

        /** v1.4.1: card/payment form detected on the page → offer quick fill. */
        @JavascriptInterface
        fun onPaymentForm() {
            if (tab.incognito || Prefs.alwaysIncognito) return
            main.post { maybeOfferPaymentFill() }
        }

        /**
         * v1.7.0: the CARD_SAVE watcher reported a Luhn-valid card number being
         * submitted (or filled, on SPA checkouts) → offer to save it encrypted.
         */
        @JavascriptInterface
        fun onCardSubmitted(number: String, holder: String, expMonth: String, expYear: String, cvc: String) {
            if (number.isEmpty()) return
            if (tab.incognito || Prefs.alwaysIncognito) return
            main.post { offerSaveCard(number, holder, expMonth, expYear, cvc) }
        }

        /** v1.5.1: identity form detected (name/email/phone/…) → offer PROFILE fill. */
        @JavascriptInterface
        fun onIdentityForm() {
            if (tab.incognito || Prefs.alwaysIncognito) return
            if (!Prefs.autofillEnabled) return
            main.post { offerIdentityAutofill() }
        }

        @JavascriptInterface
        fun notifyFavicon(href: String) {
            if (href.startsWith("http")) {
                main.post { loadFaviconFor(href) }
            }
        }

        /** v1.7.4: the page fetched a blob: URL for us — hand the payload to the
         *  download engine (see downloadBlobViaPage). */
        @JavascriptInterface
        fun onBlobData(name: String, mime: String, b64: String) {
            main.post { acceptBlobDownload(name, mime, b64) }
        }

        /** v1.7.5: the page-side FETCH fallback reported (photo downloads that the
         *  native engine could not fetch — see downloadViaPageFetch). */
        @JavascriptInterface
        fun onFetchData(url: String, name: String, mime: String, b64: String) {
            main.post { acceptFetchData(url, name, mime, b64) }
        }

        /** v1.7.7: the media sniffer found a playable/streamable URL. */
        @JavascriptInterface
        fun onMediaFound(url: String) {
            main.post { onMediaFoundInternal(url) }
        }

        /** v1.7.15: the page reported whether the touched element chain can scroll up. */
        @JavascriptInterface
        fun reportScroll(canUp: Boolean) {
            main.post { pageInnerScroll = canUp }
        }

        /** v1.7.15: the sniffer found a cross-origin iframe PLAYER source
         *  (movie sites embed players from other origins — the DOM walk cannot
         *  enter them; native offers opening the player page in a new tab). */
        @JavascriptInterface
        fun onEmbedFound(url: String) {
            main.post { onEmbedFoundInternal(url) }
        }

        /** v1.7.7: inline page translation — a chunk of source strings. */
        @JavascriptInterface
        fun translateChunk(json: String, cbId: Int) {
            translateChunkInternal(json, cbId)
        }

        /** v1.7.10: inline page translation — live progress (batches done/total). */
        @JavascriptInterface
        fun translationProgress(done: Int, total: Int) {
            main.post { translationProgressInternal(done, total) }
        }

        /** v1.7.7: inline page translation — all chunks processed. */
        @JavascriptInterface
        fun translationFinished() {
            main.post { this@MainActivity.translationFinished() }
        }
    }

    // ---------------- v1.4.2 FIX 7: login autofill (focus → chip → keyguard → fill) ----------------

    private var pendingAutofillTab: TabManager.Tab? = null
    private var pendingAutofillCreds: Pair<String, String>? = null

    /**
     * v1.5.1: an identity form (>= 2 visible name/email/phone/address fields, no
     * password) was detected → offer to fill it with a saved autofill PROFILE.
     * This is what finally makes the "Автозаполнение" panel data actually DO
     * something — before v1.5.1 profiles were stored but never injected.
     */
    private var identityOfferShownFor: String? = null
    private fun offerIdentityAutofill() {
        val tab = tabs.current ?: return
        val pageUrl = tab.pageUrlSnapshot
        if (isFinishing) return
        // one offer per page (like the login/payment offers)
        if (identityOfferShownFor == pageUrl) return
        identityOfferShownFor = pageUrl
        ioScope.launch {
            val profiles = try {
                db.autofillDao().all().first()
            } catch (_: Exception) { emptyList() }
            if (profiles.isEmpty()) return@launch
            main.post {
                if (tabs.current?.id != tab.id || isFinishing) return@post
                val names = profiles.map { it.label }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    // v1.7.98: message + list share one slot; the explanation
                    // would have hidden the profiles it is explaining.
                    .setCustomTitle(com.zephyr.browser.settings.SettingsUi.dialogTitle(
                        this@MainActivity,
                        getString(R.string.autofill_profile_title),
                        getString(R.string.autofill_profile_msg)))
                    .setItems(names) { _, which ->
                        val p = profiles[which]
                        try {
                            val fields = JSONObject(p.fields)
                            tab.webView?.evaluateJavascript(Scripts.profileFillScript(fields), null)
                            Toast.makeText(this@MainActivity, R.string.autofill_filled, Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, R.string.autofill_fill_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.not_now, null)
                    .show()
            }
        }
    }

    /** Saved credentials exist for the focused login form → offer a one-tap fill. */
    /**
     * SECURITY (audit A-001, fixed 1.8.1): the page must never choose WHOSE
     * password is offered.
     *
     * This took a `domain` straight from JavaScript — `Zephyr.onLoginFocus(host)`
     * — and looked a credential up with it whenever the real origin had none.
     * The query behind it is `origin LIKE '%domain%'`, wildcarded on both
     * sides, so the page did not even have to know what to ask for: calling
     * `Zephyr.onLoginFocus("a")` matched almost every saved entry and
     * `.firstOrNull()` handed one over.
     *
     * The rest wrote itself. A dialog appeared naming a site of the attacker's
     * choosing, and "Fill" typed a password saved for a bank into a form on
     * whatever page was open. The device-lock gate did not help: the user
     * unlocks because the dialog looks legitimate.
     *
     * The parameter is gone. A credential is now found only by the origin of
     * the page actually loaded in the tab, which the page cannot influence.
     */
    private fun offerLoginAutofill() {
        if (!Prefs.autofillEnabled || pendingAutofillTab != null) return
        val tab = tabs.current ?: return
        val origin = Urls.originOf(tab.pageUrlSnapshot)
        if (origin.isEmpty()) return
        val realDomain = Urls.domainOf(origin)
        ioScope.launch {
            val saved = try {
                db.passwordDao().byOrigin(origin)
                // a credential saved on a sibling host of the SAME registrable
                // domain still counts — but the domain comes from the loaded
                // page, never from the page's script, and must match exactly
                    ?: db.passwordDao().byDomain(realDomain)
                        .firstOrNull { Urls.domainOf(it.origin) == realDomain }
            } catch (_: Exception) { null } ?: return@launch
            val pass = try { VaultCrypto.decrypt(saved.passwordEnc) ?: "" } catch (_: Exception) { "" }
            if (pass.isEmpty()) return@launch
            val creds = saved.username to pass
            main.post {
                if (tabs.current?.id != tab.id || isFinishing) return@post
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.autofill_offer_title)
                    .setMessage(getString(R.string.autofill_offer_msg, saved.username, realDomain))
                    .setPositiveButton(R.string.autofill_fill) { _, _ ->
                        requestAutofillFill(tab, creds)
                    }
                    .setNegativeButton(R.string.not_now, null)
                    .show()
            }
        }
    }

    /** Gate the fill behind the device lock when one is set (biometric/PIN/pattern). */
    private fun requestAutofillFill(tab: TabManager.Tab, creds: Pair<String, String>) {
        val wv = tab.webView ?: return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km != null && km.isKeyguardSecure) {
            val i = try { km.createConfirmDeviceCredentialIntent(null, null) } catch (_: Exception) { null }
            if (i != null) {
                pendingAutofillTab = tab
                pendingAutofillCreds = creds
                try { startActivityForResult(i, AUTH_RC); return } catch (_: Exception) {}
            }
        }
        // no device lock (or dialog unavailable) → fill directly
        wv.evaluateJavascript(Scripts.fillScript(creds.first, creds.second), null)
    }

    private fun performPendingAutofill() {
        val tab = pendingAutofillTab
        val creds = pendingAutofillCreds
        pendingAutofillTab = null
        pendingAutofillCreds = null
        if (tab == null || creds == null) return
        tab.webView?.evaluateJavascript(Scripts.fillScript(creds.first, creds.second), null)
    }

    private fun offerSavePassword(origin: String, user: String, pass: String) {
        ioScope.launch {
            val existing = try { db.passwordDao().byOrigin(origin) } catch (_: Exception) { null }
            if (existing != null && existing.username == user) {
                // v1.4.2 FIX 7: same account re-submitted — refresh the ciphertext if the
                // password was rotated (update in place, no duplicate rows, no prompt).
                // v1.7.0: NEVER silent — the old catch{} hid the AndroidKeyStore encrypt
                // failure, so the user's "Да" saved NOTHING and later logins had no autofill.
                try {
                    val newEnc = VaultCrypto.encrypt(pass)
                    if (newEnc != existing.passwordEnc) db.passwordDao().update(existing.copy(passwordEnc = newEnc))
                } catch (_: Exception) {
                    main.post { if (!isFinishing) Toast.makeText(this@MainActivity, R.string.vault_encrypt_error, Toast.LENGTH_SHORT).show() }
                }
                return@launch
            }
            // v1.4.2 FIX 7: per-site save modes — "never" stays silent, "always" saves silently
            when (Prefs.passwordSiteMode(origin)) {
                "never" -> return@launch
                "always" -> {
                    try {
                        db.passwordDao().insert(
                            PasswordEntity(origin = origin, username = user, passwordEnc = VaultCrypto.encrypt(pass))
                        )
                    } catch (_: Exception) {
                        // v1.7.0: NEVER silent — surface crypto/DB failures to the user
                        main.post { if (!isFinishing) Toast.makeText(this@MainActivity, R.string.vault_encrypt_error, Toast.LENGTH_SHORT).show() }
                    }
                    return@launch
                }
            }
            main.post {
                if (isFinishing) return@post
                val remember = android.widget.CheckBox(this@MainActivity).apply {
                    text = getString(R.string.save_remember_site)
                    setPadding(dp(22), dp(2), dp(22), dp(2))
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.save_password_title)
                    .setMessage(getString(R.string.save_password_msg, Urls.domainOf(origin), user))
                    .setView(remember)
                    .setPositiveButton(R.string.save) { _, _ ->
                        ioScope.launch {
                            try {
                                db.passwordDao().insert(
                                    PasswordEntity(origin = origin, username = user, passwordEnc = VaultCrypto.encrypt(pass))
                                )
                            } catch (_: Exception) {
                                // v1.7.0: NEVER silent — this catch{} is why the user's "Да"
                                // (GitHub login) saved nothing in v1.3–v1.6.
                                main.post { if (!isFinishing) Toast.makeText(this@MainActivity, R.string.vault_encrypt_error, Toast.LENGTH_SHORT).show() }
                            }
                        }
                        if (remember.isChecked) Prefs.setPasswordSiteMode(origin, "always")
                    }
                    .setNegativeButton(R.string.not_now) { _, _ ->
                        if (remember.isChecked) Prefs.setPasswordSiteMode(origin, "never")
                    }
                    .show()
            }
        }
    }

    private fun fillIfEmpty() { /* superseded by onLoginFocus autofill (v1.4.2 FIX 7) */ }

    // ---------------- payment quick fill (v1.4.1) ----------------

    /** A payment form was detected on the current page → offer saved cards / PayPal. */
    private fun maybeOfferPaymentFill() {
        if (!Prefs.payAutofill) return
        val wv = tabs.current?.webView ?: return
        ioScope.launch {
            val methods = try { db.paymentDao().allOnce() } catch (_: Exception) { emptyList() }
            main.post {
                if (methods.isEmpty()) {
                    // v1.7.0: no saved payment methods → stay SILENT. The old pay_none_saved
                    // toast fired on false-positive detections (github.com login page showed
                    // TWO absurd "нет сохраненных карт" popups) — with no cards saved there is
                    // simply nothing to offer; the Payments panel is reachable from the menu.
                    return@post
                }
                val names = methods.map {
                    if (it.kind == "paypal") it.label
                    else "${it.label}${if (it.expMonth > 0) " · %02d/%02d".format(it.expMonth, it.expYear % 100) else ""}"
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.pay_quick_fill)
                    .setItems(names) { d, which ->
                        d.dismiss()
                        fillPayment(wv, methods[which])
                    }
                    .setNeutralButton(R.string.pay_manage, { d, _ ->
                        d.dismiss()
                        openPanel("payments")
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * v1.7.0: a Luhn-valid card number was submitted/filled on the page (CARD_SAVE watcher)
     * → offer to store it in the encrypted payments vault. Dedup by last-4 digits so the
     * dialog never spams; every failure TOASTS (no silent catch — that is how v1.3–v1.6
     * lost password/card data). Save is always offered regardless of Prefs.payAutofill
     * (that toggle only gates FILLING); the user decides per-card via the dialog.
     */
    private fun offerSaveCard(number: String, holder: String, expMonth: String, expYear: String, cvc: String) {
        val digits = number.filter { it.isDigit() }
        if (digits.length !in 13..19) return
        val last4 = digits.takeLast(4)
        val month = expMonth.filter { it.isDigit() }.toIntOrNull() ?: 0
        val yearRaw = expYear.filter { it.isDigit() }.toIntOrNull() ?: 0
        val year = when {
            yearRaw in 1..99 -> 2000 + yearRaw   // 2-digit year → 20xx
            yearRaw > 99 -> yearRaw              // already 4-digit
            else -> 0
        }
        val cvv = cvc.trim()
        ioScope.launch {
            val existing = try { db.paymentDao().allOnce() } catch (_: Exception) { emptyList() }
            // dedup: same last-4 already saved → skip silently (no dialog spam)
            val dup = existing.any { card ->
                card.kind == "card" && (try { VaultCrypto.decrypt(card.numberEnc) ?: "" } catch (_: Exception) { "" })
                    .filter { it.isDigit() }.takeLast(4) == last4
            }
            if (dup) return@launch
            main.post {
                if (isFinishing) return@post
                val msg = getString(R.string.card_save_msg, last4)
                    .let { m -> if (holder.isNotBlank()) "$m\n$holder" else m }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.card_save_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.save) { _, _ ->
                        ioScope.launch {
                            try {
                                db.paymentDao().insert(
                                    com.zephyr.browser.db.PaymentEntity(
                                        kind = "card",
                                        label = "•••• $last4",
                                        numberEnc = VaultCrypto.encrypt(digits),
                                        holder = holder.trim(),
                                        expMonth = month,
                                        expYear = year,
                                        cvvEnc = if (cvv.isNotEmpty()) VaultCrypto.encrypt(cvv) else "",
                                        billingZip = ""
                                    )
                                )
                                main.post {
                                    if (!isFinishing) Toast.makeText(this@MainActivity, R.string.card_saved, Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                // v1.7.0: NEVER silent — encryption failure must be visible
                                main.post {
                                    if (!isFinishing) Toast.makeText(this@MainActivity, R.string.vault_encrypt_error, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.not_now, null)
                    .show()
            }
        }
    }

    /** Fill the detected card form with the chosen payment method (PAN/CVV decrypted in-memory only). */
    private fun fillPayment(wv: WebView, p: com.zephyr.browser.db.PaymentEntity) {
        ioScope.launch {
            val number = try { VaultCrypto.decrypt(p.numberEnc) ?: "" } catch (_: Exception) { "" }
            val cvv = try { VaultCrypto.decrypt(p.cvvEnc) ?: "" } catch (_: Exception) { "" }
            val js = Scripts.fillPaymentScript(
                number, p.holder,
                if (p.expMonth > 0) "%02d".format(p.expMonth) else "",
                if (p.expYear > 0) "%02d".format(p.expYear % 100) else "",
                cvv, p.billingZip, p.kind == "paypal"
            )
            main.post { wv.evaluateJavascript(js, null) }
        }
    }

    // ---------------- site info ----------------

    fun showSiteInfo() {
        val tab = tabs.current ?: return
        val url = tab.pageUrlSnapshot
        if (url == Urls.DEFAULT_HOME) return
        MenuSheets.showSiteInfo(this, url)
    }

    fun clearSiteData(url: String) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val variants = listOf(
            "https://$host", "http://$host", "https://$host/", "http://$host/",
            "https://${host.removePrefix("www.")}", "http://${host.removePrefix("www.")}"
        )
        val cm = CookieManager.getInstance()
        for (v in variants) {
            try { cm.setCookie(v, "") } catch (_: Exception) {}
        }
        try { cm.removeExpiredCookie() } catch (_: Exception) {}
        try { android.webkit.WebStorage.getInstance().deleteOrigin("https://$host") } catch (_: Exception) {}
        try { android.webkit.WebStorage.getInstance().deleteOrigin("http://$host") } catch (_: Exception) {}
        tabs.current?.webView?.clearCache(true)
        tabs.current?.webView?.clearFormData()
        Toast.makeText(this, R.string.site_data_cleared, Toast.LENGTH_SHORT).show()
    }

    // ---------------- desktop mode ----------------

    fun toggleDesktopMode() {
        val tab = tabs.current ?: return
        val on = !tab.desktop
        tabs.setDesktopMode(tab, on)
        if (on) {
            // swap m. -> www.
            val url = tab.pageUrlSnapshot
            val swapped = swapMobileHost(url)
            navigate(swapped)
            tab.webView?.evaluateJavascript(Scripts.DESKTOP_VIEWPORT, null)
        } else {
            navigate(tab.pageUrlSnapshot)
        }
        Toast.makeText(this, if (on) R.string.pc_mode_on else R.string.pc_mode_off, Toast.LENGTH_SHORT).show()
    }

    private fun swapMobileHost(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return url
            val newHost = when {
                host.startsWith("m.") -> host.removePrefix("m.")
                host.startsWith("mobile.") -> host.removePrefix("mobile.")
                host == "m.wikipedia.org" -> "wikipedia.org"
                else -> host
            }
            url.replace(host, newHost, ignoreCase = true)
        } catch (_: Exception) { url }
    }

    // ---------------- reader ----------------

    fun openReader() {
        val tab = tabs.current ?: return
        val wv = tab.webView ?: return
        wv.evaluateJavascript(Scripts.READER_EXTRACT) { json ->
            try {
                val v = JSONTokener(json).nextValue()
                if (v is JSONObject && v.optInt("len", 0) > 350) {
                    MenuSheets.showReader(this, v)
                } else {
                    Toast.makeText(this, R.string.reader_unavailable, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.reader_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- v1.7.7: reader library ----------------

    /** v1.7.7 (user: «Reader Mode++ — офлайн-библиотека красивых статей»): saves the
     *  EXTRACTED article (not an .mht) into reader_articles — the Library panel
     *  re-renders it in the premium reader typography, fully offline. */
    fun saveReaderArticle(json: JSONObject) {
        val tab = tabs.current ?: return
        val title = json.optString("title", "").ifEmpty { tab.title }
        val byline = json.optString("byline", "")
        val site = json.optString("siteName", "")
        val html = json.optString("html", "")
        val url = tab.pageUrlSnapshot
        if (html.isEmpty()) {
            Toast.makeText(this, R.string.reader_lib_fail, Toast.LENGTH_SHORT).show()
            return
        }
        ioScope.launch {
            try {
                val dupe = if (url.isNotEmpty()) db.readerArticleDao().byUrl(url) else null
                db.readerArticleDao().insert(com.zephyr.browser.db.ReaderArticleEntity(
                    title = title, byline = byline, site = site, url = url, html = html))
                main.post {
                    Toast.makeText(this@MainActivity, if (dupe != null) R.string.reader_lib_updated
                    else R.string.reader_lib_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                main.post { Toast.makeText(this@MainActivity, R.string.reader_lib_fail, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ---------------- bookmarks ----------------

    fun addBookmark() {
        val tab = tabs.current ?: return
        val url = tab.pageUrlSnapshot
        if (url == Urls.DEFAULT_HOME) return
        val title = tab.title.ifEmpty { Urls.domainOf(url) }
        ioScope.launch {
            val exists = try { db.bookmarkDao().byUrl(url) } catch (_: Exception) { null }
            if (exists != null) {
                main.post { Toast.makeText(this@MainActivity, R.string.bm_exists, Toast.LENGTH_SHORT).show() }
                return@launch
            }
            // v1.5.1: the broken "save to private" checkbox is REMOVED — the private
            // bookmark group never worked and is gone from the panel as well.
            main.post {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.add_bookmark)
                    .setMessage(url)
                    .setPositiveButton(R.string.save) { _, _ ->
                        ioScope.launch {
                            try {
                                db.bookmarkDao().insert(
                                    BookmarkEntity(title = title, url = url, incognito = false)
                                )
                            } catch (_: Exception) {}
                            main.post { Toast.makeText(this@MainActivity, R.string.bm_saved, Toast.LENGTH_SHORT).show() }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    // ---------------- quick access ----------------

    // v1.7.3 (user: "для сайтов надо добавить опцию добавить в быстрый доступ! а то
    // приходится вручную вводить"): site-menu action that pins the current page as a
    // quick-access tile on the home screen (quick_sites table, rendered by HomePageView).
    // Mirrors addBookmark(): duplicate check on IO, name/url dialog on main, insert on IO.
    fun addToQuickAccess() {
        val tab = tabs.current ?: return
        val url = tab.pageUrlSnapshot
        if (url == Urls.DEFAULT_HOME) {
            Toast.makeText(this, R.string.quick_need_site, Toast.LENGTH_SHORT).show()
            return
        }
        val title = tab.title.ifEmpty { Urls.domainOf(url) }
        ioScope.launch {
            val exists = try { db.quickSiteDao().byUrl(url) } catch (_: Exception) { null }
            if (exists != null) {
                main.post { Toast.makeText(this@MainActivity, R.string.quick_exists, Toast.LENGTH_SHORT).show() }
                return@launch
            }
            main.post {
                // Same two-field dialog style as HomePageView's add/edit quick dialogs.
                val name = android.widget.EditText(this@MainActivity).apply {
                    hint = getString(R.string.home_add_title); setText(title)
                }
                val urlInput = android.widget.EditText(this@MainActivity).apply {
                    hint = getString(R.string.home_add_url); setText(url)
                }
                val box = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    addView(name)
                    addView(urlInput)
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.quick_dialog_title)
                    .setView(box)
                    .setPositiveButton(R.string.save) { _, _ ->
                        val t = name.text.toString().trim()
                        val u = urlInput.text.toString().trim()
                        if (t.isNotEmpty() && u.isNotEmpty()) {
                            ioScope.launch {
                                try {
                                    db.quickSiteDao().insert(
                                        com.zephyr.browser.db.QuickSiteEntity(
                                            title = t,
                                            url = if (u.startsWith("http")) u else "https://$u"
                                        )
                                    )
                                } catch (_: Exception) {}
                                main.post { Toast.makeText(this@MainActivity, R.string.quick_added, Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    // ---------------- v1.7.6: offline pages + selection translation ----------------

    /** v1.7.6 (user: «Оффлайн-страницы — кнопка сохранить для чтения без сети»):
     *  snapshots the CURRENT page as a WebView web-archive (.mht) into the private
     *  offline dir and journals it — the Offline panel lists and re-opens them
     *  (file access is granted only for this exact dir, see navigate()). */
    fun saveOffline() {
        val tab = tabs.current ?: return
        val wv = tab.webView
        if (wv == null || tab.isHome) {
            Toast.makeText(this, R.string.offline_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val url = tab.pageUrlSnapshot
        val title = tab.title.ifEmpty { url }
        val dir = File(filesDir, "offline").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "page_$stamp.mht")
        try {
            wv.saveWebArchive(file.absolutePath, false) { savedPath ->
                val ok = savedPath != null && File(savedPath).length() > 0
                if (ok) {
                    ioScope.launch {
                        try {
                            db.offlinePageDao().insert(com.zephyr.browser.db.OfflinePageEntity(
                                title = title, url = url, path = file.absolutePath))
                        } catch (_: Exception) {}
                    }
                    Toast.makeText(this, R.string.offline_saved, Toast.LENGTH_SHORT).show()
                } else {
                    file.delete()
                    Toast.makeText(this, R.string.offline_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.offline_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** v1.7.6 (user: «Переводчик без гугл-обёртки — перевод выделенного текста»):
     *  a "Перевести" item is injected into the TEXT-SELECTION action mode; the
     *  selected text is translated in-app (TranslationApi — no wrapper page, no
     *  navigation away) and shown in a dialog. */
    override fun onActionModeStarted(mode: android.view.ActionMode?) {
        super.onActionModeStarted(mode)
        if (mode == null) return
        try {
            mode.menu.add(0, 9990, 0, R.string.translate_selection)?.apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
                setOnMenuItemClickListener {
                    translateSelection()
                    true
                }
            }
        } catch (_: Exception) {}
    }

    private fun translateSelection() {
        val wv = tabs.current?.webView ?: return
        wv.evaluateJavascript(
            "(function(){try{return window.getSelection?window.getSelection().toString():''}catch(e){return ''}})()") { raw ->
            val text = try {
                (org.json.JSONTokener(raw).nextValue() as? String) ?: ""
            } catch (_: Exception) { "" }
            val clean = text.trim()
            if (clean.isEmpty()) {
                Toast.makeText(this, R.string.translate_failed, Toast.LENGTH_SHORT).show()
            } else {
                showSelectionTranslation(clean)
            }
        }
    }

    private fun showSelectionTranslation(selected: String) {
        val body = TextView(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            textSize = 15f
            text = getString(R.string.translating)
            setTextIsSelectable(true)
            setTextColor(resolveThemeColor(R.attr.colorOnSurface))
        }
        var result: String? = null
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translate)
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.translate_copy) { _, _ -> result?.let { copyToClipboard(it) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
        ioScope.launch {
            val res = com.zephyr.browser.TranslationApi.translate(selected)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!dlg.isShowing) return@withContext
                if (res == null) body.text = getString(R.string.translate_failed)
                else { result = res; body.text = res }
            }
        }
    }

    // ---------------- panels & settings ----------------

    fun openPanel(panel: String) {
        startActivity(Intent(this, com.zephyr.browser.panels.PanelsActivity::class.java).apply {
            putExtra("panel", panel)
            // v1.5.0 FIX 5: panels opened from an incognito tab are incognito-chromed
            putExtra(com.zephyr.browser.panels.PanelsActivity.EXTRA_INCOGNITO, tabs.current?.incognito == true)
        })
        // v1.5.0 FIX 7: panel slides up over the browser
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.panel_in, R.anim.hold)
    }

    // ---------------- suggestions ----------------

    /** Logical state of the suggestions dropdown — guards the async hide animation. */
    private var suggestionsShown = false

    private fun showSuggestions() {
        querySuggestions(omniboxInput.text.toString())
    }

    private fun hideSuggestions() {
        suggestionsShown = false
        if (suggestionsPanel.visibility != View.VISIBLE) return
        // v1.5.0 FIX 7: quick fade-out, then GONE
        Anim.fadeOut(suggestionsPanel, 120) {
            if (!suggestionsShown) suggestionsPanel.visibility = View.GONE
        }
    }

    private fun showSuggestionsPanel() {
        // wasHidden → a hide-animation may still be in flight; slideUp() cancels it
        val wasHidden = !suggestionsShown
        suggestionsShown = true
        if (suggestionsPanel.visibility != View.VISIBLE) {
            suggestionsPanel.visibility = View.VISIBLE
        }
        if (wasHidden) {
            // v1.5.0 FIX 7: slide-up + fade (was a hard visibility flip)
            Anim.slideUp(suggestionsPanel, distanceDp = 14f, duration = 180)
        }
    }

    private var suggestJob: kotlinx.coroutines.Job? = null

    private fun querySuggestions(text: String) {
        suggestionsAdapter?.let { ad -> ad.onPick = null }
        suggestJob?.cancel()
        if (text.isBlank()) {
            hideSuggestions()
            return
        }
        suggestJob = ioScope.launch {
            val q = text.trim()
            val results = mutableListOf<SuggestionsAdapter.Suggestion>()
            results.add(SuggestionsAdapter.Suggestion(null, q, getString(R.string.suggestion_search,
                getString(Prefs.currentEngine().nameRes)), "search"))
            try {
                for (h in db.historyDao().search(q).take(4)) {
                    results.add(SuggestionsAdapter.Suggestion(null, h.url, h.title.ifEmpty { h.url }, "history"))
                }
            } catch (_: Exception) {}
            try {
                for (b in db.bookmarkDao().allOnce().filter {
                    it.title.contains(q, true) || it.url.contains(q, true)
                }.take(3)) {
                    results.add(SuggestionsAdapter.Suggestion(null, b.url, b.title, "bookmark"))
                }
            } catch (_: Exception) {}
            main.post {
                if (!omniboxInput.hasFocus()) return@post
                suggestionsAdapter?.submit(results)
                if (results.isEmpty()) hideSuggestions() else showSuggestionsPanel()
                suggestionsAdapter?.onPick = { s ->
                    omniboxInput.setText(if (s.kind == "search") s.url else s.url)
                    omniboxInput.setSelection(omniboxInput.text.length)
                    submitOmnibox()
                }
            }
        }
    }

    // ---------------- misc ----------------

    private fun maybeFirstRun() {
        if (Prefs.firstRunDone) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_msg)
            .setPositiveButton(R.string.ok) { _, _ -> }
            .show()
    }

    /** v1.4.1: live page-appearance switching — re-injects the CSS into the CURRENT page
     *  (was only applied on next load: user report "из сепии на светлую не переключалось").
     *  v1.7.5: unified — injects OR REMOVES the look CSS and syncs darkening + the
     *  WebView base color. The per-site override (tab.appearance) wins over the global. */
    fun applyPageAppearance(appearance: Int) {
        // appearance param kept for call-site compatibility; the EFFECTIVE value is
        // recomputed (per-site override beats the freshly-set global default)
        applyAppearanceLive()
    }

    /** v1.7.5: applies the effective appearance of the current tab RIGHT NOW. */
    fun applyAppearanceLive() {
        val tab = tabs.current ?: return
        val wv = tab.webView ?: return
        val eff = tabs.effectiveAppearance(tab)
        val js = Scripts.appearanceCss(eff).ifEmpty {
            // appearance "system/light" → REMOVE the style tag so the page resets
            "(function(){try{var s=document.getElementById('__zephyrLook');if(s)s.remove();}catch(e){}})();"
        }
        wv.evaluateJavascript(js, null)
        try { tabs.applySettingsFor(wv, tab) } catch (_: Exception) {}
    }

    /** v1.7.5 (user: «взял сепию на википедии — на ютубе тоже сепия»): the menu
     *  appearance picker is PER-SITE — the choice lands in the site settings for
     *  the current domain; "как в настройках" clears the override. On the home
     *  page the picker edits the GLOBAL default instead.
     *  v1.7.6 FIX (user: «на википедии — Выбор действует только для сайта google.com»):
     *  the host now comes from the WebView's LIVE url — the old snapshot could still
     *  hold the previous page (google.com) right after a navigation, so the dialog
     *  named the wrong site. The home dialog also explains that it sets the default
     *  for ALL sites (that's why sepia chosen on Home later shows on YouTube). */
    fun showAppearancePicker() {
        // v1.7.7: the confusing per-site dialog («выбор действует только для сайта …
        // и кнопка Отмена») is replaced by the premium scope-aware sheet: theme
        // PREVIEW CARDS + explicit chips (Только этот сайт / Все сайты).
        com.zephyr.browser.ui.MenuSheets.showAppearancePickerSheet(this)
        return
    }

/** Writes the per-site appearance override for the current domain and applies it live. */
    fun setSiteAppearance(value: Int?) {
        val tab = tabs.current ?: return
        // v1.7.6: same LIVE-url host resolution as the picker
        val liveUrl = tab.webView?.url ?: tab.pageUrlSnapshot.ifEmpty { tab.startUrl }
        val host = Urls.domainOf(liveUrl)
        if (host.isEmpty()) { Prefs.setPageAppearance(value ?: 0); applyAppearanceLive(); return }
        tab.appearance = value ?: -1
        ioScope.launch {
            try {
                val dao = this@MainActivity.db.domainSettingsDao()
                val existing = dao.byDomain(host)
                dao.insert((existing ?: com.zephyr.browser.db.DomainSettingsEntity(domain = host)).copy(appearance = value))
                tabs.refreshDomainSettingsAsync()
            } catch (_: Exception) {}
        }
        applyAppearanceLive()
    }

    fun recreateUi() {
        applyIncognitoUi(tabs.current?.incognito == true)
        setupToolbarSlots()
        updateOmniboxForTab(tabs.current)
        updateTabCounter()
        homeView.reload()
        // v1.7.6: pull-to-refresh may have been (de)activated in Settings
        (tabs.current?.webView?.parent as? SwipeRefreshLayout)?.isEnabled = Prefs.pullToRefresh
    }

    // ---------------- v1.7.6: night mode by schedule ----------------

    /** Minute ticker (started in onResume, stopped in onPause). */
    private val nightTick: Runnable = object : Runnable {
        override fun run() {
            applyNightSchedule()
            main.postDelayed(this, 60_000)
        }
    }

    /** v1.7.6 (user: «Ночной режим по расписанию — тема приложения + страниц автоматически»):
     *  entering the night window saves the current day-time theme & page appearance,
     *  then applies the dark app theme + dark page look; leaving the window restores
     *  both. The theme flip uses the regular setters so every activity picks it up,
     *  and recreate() re-skins this one. */
    fun applyNightSchedule() {
        try {
            val inWindow = Prefs.isNightWindowNow()
            if (inWindow && !Prefs.nightActive) {
                val savedTheme = Prefs.themeId
                val savedAppearance = Prefs.pageAppearance
                Prefs.setNightActive(true, savedTheme, savedAppearance)
                Prefs.setTheme(1)
                Prefs.setPageAppearance(2)
                appliedThemeId = Prefs.themeSnapshot(this)
                applyAppearanceLive()
                recreate()
            } else if (!inWindow && Prefs.nightActive) {
                val restoreTheme = Prefs.nightSavedTheme
                val restoreAppearance = Prefs.nightSavedAppearance
                Prefs.setNightActive(false, 0, 0)
                Prefs.setTheme(restoreTheme)
                Prefs.setPageAppearance(restoreAppearance)
                appliedThemeId = Prefs.themeSnapshot(this)
                applyAppearanceLive()
                recreate()
            }
        } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            MenuSheets.showMainMenu(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

val Context.db: com.zephyr.browser.db.AppDb get() = com.zephyr.browser.db.AppDb.get(this)
