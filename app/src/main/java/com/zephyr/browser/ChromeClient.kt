package com.zephyr.browser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.io.File

/** WebChromeClient: file chooser (incl. camera), popups, permissions, fullscreen video, favicons. */
class ChromeClient(private val activity: MainActivity) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        if (filePathCallback == null) return false
        activity.chooseFile(filePathCallback, fileChooserParams)
        return true
    }

    override fun onCreateWindow(
        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
    ): Boolean {
        if (resultMsg == null || !isUserGesture && !Prefs.popupsEnabled) return false
        if (!Prefs.popupsEnabled) return false
        return try {
            val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport ?: return false
            val popup = activity.createPopupWebView(view)
            popup.webViewClient = BrowserClient(activity)
            transport.webView = popup
            resultMsg.sendToTarget()
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onCloseWindow(window: WebView?) {
        window?.let { activity.closePopup(it) }
    }

    // ---------------- permissions ----------------

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?, callback: GeolocationPermissions.Callback?
    ) {
        if (origin == null || callback == null) return
        activity.requestGeolocation(origin, callback)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        activity.requestWebPermission(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        activity.cancelPermissionRequest(request)
    }

    // ---------------- fullscreen video ----------------

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) return
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        activity.enterFullscreen(view)
    }

    override fun onHideCustomView() {
        val v = customView ?: return
        customView = null
        (v.parent as? ViewGroup)?.removeView(v)
        activity.exitFullscreen()
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    fun hideCustomViewIfShown(): Boolean {
        if (customView != null) {
            onHideCustomView()
            return true
        }
        return false
    }

    /** v1.7.18 floating player: the LIVE fullscreen video view while chromium
     *  keeps its fullscreen state (null otherwise). The float window hosts this
     *  view directly; the pending CustomViewCallback stays honored. */
    fun activeCustomView(): View? = customView

    // ---------------- misc ----------------

    override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
        icon?.let { FaviconLoader.store(activity.currentTabForThreads()?.pageUrlSnapshot ?: "", it) }
    }

    /** v1.7.38: drive the hairline progress bar under the omnibox. */
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        activity.onPageProgress(newProgress)
        super.onProgressChanged(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (title != null) activity.onReceivedTitle(title)
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
        if (result == null) return false
        activity.showJsDialog(true, message ?: "", result)
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
        if (result == null) return false
        activity.showJsDialog(false, message ?: "", result)
        return true
    }

    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
        if (result == null) return false
        activity.showJsPrompt(message ?: "", defaultValue ?: "", result)
        return true
    }

    override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
        return true // silence
    }

    companion object {
        fun hasPermission(activity: Activity, perm: String): Boolean =
            ContextCompat.checkSelfPermission(activity, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

        fun cameraPerms(): Array<String> = buildArray()
        private fun buildArray(): Array<String> {
            val base = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < 33) base.add("android.permission.READ_EXTERNAL_STORAGE") // media access for camera chooser on old devices
            return base.toTypedArray()
        }
    }
}
