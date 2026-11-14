package com.zephyr.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.URL
import java.net.URLDecoder
import java.util.Calendar
import kotlin.coroutines.resume

/** Download engine v1.7.8 — own HTTP fetch (cookies + UA + referer), MediaStore/app-dir/SAF
 *  storage, notifications, Room records, and a REAL download manager:
 *   - max 3 concurrent transfers, everything else queues with PRIORITY (bump to front);
 *   - PAUSE/RESUME via HTTP Range + a .part journal in the app dir;
 *   - optional user-chosen download folder (SAF tree, Prefs.downloadDirUri);
 *   - cancel deletes the partial and the record keeps status 4.
 *  v1.7.9:
 *   - Wi-Fi-only / night-only scheduler (Prefs.dmWifiOnly / dmNightOnly):
 *     runTransfer holds a transfer with live.waiting=true + live.reason
 *     (dm_wait_wifi / dm_wait_night) until the constraint clears (15 s poll);
 *   - YouTube ADAPTIVE mux downloads (enqueueMuxDownload): the video-only and
 *     audio-only streams are fetched into cacheDir temp files and merged with
 *     MediaMuxer (YtMuxer) into a real 1080p+ .mp4 with sound.
 *
 *  Status codes (DownloadEntry.status): 0 running/waiting · 1 done · 2 failed ·
 *  3 paused (resumable) · 4 cancelled. */
object DownloadEngine {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    const val CH = "zephyr_downloads"              // progress: silent, low importance
    const val CH_DONE = "zephyr_downloads_done"    // completions/failures that actually alert

    // ---------------- v1.7.8: queue + controls ----------------

    private const val MAX_ACTIVE = 3

    /** [reason] — v1.7.9 scheduler: human-readable wait cause when
     *  waiting=true ("Ожидание Wi-Fi…"/"Ожидание ночного окна…"), empty otherwise. */
    class Live(val downloaded: Long, val total: Long, val waiting: Boolean, val paused: Boolean,
               val reason: String = "")

    private class Ctrl {
        @Volatile var pauseReq = false
        @Volatile var cancelReq = false
    }

    private val controls = java.util.concurrent.ConcurrentHashMap<Long, Ctrl>()
    private val live = java.util.concurrent.ConcurrentHashMap<Long, Live>()

    /**
     * v1.8.3 (user: «я опять удалил, но файл всё равно пришёл»).
     *
     * Cancellation used to live only on a [Ctrl], and that was the bug. A Ctrl
     * exists only while `runTransfer` is running, so a cancel arriving while
     * the download sat in the queue landed on nothing — and worse,
     * `runTransfer` opened with `ctrl.cancelReq = false`, wiping any cancel
     * that had arrived before it started.
     *
     * The intent to cancel is therefore recorded here, where it survives both
     * cases. A transfer consults this before it begins and gives up.
     */
    private val cancelled =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

    /** Live progress for the UI — the engine knows the real total; nobody else does. */
    fun progressOf(entryId: Long): Live? = live[entryId]
    private val qLock = Any()
    private val waiters = ArrayList<Waiter>()
    private var activeCount = 0
    private var seqCounter = 0L
    private var topPriority = 0

    private class Waiter(val entryId: Long, @Volatile var priority: Int, val seq: Long,
                         val cont: CancellableContinuation<Unit>)

    /** Suspends until a transfer slot is free. Priority-bumped waiters go first. */
    private suspend fun awaitSlot(entryId: Long) {
        return suspendCancellableCoroutine { cont ->
            val w = Waiter(entryId, 0, synchronized(qLock) { seqCounter++ }, cont)
            var resumedHere = false
            synchronized(qLock) {
                waiters.add(w)
                if (activeCount < MAX_ACTIVE && waiters.maxByOrNull { it.priority * 1_000_000L - it.seq } == w) {
                    waiters.remove(w); activeCount++; resumedHere = true
                }
            }
            if (resumedHere) {
                cont.resume(Unit) {}
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                synchronized(qLock) {
                    waiters.remove(w)
                    pumpLocked()
                }
            }
        }
    }

    /** Hand the next-highest-priority waiter its slot. */
    private fun pumpLocked() {
        // cancelled-while-waiting downloads are woken so their coroutines can exit
        // (runTransfer's cancel path releases the slot we account for here)
        val wit = waiters.iterator()
        while (wit.hasNext()) {
            val w = wit.next()
            if (controls[w.entryId]?.cancelReq == true) {
                wit.remove()
                activeCount++
                try { w.cont.resume(Unit) {} } catch (_: Exception) { activeCount-- }
            }
        }
        while (activeCount < MAX_ACTIVE && waiters.isNotEmpty()) {
            val best = waiters.maxByOrNull { it.priority * 1_000_000L - it.seq } ?: return
            waiters.remove(best)
            activeCount++
            try {
                best.cont.resume(Unit) {
                    synchronized(qLock) { activeCount-- }
                    pumpThreadSafe()
                }
            } catch (_: Exception) {
                activeCount--
            }
        }
    }

    private fun pumpThreadSafe() {
        synchronized(qLock) { pumpLocked() }
    }

    private fun releaseSlot() {
        synchronized(qLock) { activeCount-- }
        pumpThreadSafe()
    }

    /** v1.7.8 UI hook: move a waiting download to the front of the queue. */
    fun bump(entryId: Long) {
        synchronized(qLock) {
            topPriority++
            waiters.filter { it.entryId == entryId }.forEach { it.priority = topPriority }
        }
        try {
            scope.launch {
                try { appContext.db.downloadDao().setPriority(entryId, ++topPriority) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** v1.7.8 UI snapshot: entryId → live progress. */
    fun liveSnapshot(): Map<Long, Live> = live.toMap()

    fun isWaiting(entryId: Long): Boolean = live[entryId]?.waiting == true

    // ---------------- pause / resume / cancel ----------------

    fun pause(entryId: Long) {
        val c = controls[entryId] ?: run {
            // not transferring right now (queued or finished) — mark DB paused anyway
            markPausedInDb(entryId)
            return
        }
        c.pauseReq = true
    }

    private fun markPausedInDb(entryId: Long) {
        try {
            scope.launch {
                try { appContext.db.downloadDao().updateStatus(entryId, 3) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Stop a transfer — queued, running, or about to start.
     *
     * v1.8.3: the intent is recorded in [cancelled] FIRST. Setting a flag on a
     * Ctrl only works for a download already streaming; one still waiting for
     * a slot had no Ctrl to set, so the cancel was silently dropped and the
     * file arrived anyway.
     *
     * The notification goes too. A progress bar left behind for something that
     * has stopped is a lie, and the user pressed the button precisely to be
     * rid of it.
     */
    fun cancel(entryId: Long) {
        cancelled.add(entryId)
        controls[entryId]?.let { it.cancelReq = true; it.pauseReq = false }
        partialFile(entryId).delete()
        live.remove(entryId)
        markCancelledInDb(entryId)
        clearNotification(entryId)
        cancelSystemTransfer(entryId)
    }

    /**
     * v1.8.4: stop the transfer when Android's own downloader is carrying it.
     *
     * A file this engine cannot fetch is handed to `DownloadManager` and the
     * row keeps `path = "dm:<systemId>"`. Nothing above touches that download —
     * which is why cancelling appeared to do nothing and the file arrived
     * regardless. `remove()` stops it and drops the partial.
     */
    private fun cancelSystemTransfer(entryId: Long) {
        scope.launch {
            try {
                val row = appContext.db.downloadDao().byId(entryId) ?: return@launch
                if (!row.path.startsWith("dm:")) return@launch
                val sysId = row.path.removePrefix("dm:").toLongOrNull() ?: return@launch
                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE)
                    as android.app.DownloadManager
                dm.remove(sysId)
            } catch (_: Throwable) {}
        }
    }


    private fun markCancelledInDb(entryId: Long) {
        try {
            scope.launch {
                try { appContext.db.downloadDao().updateStatus(entryId, 4) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Resumes a paused entry by re-issuing the stored request with a Range header. */
    fun resume(entryId: Long) {
        scope.launch {
            var req: Request? = null
            var fileName = ""
            var mime = ""
            var muxAudio: String? = null
            try {
                val e = appContext.db.downloadDao().byId(entryId) ?: return@launch
                if (e.status != 3 || !e.url.startsWith("http")) return@launch
                fileName = e.fileName
                mime = e.mime
                val h = if (e.headers.isNotEmpty()) {
                    try { JSONObject(e.headers) } catch (_: Exception) { null }
                } else null
                muxAudio = h?.optString("muxAudio")?.ifEmpty { null }
                req = Request(e.url, e.fileName, e.mime,
                    h?.optString("ua")?.ifEmpty { null },
                    h?.optString("cookies")?.ifEmpty { null },
                    h?.optString("referer")?.ifEmpty { null })
            } catch (_: Exception) { return@launch }
            val r = req ?: return@launch
            if (muxAudio != null) {
                // v1.7.9: a paused MUX download restarts the whole video+audio
                // pipeline (its cache temp partials are never kept on pause)
                runMuxTransfer(r, muxAudio, entryId)
            } else {
                runTransfer(r, entryId, resuming = true, quiet = false,
                    nameHint = fileName, mimeHint = mime, onFinish = {})
            }
        }
    }

    /** Retry a failed/cancelled entry from scratch. */
    fun retry(entryId: Long) {
        scope.launch {
            var req: Request? = null
            var muxAudio: String? = null
            try {
                val e = appContext.db.downloadDao().byId(entryId) ?: return@launch
                if (e.status != 2 && e.status != 4 && e.status != 3) return@launch
                val h = if (e.headers.isNotEmpty()) {
                    try { JSONObject(e.headers) } catch (_: Exception) { null }
                } else null
                muxAudio = h?.optString("muxAudio")?.ifEmpty { null }
                partialFile(entryId).delete()
                appContext.db.downloadDao().updateProgress(entryId, 0, 0)
                appContext.db.downloadDao().updateStatus(entryId, 0)
                req = Request(e.url, e.fileName, e.mime,
                    h?.optString("ua")?.ifEmpty { null },
                    h?.optString("cookies")?.ifEmpty { null },
                    h?.optString("referer")?.ifEmpty { null })
            } catch (_: Exception) { return@launch }
            val r = req ?: return@launch
            if (muxAudio != null) runMuxTransfer(r, muxAudio, entryId)
            else runTransfer(r, entryId, resuming = false, quiet = false,
                nameHint = "", mimeHint = "", onFinish = {})
        }
    }

    private fun headersJson(req: Request, muxAudio: String? = null): String = try {
        val o = JSONObject()
        req.userAgent?.let { o.put("ua", it) }
        req.cookies?.let { o.put("cookies", it) }
        req.referer?.let { o.put("referer", it) }
        // v1.7.9: mux rows remember their audio stream URL so PAUSE/RETRY can
        // restart the whole video+audio pipeline instead of the video alone
        muxAudio?.let { o.put("muxAudio", it) }
        o.toString()
    } catch (_: Exception) { "" }

    private fun partialFile(entryId: Long): File =
        File(File(appContext.filesDir, "partial").apply { mkdirs() }, "$entryId.part")

    // ---------------- init ----------------

    fun init(context: Context) {
        appContext = context.applicationContext
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, context.getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, context.getString(R.string.dl_done_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        // v1.7.8: rows left "running" by a previous process death become PAUSED —
        // resumable from the Files → Загрузки manager instead of lying forever.
        try {
            scope.launch {
                try {
                    for (e in appContext.db.downloadDao().allOnce()) {
                        if (e.status == 0) appContext.db.downloadDao().updateStatus(e.id, 3)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    data class Request(
        val url: String,
        val fileName: String,
        val mime: String,
        val userAgent: String?,
        val cookies: String?,
        val referer: String?,
        val contentDispositionName: String? = null
    )

    /** Successful download result. */
    private class Outcome(val name: String, val path: String, val size: Long, val mime: String)

    private object Paused
    private object Cancelled

    /** v1.7.7 QUIET chain mode + v1.7.8 queueing. */
    fun enqueue(req: Request, quiet: Boolean = false,
                onStarted: (Long) -> Unit = {}, onFinish: (success: Boolean) -> Unit = {}) {
        scope.launch {
            val entryId = recordStart(req)
            withContext(Dispatchers.Main) { onStarted(entryId) }
            runTransfer(req, entryId, resuming = false, quiet = quiet, nameHint = "", mimeHint = "", onFinish = onFinish)
        }
    }

    /** One queued transfer: waits for a slot, streams to a .part file (Range-resumable),
     *  honors pause/cancel, finalizes into the storage target on completion. */
    private suspend fun runTransfer(req: Request, entryId: Long, resuming: Boolean, quiet: Boolean,
                                    nameHint: String, mimeHint: String,
                                    onFinish: (success: Boolean) -> Unit) {
        val ctrl = controls.getOrPut(entryId) { Ctrl() }
        ctrl.pauseReq = false
        // v1.8.3: this used to be an unconditional `ctrl.cancelReq = false`,
        // which erased a cancel that arrived while the download was queued —
        // the commonest case, since that is when the user is still looking at
        // it. A transfer that was cancelled before it began now leaves at once.
        if (cancelled.contains(entryId)) {
            cancelled.remove(entryId)
            controls.remove(entryId); live.remove(entryId)
            partialFile(entryId).delete()
            clearNotification(entryId)
            withContext(Dispatchers.Main) { onFinish(false) }
            return
        }
        ctrl.cancelReq = false
        if (!resuming) live[entryId] = Live(0, 0, waiting = true, paused = false)
        awaitSlot(entryId)
        if (ctrl.cancelReq) {
            releaseSlot(); controls.remove(entryId); live.remove(entryId)
            withContext(Dispatchers.Main) { onFinish(false) }
            return
        }
        // v1.7.9 scheduler: Wi-Fi-only / night-only — hold the transfer (15 s
        // polls) until the user's constraints are satisfied; live.waiting=true
        // + live.reason tell the Files UI WHY it waits. Cancel follows the
        // cancel path below; pause breaks out and downloadRange's existing
        // pause handling takes over on its first loop iteration.
        var name = sanitize(if (resuming && nameHint.isNotEmpty()) nameHint
        else req.fileName.ifEmpty { Urls.guessName(req.url) })
        awaitConstraints(entryId, ctrl,
            haveBytes = if (resuming) partialFile(entryId).length() else 0L, name = name)
        if (ctrl.cancelReq) {
            releaseSlot(); controls.remove(entryId); live.remove(entryId)
            withContext(Dispatchers.Main) { onFinish(false) }
            return
        }
        live[entryId] = Live(partialFile(entryId).length(), 0, waiting = false, paused = false)
        var ok = false
        var paused = false
        var mime = if (resuming && mimeHint.isNotEmpty()) mimeHint else req.mime
        var path = ""
        var size = 0L
        try {
            val dataBytes = if (resuming) null else parseDataUri(req.url)
            if (dataBytes != null) {
                val r = storeBytes(req, entryId, dataBytes.first, dataBytes.second)
                if (r != null) { ok = true; name = r.name; path = r.path; size = r.size; mime = r.mime }
            } else {
                val r = downloadResumable(req, entryId, ctrl, resuming, nameHint, mimeHint)
                when {
                    r is Outcome -> { ok = true; name = r.name; path = r.path; size = r.size; mime = r.mime }
                    r === Paused -> paused = true
                    r === Cancelled -> {}
                }
            }
        } catch (_: Throwable) {
            // v1.7.12: Throwable, not Exception — this device has a documented
            // (v1.7.4) history of throwing UnsatisfiedLinkError; an escaping Error
            // used to kill the coroutine, LEAK one of the MAX_ACTIVE=3 slots and
            // skip the failure verdict/fallbacks below.
        }
        // v1.8.3: one question, asked once, instead of trusting a flag that the
        // start of this function used to clear.
        val wasCancelled = ctrl.cancelReq || cancelled.contains(entryId)
        when {
            // A cancel can land after the last byte but before this line. The
            // user asked for it gone, so it goes — otherwise the file appears
            // anyway and the button looks broken, which is what was reported.
            ok && wasCancelled -> {
                try { if (path.startsWith("/")) File(path).delete() } catch (_: Exception) {}
                clearNotification(entryId)
            }
            ok -> {
                try { recordFinish(entryId, true, path, size, name, mime) } catch (_: Exception) {}
                notifyDone(entryId, name, path)
                completionToast(name)
            }
            paused -> {
                try { appContext.db.downloadDao().updateStatus(entryId, 3) } catch (_: Exception) {}
            }
            // v1.8.3: cancelling used to leave the progress notification frozen
            // on screen, and — because the flag had been reset — often ended in
            // the `else` branch below, announcing a FAILURE for something the
            // user deliberately stopped. Both were wrong.
            wasCancelled -> clearNotification(entryId)
            else -> {
                try { recordFinish(entryId, false, "", 0, name, mime) } catch (_: Exception) {}
                if (!quiet) notifyFailed(entryId, name)
            }
        }
        cancelled.remove(entryId)
        controls.remove(entryId)
        live.remove(entryId)
        releaseSlot()
        withContext(Dispatchers.Main) { onFinish(ok) }
    }

    /** v1.7.7: the download chain ended — post the single final notification. */
    fun finishQuiet(entryId: Long, ok: Boolean, name: String, path: String, size: Long, mime: String) {
        scope.launch {
            try { recordFinish(entryId, ok, path, size, name, mime) } catch (_: Exception) {}
        }
        if (ok) {
            notifyDone(entryId, name, path)
            completionToast(name)
        } else {
            notifyFailed(entryId, name)
        }
    }

    /** v1.7.7: drop the stuck progress notification of a chain download. */
    fun clearNotification(entryId: Long) {
        try { nm().cancel((20000 + entryId % 40000).toInt()) } catch (_: Exception) {}
    }

    /** v1.7.7: silent system-DownloadManager fallback finished — post the Zephyr
     *  completion notification (called from App.kt's ACTION_DOWNLOAD_COMPLETE). */
    fun notifySystemDone(context: Context, entryId: Long, name: String, path: String) {
        try {
            val open = viewPendingIntent(entryId, path, name) ?: panelPendingIntent(entryId)
            val b = NotificationCompat.Builder(appContext, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(appContext.getString(R.string.dl_complete))
                .setContentText(name)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open)
            NotificationManagerCompat.from(appContext).notify(
                (20000 + entryId % 40000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    /** v1.7.7: silent system-DownloadManager fallback failed. */
    fun notifySystemFailed(context: Context, entryId: Long, name: String) {
        try {
            val b = NotificationCompat.Builder(appContext, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(appContext.getString(R.string.dl_failed))
                .setContentText(name)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(panelPendingIntent(entryId))
            NotificationManagerCompat.from(appContext).notify(
                (20000 + entryId % 40000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    /**
     * v1.7.4: stores an in-memory payload (data: URI body, blob: fetched by the
     * page via the JS bridge) through the SAME routing/recording/notification
     * pipeline as network downloads.
     */
    fun enqueueBytes(url: String, nameHint: String, mimeHint: String,
                     bytes: ByteArray, onStarted: (Long) -> Unit = {}, onFinish: (Boolean) -> Unit = {}) {
        val req = Request(url, nameHint.ifEmpty { "file" }, mimeHint, null, null, null)
        scope.launch {
            val entryId = recordStart(req)
            withContext(Dispatchers.Main) { onStarted(entryId) }
            var ok = false
            var name = sanitize(req.fileName)
            var path = ""
            var size = 0L
            var mime = req.mime
            try {
                val r = storeBytes(req, entryId, mimeHint, bytes)
                if (r != null) { ok = true; name = r.name; path = r.path; size = r.size; mime = r.mime }
            } catch (_: Throwable) {
                // v1.7.12: Throwable — an Error must land in the failure branch
                // (notifyFailed + onFinish(false)) instead of killing the coroutine.
            }
            try { recordFinish(entryId, ok, path, size, name, mime) } catch (_: Exception) {}
            if (ok) {
                notifyDone(entryId, name, path)
                completionToast(name)
            } else {
                notifyFailed(entryId, name)
            }
            withContext(Dispatchers.Main) { onFinish(ok) }
        }
    }

    /**
     * v1.7.4: parses a data: URI into (mime, bytes). Supports ;base64 and
     * URL-encoded plain bodies; returns null for anything unparsable.
     */
    fun parseDataUri(url: String): Pair<String, ByteArray>? {
        try {
            if (!url.startsWith("data:", ignoreCase = true)) return null
            val comma = url.indexOf(',')
            if (comma < 5) return null
            val head = url.substring(5, comma)
            val parts = head.split(';')
            val mime = parts.getOrNull(0)?.trim()?.lowercase().orEmpty()
                .ifEmpty { "application/octet-stream" }
            val isB64 = parts.any { it.equals("base64", ignoreCase = true) }
            val payload = url.substring(comma + 1)
            val bytes = if (isB64) {
                android.util.Base64.decode(payload,
                    android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE)
            } else {
                URLDecoder.decode(payload, "UTF-8").toByteArray()
            }
            if (bytes.isEmpty()) return null
            return mime to bytes
        } catch (_: Exception) {
            return null
        }
    }

    /** True when the URL needs the page-side blob fetch (JS bridge) instead of HTTP. */
    fun isBlobUrl(url: String): Boolean = url.startsWith("blob:", ignoreCase = true)

    private suspend fun recordStart(req: Request, muxAudio: String? = null): Long = try {
        appContext.db.downloadDao().insert(
            com.zephyr.browser.db.DownloadEntry(
                url = req.url, fileName = req.fileName, mime = req.mime, path = "", size = 0,
                status = 0, headers = headersJson(req, muxAudio)
            )
        )
    } catch (_: Exception) { System.currentTimeMillis() }   // unique-ish id even if the row failed

    /** v1.7.3: also persists the resolved mime and the final (extension-repaired) name. */
    private suspend fun recordFinish(id: Long, ok: Boolean, path: String, size: Long, name: String, mime: String) {
        appContext.db.downloadDao().update(id, if (ok) 1 else 2, size, path, mime, name)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    // ---------------- v1.7.8: resumable transfer core ----------------

    /**
     * Streams [req] into the .part journal, honoring [ctrl]. On pause the partial
     * stays on disk + the DB row keeps downloaded/total. On completion the partial
     * is copied into the storage target (SAF folder / MediaStore / app dir).
     */
    private suspend fun downloadResumable(req: Request, entryId: Long, ctrl: Ctrl,
                                          resuming: Boolean, nameHint: String, mimeHint: String): Any? {
        val partial = partialFile(entryId)
        val have = if (resuming) partial.length() else 0L
        if (!resuming) partial.delete()
        val result = downloadRange(req, entryId, ctrl, have, resuming, nameHint, mimeHint)
        if (result !is Outcome) return result
        // finalize: copy the partial into the final target
        try {
            val routed = openTargetStream(result.name, result.mime, result.size, entryId)
                ?: return run { partial.delete(); null }
            val out = routed.second
            try {
                FileInputStream(partial).use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (ctrl.cancelReq) { out.close(); partial.delete(); return Cancelled }
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.flush()
            } finally {
                try { out.close() } catch (_: Exception) {}
            }
            partial.delete()
            return Outcome(result.name, routed.first, result.size, result.mime)
        } catch (_: Exception) {
            partial.delete()
            return null
        }
    }

    /** Opens the connection (Range when resuming), streams into [partialFile]. */
    private suspend fun downloadRange(req: Request, entryId: Long, ctrl: Ctrl, haveBytes: Long,
                                      resuming: Boolean, nameHint: String, mimeHint: String): Any? {
        var current = req.url
        var redirects = 0
        var conn: java.net.HttpURLConnection? = null
        try {
            while (true) {
                conn = URL(current).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = false
                req.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                req.cookies?.let { conn.setRequestProperty("Cookie", it) }
                req.referer?.let { conn.setRequestProperty("Referer", it) }
                conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                conn.setRequestProperty("Accept-Language", "ru,en-US;q=0.8,en;q=0.6")
                if (haveBytes > 0) conn.setRequestProperty("Range", "bytes=$haveBytes-")
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (code in 300..399 && !loc.isNullOrBlank() && redirects < 5) {
                    val next = URL(URL(current), loc).toString()
                    conn.disconnect()
                    current = next
                    redirects++
                    continue
                }
                if (code !in 200..299) return null
                val partial = partialFile(entryId)
                if (haveBytes > 0 && code != 206) {
                    // server ignored the Range → start over from byte 0
                    partial.delete()
                    return downloadRange(req, entryId, ctrl, 0, false, "", "")
                }
                val remaining = conn.contentLengthLong
                val total = if (remaining > 0) haveBytes + remaining else 0L
                val headerMime = (conn.contentType ?: "").substringBefore(';').trim().lowercase()

                // a fresh download (no usable hints) sniffs the real type from the
                // first 64 body bytes; a resumed one reuses the persisted name/mime
                val sniff = !resuming || nameHint.isEmpty() || partial.length() == 0L
                val rawName = sanitize(if (resuming && nameHint.isNotEmpty()) nameHint
                else req.fileName.ifEmpty { Urls.guessName(req.url) })
                val ins0 = try { conn.inputStream } catch (_: Exception) { return null }
                var target: java.io.FileOutputStream? = null
                try {
                    var name = rawName
                    var realMime = if (!sniff && mimeHint.isNotEmpty()) mimeHint else headerMime
                    var headLen = 0
                    val head = ByteArray(64)
                    if (sniff) {
                        while (headLen < head.size) {
                            if (ctrl.cancelReq) return Cancelled
                            val n = ins0.read(head, headLen, head.size - headLen)
                            if (n < 0) break
                            headLen += n
                        }
                        realMime = MimeType.resolve(headerMime, req.mime, head.copyOf(headLen), rawName)
                        name = repairName(rawName, realMime)
                        try { appContext.db.downloadDao().updateNameMime(entryId, name, realMime) } catch (_: Exception) {}
                    }
                    target = java.io.FileOutputStream(partial, true)
                    if (sniff && headLen > 0) target.write(head, 0, headLen)
                    var read = partial.length()
                    notifyProgress(entryId, name, read, total)
                    val buf = ByteArray(64 * 1024)
                    var lastDb = 0L
                    var lastNotif = 0L
                    while (true) {
                        if (ctrl.cancelReq) { target.flush(); return Cancelled }
                        if (ctrl.pauseReq) {
                            target.flush()
                            try { appContext.db.downloadDao().updateProgress(entryId, read, total) } catch (_: Exception) {}
                            live[entryId] = Live(read, total, false, true)
                            return Paused
                        }
                        val n = ins0.read(buf)
                        if (n < 0) break
                        target.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastNotif > 700) {
                            lastNotif = now
                            notifyProgress(entryId, name, read, total)
                        }
                        if (now - lastDb > 2500) {
                            lastDb = now
                            try { appContext.db.downloadDao().updateProgress(entryId, read, total) } catch (_: Exception) {}
                        }
                        live[entryId] = Live(read, total, false, false)
                    }
                    target.flush()
                    try { appContext.db.downloadDao().updateProgress(entryId, read, total) } catch (_: Exception) {}
                    return Outcome(name, "", read, realMime)
                } finally {
                    try { target?.close() } catch (_: Exception) {}
                    try { ins0.close() } catch (_: Exception) {}
                }
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** v1.7.4: in-memory payload storage (data:/blob:) — same routing as HTTP. */
    private fun storeBytes(req: Request, entryId: Long, mimeHint: String, bytes: ByteArray): Outcome? {
        val rawName = sanitize(req.fileName.ifEmpty { "file" })
        val realMime = MimeType.resolve(mimeHint, req.mime, bytes.copyOf(minOf(bytes.size, 64)), rawName)
        val name = repairName(rawName, realMime)
        val routed = openTargetStream(name, realMime, bytes.size.toLong(), entryId) ?: return null
        try {
            routed.second.write(bytes)
            routed.second.flush()
        } finally {
            try { routed.second.close() } catch (_: Exception) {}
        }
        notifyProgress(entryId, name, 0, bytes.size.toLong())
        return Outcome(name, routed.first, bytes.size.toLong(), realMime)
    }

    /** Extension repair: "site.com/photo/12345" becomes "12345.png" on disk. */
    private fun repairName(rawName: String, realMime: String): String {
        if (MimeType.extOf(rawName) == null) {
            val repairedExt = MimeType.MIME_EXT[realMime]
            if (repairedExt != null) return "${rawName.trimEnd('.')}.$repairedExt"
        }
        return rawName
    }

    /**
     * Opens the storage target for [name]/[realMime]: the user's chosen SAF folder
     * (v1.7.8, Prefs.downloadDirUri) first, then MediaStore on API 29+ for media
     * types (Pictures/Movies/Music/Zephyr), the app dir otherwise.
     * Returns (final path-or-uri, stream) or null when the target can't be opened.
     */
    private fun openTargetStream(name: String, realMime: String, total: Long,
                                 entryId: Long = 0): Pair<String, OutputStream>? {
        // v1.7.8: user-chosen folder wins when set and writable
        val dirUri = try { Prefs.downloadDirUri } catch (_: Exception) { "" }
        if (dirUri.isNotEmpty()) {
            try {
                val tree = Uri.parse(dirUri)
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree))
                val mime = if (MimeType.isGeneric(realMime)) "application/octet-stream" else realMime
                val outUri = DocumentsContract.createDocument(appContext.contentResolver, doc, mime, name)
                if (outUri != null) {
                    val s = appContext.contentResolver.openOutputStream(outUri, "wt")
                    if (s != null) return outUri.toString() to s
                }
            } catch (_: Exception) {}
        }
        return openTarget(name, realMime, total)
    }

    private fun openTarget(name: String, realMime: String, total: Long): Pair<String, OutputStream>? {
        val ext = MimeType.extOf(name) ?: ""
        val usePublic = Build.VERSION.SDK_INT >= 29 && (
            realMime.startsWith("image/") || realMime.startsWith("video/") ||
                realMime.startsWith("audio/") || realMime == "application/pdf" ||
                ext in setOf("jpg","jpeg","png","gif","webp","mp4","mkv","webm","3gp","mp3","flac","ogg","wav","pdf"))
        if (usePublic) {
            val rel = when {
                realMime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
                realMime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                realMime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            val storeMime = if (MimeType.isGeneric(realMime)) "application/octet-stream" else realMime
            val collection = MediaStore.Files.getContentUri("external")
            // v1.7.12 hardened ladder (user: «Загрузка не удалась» on photos): OEM
            // MediaStore inserts fail in exotic ways — SIZE + RELATIVE_PATH column
            // restrictions, SQLiteFullException, IllegalArgumentException, or a
            // null/throwing openOutputStream. Each variant below is fully guarded
            // so an Error can never escape either:
            //   attempt 1: DISPLAY_NAME + MIME + RELATIVE_PATH + SIZE (if total>0)
            //   attempt 2: without the SIZE column
            //   attempt 3: plain Files insert (no SIZE, no RELATIVE_PATH)
            // A successful insert whose stream can't be opened deletes its orphan
            // row; when EVERY attempt fails we fall through to the plain app-dir
            // file branch below so the download ALWAYS lands somewhere findable.
            for (attempt in 1..3) {
                var orphan: Uri? = null
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, storeMime)
                        if (attempt < 3) put(MediaStore.MediaColumns.RELATIVE_PATH, "$rel/Zephyr")
                        if (attempt == 1 && total > 0) put(MediaStore.MediaColumns.SIZE, total)
                    }
                    val outUri = appContext.contentResolver.insert(collection, cv) ?: continue
                    orphan = outUri
                    val stream = try {
                        appContext.contentResolver.openOutputStream(outUri)
                    } catch (_: Throwable) { null }
                    if (stream != null) return outUri.toString() to stream
                } catch (_: Throwable) {
                }
                // insert failed at any point, or its stream is unusable — remove
                // the orphan MediaStore row (best-effort) and try the next variant
                orphan?.let { u ->
                    try { appContext.contentResolver.delete(u, null, null) } catch (_: Throwable) {}
                }
            }
        }
        // v1.7.15 (user: «загрузка файлов не отображается в Download»): non-media
        // files (APK/zip/docs) now land in the public Downloads collection so the
        // system Downloads app finally shows them. Same 3-attempt hardening as the
        // media ladder above. Reached when the type is NOT media (usePublic false)
        // OR the media ladder above exhausted all attempts.
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Downloads.getContentUri("external")
            for (attempt in 1..3) {
                var orphan: Uri? = null
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE,
                            if (MimeType.isGeneric(realMime)) "application/octet-stream" else realMime)
                        if (attempt < 3) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Zephyr")
                    }
                    val outUri = appContext.contentResolver.insert(collection, cv) ?: continue
                    orphan = outUri
                    val stream = try { appContext.contentResolver.openOutputStream(outUri) } catch (_: Throwable) { null }
                    if (stream != null) return outUri.toString() to stream
                } catch (_: Throwable) {}
                orphan?.let { u -> try { appContext.contentResolver.delete(u, null, null) } catch (_: Throwable) {} }
            }
        }
        // existing app-dir branch stays as the last resort
        val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir, "Zephyr").apply { mkdirs() }
        var f = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.')
        val dotExt = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (f.exists()) { f = File(dir, "$base (${n++})$dotExt") }
        return f.absolutePath to f.outputStream()
    }

    private fun baseNotification(entryId: Long, name: String): NotificationCompat.Builder =
        NotificationCompat.Builder(appContext, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setOnlyAlertOnce(true)
            .setContentIntent(panelPendingIntent(entryId))

    /**
     * v1.8.2 (user: «надо добавить процент и МБ сколько скачало из скольки…
     * также в уведомление надо добавить отменить»).
     *
     * A bare progress bar answers "is it moving" and nothing else. On a phone
     * the questions are how much of my data this has spent and how long it has
     * left, and both need the numbers. So the line reads
     * `12.4 MB / 48.0 MB · 26%`, and when the server sends no length it says
     * how much has arrived rather than pretending to a percentage.
     *
     * The Cancel action is here for the same reason it was missing: until now
     * the only way to stop a download was to find the panel, and that is a long
     * walk to undo a mis-tap.
     */
    private fun notifyProgress(entryId: Long, name: String, done: Long, total: Long) {
        try {
            val b = baseNotification(entryId, name)
            b.setPriority(NotificationCompat.PRIORITY_LOW)   // pre-O: silent while transferring
            if (total > 0) {
                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                b.setProgress(100, pct, false)
                b.setContentText(
                    appContext.getString(R.string.dl_progress_fmt, human(done), human(total), pct)
                )
            } else {
                b.setProgress(0, 0, true)
                b.setContentText(appContext.getString(R.string.dl_progress_unknown_fmt, human(done)))
            }
            b.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.cancel),
                cancelPendingIntent(entryId)
            )
            nm().notify((20000 + entryId % 40000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    /** Stops the transfer straight from the shade — see [DownloadActionReceiver]. */
    private fun cancelPendingIntent(entryId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            (60000 + entryId % 30000).toInt(),
            Intent(appContext, DownloadActionReceiver::class.java).apply {
                action = DownloadActionReceiver.ACTION_CANCEL
                putExtra(DownloadActionReceiver.EXTRA_ID, entryId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** "12.4 MB" — the same shape the downloads panel shows. */
    private fun human(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 ->
            String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
    }

    /** v1.7.1: finished download alert on the IMPORTANCE_DEFAULT channel; tap opens the file itself. */
    private fun notifyDone(entryId: Long, name: String, path: String) {
        // v1.7.15 (user: «загрузка файлов не отображается в Download»): best-effort
        // MediaScanner indexing for downloads that finished as PLAIN FILES (the
        // app-dir last-resort branch) — the system Downloads/Documents apps only
        // list indexed files. content:// results are already MediaStore rows, so
        // only real "/" paths go through here.
        if (path.startsWith("/")) {
            try {
                android.media.MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
            } catch (_: Exception) {}
        }
        try {
            val open = viewPendingIntent(entryId, path, name) ?: panelPendingIntent(entryId)
            val b = NotificationCompat.Builder(appContext, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(appContext.getString(R.string.dl_complete))
                .setContentText(name)
                .setAutoCancel(true)
                .setProgress(0, 0, false)                     // clear any progress bar from the same id
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)   // pre-O: heads-up-capable
                .setContentIntent(open)
            nm().notify((20000 + entryId % 40000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    /** v1.7.1: failure replaces the (previously forever-stuck) progress notification. */
    private fun notifyFailed(entryId: Long, name: String) {
        try {
            val b = NotificationCompat.Builder(appContext, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(appContext.getString(R.string.dl_failed))
                .setContentText(name)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(panelPendingIntent(entryId))
            nm().notify((20000 + entryId % 40000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    /** ACTION_VIEW intent for the stored file (FileProvider uri for plain files, direct uri for MediaStore rows);
     *  null when no viewer target can be built → caller falls back to the Downloads panel. */
    private fun viewPendingIntent(entryId: Long, path: String, name: String): PendingIntent? {
        return try {
            val uri: Uri? = when {
                path.startsWith("/") -> androidx.core.content.FileProvider.getUriForFile(
                    appContext, appContext.packageName + ".fileprovider", File(path))
                path.startsWith("content") -> Uri.parse(path)
                else -> null
            }
            if (uri == null) return null
            val mime = appContext.contentResolver.getType(uri) ?: guessMime(name)
            PendingIntent.getActivity(
                appContext, (entryId % 60000).toInt(),
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (_: Exception) { null }
    }

    /** Opens the Downloads panel (fallback when the file itself has no viewer). */
    private fun panelPendingIntent(entryId: Long): PendingIntent = PendingIntent.getActivity(
        appContext, (entryId % 60000).toInt(),
        Intent(appContext, com.zephyr.browser.panels.PanelsActivity::class.java).apply {
            putExtra("panel", "downloads")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** v1.7.15: name-based APK check (mime providers shrug with octet-stream). */
    fun isApkName(name: String): Boolean = name.endsWith(".apk", true)

    /**
     * v1.7.15 (user: «выбираю установщик — тишина, не устанавливается»): the APK
     * install flow. Android 8+ requires REQUEST_INSTALL_PACKAGES (added to the
     * manifest) AND the per-app "unknown sources" grant — without it the system
     * installer exits silently. This gate shows a guiding dialog once, sends the
     * user to the system toggle, and fires the install intent when allowed.
     * Shared by FilesActivity (open-with sheet, dm rows) and the Downloads panel.
     */
    fun installApk(activity: android.app.Activity, uri: Uri, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val canInstall = try {
                    activity.packageManager.canRequestPackageInstalls()
                } catch (_: Exception) { true }
                if (!canInstall) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.install_unknown_title)
                        .setMessage(R.string.install_unknown_text)
                        .setPositiveButton(R.string.install_open_settings) { _, _ ->
                            try {
                                activity.startActivity(Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.packageName)))
                            } catch (_: Exception) {}
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return
                }
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            try { Toast.makeText(activity, R.string.app_not_found, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /** In-app fallback when system notifications are disabled (Android 13+ POST_NOTIFICATIONS denied):
     *  "Файл загружен: %s" becomes the only completion feedback. */
    private fun completionToast(name: String) {
        try {
            if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return
            main.post {
                try {
                    Toast.makeText(appContext, appContext.getString(R.string.dl_done_toast, name), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun nm(): NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ---------------- v1.7.7: HLS (.m3u8) downloader ----------------

    /** Downloads an HLS stream by stitching all TS segments into a single .ts file.
     *  v1.7.8: honors CANCEL between segments. */
    fun enqueueHls(url: String, name: String, userAgent: String?, cookies: String?, referer: String?) {
        scope.launch {
            val req = Request(url, name, "video/mp2t", userAgent, cookies, referer)
            val entryId = recordStart(req)
            val ctrl = controls.getOrPut(entryId) { Ctrl() }
            var ok = false
            var finalName = sanitize(name.ifEmpty { "video_" + System.currentTimeMillis() + ".ts" })
            var path = ""
            var size = 0L
            var cancelled = false
            try {
                val r = downloadHlsInternal(req, entryId, ctrl)
                when {
                    r is Outcome -> { ok = true; finalName = r.name; path = r.path; size = r.size }
                    r === Cancelled -> cancelled = true
                }
            } catch (_: Throwable) {
                // v1.7.12: Throwable — an Error must reach the failure verdict below
                // (recordFinish(false) + notifyFailed), not kill the coroutine.
            }
            if (cancelled) {
                try { appContext.db.downloadDao().updateStatus(entryId, 4) } catch (_: Exception) {}
            } else {
                try { recordFinish(entryId, ok, path, size, finalName, "video/mp2t") } catch (_: Exception) {}
            }
            if (ok) { notifyDone(entryId, finalName, path); completionToast(finalName) }
            else if (!cancelled) notifyFailed(entryId, finalName)
            controls.remove(entryId)
        }
    }

    private fun httpGetText(url: String, ua: String?, cookies: String?, referer: String?): String {
        val sb = StringBuilder()
        var current = url
        var redirects = 0
        var conn: java.net.HttpURLConnection? = null
        try {
            while (true) {
                conn = URL(current).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = false
                ua?.let { conn.setRequestProperty("User-Agent", it) }
                cookies?.let { conn.setRequestProperty("Cookie", it) }
                referer?.let { conn.setRequestProperty("Referer", it) }
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (code in 300..399 && !loc.isNullOrBlank() && redirects < 5) {
                    val next = URL(URL(current), loc).toString()
                    conn.disconnect(); current = next; redirects++; continue
                }
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                conn.inputStream.bufferedReader().use { it.forEachLine { l -> sb.append(l).append('\n') } }
                return sb.toString()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun downloadHlsInternal(req: Request, entryId: Long, ctrl: Ctrl): Any? {
        // 1) resolve master → best variant
        var playlistUrl = req.url
        var text = httpGetText(playlistUrl, req.userAgent, req.cookies, req.referer)
        if (text.contains("#EXT-X-STREAM-INF")) {
            var bestUri: String? = null
            var bestBw = -1L
            val lines = text.split('\n')
            var i = 0
            while (i < lines.size) {
                val l = lines[i].trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                    val uri = lines.getOrNull(j)?.trim()
                    if (!uri.isNullOrEmpty() && bw > bestBw) {
                        bestBw = bw
                        bestUri = URL(URL(playlistUrl), uri).toString()
                    }
                    i = j
                }
                i++
            }
            if (bestUri == null) return null
            playlistUrl = bestUri
            text = httpGetText(playlistUrl, req.userAgent, req.cookies, req.referer)
        }
        // 2) collect segments; reject encrypted streams
        if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) {
            throw java.io.IOException("AES encrypted HLS not supported")
        }
        val segments = mutableListOf<String>()
        for (raw in text.split('\n')) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            segments.add(URL(URL(playlistUrl), l).toString())
        }
        if (segments.isEmpty()) return null
        // 3) stream all segments into one .ts target
        val rawName = sanitize(req.fileName.ifEmpty { "video.ts" })
        val name = if (MimeType.extOf(rawName) == null) "${rawName.trimEnd('.')}.ts" else rawName
        val routed = openTargetStream(name, "video/mp2t", 0L, entryId) ?: return null
        var out: OutputStream? = null
        try {
            out = routed.second
            val buf = ByteArray(64 * 1024)
            var total = 0L
            for ((idx, seg) in segments.withIndex()) {
                if (ctrl.cancelReq) { try { out.close() } catch (_: Exception) {}; return Cancelled }
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = URL(seg).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 20000
                    conn.readTimeout = 60000
                    req.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                    req.cookies?.let { conn.setRequestProperty("Cookie", it) }
                    req.referer?.let { conn.setRequestProperty("Referer", it) }
                    if (conn.responseCode !in 200..299) throw java.io.IOException("seg HTTP ${conn.responseCode}")
                    conn.inputStream.use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
                if (idx % 3 == 0 || idx == segments.size - 1) {
                    notifyProgressHls(entryId, name, idx + 1, segments.size)
                }
            }
            out.flush()
            return Outcome(name, routed.first, total, "video/mp2t")
        } finally {
            try { out?.close() } catch (_: Exception) {}
        }
    }

    private fun notifyProgressHls(entryId: Long, name: String, done: Int, total: Int) {
        try {
            val b = baseNotification(entryId, name)
            b.setPriority(NotificationCompat.PRIORITY_LOW)
            b.setContentText(appContext.getString(R.string.dl_hls_progress, done, total))
            b.setProgress(total, done, false)
            nm().notify((20000 + entryId % 40000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    // ---------------- v1.7.9: Wi-Fi/night scheduler + YouTube mux downloads ----------------

    /** v1.7.9 scheduler: why the transfer must wait right now — Wi-Fi-only while
     *  off Wi-Fi, or night-only outside the 00:00–06:00 window. Null = free to go. */
    private fun constraintWaitReason(): String? {
        try {
            if (Prefs.dmWifiOnly && !isOnWifi()) return appContext.getString(R.string.dm_wait_wifi)
        } catch (_: Exception) {}
        try {
            if (Prefs.dmNightOnly &&
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 0..5
            ) return appContext.getString(R.string.dm_wait_night)
        } catch (_: Exception) {}
        return null
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            if (Build.VERSION.SDK_INT >= 23) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (_: Exception) { false }
    }

    /** v1.7.9 scheduler: holds the transfer (15 s polls) while the Wi-Fi-only /
     *  night-only constraints are unsatisfied — live.waiting=true + the human
     *  reason keep the Files UI informed. ctrl.cancelReq/ctrl.pauseReq break the
     *  loop; the caller's existing cancel/pause handling takes over from there. */
    private suspend fun awaitConstraints(entryId: Long, ctrl: Ctrl,
                                         haveBytes: Long = 0L, name: String? = null) {
        try {
            var reason: String? = constraintWaitReason() ?: return
            while (reason != null && !ctrl.cancelReq && !ctrl.pauseReq) {
                live[entryId] = Live(haveBytes, 0L, waiting = true, paused = false, reason = reason!!)
                if (name != null) notifyWaiting(entryId, name, reason!!)
                delay(15_000)
                reason = constraintWaitReason()
            }
        } catch (_: Exception) {}
    }

    /** v1.7.9: silent progress notification carrying the scheduler's wait reason. */
    private fun notifyWaiting(entryId: Long, name: String, reason: String) {
        try {
            val b = baseNotification(entryId, name)
            b.setPriority(NotificationCompat.PRIORITY_LOW)
            b.setContentText(reason)
            b.setProgress(0, 0, true)
            nm().notify((20000 + entryId % 40000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    /** v1.7.9: PHASE 3 progress notification ("Видео + аудио объединяются…"). */
    private fun notifyMuxPhase(entryId: Long, name: String) {
        try {
            val b = baseNotification(entryId, name)
            b.setPriority(NotificationCompat.PRIORITY_LOW)
            b.setContentText(appContext.getString(R.string.sniff_mux_progress))
            b.setProgress(0, 0, true)
            nm().notify((20000 + entryId % 40000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    /**
     * v1.7.9 (user: «Найденные видео — опция хорошая, но ничего не парсится»):
     * downloads a YouTube ADAPTIVE pair — the video-only [videoUrl] plus the
     * audio-only [audioUrl] — and merges them into one real 1080p+ .mp4 WITH
     * sound (YtMuxer / MediaMuxer). The row reuses the standard download
     * pipeline (recordStart → recordFinish/notifyDone), so it shows up in
     * Files → Загрузки with the usual pause/cancel controls.
     */
    fun enqueueMuxDownload(videoUrl: String, audioUrl: String, fileName: String,
                           userAgent: String?, cookies: String?, referer: String?) {
        scope.launch {
            val req = Request(videoUrl, fileName, "video/mp4", userAgent, cookies, referer)
            val entryId = recordStart(req, muxAudio = audioUrl)
            runMuxTransfer(req, audioUrl, entryId)
        }
    }

    /** The mux pipeline body — shared by [enqueueMuxDownload] and the
     *  resume()/retry() re-dispatch (a paused/cancelled mux always restarts
     *  from scratch: two raw partials cannot be Range-resumed reliably).
     *  PHASE 1 video →  PHASE 2 audio →  PHASE 3 MediaMuxer merge → routed
     *  through the SAME storage target logic as every other download. */
    private suspend fun runMuxTransfer(req: Request, audioUrl: String, entryId: Long) {
        val ctrl = controls.getOrPut(entryId) { Ctrl() }
        ctrl.pauseReq = false
        ctrl.cancelReq = false
        val name = sanitize(req.fileName.ifEmpty { "youtube_" + System.currentTimeMillis() + ".mp4" })
        val mime = "video/mp4"
        var path = ""
        var size = 0L
        var ok = false
        var videoOnly = false     // mux/audio failed → stored without the sound track
        val vFile = File(appContext.cacheDir, "mux_${entryId}_v.mp4")
        val aFile = File(appContext.cacheDir, "mux_${entryId}_a.m4a")
        live[entryId] = Live(0, 0, waiting = true, paused = false)
        awaitSlot(entryId)
        // v1.7.12: the slot must ALWAYS be released. An Error (this device's
        // documented UnsatisfiedLinkError history) escaping the phases used to
        // kill the coroutine, skip releaseSlot() below and PERMANENTLY leak one
        // of the MAX_ACTIVE=3 slots — after 3 leaks every download wedged.
        try {
            try {
                // v1.7.9 scheduler (Wi-Fi-only / night-only) applies here too
                if (!ctrl.cancelReq) awaitConstraints(entryId, ctrl, name = name)
                if (!ctrl.cancelReq && !ctrl.pauseReq) {
                    // PHASE 1 — the adaptive VIDEO-ONLY stream into a raw temp file
                    val vLen = fetchToFile(req.url, vFile, ctrl, entryId, name,
                        req.userAgent, req.cookies, req.referer, addedDone = 0L, addedTotal = 0L)
                    if (vLen != null && !ctrl.cancelReq && !ctrl.pauseReq) {
                        // PHASE 2 — the audio-only stream; progress counts on top of
                        // the finished video bytes (combined byte scale)
                        val aLen = fetchToFile(audioUrl, aFile, ctrl, entryId, name,
                            req.userAgent, req.cookies, req.referer,
                            addedDone = vLen, addedTotal = vLen)
                        if (!ctrl.cancelReq && !ctrl.pauseReq) {
                            // PHASE 3 — merge. MediaMuxer only writes to a real file,
                            // so mux into a cache temp and stream it to the target.
                            val merged = if (aLen != null) {
                                live[entryId] = Live(vLen + aLen, vLen + aLen, waiting = false, paused = false)
                                notifyMuxPhase(entryId, name)
                                try { YtMuxer.muxToTemp(vFile, aFile) } catch (_: Exception) { null }
                            } else null
                            // mux failed OR the audio fetch failed → keep the (silent)
                            // video rather than nothing; the notification says so
                            if (merged == null) videoOnly = true
                            val src = merged ?: vFile
                            val routed = streamFileToTarget(src, name, mime, entryId, ctrl)
                            if (routed != null) { ok = true; path = routed; size = src.length() }
                            try { merged?.delete() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Throwable) {
                // v1.7.12: Throwable — an Error lands in the failure verdict below
                // (recordFinish(false) + notifyFailed) instead of killing the coroutine.
            } finally {
                try { vFile.delete() } catch (_: Exception) {}
                try { aFile.delete() } catch (_: Exception) {}
            }
            val cancelled = ctrl.cancelReq
            val paused = !cancelled && !ok && ctrl.pauseReq
            when {
                ok -> {
                    try { recordFinish(entryId, true, path, size, name, mime) } catch (_: Exception) {}
                    val display = if (videoOnly)
                        "$name (${appContext.getString(R.string.sniff_mux_failed)})" else name
                    notifyDone(entryId, display, path)
                    completionToast(display)
                }
                cancelled -> { /* DB already marked status 4 by cancel() */ }
                paused -> {
                    // pause = restart from scratch next time (downloaded=0)
                    try {
                        appContext.db.downloadDao().updateProgress(entryId, 0, 0)
                        appContext.db.downloadDao().updateStatus(entryId, 3)
                    } catch (_: Exception) {}
                }
                else -> {
                    try { recordFinish(entryId, false, "", 0, name, mime) } catch (_: Exception) {}
                    notifyFailed(entryId, name)
                }
            }
            controls.remove(entryId)
            live.remove(entryId)
        } finally {
            releaseSlot()
        }
    }

    /** Streams [url] into the RAW temp file [dest] (no .part journal, no storage
     *  routing) for the mux pipeline. [addedDone]/[addedTotal] shift the live
     *  byte counters so PHASE 2 counts on top of the finished video bytes.
     *  Pause cannot resume a mux partial → it aborts like cancel (restart).
     *  Returns the byte size on success, null on abort or network failure. */
    private fun fetchToFile(url: String, dest: File, ctrl: Ctrl, entryId: Long, name: String,
                            userAgent: String?, cookies: String?, referer: String?,
                            addedDone: Long, addedTotal: Long): Long? {
        var current = url
        var redirects = 0
        var conn: java.net.HttpURLConnection? = null
        try {
            while (true) {
                conn = URL(current).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = false
                userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                cookies?.let { conn.setRequestProperty("Cookie", it) }
                referer?.let { conn.setRequestProperty("Referer", it) }
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (code in 300..399 && !loc.isNullOrBlank() && redirects < 5) {
                    val next = URL(URL(current), loc).toString()
                    conn.disconnect(); current = next; redirects++; continue
                }
                if (code !in 200..299) return null
                val total = conn.contentLengthLong
                val ins = try { conn.inputStream } catch (_: Throwable) { return null }
                try {
                    java.io.FileOutputStream(dest).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var read = 0L
                        var lastNotif = 0L
                        while (true) {
                            // pause cannot Range-resume two mux partials — abort
                            // (runMuxTransfer marks it restartable-paused)
                            if (ctrl.cancelReq || ctrl.pauseReq) return null
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            if (now - lastNotif > 700) {
                                lastNotif = now
                                val lt = when {
                                    total > 0 && addedTotal > 0 -> addedTotal + total
                                    total > 0 -> total
                                    else -> 0L
                                }
                                live[entryId] = Live(addedDone + read, lt, waiting = false, paused = false)
                                notifyProgress(entryId, name, addedDone + read, lt)
                            }
                        }
                        out.flush()
                    }
                    return dest.length()
                } finally {
                    try { ins.close() } catch (_: Exception) {}
                }
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Streams a finished temp file into the SAME storage routing every other
     *  download finalizes through (SAF folder → MediaStore Movies → app dir).
     *  Returns the final path/uri, or null when the target can't be written. */
    private fun streamFileToTarget(src: File, name: String, mime: String,
                                   entryId: Long, ctrl: Ctrl): String? {
        return try {
            val routed = openTargetStream(name, mime, src.length(), entryId) ?: return null
            try {
                routed.second.use { out ->
                    FileInputStream(src).use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (ctrl.cancelReq) return null
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.flush()
                }
                routed.first
            } catch (_: Throwable) { null }
        } catch (_: Throwable) { null }
    }

    fun guessMime(name: String): String = when (MimeType.extOf(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "html", "htm" -> "text/html"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "torrent" -> "application/x-bittorrent"
        else -> "*/*"
    }
}
