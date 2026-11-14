package com.zephyr.browser.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.FaviconLoader
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.SearchEngines
import com.zephyr.browser.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Home page: search bar (engine/voice/image/AI), quick access grid, recents & bookmarks card grids.
 * Background (color or wallpaper photo) is a dedicated NON-INTERACTIVE layer BEHIND content —
 * it never intercepts touches and never covers UI elements.
 * v1.7.9: the recents/bookmarks feed direction is customizable (Settings → Главный экран):
 * Prefs.homeFeedMode 0 = horizontal swipe rows (default), 1 = vertical grid of wide
 * list-style cards with a "see all" footer that opens the full history/bookmarks panel.
 * v1.7.14: the WHOLE home layout is customizable (Settings → Главный экран →
 * Главная страница) — Prefs.homeVariant: 0 standard (default), 1 minimal (search only),
 * 2 large live clock, 3 large sites grid, 4 custom page — the user's own HTML file
 * (e.g. file:///storage/emulated/0/index.html) or URL rendered in an embedded WebView.
 * v1.7.17 (user: «когда я выбираю допустим страницу с часами там уже нельзя
 * и не накладываются закладки, недавние итд хотя тумблеры включены ты не
 * предусмотрел это!»): the Быстрый доступ / Недавние / Закладки toggles now apply
 * to the clock (2) and sites-grid (3) variants too; variant 1 stays search-only
 * BY DESIGN (the clean look IS the variant).
 */
class HomePageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onNavigate: (String) -> Unit = {}
    var onVoice: () -> Unit = {}
    var onImageSearch: () -> Unit = {}
    var onAiChat: () -> Unit = {}
    var onEngineClick: () -> Unit = {}

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

    // background layer (non-interactive)
    private var bgLayer: ImageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.CENTER_CROP
        isClickable = false
        isFocusable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
    }
    private var bgColorView: View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        isClickable = false
        isFocusable = false
    }
    // v1.4.2 FIX 8: readability scrim over photo/HEX backgrounds — soft black gradient
    // (40% → 0) that sits BEHIND home content so tiles/text stay legible on any photo.
    private var scrimView: View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        isClickable = false
        isFocusable = false
        visibility = View.GONE
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x66000000.toInt(), 0x33000000.toInt(), 0x00000000.toInt()))
    }

    // content
    private var scroll: ScrollView
    private var contentColumn: LinearLayout
    private var searchPill: LinearLayout
    private var searchInput: EditText
    private var engineLogo: ImageView
    private var privateBanner: LinearLayout
    private var privateTitle: TextView
    private var quickSection: LinearLayout
    private var quickRows: MutableList<LinearLayout> = mutableListOf()   // v1.4.1: grid rows (N columns)
    private var recentsSection: LinearLayout
    private var recentsRow: LinearLayout
    private var recentsSwipe: android.widget.HorizontalScrollView
    private var bookmarksSection: LinearLayout
    private var bookmarksRow: LinearLayout
    private var bookmarksSwipe: android.widget.HorizontalScrollView

    private var incognito = false
    private var quickSites: List<com.zephyr.browser.db.QuickSiteEntity> = emptyList()
    private var recents: List<com.zephyr.browser.db.HistoryEntity> = emptyList()
    private var bookmarks: List<com.zephyr.browser.db.BookmarkEntity> = emptyList()

    // v1.7.14 home page variants (Settings → Главный экран → Главная страница):
    // 0 standard (default), 1 minimal, 2 clock, 3 sites grid, 4 custom HTML/URL page.
    // init{} builds the variant-0 structure, so lastBuiltVariant starts at 0 — the
    // default layout for existing users is never even touched by the variant logic.
    private var lastBuiltVariant = 0
    private var clockTicker: android.os.Handler? = null
    private var clockBlock: LinearLayout? = null
    private var clockTime: TextView? = null
    private var clockDate: TextView? = null
    private var customWeb: android.webkit.WebView? = null
    private var lastLoadedCustom: String? = null
    // v1.7.15: load-error card over the custom page (removed by onPageStarted)
    private var customErrorOverlay: TextView? = null

    /** v1.7.14 variant 2: 30-second clock refresh loop (single ticker, never stacked). */
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockTicker?.postDelayed(this, 30_000L)
        }
    }

    init {
        // layer 0: background color + wallpaper (both non-interactive, behind everything)
        addView(bgColorView)
        addView(bgLayer)
        // v1.4.2 FIX 8: scrim directly above the background, below the content
        addView(scrimView)

        // layer 1: content
        scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
            clipToPadding = false
        }
        contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(24), dp(16), dp(28))
        }
        scroll.addView(contentColumn)
        addView(scroll)

        // private-mode banner
        privateBanner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        privateTitle = TextView(context).apply {
            text = context.getString(R.string.home_private_title)
            textSize = 20f
            setTextColor(0xFFEDE7FB.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        privateBanner.addView(privateTitle)
        privateBanner.addView(TextView(context).apply {
            text = context.getString(R.string.home_private_subtitle)
            textSize = 13f
            setTextColor(0xFFB7A7DA.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })
        contentColumn.addView(privateBanner)

        // search bar (v1.4.1: hideable via Настройки → Главный экран)
        searchPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground()
            setPadding(dp(14), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val engineLogo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            contentDescription = context.getString(R.string.home_pick_engine)
            setImageResource(R.drawable.ic_search)
        }
        engineLogo.setOnClickListener { onEngineClick() }
        searchInput = EditText(context).apply {
            hint = context.getString(R.string.home_search_hint)
            textSize = 15f
            background = null
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { submitSearch(); true } else false
            }
        }
        this.engineLogo = engineLogo
        searchPill.addView(engineLogo)
        searchPill.addView(searchInput)
        fun quickBtn(iconRes: Int, descRes: Int, action: () -> Unit): ImageButton {
            val b = ImageButton(context).apply {
                setImageResource(iconRes)
                background = circularRipple()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                contentDescription = context.getString(descRes)
                setOnClickListener { action() }
            }
            return b
        }
        searchPill.addView(quickBtn(R.drawable.ic_camera, R.string.image_search) { onImageSearch() })
        searchPill.addView(quickBtn(R.drawable.ic_mic, R.string.voice_search) { onVoice() })
        searchPill.addView(quickBtn(R.drawable.ic_ai, R.string.ai_chat) { onAiChat() })
        // tap on pill background focuses the input; text & hint always visible
        searchPill.setOnClickListener {
            searchInput.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
        contentColumn.addView(searchPill)

        // sections (v1.4.1: quick = N-column GRID; recents/bookmarks = horizontal SWIPE rows)
        quickSection = section(R.string.home_quick_access)
        contentColumn.addView(quickSection)

        recentsSection = section(R.string.home_recents)
        recentsRow = tilesRow()
        recentsSwipe = horizontalSwipeRow(recentsRow)
        recentsSection.addView(recentsSwipe)
        contentColumn.addView(recentsSection)

        bookmarksSection = section(R.string.home_bookmarks)
        bookmarksRow = tilesRow()
        bookmarksSwipe = horizontalSwipeRow(bookmarksRow)
        bookmarksSection.addView(bookmarksSwipe)
        contentColumn.addView(bookmarksSection)

        applyBackground()
    }

    /**
     * v1.4.1 STABLE BACKGROUND FIX: the wallpaper layer keeps its FIRST measured size
     * when the window resizes (soft keyboard). Without this, adjustResize + CENTER_CROP
     * re-cropped/zoomed the photo on every omnibox tap (user report: "фото уменьшается").
     */
    private var stableW = -1
    private var stableH = -1

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (stableW < 0 && width > 0 && height > 0) {
            stableW = width
            stableH = height
        }
        if (stableW > 0 && (width != stableW || height != stableH)) {
            // window resized (keyboard) → pin the background to the original rect
            bgLayer.layout(bgLayer.left, bgLayer.top, bgLayer.left + stableW, bgLayer.top + stableH)
            bgColorView.layout(0, 0, stableW, stableH)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // v1.7.14: a layout change may transiently detach/reattach this view — revive the clock
        if (lastBuiltVariant == 2) startClockTicker()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // v1.7.14: ONLY stop timers here. This view lives for the whole app lifetime
        // (MainActivity's homeView), so the single cached custom WebView is kept —
        // destroying it on a transient detach would blank the custom home page.
        stopClockTicker()
    }

    /** Horizontally swipeable row (user request: "макеты двигать влево вправо"). */
    private fun horizontalSwipeRow(inner: LinearLayout): android.widget.HorizontalScrollView =
        android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            clipToPadding = false
            addView(inner)
        }

    // ---------------- public API ----------------

    fun setIncognito(value: Boolean) {
        if (incognito == value) { tintPrivate(); return }
        incognito = value
        applyBackground()
        tintPrivate()
        reload()
    }

    /**
     * v1.6.0: home CONTENT entrance — the scroll container rises 14dp and fades in
     * over an ALREADY-OPAQUE home background. This replaces the old whole-view
     * fadeIn which made the home semi-transparent for its first 120 ms (the page
     * behind — e.g. YouTube — flashed through right after pressing the home button).
     */
    fun animateContentIn() {
        val dp14 = 14f * resources.displayMetrics.density
        // v1.7.14: custom page — fade the embedded WebView itself (no native content column)
        val custom = customWeb
        if (custom != null && custom.visibility == View.VISIBLE) {
            custom.animate().cancel()
            custom.alpha = 0f
            custom.animate().alpha(1f)
                .setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))
                .start()
            return
        }
        scroll.animate().cancel()
        scroll.alpha = 0f
        scroll.translationY = dp14
        scroll.animate().alpha(1f).translationY(0f)
            .setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))
            .start()
    }

    fun reload() {
        applyEngineLabel()
        applyBackground()   // v1.3.2 fix: background state must refresh every time home shows
        syncVariantStructure()   // v1.7.14: variant switch + custom page refresh (no-op for variant 0)
        io.launch {
            val db = com.zephyr.browser.db.AppDb.get(context.applicationContext)
            val q = try { db.quickSiteDao().allOnce() } catch (_: Exception) { emptyList() }
            val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            val r = try { db.historyDao().recentSince(since, 10) } catch (_: Exception) { emptyList() }
            val b = try {
                // v1.5.1: the private bookmark group is GONE — incognito home shows the
                // same (regular) bookmarks; they are the user's own picks, not history.
                db.bookmarkDao().regular(10)
            } catch (_: Exception) { emptyList() }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                quickSites = q
                recents = r
                bookmarks = b
                rebuildQuick()
                rebuildRecents()
                rebuildBookmarks()
                applySectionToggles()
            }
        }
    }

    // ---------------- background (BEHIND content, non-interactive) ----------------

    private fun applyBackground() {
        if (incognito) {
            bgColorView.setBackgroundResource(R.drawable.bg_incognito_root)
            bgLayer.setImageDrawable(null)
            scrimView.visibility = View.GONE
            return
        }
        bgColorView.setBackgroundColor(if (Prefs.homeBgColor != 0) Prefs.homeBgColor else surfaceColor())
        // v1.4.2 FIX 8: photo/HEX background → the readability scrim comes on
        scrimView.visibility =
            if (Prefs.homeBgColor != 0 || Prefs.wallpaperUri.isNotEmpty()) View.VISIBLE else View.GONE
        val path = Prefs.wallpaperUri
        if (path.isNotEmpty()) {
            // v1.3.2 white-bg fix: decode OFF the main thread, from the local copy in filesDir
            io.launch {
                // v1.4.1 QUALITY fix: decode at up to 2× screen width — CENTER_CROP consumes
                // only ~55% of the width for portrait photos, so 1080-target decodes looked
                // blurry (user report: "качество ухудшается")
                val target = (resources.displayMetrics.widthPixels * 2).coerceAtLeast(1440)
                val bmp = decodeSampled(path, target)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (Prefs.wallpaperUri == path) bgLayer.setImageBitmap(bmp)
                }
            }
        } else {
            bgLayer.setImageDrawable(null)
        }
    }

    private fun decodeSampled(source: String, target: Int): Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openSource(source)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > target * 2) sample *= 2
            val dec = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            openSource(source)?.use { android.graphics.BitmapFactory.decodeStream(it, null, dec) }
        } catch (_: Exception) { null }
    }

    /** Local copied wallpaper (plain path) or legacy content uri. */
    private fun openSource(source: String): java.io.InputStream? = try {
        if (source.startsWith("content:")) context.contentResolver.openInputStream(android.net.Uri.parse(source))
        else java.io.File(source).inputStream()
    } catch (_: Exception) { null }

    // ---------------- search ----------------

    private fun applyEngineLabel() {
        val engine = Prefs.currentEngine()
        val host = engine.urlTemplate.substringAfter("://").substringBefore("/")
        searchInput.hint = context.getString(R.string.omnibox_engine_hint, host)
        // async favicon fetch — direct HTTP, not FaviconLoader.fetchNetwork (that one
        // calls Urls.domainOf(url) first and would return "google.com" instead of $host).
        io.launch {
            var bmp: Bitmap? = null
            try {
                val url = java.net.URL("https://www.google.com/s2/favicons?sz=64&domain=$host")
                val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode == 200)
                        bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
            } catch (_: Exception) {}
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (bmp != null) engineLogo.setImageBitmap(bmp)
                else engineLogo.setImageResource(R.drawable.ic_search)
            }
        }
    }

    private fun submitSearch() {
        val text = searchInput.text.toString().trim()
        if (text.isEmpty()) return
        searchInput.setText("")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
        val engine = Prefs.currentEngine()
        onNavigate(Urls.normalize(text) { q -> SearchEngines.searchUrl(engine.id, q) })
    }

    // ---------------- sections ----------------

    private fun section(titleRes: Int): LinearLayout {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12); leftMargin = dp(2) }
        }

        val (iconStr, gradColors) = when (titleRes) {
            R.string.home_quick_access -> "⚡" to intArrayOf(0xFFFF8A65.toInt(), 0xFFFF5722.toInt())
            R.string.home_recents -> "🕒" to intArrayOf(0xFF4DD0E1.toInt(), 0xFF009688.toInt())
            R.string.home_bookmarks -> "🔖" to intArrayOf(0xFFB388FF.toInt(), 0xFF651FFF.toInt())
            else -> "✨" to intArrayOf(accentColor(), accentColor())
        }

        // Vibrant rounded icon badge
        val badge = TextView(context).apply {
            text = iconStr
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (incognito) intArrayOf(0xFF9575CD.toInt(), 0xFF512DA8.toInt()) else gradColors
            ).apply {
                cornerRadius = dp(10).toFloat()
            }
        }
        header.addView(badge)

        header.addView(TextView(context).apply {
            text = context.getString(titleRes)
            textSize = 13.5f
            letterSpacing = 0.04f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        })
        wrap.addView(header)
        return wrap
    }

    private fun tilesRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun applySectionToggles() {
        // v1.7.14: variant-aware visibility. Branch 0 (else) is the ORIGINAL logic
        // kept byte-for-byte — the default layout for all existing users never changes.
        // v1.7.17 (user: «когда я выбираю допустим страницу с часами там уже нельзя
        // и не накладываются закладки, недавние итд хотя тумблеры включены ты не
        // предусмотрел это!»): the section toggles now apply to EVERY native variant
        // (0/2/3) — clock and sites-grid homes honor the Быстрый доступ / Недавние /
        // Закладки switches exactly like the standard home (mirror of branch 0's
        // semantics). Variant 1 (minimal) keeps its clean search-only look BY DESIGN;
        // variant 4 is the user's own page (the embedded WebView covers everything).
        when (lastBuiltVariant) {
            1 -> {   // minimal: search only — nothing else, not even the private banner
                quickSection.visibility = View.GONE
                recentsSection.visibility = View.GONE
                bookmarksSection.visibility = View.GONE
                privateBanner.visibility = View.GONE
                searchPill.visibility = if (Prefs.homeSearchBar) View.VISIBLE else View.GONE
            }
            2 -> {   // clock: sections follow the toggles, same semantics as variant 0
                quickSection.visibility = if (Prefs.homeQuick) View.VISIBLE else View.GONE
                recentsSection.visibility = if (Prefs.homeRecents && !incognito) View.VISIBLE else View.GONE
                bookmarksSection.visibility = if (Prefs.homeBookmarks) View.VISIBLE else View.GONE
                privateBanner.visibility = if (incognito) View.VISIBLE else View.GONE
                searchPill.visibility = if (Prefs.homeSearchBar) View.VISIBLE else View.GONE
            }
            3 -> {   // sites grid: the LARGE grid IS the variant — always visible
                quickSection.visibility = View.VISIBLE
                recentsSection.visibility = if (Prefs.homeRecents && !incognito) View.VISIBLE else View.GONE
                bookmarksSection.visibility = if (Prefs.homeBookmarks) View.VISIBLE else View.GONE
                privateBanner.visibility = if (incognito) View.VISIBLE else View.GONE
                searchPill.visibility = if (Prefs.homeSearchBar) View.VISIBLE else View.GONE
            }
            4 -> {   // custom page: the embedded WebView covers everything
                scroll.visibility = View.GONE
                customWeb?.visibility = View.VISIBLE
            }
            else -> {   // 0 standard — EXACTLY as before
                quickSection.visibility = if (Prefs.homeQuick) View.VISIBLE else View.GONE
                recentsSection.visibility = if (Prefs.homeRecents && !incognito) View.VISIBLE else View.GONE
                bookmarksSection.visibility = if (Prefs.homeBookmarks) View.VISIBLE else View.GONE
                privateBanner.visibility = if (incognito) View.VISIBLE else View.GONE
                // v1.4.1: search bar can be fully hidden (Settings → Главный экран)
                searchPill.visibility = if (Prefs.homeSearchBar) View.VISIBLE else View.GONE
            }
        }
    }

    // ---------------- v1.7.14 home page variants ----------------

    /** Effective variant: «custom» with a BLANK target falls back to the standard layout. */
    private fun currentVariant(): Int {
        val v = Prefs.homeVariant.coerceIn(0, 4)
        return if (v == 4 && Prefs.homeCustomUrl.isBlank()) 0 else v
    }

    /**
     * v1.7.14: structural switch between the five home layouts. Runs on every reload
     * but only REBUILDS when the variant actually changed; the custom WebView is
     * created once and cached forever (no flicker between home shows).
     * v1.7.15 (user: «опция Главная страница … не открывает окно — жму, выбивает»):
     * fully armored — ANY device-specific failure rebuilds the STANDARD variant-0
     * layout and toasts the exception class name, so the home screen itself can
     * never crash or stay blank. Variants 0/1 render exactly as in v1.7.14.
     * v1.7.17: variants 2/3 now ALSO stack the recents & bookmarks sections below
     * the quick sites (toggle-driven visibility via applySectionToggles) — the
     * sections are single instances re-added after contentColumn.removeAllViews(),
     * so this can never double-add them.
     */
    private fun syncVariantStructure() {
        try {
            val variant = currentVariant()
            // staying in variant 4: keep the loaded target fresh (user may edit the path).
            // v1.7.15: a failed custom page already rebuilt variant 0 — stop right here.
            if (variant == 4 && !loadCustomHome()) return
            if (variant == lastBuiltVariant) {
                // revive the clock if a transient detach/reattach killed its ticker
                if (variant == 2 && clockTicker == null) startClockTicker()
                return
            }
            stopClockTicker()
            // detach everything, then re-attach only what the new variant shows
            contentColumn.removeAllViews()
            contentColumn.setPadding(dp(16), dp(if (variant == 1) 72 else 24), dp(16), dp(28))
            when (variant) {
                1 -> {   // minimal: wordmark + search only — clean and airy
                    contentColumn.addView(homeWordmark())
                    contentColumn.addView(searchPill)
                }
                2 -> {   // clock: live clock + date, search + all sections (toggle-driven)
                    contentColumn.addView(privateBanner)
                    contentColumn.addView(clockBlock())
                    contentColumn.addView(searchPill)
                    contentColumn.addView(quickSection)
                    // v1.7.17: feeds stack below the clock too — visibility comes
                    // from applySectionToggles() (Быстрый доступ / Недавние / Закладки)
                    contentColumn.addView(recentsSection)
                    contentColumn.addView(bookmarksSection)
                    startClockTicker()
                }
                3 -> {   // sites grid: search + LARGE grid of ALL quick sites + feeds
                    contentColumn.addView(privateBanner)
                    contentColumn.addView(searchPill)
                    contentColumn.addView(quickSection)
                    // v1.7.17: feeds below the big grid — toggle-driven, same as variant 0
                    contentColumn.addView(recentsSection)
                    contentColumn.addView(bookmarksSection)
                }
                4 -> {   // custom page: the embedded WebView replaces the native column
                    scroll.visibility = View.GONE
                    customWeb?.visibility = View.VISIBLE   // v1.7.15: self-consistent even after a fallback
                }
                else -> ensureVariant0()
            }
            if (variant != 4) {
                scroll.visibility = View.VISIBLE
                customWeb?.visibility = View.GONE   // cached, not destroyed
                removeCustomErrorOverlay()   // v1.7.15: stale error card must not cover the native home
            }
            lastBuiltVariant = variant
        } catch (t: Throwable) {
            // v1.7.15: no variant may EVER take the home screen down
            fallbackToVariant0(t)
        }
    }

    /** v1.7.15: variant-0 (standard) structure — shared by the switch and the crash fallback. */
    private fun ensureVariant0() {
        // 0 standard — the original structure, unchanged
        contentColumn.addView(privateBanner)
        contentColumn.addView(searchPill)
        contentColumn.addView(quickSection)
        contentColumn.addView(recentsSection)
        contentColumn.addView(bookmarksSection)
    }

    /**
     * v1.7.15 armor: a variant switch failed on this device → stop the clock, rebuild
     * the STANDARD variant-0 layout and toast «Главная страница: <Exception>» so a
     * user report pinpoints the cause. Every step is itself guarded — the fallback
     * can never become the thing that crashes the home screen.
     */
    private fun fallbackToVariant0(t: Throwable) {
        try {
            stopClockTicker()
            removeCustomErrorOverlay()
            contentColumn.removeAllViews()
            ensureVariant0()
            scroll.visibility = View.VISIBLE
            customWeb?.visibility = View.GONE   // cached, not destroyed
            lastBuiltVariant = 0
        } catch (_: Throwable) {}
        try {
            Toast.makeText(context, context.getString(R.string.home_page_variant) + ": " +
                t.javaClass.simpleName, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {}
    }

    /** v1.7.14 variant 1: Zephyr wordmark above the lone search pill. */
    private fun homeWordmark(): TextView = TextView(context).apply {
        text = context.getString(R.string.app_name)
        textSize = 34f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        letterSpacing = 0.02f
        gravity = Gravity.CENTER
        setTextColor(if (incognito) 0xFFEDE7FB.toInt() else onSurfaceColor())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) }
    }

    /** v1.7.14 variant 2: large live clock (HH:MM) + full local date, built ONCE and reused. */
    private fun clockBlock(): LinearLayout {
        clockBlock?.let { updateClock(); return it }
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        clockTime = TextView(context).apply {
            tag = "clock"   // excluded from the tintPrivate walk — recolored explicitly
            textSize = 60f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.01f
            gravity = Gravity.CENTER
            setTextColor(if (incognito) 0xFFEDE7FB.toInt() else onSurfaceColor())
        }
        clockDate = TextView(context).apply {
            tag = "clock"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (incognito) 0xFFB7A7DA.toInt() else onSurfaceVariantColor())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        block.addView(clockTime)
        block.addView(clockDate)
        clockBlock = block
        updateClock()
        return block
    }

    private fun updateClock() {
        val time = clockTime ?: return
        val now = java.util.Date()
        time.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
        clockDate?.text = java.text.DateFormat.getDateInstance(
            java.text.DateFormat.FULL, java.util.Locale.getDefault()).format(now)
    }

    private fun startClockTicker() {
        stopClockTicker()   // never stack tickers across variant switches / reattach
        clockTicker = android.os.Handler(android.os.Looper.getMainLooper())
        clockTick.run()
    }

    private fun stopClockTicker() {
        clockTicker?.removeCallbacks(clockTick)
        clockTicker = null
    }

    // ---------------- v1.7.14 variant 4: custom page (embedded WebView) ----------------

    /**
     * User-typed target → loadable URL: file:///http(s):// pass through unchanged,
     * "/storage/…" becomes a file:// URL, anything else is treated as a web host.
     */
    private fun normalizeCustomHome(input: String): String {
        val s = input.trim()
        return when {
            s.isEmpty() -> s
            s.startsWith("file://", true) || s.startsWith("http://", true) ||
                s.startsWith("https://", true) -> s
            s.startsWith("/") -> "file://" + s
            else -> "https://" + s
        }
    }

    /**
     * v1.7.15: lazily builds the embedded home WebView ONCE (never recreated → no
     * flicker) — armored: a device-specific construction failure (a suspect behind
     * «жму — выбивает» on the custom-page home) degrades to the variant-0 layout.
     * Returns false when that fallback already ran.
     */
    @Suppress("DEPRECATION")
    private fun ensureCustomWebView(): Boolean {
        if (customWeb != null) return true
        return try {
            customWeb = android.webkit.WebView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // the user's local page may load sibling assets/JS from the same folder
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                // SECURITY (audit A-002, fixed 1.8.1): allowUniversalAccess-
                // FromFileURLs used to be on here too, and it is a different
                // permission entirely. The line above is what lets a local page
                // read its own sibling files — the feature this is for. The
                // universal one additionally lets a file:// document make
                // cross-ORIGIN requests, which turns "read local files" into
                // "read local files and post them to any server".
                //
                // Nobody needs that to load a stylesheet. A user who points
                // their home page at an .html they downloaded from somewhere
                // was handing it the app's entire readable storage plus a way
                // out. Off, and the sibling-asset case still works.
                settings.mediaPlaybackRequiresUserGesture = false
                // NO Zephyr JS bridge here — this is plain user content, not a browser tab.
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(
                        view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?
                    ) {
                        // v1.7.15: any new navigation clears the previous load-error card
                        removeCustomErrorOverlay()
                    }
                    override fun shouldOverrideUrlLoading(
                        view: android.webkit.WebView, request: android.webkit.WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        return if (url.startsWith("http://") || url.startsWith("https://")) {
                            onNavigate(url)   // hand off to the normal browser tab flow
                            true
                        } else {
                            false   // file:// (and others) stay INSIDE the embedded page
                        }
                    }
                    // v1.7.15: MAIN-frame load failure → readable error card instead of a
                    // dead white screen (API 23+; subframe/iframe errors are ignored).
                    override fun onReceivedError(
                        view: android.webkit.WebView, request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            showCustomError(error.description?.toString() ?: "", request.url.toString())
                        }
                    }
                    // legacy callback (no WebResourceRequest on it): on M+ Chromium still
                    // fires it for main-frame errors — handled as main-frame, idempotent
                    // with the modern one above.
                    override fun onReceivedError(
                        view: android.webkit.WebView, errorCode: Int, description: String?, failingUrl: String?
                    ) {
                        showCustomError(description ?: "", failingUrl ?: "")
                    }
                    // v1.7.15 (optional hardening): main-frame HTTP 4xx/5xx → error card too
                    override fun onReceivedHttpError(
                        view: android.webkit.WebView, request: android.webkit.WebResourceRequest,
                        errorResponse: android.webkit.WebResourceResponse
                    ) {
                        if (request.isForMainFrame) {
                            showCustomError("HTTP " + errorResponse.statusCode, request.url.toString())
                        }
                    }
                }
            }
            addView(customWeb)   // topmost layer of this FrameLayout, above the scroll
            true
        } catch (t: Throwable) {
            customWeb = null   // a half-built view must not be cached as "ready"
            fallbackToVariant0(t)
            false
        }
    }

    /**
     * Loads the custom target only when it differs from the last loaded one.
     * v1.7.15: armored — returns false when the custom page failed on this device
     * (the variant-0 fallback has already been rebuilt by then).
     */
    private fun loadCustomHome(): Boolean {
        return try {
            val target = normalizeCustomHome(Prefs.homeCustomUrl)
            if (target.isEmpty()) return true   // blank target ⇒ currentVariant() says variant 0
            if (!ensureCustomWebView()) return false
            if (lastLoadedCustom != target) {
                lastLoadedCustom = target
                customWeb?.loadUrl(target)
            }
            true
        } catch (t: Throwable) {
            // the custom page must NEVER take the home screen down
            fallbackToVariant0(t)
            false
        }
    }

    /**
     * v1.7.15: small dark-readable card pinned to the top of the custom page when
     * its MAIN frame fails to load — error description, the URL that failed and a
     * «проверьте файл» line (home_custom_missing). Cleared by onPageStarted.
     */
    private fun showCustomError(desc: String, url: String) {
        try {
            removeCustomErrorOverlay()
            val card = TextView(context).apply {
                tag = "customError"   // fixed dark palette — excluded from the tintPrivate walk
                textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(0xFFF3F1F6.toInt())
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    // near-opaque dark card — readable over any home background/theme
                    setColor(0xEE1C1B22.toInt())
                    setStroke(dp(1), 0x40FFFFFF)
                }
                text = listOf(desc, url, context.getString(R.string.home_custom_missing))
                    .filter { it.isNotBlank() }.joinToString("\n")
            }
            customErrorOverlay = card
            addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP).apply {
                    topMargin = dp(64); marginStart = dp(24); marginEnd = dp(24)
                })
        } catch (_: Throwable) {}
    }

    /** v1.7.15: drops the load-error card (next navigation / variant switch / fallback). */
    private fun removeCustomErrorOverlay() {
        try {
            customErrorOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            customErrorOverlay = null
        } catch (_: Throwable) {}
    }

    // ---------------- quick access ----------------

    private fun rebuildQuick() {
        // v1.4.1: configurable grid — columns (4/5/6) × tile size (S/M/L)
        // v1.7.14 variant 3: LARGE grid of ALL quick sites — 3 columns phone /
        // 4 tablet, tiles sized to fill the row, 42dp icons + 13sp labels.
        val variant = currentVariant()
        val columns: Int
        val tileW: Int
        val iconDp: Int
        if (variant == 3) {
            val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            columns = if (widthDp < 420) 3 else 4
            tileW = (((widthDp - 32) / columns).toInt() - 10).coerceAtLeast(80)
            iconDp = 42
        } else {
            columns = Prefs.homeGridColumns.coerceIn(4, 6)
            val (w, i) = when (Prefs.homeTileSize) {
                0 -> 64 to 32
                2 -> 92 to 48
                else -> 76 to 40
            }
            tileW = w
            iconDp = i
        }
        quickSection.removeViews(1, (quickSection.childCount - 1).coerceAtLeast(0))
        quickRows.clear()
        fun newRow(): LinearLayout {
            val r = tilesRow()
            quickSection.addView(r)
            quickRows.add(r)
            return r
        }
        var row = newRow()
        var placed = 0
        val sites = quickSites
        // variant 3 draws bigger labels + generous tile padding (defaults = v1.4.1 look)
        val labelSp = if (variant == 3) 13f else 11f
        val padTop = if (variant == 3) 16 else 10
        val padBottom = if (variant == 3) 10 else 8
        for (site in sites) {
            if (placed > 0 && placed % columns == 0) row = newRow()
            row.addView(tile(site.title, site.url, tileW, iconDp, { editQuickDialog(site) }, { onNavigate(site.url) },
                labelSp, padTop, padBottom))
            placed++
        }
        if (placed % columns == 0 && placed > 0) row = newRow()
        row.addView(addTile(tileW, iconDp, R.string.home_add_site) { addQuickDialog() })
    }

    private fun addQuickDialog() {
        val name = EditText(context).apply { hint = context.getString(R.string.home_add_title) }
        val url = EditText(context).apply { hint = context.getString(R.string.home_add_url) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        box.addView(name)
        box.addView(url)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.home_add_site)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (t.isNotEmpty() && u.isNotEmpty()) {
                    io.launch {
                        try {
                            AppDb().quickSiteDao().insert(
                                com.zephyr.browser.db.QuickSiteEntity(title = t, url = if (u.startsWith("http")) u else "https://$u")
                            )
                        } catch (_: Exception) {}
                        withContext(kotlinx.coroutines.Dispatchers.Main) { reload() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editQuickDialog(site: com.zephyr.browser.db.QuickSiteEntity) {
        val name = EditText(context).apply { setText(site.title) }
        val url = EditText(context).apply { setText(site.url) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        box.addView(name)
        box.addView(url)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.bm_edit)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                io.launch {
                    try {
                        AppDb().quickSiteDao().delete(site)
                        AppDb().quickSiteDao().insert(
                            com.zephyr.browser.db.QuickSiteEntity(title = name.text.toString().trim().ifEmpty { site.title },
                                url = url.text.toString().trim().ifEmpty { site.url }, position = site.position)
                        )
                    } catch (_: Exception) {}
                    withContext(kotlinx.coroutines.Dispatchers.Main) { reload() }
                }
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                io.launch {
                    try { AppDb().quickSiteDao().delete(site) } catch (_: Exception) {}
                    withContext(kotlinx.coroutines.Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun AppDb(): com.zephyr.browser.db.AppDb = com.zephyr.browser.db.AppDb.get(context.applicationContext)

    // ---------------- recents & bookmarks ----------------

    private fun rebuildRecents() {
        // v1.7.9: feed mode — 0 = horizontal swipe row (unchanged), 1 = vertical wide-card grid
        stripSectionBody(recentsSection)
        val items = recents.filter { it.url != Urls.DEFAULT_HOME }.take(if (Prefs.homeFeedMode == 1) 12 else 14)
        if (Prefs.homeFeedMode == 1) {
            buildWideGrid(recentsSection, items.map { it.title.ifEmpty { Urls.domainOf(it.url) } to it.url })
            if (items.isNotEmpty()) recentsSection.addView(feedFooter(R.string.home_recent_more, "history"))
        } else {
            recentsRow.removeAllViews()
            for (h in items) recentsRow.addView(card(h.title.ifEmpty { Urls.domainOf(h.url) }, h.url))
            recentsSection.addView(recentsSwipe)
        }
        if (items.isEmpty()) recentsSection.visibility = View.GONE
    }

    private fun rebuildBookmarks() {
        // v1.7.9: same two feed modes as recents; the strip also clears any old "bmEmpty" text
        stripSectionBody(bookmarksSection)
        val items = bookmarks.take(if (Prefs.homeFeedMode == 1) 12 else 14)
        if (Prefs.homeFeedMode == 1) {
            buildWideGrid(bookmarksSection, items.map { it.title.ifEmpty { Urls.domainOf(it.url) } to it.url })
            if (items.isNotEmpty()) bookmarksSection.addView(feedFooter(R.string.panel_bookmarks, "bookmarks"))
        } else {
            bookmarksRow.removeAllViews()
            for (b in items) bookmarksRow.addView(card(b.title.ifEmpty { Urls.domainOf(b.url) }, b.url))
            bookmarksSection.addView(bookmarksSwipe)
        }
        if (items.isEmpty()) {
            bookmarksSection.addView(TextView(context).apply {
                text = context.getString(R.string.empty_list)
                textSize = 12f
                tag = "bmEmpty"
                setPadding(dp(4), dp(4), dp(4), dp(4))
            })
        }
    }

    /** v1.7.9: drops a section's body (everything below the header) before a rebuild. */
    private fun stripSectionBody(section: LinearLayout) {
        if (section.childCount > 1) section.removeViews(1, section.childCount - 1)
    }

    /**
     * v1.7.9 vertical feed: rows of N wide list-style cards stacked top→down
     * (3 columns below 420dp screen width, 4 above); cells share the row width
     * equally (weight 1), so the grid always fills the content column.
     */
    private fun buildWideGrid(section: LinearLayout, items: List<Pair<String, String>>) {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val columns = if (widthDp < 420) 3 else 4
        var row: LinearLayout? = null
        items.forEachIndexed { index, (title, url) ->
            if (index % columns == 0) {
                val r = tilesRow().apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }
                row = r
                section.addView(r)
            }
            row?.addView(wideCard(title, url), LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                marginEnd = if (index % columns == columns - 1 || index == items.lastIndex) 0 else dp(8)
            })
        }
    }

    /**
     * v1.7.9 vertical feed cell: wide rounded card (fills 1/N of a grid row) —
     * favicon in a 40dp soft rounded tile + 2-line title + domain (list-row look).
     */
    private fun wideCard(title: String, url: String): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = cardBackground(radius = 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { Anim.press(this); onNavigate(url) }
        }
        val tile = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (incognito) 0x666E5A96.toInt()
                    else onSurfaceVariantColor() and 0x00FFFFFF or 0x1A000000.toInt())
            }
        }
        val iv = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iv.setImageBitmap(FaviconLoader.load(context, url, title))
        tile.addView(iv)
        cell.addView(tile)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = title
            textSize = 12.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(onSurfaceColor())
            setLineSpacing(dp(1).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        texts.addView(TextView(context).apply {
            text = Urls.domainOf(url)
            textSize = 10.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(onSurfaceVariantColor())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        cell.addView(texts)
        return cell
    }

    /**
     * v1.7.9 vertical feed footer: soft pill that opens the FULL panel
     * (PanelsActivity hosts history & bookmarks) — same intent as MainActivity.openPanel.
     */
    private fun feedFooter(labelRes: Int, panel: String): View {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(if (incognito) 0x403B2B5E.toInt()
                    else accentColor() and 0x00FFFFFF or 0x14000000.toInt())
            }
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            setOnClickListener {
                Anim.press(this)
                try {
                    context.startActivity(Intent(context, com.zephyr.browser.panels.PanelsActivity::class.java).apply {
                        putExtra(com.zephyr.browser.panels.PanelsActivity.EXTRA_PANEL, panel)
                        putExtra(com.zephyr.browser.panels.PanelsActivity.EXTRA_INCOGNITO, incognito)
                    })
                    (context as? android.app.Activity)?.let { act ->
                        @Suppress("DEPRECATION")
                        act.overridePendingTransition(R.anim.panel_in, R.anim.hold)
                    }
                } catch (_: Exception) {}
            }
        }
        btn.addView(TextView(context).apply {
            tag = "accent"
            text = context.getString(labelRes)
            textSize = 12.5f
            maxLines = 1
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            setTextColor(if (incognito) 0xFFEDE7FB.toInt() else accentColor())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        btn.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) }
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (incognito) 0xFFEDE7FB.toInt() else accentColor())
        })
        return btn
    }

    // ---------------- tile / card views ----------------

    private fun faviconView(url: String, title: String): ImageView {
        val iv = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iv.setImageBitmap(FaviconLoader.load(context, url, title))
        return iv
    }

    /**
     * Small square tile with favicon + 2-line label (quick access; size configurable).
     * v1.7.14: labelSp / padTopDp / padBottomDp params let the sites-grid variant
     * draw bigger labels with generous padding — defaults keep the v1.4.1 look.
     */
    private fun tile(title: String, url: String, tileWDp: Int, iconDp: Int, onLong: () -> Unit, onTap: () -> Unit,
                     labelSp: Float = 11f, padTopDp: Int = 10, padBottomDp: Int = 8): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(padTopDp), dp(6), dp(padBottomDp))
            background = cardBackground(radius = 16)
            layoutParams = LinearLayout.LayoutParams(dp(tileWDp), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            setOnLongClickListener { onLong(); true }
        }
        val iconWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(iconDp), dp(iconDp))
        }
        iconWrap.addView(faviconView(url, title), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })
        col.addView(iconWrap)
        col.addView(TextView(context).apply {
            text = title
            textSize = labelSp
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        return col
    }

    private fun addTile(tileWDp: Int, iconDp: Int, textRes: Int, onClick: () -> Unit): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(8))
            background = cardBackground(radius = 16)
            layoutParams = LinearLayout.LayoutParams(dp(tileWDp), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        col.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_add)
            layoutParams = LinearLayout.LayoutParams(dp((iconDp * 0.6f).toInt()), dp((iconDp * 0.6f).toInt()))
        })
        col.addView(TextView(context).apply {
            text = context.getString(textRes)
            textSize = 10f
            maxLines = 2
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        return col
    }

    /**
     * Large card with favicon + title + host (recents/bookmarks, Chrome-like thumbnails).
     * v1.5.1 FIX: FIXED geometry — every card is exactly the same width AND height
     * (1-line-clamped preview strip + 2-line title block + 1-line domain), so a
     * "YouTube" tile can never end up smaller/taller than a "Wikipedia" one.
     */
    private fun card(title: String, url: String): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = cardBackground(radius = 18)
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(132)).apply {
                marginEnd = dp(12)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { Anim.press(this); onNavigate(url) }   // v1.7.9: gentle press feedback
        }
        // fixed-height preview strip with the centered favicon (a mini "screenshot" look)
        val preview = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(onSurfaceVariantColor() and 0x00FFFFFF or 0x14000000.toInt())
            }
            clipToOutline = true
        }
        val iv = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iv.setImageBitmap(FaviconLoader.load(context, url, title))
        preview.addView(iv)
        col.addView(preview)
        col.addView(TextView(context).apply {
            text = title
            textSize = 12f
            maxLines = 2
            minLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(onSurfaceColor())
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
        })
        col.addView(TextView(context).apply {
            text = Urls.domainOf(url)
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(onSurfaceVariantColor())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        return col
    }

    // ---------------- styling helpers ----------------

    private fun tintPrivate() {
        val (fg, sub) = if (incognito) 0xFFB7A7DA.toInt() to 0xFFEDE7FB.toInt()
        else onSurfaceVariantColor() to onSurfaceColor()
        fun walk(v: View) {
            // v1.7.9: keep the section accent bar in sync with the mode palette
            if (v.tag == "sectionDot") {
                (v.background as? GradientDrawable)?.setColor(
                    if (incognito) 0xFFB7A7DA.toInt() else accentColor())
            }
            if (v is TextView && v !== searchInput && v.tag != "accent" && v.tag != "clock" && v.tag != "customError") {
                if (v.parent === bookmarksSection && v.tag == "bmEmpty") v.setTextColor(if (incognito) 0xFFB7A7DA.toInt() else sub)
                else if (v.textSize < 13f) v.setTextColor(if (incognito) 0xFFB7A7DA.toInt() else sub)
                else v.setTextColor(if (incognito) 0xFFEDE7FB.toInt() else fg)
            }
            if (v is ImageButton) {
                v.imageTintList = android.content.res.ColorStateList.valueOf(if (incognito) 0xFFB7A7DA.toInt() else sub)
            }
            if (v is ImageView && v.parent is LinearLayout && v.parent !== searchInput.parent) {
                // tint the small add icon; favicons stay un-tinted (colored originals)
                if (v.drawable?.constantState == context.getDrawable(R.drawable.ic_add)?.constantState) {
                    v.imageTintList = android.content.res.ColorStateList.valueOf(if (incognito) 0xFFB7A7DA.toInt() else sub)
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        // v1.7.14: the clock block follows the mode palette (time strong, date subtle)
        clockTime?.setTextColor(if (incognito) 0xFFEDE7FB.toInt() else onSurfaceColor())
        clockDate?.setTextColor(if (incognito) 0xFFB7A7DA.toInt() else onSurfaceVariantColor())
        searchInput.setTextColor(if (incognito) 0xFFEDE7FB.toInt() else fg)
        searchInput.setHintTextColor(if (incognito) 0x88B7A7DA.toInt() else sub)
        (engineLogo.parent as? LinearLayout)?.background = pillBackground()
    }

    private fun pillBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
        if (incognito) {
            setColor(0xFF3B2B5E.toInt())
            setStroke(dp(1), 0xFF6E5A96.toInt())
        } else {
            // v1.4.2 FIX 8: same rule as the tiles — themed surface at high alpha (90%),
            // never hardcoded white
            setColor(if (Prefs.homeBgColor != 0 || Prefs.wallpaperUri.isNotEmpty())
                surfaceColor() and 0x00FFFFFF or 0xE6000000.toInt() else surfaceVariantColor())
            setStroke(dp(1), outlineColor() and 0x00FFFFFF or 0x33000000)
        }
    }

    private fun cardBackground(radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        if (incognito) {
            setColor(0x803B2B5E.toInt())
            setStroke(dp(1), 0x406E5A96)
        } else {
            if (Prefs.homeBgColor != 0 || Prefs.wallpaperUri.isNotEmpty()) {
                // v1.4.2 FIX 8: NEVER hardcode white — theme surface at ~78% alpha works on
                // light AND dark photos and follows all 12 themes; soft outline for edges.
                setColor(surfaceColor() and 0x00FFFFFF or 0xC7000000.toInt())
                setStroke(dp(1), outlineColor() and 0x00FFFFFF or 0x59000000)
            } else {
                setColor(surfaceVariantColor())
            }
        }
    }

    private fun circularRipple(): android.graphics.drawable.Drawable {
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33000000) }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x331A73E8), null, mask)
    }

    /** v1.7.9: theme accent (section markers, feed footer) — matches the omnibox ripple blue. */
    private fun accentColor(): Int = themeColor(R.attr.colorPrimary, 0xFF1A73E8.toInt())

    private fun surfaceColor(): Int = themeColor(android.R.attr.windowBackground, if (incognito) 0xFF241A3D.toInt() else 0xFFFFFFFF.toInt())
    private fun surfaceVariantColor(): Int = themeColor(R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt())
    private fun onSurfaceColor(): Int = themeColor(R.attr.colorOnSurface, 0xFF202124.toInt())
    private fun onSurfaceVariantColor(): Int = themeColor(R.attr.colorOnSurfaceVariant, 0xFF5F6368.toInt())
    private fun outlineColor(): Int = themeColor(com.google.android.material.R.attr.colorOutline, 0xFF747775.toInt())

    private fun themeColor(attr: Int, fallback: Int): Int = try {
        val ta = context.theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, fallback)
        ta.recycle()
        c
    } catch (_: Exception) { fallback }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
