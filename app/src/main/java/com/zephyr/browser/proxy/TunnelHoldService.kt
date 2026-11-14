package com.zephyr.browser.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.zephyr.browser.R

/**
 * v1.7.3: slim foreground HOLD service for the WireGuard/AmneziaWG tunnels.
 *
 * The GoBackend engines (official libwg-go + vendored AmneziaWG libawg-go) run
 * the LIBRARY'S own VpnService inside THIS process — but that library service is
 * not a foreground service, so Android would kill the process (and the tunnel
 * with it) as soon as Zephyr goes to the background. This service keeps the
 * process alive with the standard "VPN active" notification while a tunnel is
 * up. No tunnel logic of its own lives here.
 *
 * Replaces the OpenVPN-era hold service (the OpenVPN engine was
 * removed from the product in v1.7.3): everything except ACTION_HOLD and the
 * layered startForeground fallback was dropped.
 *
 * Lifecycle contract (mirrors the old hold semantics exactly):
 *  - [hold] starts the service in foreground mode with ACTION_HOLD;
 *  - [stop] delivers any non-HOLD action (or a null intent after a process
 *    restart) → the service leaves foreground and stops itself;
 *  - START_STICKY while held, so a sticky restart re-arms the notification only
 *    if the intent still says HOLD (null restart intents are treated as stop —
 *    WireGuardManager re-holds on its own reconnects).
 */
class TunnelHoldService : VpnService() {

    companion object {
        const val ACTION_HOLD = "com.zephyr.browser.vpn.HOLD"
        const val ACTION_STOP = "com.zephyr.browser.vpn.STOP"

        /**
         * Bring the process into the foreground while a WireGuard/AmneziaWG
         * tunnel is up. Safe to call repeatedly; failures are swallowed (the
         * tunnel itself keeps running non-foreground in the worst case).
         */
        fun hold(context: Context) {
            try {
                val i = Intent(context, TunnelHoldService::class.java).apply { action = ACTION_HOLD }
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (_: Throwable) { }
        }

        /**
         * Release the hold. Some OEMs throw while the app/service is mid-
         * transition (startService right around stopForeground / process
         * change) — swallowed here; the service also stops itself when the
         * engine ends.
         */
        fun stop(context: Context) {
            try {
                context.startService(Intent(context, TunnelHoldService::class.java).apply { action = ACTION_STOP })
            } catch (_: Throwable) { /* already gone / transitioning */ }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HOLD) {
            // foreground hold: no engine, just the notification
            try {
                startForegroundCompat()
            } catch (_: Throwable) { }
            return START_STICKY
        }
        // ACTION_STOP, a null sticky restart or anything else → release
        try {
            stopForeground(true)
        } catch (_: Throwable) { }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { stopForeground(true) } catch (_: Throwable) { }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Zephyr VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            this, 12,
            Intent(this, com.zephyr.browser.settings.SettingsActivity::class.java).apply {
                putExtra("screen", "proxy")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.vpn_notif_title))
            .setContentText(getString(R.string.vpn_notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
        val n = b.build()
        // Layered fallback, EVERY startForeground call wrapped. On Android 14 a
        // non-exempt app throws SecurityException / InvalidForegroundServiceType
        // Exception for a missing/mismatched FGS type. Order: specialUse
        // (manifest-declared, API 34+) → systemExempted → plain 2-arg → stay
        // non-fg (the tunnel still runs).
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (t: Throwable) {
                try {
                    startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                } catch (t2: Throwable) {
                    try { startForeground(NOTIF_ID, n) } catch (t3: Throwable) { /* stay non-fg */ }
                }
            }
        } else {
            try { startForeground(NOTIF_ID, n) } catch (_: Throwable) {}
        }
    }
}

/** Same channel as the old VPN service notification ("Zephyr VPN", LOW). */
private const val CHANNEL_ID = "zephyr_vpn"
/** 42: distinct from the old OpenVPN service (41) and DnsVpnService (43). */
private const val NOTIF_ID = 42
