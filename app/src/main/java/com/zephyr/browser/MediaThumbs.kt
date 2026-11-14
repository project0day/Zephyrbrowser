package com.zephyr.browser

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever

/**
 * v1.7.25b: real thumbnails + real resolution for the video sniffer.
 *
 * The sheet used to show only a text label (user: «я бы сделал чтобы в окне
 * сниффера он находил бы и показывал сразу мелкие миниатюры видео и разрешения
 * и кнопку скачать»). [probe] pulls a frame and the intrinsic dimensions
 * straight from the stream with [MediaMetadataRetriever], using the page's own
 * User-Agent / Referer / Cookie so hot-link-protected CDNs still answer.
 *
 * Blocking — always call it from a background thread. Results are cached by URL
 * so reopening the sheet is instant.
 */
object MediaThumbs {

    /** What a probe learned about one stream. */
    data class Meta(val width: Int, val height: Int, val durationMs: Long, val frame: Bitmap?)

    private val cache = LinkedHashMap<String, Meta>()
    private const val MAX_CACHE = 24

    @Synchronized
    fun cached(url: String): Meta? = cache[url]

    @Synchronized
    private fun put(url: String, m: Meta) {
        cache[url] = m
        while (cache.size > MAX_CACHE) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)?.frame?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Read dimensions, duration and one preview frame from [url].
     * Returns null when the stream cannot be opened (common for DRM, some HLS
     * variants and hosts that reject non-browser requests).
     */
    fun probe(url: String, userAgent: String?, referer: String?, cookies: String?): Meta? {
        cached(url)?.let { return it }
        val mmr = MediaMetadataRetriever()
        return try {
            val headers = HashMap<String, String>()
            userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
            referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
            cookies?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
            mmr.setDataSource(url, headers)

            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // a frame a few seconds in — frame 0 is very often a black fade-in
            val at = if (dur > 8000) 4_000_000L else 1_000_000L
            val frame = try {
                mmr.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: mmr.frameAtTime
            } catch (_: Throwable) { null }
            val small = frame?.let { scaleDown(it, 320) }
            if (small !== frame) frame?.recycle()

            if (w == 0 && h == 0 && small == null) null
            else Meta(w, h, dur, small).also { put(url, it) }
        } catch (_: Throwable) { null } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    private fun scaleDown(b: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxEdge) return b
        val ratio = maxEdge.toFloat() / longest
        return try {
            Bitmap.createScaledBitmap(b, (b.width * ratio).toInt(), (b.height * ratio).toInt(), true)
        } catch (_: Throwable) { b }
    }

    /** "1:23:45" / "4:07" from a duration in milliseconds; empty when unknown. */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    /** "1920×1080 · 1080p" style badge; empty when the size is unknown. */
    fun resolutionBadge(w: Int, h: Int): String = if (w > 0 && h > 0) h.toString() + "p" else ""
}
