package com.zephyr.browser.files

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.DownloadEngine
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.db.DownloadEntry
import com.zephyr.browser.db.TrashEntry
import com.zephyr.browser.settings.SettingsUi
import com.zephyr.browser.player.PlayerActivity
import com.zephyr.browser.ui.MenuSheets
import com.zephyr.browser.viewer.PhotoViewerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * FILES v3 (v1.7.9 full rework — user: «превью грузится очень медленно»,
 * «при нажатии на APK ничего не происходит — для ЛЮБОГО файла нужна системная
 * панель "открыть с помощью" с выбором "только сейчас" / "всегда"», «нижняя
 * панель с плюсом съедает экран», «добавь масштаб — чтобы влезало больше
 * папок», «в медиа дай выбирать, каким приложением открывать фото/видео»,
 * «в Памяти не пускай выше хранилища — назад ходить неудобно» + корзина
 * 30 дней, поиск по всему хранилищу, планировщик загрузок).
 *
 * What changed vs v1.7.8:
 *  - THUMBNAILS: 24MB KB-based LruCache + separate executors (3 threads for
 *    images, 1 for video/MediaMetadataRetriever), 140px decodes, native
 *    loadThumbnail on API 29+, and the bind race FIXED (tag set BEFORE the
 *    decode launch and checked on the cache-hit path too — no more stale
 *    bitmaps popping into recycled rows);
 *  - OPEN-WITH SHEET for ANY file (storage / media / finished downloads):
 *    premium bottom sheet with the app list (explicit = open once), a
 *    «Выбрать приложение (один раз / всегда)» row that fires the plain
 *    implicit intent so the SYSTEM resolver offers Just once / Always —
 *    exactly like the Downloads section; APKs finally open;
 *  - the bottom + panel is GONE: FAB now overlays the list (bottom|end),
 *    visible on «Загрузки» (note) and «Память» tabs only;
 *  - ZOOM: header button (Крупно…Мелко) + PINCH on the list; grid span
 *    2→5 columns, list rows scale too, persisted via Prefs.filesZoom;
 *  - TRASH: deletes move to app-private trash (30 days), with a restore /
 *    purge sheet (Корзина button in the header); MediaStore items are copied
 *    out before the content delete and re-scanned on restore;
 *  - GLOBAL STORAGE SEARCH: switch under the search box walks the whole
 *    storage (skips Android/, hidden dirs, depth 8, 4000 hits, 500 shown)
 *    with MediaStore Downloads/Files as fallback;
 *  - navigation is CLAMPED at the storage root — breadcrumbs never show
 *    filesystem ancestors, system BACK climbs folders (dispatcher callback);
 *  - download scheduler card (Wi-Fi only / night only) in the manager head;
 *    waiting rows surface the engine's live reason («Ожидание Wi-Fi…»);
 *  - v1.7.14 TAB REORDER («в Файлах дай возможность расставлять вкладки
 *    по своему желанию»): the three tabs follow the USER'S saved order
 *    (Prefs.filesTabOrderList) — LONG-PRESS any tab opens a live-apply
 *    reorder dialog (↑/↓ per row, reset to default). Each segment carries
 *    its TAB constant in `tag`, so selection styles by constant, not by
 *    visual position (those diverge once reordered).
 */
class FilesActivity : AppCompatActivity() {

    companion object {
        const val TAB_DOWNLOADS = 0
        const val TAB_MEDIA = 1
        const val TAB_STORAGE = 2
        /** v1.7.49: open Files at this absolute path (file or folder). */
        const val EXTRA_PATH = "zephyr.files.path"
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "ts", "avi", "mov", "m4v")
        private val AUDIO_EXT = setOf("mp3", "flac", "ogg", "wav", "m4a", "opus", "aac", "amr")
        private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz")
        private const val TRASH_KEEP_MS = 30L * 24 * 3600 * 1000
    }

    /**
     * v1.7.38 FIX (user: «выставляю первой вкладкой Память, а Файлы каждый раз
     * открываются на Загрузках»): the opening tab was hard-wired to downloads,
     * so reordering the tabs changed only the row of buttons, never what you
     * actually landed on. It now follows the user's own order.
     */
    private var tab = Prefs.filesTabOrderList().firstOrNull() ?: TAB_DOWNLOADS
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: FilesAdapter
    private lateinit var crumbs: LinearLayout
    private lateinit var searchBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchAllRow: LinearLayout
    private lateinit var searchAllSwitch: Switch
    private lateinit var gridBtn: ImageButton
    private lateinit var sortBtn: ImageButton
    private lateinit var zoomBtn: ImageButton
    private lateinit var trashBtn: ImageButton
    private lateinit var fab: ImageButton
    private lateinit var tabsRow: LinearLayout
    private lateinit var tabButtons: MutableList<TextView>
    private lateinit var chipsRow: HorizontalScrollView
    private var query = ""
    private var searchAll = false
    private var loadGen = 0
    private var currentDir: File? = null

    /**
     * v1.7.49 (user: «пусть путь в ответе ИИ будет кликабельным и открывает
     * Файлы прямо на этом файле, а не заставляет искать»): the name of the
     * entry to highlight once the listing is built, and the row it landed on.
     */
    private var jumpHighlight: String? = null
    private val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private val main = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob())
    private val singleIo = Dispatchers.IO.limitedParallelism(1)

    /** v1.7.9: images decode fast and often → dedicated pool; video frames via
     *  MediaMetadataRetriever are heavy → 1 worker so scrolling through a camera
     *  roll never queues behind a slow mkv probe.
     *  v1.7.16 (user: «миниатюры появляются только после скролла вверх-вниз»):
     *  RecyclerView's fling PREFETCH floods a plain FIFO pool with off-screen
     *  binds — the visible cells' decodes sat behind dozens of stale ones. Both
     *  pools are now NEWEST-FIRST (LIFO by submit order): whatever is currently
     *  on screen (or freshly re-bound) jumps the queue; stale prefetch decodes
     *  sink to the tail and get replaced by fresh work. */
    private class Seq(val seq: Long, val run: Runnable) : Runnable, Comparable<Seq> {
        override fun run() { try { run.run() } catch (_: Throwable) {} }
        override fun compareTo(other: Seq): Int = other.seq.compareTo(seq)   // newest first
    }
    private val seqGen = java.util.concurrent.atomic.AtomicLong()
    private fun lifo(n: Int): ExecutorService {
        val ex = java.util.concurrent.ThreadPoolExecutor(
            n, n, 5L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.PriorityBlockingQueue<Runnable>())
        ex.allowCoreThreadTimeOut(true)
        return ex
    }
    private val imgExecutor: ExecutorService = lifo(4)
    private val vidExecutor: ExecutorService = lifo(1)

    /** KB-based cache (~24MB of bitmaps) — the old count-based 96-entry cache
     *  thrashed instantly on 1080p camera thumbs. */
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var dmPoll: Runnable? = null
    private var dmDirty = true

    /** All renderable rows. */
    private sealed class Item {
        class Dm(val entry: DownloadEntry, val live: DownloadEngine.Live?) : Item()
        class Media(val uri: String, val id: Long, val name: String, val size: Long,
                    val mime: String, val added: Long, val isImage: Boolean, val isVideo: Boolean,
                    val path: String) : Item()
        /** relPath != null → global-search hit, subtitle shows the parent path. */
        class Fs(val file: File, val relPath: String? = null) : Item()
        class Msg(val text: String, val icon: Int) : Item()
        class Card(val title: String, val body: String, val button: String, val action: () -> Unit) : Item()
        class Hint(val key: String, val title: String, val body: String) : Item()
        /** v1.7.9: premium dark-gradient hint banner (matches PanelUi.hintBanner) —
         *  rendered as a full-span row with a frosted icon chip + close on tap. */
        class PremiumHint(val key: String, val iconRes: Int, val title: String, val body: String) : Item()
        /** Download scheduler card (Wi-Fi / night toggles). */
        class Sched : Item()
    }

    private val items = mutableListOf<Item>()

    private val searchPost = Runnable { loadAsync() }

    // ------------------------------------------------------------------ setup

    /**
     * v1.7.49: open Files at a path handed over from elsewhere.
     *
     * The AI answers with an absolute path — `/storage/emulated/0/Xlite` — and
     * tapping it should land you there, not at the root with a search to do.
     * A directory opens as the folder; a file opens its parent folder with the
     * file marked, which is what "показать этот файл" actually means.
     */
    private fun applyJumpTarget(i: android.content.Intent?) {
        val raw = i?.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        if (raw.isEmpty()) return
        try {
            val f = File(raw)
            if (!f.exists()) {
                Toast.makeText(this, getString(R.string.files_jump_missing, raw), Toast.LENGTH_LONG).show()
                return
            }
            tab = TAB_STORAGE
            if (f.isDirectory) {
                currentDir = f
                jumpHighlight = null
            } else {
                currentDir = f.parentFile
                jumpHighlight = f.name
            }
        } catch (_: Throwable) {}
    }

    override fun onNewIntent(i: android.content.Intent?) {
        super.onNewIntent(i)
        if (i?.hasExtra(EXTRA_PATH) == true) {
            applyJumpTarget(i)
            try { reloadNow() } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // v1.7.12: launch probe — MainActivity checks this file to detect a launch
        // that died BEFORE any of our code could run (class load / super.onCreate).
        try { java.io.File(filesDir, "files_launch_probe.txt").delete() } catch (_: Throwable) {}
        // v1.7.11: even the pre-super theme/Prefs setup is armored — nothing in
        // this activity's startup may reach the process handler uncaught.
        try {
            Prefs.init(this)
            // v1.7.8: the night-mode schedule automatically darkens Files too
            setTheme(if (Prefs.nightSchedule && Prefs.isNightWindowNow() && Prefs.themeId != 2)
                R.style.Theme_Zephyr_Dark else Prefs.themeStyle())
        } catch (_: Throwable) {
            try { setTheme(R.style.Theme_Zephyr) } catch (_: Throwable) {}
        }
        // v1.7.12 («файлы не открываются» — третий заход): super.onCreate itself can
        // throw on this device (theme/delegate processing) — armor it; a throw here
        // still gets the readable fallback screen instead of a dead process.
        var superOk = true
        try {
            super.onCreate(savedInstanceState)
        } catch (t: Throwable) {
            superOk = false
            writeCrashFile(t, "super.onCreate")
            fallbackErrorUi(t)
        }
        if (!superOk) return
        // v1.7.10 hardening («файлы не открываются» report): ANY failure in the
        // startup path (UI build, back dispatcher, first load) degrades to a
        // dead-simple fallback screen instead of bouncing back or killing the app.
        try {
            setContentView(buildUi())
            // system BACK climbs folders / closes search before exiting
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { onUp() }
            })
            purgeTrashAsync()
            // v1.7.49: opened from an AI answer — jump straight to the folder
            // and light up the file the assistant named
            applyJumpTarget(intent)
            loadAsync()
            // v1.7.14: one-time discoverability hint — long-press reorders tabs
            if (!Prefs.isHintDismissed("files_tab_order_hint")) {
                Prefs.dismissHint("files_tab_order_hint")
                Toast.makeText(this, R.string.files_tab_order_hint, Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            fallbackErrorUi(t)
        }
    }

    override fun onDestroy() {
        dmPoll?.let { main.removeCallbacks(it) }
        main.removeCallbacks(searchPost)
        imgExecutor.shutdownNow()
        vidExecutor.shutdownNow()
        super.onDestroy()
    }

    /** v1.7.10: dumps a caught-but-fatal exception to filesDir/last_crash.txt in
     *  the SAME format as the App-level uncaught-exception logger (time /
     *  version / thread + full stack), so the user can share the exact cause.
     *  v1.7.11: `where` marks the exact spot the failure was caught, so a shared
     *  report pinpoints the culprit even when the screen recovered. */
    private fun writeCrashFile(t: Throwable, where: String = "") {
        try {
            val file = File(filesDir, "last_crash.txt")
            file.writeText(buildString {
                append("time: ").append(System.currentTimeMillis())
                append("\nversion: 1.7.12 (26)\nthread: ").append(Thread.currentThread().name)
                if (where.isNotEmpty()) append("\ncaught at: ").append(where)
                append("\n\n")
                append(android.util.Log.getStackTraceString(t))
            })
        } catch (_: Throwable) {}
    }

    /** v1.7.11: shares filesDir/last_crash.txt — the one-tap diagnostic channel
     *  (also used by Настройки → О приложении). Empty → polite toast. */
    fun shareCrashReport() {
        try {
            val f = File(filesDir, "last_crash.txt")
            if (!f.exists() || f.length() == 0L) {
                Toast.makeText(this, R.string.crash_report_none, Toast.LENGTH_SHORT).show()
                return
            }
            val text = try { f.readText().take(60_000) } catch (_: Exception) { "" }
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Zephyr 1.7.12 crash report")
                putExtra(Intent.EXTRA_TEXT, text)
            })
        } catch (_: Exception) {
            Toast.makeText(this, R.string.crash_report_none, Toast.LENGTH_SHORT).show()
        }
    }

    /** v1.7.10: last-resort error screen. Plain views ONLY (no RecyclerView, no
     *  DB, no Prefs reads, no themed drawables) so it cannot itself crash; the
     *  background comes from SettingsUi.color with a hard-coded dark fallback.
     *  Shows what happened + "OK" to exit the screen normally. */
    private fun fallbackErrorUi(t: Throwable) {
        writeCrashFile(t)
        try { Toast.makeText(this, R.string.crash_reported, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        try {
            val bg = try {
                SettingsUi.color(this, com.google.android.material.R.attr.colorSurface)
            } catch (_: Throwable) { 0xFF10141D.toInt() }
            val fg = 0xFFF2F4F8.toInt()
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(bg)
                setPadding(dp(24), dp(56), dp(24), dp(24))
            }
            root.addView(TextView(this).apply {
                text = getString(R.string.files_error_title)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(fg)
            })
            root.addView(TextView(this).apply {
                text = getString(R.string.files_error_body)
                textSize = 13f
                setTextColor(fg)
                setPadding(0, dp(12), 0, 0)
            })
            root.addView(TextView(this).apply {
                text = t.javaClass.name + ": " + (t.message ?: "")
                typeface = Typeface.MONOSPACE
                textSize = 11f
                alpha = 0.6f
                setTextColor(fg)
                setPadding(0, dp(12), 0, 0)
            })
            root.addView(TextView(this).apply {
                text = "OK"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF3B82F6.toInt())
                setPadding(dp(28), dp(12), dp(28), dp(12))
                isClickable = true
                setOnClickListener { finishWithAnim() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(28)
                }
            })
            // v1.7.11: one-tap crash report — sends the exact stack trace
            // (last_crash.txt) to the developer via any messenger/mail app.
            root.addView(TextView(this).apply {
                text = getString(R.string.crash_share_report)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFFB7C0D4.toInt())
                isClickable = true
                setOnClickListener { shareCrashReport() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                }
            })
            setContentView(root)
        } catch (_: Throwable) {
            // even the fallback failed — exit quietly rather than crash-loop
            try { finish() } catch (_: Throwable) {}
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SettingsUi.color(this@FilesActivity, com.google.android.material.R.attr.colorSurface))
        }

        fun hBtn(iconRes: Int, desc: String, click: (ImageButton) -> Unit): ImageButton =
            ImageButton(this@FilesActivity).apply {
                setImageResource(iconRes)
                background = MenuSheets.ripple(this@FilesActivity)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                imageTintList = csl(tint(R.attr.colorOnSurface))
                contentDescription = desc
                setOnClickListener { click(this) }
            }

        // ---- header: back + title + trash + search + sort + grid + zoom ----
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            addView(hBtn(R.drawable.ic_back, getString(R.string.pl_back)) { onUp() })
            addView(TextView(this@FilesActivity).apply {
                text = getString(R.string.files_title)
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(csl(tint(R.attr.colorOnSurface)))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2) }
            })
            // v1.7.9: trash (storage/media tabs only)
            trashBtn = hBtn(R.drawable.ic_delete, getString(R.string.files_trash)) { showTrashSheet() }
            addView(trashBtn)
            addView(hBtn(R.drawable.ic_search, getString(R.string.menu_find)) {
                if (searchBox.visibility == View.VISIBLE) {
                    searchBox.visibility = View.GONE
                    searchAllRow.visibility = View.GONE
                    searchInput.setText("")
                    query = ""
                    reloadNow()
                } else {
                    searchBox.visibility = View.VISIBLE
                    updateSearchAllRow()
                    searchInput.requestFocus()
                }
            })
            sortBtn = hBtn(R.drawable.ic_sort, getString(R.string.files_sort)) { showSortMenu() }
            addView(sortBtn)
            gridBtn = hBtn(
                if (Prefs.filesGrid) R.drawable.ic_list else R.drawable.ic_grid,
                getString(R.string.files_view_toggle)) {
                Prefs.setFilesGrid(!Prefs.filesGrid)
                gridBtn.setImageResource(if (Prefs.filesGrid) R.drawable.ic_list else R.drawable.ic_grid)
                applyLayoutMode()
            }
            addView(gridBtn)
            // v1.7.9: zoom / density
            zoomBtn = hBtn(R.drawable.ic_fullscreen, getString(R.string.files_zoom)) { showZoomMenu() }
            addView(zoomBtn)
        })

        // ---- search row ----
        searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(4), dp(16), dp(8))
            addView(EditText(this@FilesActivity).apply {
                hint = getString(R.string.files_search_hint)
                setSingleLine(true)
                textSize = 14.5f
                background = SettingsUi.rounded(this@FilesActivity, dp(24))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        query = s?.toString()?.trim() ?: ""
                        // debounce: a full-storage walk must not launch per keystroke
                        main.removeCallbacks(searchPost)
                        main.postDelayed(searchPost, 260)
                    }
                })
            }.also { searchInput = it })
        }
        root.addView(searchBox)

        // ---- v1.7.9: "search whole storage" toggle (storage tab only) ----
        searchAllRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(2), dp(16), dp(8))
            visibility = View.GONE
            isClickable = true
            addView(TextView(this@FilesActivity).apply {
                text = getString(R.string.files_search_all)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(csl(tint(R.attr.colorPrimary)))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            searchAllSwitch = Switch(this@FilesActivity).apply {
                isChecked = searchAll
                isClickable = false
                isFocusable = false
            }
            addView(searchAllSwitch)
            setOnClickListener {
                searchAll = !searchAll
                searchAllSwitch.isChecked = searchAll
                loadAsync()
            }
        }
        root.addView(searchAllRow)

        // ---- tabs (v1.7.14: user-defined order — segments are built by
        //      rebuildTabRow() from Prefs.filesTabOrderList()) ----
        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(8))
        }
        rebuildTabRow()
        root.addView(tabsRow)

        // ---- quick location chips (storage tab) ----
        chipsRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            val row = LinearLayout(this@FilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(0), dp(16), dp(6))
            }
            fun chip(label: String, dir: String) {
                row.addView(TextView(this@FilesActivity).apply {
                    text = label
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    background = SettingsUi.rounded(this@FilesActivity, dp(18))
                    setTextColor(csl(tint(R.attr.colorPrimary)))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(8)
                    }
                    setOnClickListener {
                        val base = File(Environment.getExternalStorageDirectory(), dir)
                        if (base.exists()) { currentDir = base; reloadNow() }
                        else Toast.makeText(context, R.string.files_folder_missing, Toast.LENGTH_SHORT).show()
                    }
                })
            }
            chip("Download", "Download")
            chip("DCIM", "DCIM")
            chip(getString(R.string.files_pictures), "Pictures")
            chip(getString(R.string.files_movies), "Movies")
            chip(getString(R.string.files_music), "Music")
            addView(row)
        }
        root.addView(chipsRow)

        // ---- breadcrumbs ----
        crumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(2), dp(16), dp(4))
            visibility = View.GONE
        }
        val crumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbs)
        }
        root.addView(crumbScroll)

        // ---- v1.7.9: list + overlay FAB (the old bottom + panel stole
        //      vertical space below the RecyclerView — removed) ----
        val listWrap = FrameLayout(this)
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@FilesActivity, spanCount())
            setPadding(dp(10), dp(4), dp(10), dp(88))
            clipToPadding = false
            // pinch-to-zoom: consume touches ONLY while a scale gesture is in
            // progress, so normal scrolling never breaks
            setOnTouchListener { _, e ->
                if (tab == TAB_STORAGE || tab == TAB_MEDIA) {
                    // v1.7.13: armor — if an OEM gesture-detector impl ever
                    // throws, pinch zoom silently disables instead of the
                    // exception killing the whole process mid-touch.
                    try {
                        scaleDetector.onTouchEvent(e)
                        when (e.actionMasked) {
                            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> pinchAccum = 1f
                        }
                        if (scaleDetector.isInProgress) return@setOnTouchListener true
                    } catch (_: Throwable) { pinchAccum = 1f }
                }
                false
            }
        }
        adapter = FilesAdapter()
        recycler.adapter = adapter
        listWrap.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fab = ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            imageTintList = csl(0xFFFFFFFF.toInt())
            elevation = dp(8).toFloat()
            background = SettingsUi.circleFill(this@FilesActivity)
            contentDescription = getString(R.string.files_create)
            setOnClickListener { showCreateSheet() }
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(20)
                bottomMargin = dp(20)
            }
        }
        listWrap.addView(fab)
        root.addView(listWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    /** v1.7.14: builds the tab segments in the USER'S saved order
     *  (Prefs.filesTabOrderList(), e.g. [2,0,1] → «Память» first). `tab`
     *  keeps holding TAB_* CONSTANTS — only the visual position changes, so
     *  tab clicks, FAB/trash visibility and every loader are untouched.
     *  Each segment carries its TAB constant in `tag` so styleTabs()
     *  selects by constant, not by visual index (those diverge after a
     *  reorder). Called from buildUi and after every order change. */
    private fun rebuildTabRow() {
        // v1.7.10 hardening: a tab-row rebuild failure must never kill the screen
        try {
            tabsRow.removeAllViews()
            tabButtons = mutableListOf()
            fun segBtn(label: String, idx: Int): TextView = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(9), dp(4), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    // margin by VISUAL slot (first drawn segment has none) —
                    // the old `idx > 0` check broke once order ≠ [0,1,2]
                    marginStart = if (tabButtons.isNotEmpty()) dp(6) else 0
                }
                tag = idx
                setOnClickListener { tab = idx; if (idx != TAB_STORAGE) currentDir = null; reloadNow() }
                // v1.7.14: long-press any tab → live-apply reorder dialog
                setOnLongClickListener { showTabOrderDialog(); true }
            }.also { tabButtons.add(it) }
            val names = mapOf(
                TAB_DOWNLOADS to getString(R.string.files_tab_downloads),
                TAB_MEDIA to getString(R.string.files_tab_media),
                TAB_STORAGE to getString(R.string.files_tab_storage))
            for (t in Prefs.filesTabOrderList()) tabsRow.addView(segBtn(names[t] ?: "", t))
        } catch (_: Throwable) {}
        styleTabs()
    }

    /** v1.7.14: tab reorder dialog. Every ↑/↓ tap applies INSTANTLY —
     *  Prefs.setFilesTabOrder + rebuildTabRow behind the dialog — so no OK
     *  button is needed: Cancel just closes (changes are already live),
     *  Reset restores [0,1,2] (also applied immediately). */
    private fun showTabOrderDialog() {
        safeSheet("showTabOrderDialog") {
        val names = mapOf(
            TAB_DOWNLOADS to getString(R.string.files_tab_downloads),
            TAB_MEDIA to getString(R.string.files_tab_media),
            TAB_STORAGE to getString(R.string.files_tab_storage))
        var order = Prefs.filesTabOrderList().toMutableList()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        fun applyLive() {
            Prefs.setFilesTabOrder(order)
            rebuildTabRow()
        }
        fun render() {
            body.removeAllViews()
            for ((i, t) in order.withIndex()) {
                body.addView(LinearLayout(this@FilesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                    addView(TextView(this@FilesActivity).apply {
                        text = names[t] ?: ""
                        textSize = 15f
                        maxLines = 1
                        setTextColor(csl(tint(R.attr.colorOnSurface)))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    fun moveBtn(iconRes: Int, descRes: Int, delta: Int) {
                        addView(ImageButton(this@FilesActivity).apply {
                            setImageResource(iconRes)
                            background = MenuSheets.ripple(this@FilesActivity)
                            setPadding(dp(10), dp(10), dp(10), dp(10))
                            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                            imageTintList = csl(tint(R.attr.colorPrimary))
                            contentDescription = getString(descRes)
                            // ↑ disabled on the first row, ↓ on the last
                            isEnabled = i + delta in order.indices
                            alpha = if (isEnabled) 1f else 0.35f
                            setOnClickListener {
                                val j = i + delta
                                if (j in order.indices) {
                                    order[j] = order[i].also { order[i] = order[j] }
                                    applyLive(); render()
                                }
                            }
                        })
                    }
                    moveBtn(R.drawable.ic_arrow_up, R.string.files_tab_move_up, -1)
                    moveBtn(R.drawable.ic_arrow_down, R.string.files_tab_move_down, +1)
                })
            }
        }
        render()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_tab_order)
            .setView(body)
            // live-apply: Reset lands immediately too, then the dialog closes
            .setNeutralButton(R.string.files_tab_order_reset) { _, _ ->
                order = mutableListOf(TAB_DOWNLOADS, TAB_MEDIA, TAB_STORAGE)
                applyLive()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        }
    }

    private fun updateSearchAllRow() {
        searchAllRow.visibility =
            if (tab == TAB_STORAGE && searchBox.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        searchAllSwitch.isChecked = searchAll
    }

    // ------------------------------------------------------------------ zoom

    private fun spanCount(): Int = if (Prefs.filesGrid) 2 + Prefs.filesZoom.coerceIn(0, 3) else 1
    private fun gridThumbDp(): Int = when (Prefs.filesZoom) { 0 -> 150; 1 -> 120; 2 -> 100; else -> 84 }
    private fun listThumbDp(): Int = when (Prefs.filesZoom) { 0 -> 52; 1 -> 48; 2 -> 44; else -> 40 }
    private fun titleSp(grid: Boolean): Float =
        if (grid) when (Prefs.filesZoom) { 0 -> 12.5f; 1 -> 12f; 2 -> 11.5f; else -> 11f }
        else when (Prefs.filesZoom) { 0 -> 15.5f; 1 -> 14.5f; 2 -> 13.5f; else -> 13f }
    private fun subSp(grid: Boolean): Float =
        if (grid) when (Prefs.filesZoom) { 0 -> 11f; 1 -> 10.5f; 2 -> 10.5f; else -> 10f }
        else when (Prefs.filesZoom) { 0 -> 12.5f; 1 -> 12f; 2 -> 11.5f; else -> 11f }

    private fun applyLayoutMode() {
        // v1.7.10 hardening: a layout-mode failure must never kill the screen
        try {
            val glm = recycler.layoutManager as? GridLayoutManager ?: return
            glm.spanCount = spanCount()
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val it = items.getOrNull(position) ?: return 1
                    return if (it is Item.Dm || it is Item.Card || it is Item.Hint || it is Item.Msg ||
                        it is Item.Sched || it is Item.PremiumHint) glm.spanCount else 1
                }
            }
            adapter.notifyDataSetChanged()
            consumeJumpHighlight()
        } catch (_: Throwable) {}
    }

    /**
     * v1.7.49: after a jump from an AI answer, scroll the named file into view
     * and flash it, so the eye lands on it instead of scanning the folder.
     */
    private fun consumeJumpHighlight() {
        val name = jumpHighlight ?: return
        jumpHighlight = null
        recycler.post {
            try {
                val pos = items.indexOfFirst { it is Item.Fs && it.file.name == name }
                if (pos < 0) return@post
                (recycler.layoutManager as? GridLayoutManager)
                    ?.scrollToPositionWithOffset(pos, dp(80))
                recycler.postDelayed({
                    try {
                        val holder = recycler.findViewHolderForAdapterPosition(pos) ?: return@postDelayed
                        val v = holder.itemView
                        val accent = com.zephyr.browser.settings.SettingsUi.accent(this)
                        v.animate().cancel()
                        // two soft pulses — enough to catch the eye, short
                        // enough not to feel like an error state
                        v.setBackgroundColor((accent and 0x00FFFFFF) or 0x40000000)
                        v.animate().alpha(0.55f).setDuration(260).withEndAction {
                            v.animate().alpha(1f).setDuration(260).withEndAction {
                                v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }.start()
                        }.start()
                    } catch (_: Throwable) {}
                }, 220)
            } catch (_: Throwable) {}
        }
    }

    /** v1.7.11: every sheet / dialog / menu builder is armored — a failure is
     *  logged to last_crash.txt (+ toast) instead of killing the process. The
     *  helper is inline so non-local `return`s inside wrapped bodies keep
     *  working exactly like before. */
    private inline fun safeSheet(where: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            writeCrashFile(t, where)
            try { Toast.makeText(this, R.string.crash_reported, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
    }

    private fun showZoomMenu() {
        safeSheet("showZoomMenu") {
        val labels = arrayOf(
            getString(R.string.files_zoom_0),
            getString(R.string.files_zoom_1),
            getString(R.string.files_zoom_2),
            getString(R.string.files_zoom_3))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_zoom)
            .setSingleChoiceItems(labels, Prefs.filesZoom.coerceIn(0, 3)) { d, which ->
                Prefs.setFilesZoom(which)
                d.dismiss()
                applyLayoutMode()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        }
    }

    /** Pinch feedback: subtle scale pulse, no toast spam. */
    private fun pulseZoom() {
        recycler.animate().scaleX(0.965f).scaleY(0.965f).alpha(0.8f).setDuration(90)
            .withEndAction {
                recycler.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }.start()
    }

    private fun changeZoom(delta: Int) {
        val target = (Prefs.filesZoom + delta).coerceIn(0, 3)
        if (target == Prefs.filesZoom) return
        Prefs.setFilesZoom(target)
        applyLayoutMode()
        pulseZoom()
    }

    private var pinchAccum = 1f

    /** v1.7.13: MUST stay LAZY. An eager field initializer runs inside <init>
     *  BEFORE the base context is attached — ScaleGestureDetector →
     *  ViewConfiguration.get → getResources() on a null context crashed with
     *  NPE, so the system could not even INSTANTIATE this activity
     *  («Unable to instantiate activity … FilesActivity» — the exact trace
     *  the 1.7.12 launch-probe captured and the user shared). That is why
     *  every in-onCreate armor of v1.7.10–1.7.12 never fired: the crash was
     *  EARLIER, in the constructor. First access happens in the recycler
     *  touch listener — long after onCreate, when the context is alive. */
    private val scaleDetector: ScaleGestureDetector by lazy {
        ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    pinchAccum *= detector.scaleFactor
                    if (pinchAccum >= 1.25f) { pinchAccum = 1f; changeZoom(-1); return true } // spread = bigger
                    if (pinchAccum <= 0.8f) { pinchAccum = 1f; changeZoom(+1); return true }  // pinch = smaller
                    return false
                }
            })
    }

    // ------------------------------------------------------------- navigation

    /** Header back / system back: search → close, subfolder → climb, else exit. */
    private fun onUp() {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        when {
            searchBox.visibility == View.VISIBLE -> {
                searchBox.visibility = View.GONE; searchAllRow.visibility = View.GONE
                searchInput.setText(""); query = ""; reloadNow()
            }
            tab == TAB_STORAGE && currentDir != null && currentDir!!.absolutePath != rootPath -> {
                currentDir = currentDir!!.parentFile; reloadNow()
            }
            else -> finishWithAnim()
        }
    }

    private fun finishWithAnim() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold, R.anim.panel_out)
    }

    private fun reloadNow() {
        main.removeCallbacks(searchPost)
        loadAsync()
    }

    // ------------------------------------------------------------- data load

    private fun loadAsync() {
        // v1.7.11 («файлы всё равно не открываются — всё так же»): loadAsync runs
        // from MANY entry points after onCreate (tab clicks, search debounce,
        // permission results, folder picker, sort/zoom menus) — none of which
        // had protection. The WHOLE synchronous part is now armored; a failure
        // lands on the fallback screen (and last_crash.txt), never the process.
        try {
            loadAsyncInner()
        } catch (t: Throwable) {
            writeCrashFile(t, "loadAsync")
            fallbackErrorUi(t)
        }
    }

    private fun loadAsyncInner() {
        val dir = currentDir
        val tabNow = tab
        val q = query
        val ctx = this
        val gen = ++loadGen
        styleTabs()
        val globalSearch = tabNow == TAB_STORAGE && hasAllFiles() && q.isNotEmpty() && searchAll
        fab.visibility = if (tabNow == TAB_MEDIA) View.GONE else View.VISIBLE
        trashBtn.visibility =
            if (tabNow == TAB_STORAGE || tabNow == TAB_MEDIA) View.VISIBLE else View.GONE
        updateSearchAllRow()
        crumbs.visibility = if (tabNow == TAB_STORAGE && hasAllFiles() && dir != null && !globalSearch)
            View.VISIBLE else View.GONE
        chipsRow.visibility = if (tabNow == TAB_STORAGE && hasAllFiles() && !globalSearch &&
            (dir == null || dir.absolutePath == Environment.getExternalStorageDirectory().absolutePath))
            View.VISIBLE else View.GONE
        if (globalSearch) {
            // instant feedback while the walker runs on IO
            items.clear()
            items.add(Item.Msg(getString(R.string.files_searching), R.drawable.ic_search))
            applyLayoutMode()
            recycler.alpha = 1f
            ioScope.launch(Dispatchers.IO) {
                // v1.7.11: an uncaught throw inside a launch on Dispatchers.IO
                // kills the WHOLE process (that is exactly how «сбой и вылетаю из
                // приложения» felt) — every IO body is wrapped now.
                val loaded = try {
                    searchAllStorage(q)
                } catch (t: Throwable) {
                    writeCrashFile(t, "searchAllStorage")
                    listOf<Item>(Item.Msg(getString(R.string.files_load_failed), R.drawable.ic_delete))
                }
                withContext(Dispatchers.Main) {
                    if (isFinishing || ctx.tab != tabNow || gen != loadGen || ctx.query != q) return@withContext
                    try {
                        items.clear()
                        items.addAll(loaded)
                        applyLayoutMode()
                        recycler.alpha = 1f
                    } catch (_: Throwable) {}
                }
            }
            return
        }
        recycler.alpha = 0.6f
        ioScope.launch(singleIo) {
            val loaded = try {
                when {
                    tabNow == TAB_STORAGE && !hasAllFiles() -> listOf<Item>(Item.Card(
                        getString(R.string.files_gate_title), getString(R.string.files_gate_text),
                        getString(R.string.files_grant)) { openAllFilesSettings() })
                    tabNow == TAB_STORAGE -> loadStorage(dir ?: File(Environment.getExternalStorageDirectory().absolutePath), q)
                    tabNow == TAB_MEDIA -> loadMedia(q)
                    else -> loadDownloads()
                }
            } catch (t: Throwable) {
                // v1.7.11: loader failure → readable error row, never a dead process
                writeCrashFile(t, "loadAsync/io")
                listOf<Item>(Item.Msg(getString(R.string.files_load_failed), R.drawable.ic_delete))
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || ctx.tab != tabNow || gen != loadGen) return@withContext
                try {
                    items.clear()
                    items.addAll(loaded)
                    applyLayoutMode()
                    recycler.alpha = 1f
                    if (tabNow == TAB_STORAGE && dir != null) renderCrumbs(dir)
                    if (tabNow == TAB_DOWNLOADS) startDmPoll() else stopDmPoll()
                } catch (_: Throwable) {}
            }
        }
    }

    /** Whole-storage walk: max depth 8, max 4000 hits, skip hidden entries and
     *  the whole Android/ tree at root level; MediaStore Downloads/Files as a
     *  fallback for anything the walk misses. */
    private fun searchAllStorage(q: String): List<Item> {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val out = mutableListOf<Item>()
        val seen = HashSet<String>()
        val deadline = System.currentTimeMillis() + 15000
        fun walk(d: File, depth: Int) {
            if (out.size >= 4000 || depth > 8 || System.currentTimeMillis() > deadline) return
            val children = try { d.listFiles() } catch (_: Exception) { null } ?: return
            for (f in children) {
                if (out.size >= 4000) return
                val n = f.name
                if (n.startsWith(".")) continue
                if (depth == 0 && n.equals("Android", ignoreCase = true)) continue
                if (n.contains(q, ignoreCase = true) && seen.add(f.absolutePath.lowercase())) {
                    out.add(Item.Fs(f, relOf(f.parentPath(), rootPath)))
                }
                if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(File(rootPath), 0)
        try {
            val cols = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
            val collections = mutableListOf(MediaStore.Files.getContentUri("external"))
            if (Build.VERSION.SDK_INT >= 29) collections.add(MediaStore.Downloads.getContentUri("external"))
            for (coll in collections) {
                try {
                    contentResolver.query(coll, cols,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$q%"), null)?.use { c ->
                        while (c.moveToNext() && out.size < 4000) {
                            val p = c.getString(1) ?: continue
                            if (p.isEmpty() || !p.startsWith("/")) continue
                            val f = File(p)
                            if (!f.exists() || !seen.add(f.absolutePath.lowercase())) continue
                            out.add(Item.Fs(f, relOf(f.parentPath(), rootPath)))
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val fsItems = out.filterIsInstance<Item.Fs>()
        val byPath = fsItems.associateBy { it.file.absolutePath }
        val sorted = sortFiles(fsItems.map { it.file }).mapNotNull { byPath[it.absolutePath] }
        val capped = sorted.take(500)
        val header = Item.Msg(getString(R.string.files_found_fmt, sorted.size), R.drawable.ic_search)
        return listOf(header) + capped +
            if (capped.isEmpty()) listOf(Item.Msg(getString(R.string.files_empty), R.drawable.ic_folder_open))
            else emptyList()
    }

    private fun File?.parentPath(): String? = this?.absolutePath

    private fun relOf(parent: String?, rootPath: String): String =
        (parent ?: rootPath).removePrefix(rootPath).removePrefix("/")

    private fun styleTabs() {
        // v1.7.10 hardening: theme/accent edge cases must not kill the render
        try {
            for (b in tabButtons) {
                // v1.7.14: select by the TAB CONSTANT stored in the segment's
                // tag — visual position no longer matches [0,1,2] after a
                // reorder, so the old `i == tab` check mis-highlighted.
                if (b.tag as? Int == tab) {
                    b.background = SettingsUi.roundedFill(this, dp(20))
                    b.setTextColor(csl(tint(R.attr.colorOnPrimary)))
                } else {
                    b.background = SettingsUi.rounded(this, dp(20))
                    b.setTextColor(csl(tint(R.attr.colorOnSurfaceVariant)))
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun mediaGranted(): Boolean {
        val img = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        val vid = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        val legacy = Build.VERSION.SDK_INT < 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return (img && vid) || legacy
    }

    private fun openAllFilesSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
        }
    }

    private fun requestMedia() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        requestPermissions(perms, 4477)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4477) loadAsync()
    }

    // ---- storage tree ----

    private fun loadStorage(dir: File, q: String): List<Item> {
        currentDir = dir
        val files = try { dir.listFiles()?.toList() } catch (_: Exception) { null } ?: emptyList()
        val shown = (if (q.isEmpty()) files else files.filter { it.name.contains(q, ignoreCase = true) })
        return sortFiles(shown).map { Item.Fs(it) } +
            if (shown.isEmpty()) listOf(Item.Msg(getString(R.string.files_empty), R.drawable.ic_folder_open)) else emptyList()
    }

    private fun sortFiles(list: List<File>): List<File> {
        val comparator = when (Prefs.filesSort) {
            0 -> compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.getDefault()) }
            1 -> compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() }
            2 -> compareByDescending<File> { it.isDirectory }.thenBy { it.lastModified() }
            3 -> compareByDescending<File> { it.isDirectory }.thenByDescending { if (it.isFile) it.length() else 0L }
            4 -> compareByDescending<File> { it.isDirectory }
                .thenBy { typeOrder(it.name) }.thenBy { it.name.lowercase(Locale.getDefault()) }
            else -> compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.getDefault()) }
        }
        return list.sortedWith(comparator)
    }

    private fun typeOrder(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
        in IMAGE_EXT -> 0
        in VIDEO_EXT -> 1
        in AUDIO_EXT -> 2
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md" -> 3
        in ARCHIVE_EXT -> 4
        "apk" -> 5
        else -> 6
    }

    /** v1.7.9 FIX: crumbs are CLAMPED at the storage root — filesystem
     *  ancestors ("/storage", "emulated") are never shown; "…" jumps to root. */
    private fun renderCrumbs(dir: File) {
        // v1.7.10 hardening: a breadcrumb render failure must never kill the screen
        try {
            crumbs.removeAllViews()
            val rootPath = Environment.getExternalStorageDirectory().absolutePath
            val parts = mutableListOf<Pair<String, File>>()
            var d: File? = dir
            while (d != null) {
                val label = if (d.absolutePath == rootPath) getString(R.string.files_root)
                else d.name.ifEmpty { getString(R.string.files_root) }
                parts.add(0, label to d)
                if (d.absolutePath == rootPath) break
                d = d.parentFile
            }
            fun crumb(label: String, target: File?, last: Boolean) {
                crumbs.addView(TextView(this).apply {
                    text = if (last) label else "$label  ›"
                    textSize = 13f
                    typeface = if (last) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    maxLines = 1
                    setTextColor(csl(tint(if (last) R.attr.colorOnSurface else R.attr.colorPrimary)))
                    setPadding(0, dp(4), dp(2), dp(4))
                    if (!last && target != null) setOnClickListener { currentDir = target; reloadNow() }
                })
            }
            if (parts.size > 4) {
                crumb("…", parts.first().second, false)
                for ((l, t) in parts.takeLast(3).dropLast(1)) crumb(l, t, false)
            } else {
                for ((l, t) in parts.dropLast(1)) crumb(l, t, false)
            }
            parts.lastOrNull()?.let { crumb(it.first, null, true) }
            crumbs.parent?.let { (it as View).requestLayout() }
        } catch (_: Throwable) {}
    }

    // ---- media store ----

    private fun queryMedia(collection: Uri, image: Boolean, video: Boolean, q: String): List<Item.Media> {
        val out = mutableListOf<Item.Media>()
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATA)
            contentResolver.query(collection, proj,
                if (q.isEmpty()) null else "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                if (q.isEmpty()) null else arrayOf("%$q%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 800) {
                    val id = c.getLong(0)
                    out.add(Item.Media(Uri.withAppendedPath(collection, id.toString()).toString(), id,
                        c.getString(1) ?: "", c.getLong(2), c.getString(3) ?: "",
                        c.getLong(4) * 1000L, image, video, c.getString(5) ?: ""))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun loadMedia(q: String): List<Item> {
        if (!mediaGranted()) return listOf(Item.Card(
            getString(R.string.files_perm_title), getString(R.string.files_perm_text),
            getString(R.string.files_grant)) { requestMedia() })
        val imgs = queryMedia(MediaStore.Images.Media.getContentUri("external"), image = true, video = false, q = q)
        val vids = queryMedia(MediaStore.Video.Media.getContentUri("external"), image = false, video = true, q = q)
        val all = (imgs + vids).sortedWith(when (Prefs.filesSort) {
            0 -> compareBy<Item.Media> { it.name.lowercase(Locale.getDefault()) }
            2 -> compareBy<Item.Media> { it.added }
            3 -> compareByDescending<Item.Media> { it.size }
            4 -> compareBy<Item.Media> { if (it.isImage) 0 else 1 }.thenBy { it.name.lowercase(Locale.getDefault()) }
            else -> compareByDescending<Item.Media> { it.added }
        })
        return all.map { Item.Media(it.uri, it.id, it.name, it.size, it.mime, it.added, it.isImage, it.isVideo, it.path) } +
            if (all.isEmpty()) listOf(Item.Msg(getString(R.string.files_empty), R.drawable.ic_image)) else emptyList()
    }

    // ---- download manager ----

    private fun dmHeadItems(): MutableList<Item> {
        val head = mutableListOf<Item>()
        if (!Prefs.isHintDismissed("files_dm")) head.add(Item.PremiumHint("files_dm",
            R.drawable.ic_download, getString(R.string.files_dm_hint_title), getString(R.string.files_dm_hint_body)))
        head.add(Item.Card(getString(R.string.files_dm_folder),
            currentDmFolderLabel(), getString(R.string.files_dm_change)) { pickDmFolder() })
        // v1.7.9: download scheduler toggles (engine side done by DownloadEngine)
        head.add(Item.Sched())
        return head
    }

    private suspend fun loadDownloads(): List<Item> {
        val rows = try { db().downloadDao().allOnce() } catch (_: Exception) { emptyList() }
        val live = DownloadEngine.liveSnapshot()
        val head = dmHeadItems()
        val body = rows.map { Item.Dm(it, live[it.id]) }
        return head + body + if (body.isEmpty())
            listOf(Item.Msg(getString(R.string.files_dm_empty), R.drawable.ic_download)) else emptyList()
    }

    private fun db() = com.zephyr.browser.db.AppDb.get(this)

    private fun currentDmFolderLabel(): String {
        val uri = Prefs.downloadDirUri
        return if (uri.isEmpty()) getString(R.string.files_dm_default_folder)
        else try {
            val doc = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(uri))
            getString(R.string.files_dm_custom_folder, doc.substringAfterLast(':'))
        } catch (_: Exception) { getString(R.string.files_dm_default_folder) }
    }

    private val dmFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
            Prefs.setDownloadDirUri(uri.toString())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
        dmDirty = true
        loadAsync()
    }

    private fun pickDmFolder() {
        try { dmFolderPicker.launch(null) } catch (_: Exception) {
            Toast.makeText(this, R.string.files_folder_missing, Toast.LENGTH_SHORT).show()
        }
    }

    /** 600 ms live refresh while the manager tab is visible. */
    private fun startDmPoll() {
        stopDmPoll()
        val poll = object : Runnable {
            override fun run() {
                if (tab != TAB_DOWNLOADS || isFinishing) return
                // v1.7.10 hardening: a polling error must never crash — the
                // self-repost stays OUTSIDE the try so polling keeps running.
                try {
                    val live = DownloadEngine.liveSnapshot()
                    var dirty = dmDirty || live.isNotEmpty()
                    dmDirty = false
                    // refresh when any transfer is active OR a row's state changed
                    if (dirty) {
                        ioScope.launch(singleIo) {
                            val rows = try { db().downloadDao().allOnce() } catch (_: Exception) { emptyList() }
                            val l2 = DownloadEngine.liveSnapshot()
                            val head = dmHeadItems()
                            val body = rows.map { Item.Dm(it, l2[it.id]) }
                            val fresh = head + body + if (body.isEmpty())
                                listOf(Item.Msg(getString(R.string.files_dm_empty), R.drawable.ic_download)) else emptyList()
                            withContext(Dispatchers.Main) {
                                if (tab != TAB_DOWNLOADS || isFinishing) return@withContext
                                items.clear(); items.addAll(fresh)
                                applyLayoutMode()
                            }
                        }
                    }
                } catch (_: Throwable) {}
                main.postDelayed(this, 600)
            }
        }
        dmPoll = poll
        main.postDelayed(poll, 600)
    }

    private fun stopDmPoll() {
        dmPoll?.let { main.removeCallbacks(it) }
        dmPoll = null
    }

    // ------------------------------------------------------------- sort menu

    private fun showSortMenu() {
        safeSheet("showSortMenu") {
        val labels = arrayOf(
            getString(R.string.files_sort_name),
            getString(R.string.files_sort_new),
            getString(R.string.files_sort_old),
            getString(R.string.files_sort_size),
            getString(R.string.files_sort_type))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_sort)
            .setSingleChoiceItems(labels, Prefs.filesSort) { d, which ->
                Prefs.setFilesSort(which)
                d.dismiss()
                loadAsync()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        }
    }

    // ------------------------------------------------------------- create / rename / editor

    private fun showCreateSheet() {
        safeSheet("showCreateSheet") {
        if (tab != TAB_STORAGE || !hasAllFiles()) {
            inputDialog(getString(R.string.files_new_note), "") { name ->
                val safe = (name.ifBlank { "note_${System.currentTimeMillis()}" }).let {
                    if (it.endsWith(".txt")) it else "$it.txt"
                }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.getContentUri("external"), cv)
                    if (uri != null) openTextEditor(null, uri)
                    else Toast.makeText(this, R.string.files_create_failed, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) { Toast.makeText(this, R.string.files_create_failed, Toast.LENGTH_SHORT).show() }
            }
            return
        }
        val dir = currentDir ?: File(Environment.getExternalStorageDirectory().absolutePath)
        val sheet = BottomSheetDialog(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(12), dp(8), dp(20)) }
        fun sRow(iconRes: Int, label: String, fn: () -> Unit) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(14))
                isClickable = true
                background = MenuSheets.ripple(this@FilesActivity)
                setOnClickListener { sheet.dismiss(); fn() }
                addView(ImageView(this@FilesActivity).apply {
                    setImageResource(iconRes); layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                    imageTintList = csl(tint(R.attr.colorPrimary))
                })
                addView(TextView(this@FilesActivity).apply {
                    text = label; textSize = 15f; setPadding(dp(14), 0, 0, 0)
                    setTextColor(csl(tint(R.attr.colorOnSurface)))
                })
            })
        }
        sRow(R.drawable.ic_folder_add, getString(R.string.files_new_folder)) {
            inputDialog(getString(R.string.files_new_folder), "") { name ->
                val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (safe.isNotEmpty()) {
                    try {
                        val ok = File(dir, safe).mkdirs()
                        Toast.makeText(this, if (ok) R.string.saved else R.string.files_create_failed, Toast.LENGTH_SHORT).show()
                        loadAsync()
                    } catch (_: Exception) { Toast.makeText(this, R.string.files_create_failed, Toast.LENGTH_SHORT).show() }
                }
            }
        }
        sRow(R.drawable.ic_text_file, getString(R.string.files_new_file)) {
            inputDialog(getString(R.string.files_new_file), "") { name ->
                val safe = (name.trim().ifEmpty { "note_${System.currentTimeMillis()}" }).let {
                    if (it.contains('.')) it else "$it.txt"
                }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                try {
                    val f = File(dir, safe)
                    f.createNewFile()
                    loadAsync()
                    openTextEditor(f.absolutePath, null)
                } catch (_: Exception) { Toast.makeText(this, R.string.files_create_failed, Toast.LENGTH_SHORT).show() }
            }
        }
        sheet.setContentView(col)
        sheet.show()
        }
    }

    private fun renameFile(f: File) {
        safeSheet("renameFile") {
        inputDialog(getString(R.string.files_rename), f.name) { name ->
            val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (safe.isNotEmpty() && safe != f.name) {
                try {
                    val ok = f.renameTo(File(f.parentFile, safe))
                    Toast.makeText(this, if (ok) R.string.saved else R.string.files_create_failed, Toast.LENGTH_SHORT).show()
                    loadAsync()
                } catch (_: Exception) { Toast.makeText(this, R.string.files_create_failed, Toast.LENGTH_SHORT).show() }
            }
        }
        }
    }

    private fun inputDialog(title: String, preset: String, onOk: (String) -> Unit) {
        safeSheet("inputDialog") {
        val input = EditText(this).apply {
            setText(preset); setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        }
    }

    /** TXT editor: monospace, full-screen dialog, save back to the same target. */
    private fun openTextEditor(path: String?, uri: Uri?) {
        safeSheet("openTextEditor") {
        val editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13.5f
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = null
            minLines = 18
        }
        var text = ""
        try {
            text = if (uri != null) contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            else File(path!!).readText()
        } catch (_: Exception) {}
        editor.setText(text)

        var dialogRef: android.app.Dialog? = null
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(4))
            addView(ImageButton(this@FilesActivity).apply {
                setImageResource(R.drawable.ic_back)
                background = MenuSheets.ripple(this@FilesActivity)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                imageTintList = csl(tint(R.attr.colorOnSurface))
                setOnClickListener { dialogRef?.dismiss() }
            })
            addView(TextView(this@FilesActivity).apply {
                text = File(uri?.lastPathSegment ?: path ?: "").name.ifEmpty { getString(R.string.files_editor) }
                textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(csl(tint(R.attr.colorOnSurface)))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
            })
            addView(ImageButton(this@FilesActivity).apply {
                setImageResource(R.drawable.ic_download_done)
                background = MenuSheets.ripple(this@FilesActivity)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                imageTintList = csl(tint(R.attr.colorPrimary))
                contentDescription = getString(R.string.save)
                setOnClickListener {
                    try {
                        if (uri != null) {
                            contentResolver.openOutputStream(uri, "wt")?.use { it.write(editor.text.toString().toByteArray()) }
                        } else {
                            File(path!!).writeText(editor.text.toString())
                        }
                        Toast.makeText(this@FilesActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(this@FilesActivity, R.string.files_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SettingsUi.color(this@FilesActivity, R.attr.colorSurface))
        }
        content.addView(bar)
        content.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dlg = MaterialAlertDialogBuilder(this)
            .setView(content)
            .create()
        dialogRef = dlg
        dlg.show()
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.86f).toInt())
        }
    }

    // ------------------------------------------------------------- open with / share

    /** Best mime for a target: provider type, refined by extension map (so
     *  APKs always resolve to application/vnd.android.package-archive even
     *  when the provider shrugs with application/octet-stream). */
    private fun bestMime(uri: Uri, name: String): String {
        val provider = try { contentResolver.getType(uri) } catch (_: Exception) { null }
        val guessed = SettingsUi.guessMime(name)
        return when {
            provider == null || provider.isBlank() || provider == "application/octet-stream" ->
                if (guessed != "*/*") guessed else (provider ?: guessed)
            else -> provider
        }
    }

    /** v1.7.9: premium OPEN-WITH bottom sheet — the fix for "APK tap does
     *  nothing" and "let me choose the app for photos". Any file (storage,
     *  media, finished downloads) routes here.
     *
     *  - app list rows open the target ONCE via an explicit intent;
     *  - the «Выбрать приложение» row fires the PLAIN implicit intent so the
     *    system resolver natively offers «Только сейчас / Всегда» (exactly
     *    like the Downloads section);
     *  - v1.7.17: builtinMedia routes images/video/audio to the BUILT-IN
     *    viewers (photo viewer / player) via a prominent accent row above
     *    the app list; sibUris/sibNames/sibPaths/sibIndex carry the sibling
     *    window the photo viewer swipes through (empty for single items);
     *    text files keep an editor row. */
    private fun showOpenWithSheet(
        uri: Uri,
        name: String,
        sub: String,
        file: File? = null,
        builtinMedia: Int = 0,
        sibUris: List<String> = emptyList(),
        sibNames: List<String> = emptyList(),
        sibPaths: List<String> = emptyList(),
        sibIndex: Int = 0,
        onEdit: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
        showInFolder: File? = null
    ) {
        // v1.7.11: THE tap-a-file path («при нажатии и попытке открыть файлы…
        // сбой и вылетаю из приложения») — fully armored: any failure (icon
        // load, sheet inflation, resolver query) degrades to a toast + report.
        safeSheet("showOpenWithSheet") {
            showOpenWithSheetInner(uri, name, sub, file, builtinMedia,
                sibUris, sibNames, sibPaths, sibIndex, onEdit, onDelete, showInFolder)
        }
    }

    private fun showOpenWithSheetInner(
        uri: Uri,
        name: String,
        sub: String,
        file: File?,
        builtinMedia: Int,
        sibUris: List<String>,
        sibNames: List<String>,
        sibPaths: List<String>,
        sibIndex: Int,
        onEdit: (() -> Unit)?,
        onDelete: (() -> Unit)?,
        showInFolder: File?
    ) {
        val mime = bestMime(uri, name)
        // v1.7.15 (user: «выбираю установщик — тишина, не устанавливается»):
        // APKs must NEVER go through a raw ACTION_VIEW — without the
        // REQUEST_INSTALL_PACKAGES grant (manifest v1.7.15) the system
        // installer exits SILENTLY. Every launch path below routes through
        // DownloadEngine.installApk, which gates on the unknown-sources
        // permission first and only then fires the view intent.
        val isApk = mime == "application/vnd.android.package-archive" || name.endsWith(".apk", true)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val apps: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= 33)
                packageManager.queryIntentActivities(viewIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            else
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(viewIntent, PackageManager.MATCH_ALL)
        } catch (_: Exception) { emptyList() }
            .filter { it.activityInfo != null && it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .sortedBy { (it.loadLabel(packageManager)?.toString() ?: "").lowercase(Locale.getDefault()) }

        val sheet = BottomSheetDialog(this)
        val scroll = NestedScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(20)) }
        scroll.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- header: name + size·date ----
        col.addView(TextView(this).apply {
            text = name
            textSize = 16.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(csl(tint(R.attr.colorOnSurface)))
            setPadding(dp(12), dp(8), dp(12), dp(2))
        })
        if (sub.isNotEmpty()) col.addView(TextView(this).apply {
            text = sub
            textSize = 12.5f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(csl(tint(R.attr.colorOnSurfaceVariant)))
            setPadding(dp(12), 0, dp(12), dp(6))
        })

        fun divider() {
            col.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(6); bottomMargin = dp(6)
                }
                setBackgroundColor(hairline())
            })
        }
        fun sectionLabel(txt: String) {
            col.addView(TextView(this).apply {
                text = txt
                textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(csl(tint(R.attr.colorPrimary)))
                setPadding(dp(12), dp(10), dp(12), dp(2))
            })
        }
        /** real app row (app icon drawable, no tint) */
        fun appRow(icon: Drawable?, label: String, onClick: () -> Unit) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                isClickable = true
                background = MenuSheets.ripple(this@FilesActivity)
                setOnClickListener { sheet.dismiss(); onClick() }
                addView(ImageView(this@FilesActivity).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                })
                addView(TextView(this@FilesActivity).apply {
                    text = label; textSize = 15f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(14), 0, 0, 0)
                    setTextColor(csl(tint(R.attr.colorOnSurface)))
                })
            })
        }
        /** themed action row (vector icon tinted colorPrimary) */
        fun actRow(iconRes: Int, label: String, danger: Boolean = false, onClick: () -> Unit) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                isClickable = true
                background = MenuSheets.ripple(this@FilesActivity)
                setOnClickListener { sheet.dismiss(); onClick() }
                addView(ImageView(this@FilesActivity).apply {
                    setImageResource(iconRes)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                    imageTintList = csl(if (danger) 0xFFEF4444.toInt() else tint(R.attr.colorPrimary))
                })
                addView(TextView(this@FilesActivity).apply {
                    text = label; textSize = 15f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(14), 0, 0, 0)
                    setTextColor(csl(tint(R.attr.colorOnSurface)))
                })
            })
        }

        // ---- section: open with ----
        sectionLabel(getString(R.string.files_open_with))
        // v1.7.17: PROMINENT built-in viewer row — images open the new photo
        // viewer (zoom / swipe / info / delete), video+audio the built-in
        // player. Styled like the v1.7.15 APK install row (accent icon +
        // bold primary label) so the native option reads as the FIRST choice.
        if (builtinMedia != 0) {
            val bmIcon = when (builtinMedia) {
                1 -> R.drawable.ic_image
                2 -> R.drawable.ic_player_play
                else -> R.drawable.ic_music_note
            }
            val bmLabel = when (builtinMedia) {
                1 -> getString(R.string.files_view_in_zephyr)
                2 -> getString(R.string.pl_watch_in_zephyr)
                else -> getString(R.string.pl_listen_in_zephyr)
            }
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                background = MenuSheets.ripple(this@FilesActivity)
                setOnClickListener {
                    sheet.dismiss()
                    if (builtinMedia == 1) {
                        // photo viewer — with the sibling window when available
                        openPhotoViewer(
                            if (sibUris.isNotEmpty()) sibUris else listOf(uri.toString()),
                            if (sibNames.isNotEmpty()) sibNames else listOf(name),
                            if (sibUris.isNotEmpty()) sibIndex else 0,
                            if (sibPaths.isNotEmpty()) sibPaths else listOf(""))
                    } else {
                        // built-in player (video / audio)
                        try {
                            startActivity(Intent(this@FilesActivity, PlayerActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                setDataAndType(uri, mime)
                                putExtra(Intent.EXTRA_TITLE, name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: Exception) {
                            Toast.makeText(this@FilesActivity, R.string.files_no_app, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                addView(ImageView(this@FilesActivity).apply {
                    setImageResource(bmIcon)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    imageTintList = csl(tint(R.attr.colorPrimary))
                })
                addView(TextView(this@FilesActivity).apply {
                    text = bmLabel; textSize = 16f; maxLines = 1
                    typeface = Typeface.DEFAULT_BOLD
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(14), 0, 0, 0)
                    setTextColor(csl(tint(R.attr.colorPrimary)))
                })
            })
        }
        // v1.7.15: PROMINENT first row for APKs — installing IS the action a
        // downloaded APK exists for (accent-colored, bolder than app rows).
        if (isApk) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                background = MenuSheets.ripple(this@FilesActivity)
                setOnClickListener {
                    sheet.dismiss()
                    DownloadEngine.installApk(this@FilesActivity, uri, name)
                }
                addView(ImageView(this@FilesActivity).apply {
                    setImageResource(R.drawable.ic_download)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    imageTintList = csl(tint(R.attr.colorPrimary))
                })
                addView(TextView(this@FilesActivity).apply {
                    text = getString(R.string.install_action); textSize = 16f; maxLines = 1
                    typeface = Typeface.DEFAULT_BOLD
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(14), 0, 0, 0)
                    setTextColor(csl(tint(R.attr.colorPrimary)))
                })
            })
        }
        if (apps.isEmpty()) {
            col.addView(TextView(this).apply {
                text = getString(R.string.files_no_apps)
                textSize = 13.5f
                alpha = 0.65f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(csl(tint(R.attr.colorOnSurfaceVariant)))
            })
        }
        for (ri in apps) {
            val label = ri.loadLabel(packageManager)?.toString() ?: continue
            appRow(try { ri.loadIcon(packageManager) } catch (_: Exception) { null }, label) {
                if (isApk) {
                    // v1.7.15: the gate must come BEFORE any raw ACTION_VIEW on
                    // an APK — this is exactly the "silence" the user saw.
                    DownloadEngine.installApk(this@FilesActivity, uri, name)
                } else try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    })
                } catch (_: Exception) {
                    try { startActivity(viewIntent) }
                    catch (_: Exception) { Toast.makeText(this@FilesActivity, R.string.files_no_app, Toast.LENGTH_SHORT).show() }
                }
            }
        }
        // system resolver: native «Только сейчас / Всегда» choice
        actRow(R.drawable.ic_open_new, getString(R.string.files_open_always)) {
            if (isApk) DownloadEngine.installApk(this@FilesActivity, uri, name)
            else try { startActivity(viewIntent) }
            catch (_: Exception) {
                try { startActivity(Intent.createChooser(viewIntent, name)) }
                catch (_: Exception) { Toast.makeText(this@FilesActivity, R.string.files_no_app, Toast.LENGTH_SHORT).show() }
            }
        }
        // v1.7.17: the old plain actRow «Просмотр в Zephyr» was REPLACED by the
        // prominent builtinMedia row above the app list (zephyrPreview is gone).
        if (onEdit != null) actRow(R.drawable.ic_edit, getString(R.string.files_edit)) { onEdit() }

        // ---- actions ----
        divider()
        actRow(R.drawable.ic_share, getString(R.string.share)) { shareUri(uri, name) }
        if (file != null) actRow(R.drawable.ic_edit, getString(R.string.files_rename)) { renameFile(file) }
        if (showInFolder != null) actRow(R.drawable.ic_folder_open, getString(R.string.files_show_in_folder)) {
            tab = TAB_STORAGE
            currentDir = showInFolder
            reloadNow()
        }
        if (onDelete != null) actRow(R.drawable.ic_delete, getString(R.string.delete), danger = true, onClick = onDelete)

        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun hairline(): Int {
        val c = tint(R.attr.colorOnSurfaceVariant)
        return (c and 0x00FFFFFF) or 0x22000000
    }

    /** Finished download → the same open-with sheet. */
    private fun openDmFile(e: DownloadEntry) {
        if (e.path.isEmpty()) return
        val uri = if (e.path.startsWith("/")) fileUri(File(e.path)) else Uri.parse(e.path)
        val name = e.fileName.ifEmpty { e.url.substringAfter("//").take(40) }
        // v1.7.15: an APK goes STRAIGHT into the install flow — the
        // unknown-sources gate must run before any ACTION_VIEW (file paths
        // arrive FileProvider-wrapped by fileUri, content:// rows pass as-is).
        if (DownloadEngine.isApkName(name) || e.mime == "application/vnd.android.package-archive") {
            DownloadEngine.installApk(this, uri, name)
            return
        }
        val size = if (e.size > 0) e.size else e.totalBytes
        // v1.7.17: images/video/audio get the built-in viewer row in the sheet
        val ext = name.substringAfterLast('.', "").lowercase()
        val bm = when {
            isImageName(name) -> 1
            ext in VIDEO_EXT -> 2
            ext in AUDIO_EXT -> 3
            else -> 0
        }
        showOpenWithSheet(uri, name,
            Formatter.formatFileSize(this, size) + "  ·  " + fmt.format(Date(e.addedAt)),
            builtinMedia = bm,
            sibUris = listOf(uri.toString()), sibNames = listOf(name),
            sibPaths = listOf(if (e.path.startsWith("/")) e.path else ""))
    }

    /** v1.7.8 FIX: storage files open via FileProvider — file:// URIs are rejected
     *  by ACTION_VIEW on API 24+, which is why PDFs "had no app" in v1.7.7. */
    private fun fileUri(f: File): Uri = try {
        androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    } catch (_: Exception) { Uri.fromFile(f) }

    private fun shareUri(uri: Uri, name: String) {
        try {
            val mime = bestMime(uri, name)
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {}
    }

    private fun isTextFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("txt", "md", "json", "xml", "log", "csv", "html", "js", "css", "conf", "ini", "yml", "yaml")

    private fun isImageName(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    private fun fileIconRes(name: String, isDir: Boolean): Int = when {
        isDir -> R.drawable.ic_folder
        isImageName(name) -> R.drawable.ic_image
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXT -> R.drawable.ic_video
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXT -> R.drawable.ic_music_note
        name.substringAfterLast('.', "").lowercase() == "pdf" -> R.drawable.ic_file
        name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXT -> R.drawable.ic_archive
        name.substringAfterLast('.', "").lowercase() == "apk" -> R.drawable.ic_android_file
        isTextFile(name) -> R.drawable.ic_text_file
        else -> R.drawable.ic_file
    }

    // ------------------------------------------------------------- trash (30 days)

    private fun trashDir(): File =
        File(getExternalFilesDir(null) ?: filesDir, "trash").apply { mkdirs() }

    private fun dirSize(f: File): Long {
        if (f.isFile) return f.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(f)
        val deadline = System.currentTimeMillis() + 2500
        while (stack.isNotEmpty() && System.currentTimeMillis() < deadline) {
            val d = stack.removeLast()
            val kids = try { d.listFiles() } catch (_: Exception) { null } ?: continue
            for (k in kids) if (k.isDirectory) stack.addLast(k) else total += k.length()
        }
        return total
    }

    private fun copyFile(src: File, dst: File): Boolean = try {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it, 64 * 1024) } }
        true
    } catch (_: Exception) { false }

    private fun copyDir(src: File, dst: File): Boolean {
        if (!dst.exists() && !dst.mkdirs()) return false
        val kids = try { src.listFiles() } catch (_: Exception) { null } ?: return false
        var ok = true
        for (k in kids) {
            val t = File(dst, k.name)
            if (k.isDirectory) { if (!copyDir(k, t)) ok = false }
            else if (!copyFile(k, t)) ok = false
        }
        return ok
    }

    private fun moveToTrashStorage(f: File): File? {
        val target = File(trashDir(), System.currentTimeMillis().toString() + "_" + f.name)
        if (f.renameTo(target)) return target
        // cross-volume (or locked) fallback: copy, then remove the original
        val copied = try { if (f.isDirectory) copyDir(f, target) else copyFile(f, target) } catch (_: Exception) { false }
        if (!copied) return null
        if (f.isDirectory) f.deleteRecursively() else f.delete()
        return target
    }

    /** Storage file/folder → trash + DB row. */
    private fun moveToTrashFile(f: File) {
        ioLaunch {
            val size = dirSize(f)
            val stored = moveToTrashStorage(f)
            if (stored != null) {
                try {
                    db().trashDao().insert(TrashEntry(
                        originalPath = f.absolutePath, fileName = f.name,
                        storedPath = stored.absolutePath, isDir = f.isDirectory,
                        size = size, deletedAt = System.currentTimeMillis()))
                } catch (_: Exception) {}
            }
            main.post {
                Toast.makeText(this@FilesActivity,
                    if (stored != null) R.string.files_to_trash else R.string.files_delete_failed,
                    Toast.LENGTH_SHORT).show()
                loadAsync()
            }
        }
    }

    /** MediaStore item → copy into trash, then delete the provider row. */
    private fun moveToTrashMedia(uri: Uri, name: String, size: Long, pathHint: String) {
        ioLaunch {
            val stored = File(trashDir(), System.currentTimeMillis().toString() + "_" + name)
            var ok = false
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    stored.outputStream().use { input.copyTo(it, 64 * 1024) }
                }
                val deleted = try { contentResolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }
                if (deleted) ok = true else stored.delete()
            } catch (_: Exception) { stored.delete() }
            if (ok) {
                val original = pathHint.ifEmpty {
                    File(Environment.getExternalStorageDirectory(), name).absolutePath
                }
                try {
                    db().trashDao().insert(TrashEntry(
                        originalPath = original, fileName = name,
                        storedPath = stored.absolutePath, isDir = false,
                        size = size, deletedAt = System.currentTimeMillis()))
                } catch (_: Exception) {}
            }
            main.post {
                Toast.makeText(this@FilesActivity,
                    if (ok) R.string.files_to_trash else R.string.files_delete_failed,
                    Toast.LENGTH_SHORT).show()
                loadAsync()
            }
        }
    }

    private fun uniqueTarget(dest: File): File {
        if (!dest.exists()) return dest
        val parent = dest.parentFile ?: return dest
        val name = dest.name
        val dot = name.lastIndexOf('.')
        var i = 1
        while (i < 200) {
            val cand = if (dot > 0)
                File(parent, name.substring(0, dot) + " ($i)" + name.substring(dot))
            else File(parent, "$name ($i)")
            if (!cand.exists()) return cand
            i++
        }
        return File(parent, System.currentTimeMillis().toString() + "_" + name)
    }

    private fun restoreFromTrash(t: TrashEntry) {
        ioLaunch {
            val stored = File(t.storedPath)
            var dest = File(t.originalPath)
            if (dest.exists()) dest = uniqueTarget(dest)
            dest.parentFile?.mkdirs()
            var ok = stored.renameTo(dest)
            if (!ok) {
                ok = try {
                    if (stored.isDirectory) copyDir(stored, dest).also { stored.deleteRecursively() }
                    else copyFile(stored, dest).also { stored.delete() }
                } catch (_: Exception) { false }
            }
            if (ok) {
                try { db().trashDao().delete(t) } catch (_: Exception) {}
                // re-register restored media with MediaStore
                try { MediaScannerConnection.scanFile(this@FilesActivity, arrayOf(dest.absolutePath), null, null) } catch (_: Exception) {}
            }
            main.post {
                Toast.makeText(this@FilesActivity,
                    if (ok) R.string.files_restore_done else R.string.files_restore_failed,
                    Toast.LENGTH_SHORT).show()
                showTrashSheet()
            }
        }
    }

    private fun deleteFromTrash(t: TrashEntry) {
        ioLaunch {
            try {
                val f = File(t.storedPath)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            } catch (_: Exception) {}
            try { db().trashDao().delete(t) } catch (_: Exception) {}
            main.post { showTrashSheet() }
        }
    }

    private fun clearTrash() {
        ioLaunch {
            try {
                val rows = db().trashDao().allOnce()
                for (t in rows) {
                    try {
                        val f = File(t.storedPath)
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    } catch (_: Exception) {}
                }
                db().trashDao().clear()
            } catch (_: Exception) {}
            main.post {
                Toast.makeText(this@FilesActivity, R.string.files_trash_purged, Toast.LENGTH_SHORT).show()
                showTrashSheet()
            }
        }
    }

    /** Startup maintenance: drop entries older than 30 days — rows AND files. */
    private fun purgeTrashAsync() {
        ioLaunch {
            try {
                val cutoff = System.currentTimeMillis() - TRASH_KEEP_MS
                for (t in db().trashDao().allOnce()) {
                    if (t.deletedAt >= cutoff) continue
                    try {
                        val f = File(t.storedPath)
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    } catch (_: Exception) {}
                    db().trashDao().delete(t)
                }
            } catch (_: Exception) {}
        }
    }

    private fun showTrashSheet() {
        safeSheet("showTrashSheet") {
        ioLaunch {
            val rows = try { db().trashDao().allOnce() } catch (_: Exception) { emptyList() }
            main.post { if (!isFinishing) buildTrashSheet(rows) }
        }
        }
    }

    private fun buildTrashSheet(rows: List<TrashEntry>) {
        safeSheet("buildTrashSheet") {
        val sheet = BottomSheetDialog(this)
        val scroll = NestedScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(12), dp(8), dp(16)) }
        scroll.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- header ----
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            addView(ImageView(this@FilesActivity).apply {
                setImageResource(R.drawable.ic_delete)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                imageTintList = csl(tint(R.attr.colorPrimary))
            })
            addView(TextView(this@FilesActivity).apply {
                text = getString(R.string.files_trash)
                textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), 0, 0, 0)
                setTextColor(csl(tint(R.attr.colorOnSurface)))
            })
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.files_trash_restore_where)
            textSize = 12.5f
            setPadding(dp(12), dp(2), dp(12), dp(6))
            setTextColor(csl(tint(R.attr.colorOnSurfaceVariant)))
        })

        if (rows.isEmpty()) {
            col.addView(TextView(this).apply {
                text = getString(R.string.files_trash_empty)
                textSize = 15f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(18), dp(16), dp(6))
                setTextColor(csl(tint(R.attr.colorOnSurface)))
            })
        } else {
            for (t in rows) {
                val days = ((t.deletedAt + TRASH_KEEP_MS - System.currentTimeMillis()) / (24 * 3600 * 1000))
                    .toInt().coerceAtLeast(0)
                col.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(6), dp(8))
                    isClickable = true
                    background = MenuSheets.ripple(this@FilesActivity)
                    addView(ImageView(this@FilesActivity).apply {
                        setImageResource(fileIconRes(t.fileName, t.isDir))
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                        imageTintList = csl(tint(R.attr.colorPrimary))
                    })
                    addView(LinearLayout(this@FilesActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(12); marginEnd = dp(4)
                        }
                        addView(TextView(this@FilesActivity).apply {
                            text = t.fileName
                            textSize = 14.5f; maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                            setTextColor(csl(tint(R.attr.colorOnSurface)))
                        })
                        addView(TextView(this@FilesActivity).apply {
                            text = getString(R.string.files_trash_days_left, days) + "  ·  " +
                                Formatter.formatFileSize(this@FilesActivity, t.size)
                            textSize = 12f; maxLines = 1
                            setTextColor(csl(tint(R.attr.colorOnSurfaceVariant)))
                        })
                    })
                    fun miniBtn(iconRes: Int, desc: String, color: Int, onClick: () -> Unit) {
                        addView(ImageButton(this@FilesActivity).apply {
                            setImageResource(iconRes)
                            background = MenuSheets.ripple(this@FilesActivity)
                            setPadding(dp(9), dp(9), dp(9), dp(9))
                            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                            imageTintList = csl(color)
                            contentDescription = desc
                            setOnClickListener { sheet.dismiss(); onClick() }
                        })
                    }
                    miniBtn(R.drawable.ic_replay, getString(R.string.files_restore), tint(R.attr.colorPrimary)) {
                        restoreFromTrash(t)
                    }
                    miniBtn(R.drawable.ic_delete, getString(R.string.delete), 0xFFEF4444.toInt()) {
                        deleteFromTrash(t)
                    }
                })
            }
            // footnote + clear-all
            col.addView(TextView(this).apply {
                text = getString(R.string.files_trash_kept)
                textSize = 12f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(4))
                setTextColor(csl(tint(R.attr.colorOnSurfaceVariant)))
            })
            col.addView(TextView(this).apply {
                text = getString(R.string.files_trash_clear)
                textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(csl(0xFFFFFFFF.toInt()))
                background = SettingsUi.roundedFill(this@FilesActivity, dp(24))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                    topMargin = dp(10); marginStart = dp(8); marginEnd = dp(8)
                }
                isClickable = true
                setOnClickListener {
                    sheet.dismiss()
                    MaterialAlertDialogBuilder(this@FilesActivity)
                        .setTitle(R.string.files_trash_clear)
                        .setMessage(R.string.files_trash_confirm)
                        .setPositiveButton(R.string.delete) { _, _ -> clearTrash() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
        }
        if (rows.isEmpty()) col.addView(TextView(this).apply {
            text = getString(R.string.files_trash_kept)
            textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(2), dp(16), dp(4))
            setTextColor(csl(tint(R.attr.colorOnSurfaceVariant)))
        })

        sheet.setContentView(scroll)
        sheet.show()
        }
    }

    // ------------------------------------------------------------- photo viewer

    /** v1.7.17: fullscreen built-in photo VIEWER (zoom, swipe navigation, info,
     *  share, delete) — replaces the old one-off openImagePreview dialog. The
     *  viewer receives the whole sibling window so one tap on a camera-roll
     *  thumbnail unlocks swiping through the entire folder.
     *  @param uris  content:// or file-provider URIs (the photo list)
     *  @param names display names, same order
     *  @param index starting index
     *  @param paths OPTIONAL parallel array of real file paths (empty string
     *               when unknown — used for the Info sheet and deletion) */
    private fun openPhotoViewer(uris: List<String>, names: List<String>, index: Int, paths: List<String>) {
        try {
            if (uris.isEmpty()) return
            val i = index.coerceIn(0, uris.size - 1)
            startActivity(Intent(this, PhotoViewerActivity::class.java).apply {
                putStringArrayListExtra("uris", ArrayList(uris))
                putStringArrayListExtra("names", ArrayList(names))
                putStringArrayListExtra("paths", ArrayList(paths))
                putExtra("index", i)
                putExtra(Intent.EXTRA_TITLE, names.getOrNull(i) ?: "")
            })
        } catch (_: Exception) {
            Toast.makeText(this, R.string.files_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------- adapter

    private inner class FilesAdapter : RecyclerView.Adapter<FilesAdapter.VH>() {

        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            var thumb: ImageView? = null
            var title: TextView? = null
            var sub: TextView? = null
            var more: View? = null
            var progress: ProgressBar? = null
            var actions: LinearLayout? = null
            var cardRoot: LinearLayout? = null
            var textCol: LinearLayout? = null
            var sched: LinearLayout? = null
        }

        override fun getItemCount(): Int = items.size

        /** grid vs list = different view types → toggling recreates the holders.
         *  Manager/cards/empty rows stay LIST-styled (full span) even in grid mode. */
        override fun getItemViewType(position: Int): Int {
            if (!Prefs.filesGrid) return 0
            return when (items.getOrNull(position)) {
                is Item.Dm, is Item.Card, is Item.Hint, is Item.Msg, is Item.Sched,
                is Item.PremiumHint -> 0
                else -> 1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            // v1.7.11: holder creation runs in the FIRST layout frame — long
            // after onCreate's try/catch — and an uncaught throw here killed the
            // process with the Files screen never appearing. On failure a plain
            // unthemeable row is built instead (LinearLayout+TextView cannot fail).
            try {
                return onCreateHolder(ctx, viewType)
            } catch (t: Throwable) {
                writeCrashFile(t, "onCreateViewHolder")
            }
            // minimal fallback holder — zero themed drawables, cannot throw
            val plain = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(TextView(ctx).apply { textSize = 14f })
            }
            return VH(plain).also { vh -> vh.title = plain.getChildAt(0) as? TextView }
        }

        private fun onCreateHolder(ctx: android.content.Context, viewType: Int): VH {
            val grid = viewType == 1
            val row = LinearLayout(ctx).apply {
                orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (grid) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = SettingsUi.rounded(ctx, dp(18))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
                isClickable = true
            }
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    if (grid) ViewGroup.LayoutParams.MATCH_PARENT else dp(listThumbDp()),
                    if (grid) dp(gridThumbDp()) else dp(listThumbDp())).apply {
                    if (grid) bottomMargin = dp(8)
                }
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    if (grid) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (grid) 0f else 1f).apply {
                    if (!grid) marginStart = dp(12)
                }
            }
            val title = TextView(ctx)
            val sub = TextView(ctx)
            val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList = csl(tint(R.attr.colorPrimary))
                isIndeterminate = false
                max = 100
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) }
            }
            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }
            textCol.addView(title)
            textCol.addView(sub)
            textCol.addView(progress)
            textCol.addView(actions)
            row.addView(thumb)
            row.addView(textCol)
            val more = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_more_vert)
                background = MenuSheets.ripple(ctx)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                imageTintList = csl(tint(R.attr.colorOnSurfaceVariant))
                visibility = if (grid) View.GONE else View.VISIBLE
            }
            if (!grid) row.addView(more)
            return VH(row).also { vh ->
                vh.thumb = thumb; vh.title = title; vh.sub = sub
                vh.progress = progress; vh.actions = actions; vh.more = more
                vh.cardRoot = row; vh.textCol = textCol
            }
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ctx = h.itemView.context
            // v1.7.10 hardening: getOrNull (never IndexOutOfBounds during a
            // concurrent item swap) + whole-bind try — a broken row degrades
            // to a blank recycled row instead of killing the whole screen.
            val item = items.getOrNull(position) ?: return
            try {
                when (item) {
                    is Item.Dm -> bindDm(h, item, ctx)
                    is Item.Media -> bindMedia(h, item, ctx)
                    is Item.Fs -> bindFs(h, item, ctx)
                    is Item.Sched -> bindSched(h, ctx)
                    is Item.Msg -> {
                        resetBasic(h, ctx, false)
                        h.thumb!!.visibility = View.GONE   // GONE even in grid — no empty slab
                        h.title!!.text = item.text
                        h.sub!!.visibility = View.GONE
                        // v1.7.12: the loader-failure row doubles as a one-tap
                        // "send the saved crash trace" button — last_crash.txt
                        // pinpoints exactly why the list failed to load.
                        if (item.text == ctx.getString(R.string.files_load_failed)) {
                            h.itemView.isClickable = true
                            h.itemView.setOnClickListener {
                                try { shareCrashReport() } catch (_: Throwable) {}
                            }
                        } else {
                            h.itemView.isClickable = false
                        }
                        h.title!!.gravity = Gravity.CENTER
                    }
                    is Item.Card -> {
                        resetBasic(h, ctx, false)
                        h.thumb!!.visibility = View.GONE
                        h.title!!.text = item.title
                        h.sub!!.visibility = View.VISIBLE
                        h.sub!!.text = item.body
                        h.itemView.setOnClickListener { item.action() }
                    }
                    is Item.Hint -> {
                        resetBasic(h, ctx, false)
                        h.thumb!!.visibility = View.GONE
                        h.title!!.text = item.title
                        h.sub!!.visibility = View.VISIBLE
                        h.sub!!.text = item.body
                        h.itemView.setOnClickListener { Prefs.dismissHint(item.key); loadAsync() }
                    }
                    is Item.PremiumHint -> {
                        // v1.7.9: premium dark-gradient hint (aurora card, frosted icon
                        // chip, white text) — matches PanelUi.hintBanner's new look.
                        resetBasic(h, ctx, false)
                        h.cardRoot!!.background = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            intArrayOf(0xFF2D2A55.toInt(), 0xFF164E63.toInt())).apply {
                            cornerRadius = dp(20).toFloat()
                        }
                        h.thumb!!.visibility = View.VISIBLE
                        h.thumb!!.layoutParams.width = dp(38)
                        h.thumb!!.layoutParams.height = dp(38)
                        h.thumb!!.background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(0x2EFFFFFF)
                        }
                        h.thumb!!.setPadding(dp(9), dp(9), dp(9), dp(9))
                        h.thumb!!.setImageResource(item.iconRes)
                        h.thumb!!.setImageTintList(csl(0xFFA7F3D0.toInt()))
                        h.thumb!!.scaleType = ImageView.ScaleType.FIT_CENTER
                        h.title!!.text = item.title
                        h.title!!.setTextColor(0xFFF5F3FF.toInt())
                        h.title!!.typeface = Typeface.DEFAULT_BOLD
                        h.sub!!.visibility = View.VISIBLE
                        h.sub!!.text = item.body
                        h.sub!!.setTextColor(0xC7C3E5.toInt())
                        h.itemView.setOnClickListener { Prefs.dismissHint(item.key); loadAsync() }
                    }
                }
            } catch (t: Throwable) {
                // defensive reset: blank row; v1.7.11: the failure is now logged
                // to last_crash.txt so a shared report shows exactly which row
                // binding failed on this device
                writeCrashFile(t, "onBindViewHolder")
                try {
                    resetBasic(h, ctx, false)
                    h.title?.text = ""
                    h.sub?.text = ""
                } catch (_: Throwable) {}
            }
        }

        /** Reset a recycled holder to the base look; grid rows get zoom-aware
         *  text sizes + thumb bounds (v1.7.9 zoom levels). */
        private fun resetBasic(h: VH, ctx: android.content.Context, grid: Boolean) {
            h.thumb!!.visibility = View.VISIBLE
            h.thumb!!.scaleType = ImageView.ScaleType.CENTER   // icons; bitmaps switch to CENTER_CROP
            h.thumb!!.setImageTintList(null)
            h.thumb!!.setImageBitmap(null)
            h.thumb!!.setPadding(dp(10), dp(10), dp(10), dp(10))
            h.thumb!!.background = SettingsUi.rounded(ctx, dp(14))
            // rounded photo corners: clip bitmaps to the rounded background
            h.thumb!!.clipToOutline = true
            h.thumb!!.outlineProvider = ViewOutlineProvider.BACKGROUND
            val lp = h.thumb!!.layoutParams
            if (grid) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = dp(gridThumbDp())
            } else {
                lp.width = dp(listThumbDp())
                lp.height = dp(listThumbDp())
            }
            h.thumb!!.layoutParams = lp
            h.title!!.visibility = View.VISIBLE
            h.sub!!.visibility = View.VISIBLE
            h.title!!.textSize = titleSp(grid)
            h.sub!!.textSize = subSp(grid)
            h.title!!.maxLines = if (grid) 2 else 1
            h.title!!.typeface = Typeface.DEFAULT
            h.title!!.gravity = Gravity.START
            h.progress!!.visibility = View.GONE
            h.actions!!.visibility = View.GONE
            h.actions!!.removeAllViews()
            h.sched?.visibility = View.GONE
            h.more?.visibility = if (grid) View.GONE else View.VISIBLE
            h.itemView.setOnClickListener(null)
            h.itemView.setOnLongClickListener(null)
        }

        private fun typeColor(name: String): Int = when {
            isImageName(name) -> 0xFF10B981.toInt()
            name.substringAfterLast('.', "").lowercase() in VIDEO_EXT -> 0xFFF59E0B.toInt()
            name.substringAfterLast('.', "").lowercase() in AUDIO_EXT -> 0xFF8B5CF6.toInt()
            name.substringAfterLast('.', "").lowercase() == "pdf" -> 0xFFEF4444.toInt()
            name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXT -> 0xFF64748B.toInt()
            name.substringAfterLast('.', "").lowercase() == "apk" -> 0xFF22C55E.toInt()
            else -> tint(R.attr.colorPrimary)
        }

        // ---- filesystem rows ----
        private fun bindFs(h: VH, it: Item.Fs, ctx: android.content.Context) {
            resetBasic(h, ctx, Prefs.filesGrid)
            val f = it.file
            val isDir = f.isDirectory
            val sizeTxt = Formatter.formatFileSize(this@FilesActivity, f.length())
            if (isDir) {
                h.thumb!!.background = SettingsUi.roundedFillAttr(ctx, dp(14))
                h.thumb!!.setImageResource(R.drawable.ic_folder)
                h.thumb!!.setImageTintList(csl(tint(R.attr.colorOnPrimary)))
                h.thumb!!.setPadding(dp(10), dp(10), dp(10), dp(10))
            } else if (isImageName(f.name) && f.length() < 64L * 1024 * 1024) {
                loadThumb(h, "f:" + f.absolutePath, f.absolutePath, null, isVideo = false)
            } else if (f.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT) {
                loadThumb(h, "f:" + f.absolutePath, f.absolutePath, null, isVideo = true)
            } else {
                h.thumb!!.background = SettingsUi.rounded(ctx, dp(14))
                h.thumb!!.setImageResource(fileIconRes(f.name, false))
                h.thumb!!.setImageTintList(csl(typeColor(f.name)))
                h.thumb!!.setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            h.title!!.text = f.name
            h.sub!!.text = when {
                isDir && it.relPath != null -> it.relPath.ifEmpty { getString(R.string.files_root) }
                isDir -> getString(R.string.files_folder)
                it.relPath != null -> it.relPath.ifEmpty { getString(R.string.files_root) } + "  ·  " + sizeTxt
                else -> sizeTxt + "  ·  " + fmt.format(Date(f.lastModified()))
            }
            h.itemView.setOnClickListener {
                if (isDir) { currentDir = f; reloadNow() }
                else {
                    // v1.7.17: builtinMedia routes image/video/audio rows to the
                    // built-in viewers (photo viewer gets this file + its real path)
                    val fu = fileUri(f)
                    val fext = f.name.substringAfterLast('.', "").lowercase()
                    showOpenWithSheet(
                        uri = fu, name = f.name,
                        sub = sizeTxt + "  ·  " + fmt.format(Date(f.lastModified())),
                        file = f,
                        builtinMedia = when {
                            isImageName(f.name) -> 1
                            fext in VIDEO_EXT -> 2
                            fext in AUDIO_EXT -> 3
                            else -> 0
                        },
                        sibUris = listOf(fu.toString()), sibNames = listOf(f.name),
                        sibPaths = listOf(f.absolutePath),
                        onEdit = if (isTextFile(f.name)) { { openTextEditor(f.absolutePath, null) } } else null,
                        onDelete = { moveToTrashFile(f) })
                }
            }
            h.itemView.setOnLongClickListener { fsSheet(f); true }
            h.more?.setOnClickListener { fsSheet(f) }
        }

        /** Long-press / ⋮ for storage entries: dirs keep a compact sheet; files
         *  open the SAME premium open-with sheet as a tap. */
        private fun fsSheet(f: File) {
            if (!f.isDirectory) {
                // v1.7.17: same builtinMedia routing as the plain tap
                val fu = fileUri(f)
                val fext = f.name.substringAfterLast('.', "").lowercase()
                showOpenWithSheet(
                    uri = fu, name = f.name,
                    sub = Formatter.formatFileSize(this@FilesActivity, f.length()) + "  ·  " +
                        fmt.format(Date(f.lastModified())),
                    file = f,
                    builtinMedia = when {
                        isImageName(f.name) -> 1
                        fext in VIDEO_EXT -> 2
                        fext in AUDIO_EXT -> 3
                        else -> 0
                    },
                    sibUris = listOf(fu.toString()), sibNames = listOf(f.name),
                    sibPaths = listOf(f.absolutePath),
                    onEdit = if (isTextFile(f.name)) { { openTextEditor(f.absolutePath, null) } } else null,
                    onDelete = { moveToTrashFile(f) })
                return
            }
            val sheet = BottomSheetDialog(this@FilesActivity)
            val col = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(12), dp(8), dp(20)) }
            fun sRow(iconRes: Int, label: String, fn: () -> Unit) {
                col.addView(LinearLayout(this@FilesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(13), dp(12), dp(13))
                    isClickable = true
                    background = MenuSheets.ripple(this@FilesActivity)
                    setOnClickListener { sheet.dismiss(); fn() }
                    addView(ImageView(this@FilesActivity).apply {
                        setImageResource(iconRes); layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        imageTintList = csl(tint(R.attr.colorPrimary))
                    })
                    addView(TextView(this@FilesActivity).apply {
                        text = label; textSize = 15f; setPadding(dp(14), 0, 0, 0)
                        setTextColor(csl(tint(R.attr.colorOnSurface)))
                    })
                })
            }
            sRow(R.drawable.ic_edit, getString(R.string.files_rename)) { renameFile(f) }
            sRow(R.drawable.ic_delete, getString(R.string.delete)) { moveToTrashFile(f) }
            sheet.setContentView(col)
            sheet.show()
        }

        // ---- media rows ----
        private fun bindMedia(h: VH, mi: Item.Media, ctx: android.content.Context) {
            resetBasic(h, ctx, Prefs.filesGrid)
            // v1.7.15: the media PATH is passed too — the decode fallback chain
            // needs it to frame videos via MediaMetadataRetriever when the
            // system thumbnail service times out on the first bind.
            loadThumb(h, "m:" + mi.uri, mi.path.ifEmpty { null }, mi.uri, isVideo = mi.isVideo)
            h.title!!.text = mi.name
            h.sub!!.text = (if (mi.isImage) getString(R.string.files_image) else getString(R.string.files_video)) +
                "  ·  " + Formatter.formatFileSize(this@FilesActivity, mi.size) + "  ·  " + fmt.format(Date(mi.added))
            val uri = Uri.parse(mi.uri)
            val folder = if (mi.path.isNotEmpty() && File(mi.path).parentFile?.exists() == true && hasAllFiles())
                File(mi.path).parentFile else null
            h.itemView.setOnClickListener { mediaOpen(mi, uri, folder) }
            h.itemView.setOnLongClickListener { mediaOpen(mi, uri, folder); true }
            h.more?.setOnClickListener { mediaOpen(mi, uri, folder) }
        }

        /** v1.7.9: tapping media asks WHICH APP to use (user request); the
         *  built-in Zephyr viewer stays available as a sheet row.
         *  v1.7.17: the sheet's prominent row routes images to the new photo
         *  viewer with a SIBLING WINDOW — all adapter items of the same kind
         *  (images vs video), capped at 120 centered on the tapped one, so
         *  swiping in the viewer walks the whole camera roll. */
        private fun mediaOpen(mi: Item.Media, uri: Uri, folder: File?) {
            val sibUris = ArrayList<String>(items.size)
            val sibNames = ArrayList<String>(items.size)
            val sibPaths = ArrayList<String>(items.size)
            var sibIndex = 0
            try {
                val same = ArrayList<Item.Media>(items.size)
                for (it in items) {
                    val m = it as? Item.Media ?: continue
                    if (m.isImage == mi.isImage) same.add(m)
                }
                var cur = 0
                for ((k, m) in same.withIndex()) {
                    if (m.uri == mi.uri && m.id == mi.id) { cur = k; break }
                }
                var start = (cur - 60).coerceAtLeast(0)
                val end = minOf(start + 120, same.size)
                start = (end - 120).coerceAtLeast(0)
                sibIndex = cur - start
                for (k in start until end) {
                    val m = same[k]
                    sibUris.add(m.uri); sibNames.add(m.name); sibPaths.add(m.path)
                }
            } catch (_: Throwable) { sibUris.clear(); sibNames.clear(); sibPaths.clear(); sibIndex = 0 }
            if (sibUris.isEmpty()) {
                // the tapped item must ALWAYS be present at sibIndex
                sibUris.add(mi.uri); sibNames.add(mi.name); sibPaths.add(mi.path); sibIndex = 0
            }
            showOpenWithSheet(
                uri = uri, name = mi.name,
                sub = (if (mi.isImage) getString(R.string.files_image) else getString(R.string.files_video)) +
                    "  ·  " + Formatter.formatFileSize(this@FilesActivity, mi.size) + "  ·  " + fmt.format(Date(mi.added)),
                builtinMedia = if (mi.isImage) 1 else 2,
                sibUris = sibUris, sibNames = sibNames, sibPaths = sibPaths, sibIndex = sibIndex,
                onEdit = if (isTextFile(mi.name)) { { openTextEditor(null, uri) } } else null,
                onDelete = { moveToTrashMedia(uri, mi.name, mi.size, mi.path) },
                showInFolder = folder)
        }

        // ---- download-manager rows ----
        private fun bindDm(h: VH, it: Item.Dm, ctx: android.content.Context) {
            resetBasic(h, ctx, false)
            val e = it.entry
            val live = it.live
            val running = live != null && !live.waiting && !live.paused
            val waiting = live?.waiting == true
            val paused = e.status == 3 || live?.paused == true
            h.thumb!!.background = SettingsUi.rounded(ctx, dp(14))
            h.thumb!!.setImageResource(fileIconRes(e.fileName, false))
            h.thumb!!.setImageTintList(csl(typeColor(e.fileName)))
            h.thumb!!.setPadding(dp(10), dp(10), dp(10), dp(10))
            h.title!!.text = e.fileName.ifEmpty { e.url.substringAfter("//").take(40) }

            val sizeTxt = Formatter.formatFileSize(this@FilesActivity,
                if (live != null && live.total > 0) live.total else if (e.size > 0) e.size
                else if (e.totalBytes > 0) e.totalBytes else e.downloaded)
            h.sub!!.text = when {
                running && live != null && live.total > 0 -> getString(R.string.dm_progress,
                    Formatter.formatFileSize(this@FilesActivity, live.downloaded), sizeTxt)
                running -> getString(R.string.dm_running)
                // v1.7.9: the engine tells us WHY the row waits (Wi-Fi / night)
                waiting -> live?.reason?.takeIf { r -> r.isNotEmpty() } ?: getString(R.string.dm_queued)
                paused -> getString(R.string.dm_paused,
                    if (e.totalBytes > 0) (e.downloaded * 100 / e.totalBytes).toInt() else 0)
                e.status == 1 -> getString(R.string.dm_done, sizeTxt, fmt.format(Date(e.addedAt)))
                e.status == 2 -> getString(R.string.dm_failed)
                e.status == 4 -> getString(R.string.dm_cancelled)
                else -> getString(R.string.dm_running)
            }

            val showProgress = (running && live != null && live.total > 0) ||
                (paused && e.totalBytes > 0)
            if (showProgress) {
                h.progress!!.visibility = View.VISIBLE
                val done = live?.downloaded ?: e.downloaded
                val total = if (live != null && live.total > 0) live.total else e.totalBytes
                h.progress!!.progress = if (total > 0) (done * 100 / total).toInt() else 0
            }

            // action buttons (list mode only — DM rows are always full-span)
            val acts = h.actions!!
            acts.visibility = View.VISIBLE
            acts.removeAllViews()
            fun act(iconRes: Int, desc: String, onClick: () -> Unit) {
                acts.addView(ImageButton(ctx).apply {
                    setImageResource(iconRes)
                    background = MenuSheets.ripple(ctx)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    imageTintList = csl(tint(R.attr.colorOnSurfaceVariant))
                    contentDescription = desc
                    setOnClickListener { onClick() }
                })
            }
            when {
                running -> {
                    act(R.drawable.ic_pause, getString(R.string.dm_pause)) { DownloadEngine.pause(e.id); dmDirty = true }
                    act(R.drawable.ic_close, getString(R.string.dm_cancel)) { DownloadEngine.cancel(e.id) }
                }
                waiting -> {
                    act(R.drawable.ic_arrow_up, getString(R.string.dm_bump)) {
                        DownloadEngine.bump(e.id); Toast.makeText(ctx, R.string.dm_bumped, Toast.LENGTH_SHORT).show()
                    }
                    act(R.drawable.ic_close, getString(R.string.dm_cancel)) { DownloadEngine.cancel(e.id) }
                }
                paused -> {
                    act(R.drawable.ic_player_play, getString(R.string.dm_resume)) { DownloadEngine.resume(e.id) }
                    act(R.drawable.ic_replay, getString(R.string.dm_retry)) { DownloadEngine.retry(e.id) }
                    act(R.drawable.ic_close, getString(R.string.dm_cancel)) { DownloadEngine.cancel(e.id) }
                }
                e.status == 2 || e.status == 4 -> {
                    act(R.drawable.ic_replay, getString(R.string.dm_retry)) { DownloadEngine.retry(e.id) }
                    act(R.drawable.ic_delete, getString(R.string.delete)) { deleteDmRow(e) }
                }
                else -> { // done → same open-with sheet as everywhere else
                    h.itemView.setOnClickListener { openDmFile(e) }
                    h.itemView.setOnLongClickListener { dmSheet(e); true }
                    h.more?.visibility = View.GONE
                    act(R.drawable.ic_share, getString(R.string.share)) {
                        if (e.path.isNotEmpty()) {
                            val uri = if (e.path.startsWith("/")) fileUri(File(e.path)) else Uri.parse(e.path)
                            shareUri(uri, e.fileName)
                        }
                    }
                    act(R.drawable.ic_more_vert, getString(R.string.open)) { dmSheet(e) }
                }
            }
            if (e.status != 1) {
                h.itemView.setOnLongClickListener { dmSheet(e); true }
                h.more?.visibility = View.GONE
            }
        }

        // ---- scheduler card (Wi-Fi / night) ----
        private fun bindSched(h: VH, ctx: android.content.Context) {
            resetBasic(h, ctx, false)
            h.thumb!!.visibility = View.GONE
            h.title!!.text = getString(R.string.dm_scheduler)
            h.title!!.textSize = 15f
            h.title!!.typeface = Typeface.DEFAULT_BOLD
            h.sub!!.visibility = View.GONE
            h.more?.visibility = View.GONE
            h.itemView.isClickable = false
            var box = h.sched
            if (box == null) {
                box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                    }
                }
                h.textCol?.addView(box)
                h.sched = box
            }
            box.visibility = View.VISIBLE
            box.removeAllViews()
            // v1.7.10 hardening: a theme/accent edge case in switchRow must not
            // kill rendering — on failure the card just shows without switches.
            try {
                box.addView(SettingsUi.switchRow(ctx, R.drawable.ic_cloud,
                    getString(R.string.dm_wifi_only), getString(R.string.dm_wifi_only_sum),
                    Prefs.dmWifiOnly) { on -> Prefs.setDmWifiOnly(on) })
                box.addView(SettingsUi.switchRow(ctx, R.drawable.ic_moon,
                    getString(R.string.dm_night_only), getString(R.string.dm_night_only_sum),
                    Prefs.dmNightOnly) { on -> Prefs.setDmNightOnly(on) })
            } catch (_: Throwable) {}
        }

        private fun deleteDmRow(e: DownloadEntry) {
            ioLaunch {
                try { db().downloadDao().delete(e) } catch (_: Exception) {}
                main.post { dmDirty = true; loadAsync() }
            }
        }

        private fun dmSheet(e: DownloadEntry) {
            // v1.7.11: armored like every other sheet builder
            safeSheet("dmSheet") {
            val sheet = BottomSheetDialog(this@FilesActivity)
            val col = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(12), dp(8), dp(20)) }
            fun sRow(iconRes: Int, label: String, fn: () -> Unit) {
                col.addView(LinearLayout(this@FilesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(13), dp(12), dp(13))
                    isClickable = true
                    background = MenuSheets.ripple(this@FilesActivity)
                    setOnClickListener { sheet.dismiss(); fn() }
                    addView(ImageView(this@FilesActivity).apply {
                        setImageResource(iconRes); layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        imageTintList = csl(tint(R.attr.colorPrimary))
                    })
                    addView(TextView(this@FilesActivity).apply {
                        text = label; textSize = 15f; setPadding(dp(14), 0, 0, 0)
                        setTextColor(csl(tint(R.attr.colorOnSurface)))
                    })
                })
            }
            if (e.status == 1 && e.path.isNotEmpty()) {
                sRow(R.drawable.ic_open_new, getString(R.string.open)) { openDmFile(e) }
                sRow(R.drawable.ic_share, getString(R.string.share)) {
                    val uri = if (e.path.startsWith("/")) fileUri(File(e.path)) else Uri.parse(e.path)
                    shareUri(uri, e.fileName)
                }
            }
            if (e.url.startsWith("http") && e.status != 1) {
                sRow(R.drawable.ic_replay, getString(R.string.dm_retry)) { DownloadEngine.retry(e.id) }
            }
            sRow(R.drawable.ic_copy, getString(R.string.ctx_copy)) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", e.url))
                Toast.makeText(this@FilesActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            sRow(R.drawable.ic_delete, getString(R.string.delete)) {
                if (e.status == 0 || e.status == 3) DownloadEngine.cancel(e.id)
                deleteDmRow(e)
            }
            sheet.setContentView(col)
            sheet.show()
            }
        }

        // ---- thumbnails ----

        /** v1.7.9 SPEED FIX: the tag is set BEFORE the decode is launched (the
         *  old code set it after thumbExecutor.execute → recycled rows showed
         *  wrong photos), the cache is checked with the tag too, and image vs
         *  video decodes run on separate pools.
         *  v1.7.16: RecyclerView fling PREFETCH floods those pools with
         *  off-screen binds (GapWorker binds ahead during flings) — every task
         *  is now wrapped in Seq(...) so the NEWEST bind decodes FIRST (LIFO):
         *  the visible cells jump the queue, stale prefetch work sinks.
         *  v1.7.17 (user: «миниатюры не появляются сразу и сами что-то не то»):
         *  the REAL root cause of the "scroll dance" was never queueing — the
         *  placeholder sets a TINT LIST (ic_image + colorOnSurfaceVariant), and
         *  ImageView re-applies its tint to EVERY drawable it is given: the
         *  first async completion swapped the glyph for the real bitmap but the
         *  tint was STILL ACTIVE → the photo rendered as a solid GRAY SLAB.
         *  Scrolling away and back ran resetBasic (which clears the tint) and
         *  the cache-hit path then showed the photo correctly. The tint is now
         *  cleared BEFORE setImageBitmap on the completion path (and on the
         *  cache-hit path as belt-and-braces). Rounds 12-b/13-b chased a
         *  queueing ghost — that diagnosis is retired. */
        private fun loadThumb(h: VH, key: String, path: String?, uri: String?, isVideo: Boolean) {
            val iv = h.thumb ?: return
            iv.tag = key
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.background = SettingsUi.rounded(iv.context, dp(14))
            iv.setPadding(0, 0, 0, 0)
            thumbCache.get(key)?.let { bmp ->
                // v1.7.17: clear any stale tint before the cached bitmap too —
                // same ImageView-tint mechanism as the decode path below.
                iv.setImageTintList(null)
                if (iv.tag == key) iv.setImageBitmap(bmp)   // instant, no flicker
                return
            }
            iv.setPadding(dp(10), dp(10), dp(10), dp(10))
            iv.setImageResource(R.drawable.ic_image)
            iv.setImageTintList(csl(tint(R.attr.colorOnSurfaceVariant)))
            val exec = if (isVideo) vidExecutor else imgExecutor
            val task = Runnable {
                val bmp = decodeThumb(key, path, uri, isVideo)
                if (bmp != null) thumbCache.put(key, bmp)
                main.post {
                    if (bmp != null && !isFinishing && iv.tag == key) {
                        iv.setPadding(0, 0, 0, 0)
                        // v1.7.17: MUST clear the placeholder tint BEFORE the
                        // bitmap — ImageView re-tints every incoming drawable,
                        // without this the first load paints a gray rectangle.
                        iv.setImageTintList(null)
                        iv.setImageBitmap(bmp)
                    }
                }
            }
            exec.execute(Seq(seqGen.incrementAndGet(), task))
        }

        private fun decodeThumb(key: String, path: String?, uri: String?, isVideo: Boolean = false): Bitmap? {
            // v1.7.15 (user: «листаю вниз — миниатюры не загрузились, вверх-вниз —
            // загрузились»): contentResolver.loadThumbnail triggers system thumbnail
            // GENERATION on first access — on busy media providers it times out and
            // returns null, leaving blank cells until a rebind retries. A fallback
            // chain now guarantees a bitmap on the FIRST bind.
            // v1.7.16 (round 2: «появляются, только когда их не видно на экране»):
            // order = cached-or-cheap first, the system GENERATOR LAST. The
            // generator is slow/hang-prone when the mini-thumbnail was never
            // created; the raw decode always works in bounded time, so the
            // first bind now fills the cell.
            // v1.7.17: video frames at t=0 are usually a black encoder slate —
            // both retriever branches now read METADATA_KEY_DURATION and aim at
            // duration/4 (us = durMs * 250, fallback 0) so the thumbnail shows a
            // representative frame instead of black.
            if (uri != null) {
                val u = Uri.parse(uri)
                val id = try { u.lastPathSegment?.toLongOrNull() } catch (_: Throwable) { null }
                // 1) legacy thumbnail cache by id — fast, reads the existing mini thumb
                try {
                    if (id != null) {
                        val t = if (isVideo)
                            MediaStore.Video.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                        else
                            MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                        if (t != null) return t
                    }
                } catch (_: Throwable) {}
                // 2) videos with a real file path — frame via retriever (bounded);
                //    v1.7.17: aim at duration/4 — frame 0 is usually a black slate
                if (isVideo && !path.isNullOrEmpty()) {
                    try {
                        val mr = android.media.MediaMetadataRetriever()
                        try {
                            mr.setDataSource(path)
                            val us = try {
                                (mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull() ?: 0L) * 250L
                            } catch (_: Throwable) { 0L }
                            val f = if (Build.VERSION.SDK_INT >= 27)
                                mr.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 140, 140)
                            else mr.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (f != null) return f
                        } finally { try { mr.release() } catch (_: Exception) {} }
                    } catch (_: Throwable) {}
                }
                // 3) images — sampled decode, either from the file path (cheapest) or
                //    the content stream; ALWAYS succeeds in bounded time
                if (!isVideo) {
                    if (!path.isNullOrEmpty()) {
                        try {
                            val f = java.io.File(path)
                            if (f.exists()) {
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(path, opts)
                                val s = sampled(opts.outWidth, opts.outHeight, 140)
                                val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
                                if (bmp != null) return bmp
                            }
                        } catch (_: Throwable) {}
                    }
                    try { contentResolver.openInputStream(u)?.use { decodeSampled(it, 140) }?.let { return it } } catch (_: Throwable) {}
                }
                // 4) LAST resort — the system generator (may be slow on first access)
                if (Build.VERSION.SDK_INT >= 29) {
                    try { contentResolver.loadThumbnail(u, android.util.Size(140, 140), null)?.let { return it } } catch (_: Throwable) {}
                }
                return null
            }
            // path-based branch (File items) — unchanged, still guarded so an
            // OEM/MediaMetadataRetriever throw can never kill the pool thread
            return try { path?.let { p ->
                val f = File(p)
                if (!f.exists()) null
                else {
                    if (p.substringAfterLast('.', "").lowercase() in VIDEO_EXT) {
                        val mr = android.media.MediaMetadataRetriever()
                        try {
                            mr.setDataSource(p)
                            // v1.7.17: duration/4 instead of the black t=0 slate
                            val us = try {
                                (mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull() ?: 0L) * 250L
                            } catch (_: Throwable) { 0L }
                            if (Build.VERSION.SDK_INT >= 27)
                                mr.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 140, 140)
                            else mr.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally { try { mr.release() } catch (_: Exception) {} }
                    } else {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(p, opts)
                        val s = sampled(opts.outWidth, opts.outHeight, 140)
                        BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = s })
                    }
                }
            } } catch (_: Exception) { null }
        }

        private fun decodeSampled(ins: java.io.InputStream, target: Int): Bitmap? = try {
            val data = ins.readBytes()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            val s = sampled(opts.outWidth, opts.outHeight, target)
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Exception) { null }

        private fun sampled(w: Int, h: Int, target: Int): Int {
            var s = 1
            var maxDim = maxOf(w, h)
            while (maxDim / s > target * 2) s *= 2
            return s
        }
    }

    private fun ioLaunch(block: suspend () -> Unit) {
        // v1.7.11: an uncaught throw inside launch on Dispatchers.IO kills the
        // whole process — every ioLaunch body is caught here instead, logged to
        // last_crash.txt, and the screen keeps working.
        ioScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (t: Throwable) {
                writeCrashFile(t, "ioLaunch")
            }
        }
    }

    private fun tint(attr: Int): Int = SettingsUi.color(this, attr)
    private fun csl(color: Int) = ColorStateList.valueOf(color)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
