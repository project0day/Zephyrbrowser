package com.zephyr.browser

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Build
import com.zephyr.browser.settings.BackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Application entry: early Prefs init so any component reads a warm snapshot. */
class App : Application() {

    companion object {
        /**
         * v1.7.86: the application context, for the handful of singletons that
         * need to read a string resource but are not handed a Context.
         *
         * [AiClient] is the reason: it is an `object` that formats the status
         * and error text the user reads in the chat, and until now that text was
         * Russian literals precisely because there was nothing to call
         * `getString` on. Set before anything else in [onCreate], so it is
         * non-null for the whole life of the process.
         */
        @Volatile
        @JvmStatic
        var ctx: Context? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        // v1.7.10: crash telemetry must be armed FIRST, before any other init
        // can fail — see the Files screen crash report («файлы не открываются!»).
        installCrashLogger()
        Prefs.init(this)
        // v1.7.0: warm up the vault crypto (keystore key + file-fallback key location)
        // so password/card encryption never races a background insert.
        VaultCrypto.init(this)
        registerSystemDownloadReceiver()
        // v1.7.6: keep the weekly auto-backup alarm armed (app start re-schedule —
        // the alarm is a self-perpetuating one-shot; this covers OS alarm wipes).
        try { BackupScheduler.rescheduleFromLastBackup(this) } catch (_: Exception) {}
    }

    // v1.7.10: crash telemetry — full stack trace to filesDir/last_crash.txt so the
    // user can share the exact cause of device-specific crashes (Files screen report).
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val file = File(filesDir, "last_crash.txt")
                file.writeText(buildString {
                    append("time: ").append(System.currentTimeMillis())
                    // v1.7.86: was the literal "1.7.17 (31)". Every crash report
                    // sent since that release named a version dozens of builds
                    // old, which makes a stack trace far harder to place.
                    append("\nversion: ").append(versionLabel())
                    append("\nthread: ").append(t.name)
                    append("\n\n")
                    append(android.util.Log.getStackTraceString(e))
                })
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    /** "1.7.86 (100)", asked of the package manager rather than hard-coded. */
    private fun versionLabel(): String = try {
        val p = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        "${p.versionName} ($code)"
    } catch (_: Throwable) { "unknown" }

    override fun attachBaseContext(base: Context) {
        // per-app language (Android 13+ picker; older: best-effort locale wrap)
        super.attachBaseContext(applyLanguage(base))
    }

    /**
     * v1.7.6: system-DownloadManager completions finalize the fallback rows in the
     * Downloads panel (path "dm:<id>" → real local URI + done/failed status), so
     * files the system downloader fetched still show up in OUR downloads list.
     */
    private fun registerSystemDownloadReceiver() {
        val filter = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(systemDownloadReceiver, filter)
        }
    }

    private val systemDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id <= 0) return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { finalizeSystemDownload(context, id) } catch (_: Exception) {}
            }
        }
    }

    /** v1.7.6: turns the "dm:<id>" journal row into a finished Downloads-panel entry.
     *  v1.7.7 (user: «фото скачено моим менеджером скачивания андроид»): the system
     *  download is now SILENT (VISIBILITY_HIDDEN) — this receiver is the one that
     *  tells the user, with a proper ZEPHYR notification. Image/video results are
     *  also copied into their real media collections (Pictures/Movies/Zephyr), so
     *  the fallback path delivers files exactly like the native engine does. */
    private suspend fun finalizeSystemDownload(context: Context, id: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val cursor: Cursor = dm.query(android.app.DownloadManager.Query().setFilterById(id)) ?: return
        try {
            if (!cursor.moveToFirst()) return
            val statusCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
            val uriCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI)
            val sizeCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val status = cursor.getInt(statusCol)
            val localUri = if (uriCol >= 0) cursor.getString(uriCol) else null
            val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
            var path = localUri ?: try {
                File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "dm/$id").absolutePath
            } catch (_: Exception) { "" }
            val ok = status == android.app.DownloadManager.STATUS_SUCCESSFUL
            val db = com.zephyr.browser.db.AppDb.get(context)
            // row is journaled with path "dm:<id>" by MainActivity.downloadViaSystem
            val row = db.downloadDao().allOnce().firstOrNull { it.path == "dm:$id" } ?: return
            var finalName = row.fileName
            // v1.7.7: images/videos → their proper public collection (Pictures/Movies/Zephyr)
            if (ok && path.isNotEmpty()) {
                try {
                    val mime = row.mime.ifEmpty { DownloadEngine.guessMime(finalName) }
                    if (mime.startsWith("image/") || mime.startsWith("video/")) {
                        val src = resolveFile(context, path)
                        val rel = if (mime.startsWith("image/")) android.os.Environment.DIRECTORY_PICTURES
                        else android.os.Environment.DIRECTORY_MOVIES
                        val cv = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$rel/Zephyr")
                            // v1.7.12: NO SIZE column — the DownloadManager cursor's
                            // reported size can differ from the real bytes (gzip/deflate
                            // transfers), and the declared-size mismatch made the
                            // openOutputStream write throw on many OEMs.
                        }
                        val outUri = context.contentResolver.insert(
                            android.provider.MediaStore.Files.getContentUri("external"), cv)
                        if (outUri != null && src != null) {
                            var copied = false
                            try {
                                val out = context.contentResolver.openOutputStream(outUri)
                                if (out != null) {
                                    out.use { o -> src.inputStream().use { it.copyTo(o) } }
                                    copied = true
                                }
                            } catch (_: Throwable) {
                            }
                            if (copied) {
                                path = outUri.toString()
                                try { src.delete() } catch (_: Exception) {}
                            } else {
                                // v1.7.12: stream unusable or the copy threw — delete
                                // the half-written orphan row (best-effort) and keep
                                // the dm file path as this download's result.
                                try { context.contentResolver.delete(outUri, null, null) } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            db.downloadDao().update(row.id, if (ok) 1 else 2, size, path, row.mime, finalName)
            // v1.7.7: the user-facing verdict — a ZEPHYR notification (the system
            // one is hidden), tappable to open the file.
            if (ok) DownloadEngine.notifySystemDone(context, row.id, finalName, path)
            else DownloadEngine.notifySystemFailed(context, row.id, finalName)
        } finally {
            try { cursor.close() } catch (_: Exception) {}
        }
    }

    private fun resolveFile(context: Context, path: String): File? {
        return try {
            when {
                path.startsWith("file://") -> File(android.net.Uri.parse(path).path ?: "")
                path.startsWith("/") -> File(path)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun applyLanguage(base: Context): Context {
        return try {
            val tag = Prefs.languageSnapshot(base)
            if (tag == "system") base
            else {
                val locale = android.os.LocaleList.forLanguageTags(tag)
                val config = android.content.res.Configuration(base.resources.configuration)
                config.setLocale(locale.get(0))
                config.setLocales(locale)
                base.createConfigurationContext(config)
            }
        } catch (_: Exception) {
            base
        }
    }
}
