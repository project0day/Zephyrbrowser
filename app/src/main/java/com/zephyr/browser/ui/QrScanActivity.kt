package com.zephyr.browser.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.zephyr.browser.MainActivity
import com.zephyr.browser.Prefs
import com.zephyr.browser.R

/**
 * v1.7.7: QR-code scanner (user request: «QR-сканер — открыть ссылку/Wi-Fi
 * наводением»). CameraX preview + zxing decoding of the luminance plane at
 * ~10 fps. Result sheet: links open in the browser / copy / share; WIFI:
 * codes parse into SSID + password (copy / connect hint); plain text:
 * copy / share / search.
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private var reader: MultiFormatReader? = null
    private var torchOn = false
    private var camera: androidx.camera.core.Camera? = null
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        setTheme(Prefs.themeStyle())
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0D12.toInt())
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        // ---- top bar: back + title ----
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(4), dp(10), dp(8), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP)
            addView(ImageButton(this@QrScanActivity).apply {
                setImageResource(R.drawable.ic_close)
                background = MenuSheets.ripple(this@QrScanActivity)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                contentDescription = getString(R.string.cancel)
                setOnClickListener { finish() }
            })
            addView(TextView(this@QrScanActivity).apply {
                text = getString(R.string.qr_title)
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
            })
        })

        // ---- framing reticle + hint ----
        val reticle = View(this).apply {
            background = MenuSheets.roundedRipple(this@QrScanActivity, 26)
            background = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this@QrScanActivity, R.drawable.bg_qr_reticle)
            layoutParams = FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER)
        }
        root.addView(reticle)
        root.addView(TextView(this).apply {
            text = getString(R.string.qr_hint)
            textSize = 13.5f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(120) }
        })

        // ---- torch toggle ----
        root.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_torch)
            background = MenuSheets.ripple(this@QrScanActivity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.qr_torch)
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(40)
            }
            setOnClickListener {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                alpha = if (torchOn) 1f else 0.65f
            }
            alpha = 0.65f
        })

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 4471)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4471) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else {
                Toast.makeText(this, R.string.qr_no_camera, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                reader = MultiFormatReader().apply {
                    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                }
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                    if (!handled) tryDecode(proxy)
                    else proxy.close()
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                Toast.makeText(this, R.string.qr_no_camera, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tryDecode(proxy: ImageProxy) {
        try {
            val plane = proxy.planes[0]
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val src = PlanarYUVLuminanceSource(
                bytes, proxy.width, proxy.height,
                0, 0, proxy.width, proxy.height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(src))
            val result = reader?.decodeWithState(bitmap)
            if (result != null) onScanned(result.text)
            reader?.reset()
        } catch (_: Exception) {
            // no QR in this frame — normal
        } finally {
            try { proxy.close() } catch (_: Exception) {}
        }
    }

    /** Handles the scan result ONCE (vibrate + result dialog). */
    private fun onScanned(text: String) {
        if (handled || text.isEmpty()) return
        handled = true
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(60)
        } catch (_: Exception) {}
        showResult(text)
    }

    private fun showResult(text: String) {
        val isUrl = text.startsWith("http://", true) || text.startsWith("https://", true)
        val isWifi = text.startsWith("WIFI:", true)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qr_result)

        if (isWifi) {
            // WIFI:T:WPA;S:<ssid>;P:<pass>;H:false;;
            val ssid = Regex("(?:^|;)S:([^;]*)").find(text)?.groupValues?.get(1) ?: ""
            val pass = Regex("(?:^|;)P:([^;]*)").find(text)?.groupValues?.get(1) ?: ""
            val msg = getString(R.string.qr_wifi_net) +
                (if (ssid.isNotEmpty()) "\n${getString(R.string.qr_wifi_ssid)}: $ssid" else "") +
                (if (pass.isNotEmpty()) "\n${getString(R.string.qr_wifi_pass)}: $pass" else "")
            builder.setMessage(msg)
                .setPositiveButton(R.string.copy) { _, _ ->
                    copyToClipboard(pass.ifEmpty { text })
                }
                .setNeutralButton(R.string.qr_open_settings) { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    } catch (_: Exception) {}
                }
        } else if (isUrl) {
            builder.setMessage(text)
                .setPositiveButton(R.string.open) { _, _ ->
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = Uri.parse(text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                .setNeutralButton(R.string.copy) { _, _ -> copyToClipboard(text) }
        } else {
            builder.setMessage(text.take(600))
                .setPositiveButton(R.string.copy) { _, _ -> copyToClipboard(text) }
                .setNeutralButton(R.string.menu_find) { _, _ ->
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_ACTION, "searchText")
                        putExtra("q", text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
        }
        builder.setNegativeButton(R.string.qr_scan_again) { d, _ ->
            handled = false
            d.dismiss()
        }
        builder.setOnCancelListener { handled = false }
        builder.show()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("QR", text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
