package com.zephyr.browser.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Built-in media player: video via VideoView + audio mode with big glyph;
 * system codecs.
 *
 * v1.7.17 UPGRADE (user: «нормальный плеер внутри браузера»):
 *  - bottom control bar: play/pause + current time + SeekBar (programmatic
 *    layer-list: thin white progress on a translucent track) + total time,
 *    updated by a 500ms tick on the stage view while playing;
 *  - double-tap: left half → -10s, right half → +10s (OSD pl_rew_10 /
 *    pl_fwd_10, a single reusable centered translucent TextView that fades
 *    out after 600ms);
 *  - horizontal drag anywhere → SCRUB (target time OSD, seek on ACTION_UP);
 *    left-half vertical drag → BRIGHTNESS (0.05..1, OSD pl_brightness);
 *    right-half vertical drag → VOLUME (STREAM_MUSIC, OSD pl_volume);
 *  - gestures live on a full-size transparent layer UNDER the control bars,
 *    so buttons keep their own touches and a single tap (confirmed, i.e. no
 *    drag / double-tap happened) still toggles the bars;
 *  - top bar: speed (0.5×–2× via the mMediaPlayer reflection — the same one
 *    the audio-focus duck uses), aspect cycle (fit / crop / stretch applied
 *    as view scale factors once onPrepared reports the video size), rotate
 *    (USER_LANDSCAPE → USER_PORTRAIT → UNSPECIFIED cycle);
 *  - kept from the older rounds: PiP on home (26+), audio mode with big
 *    glyph, audio focus incl. the reflection duck, immersive re-assert,
 *    state save/restore, resolveTitle.
 *  House convention: every reflection / ropey API stays in try/catch — no
 *  feature may crash the screen. */
class PlayerActivity : AppCompatActivity() {

    private lateinit var stage: FrameLayout
    private lateinit var video: VideoView
    private lateinit var topBar: LinearLayout
    private lateinit var centerBtn: ImageButton
    private lateinit var titleView: TextView
    private lateinit var bottomBar: LinearLayout
    /** v1.7.20: dedicated full-width gradient behind the control bar — reaches
     *  the very bottom edge of the screen (the old bar-attached background
     *  stopped at the bar bounds and the 26dp lift left a raw video strip
     *  below it). */
    private lateinit var bottomScrimView: View
    private lateinit var playBtn: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var timeCur: TextView
    private lateinit var timeTotal: TextView
    private lateinit var osd: TextView
    private lateinit var gestureLayer: View
    private var audioMode = false
    private var resumePos = 0
    private var wasPlaying = false
    private var barsShown = true
    // v1.7.17 playback state
    private var vidW = 0
    private var vidH = 0
    private var aspectMode = 0          // 0 fit, 1 crop, 2 stretch
    private var speed = 1f
    private var seekDragging = false
    /** v1.7.18: the ⋮ overflow button (anchor for the popup menu). */
    private lateinit var menuBtn: ImageButton
    private val am get() = getSystemService(AUDIO_SERVICE) as AudioManager
    private var focusRequest: Any? = null

    // gesture state for the raw layer (scrub / brightness / volume)
    private var gMode = 0               // 0 none, 1 scrub, 2 brightness, 3 volume
    private var gStartX = 0f
    private var gStartY = 0f
    private var gBasePos = 0
    private var gScrubTarget = 0
    private var gBaseBright = 0.5f
    private var gBaseVol = 0
    private var gMaxVol = 1
    private var tapDet: GestureDetector? = null

    private val hideBar = Runnable { hideBars() }

    private val osdHide = Runnable {
        try {
            osd.animate().alpha(0f).setDuration(250)
                .withEndAction { osd.visibility = View.GONE }.start()
        } catch (_: Exception) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (video.isPlaying && !seekDragging) {
                    val d = video.duration
                    val p = video.currentPosition
                    // max == duration (ms) — progress is the position itself, no scaling
                    if (d > 0) seek.progress = p.coerceIn(0, d)
                    timeCur.text = fmtTime(p)
                }
            } catch (_: Exception) {}
            if (!isFinishing) stage.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val black = 0xFF000000.toInt()
        window.statusBarColor = black
        window.navigationBarColor = black
        // v1.7.12: true immersive playback — bars hidden (transient by swipe), not
        // just black; FLAG_FULLSCREEN as the OEM-proof wall for the status bar.
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

        // v1.7.18 FIX (user: «при повороте видео не по центру — попадает в самый
        // край со стороны панели строки состояния»): with the DEFAULT cutout mode
        // a landscape window is clipped at the notch side — the whole stage sits
        // off-center. shortEdges lets the window render under the cutout so the
        // video truly centers on the full screen.
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
        } catch (_: Exception) {}

        stage = FrameLayout(this).apply { setBackgroundColor(black) }
        video = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnPreparedListener { mp -> onPrepared(mp) }
            setOnCompletionListener { onCompletion() }
            setOnErrorListener { _, _, _ -> onError(); true }
        }
        stage.addView(video)

        // v1.7.17: transparent GESTURE LAYER above the video but UNDER the
        // control bars — drags/taps land here; bar buttons keep their touches.
        gestureLayer = View(this)
        gestureLayer.setOnTouchListener { v, ev -> onGesture(v, ev) }
        stage.addView(gestureLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // top bar (auto-hiding): back + title + speed/aspect/rotate over scrim
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = gradientScrim()
            elevation = dp(6).toFloat()
        }
        // v1.7.18: proper BACK arrow — the old ic_player_back was a skip-to-
        // previous glyph and read as «отмотать» (user: «кнопка странная назад
        // как прокрутка»)
        topBar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.pl_back)
            setOnClickListener { finish() }
        })
        titleView = TextView(this).apply {
            textSize = 14f
            maxLines = 1
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleView)
        // v1.7.18 (user: «вместо кнопки повернуть справа сверху сделай кнопку
        // три точки и там меню поделится, информация итд»): ONE overflow button
        // instead of the three top icons; the rotate control moved next to the
        // seek bar (below).
        menuBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_more_vert)
            background = null
            setPadding(dp(10), dp(10), dp(10), dp(10))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.pl_menu)
            setOnClickListener { showPlayerMenu() }
        }
        topBar.addView(menuBtn)
        stage.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // center play/pause/replay
        centerBtn = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
            background = circleBg(0x99000000.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnClickListener { togglePlay() }
        }
        stage.addView(centerBtn)

        // v1.7.17: bottom control bar — play/pause, times, seekbar
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            // v1.7.20: the gradient moved to bottomScrimView BEHIND the bar —
            // a bar-attached background could not cover the gap below the bar
            // (the bar is lifted 26dp) and its shadow visibly ended mid-video.
            elevation = 0f
        }
        playBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_player_pause)
            background = null
            setPadding(dp(10), dp(10), dp(10), dp(10))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.pl_pause)
            setOnClickListener { togglePlay() }
        }
        bottomBar.addView(playBtn)
        timeCur = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            minWidth = dp(38)
            gravity = Gravity.CENTER
            text = "0:00"
        }
        bottomBar.addView(timeCur, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            })
        seek = SeekBar(this).apply {
            max = 0
            progressDrawable = seekTrackDrawable()
            thumb = seekThumbDrawable()
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    // drag preview only — the real seek happens on release
                    if (fromUser) timeCur.text = fmtTime(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { seekDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seekDragging = false
                    try { video.seekTo(sb.progress) } catch (_: Exception) {}
                    scheduleHide()
                }
            })
        }
        bottomBar.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6); marginEnd = dp(6)
        })
        timeTotal = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            text = "0:00"
        }
        bottomBar.addView(timeTotal, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // v1.7.20 (user: «снизу эту тень видно и она не идёт в край видео»):
        // one tall gradient BEHIND the bar that runs from above the bar all the
        // way DOWN TO THE SCREEN EDGE — no more transparent gap between the
        // shadow and the bottom of the video.
        bottomScrimView = View(this).apply {
            background = bottomScrim()
        }
        stage.addView(bottomScrimView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(104), Gravity.BOTTOM))
        // v1.7.18 (user: «кнопку повернуть сделай не круглую стрелку, а квадратик
        // и помести справа от шкалы воспроизведения»): the screen-rotation
        // control now lives at the right end of the seek bar row.
        bottomBar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_screen_rotation)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.pl_rotate)
            setOnClickListener { cycleOrientation() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(2)
            })
        // v1.7.18 (user: «панель прокрутки слишком внизу — подними на пункт
        // выше»): the whole control bar is lifted off the very screen edge.
        stage.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { bottomMargin = dp(26) })

        // v1.7.17: reusable OSD (double-tap / scrub / brightness / volume)
        osd = TextView(this).apply {
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xB3000000.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
        }
        stage.addView(osd, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setContentView(stage)

        titleView.text = resolveTitle(intent)
        audioMode = guessAudio(intent)
        if (audioMode) decorateAudioMode()
        buildTapDetector()
        restoreState(savedInstanceState)
        play(intent?.data)
        showBars()
        stage.postDelayed(tick, 500)
    }

    // ---------------- playback ----------------

    private fun play(uri: Uri?) {
        if (uri == null) { finish(); return }
        video.setVideoURI(uri)
        if (resumePos > 0) video.seekTo(resumePos)
        // v1.7.17: restore-paused (wasPlaying=false + resumePos>0) must show the
        // PLAY glyph — the old code always painted pause on a paused screen.
        val starting = wasPlaying || resumePos == 0
        if (starting) video.start()
        val ic = if (starting) R.drawable.ic_player_pause else R.drawable.ic_player_play
        centerBtn.setImageResource(ic)
        playBtn.setImageResource(ic)
    }

    private fun onPrepared(mp: MediaPlayer) {
        audioMode = mp.videoWidth == 0
        if (audioMode) decorateAudioMode()
        mp.setScreenOnWhilePlaying(true)
        requestAudioFocus()
        vidW = mp.videoWidth
        vidH = mp.videoHeight
        // v1.7.17: seekbar range + aspect need the prepared size/duration
        try {
            val d = video.duration
            if (d > 0) {
                seek.max = d
                timeTotal.text = fmtTime(d)
            }
            val p = video.currentPosition
            timeCur.text = fmtTime(p)
            if (d > 0) seek.progress = p.coerceIn(0, d)
        } catch (_: Exception) {}
        stage.post { applyAspect() }
    }

    private fun togglePlay() {
        if (video.isPlaying) {
            video.pause()
            centerBtn.setImageResource(R.drawable.ic_player_play)
            playBtn.setImageResource(R.drawable.ic_player_play)
        } else {
            if (video.duration > 0 && video.currentPosition >= video.duration) {
                video.seekTo(0)
            }
            video.start()
            centerBtn.setImageResource(R.drawable.ic_player_pause)
            playBtn.setImageResource(R.drawable.ic_player_pause)
        }
        scheduleHide()
    }

    private fun onCompletion() {
        centerBtn.setImageResource(R.drawable.ic_player_replay)
        playBtn.setImageResource(R.drawable.ic_player_replay)
        showBars()
    }

    private fun onError() {
        Toast.makeText(this, R.string.pl_error, Toast.LENGTH_SHORT).show()
        android.os.Handler(mainLooper).postDelayed({ finish() }, 3000)
    }

    // ---------------- v1.7.17: speed / aspect / rotate ----------------

    private fun showSpeedDialog() {
        try {
            val labels = arrayOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×")
            val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            var sel = -1
            for (k in values.indices) if (abs(values[k] - speed) < 0.01f) { sel = k; break }
            if (sel < 0) sel = 2
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pl_speed)
                .setSingleChoiceItems(labels, sel) { dlg, which ->
                    dlg.dismiss()
                    applySpeed(values[which])
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (_: Exception) {}
    }

    private fun applySpeed(f: Float) {
        // the SAME mMediaPlayer reflection the audio-focus duck uses — VideoView
        // hides its player, PlaybackParams is the only lever it exposes.
        try {
            val fld = VideoView::class.java.getDeclaredField("mMediaPlayer")
            fld.isAccessible = true
            val mp = fld.get(video) as? MediaPlayer ?: throw IllegalStateException("player not ready")
            mp.playbackParams = android.media.PlaybackParams().setSpeed(f)
            speed = f
            showOsd(getString(R.string.pl_speed) + "  " + speedLabel(f))
        } catch (_: Throwable) {
            try { Toast.makeText(this, R.string.pl_error, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    private fun speedLabel(f: Float): String = when (f) {
        0.5f -> "0.5×"; 0.75f -> "0.75×"; 1.25f -> "1.25×"; 1.5f -> "1.5×"; 2f -> "2×"
        else -> "1×"
    }

    private fun cycleAspect() {
        aspectMode = (aspectMode + 1) % 3
        applyAspect()
        showOsd(when (aspectMode) {
            1 -> getString(R.string.pl_aspect_fill)
            2 -> getString(R.string.pl_aspect_stretch)
            else -> getString(R.string.pl_aspect_fit)
        })
    }

    /** VideoView letterboxes the content inside its bounds — v1.7.18 FIX (user:
     *  «при повороте видео не по центру, попадает в самый край со стороны
     *  панели строки состояния»): the old view-SCALE hacks are replaced by an
     *  EXACT frame (size + Gravity.CENTER): the VideoView gets precisely the
     *  computed display rect, so it centers in BOTH orientations regardless of
     *  cutout/inset quirks. fit = largest centered rect, crop = uniform max
     *  (overflows the stage), stretch = independent x/y. */
    private fun applyAspect() {
        try {
            video.scaleX = 1f
            video.scaleY = 1f
            if (audioMode || vidW <= 0 || vidH <= 0) {
                resetVideoFrame()
                return
            }
            val sw = stage.width.toFloat()
            val sh = stage.height.toFloat()
            if (sw <= 0f || sh <= 0f) { stage.post { applyAspect() }; return }
            val vw: Int
            val vh: Int
            when (aspectMode) {
                1 -> {
                    val s = maxOf(sw / vidW, sh / vidH)
                    vw = (vidW * s).roundToInt().coerceAtLeast(1)
                    vh = (vidH * s).roundToInt().coerceAtLeast(1)
                }
                2 -> {
                    vw = sw.roundToInt().coerceAtLeast(1)
                    vh = sh.roundToInt().coerceAtLeast(1)
                }
                else -> {
                    val s = minOf(sw / vidW, sh / vidH)
                    vw = (vidW * s).roundToInt().coerceAtLeast(1)
                    vh = (vidH * s).roundToInt().coerceAtLeast(1)
                }
            }
            val lp = (video.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(vw, vh)
            lp.width = vw
            lp.height = vh
            lp.gravity = Gravity.CENTER
            video.layoutParams = lp
        } catch (_: Throwable) {}
    }

    private fun resetVideoFrame() {
        try {
            val lp = (video.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.CENTER
            video.layoutParams = lp
        } catch (_: Throwable) {}
    }

    private fun cycleOrientation() {
        // USER_LANDSCAPE → USER_PORTRAIT → UNSPECIFIED cycle (declared with
        // configChanges → no recreate, just a relayout)
        try {
            requestedOrientation = when (requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                else -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            }
        } catch (_: Exception) {}
    }

    // ---------------- v1.7.18: ⋮ menu / share / info ----------------

    /** The ⋮ overflow menu (user: «сделай кнопку три точки и там сделаем меню
     *  поделится информация итд ИТП»): Поделиться, Информация, Скорость,
     *  Формат кадра. */
    private fun showPlayerMenu() {
        try {
            if (!::menuBtn.isInitialized) return
            val pm = android.widget.PopupMenu(this, menuBtn)
            pm.menu.add(0, 1, 0, getString(R.string.share))
            pm.menu.add(0, 2, 0, getString(R.string.pv_info))
            pm.menu.add(0, 3, 0, getString(R.string.pl_speed))
            pm.menu.add(0, 4, 0, getString(R.string.pl_aspect))
            pm.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> shareMedia()
                    2 -> showMediaInfo()
                    3 -> showSpeedDialog()
                    4 -> cycleAspect()
                }
                true
            }
            pm.show()
        } catch (_: Exception) {}
    }

    /** System share sheet for the playing media (content URI + mime). */
    private fun shareMedia() {
        try {
            val uri = intent?.data ?: return
            val send = Intent(Intent.ACTION_SEND).apply {
                type = intent?.type ?: "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, titleView.text))
        } catch (_: Exception) {
            try { Toast.makeText(this, R.string.pl_error, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /** Media info dialog: duration, resolution, size, source. */
    private fun showMediaInfo() {
        try {
            val uri = intent?.data
            val sb = StringBuilder()
            if (audioMode || vidW <= 0 || vidH <= 0) {
                sb.append(getString(R.string.pl_audio_track)).append('\n')
            } else {
                sb.append(getString(R.string.pv_dimens, vidW, vidH)).append('\n')
            }
            try {
                val d = video.duration
                if (d > 0) sb.append(getString(R.string.pl_duration, fmtTime(d))).append('\n')
            } catch (_: Exception) {}
            if (uri != null) {
                try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                        sb.append(getString(R.string.pv_filesize,
                            android.text.format.Formatter.formatFileSize(this, fd.length))).append('\n')
                    }
                } catch (_: Exception) {}
                sb.append(getString(R.string.pv_path, uri.toString()))
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(titleView.text)
                .setMessage(sb.toString().trimEnd())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // stage already resized — refit the video into the new bounds
        stage.post { applyAspect() }
    }

    // ---------------- v1.7.17: gestures + OSD ----------------

    private fun buildTapDetector() {
        tapDet = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // SINGLE tap (confirmed = no drag, no double-tap) toggles the bars
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (barsShown) hideBars() else showBars()
                return true
            }

            // double-tap: left half → -10s, right half → +10s
            override fun onDoubleTap(e: MotionEvent): Boolean {
                try {
                    val cur = video.currentPosition
                    val dur = video.duration
                    if (e.x < gestureLayer.width / 2f) {
                        video.seekTo((cur - 10000).coerceAtLeast(0))
                        showOsd(getString(R.string.pl_rew_10))
                    } else {
                        val t = if (dur > 0) (cur + 10000).coerceAtMost(dur) else cur + 10000
                        video.seekTo(t)
                        showOsd(getString(R.string.pl_fwd_10))
                    }
                } catch (_: Exception) {}
                return true
            }
        })
    }

    /** Raw gesture layer: feeds the tap/double-tap detector AND tracks
     *  drags — horizontal = scrub, vertical left = brightness, vertical
     *  right = volume. Buttons sit ABOVE this layer and keep their touches. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun onGesture(v: View, ev: MotionEvent): Boolean {
        try { tapDet?.onTouchEvent(ev) } catch (_: Exception) {}
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gMode = 0
                gStartX = ev.x
                gStartY = ev.y
                gBasePos = try { video.currentPosition } catch (_: Exception) { 0 }
                try {
                    val b = window.attributes.screenBrightness
                    gBaseBright = if (b in 0.01f..1f) b else 0.5f
                } catch (_: Exception) { gBaseBright = 0.5f }
                try { gMaxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) } catch (_: Exception) { gMaxVol = 1 }
                try { gBaseVol = am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { gBaseVol = 0 }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - gStartX
                val dy = ev.y - gStartY
                val thr = dp(16)
                if (gMode == 0 && (abs(dx) > thr || abs(dy) > thr)) {
                    gMode = when {
                        abs(dx) > abs(dy) * 1.2f -> 1     // horizontal → scrub
                        ev.x < v.width / 2f -> 2          // left half → brightness
                        else -> 3                         // right half → volume
                    }
                }
                when (gMode) {
                    1 -> scrubTo(dx, v.width)
                    2 -> brightnessTo(dy, v.height)
                    3 -> volumeTo(dy, v.height)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gMode == 1) scrubCommit()
                gMode = 0
            }
        }
        return true
    }

    private fun scrubTo(dx: Float, w: Int) {
        try {
            if (w <= 0) return
            val dur = video.duration
            if (dur <= 0) return
            // full screen width ≈ max(60s, half the video) — feels right on
            // both a 30s clip and a 2h movie
            val secPerPx = maxOf(60f, dur / 2000f) / w
            gScrubTarget = (gBasePos + dx * secPerPx * 1000f).toInt().coerceIn(0, dur)
            showOsd(fmtTime(gScrubTarget))
        } catch (_: Exception) {}
    }

    private fun scrubCommit() {
        try {
            video.seekTo(gScrubTarget)
            timeCur.text = fmtTime(gScrubTarget)
            val d = video.duration
            if (d > 0) seek.progress = gScrubTarget.coerceIn(0, d)
        } catch (_: Exception) {}
    }

    private fun brightnessTo(dy: Float, h: Int) {
        try {
            if (h <= 0) return
            val b = (gBaseBright - dy / h.toFloat() * 1.2f).coerceIn(0.05f, 1f)
            val lp = window.attributes
            lp.screenBrightness = b
            window.attributes = lp
            showOsd(getString(R.string.pl_brightness) + "  " + (b * 100).roundToInt() + "%")
        } catch (_: Exception) {}
    }

    private fun volumeTo(dy: Float, h: Int) {
        try {
            if (h <= 0 || gMaxVol <= 0) return
            val vol = (gBaseVol - dy / h.toFloat() * gMaxVol * 1.5f).roundToInt().coerceIn(0, gMaxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
            showOsd(getString(R.string.pl_volume) + "  " + (vol * 100 / gMaxVol) + "%")
        } catch (_: Exception) {}
    }

    /** Single reusable OSD: text now, fade-out 600ms later. */
    private fun showOsd(text: String) {
        try {
            osd.removeCallbacks(osdHide)
            osd.text = text
            osd.alpha = 1f
            osd.visibility = View.VISIBLE
            osd.postDelayed(osdHide, 600)
        } catch (_: Exception) {}
    }

    private fun fmtTime(ms: Int): String {
        val t = ms.toLong().coerceAtLeast(0L) / 1000L
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    // ---------------- bars ----------------

    private fun showBars() {
        barsShown = true
        topBar.visibility = View.VISIBLE
        centerBtn.visibility = View.VISIBLE
        bottomScrimView.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        scheduleHide()
    }

    private fun hideBars() {
        if (seekDragging) { scheduleHide(); return }   // never hide mid-drag
        barsShown = false
        topBar.visibility = View.GONE
        centerBtn.visibility = View.GONE
        bottomScrimView.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private fun scheduleHide() {
        stage.removeCallbacks(hideBar)
        stage.postDelayed(hideBar, 4000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // v1.7.12: dialogs/PiP churn can resurrect the bars — re-assert immersive.
        if (hasFocus && !isFinishing) {
            try {
                val c = WindowInsetsControllerCompat(window, window.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } catch (_: Exception) {}
            try { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) } catch (_: Exception) {}
        }
    }

    // ---------------- audio mode decoration ----------------

    private fun decorateAudioMode() {
        if (stage.findViewWithTag<View>("audioDeco") != null) return
        val deco = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            tag = "audioDeco"
            // non-interactive: touches fall through to the gesture layer below
            isClickable = false
            isFocusable = false
        }
        deco.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_player_music)
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            alpha = 0.9f
            background = circleBg(0x33FFFFFF)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        })
        deco.addView(TextView(this).apply {
            text = titleView.text
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(20), 0, 0)
        })
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        lp.bottomMargin = dp(120)
        stage.addView(deco, lp)
    }

    // ---------------- audio focus ----------------

    private fun requestAudioFocus() {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { video.pause(); centerBtn.setImageResource(R.drawable.ic_player_play) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { wasPlaying = video.isPlaying; video.pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    try {
                        val fld = VideoView::class.java.getDeclaredField("mMediaPlayer")
                        fld.isAccessible = true
                        (fld.get(video) as? MediaPlayer)?.setVolume(0.2f, 0.2f)
                    } catch (_: Exception) {}
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    try {
                        val fld = VideoView::class.java.getDeclaredField("mMediaPlayer")
                        fld.isAccessible = true
                        (fld.get(video) as? MediaPlayer)?.setVolume(1f, 1f)
                    } catch (_: Exception) {}
                    if (wasPlaying || audioMode) video.start()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
                .setOnAudioFocusChangeListener(listener)
                .build()
            am.requestAudioFocus(focusRequest as AudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest as AudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    // ---------------- lifecycle & state ----------------

    override fun onStart() {
        super.onStart()
        requestAudioFocus()
    }

    override fun onStop() {
        video.pause()
        abandonFocus()
        super.onStop()
    }

    override fun onDestroy() {
        // stage may be uninitialized if onCreate died mid-way — guarded
        try { if (::stage.isInitialized) { stage.removeCallbacks(tick); stage.removeCallbacks(hideBar) } } catch (_: Exception) {}
        abandonFocus()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (isInPictureInPictureMode) return
        video.pause()
    }

    private fun restoreState(s: Bundle?) {
        if (s == null) return
        resumePos = s.getInt("pos", 0)
        wasPlaying = s.getBoolean("playing", false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            outState.putInt("pos", video.currentPosition)
            outState.putBoolean("playing", video.isPlaying)
        } catch (_: Exception) {}
    }

    // ---------------- PiP ----------------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && video.isPlaying) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (_: Exception) {}
        }
    }

    // ---------------- helpers ----------------

    private fun guessAudio(intent: Intent?): Boolean {
        val mime = intent?.type ?: return false
        if (mime.startsWith("audio/")) return true
        val name = titleView.text.toString().lowercase()
        val ext = name.substringAfterLast('.', "")
        return ext in setOf("mp3","m4a","aac","ogg","oga","opus","flac","wav","wma","amr")
    }

    private fun resolveTitle(intent: Intent?): String {
        intent?.getStringExtra(Intent.EXTRA_TITLE)?.let { return it }
        val uri = intent?.data ?: return getString(R.string.pl_title_default)
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
                }
            } catch (_: Exception) {}
        }
        return try {
            uri.lastPathSegment ?: getString(R.string.pl_title_default)
        } catch (_: Exception) { getString(R.string.pl_title_default) }
    }

    /** v1.7.17: thin white progress over a translucent track — a 3dp
     *  layer-list built programmatically (the ids must match what ProgressBar
     *  looks up: android.R.id.background / progress). */
    private fun seekTrackDrawable(): android.graphics.drawable.LayerDrawable {
        val r = dp(2).toFloat()
        val track = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x59FFFFFF.toInt()); cornerRadius = r; setSize(-1, dp(3))
        }
        val fill = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt()); cornerRadius = r; setSize(-1, dp(3))
        }
        val clip = android.graphics.drawable.ClipDrawable(
            fill, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        val ld = android.graphics.drawable.LayerDrawable(arrayOf(track, clip))
        ld.setId(0, android.R.id.background)
        ld.setId(1, android.R.id.progress)
        ld.setLayerGravity(0, Gravity.CENTER_VERTICAL)
        ld.setLayerGravity(1, Gravity.CENTER_VERTICAL)
        return ld
    }

    private fun seekThumbDrawable(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
            setSize(dp(12), dp(12))
        }

    private fun circleBg(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }

    private fun gradientScrim(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xE6000000.toInt(), 0x00000000))

    private fun bottomScrim(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0xE6000000.toInt()))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
