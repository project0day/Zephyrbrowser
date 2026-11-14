package com.zephyr.browser.ai

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import com.zephyr.browser.ui.GenerativeWaveView
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * v1.7.79 — offline speech recognition (user: «у кого есть гугл сервисы пусть
 * используется гугловская, а у кого нету, то в настройках возможность перейти
 * включить и закачать твой вариант»).
 *
 * Order of preference, decided in [com.zephyr.browser.MainActivity.startAiVoiceInput]:
 *   1. the system recogniser (Google's), when the phone has one — it is the
 *      best one and costs nothing to use;
 *   2. this, when the user has downloaded a model — runs fully on the device,
 *      no network, no account;
 *   3. [AiVoice], which uploads the clip to the configured provider.
 *
 * Nothing here is bundled. The engine (~12 MB of native code) ships in the APK
 * because a .so cannot sensibly be side-loaded, but every acoustic model is
 * fetched on demand from alphacephei.com and can be deleted again from
 * Settings, so a user who never opens this pays nothing for it.
 */
object AiVosk {

    /** A downloadable model. Sizes are the published zip sizes, for the UI. */
    data class Lang(
        val code: String,
        val title: String,
        val zip: String,
        val mb: Int
    )

    /**
     * The "small" models only. Vosk also publishes full ones, but those are
     * 1.5–2.5 GB — absurd on a phone, and the small ones are accurate enough
     * for dictating a question into a chat box.
     */
    val LANGS = listOf(
        Lang("ru", "Русский", "vosk-model-small-ru-0.22.zip", 44),
        Lang("fr", "Français", "vosk-model-small-fr-0.22.zip", 40),
        Lang("en", "English", "vosk-model-small-en-us-0.15.zip", 39),
        Lang("de", "Deutsch", "vosk-model-small-de-0.15.zip", 44),
        Lang("es", "Español", "vosk-model-small-es-0.42.zip", 37),
        Lang("it", "Italiano", "vosk-model-small-it-0.22.zip", 47),
        Lang("uk", "Українська", "vosk-model-small-uk-v3-nano.zip", 74),
        Lang("tr", "Türkçe", "vosk-model-small-tr-0.3.zip", 35)
    )

    private const val BASE = "https://alphacephei.com/vosk/models/"

    fun lang(code: String): Lang? = LANGS.firstOrNull { it.code == code }

    // ---------------- model storage ----------------

    private fun root(ctx: Context) = File(ctx.filesDir, "vosk")

    fun dir(ctx: Context, code: String) = File(root(ctx), code)

    /**
     * Installed means usable, not merely present: a model without its acoustic
     * data is a half-finished download, and opening one crashes inside JNI
     * rather than throwing something catchable. Checking for `am` is cheap
     * insurance against an interrupted transfer.
     */
    fun installed(ctx: Context, code: String): Boolean {
        val d = dir(ctx, code)
        return d.isDirectory && File(d, "am").exists() && File(d, "conf").exists()
    }

    fun installedLangs(ctx: Context): List<Lang> = LANGS.filter { installed(ctx, it.code) }

    fun sizeOnDisk(ctx: Context, code: String): Long = bytesOf(dir(ctx, code))

    private fun bytesOf(f: File): Long =
        if (!f.exists()) 0L
        else if (f.isFile) f.length()
        else f.listFiles()?.sumOf { bytesOf(it) } ?: 0L

    fun delete(ctx: Context, code: String) {
        dir(ctx, code).deleteRecursively()
        if (com.zephyr.browser.Prefs.voiceLang == code) {
            val next = installedLangs(ctx).firstOrNull()?.code ?: ""
            com.zephyr.browser.Prefs.setVoiceLang(next)
        }
    }

    /** Any model at all downloaded? Decides whether the offline route exists. */
    fun ready(ctx: Context): Boolean = installedLangs(ctx).isNotEmpty()

    /** Which model to actually use: the chosen one, else the only one there is. */
    fun activeLang(ctx: Context): String {
        val chosen = com.zephyr.browser.Prefs.voiceLang
        if (chosen.isNotBlank() && installed(ctx, chosen)) return chosen
        return installedLangs(ctx).firstOrNull()?.code ?: ""
    }

    // ---------------- download ----------------

    /**
     * Fetch and unpack one model, showing real progress — these are 40+ MB and
     * a silent spinner for two minutes reads as a hang.
     *
     * The download lands in a `.part` directory that is only renamed into place
     * once the unpack finished, so a cancelled or failed transfer can never
     * leave behind something [installed] would accept.
     */
    fun download(activity: Activity, code: String, onDone: (Boolean) -> Unit) {
        // v1.7.79 FIX: the dialog and its views MUST be built on the Activity.
        // An application Context carries no AppCompat theme, and Material's
        // builder refuses one outright — «requires your app theme to be
        // Theme.AppCompat». Only the background work uses the app context, so
        // a finished Activity is not held alive by a 45 MB transfer.
        val ctx: Context = activity
        val app = activity.applicationContext
        val l = lang(code) ?: run { onDone(false); return }
        val d = { v: Int -> AiUi.dp(ctx, v) }

        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
        }
        val status = TextView(ctx).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, d(12), 0, 0)
            setTextColor(AiUi.onSurface(ctx))
            text = ctx.getString(R.string.voice_dl_starting)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(24), d(20), d(24), d(8))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(6)))
            addView(status)
        }

        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.voice_dl_title_fmt, l.title))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled.set(true) }
            .create()
        dialog.show()

        thread {
            val target = dir(app, code)
            val part = File(root(app), "$code.part")
            var ok = false
            var err: String? = null
            try {
                part.deleteRecursively()
                part.mkdirs()
                val conn = (URL(BASE + l.zip).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (l.mb * 1024L * 1024L)
                var read = 0L
                var lastPct = -1

                ZipInputStream(conn.inputStream.buffered(64 * 1024)).use { zin ->
                    val buf = ByteArray(64 * 1024)
                    var entry = zin.nextEntry
                    while (entry != null) {
                        if (cancelled.get()) throw InterruptedException()
                        // The published zips wrap everything in one top-level
                        // folder; that level is dropped so the model sits
                        // directly in our directory and the path we hand to
                        // Vosk never depends on the zip's own naming.
                        val rel = entry.name.substringAfter('/', "")
                        if (rel.isNotBlank() && !entry.isDirectory) {
                            val out = File(part, rel)
                            // zip-slip: a crafted archive must not write outside
                            if (!out.canonicalPath.startsWith(part.canonicalPath + File.separator)) {
                                throw IllegalStateException("bad entry: " + entry.name)
                            }
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { o ->
                                while (true) {
                                    val n = zin.read(buf)
                                    if (n <= 0) break
                                    o.write(buf, 0, n)
                                    read += n
                                    // the counter tracks UNPACKED bytes against
                                    // the compressed total, so it is scaled by
                                    // the ~1.0 ratio these zips actually have
                                    val pct = ((read * 1000) / total).toInt().coerceIn(0, 1000)
                                    if (pct / 10 != lastPct / 10) {
                                        lastPct = pct
                                        activity.runOnUiThread {
                                            bar.progress = pct
                                            status.text = ctx.getString(
                                                R.string.voice_dl_progress_fmt,
                                                pct / 10, (read / (1024 * 1024)).toInt(), l.mb
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
                if (cancelled.get()) throw InterruptedException()
                if (!File(part, "am").exists()) throw IllegalStateException("incomplete model")
                target.deleteRecursively()
                ok = part.renameTo(target)
                if (!ok) err = "rename failed"
            } catch (_: InterruptedException) {
                err = null
            } catch (t: Throwable) {
                err = t.localizedMessage ?: t.javaClass.simpleName
            } finally {
                if (!ok) part.deleteRecursively()
            }

            activity.runOnUiThread {
                try { dialog.dismiss() } catch (_: Throwable) {}
                if (ok) {
                    if (com.zephyr.browser.Prefs.voiceLang.isBlank()) {
                        com.zephyr.browser.Prefs.setVoiceLang(code)
                    }
                    toast(activity, app.getString(R.string.voice_dl_ok_fmt, l.title))
                } else if (err != null) {
                    toast(activity, app.getString(R.string.voice_dl_fail_fmt, err))
                }
                onDone(ok)
            }
        }
    }

    // ---------------- recognition ----------------

    /**
     * Listen through the downloaded model and hand back the transcript.
     *
     * Unlike the cloud route this is live: partial hypotheses appear while the
     * user is still talking, which is the whole reason an offline recogniser
     * feels better than uploading a clip.
     */
    fun listen(activity: Activity, onText: (String) -> Unit) {
        val ctx = activity
        val code = activeLang(ctx)
        if (code.isBlank()) { toast(activity, ctx.getString(R.string.voice_no_model)); return }

        val d = { v: Int -> AiUi.dp(ctx, v) }
        val heard = TextView(ctx).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            minLines = 2
            setPadding(0, d(14), 0, 0)
            setTextColor(AiUi.onSurface(ctx))
            setText(R.string.voice_listening)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(d(24), d(20), d(24), d(8))
            addView(GenerativeWaveView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(d(140), d(40))
            })
            addView(heard)
        }

        // everything the recogniser has finalised so far; partials are shown
        // but never appended, because the next final result replaces them
        val done = StringBuilder()
        var partial = ""
        var service: SpeechService? = null
        var finished = false

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(box)
            .setPositiveButton(R.string.voice_stop, null)
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .create()

        // stop() must run once: both buttons and a shutdown land here, and
        // releasing a SpeechService twice takes the process with it
        val stop = fun(send: Boolean) {
            if (finished) return
            finished = true
            try { service?.stop() } catch (_: Throwable) {}
            try { service?.shutdown() } catch (_: Throwable) {}
            service = null
            try { dialog.dismiss() } catch (_: Throwable) {}
            if (!send) return
            val text = (done.toString() + " " + partial).trim().replace(Regex("\\s+"), " ")
            if (text.isBlank()) toast(activity, ctx.getString(R.string.voice_empty))
            else onText(text)
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener { stop(true) }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener { stop(false) }
        }
        dialog.show()

        val listener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                partial = field(hypothesis, "partial")
                show()
            }

            override fun onResult(hypothesis: String?) {
                val t = field(hypothesis, "text")
                if (t.isNotBlank()) { done.append(' ').append(t) }
                partial = ""
                show()
            }

            override fun onFinalResult(hypothesis: String?) {
                val t = field(hypothesis, "text")
                if (t.isNotBlank()) { done.append(' ').append(t) }
                partial = ""
            }

            override fun onError(e: Exception?) {
                activity.runOnUiThread {
                    toast(activity, ctx.getString(
                        R.string.voice_fail_fmt, e?.localizedMessage ?: "mic"))
                    stop(false)
                }
            }

            override fun onTimeout() {
                activity.runOnUiThread { stop(true) }
            }

            private fun show() {
                val text = (done.toString() + " " + partial).trim()
                activity.runOnUiThread {
                    heard.text = text.ifBlank { ctx.getString(R.string.voice_listening) }
                }
            }
        }

        // loading a model touches ~45 MB of files — off the UI thread always
        thread {
            val svc = try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val model = Model(dir(ctx, code).absolutePath)
                SpeechService(Recognizer(model, 16000.0f), 16000.0f)
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    toast(activity, ctx.getString(
                        R.string.voice_fail_fmt, t.localizedMessage ?: t.javaClass.simpleName))
                    stop(false)
                }
                return@thread
            }
            activity.runOnUiThread {
                if (finished) {
                    // the user cancelled while the model was still loading
                    try { svc.shutdown() } catch (_: Throwable) {}
                    return@runOnUiThread
                }
                service = svc
                try { svc.startListening(listener) } catch (t: Throwable) {
                    toast(activity, ctx.getString(
                        R.string.voice_fail_fmt, t.localizedMessage ?: "mic"))
                    stop(false)
                }
            }
        }
    }

    private fun field(json: String?, name: String): String = try {
        JSONObject(json ?: "{}").optString(name).trim()
    } catch (_: Throwable) { "" }

    private fun toast(activity: Activity, text: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
