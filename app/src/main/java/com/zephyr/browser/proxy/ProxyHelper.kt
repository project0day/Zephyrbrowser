package com.zephyr.browser.proxy

import android.content.Context
import com.zephyr.browser.Prefs
import android.content.Intent
import android.net.Uri
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

/**
 * Browser proxy helper (v1.7.3: OpenVPN profile plumbing removed with the
 * engine — WireGuard/Amnezia/WARP profiles live in [WireGuardManager]).
 * - WebView HTTP(S) proxy via androidx.webkit ProxyController (official API).
 * - Orbot presets (HTTP 8118; SOCKS 9050 unsupported by WebView — documented).
 */
object ProxyHelper {

    /** Direct executor: callbacks run on the calling thread. */
    private val direct: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }

    const val ORBOT_PKG = "org.torproject.android"
    const val ORBOT_HTTP_PORT = 8118
    const val ORBOT_SOCKS_PORT = 9050
    const val I2P_HTTP_PORT = 4444

    fun proxySupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /** Apply proxy from Prefs. Returns true if a proxy is now active. */
    fun applyFromPrefs(context: Context): Boolean {
        if (!proxySupported()) return false
        return try {
            val type = Prefs.proxyType
            if (type == 0) {
                ProxyController.getInstance().clearProxyOverride(direct, Runnable {})
                false
            } else {
                val host: String
                val port: Int
                when (type) {
                    2 -> { host = "127.0.0.1"; port = ORBOT_HTTP_PORT }
                    3 -> { host = "127.0.0.1"; port = I2P_HTTP_PORT }
                    else -> { host = Prefs.proxyHost; port = Prefs.proxyPort }
                }
                if (host.isEmpty() || port <= 0) {
                    ProxyController.getInstance().clearProxyOverride(direct, Runnable {})
                    return false
                }
                val config = ProxyConfig.Builder()
                    .addProxyRule("$host:$port")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("10.*")
                    .addBypassRule("192.168.*")
                    .addBypassRule("172.16.*")
                    .addBypassRule("172.17.*")
                    .addBypassRule("172.18.*")
                    .addBypassRule("::1")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, direct, Runnable {})
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun orbotInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(ORBOT_PKG, 0)
        true
    } catch (_: Exception) { false }

    fun openOrbot(context: Context): Boolean = try {
        context.startActivity(
            Intent("org.torproject.android.START_ORBOT").setPackage(ORBOT_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .takeIf { it.resolveActivity(context.packageManager) != null }
                ?: Intent().setPackage(ORBOT_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ORBOT_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }
}
