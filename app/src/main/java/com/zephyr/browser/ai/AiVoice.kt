package com.zephyr.browser.ai

import android.app.Activity
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.ui.GenerativeWaveView
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * v1.7.55 (user, on microG: «Голосовой поиск недоступен, так как у меня телефон
 * на microG и нет гугл-хлама. Писать свой встроенный голосовой долго/трудно?»).
 *
 * Android's voice input is not part of Android — it is a RecognitionService,
 * and on a de-Googled phone nothing provides one, so ACTION_RECOGNIZE_SPEECH
 * has nobody to answer it. Writing a recogniser means shipping an acoustic
 * model: Vosk is ~40 MB per language, Whisper far more. That is a lot of APK
 * for a button.
 *
 * But the user already has speech-to-text: every OpenAI-compatible provider
 * exposes `/v1/audio/transcriptions`, and the keys are already in the app. So
 * the microphone is recorded locally and the clip is transcribed by whichever
 * provider the user points this at — nothing bundled, nothing Google.
 *
 * The clip is deleted as soon as the answer comes back, successful or not.
 */
object AiVoice {

    /** Is there a system recogniser at all? False on a phone without Google's. */
    fun systemAvailable(ctx: Context): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(ctx)
    } catch (_: Throwable) { false }

    /** Configured to transcribe through a provider? */
    fun cloudReady(): Boolean = endpoint().isNotBlank() && key().isNotBlank()

    /**
     * Where to send audio. An explicit setting wins; otherwise the active
     * profile's host is reused, which makes Groq and OpenAI work with no setup
     * at all — both serve transcriptions from the same base as chat.
     */
    fun endpoint(): String {
        Prefs.sttEndpoint.trim().takeIf { it.isNotBlank() }?.let { return it }
        val p = try { AiModels.active() } catch (_: Throwable) { return "" }
        val base = p.endpoint.trim().removeSuffix("/")
        val idx = base.indexOf("/chat/completions")
        val root = if (idx > 0) base.substring(0, idx) else base
        if (!root.startsWith("http")) return ""
        // only offer the automatic route for hosts known to serve Whisper
        val low = root.lowercase()
        if (!low.contains("groq.com") && !low.contains("openai.com")) return ""
        return "$root/audio/transcriptions"
    }

    fun key(): String =
        Prefs.sttKey.trim().ifBlank {
            try { AiModels.active().key.trim() } catch (_: Throwable) { "" }
        }

    fun model(): String = Prefs.sttModel.trim().ifBlank { "whisper-large-v3-turbo" }

    // ---------------- recording ----------------

    /**
     * Record until the user taps stop, transcribe, then hand the text back.
     *
     * Everything blocking happens off the UI thread; the dialog is only a stop
     * button with the wave animation, so it is obvious the microphone is live.
     */
    fun listen(activity: Activity, onText: (String) -> Unit) {
        val ctx = activity
        val file = File(ctx.cacheDir, "voice_" + System.currentTimeMillis() + ".m4a")
        val recorder = try { newRecorder(ctx) } catch (_: Throwable) { null }
        if (recorder == null) {
            toast(activity, ctx.getString(R.string.voice_rec_fail)); return
        }

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(16000)
            recorder.setAudioEncodingBitRate(64000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (_: Throwable) {
            try { recorder.release() } catch (_: Throwable) {}
            file.delete()
            toast(activity, ctx.getString(R.string.voice_rec_fail))
            return
        }

        val d = { v: Int -> AiUi.dp(ctx, v) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(d(24), d(20), d(24), d(8))
            addView(GenerativeWaveView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(d(140), d(40))
            })
            addView(TextView(ctx).apply {
                setText(R.string.voice_listening)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, d(14), 0, 0)
                setTextColor(AiUi.onSurface(ctx))
            })
        }

        var stopped = false
        // finish() must run exactly once: the stop button and a dismiss both
        // land here, and releasing a recorder twice throws
        val finish = fun(send: Boolean) {
            if (stopped) return
            stopped = true
            val ok = try { recorder.stop(); true } catch (_: Throwable) { false }
            try { recorder.release() } catch (_: Throwable) {}
            if (!send || !ok || !file.exists() || file.length() < 2000) {
                file.delete()
                if (send) toast(activity, ctx.getString(R.string.voice_too_short))
                return
            }
            transcribeAsync(activity, file, onText)
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(box)
            .setPositiveButton(R.string.voice_stop) { _, _ -> finish(true) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }
            .create()
        dialog.show()
    }

    private fun newRecorder(ctx: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
        else @Suppress("DEPRECATION") MediaRecorder()

    // ---------------- transcription ----------------

    private fun transcribeAsync(activity: Activity, file: File, onText: (String) -> Unit) {
        val ctx = activity.applicationContext
        toast(activity, ctx.getString(R.string.voice_sending))
        thread {
            val result = try { transcribe(file) } catch (t: Throwable) {
                Result.failure<String>(t)
            }
            file.delete()
            activity.runOnUiThread {
                result.onSuccess { text ->
                    if (text.isBlank()) toast(activity, ctx.getString(R.string.voice_empty))
                    else onText(text)
                }.onFailure { t ->
                    toast(activity, ctx.getString(R.string.voice_fail_fmt,
                        t.localizedMessage ?: t.javaClass.simpleName))
                }
            }
        }
    }

    /** One multipart POST. Blocking — never call from the UI thread. */
    private fun transcribe(file: File): Result<String> {
        val url = endpoint()
        if (url.isBlank()) return Result.failure(IllegalStateException("Не задан адрес распознавания речи"))
        val boundary = "----zephyr" + System.currentTimeMillis()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 120000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            key().takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            DataOutputStream(conn.outputStream.buffered()).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("model", model())
                field("response_format", "json")
                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.name + "\"\r\n"
                )
                out.writeBytes("Content-Type: audio/m4a\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("HTTP $code: " + body.take(200)))
            }
            val text = try {
                org.json.JSONObject(body).optString("text")
            } catch (_: Throwable) { "" }
            return Result.success(text.trim())
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun toast(activity: Activity, text: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
