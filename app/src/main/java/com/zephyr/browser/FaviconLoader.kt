package com.zephyr.browser

import android.content.Context
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

/** Favicons: memory LRU + disk cache + colored letter-avatar fallback. Icons stay COLORED (never tinted). */
object FaviconLoader {

    private const val MEM = 48
    private lateinit var diskDir: File
    private val mem = object : LruCache<String, Bitmap>(64) {}

    fun init(context: Context) {
        diskDir = File(context.filesDir, "favicons").apply { mkdirs() }
    }

    fun keyFor(url: String): String {
        val host = Urls.domainOf(url)
        val md = MessageDigest.getInstance("MD5")
        return md.digest(host.toByteArray()).joinToString("") { "%02x".format(it) } + "-" + abs(host.hashCode())
    }

    fun diskFile(url: String): File = File(diskDir, keyFor(url) + ".png")

    /** Non-blocking memory lookup. */
    fun cached(url: String?): Bitmap? {
        if (url.isNullOrEmpty()) return null
        return mem.get(keyFor(url))
    }

    /** Store bitmap (from web favicon API or page DOM). */
    fun store(url: String, bmp: Bitmap?) {
        if (bmp == null) return
        try {
            val scaled = if (bmp.width > MEM || bmp.height > MEM) {
                Bitmap.createScaledBitmap(bmp, MEM, MEM, true)
            } else bmp
            mem.put(keyFor(url), scaled)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { scaled.compress(Bitmap.CompressFormat.PNG, 90, diskFile(url).outputStream()) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Best-effort load: memory -> disk -> letter avatar (colored, drawn synchronously). */
    fun load(context: Context, url: String?, text: String?): Bitmap? {
        if (url.isNullOrEmpty()) return null
        val key = keyFor(url)
        mem.get(key)?.let { return it }
        val f = diskFile(url)
        if (f.exists()) {
            try {
                val b = BitmapFactory.decodeFile(f.absolutePath)
                if (b != null) { mem.put(key, b); return b }
            } catch (_: Exception) {}
        }
        return letterAvatar(url, text)
    }

    /** Colored letter avatar (Chrome-style colorful circle with initial). */
    fun letterAvatar(url: String, text: String?): Bitmap {
        val host = Urls.domainOf(url)
        val label = (text?.trim()?.firstOrNull()?.uppercase() ?: host.firstOrNull()?.uppercase() ?: '?').toString()
        val hue = abs(host.hashCode()) % 360
        val bgColor = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.92f))
        val fgColor = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.28f))
        val bmp = Bitmap.createBitmap(MEM, MEM, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = bgColor
        c.drawCircle(MEM / 2f, MEM / 2f, MEM / 2f, paint)
        paint.color = fgColor
        paint.textSize = MEM * 0.55f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        val fm = paint.fontMetrics
        val y = MEM / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(label, MEM / 2f, y, paint)
        mem.put(keyFor(url), bmp)
        return bmp
    }

    /** Fetch favicon via Google S2 (colored originals). Background thread only. */
    fun fetchNetwork(url: String): Bitmap? {
        val host = Urls.domainOf(url)
        if (host.isEmpty()) return null
        val candidates = listOf(
            "https://www.google.com/s2/favicons?sz=64&domain=$host",
            "https://icons.duckduckgo.com/ip3/$host.ico"
        )
        for (cand in candidates) {
            try {
                val conn = URL(cand).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    conn.inputStream.use { ins ->
                        BitmapFactory.decodeStream(ins)?.let { return it }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
