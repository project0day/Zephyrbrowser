package com.zephyr.browser.viewer

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * v1.7.17: built-in fullscreen PHOTO VIEWER (user: «смотри фото прямо в
 * Zephyr»). Replaces the old one-off openImagePreview dialog from Files.
 *
 * Intent contract (started from FilesActivity / the Downloads panel):
 *  - putStringArrayListExtra("uris",  …)  content:// or file-provider URIs
 *  - putStringArrayListExtra("names", …)  display names, same order
 *  - putIntExtra("index", i)              starting index
 *  - putStringArrayListExtra("paths", …)  OPTIONAL real file paths (empty
 *    string when unknown; used for the Info sheet and file deletion)
 *  - putExtra(Intent.EXTRA_TITLE, name)   for the transition
 *
 * Features: v1.7.19 rigid pinch zoom 1x–8x (absolute span ratio, image glued
 * to the fingers, spring-back on release), double-tap 1x ↔ 2.5x,
 * horizontal swipe/fling switches photos with a slide animation (only at
 * scale 1), single tap toggles the top bar, Info bottom sheet (dimensions /
 * size / path / date), share, delete (MediaStore row or real file) with
 * neighbor-follow, animated GIF/WebP via ImageDecoder on API 28+, sampled
 * bitmap decode ≤2048px elsewhere, keep-screen-on, and the house armor
 * convention — nothing in this screen may crash the process.
 */
class PhotoViewerActivity : AppCompatActivity() {

    private val uris = ArrayList<String>()
    private val names = ArrayList<String>()
    private val paths = ArrayList<String>()
    private var index = 0

    private lateinit var root: FrameLayout
    private lateinit var zoomView: ZoomImage
    private lateinit var topBar: LinearLayout
    private lateinit var nameView: TextView
    private lateinit var counterView: TextView
    private lateinit var spinner: ProgressBar

    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    /** Monotonic decode generation — a stale decode result must not clobber
     *  a photo the user already swiped to. */
    private var decodeGen = 0
    private var lastW = 0
    private var lastH = 0
    private var barsShown = true
    /** One slide animation at a time — a second swipe mid-animation would
     *  fight the running animator and desync index vs. view. */
    private var slideBusy = false
    private val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
        } catch (_: Throwable) {
            finish()
            return
        }
        // v1.7.17 armor convention: ANY failure in the build path degrades to
        // a toast + finish instead of an uncaught crash.
        try {
            buildUi(savedInstanceState)
        } catch (t: Throwable) {
            try { Toast.makeText(this, R.string.pv_load_error, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            finish()
        }
    }

    private fun buildUi(savedInstanceState: Bundle?) {
        val black = 0xFF000000.toInt()
        window.statusBarColor = black
        window.navigationBarColor = black
        // immersive (the PlayerActivity pattern): hidden system bars +
        // FLAG_FULLSCREEN as the OEM-proof wall + black window background.
        try {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {}
        try {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(black))
        } catch (_: Exception) {}

        // ---- intent contract ----
        val i = intent ?: throw IllegalStateException("no intent")
        i.getStringArrayListExtra("uris")?.let { uris.addAll(it) }
        if (uris.isEmpty()) throw IllegalStateException("empty uri list")
        i.getStringArrayListExtra("names")?.let { names.addAll(it) }
        i.getStringArrayListExtra("paths")?.let { paths.addAll(it) }
        while (names.size < uris.size) names.add("")
        while (paths.size < uris.size) paths.add("")
        val fromIntent = i.getIntExtra("index", 0).coerceIn(0, uris.size - 1)
        index = (savedInstanceState?.getInt("pvIndex", fromIntent) ?: fromIntent)
            .coerceIn(0, uris.size - 1)

        executor = Executors.newSingleThreadExecutor()

        // ---- root + zoomable image ----
        root = FrameLayout(this).apply { setBackgroundColor(black) }
        zoomView = ZoomImage(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            contentDescription = getString(R.string.pv_photo)
            onTap = { toggleBars() }
            onSwipe = { dir -> go(dir) }
        }
        root.addView(zoomView)

        // decode spinner (white, centered)
        spinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            visibility = View.GONE
        }
        root.addView(spinner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // ---- top bar (gradient scrim like the player) ----
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = gradientScrim()
            elevation = dp(6).toFloat()
        }
        // v1.7.18: proper BACK arrow — the old ic_player_back was a skip-to-
        // previous glyph and looked like «отмотать» (user bug report)
        topBar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.pl_back)
            setOnClickListener { finish() }
        })
        nameView = TextView(this).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(nameView)
        counterView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xB3FFFFFF.toInt())   // white 70%
            setPadding(dp(8), 0, dp(8), 0)
        }
        topBar.addView(counterView)
        fun barBtn(iconRes: Int, desc: String, onClick: () -> Unit) {
            topBar.addView(ImageButton(this).apply {
                setImageResource(iconRes)
                background = null
                setPadding(dp(10), dp(10), dp(10), dp(10))
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                contentDescription = desc
                setOnClickListener { onClick() }
            })
        }
        barBtn(R.drawable.ic_info, getString(R.string.pv_info)) { showInfo() }
        barBtn(R.drawable.ic_share, getString(R.string.share)) { shareCurrent() }
        barBtn(R.drawable.ic_delete, getString(R.string.delete)) { confirmDelete() }
        barBtn(R.drawable.ic_more_vert, getString(R.string.files_open_with)) { moreMenu() }
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        setContentView(root)

        applyChrome()
        loadCurrent()
    }

    // ------------------------------------------------------------- chrome

    private fun applyChrome() {
        nameView.text = names.getOrNull(index) ?: ""
        counterView.text = "${index + 1} / ${uris.size}"
        title = nameView.text
    }

    private fun toggleBars() {
        barsShown = !barsShown
        topBar.visibility = if (barsShown) View.VISIBLE else View.GONE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // dialogs can resurrect the bars — re-assert immersive
        if (hasFocus && !isFinishing) {
            try {
                val c = WindowInsetsControllerCompat(window, window.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } catch (_: Exception) {}
            try { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------- decode

    /** One decoded photo (drawable + pixel size; AnimatedImageDrawable carries
     *  its own size so the Info sheet works for GIFs too). */
    private class Dec(val drawable: android.graphics.drawable.Drawable, val w: Int, val h: Int)

    private fun loadCurrent() {
        val uriStr = uris.getOrNull(index) ?: return
        val gen = ++decodeGen
        spinner.visibility = View.VISIBLE
        val exec = executor ?: return
        exec.execute {
            val res = try { decode(Uri.parse(uriStr)) } catch (_: Throwable) { null }
            main.post {
                if (isFinishing || gen != decodeGen) return@post
                spinner.visibility = View.GONE
                if (res != null) {
                    lastW = res.w
                    lastH = res.h
                    zoomView.setImageDrawable(res.drawable)
                    // animated GIF / WebP (API 28+ path) — start the loop.
                    // The SDK gate MUST come first: the instanceof would throw
                    // NoClassDefFoundError on < 28 where the class is absent.
                    if (Build.VERSION.SDK_INT >= 28 &&
                        res.drawable is android.graphics.drawable.AnimatedImageDrawable) {
                        try { (res.drawable as android.graphics.drawable.AnimatedImageDrawable).start() } catch (_: Exception) {}
                    }
                } else {
                    lastW = 0
                    lastH = 0
                    zoomView.setImageDrawable(null)
                    try { Toast.makeText(this@PhotoViewerActivity, R.string.pv_load_error, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun decode(uri: Uri): Dec? {
        // API 28+: ImageDecoder — the ONLY way to get animated GIF/WebP into a
        // plain ImageView; sampled to ≤2048px so 50MP shots stay in memory.
        // v1.7.20 FIX (user: «при де-зуме фото разделяется на две и улетают в
        // разные края»): decodeDrawable defaults to a HARDWARE bitmap, and on a
        // number of GPUs a heavily scaled hardware bitmap (view zoom up to 8x →
        // a >16k px quad) is tiled by the driver — the tiles get misplaced and
        // the photo visibly tears into fragments / duplicates. ALLOCATOR_SOFTWARE
        // forces an ordinary bitmap: one texture, one quad, nothing to tear.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                val d = android.graphics.ImageDecoder.decodeDrawable(src) { dec, info, _ ->
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > 2048) dec.setTargetSampleSize(ceil(maxDim / 2048.0).toInt())
                }
                val w = d.intrinsicWidth
                val h = d.intrinsicHeight
                if (w > 0 && h > 0) return Dec(d, w, h)
            } catch (_: Throwable) {}
        }
        // static bitmap fallback (also the GIF first frame below API 28)
        return try {
            contentResolver.openInputStream(uri)?.use { ins ->
                val data = ins.readBytes()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
                var s = 1
                while (maxOf(opts.outWidth, opts.outHeight) / s > 2048) s *= 2
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size,
                    BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
                Dec(android.graphics.drawable.BitmapDrawable(resources, bmp), bmp.width, bmp.height)
            }
        } catch (_: Throwable) { null }
    }

    // ------------------------------------------------------------- navigation

    /** dir = +1 → next photo, -1 → previous. Slide out, load, slide in. */
    private fun go(dir: Int) {
        if (slideBusy) return
        val ni = index + dir
        if (ni !in uris.indices) {
            // edge bounce so the swipe does not feel dead
            val nudge = dp(28).toFloat()
            zoomView.animate()
                .translationX(if (dir > 0) -nudge else nudge)
                .setDuration(90)
                .withEndAction {
                    zoomView.animate().translationX(0f).setDuration(140).start()
                }.start()
            return
        }
        slideBusy = true
        val w = maxOf(root.width, dp(240)).toFloat()
        zoomView.animate()
            .translationX(if (dir > 0) -w / 3f else w / 3f)
            .alpha(0f)
            .setDuration(130)
            .withEndAction {
                slideBusy = false
                index = ni
                applyChrome()
                zoomView.resetZoom()
                loadCurrent()
                zoomView.translationX = if (dir > 0) w / 3f else -w / 3f
                zoomView.animate().translationX(0f).alpha(1f).setDuration(130).start()
            }.start()
    }

    // ------------------------------------------------------------- actions

    private fun mimeOf(uri: Uri, name: String): String {
        val t = try { contentResolver.getType(uri) } catch (_: Exception) { null }
        if (!t.isNullOrEmpty() && t != "application/octet-stream") return t
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "jpe" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            else -> "image/*"
        }
    }

    private fun shareCurrent() {
        try {
            val u = Uri.parse(uris[index])
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(u, names.getOrNull(index) ?: "")
                putExtra(Intent.EXTRA_STREAM, u)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, names.getOrNull(index) ?: ""))
        } catch (_: Exception) {
            try { Toast.makeText(this, R.string.files_no_app, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    private fun moreMenu() {
        try {
            val anchor = topBar.getChildAt(topBar.childCount - 1) ?: return
            val pm = android.widget.PopupMenu(this, anchor)
            pm.menu.add(0, 1, 0, getString(R.string.files_open_with))
            pm.setOnMenuItemClickListener {
                if (it.itemId == 1) {
                    try {
                        val u = Uri.parse(uris[index])
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(u, mimeOf(u, names.getOrNull(index) ?: ""))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(view, names.getOrNull(index) ?: ""))
                    } catch (_: Exception) {
                        try { Toast.makeText(this, R.string.files_no_app, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                    }
                }
                true
            }
            pm.show()
        } catch (_: Exception) {}
    }

    private fun confirmDelete() {
        try {
            // v1.7.18: the dialog window hides the system bars too — its focus
            // must not resurrect them over the photo (the sheet-shift fix).
            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pv_delete_q)
                .setPositiveButton(R.string.delete) { _, _ -> deleteCurrent() }
                .setNegativeButton(R.string.cancel, null)
                .create()
            dlg.setOnShowListener { goImmersive(dlg.window) }
            dlg.show()
        } catch (_: Exception) {}
    }

    private fun deleteCurrent() {
        val uriStr = uris.getOrNull(index) ?: return
        val path = paths.getOrNull(index) ?: ""
        val pos = index
        val exec = executor ?: return
        exec.execute {
            var ok = false
            try {
                val u = Uri.parse(uriStr)
                when {
                    // MediaStore row (media tab) — delete through the provider
                    u.scheme == "content" -> {
                        val n = try { contentResolver.delete(u, null, null) } catch (_: Exception) { 0 }
                        ok = n > 0
                        if (!ok && path.isNotEmpty()) {
                            val f = File(path)
                            if (f.exists()) ok = f.delete()
                        }
                    }
                    u.scheme == "file" -> ok = try { File(u.path ?: "").delete() } catch (_: Exception) { false }
                    else -> if (path.isNotEmpty()) {
                        val f = File(path)
                        if (f.exists()) ok = f.delete()
                    }
                }
            } catch (_: Exception) {}
            main.post {
                if (isFinishing) return@post
                if (!ok) {
                    try { Toast.makeText(this@PhotoViewerActivity, R.string.files_delete_failed, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                    return@post
                }
                try { Toast.makeText(this@PhotoViewerActivity, R.string.pv_deleted, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                if (pos < uris.size) { uris.removeAt(pos); names.removeAt(pos); paths.removeAt(pos) }
                if (uris.isEmpty()) { finish(); return@post }
                // the user may have swiped while the background delete ran —
                // keep the CURRENT photo under the user, not its old neighbor
                if (pos < index) index--
                index = index.coerceIn(0, uris.size - 1)
                zoomView.resetZoom()
                applyChrome()
                loadCurrent()
            }
        }
    }

    // ------------------------------------------------------------- info sheet

    private fun fileSizeOf(uri: Uri): String {
        try {
            val p = paths.getOrNull(index) ?: ""
            if (p.isNotEmpty()) {
                val f = File(p)
                if (f.exists()) return Formatter.formatFileSize(this, f.length())
            }
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                return Formatter.formatFileSize(this, fd.length)
            }
        } catch (_: Exception) {}
        return "—"
    }

    private fun dateOf(uri: Uri): String {
        try {
            val p = paths.getOrNull(index) ?: ""
            if (p.isNotEmpty()) {
                val f = File(p)
                if (f.exists() && f.lastModified() > 0) return fmt.format(Date(f.lastModified()))
            }
            contentResolver.query(uri, arrayOf("date_modified"), null, null, null)?.use { c ->
                val idx = c.getColumnIndex("date_modified")
                if (idx >= 0 && c.moveToFirst()) {
                    val sec = c.getLong(idx)
                    if (sec > 0) return fmt.format(Date(sec * 1000L))
                }
            }
        } catch (_: Exception) {}
        return "—"
    }

    private fun showInfo() {
        try {
            val uri = Uri.parse(uris[index])
            val name = names.getOrNull(index) ?: ""
            val sheet = BottomSheetDialog(this)
            // v1.7.18 FIX (user: «при выезде окна sheets фото на заднем плане
            // поднимается на чуть-чуть»): the DIALOG window is not immersive, so
            // the status bar reappeared while the sheet had focus and the
            // activity window re-inset — the photo jumped. The sheet's own
            // window hides the system bars too → no re-layout, no shift.
            sheet.setOnShowListener { d -> goImmersive((d as? BottomSheetDialog)?.window) }
            val scroll = NestedScrollView(this)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(14), dp(8), dp(24))
            }
            scroll.addView(col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(TextView(this).apply {
                text = getString(R.string.pv_info)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(0xFFFFFFFF.toInt())
            })
            fun row(label: String) {
                col.addView(TextView(this).apply {
                    text = label
                    textSize = 13.5f
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    setTextColor(0xE6FFFFFF.toInt())
                    setTextIsSelectable(true)
                })
            }
            row(name)
            row(if (lastW > 0 && lastH > 0) getString(R.string.pv_dimens, lastW, lastH) else "—")
            row(getString(R.string.pv_filesize, fileSizeOf(uri)))
            row(getString(R.string.pv_path,
                (paths.getOrNull(index)?.takeIf { it.isNotEmpty() }) ?: uri.toString()))
            row(getString(R.string.pv_date, dateOf(uri)))
            sheet.setContentView(scroll)
            sheet.show()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------- chrome helper

    /** v1.7.18: hides the system bars on a SECONDARY window (bottom sheet,
     *  dialog) so their focus never resurrects the bars over the photo. */
    private fun goImmersive(win: android.view.Window?) {
        try {
            if (win == null) return
            val c = WindowInsetsControllerCompat(win, win.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------- state

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try { outState.putInt("pvIndex", index) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { executor?.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------- zoomable view

    /**
     * Zoomable photo view — v1.7.19: gesture layer fully REWRITTEN.
     *
     * Why: 1.7.18 still sat on ScaleGestureDetector. Its zoom is incremental
     * (scale × scaleFactor every tick → finger wobble accumulates into a
     * visible tremor), it refuses to start scaling until the span grows past
     * the framework's ~27 mm min-scaling-span (zoom feels stiff/dead), and
     * clampPan() fought the focus transform on every frame at the edges
     * (jitter). On top of that GestureDetector's double-tap fired when a
     * finger landed right after a pinch, yanking the photo to 2.5x mid-gesture.
     *
     * New model — rigid, absolute, zero-threshold:
     *  • zoom = ABSOLUTE span ratio (spanNow / spanStart), never multiplied
     *    event-by-event → no drift, no tremor;
     *  • the image point under the gesture midpoint stays glued to it → zoom
     *    AND pan happen in one gesture; the photo is pinned to the fingers;
     *  • no minimum span: scaling starts the instant the fingers move;
     *  • NOTHING is clamped during the pinch (the per-frame clamp was eating
     *    motion) — the photo is free while two fingers are down and springs
     *    softly back into bounds on release;
     *  • double-tap / swipe-nav / fling are suppressed while two fingers are
     *    down and for 400 ms after a pinch ends;
     *  • single-finger pan when zoomed: 1:1 drag with live clamp + inertia
     *    fling (OverScroller);
     *  • zoom range 1x–8x (was 5x), double-tap 1x ↔ 2.5x kept.
     */
    private inner class ZoomImage(ctx: android.content.Context) : AppCompatImageView(ctx) {

        var onTap: (() -> Unit)? = null
        /** dir = +1 → next photo, -1 → previous (activity-side). */
        var onSwipe: ((Int) -> Unit)? = null

        // ---- gesture state ----
        private var lastX = 0f
        private var lastY = 0f
        /** one swipe per gesture (reset on ACTION_DOWN) */
        private var swiped = false
        /** this gesture contained a real span change → suppress swipe-nav */
        private var pinched = false
        private var twoFingers = false
        /** uptime of the last pinch release — guards double-tap after pinch */
        private var pinchEndAt = 0L

        // rigid pinch snapshot (re-taken when the 2nd finger lands / re-lands)
        private var pidA = -1            // pointer id — finger A (primary)
        private var pidB = -1            // pointer id — finger B
        private var span0 = 1f           // finger span at snapshot
        private var scale0 = 1f          // view scale at snapshot
        /** image point (in 1x view coords) that stays glued to the midpoint */
        private var anchorX = 0f
        private var anchorY = 0f
        /** v1.7.20: smoothed gesture inputs — two-finger midpoints/spans are
         *  noisier than single-finger ones (the digitizer interpolates both
         *  contacts); following them 1:1 trembles the image. Light exp filter
         *  (α≈0.55, ≈1 frame of lag) + per-frame averaging of the historical
         *  samples kills the tremor without feeling sluggish. */
        private var smSpan = -1f
        private var smMidX = 0f
        private var smMidY = 0f

        private val velocity = VelocityTracker.obtain()
        private val scroller = OverScroller(ctx)

        // v1.7.22: gesture-start point — animation cancels during MOVE are now
        // GATED on real movement away from it (a 1px jitter must not kill the
        // double-tap animation a few ms after it started)
        private var gDownX = 0f
        private var gDownY = 0f
        /** uptime until which a double-tap animation owns the view — the UP of
         *  the second tap must not run the micro-zoom settle over it */
        private var dtAnimUntil = 0L

        /** v1.7.22: SELF-DRIVEN fling loop. The old implementation relied on
         *  computeScroll() being invoked by the framework's draw plumbing; on
         *  some shells that never happens for a plain View → the fling did
         *  nothing and the user had to swipe 2-3 times to cross the photo.
         *  postOnAnimation() re-posts itself every frame — no framework
         *  dependency, works identically everywhere. */
        private val flingTick = object : Runnable {
            override fun run() {
                if (scroller.computeScrollOffset()) {
                    translationX = scroller.currX.toFloat()
                    translationY = scroller.currY.toFloat()
                    postOnAnimation(this)
                }
            }
        }

        private val tapDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // a finger landing right after a pinch is NOT a double-tap —
                // the old code yanked the photo to 2.5x here (one of the
                // "vibration" sources the user saw)
                if (twoFingers || SystemClock.uptimeMillis() - pinchEndAt < 400) return true
                // v1.7.22: mark the animation window — the UP of THIS tap used
                // to run the «micro-zoom → settle at 1x» branch while scaleX was
                // still ≈1.0 (the zoom-in had just started) and immediately
                // reversed it. That (plus the DOWN cancel-kill below) is why the
                // double-tap zoom had «disappeared».
                dtAnimUntil = SystemClock.uptimeMillis() + 260
                if (scaleX > 1.05f) animateTo(1f, width / 2f, height / 2f)
                else animateTo(2.5f, e.x, e.y)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (twoFingers || pinched || swiped || scaleX > 1.05f || e1 == null) return false
                val totalX = e2.x - e1.x
                val totalY = e2.y - e1.y
                if (abs(totalX) > dp(96) && abs(totalX) > abs(totalY) * 1.2f) {
                    swiped = true
                    onSwipe?.invoke(if (totalX < 0) 1 else -1)
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (twoFingers || pinched || swiped || scaleX > 1.05f || e1 == null) return false
                val totalX = e2.x - e1.x
                if (abs(vx) > 2200f && abs(totalX) > dp(40) && abs(totalX) > abs(e2.y - e1.y) * 1.2f) {
                    swiped = true
                    onSwipe?.invoke(if (vx < 0) 1 else -1)
                }
                return true
            }
        })

        init {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Ultra-low friction (0.0025f) + boosted velocity = effortless, fast gliding across photo edges
            try { scroller.setFriction(0.0025f) } catch (_: Exception) {}
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            velocity.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animate().cancel()
                    scroller.forceFinished(true)
                    velocity.clear()
                    swiped = false
                    pinched = false
                    twoFingers = false
                    pidA = ev.getPointerId(0)
                    pidB = -1
                    lastX = ev.x
                    lastY = ev.y
                    gDownX = ev.x
                    gDownY = ev.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!twoFingers) {
                        pidA = ev.getPointerId(0)   // original finger stays primary
                        twoFingers = true
                    }
                    startPinch(ev, ev.actionIndex)
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(ev.x - gDownX) > dp(10) || abs(ev.y - gDownY) > dp(10)
                    if (moved) { animate().cancel(); scroller.forceFinished(true) }
                    var consumed = false
                    if (twoFingers) {
                        val ia = if (pidA >= 0) ev.findPointerIndex(pidA) else -1
                        val ib = if (pidB >= 0) ev.findPointerIndex(pidB) else -1
                        if (ia >= 0 && ib >= 0) {
                            pinchMove(ev, ia, ib)
                            consumed = true
                        } else {
                            twoFingers = false
                        }
                    }
                    if (!consumed && scaleX > 1.02f) {
                        translationX += ev.x - lastX
                        translationY += ev.y - lastY
                        clampPan()
                    }
                    lastX = ev.x
                    lastY = ev.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val upId = ev.getPointerId(ev.actionIndex)
                    if (twoFingers) {
                        if (upId == pidA) pidA = pidB
                        pidB = -1
                        twoFingers = false
                        val ia = if (pidA >= 0) ev.findPointerIndex(pidA) else -1
                        if (ia >= 0) { lastX = ev.getX(ia); lastY = ev.getY(ia) }
                        else { lastX = ev.x; lastY = ev.y }
                        if (scaleX > 1.02f) clampPanAnimated()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    twoFingers = false
                    pidA = -1; pidB = -1
                    velocity.computeCurrentVelocity(1000, 16000f)
                    val vx = velocity.xVelocity
                    val vy = velocity.yVelocity
                    val wasPinch = pinched
                    if (wasPinch) pinchEndAt = SystemClock.uptimeMillis()
                    val dtAnimating = SystemClock.uptimeMillis() < dtAnimUntil
                    if (scaleX < 1.06f && !dtAnimating) {
                        scroller.forceFinished(true)
                        animateTo(1f, width / 2f, height / 2f)
                    } else {
                        val (mx, my) = panBounds()
                        val out = translationX < -mx || translationX > mx ||
                            translationY < -my || translationY > my
                        when {
                            out && !dtAnimating -> {
                                scroller.forceFinished(true)
                                clampPanAnimated()
                            }
                            !out && abs(vx) + abs(vy) > dp(160) -> {
                                scroller.forceFinished(true)
                                scroller.fling(
                                    translationX.toInt(), translationY.toInt(),
                                    (vx * 2.2f).toInt(), (vy * 2.2f).toInt(),
                                    (-mx).toInt(), mx.toInt(), (-my).toInt(), my.toInt())
                                postOnAnimation(flingTick)
                            }
                        }
                    }
                }
            }
            // v1.7.22: the gesture detector runs AFTER the when-block now. It
            // used to run FIRST — onDoubleTap fired mid-call, started animateTo(),
            // and the ACTION_DOWN branch right below cancelled that animation in
            // the SAME call (animate().cancel()) → double-tap zoom was dead.
            try { tapDet.onTouchEvent(ev) } catch (_: Exception) {}
            return true
        }

        /** Take the rigid-pinch snapshot: span/scale plus the image point
         *  under the two-finger midpoint (pivot = view center). */
        private fun startPinch(ev: MotionEvent, idxB: Int) {
            pidB = ev.getPointerId(idxB)
            val ia = (if (pidA >= 0) ev.findPointerIndex(pidA) else 0).coerceAtLeast(0)
            val ib = ev.findPointerIndex(pidB)
            if (ib < 0) return
            val ax = ev.getX(ia); val ay = ev.getY(ia)
            val bx = ev.getX(ib); val by = ev.getY(ib)
            span0 = hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat().coerceAtLeast(1f)
            scale0 = scaleX
            val cx = width / 2f
            val cy = height / 2f
            val midx = (ax + bx) / 2f
            val midy = (ay + by) / 2f
            anchorX = (midx - cx - translationX) / scale0 + cx
            anchorY = (midy - cy - translationY) / scale0 + cy
            // the smoother starts from the raw snapshot — zero lag at gesture start
            smSpan = span0
            smMidX = midx
            smMidY = midy
        }

        /** Rigid two-finger move: scale from the ABSOLUTE (smoothed) span ratio
         *  and keep the anchored image point under the moving midpoint.
         *  v1.7.20: span inputs are averaged over the whole event batch and
         *  exp-smoothed; below 1x the translation is damped toward the center.
         *  v1.7.21 (user: «при зуме фото тяжело двигается влево/вправо»): the
         *  MIDPOINT no longer goes through the batch average — that added 1-2
         *  frames of lag on top of the filter, and panning while zoomed felt
         *  heavy. The endpoint IS where the fingers are; a faster α=0.75 keeps
         *  the tremor filter while the pan follows the fingers almost 1:1. */
        private fun pinchMove(ev: MotionEvent, ia: Int, ib: Int) {
            // ---- span: batch average (current sample + historical samples) ----
            var rawSpan = 0f; var n = 0
            for (i in 0 until ev.historySize) {
                rawSpan += hypot(
                    (ev.getHistoricalX(ib, i) - ev.getHistoricalX(ia, i)).toDouble(),
                    (ev.getHistoricalY(ib, i) - ev.getHistoricalY(ia, i)).toDouble()).toFloat()
                n++
            }
            rawSpan += hypot((ev.getX(ib) - ev.getX(ia)).toDouble(),
                (ev.getY(ib) - ev.getY(ia)).toDouble()).toFloat()
            rawSpan = (rawSpan / (n + 1)).coerceAtLeast(1f)
            // ---- midpoint: ENDPOINT ONLY (v1.7.21 — zero batch lag) ----
            val rawMidX = (ev.getX(ia) + ev.getX(ib)) / 2f
            val rawMidY = (ev.getY(ia) + ev.getY(ib)) / 2f
            if (abs(rawSpan - span0) > dp(5)) pinched = true
            // ---- light exponential smoothing (span 0.55 / mid 0.75) ----
            if (smSpan < 0f) { smSpan = rawSpan; smMidX = rawMidX; smMidY = rawMidY }
            smSpan = smSpan * 0.45f + rawSpan * 0.55f
            smMidX = smMidX * 0.25f + rawMidX * 0.75f
            smMidY = smMidY * 0.25f + rawMidY * 0.75f
            val ns = (scale0 * smSpan / span0).coerceIn(0.75f, 8f)
            // t = mid - c - (anchor - c) * s — the anchor stays under the midpoint;
            // below 1x damp the offset → the photo eases back to center
            val damp = ((ns - 0.75f) / 0.25f).coerceIn(0f, 1f)
            val cx = width / 2f
            val cy = height / 2f
            scaleX = ns
            scaleY = ns
            translationX = (smMidX - cx - (anchorX - cx) * ns) * damp
            translationY = (smMidY - cy - (anchorY - cy) * ns) * damp
        }

        /** Animated variant (double-tap / settle): compute the target transform
         *  and let ViewPropertyAnimator interpolate. */
        private fun animateTo(ns: Float, fx: Float, fy: Float) {
            val k = ns / scaleX
            val cx = width / 2f
            val cy = height / 2f
            val tx = fx - cx - (fx - cx - translationX) * k
            val ty = fy - cy - (fy - cy - translationY) * k
            // v1.7.21: bounds honor the DRAWN image size (FIT_CENTER), not the view
            val (bmx, bmy) = panBoundsFor(ns)
            animate().scaleX(ns).scaleY(ns)
                .translationX(tx.coerceIn(-bmx, bmx))
                .translationY(ty.coerceIn(-bmy, bmy))
                .setDuration(180)
                .withEndAction { clampPan() }
                .start()
        }

        /** v1.7.21: free-pan range on each axis for view scale [s], honoring
         *  FIT_CENTER — the DRAWN content size defines the overshoot, not the
         *  view size. The old width*(s-1)/2 bound was wrong whenever the image
         *  is letterboxed (landscape photo on a portrait screen etc.): the
         *  photo could be dragged deep into the empty letterbox area and felt
         *  like it « barely moves / gets lost ». */
        private fun panBoundsFor(s: Float): Pair<Float, Float> {
            val d = drawable ?: return 0f to 0f
            val iw = d.intrinsicWidth.toFloat()
            val ih = d.intrinsicHeight.toFloat()
            if (iw <= 0f || ih <= 0f || width < 1 || height < 1) return 0f to 0f
            val fit = minOf(width / iw, height / ih)
            val cw = iw * fit * s          // drawn content width at scale s
            val ch = ih * fit * s          // drawn content height at scale s
            return maxOf(0f, (cw - width) / 2f) to maxOf(0f, (ch - height) / 2f)
        }

        private fun panBounds(): Pair<Float, Float> = panBoundsFor(scaleX)

        private fun clampPan() {
            val (mx, my) = panBounds()
            translationX = translationX.coerceIn(-mx, mx)
            translationY = translationY.coerceIn(-my, my)
        }

        /** Smooth (animated) version used when a gesture hands the photo back
         *  out of bounds — never snaps mid-gesture. */
        private fun clampPanAnimated() {
            val (mx, my) = panBounds()
            val tx = translationX.coerceIn(-mx, mx)
            val ty = translationY.coerceIn(-my, my)
            if (tx != translationX || ty != translationY) {
                animate().translationX(tx).translationY(ty)
                    .setDuration(160)
                    .start()
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                translationX = scroller.currX.toFloat()
                translationY = scroller.currY.toFloat()
                postInvalidateOnAnimation()
            }
        }

        /** New photo coming — kill any animation, back to 1x centered. */
        fun resetZoom() {
            try {
                animate().cancel()
                scroller.forceFinished(true)
            } catch (_: Exception) {}
            scaleX = 1f; scaleY = 1f
            translationX = 0f; translationY = 0f
        }
    }

    // ------------------------------------------------------------- helpers

    private fun gradientScrim(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xE6000000.toInt(), 0x00000000))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
