package com.zephyr.browser.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import java.io.File

/**
 * v1.7.77 rewrite (user: «в камере не нужны твои пункты, только фото… снял —
 * галочка отправить или крестик, чтобы снять новый кадр… в Grok есть поворот
 * камеры, фронтальная, вспышка авто/вкл/выкл»).
 *
 * The first version asked what the photo was FOR before it was taken, which put
 * a decision in front of the shot and got the order backwards — a camera should
 * take the picture, and the question comes after, in the words of whoever is
 * holding the phone.
 *
 * So this is only a camera: flip, flash, shutter. The shot is then shown for
 * approval — ✓ keeps it, ✕ throws it away and returns to the viewfinder,
 * because the second frame is usually the good one. Approved photos land in the
 * chat composer as attachments, unsent, and the user writes the question.
 *
 * Images are capped at 1280px on the long edge by AiClient before they are
 * encoded, so a full-resolution sensor shot does not become a wall of tokens.
 */
class CameraAiActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var shotView: ImageView
    private lateinit var liveControls: LinearLayout
    private lateinit var reviewControls: LinearLayout
    private lateinit var flashBtn: ImageView

    private var capture: ImageCapture? = null
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var front = false
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var lastShot: File? = null

    /** Photos approved so far in this session. */
    private val kept = ArrayList<android.net.Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        setTheme(Prefs.themeStyle())
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            // FIT, not FILL: at 4:3 the frame is shown whole, so what you see
            // is what the photo contains
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        root.addView(previewView)

        // the frame under review sits on top of the viewfinder
        shotView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(shotView)

        // v1.7.78 (user laid out exactly where everything goes).
        //
        // TOP RIGHT — flash only, because it is the one setting you change
        // while already aiming.
        flashBtn = iconButton(R.drawable.ic_flash_auto) { cycleFlash() }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            ).apply { topMargin = statusBar() + dp(8) }
            setPadding(dp(8), 0, dp(10), 0)
            addView(flashBtn)
        })

        // BOTTOM — ✕ left, shutter centre, flip right. The side buttons sit in
        // equal-weight halves so the shutter stays on the centre line whatever
        // they contain.
        liveControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@CameraAiActivity).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(iconButton(R.drawable.ic_close) { finishWithResult() })
            })
            addView(shutterButton())
            addView(LinearLayout(this@CameraAiActivity).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(iconButton(R.drawable.ic_camera_flip) { flip() })
            })
        }

        // AFTER THE SHOT — nothing but the frame: ✕ left, ✓ right.
        reviewControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(LinearLayout(this@CameraAiActivity).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(bigButton("✕", 0x66000000, Color.WHITE) { retake() })
            })
            addView(LinearLayout(this@CameraAiActivity).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(bigButton("✓", 0xFF10B981.toInt(), Color.WHITE) { accept() })
            })
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
            setPadding(dp(16), dp(10), dp(16), dp(20) + navBar())
            addView(liveControls)
            addView(reviewControls)
        })

        setContentView(root)
        startCamera()
    }

    // ---------------- camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (_: Throwable) {
                Toast.makeText(this, R.string.cam_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        // 4:3 — the sensor's native shape, and the one that fits a sheet of
        // paper without cutting its edges off
        val preview = Preview.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
            .build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        capture = ImageCapture.Builder()
            // a document photo wants speed, not the sensor's finest hour
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
            .setFlashMode(flashMode)
            .build()
        val selector =
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, selector, preview, capture)
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.cam_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun flip() {
        front = !front
        bind()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        flashBtn.setImageResource(
            when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off
                else -> R.drawable.ic_flash_auto
            }
        )
        // setFlashMode applies to the live use case without a rebind
        capture?.flashMode = flashMode
    }

    // ---------------- shoot · review ----------------

    private fun shoot() {
        val cap = capture ?: return
        // the cache, not the gallery: this photo is a question, not a memory
        val out = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(out).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    lastShot = out
                    showReview(out)
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@CameraAiActivity, R.string.cam_failed, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun showReview(file: File) {
        try {
            // decoded small: this is a check, not a gallery viewer
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            shotView.setImageBitmap(upright(file, bmp))
        } catch (_: Throwable) {}
        shotView.visibility = View.VISIBLE
        liveControls.visibility = View.GONE
        reviewControls.visibility = View.VISIBLE
        // nothing but the frame while it is being judged
        flashBtn.visibility = View.GONE
    }

    /**
     * v1.7.80 (user: «фотография почему-то переворачивается, а когда отправляю,
     * отправляется как и сфоткал»).
     *
     * Exactly so: CameraX does not rotate the pixels, it records the camera's
     * orientation in an EXIF tag, and everything downstream honours that tag —
     * which is why the photo the assistant received was always right. The one
     * place that did not honour it was this review screen, because
     * BitmapFactory hands back the raw pixel grid and ignores EXIF entirely.
     *
     * So the rotation is applied here, for display only. The file on disk is
     * left untouched: re-encoding it would cost quality and solve nothing.
     */
    private fun upright(file: File, bmp: android.graphics.Bitmap?): android.graphics.Bitmap? {
        if (bmp == null) return null
        val m = android.graphics.Matrix()
        try {
            val exif = android.media.ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                // the front camera can also record a mirrored frame
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                    m.postRotate(90f); m.postScale(-1f, 1f)
                }
                android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                    m.postRotate(270f); m.postScale(-1f, 1f)
                }
                else -> return bmp
            }
        } catch (_: Throwable) { return bmp }
        return try {
            android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (_: Throwable) { bmp }
    }

    private fun retake() {
        lastShot?.delete()
        lastShot = null
        shotView.setImageDrawable(null)
        shotView.visibility = View.GONE
        reviewControls.visibility = View.GONE
        liveControls.visibility = View.VISIBLE
        flashBtn.visibility = View.VISIBLE
    }

    /**
     * Keep the frame and hand it to the composer.
     *
     * The camera closes here rather than waiting for more shots: a second photo
     * is one tap away from the chat's own "+", and staying open would leave the
     * user in a viewfinder wondering how to get out with what they took.
     */
    private fun accept() {
        val file = lastShot ?: return
        try {
            kept.add(
                androidx.core.content.FileProvider.getUriForFile(
                    this, packageName + ".fileprovider", file
                )
            )
        } catch (_: Throwable) {}
        lastShot = null
        finishWithResult()
    }

    /**
     * Hand whatever was kept to the composer — attached, NOT sent.
     *
     * The question belongs to the user; the camera's job ends at the picture.
     */
    private fun finishWithResult() {
        if (kept.isEmpty()) { finish(); return }
        val main = com.zephyr.browser.ai.AiTools.host
        finish()
        if (main != null) {
            com.zephyr.browser.ai.AiAssistantSheet.attachFiles(main, ArrayList(kept))
        }
    }

    override fun onBackPressed() {
        if (reviewControls.visibility == View.VISIBLE) retake() else finishWithResult()
    }

    // ---------------- chrome ----------------

    /**
     * v1.7.79 (user: «крестик слева тоже помести в круг, так же как крестик
     * после фотки, чтобы кнопки не отличались и были одного варианта»).
     *
     * One circle, one size, for every secondary control — close, flip, discard,
     * accept. Only the shutter is bigger, because it is the one you aim for
     * without looking.
     */
    private val SIDE = 58
    private val SHUTTER = 74

    private fun shutterButton(): View = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(SHUTTER), dp(SHUTTER))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FFFFFF)
            setStroke(dp(3), Color.WHITE)
        }
        isClickable = true
        addView(View(this@CameraAiActivity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(SHUTTER - 18), dp(SHUTTER - 18), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        })
        setOnClickListener { shoot() }
    }

    private fun bigButton(glyph: String, bg: Int, fg: Int, onTap: () -> Unit): View =
        TextView(this).apply {
            text = glyph
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(fg)
            layoutParams = LinearLayout.LayoutParams(dp(SIDE), dp(SIDE))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
                setStroke(dp(2), 0x55FFFFFF)
            }
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun iconButton(icon: Int, onTap: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(dp(SIDE), dp(SIDE))
        // the same circle the discard and accept buttons wear
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66000000)
            setStroke(dp(2), 0x55FFFFFF)
        }
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun statusBar(): Int = barHeight("status_bar_height")
    private fun navBar(): Int = barHeight("navigation_bar_height")

    private fun barHeight(name: String): Int = try {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    } catch (_: Throwable) { 0 }
}
