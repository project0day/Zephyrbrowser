package com.zephyr.browser.panels

import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.DownloadEngine
import com.zephyr.browser.MimeType
import com.zephyr.browser.R
import com.zephyr.browser.Urls
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.DownloadEntry
import com.zephyr.browser.player.PlayerActivity
import com.zephyr.browser.viewer.PhotoViewerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Downloads panel, v1.7.2 premium redesign:
 * - every download is its own rounded surface card with a REAL 10dp gap below it
 *   (root cause of the "rows glued together" bug: the swipe wrapper re-added the
 *   card with fresh FrameLayout params and silently dropped its bottom margin —
 *   the wrapper now carries the spacing itself); in-progress cards wear an accent
 *   stroke, failed cards an error stroke;
 * - real thumbnails on the left, 60dp rounded 12dp, decoded ASYNC on Dispatchers.IO
 *   with a 4MB LruCache: image previews (inSampleSize decode from a file path or a
 *   MediaStore content URI), video frames via MediaMetadataRetriever, APK app icons
 *   via PackageManager, embedded album art for audio; typed icon tiles as fallbacks
 *   (video gets a play glyph on an accent gradient, audio a tinted music tile);
 * - filter chips row (All / Photos / Videos / Audio / Files) matching mime or
 *   file extension, active chip filled with the theme accent;
 * - in-progress rows: thin rounded progress bar with a sweep animation, live
 *   "downloaded of total" text and a percentage once the total is known
 *   (bytes are read from the partially written app-dir file; the total comes
 *   from a one-time HEAD request per entry, because DownloadEngine only writes
 *   the size to Room when the transfer finishes);
 * - periodic refresh tick (1s while something is running, 4s idle otherwise);
 * - row menu anchored to the dot-dot-dot button with Gravity.END (v1.7.1 fix kept),
 *   top actions "clear finished" and "delete all" with confirm dialogs,
 *   swipe-to-delete, failed rows show an error chip, empty state with icon;
 * - v1.7.3: legacy rows with a generic mime (pre-fix engine wrote whatever the
 *   server handed over) are self-repaired from the stored file — ContentResolver
 *   type for MediaStore rows, the shared 64-byte magic sniff for plain files —
 *   persisted via DownloadDao.updateMime and re-filed under the right chip; file
 *   rows with an unknown type still get an image decode attempt so photo previews
 *   show even when the type could not be recovered.
 */
class DownloadsPanelFragment : Fragment() {

    private enum class Filter(val label: Int) {
        ALL(R.string.dl_filter_all),
        IMAGES(R.string.dl_filter_images),
        VIDEO(R.string.dl_filter_video),
        AUDIO(R.string.dl_filter_audio),
        FILES(R.string.dl_filter_files)
    }

    /** Live transfer numbers for a running entry (total is 0 when unknown). */
    private class Live(val downloaded: Long, val total: Long)

    /** Mutable per-row views kept around so the refresh tick can update them in place. */
    private class RowRefs(val entry: DownloadEntry, val bar: Bar?, val pct: TextView?, val sub: TextView)

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var items: List<DownloadEntry> = emptyList()
    private lateinit var list: LinearLayout
    private var filter = Filter.ALL
    private val chipViews = HashMap<Filter, TextView>()
    private val rowsById = HashMap<Long, RowRefs>()
    private var lastSig = ""
    private var resumed = false
    private var reloadGen = 0
    /** Cached at view creation so IO decoders never touch fragment resources. */
    private var density = 1f

    /** path (or MediaStore name key) to decoded preview, ~4MB. */
    private val thumbCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.rowBytes * value.height
    }

    /** Keys that failed to decode — skip re-decoding them on every rebuild. */
    private val misses: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Running entry id to its total size (negative means unknown or unfetchable). */
    private val totals = ConcurrentHashMap<Long, Long>()

    /** Finished entry ids whose generic mime was already probed (repaired or proven
     *  unfixable) — keeps the periodic tick from re-opening the same files on every
     *  reload. Concurrent-map backed: reloads can overlap on the IO dispatcher. */
    private val repairedIds: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** Periodic refresh: 1s while transfers are running, 4s idle (discovers new ones). */
    private val tick = Runnable {
        if (resumed && isAdded) reload()
    }

    // =====================================================================
    // lifecycle + scaffold
    // =====================================================================

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        density = resources.displayMetrics.density
        val incognito = try {
            activity?.intent?.getBooleanExtra(PanelsActivity.EXTRA_INCOGNITO, false) ?: false
        } catch (_: Exception) { false }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }

        // top action bar: clear finished + delete all (text buttons, right aligned)
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(10), 0)
            addView(actionButton(ctx, R.string.dl_clear_finished, PanelUi.accent(ctx)) { confirmClearFinished() })
            addView(actionButton(ctx, R.string.dl_delete_all,
                PanelUi.themedColor(ctx, R.attr.colorError, 0xFFD93025.toInt())) { confirmDeleteAll() })
        })

        // v1.7.8: dismissable explanation banner (user: «нет пояснений во многих опциях»)
        PanelUi.hintBanner(ctx, "downloads_hint", R.drawable.ic_download,
            ctx.getString(R.string.downloads_hint_title), ctx.getString(R.string.downloads_hint_body))
            ?.let { root.addView(it) }

        // filter chips, horizontally scrollable
        val chipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
        }
        for (f in Filter.values()) {
            val chip = chip(ctx, f)
            chipViews[f] = chip
            chipsRow.addView(chip)
        }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(chipsRow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        restyleChips()

        // the list itself; the scroll paints the tinted backdrop so surface cards pop
        val scroll = ScrollView(ctx)
        if (!incognito) scroll.setBackgroundColor(backdrop(ctx))
        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(28))
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        for (r in rowsById.values) r.bar?.resumeAnim()
        reload()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(tick)
        for (r in rowsById.values) r.bar?.pauseAnim()
    }

    // =====================================================================
    // data + refresh tick
    // =====================================================================

    fun reload() {
        val ctx = context ?: return
        val gen = ++reloadGen
        io.launch {
            val appCtx = ctx.applicationContext
            val fresh = try {
                AppDb.get(appCtx).downloadDao().all().first()
            } catch (_: Exception) { emptyList() }
            // v1.7.3: recover the real type of legacy generic-mime rows before rendering
            val rows = repairMimes(appCtx, fresh)
            val live = HashMap<Long, Live>()
            for (d in rows) {
                val l = liveProgress(appCtx, d)
                if (l != null) live[d.id] = l
            }
            withContext(Dispatchers.Main) {
                if (gen != reloadGen || !isAdded) { scheduleTick(); return@withContext }
                items = rows
                val sig = signature()
                if (sig != lastSig) {
                    lastSig = sig
                    misses.clear()          // data changed — give failed decodes another chance
                    rebuild()
                } else {
                    applyLive(live)         // cheap in-place update, keeps scroll + swipe state
                }
                scheduleTick()
            }
        }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        if (!resumed) return
        val fast = items.any { it.status == 0 }
        main.postDelayed(tick, if (fast) 1000L else 4000L)
    }

    /** Everything the UI depends on: the chips read the mime, the title reads the file
     *  name (the engine rewrites it with the repaired extension at completion) and the
     *  row reads status/size/path — so a legacy mime repair triggers a rebuild too. */
    private fun signature(): String =
        "f${filter.ordinal}|" + items.joinToString("|") { "${it.id}:${it.status}:${it.size}:${it.mime}:${it.fileName}:${it.path}" }

    /**
     * Bytes written so far for a running download.
     *
     * v1.8.3 (user: «идёт непонятная анимация, линия постоянно заполняется
     * слева направо»). That sweep is what a progress bar shows when it has no
     * total, and it had none because this screen was working the figure out by
     * itself: find the .part file, then fire a HEAD request at the URL and hope.
     * The HEAD frequently fails — redirects, cookies, servers that refuse it —
     * and the bar swept forever.
     *
     * The engine already has the real number: it read the Content-Length when
     * it opened the stream. Ask it first, and only fall back to guessing.
     */
    private fun liveProgress(appCtx: Context, d: DownloadEntry): Live? {
        if (d.status != 0) return null
        com.zephyr.browser.DownloadEngine.progressOf(d.id)?.let { e ->
            if (e.total > 0L || e.downloaded > 0L) return Live(e.downloaded, e.total)
        }
        return try {
            val dir = File(appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: appCtx.filesDir, "Zephyr")
            var partial: File? = null
            for (name in candidateNames(d.fileName)) {
                val c = File(dir, name)
                if (c.isFile) { partial = c; break }
            }
            if (partial == null) return null
            val bytes = partial.length()
            var total = totals[d.id]
            if (total == null) {
                totals[d.id] = -1L          // mark as attempted before firing the request
                val url = d.url
                io.launch { totals[d.id] = fetchTotal(url) }
                total = -1L
            }
            Live(bytes, total)
        } catch (_: Exception) { null }
    }

    /** One-time HEAD request for the entry total size (engine stores it only at completion). */
    private fun fetchTotal(url: String): Long = try {
        if (!url.startsWith("http")) -1L
        else {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = true
            val len = if (conn.responseCode in 200..299) conn.contentLengthLong else -1L
            conn.disconnect()
            len
        }
    } catch (_: Exception) { -1L }

    /** Engine writes the sanitized file name; probe both it and the raw one. */
    private fun candidateNames(raw: String): List<String> {
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        return listOf(raw, clean).filter { it.isNotBlank() }.distinct()
    }

    // =====================================================================
    // legacy mime repair (v1.7.3)
    // =====================================================================

    /** Rows written before v1.7.3 often carry a generic mime (servers answer photo
     *  requests with a binary type and the WebView sometimes hands over an empty one),
     *  which filed pictures under the Files chip and broke their previews. For finished
     *  rows the real type is recovered from the stored file — ContentResolver.getType
     *  for MediaStore rows (with a magic-byte fallback when even that is generic) and
     *  the shared 64-byte sniff for plain files — then persisted via updateMime so the
     *  repair survives restarts. Probed ids are cached to keep the tick cheap. */
    private suspend fun repairMimes(appCtx: Context, list: List<DownloadEntry>): List<DownloadEntry> {
        if (list.none { it.status == 1 && MimeType.isGeneric(it.mime) && !repairedIds.contains(it.id) }) return list
        val dao = AppDb.get(appCtx).downloadDao()
        val out = ArrayList<DownloadEntry>(list.size)
        for (d in list) {
            if (d.status != 1 || !MimeType.isGeneric(d.mime) || repairedIds.contains(d.id)) {
                out.add(d)
                continue
            }
            val fixed = try {
                if (d.path.startsWith("content")) {
                    val t = appCtx.contentResolver.getType(Uri.parse(d.path))
                    if (t != null && !MimeType.isGeneric(t)) t
                    else sniffUri(appCtx, Uri.parse(d.path), d.fileName)
                } else if (d.path.startsWith("/")) {
                    val f = File(d.path)
                    if (f.isFile) sniffStream({ f.inputStream() }, d.fileName) else null
                } else null
            } catch (_: Exception) { null }
            if (fixed != null && !MimeType.isGeneric(fixed)) {
                try { dao.updateMime(d.id, fixed) } catch (_: Exception) {}
                out.add(d.copy(mime = fixed))
            } else {
                out.add(d)
            }
            repairedIds.add(d.id)
        }
        return out
    }

    /** Magic sniff of a MediaStore row whose own stored type is generic too. */
    private fun sniffUri(appCtx: Context, uri: Uri, name: String): String? =
        sniffStream({ appCtx.contentResolver.openInputStream(uri) }, name)

    /** Reads the first 64 bytes of anything openable and applies the shared sniff. */
    private fun sniffStream(open: () -> InputStream?, name: String): String? = try {
        val head = ByteArray(64)
        var len = 0
        open()?.use { s ->
            while (len < head.size) {
                val n = s.read(head, len, head.size - len)
                if (n < 0) break
                len += n
            }
        }
        MimeType.sniff(head.copyOf(len), name)
    } catch (_: Exception) { null }

    // =====================================================================
    // thumbnails (async, cached)
    // =====================================================================

    /** Cache key AND ImageView recycle-check tag: the entry id leads, so a
     *  re-download of the same path decodes fresh and a rebuilt row for another
     *  entry never receives a stale bitmap. */
    private fun cacheKey(d: DownloadEntry): String =
        "${d.id}::" + (if (d.path.isBlank()) "ms::${d.fileName}" else d.path)

    private fun thumbTargetPx(): Int =
        (64 * density + 0.5f).toInt().coerceAtLeast(96)

    private fun requestThumb(ctx: Context, d: DownloadEntry, iv: ImageView) {
        val key = cacheKey(d)
        iv.tag = key
        val cached = thumbCache.get(key)
        if (cached != null) { applyThumb(iv, cached, d); return }
        if (misses.contains(key)) return
        val appCtx = ctx.applicationContext
        io.launch {
            val bmp = decodeThumb(appCtx, d)
            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    thumbCache.put(key, bmp)
                    misses.remove(key)
                } else {
                    misses.add(key)
                }
                if (bmp != null && iv.tag == key) applyThumb(iv, bmp, d)
            }
        }
    }

    /** Swaps the placeholder tile for the real preview: resets the placeholder
     *  padding, tint, gradient background and CENTER_INSIDE scaling first. */
    private fun applyThumb(iv: ImageView, bmp: Bitmap, d: DownloadEntry) {
        iv.setPadding(0, 0, 0, 0)
        iv.imageTintList = null
        iv.background = null
        if (isApk(d)) {
            // rasterized app icon: keep its margins instead of cropping them away
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(7), dp(7), dp(7), dp(7))
        } else {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        iv.setImageBitmap(bmp)
    }

    private fun decodeThumb(appCtx: Context, d: DownloadEntry): Bitmap? {
        if (d.status != 1) return null
        return try {
            when {
                isApk(d) -> apkBitmap(appCtx, d)
                d.path.startsWith("content") -> contentThumb(appCtx, Uri.parse(d.path), d)
                d.path.isBlank() -> findInMediaStore(appCtx, d.fileName)?.let { contentThumb(appCtx, it, d) }
                else -> {
                    val f = File(d.path)
                    when {
                        !f.exists() || !f.isFile -> null
                        isImage(d) -> decodeImageFile(f)
                        isVideo(d) -> decodeVideoFile(f)
                        isAudio(d) -> decodeAudioArt(appCtx, f.absolutePath, null)
                        // v1.7.3: generic-mime rows may still be pictures (no repairable
                        // source or the sniff failed) — decodeImageFile safely returns
                        // null for anything that is not an image
                        MimeType.isGeneric(d.mime) -> decodeImageFile(f)
                        else -> null
                    }
                }
            }
        } catch (_: Exception) { null }
    }

    private fun decodeImageFile(f: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val target = thumbTargetPx()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }

    private fun decodeVideoFile(f: File): Bitmap? = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            scaleFrame(mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    } catch (_: Exception) { null }

    private fun decodeAudioArt(appCtx: Context, path: String?, uri: Uri?): Bitmap? = try {
        val mmr = MediaMetadataRetriever()
        try {
            if (uri != null) mmr.setDataSource(appCtx, uri) else mmr.setDataSource(path ?: "")
            val bytes = mmr.embeddedPicture
            if (bytes != null) decodeSampled(bytes) else null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    } catch (_: Exception) { null }

    private fun decodeSampled(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = thumbTargetPx()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }

    /** Thumbnail for a MediaStore item: images, videos and embedded audio art. */
    private fun contentThumb(appCtx: Context, uri: Uri, d: DownloadEntry): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29 && (isImage(d) || isVideo(d))) {
            try {
                val px = thumbTargetPx()
                return appCtx.contentResolver.loadThumbnail(uri, Size(px, px), null)
            } catch (_: Exception) {}
        }
        return when {
            isImage(d) -> decodeContentImage(appCtx, uri)
            isVideo(d) -> decodeContentVideo(appCtx, uri)
            isAudio(d) -> decodeAudioArt(appCtx, null, uri)
            else -> null
        }
    }

    private fun decodeContentImage(appCtx: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appCtx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val target = thumbTargetPx()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val so = BitmapFactory.Options().apply { inSampleSize = sample }
        appCtx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, so) }
    } catch (_: Exception) { null }

    private fun decodeContentVideo(appCtx: Context, uri: Uri): Bitmap? = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(appCtx, uri)
            scaleFrame(mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    } catch (_: Exception) { null }

    /** Video frames come out full size; shrink them to thumbnail scale. */
    private fun scaleFrame(bmp: Bitmap?): Bitmap? {
        if (bmp == null) return null
        val target = thumbTargetPx()
        val m = minOf(bmp.width, bmp.height)
        if (m <= target * 2) return bmp
        val s = target.toFloat() / m
        return try {
            Bitmap.createScaledBitmap(bmp,
                (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1), true)
        } catch (_: Exception) { bmp }
    }

    /** APK app icon via PackageManager, rasterized to a cacheable bitmap. */
    private fun apkBitmap(ctx: Context, d: DownloadEntry): Bitmap? {
        if (!d.path.startsWith("/")) return null
        return try {
            val pm = ctx.packageManager
            val info = pm.getPackageArchiveInfo(d.path, 0)
            val ai = info?.applicationInfo
            if (ai == null) null
            else {
                ai.sourceDir = d.path
                ai.publicSourceDir = d.path
                val dr = pm.getApplicationIcon(ai)
                val target = thumbTargetPx()
                val ow: Int
                val oh: Int
                if (dr.intrinsicWidth <= 0 || dr.intrinsicHeight <= 0) {
                    ow = target; oh = target
                } else {
                    val s = target.toFloat() / maxOf(dr.intrinsicWidth, dr.intrinsicHeight)
                    ow = (dr.intrinsicWidth * s).toInt().coerceAtLeast(1)
                    oh = (dr.intrinsicHeight * s).toInt().coerceAtLeast(1)
                }
                dr.setBounds(0, 0, ow, oh)
                val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                dr.draw(Canvas(bmp))
                bmp
            }
        } catch (_: Exception) { null }
    }

    /** Locate a finished file in MediaStore by display name (newest first) — legacy rows with a blank path. */
    private fun findInMediaStore(appCtx: Context, name: String): Uri? {
        if (name.isBlank()) return null
        return try {
            val files = MediaStore.Files.getContentUri("external")
            appCtx.contentResolver.query(
                files,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(name),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(files, c.getLong(0)) else null
            }
        } catch (_: Exception) { null }
    }

    // =====================================================================
    // list building
    // =====================================================================

    private fun rebuild() {
        val ctx = context ?: return
        list.removeAllViews()
        rowsById.clear()
        val visible = items.filter { matches(it, filter) }
        if (visible.isEmpty()) {
            list.addView(emptyView(ctx))
            return
        }
        for (d in visible) {
            val card = buildRow(d)
            val wrap = PanelUi.swipeable(ctx, card) { deleteEntry(d, true) }
            // THE spacing fix: the swipe wrapper REPLACES the card's layout params
            // when it re-parents it, so the 10dp gap must live on the wrapper itself.
            wrap.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            list.addView(wrap)
        }
    }

    private fun buildRow(d: DownloadEntry): LinearLayout {
        val ctx = requireContext()
        val running = d.status == 0
        val failed = d.status == 2

        // leading thumbnail: 60dp rounded 12dp frame with a typed placeholder,
        // swapped for the real preview as soon as the async decode lands
        val frame = PanelUi.thumbFrame(ctx, sizeDp = 60, radiusDp = 12)
        val iv = ImageView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(iv)
        stylePlaceholder(ctx, iv, d)
        val ext = Urls.fileExtOf(d.fileName)
        if (!isApk(d) && !isImage(d) && !isVideo(d) && !isAudio(d) && ext != null) {
            frame.addView(TextView(ctx).apply {
                text = ext.uppercase()
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(5), dp(1), dp(5), dp(1))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(4) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0xB3000000.toInt())
                }
            })
        }
        if (!running) requestThumb(ctx, d, iv)

        // text column: title + status chip, subtitle, live progress for running rows
        val sub = TextView(ctx).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        }
        sub.text = subtitleText(ctx, d, null)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = d.fileName
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (failed) {
            titleRow.addView(statusChip(ctx, R.string.dl_chip_failed,
                PanelUi.themedColor(ctx, R.attr.colorError, 0xFFD93025.toInt())))
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(titleRow)
        textCol.addView(sub)

        var bar: Bar? = null
        var pct: TextView? = null
        if (running) {
            val b = Bar(ctx)
            val p = TextView(ctx).apply {
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(PanelUi.accent(ctx))
            }
            bar = b
            pct = p
            textCol.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), dp(10), 0)
                addView(b, LinearLayout.LayoutParams(0, dp(5), 1f))
                addView(p, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            })
            b.setProgress(null, false)      // sweep until real numbers arrive
        }

        val moreBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            background = PanelUi.ripple(ctx)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            contentDescription = getString(R.string.menu)
            setOnClickListener { v -> entryMenu(d, v) }
        }

        val isMedia = isPlayableMedia(d)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(6), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = cardBg(ctx, running, failed)
            isClickable = true
            isFocusable = true
            clipToOutline = true
            foreground = PanelUi.ripple(ctx)
            if (running) alpha = 0.92f
            setOnClickListener {
                if (d.status == 1) {
                    if (isMedia) playEntry(d) else openEntry(d)
                } else {
                    Toast.makeText(ctx, R.string.dl_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
            setOnLongClickListener { v -> entryMenu(d, v); true }
        }
        card.addView(frame)
        card.addView(textCol)
        // v1.8.3 (user: «почему в загрузках ты не добавил функцию отмены? там
        // её нету»). Quite right — the notification got one and this screen did
        // not, so the only way to stop a transfer here was to delete the row
        // and hope. Its own button, next to the menu, on running rows only.
        if (running) {
            card.addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_close)
                background = PanelUi.ripple(ctx)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                imageTintList = ColorStateList.valueOf(
                    PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
                contentDescription = getString(R.string.cancel)
                setOnClickListener {
                            cancelEntry(d)
                }
            })
        }
        card.addView(moreBtn)
        rowsById[d.id] = RowRefs(d, bar, pct, sub)
        return card
    }

    /** Typed tile shown while the preview decodes (and whenever decoding is impossible). */
    private fun stylePlaceholder(ctx: Context, iv: ImageView, d: DownloadEntry) {
        iv.setPadding(0, 0, 0, 0)
        iv.imageTintList = null
        iv.background = null
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val accent = PanelUi.accent(ctx)
        val onVar = PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant)
        when {
            isApk(d) -> {
                iv.setImageResource(R.drawable.ic_archive)
                iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                iv.setPadding(dp(11), dp(11), dp(11), dp(11))
                iv.imageTintList = ColorStateList.valueOf(accent)
            }
            isVideo(d) -> {
                // play glyph on an accent gradient — the "no frame decoded" tile
                iv.setImageResource(R.drawable.ic_player_play)
                iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                iv.setPadding(dp(13), dp(13), dp(13), dp(13))
                iv.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                iv.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(accent, darken(accent)))
            }
            isAudio(d) -> {
                iv.setImageResource(R.drawable.ic_player_music)
                iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                iv.setPadding(dp(11), dp(11), dp(11), dp(11))
                iv.imageTintList = ColorStateList.valueOf(accent)
                iv.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor((accent and 0x00FFFFFF) or 0x24000000)
                }
            }
            isImage(d) -> {
                iv.setImageResource(R.drawable.ic_image)
                iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                iv.setPadding(dp(11), dp(11), dp(11), dp(11))
                iv.imageTintList = ColorStateList.valueOf(onVar)
            }
            else -> {
                iv.setImageResource(genericIcon(Urls.fileExtOf(d.fileName)))
                iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                iv.setPadding(dp(11), dp(11), dp(11), dp(11))
                iv.imageTintList = ColorStateList.valueOf(onVar)
            }
        }
    }

    private fun genericIcon(ext: String?): Int = when (ext) {
        "zip", "rar", "7z", "tar", "gz", "iso" -> R.drawable.ic_archive
        "pdf", "epub" -> R.drawable.ic_script
        "txt", "doc", "docx", "rtf", "log" -> R.drawable.ic_list
        "html", "htm", "xml" -> R.drawable.ic_globe
        "torrent" -> R.drawable.ic_download
        else -> R.drawable.ic_download
    }

    /** In-place progress update for running rows (no rebuild → swipe gestures survive). */
    private fun applyLive(live: Map<Long, Live>) {
        val ctx = context ?: return
        for (refs in rowsById.values) {
            val d = refs.entry
            if (d.status != 0) continue
            val l = live[d.id]
            if (l != null && l.total > 0) {
                val fr = if (l.downloaded <= 0) 0f
                else (l.downloaded.toFloat() / l.total.toFloat()).coerceIn(0f, 1f)
                refs.bar?.setProgress(fr, true)
                refs.pct?.text = "${(fr * 100).toInt()}%"
            } else {
                refs.bar?.setProgress(null, false)
                refs.pct?.text = ""
            }
            refs.sub.text = subtitleText(ctx, d, l)
        }
    }

    private fun subtitleText(ctx: Context, d: DownloadEntry, live: Live?): String {
        val date = DateFormat.getDateFormat(ctx).format(java.util.Date(d.addedAt))
        val host = Urls.domainOf(d.url)
        val parts = ArrayList<String>(3)
        when {
            d.status == 0 -> parts.add(runningSizeLabel(ctx, live))
            d.status == 2 -> {}   // failed rows: the error chip carries the state, size unknown
            d.size > 0 -> parts.add(Formatter.formatFileSize(ctx, d.size))
            else -> parts.add(getString(R.string.dl_unknown_size))
        }
        parts.add(date)
        if (host.isNotBlank()) parts.add(host)
        return parts.joinToString(" · ")
    }

    /**
     * v1.8.2 (user: «надо добавить процент и МБ сколько скачало из скольки»).
     *
     * The megabytes were here; the percentage was not, and the two answer
     * different questions — one is "how much of my data has this spent", the
     * other "how much longer". The row now reads the same as the notification,
     * so a glance at either gives the same answer.
     *
     * Android's own `Formatter` is used for the sizes rather than a private
     * one: it already says "Мо" in French and "МБ" in Russian.
     */
    private fun runningSizeLabel(ctx: Context, live: Live?): String {
        if (live == null || live.downloaded <= 0L) return getString(R.string.dl_in_progress)
        val down = Formatter.formatFileSize(ctx, live.downloaded)
        if (live.total <= 0L) return down
        val of = getString(R.string.dl_progress_of, down, Formatter.formatFileSize(ctx, live.total))
        val pct = (live.downloaded * 100 / live.total).toInt().coerceIn(0, 100)
        return "$of · $pct%"
    }

    private fun emptyView(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(72), 0, dp(72))
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_download)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            imageTintList = ColorStateList.valueOf(
                PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
            alpha = 0.55f
        })
        addView(TextView(ctx).apply {
            setText(R.string.dl_empty)
            textSize = 14.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            setTextColor(PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant))
        })
    }

    // =====================================================================
    // thin rounded progress bar (determinate or sweeping)
    // =====================================================================

    private class Bar(ctx: Context) : View(ctx) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFE8EAED.toInt())
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelUi.accent(ctx)
        }
        private var anim: ValueAnimator? = null
        private var sweep = false
        var fraction = -1f

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h / 2f
            canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint)
            val f = (if (sweep) anim?.animatedFraction ?: 0f else fraction).coerceIn(0f, 1f)
            if (f > 0.01f) {
                val fw = (w * f).coerceAtMost(w)
                val rr = r.coerceAtMost(fw / 2f)
                canvas.drawRoundRect(0f, 0f, fw, h, rr, rr, fillPaint)
            }
        }

        /** A null fraction switches to the looping sweep (total unknown). Idempotent:
         *  an already-sweeping bar keeps its animator (the refresh tick calls this
         *  every second — recreating it would visibly restart the sweep). */
        fun setProgress(f: Float?, animated: Boolean) {
            if (f == null || f < 0f || f.isNaN() || f.isInfinite()) {
                if (sweep && anim != null) return
                anim?.cancel()
                anim = null
                sweep = true
                fraction = -1f
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1600
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { invalidate() }
                    start()
                }
            } else {
                val target = f.coerceIn(0f, 1f)
                if (sweep) {
                    // first real number after an indeterminate sweep — jump, then animate
                    anim?.cancel()
                    anim = null
                    sweep = false
                    fraction = -1f
                }
                if (animated && fraction in 0f..1f && abs(target - fraction) > 0.004f) {
                    val from = fraction
                    anim = ValueAnimator.ofFloat(from, target).apply {
                        duration = 380
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { a -> fraction = a.animatedValue as Float; invalidate() }
                        start()
                    }
                } else {
                    fraction = target
                    invalidate()
                }
            }
        }

        fun pauseAnim() { try { anim?.pause() } catch (_: Exception) {} }
        fun resumeAnim() { try { anim?.resume() } catch (_: Exception) {} }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            anim = null
            super.onDetachedFromWindow()
        }
    }

    // =====================================================================
    // chips, actions, dialogs
    // =====================================================================

    private fun chip(ctx: Context, f: Filter): TextView = TextView(ctx).apply {
        setText(f.label)
        textSize = 13f
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(dp(16), 0, dp(16), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) }
        isClickable = true
        isFocusable = true
        clipToOutline = true
        foreground = PanelUi.ripple(ctx)
        setOnClickListener {
            if (filter != f) {
                filter = f
                restyleChips()
                lastSig = signature()
                rebuild()
            }
        }
    }

    private fun restyleChips() {
        val ctx = context ?: return
        val accent = PanelUi.accent(ctx)
        val onVar = PanelUi.themedColor(ctx, R.attr.colorOnSurfaceVariant)
        // inactive chips: SOFT surfaceVariant fill (60% alpha) over the panel background
        val softVariant = (PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt())
            and 0x00FFFFFF) or (0x99 shl 24)
        val activeText = if (luminance(accent) > 0.55f) 0xFF202124.toInt() else 0xFFFFFFFF.toInt()
        for ((f, chip) in chipViews) {
            val active = f == filter
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(17).toFloat()
                if (active) setColor(accent) else setColor(softVariant)
            }
            chip.setTextColor(if (active) activeText else onVar)
            chip.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun actionButton(ctx: Context, labelRes: Int, color: Int, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            setText(labelRes)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = PanelUi.ripple(ctx)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun statusChip(ctx: Context, textRes: Int, color: Int): TextView = TextView(ctx).apply {
        setText(textRes)
        textSize = 10.5f
        typeface = Typeface.DEFAULT_BOLD
        setSingleLine(true)
        setTextColor(color)
        setPadding(dp(9), dp(2), dp(9), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor((color and 0x00FFFFFF) or 0x26000000)
        }
    }

    private fun confirmClearFinished() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dl_clear_finished)
            .setMessage(R.string.dl_clear_finished_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val finished = items.filter { it.status != 0 }
                io.launch {
                    val dao = AppDb.get(ctx.applicationContext).downloadDao()
                    for (d in finished) { try { dao.delete(d) } catch (_: Exception) {} }
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        val ctx = context ?: return
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dl_delete_all)
            .setMessage(R.string.dl_delete_all_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val all = items.toList()
                for (d in all) totals.remove(d.id)
                io.launch {
                    val dao = AppDb.get(ctx.applicationContext).downloadDao()
                    for (d in all) {
                        try { deleteStoredFile(ctx, d) } catch (_: Exception) {}
                        try { dao.delete(d) } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.dl_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(d: DownloadEntry) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.delete)
            .setMessage(R.string.dl_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> deleteEntry(d, true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Stop a transfer, whichever engine is actually carrying it.
     *
     * v1.8.4 (user, after two failed attempts: «всё без изменений, файл всё
     * равно скачался»). Both earlier fixes worked on `DownloadEngine`, and for
     * this download there was nothing there to stop.
     *
     * A file the browser cannot fetch itself is handed to Android's own
     * `DownloadManager` (`MainActivity.downloadViaSystem`), and the row is
     * journalled with `path = "dm:<systemId>"`. Our engine never sees it. So
     * `DownloadEngine.cancel` was cancelling a transfer that did not exist
     * while the system carried on downloading — which is exactly the reported
     * behaviour: the notification goes, the file still arrives, and the system
     * stage later reports a result for a download the user had stopped.
     *
     * The row itself says which engine to talk to. Ask it first.
     */
    private fun cancelEntry(d: DownloadEntry) {
        val ctx = context ?: return
        val appCtx = ctx.applicationContext
        totals.remove(d.id)
        DownloadEngine.cancel(d.id)
        val dmId = d.path.removePrefix("dm:").toLongOrNull()?.takeIf { d.path.startsWith("dm:") }
        if (dmId != null) {
            try {
                val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE)
                    as android.app.DownloadManager
                // remove() stops the transfer AND deletes the partial file
                dm.remove(dmId)
            } catch (_: Exception) {}
        }
        io.launch {
            try { AppDb.get(appCtx).downloadDao().updateStatus(d.id, 4) } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                if (isAdded) { Toast.makeText(ctx, R.string.dl_cancelled, Toast.LENGTH_SHORT).show(); reload() }
            }
        }
    }

    /** Deletes the row (and the stored file) then reloads. */
    private fun deleteEntry(d: DownloadEntry, notify: Boolean) {
        val ctx = context ?: return
        totals.remove(d.id)
        // v1.8.2 (user: «удалил загрузку, очистил, но она не прекратилась и
        // файл всё равно скачался»).
        //
        // Deleting removed the row and the file but never stopped the transfer,
        // so the coroutine carried on and wrote the finished file afterwards.
        // The order made it certain: the file was deleted WHILE the download
        // was still running, then the download recreated it. Deleting an active
        // download was not merely ineffective, it was exactly backwards.
        //
        // The transfer is stopped first, and only then is anything removed.
        DownloadEngine.cancel(d.id)
        io.launch {
            try {
                deleteStoredFile(ctx, d)
                AppDb.get(ctx.applicationContext).downloadDao().delete(d)
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                if (notify) Toast.makeText(ctx, R.string.dl_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    /** Deleting a row also deletes the stored file — plain files directly, MediaStore rows via ContentResolver. */
    private fun deleteStoredFile(ctx: Context, d: DownloadEntry) {
        try {
            if (d.path.startsWith("/")) File(d.path).delete()
            else if (d.path.startsWith("content")) ctx.contentResolver.delete(Uri.parse(d.path), null, null)
        } catch (_: Exception) {}
    }

    // =====================================================================
    // row menu + open / play / share
    // =====================================================================

    private fun entryMenu(d: DownloadEntry, anchor: View) {
        val ctx = context ?: return
        // anchored to the dot-dot-dot button itself, right-aligned (v1.7.1 fix kept)
        val menu = android.widget.PopupMenu(ctx, anchor, Gravity.END)
        menu.menu.add(0, 1, 0, getString(R.string.dl_open))
        if (isPlayableMedia(d) && d.status == 1) menu.menu.add(0, 2, 0, getString(R.string.dl_play))
        menu.menu.add(0, 3, 0, getString(R.string.dl_share))
        menu.menu.add(0, 4, 0, getString(R.string.ctx_copy))
        // v1.8.4: cancel also lives here, not only on the inline button. Two
        // attempts at this went out without the user finding a way to stop a
        // transfer, and a menu entry is a path that cannot be squeezed off the
        // edge of a row by a long file name.
        if (d.status == 0) menu.menu.add(0, 6, 0, getString(R.string.dl_cancel))
        menu.menu.add(0, 5, 0, getString(R.string.delete))
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> openEntry(d)
                2 -> playEntry(d)
                3 -> shareEntry(d)
                4 -> copyLink(d)
                5 -> confirmDelete(d)
                6 -> cancelEntry(d)
            }
            true
        }
        menu.show()
    }

    private fun playEntry(d: DownloadEntry) {
        val ctx = context ?: return
        try {
            val uri = fileUri(d)
            startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, d.mime.ifEmpty { DownloadEngine.guessMime(d.fileName) })
                putExtra(Intent.EXTRA_TITLE, d.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.dl_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEntry(d: DownloadEntry) {
        val ctx = context ?: return
        try {
            val uri = fileUri(d)
            // v1.7.15 (user: «выбираю установщик — тишина, не устанавливается»):
            // APKs route through DownloadEngine.installApk — the unknown-sources
            // gate (REQUEST_INSTALL_PACKAGES, manifest v1.7.15) must come before
            // any raw ACTION_VIEW, which the system installer silently drops
            // without the grant. Covers the row tap and the ⋮ «Открыть» item.
            if (isApk(d)) {
                DownloadEngine.installApk(requireActivity(), uri, d.fileName)
                return
            }
            // v1.7.17: images open in the BUILT-IN photo viewer (zoom, swipe,
            // info, delete) instead of an external app; videos/audio keep the
            // playEntry path that already routes to PlayerActivity.
            if (isImage(d) && d.status == 1) {
                startActivity(Intent(ctx, PhotoViewerActivity::class.java).apply {
                    putStringArrayListExtra("uris", arrayListOf(uri.toString()))
                    putStringArrayListExtra("names", arrayListOf(d.fileName))
                    putStringArrayListExtra("paths", arrayListOf(if (d.path.startsWith("/")) d.path else ""))
                    putExtra("index", 0)
                    putExtra(Intent.EXTRA_TITLE, d.fileName)
                })
                return
            }
            val mime = d.mime.ifEmpty { DownloadEngine.guessMime(d.fileName) }
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.dl_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fileUri(d: DownloadEntry): Uri =
        if (d.path.startsWith("/")) {
            FileProvider.getUriForFile(requireContext(), requireContext().packageName + ".fileprovider", File(d.path))
        } else Uri.parse(d.path)

    private fun shareEntry(d: DownloadEntry) {
        val ctx = context ?: return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = d.mime.ifEmpty { "*/*" }
                putExtra(Intent.EXTRA_STREAM, fileUri(d))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, ctx.getString(R.string.dl_share)))
        } catch (_: Exception) {}
    }

    private fun copyLink(d: DownloadEntry) {
        val ctx = context ?: return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", d.url))
            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    // =====================================================================
    // type predicates + local styling helpers
    // =====================================================================

    private fun matches(d: DownloadEntry, f: Filter): Boolean = when (f) {
        Filter.ALL -> true
        Filter.IMAGES -> isImage(d)
        Filter.VIDEO -> isVideo(d)
        Filter.AUDIO -> isAudio(d)
        Filter.FILES -> !isImage(d) && !isVideo(d) && !isAudio(d)
    }

    private fun hasExt(d: DownloadEntry, exts: Set<String>): Boolean {
        val e = Urls.fileExtOf(d.fileName) ?: return false
        return e in exts
    }

    private fun isApk(d: DownloadEntry): Boolean =
        d.fileName.endsWith(".apk", true) || d.mime == "application/vnd.android.package-archive"

    private fun isImage(d: DownloadEntry): Boolean =
        d.mime.startsWith("image/") || hasExt(d, IMAGE_EXTS)

    private fun isVideo(d: DownloadEntry): Boolean =
        d.mime.startsWith("video/") || hasExt(d, VIDEO_EXTS)

    private fun isAudio(d: DownloadEntry): Boolean =
        d.mime.startsWith("audio/") || hasExt(d, AUDIO_EXTS)

    private fun isPlayableMedia(d: DownloadEntry): Boolean {
        if (d.mime.startsWith("audio/") || d.mime.startsWith("video/")) return true
        val ext = Urls.fileExtOf(d.fileName) ?: return false
        return ext in PLAYABLE_EXTS
    }

    /** Rounded surface card, settings-groupCard look: 16dp corners + soft stroke.
     *  Running rows carry an accent stroke and failed rows an error stroke, so the
     *  state reads from the card silhouette itself, not only from the chips. */
    private fun cardBg(ctx: Context, running: Boolean, failed: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(PanelUi.themedColor(ctx, R.attr.colorSurface, 0xFFFFFFFF.toInt()))
            setStroke(dp(1).coerceAtLeast(1), when {
                running -> (PanelUi.accent(ctx) and 0x00FFFFFF) or (0x99 shl 24)
                failed -> (PanelUi.themedColor(ctx, R.attr.colorError, 0xFFD93025.toInt())
                    and 0x00FFFFFF) or (0x99 shl 24)
                else -> softOutline(ctx)
            })
        }

    /** Theme outline at ~35% alpha — soft stroke that adapts to every theme. */
    private fun softOutline(ctx: Context): Int =
        (PanelUi.themedColor(ctx, R.attr.colorOutline, 0xFF9AA0A6.toInt()) and 0x00FFFFFF) or (0x59 shl 24)

    /** List backdrop: background and surfaceVariant blended 50/50 (settings look). */
    private fun backdrop(ctx: Context): Int = blend(
        PanelUi.themedColor(ctx, android.R.attr.colorBackground, 0xFFFFFFFF.toInt()),
        PanelUi.themedColor(ctx, R.attr.colorSurfaceVariant, 0xFFF1F3F4.toInt()),
        0.5f)

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val ar = a shr 16 and 0xFF
        val ag = a shr 8 and 0xFF
        val ab = a and 0xFF
        val br = b shr 16 and 0xFF
        val bg = b shr 8 and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * ratio).toInt()
        val g = (ag + (bg - ag) * ratio).toInt()
        val bl = (ab + (bb - ab) * ratio).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun darken(c: Int): Int {
        val r = ((c shr 16 and 0xFF) * 0.58f).toInt().coerceAtMost(255)
        val g = ((c shr 8 and 0xFF) * 0.58f).toInt().coerceAtMost(255)
        val b = ((c and 0xFF) * 0.58f).toInt().coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun luminance(c: Int): Float =
        0.299f * ((c shr 16 and 0xFF) / 255f) +
            0.587f * ((c shr 8 and 0xFF) / 255f) +
            0.114f * ((c and 0xFF) / 255f)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "jfif")
        private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "mkv", "avi", "mov", "3gp", "3gpp", "mpg", "mpeg", "ts", "flv")
        private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr", "mid", "midi", "aiff")
        private val PLAYABLE_EXTS = setOf(
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr",
            "mp4", "m4v", "webm", "mkv", "avi", "mov", "3gp", "3gpp", "mpg", "mpeg", "ts")
    }
}
