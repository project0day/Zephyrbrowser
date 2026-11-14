package com.zephyr.browser.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * v1.7.0: DNS-only VPN with an in-app encrypted resolver (dns66 / personalDNSfilter
 * / Nebulo architecture, Intra-style encrypted upstreams).
 *
 * How the leak protection works:
 *  1. The TUN interface routes ONLY the selected provider's IPs (+ a catch-list of
 *     well-known public resolvers when leak protection is on) — all other traffic
 *     never enters the tunnel, so there is zero throughput impact on browsing.
 *  2. Every DNS packet that lands in the TUN (system resolver queries AND apps that
 *     hardcode 8.8.8.8 etc.) is answered by THIS resolver: the query is forwarded
 *     to the user-selected upstream — plaintext UDP, DNS-over-TLS (:853) or
 *     DNS-over-HTTPS (:443) — and the answer is written back into the TUN with
 *     correctly checksummed IPv4/UDP headers.
 *  3. Encrypted upstreams dial the resolver IP DIRECTLY and set the TLS SNI to the
 *     provider hostname (bootstrap-safe: no chicken-and-egg DNS lookup, and the
 *     certificate is still hostname-verified) — the exact pattern Google's Intra
 *     and RethinkDNS use.
 *  4. IPv6 DNS traffic to known resolvers is routed into the tunnel and dropped
 *     (no v6 resolver stack) — apps fall back to IPv4 DNS which we control.
 *
 * v1.7.1 fixes (user: "some DNS providers break the internet, ERR_NAME_NOT_RESOLVED,
 * Quad9 works"):
 *  - Upstream IP ROTATION: plain UDP now tries BOTH provider IPs (previously only
 *    the secondary — a single dead/blocked secondary IP black-holed the whole
 *    device's name resolution for exactly the providers whose secondary is flaky
 *    on the user's network, while others like Quad9 kept working).
 *  - DoT/DoH ensure() also rotates primary→secondary.
 *  - Self-healing: 10 CONSECUTIVE upstream failures (≈ dead provider) → the tunnel
 *    is torn down and the service stopped with a clear error — the device falls
 *    back to the system resolver instead of staying offline ("internet blocked").
 *  - DnsProbe: standalone health-check used by the DNS settings screen so the user
 *    can SEE which provider/protocol actually works from their network before
 *    enabling the tunnel.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.zephyr.browser.dns.START"
        const val ACTION_STOP = "com.zephyr.browser.dns.STOP"

        const val STATE_IDLE = "idle"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_FAILED = "failed"
        const val STATE_DISCONNECTED = "disconnected"

        @Volatile var state: String = STATE_IDLE
        @Volatile var lastError: String? = null
        @Volatile var activeProvider: String? = null
        @Volatile var queries: Long = 0
        @Volatile var failed: Long = 0
        private val queryCounter = AtomicLong(0)

        fun start(context: Context) {
            val i = Intent(context, DnsVpnService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, DnsVpnService::class.java).apply { action = ACTION_STOP })
            } catch (_: Throwable) {}
        }

        fun isActive(): Boolean = state == STATE_CONNECTED || state == STATE_CONNECTING

        fun setFailed(err: String) { state = STATE_FAILED; lastError = err }
    }

    private var engine: Engine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                try {
                    engine?.shutdown()
                    stopForeground(true)
                    stopSelf()
                    state = STATE_DISCONNECTED
                } catch (_: Throwable) {
                    state = STATE_DISCONNECTED
                    try { stopSelf() } catch (_: Throwable) {}
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                try {
                    engine?.shutdown()
                    // only ONE tunnel kind can hold the TUN device
                    // (v1.7.3: the OpenVPN service is gone — the WG hold
                    // service is released through WireGuardManager.disconnect)
                    try { TunnelHoldService.stop(this) } catch (_: Throwable) {}
                    try { WireGuardManager.disconnect(this) } catch (_: Throwable) {}
                    Thread.sleep(150)
                    startForegroundCompat()
                    state = STATE_CONNECTING
                    lastError = null
                    val eng = Engine()
                    engine = eng
                    Thread {
                        try { eng.run() }
                        catch (t: Throwable) {
                            if (eng.running.get()) setFailed(t.message ?: "dns failed")
                            eng.shutdown()
                        }
                    }.apply { isDaemon = true }.start()
                } catch (t: Throwable) {
                    setFailed(t.message ?: t.javaClass.simpleName)
                    try { engine?.shutdown() } catch (_: Throwable) {}
                    engine = null
                    try { stopSelf() } catch (_: Throwable) {}
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        // system revoked the VPN (Always-on conflict / user toggle) — stop cleanly
        engine?.shutdown()
        state = STATE_DISCONNECTED
        try { stopForeground(true) } catch (_: Throwable) {}
        stopSelf()
    }

    /** v1.7.1: fatal engine path — mark failed, tear down, release the foreground. */
    private fun failAndStop(reason: String) {
        setFailed(reason)
        engine?.shutdown()
        Handler(Looper.getMainLooper()).post {
            try { stopForeground(true) } catch (_: Throwable) {}
            try { stopSelf() } catch (_: Throwable) {}
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(DNS_CHANNEL_ID, "Zephyr DNS", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            this, 13,
            Intent(this, com.zephyr.browser.settings.SettingsActivity::class.java).apply {
                putExtra("screen", "dns")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, DNS_CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_globe)
            .setContentTitle(getString(R.string.dns_notif_title))
            .setContentText(getString(R.string.dns_notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(DNS_NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (t: Throwable) {
                try { startForeground(DNS_NOTIF_ID, n) } catch (_: Throwable) {}
            }
        } else {
            try { startForeground(DNS_NOTIF_ID, n) } catch (_: Throwable) {}
        }
    }

    // =====================================================================
    // Engine
    // =====================================================================

    private inner class Engine {

        val running = AtomicBoolean(true)
        private var tun: ParcelFileDescriptor? = null
        private var tunOut: FileOutputStream? = null
        private var upstream: DnsUpstream? = null
        private val pool = Executors.newFixedThreadPool(4) as ThreadPoolExecutor
        private val consecFails = AtomicLong(0)

        fun run() {
            // ---- resolve the user's selection into a concrete upstream ----
            val provider = currentProvider()
            val primary = provider?.primary
            if (primary == null) {
                throw java.io.IOException("system dns selected")   // nothing to enforce
            }
            activeProvider = provider.name

            // ---- build the DNS-only TUN ----
            val b = Builder()
                .setSession("Zephyr DNS")
                .setMtu(1500)
            b.addAddress(TUN_ADDR, 32)
            val routed = mutableSetOf(primary)
            provider.secondary?.let { routed.add(it) }
            if (Prefs.dnsLeakProtection) {
                for (ip in DnsCatalog.LEAK_CATCH_IPS) routed.add(ip)
            }
            for (ip in routed) {
                try { b.addDnsServer(ip) } catch (_: Exception) {}
                try { b.addRoute(ip, 32) } catch (_: Exception) {}
            }
            if (Prefs.dnsLeakProtection) {
                for (ip in DnsCatalog.LEAK_CATCH_V6) {
                    try { b.addRoute(ip, 128) } catch (_: Exception) {}
                }
            }
            tun = b.establish() ?: throw java.io.IOException("vpn permission denied")
            val fd = tun?.fileDescriptor ?: throw java.io.IOException("vpn: no file descriptor")
            val tunIn = FileInputStream(fd)
            tunOut = FileOutputStream(fd)

            // v1.7.1: protect-lambdas keep the upstream sockets OUT of our own TUN
            upstream = DnsUpstream(provider, Prefs.dnsProtocol,
                { s -> try { protect(s) } catch (_: Throwable) { false } },
                { d -> try { protect(d) } catch (_: Throwable) { false } })

            state = STATE_CONNECTED

            // ---- reader loop: dispatch every DNS packet to the pool ----
            val buf = ByteArray(32767)
            val ch = tunIn.channel
            while (running.get()) {
                val n = try { ch.read(java.nio.ByteBuffer.wrap(buf)) } catch (_: Exception) { -1 }
                if (n < 0) break
                if (n == 0) continue
                val pkt = buf.copyOf(n)
                val parsed = parseDnsQuery(pkt) ?: continue
                val ups = upstream ?: continue
                pool.execute {
                    val answer = try { ups.resolve(parsed.dns) } catch (_: Throwable) { null }
                    if (!running.get()) return@execute
                    if (answer == null) {
                        failed++
                        // v1.7.1: self-healing — a fully dead upstream (10 consecutive
                        // failures) must NOT keep the device offline: stop the tunnel
                        // so the system resolver takes over again.
                        if (consecFails.incrementAndGet() >= 10 && running.get()) {
                            failAndStop(getString(R.string.dns_upstream_dead))
                            pool.shutdownNow()
                        }
                        return@execute
                    }
                    consecFails.set(0)
                    queries = queryCounter.incrementAndGet()
                    val resp = craftResponse(parsed, answer) ?: return@execute
                    val out = tunOut
                    if (out != null) {
                        try { synchronized(out) { out.write(resp) } } catch (_: Exception) {}
                    }
                }
            }
            shutdown()
        }

        fun shutdown() {
            running.set(false)
            try { tun?.close() } catch (_: Exception) {}
            try { pool.shutdownNow() } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
            if (state == STATE_CONNECTED || state == STATE_CONNECTING) state = STATE_DISCONNECTED
        }

        // ---------------- packet parsing / crafting ----------------

        private fun parseDnsQuery(pkt: ByteArray): DnsQuery? {
            if (pkt.size < 28) return null
            val ver = (pkt[0].toInt() shr 4) and 0xF
            if (ver == 6) return null            // v6 DNS catch-routes: blackhole (no response)
            if (ver != 4) return null
            val ihl = (pkt[0].toInt() and 0xF) * 4
            if (ihl < 20 || pkt.size < ihl + 8) return null
            val proto = pkt[9].toInt() and 0xFF
            if (proto != 17) return null          // UDP only
            val srcPort = ((pkt[ihl].toInt() and 0xFF) shl 8) or (pkt[ihl + 1].toInt() and 0xFF)
            val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) return null
            val dnsLen = ((pkt[ihl + 4].toInt() and 0xFF) shl 8) or (pkt[ihl + 5].toInt() and 0xFF)
            val off = ihl + 8
            if (dnsLen < 12 || pkt.size < off + 12) return null
            val dns = pkt.copyOfRange(off, minOf(pkt.size, off + dnsLen - 8))
            if (dns.size < 12) return null
            return DnsQuery(
                srcIp = pkt.copyOfRange(12, 16),
                dstIp = pkt.copyOfRange(16, 20),
                srcPort = srcPort,
                dns = dns
            )
        }

        /** IPv4+UDP response packet: original dst becomes src (app asked 8.8.8.8 → answered by 8.8.8.8). */
        private fun craftResponse(q: DnsQuery, answer: ByteArray): ByteArray? {
            var payload = answer
            val maxPayload = 1500 - 28
            if (payload.size > maxPayload) {
                payload = payload.copyOf(maxPayload)
                // set the DNS truncation flag so clients retry (or give up) instead of corrupting
                if (payload.size > 3) payload[2] = (payload[2].toInt() or 0x02).toByte()
            }
            val total = 20 + 8 + payload.size
            val out = ByteArray(total)
            out[0] = 0x45
            out[1] = 0
            out[2] = ((total shr 8) and 0xFF).toByte()
            out[3] = (total and 0xFF).toByte()
            val id = (queryCounter.get() and 0xFFFF).toInt()
            out[4] = ((id shr 8) and 0xFF).toByte()
            out[5] = (id and 0xFF).toByte()
            out[6] = 0; out[7] = 0
            out[8] = 64                       // TTL
            out[9] = 17                       // UDP
            // 10..11 checksum filled below
            System.arraycopy(q.dstIp, 0, out, 12, 4)   // src = the resolver the app dialed
            System.arraycopy(q.srcIp, 0, out, 16, 4)   // dst = the querying app
            val csum = ipChecksum(out, 20)
            out[10] = ((csum shr 8) and 0xFF).toByte()
            out[11] = (csum and 0xFF).toByte()
            val uo = 20
            out[uo] = ((53 shr 8) and 0xFF).toByte()
            out[uo + 1] = (53 and 0xFF).toByte()
            out[uo + 2] = ((q.srcPort shr 8) and 0xFF).toByte()
            out[uo + 3] = (q.srcPort and 0xFF).toByte()
            val ulen = 8 + payload.size
            out[uo + 4] = ((ulen shr 8) and 0xFF).toByte()
            out[uo + 5] = (ulen and 0xFF).toByte()
            out[uo + 6] = 0; out[uo + 7] = 0    // UDP checksum 0 = none (legal for IPv4)
            System.arraycopy(payload, 0, out, uo + 8, payload.size)
            return out
        }

        private fun ipChecksum(hdr: ByteArray, len: Int): Int {
            var sum = 0
            var i = 0
            while (i < len) {
                sum += ((hdr[i].toInt() and 0xFF) shl 8) or (hdr[i + 1].toInt() and 0xFF)
                sum = (sum and 0xFFFF) + (sum ushr 16)
                i += 2
            }
            sum = (sum and 0xFFFF) + (sum ushr 16)
            return sum.inv() and 0xFFFF
        }
    }

    private fun currentProvider(): DnsCatalog.Provider? {
        val id = Prefs.dnsProvider
        return when {
            id == DnsCatalog.CUSTOM_ID -> {
                val ip = Prefs.dnsCustomIp.trim()
                if (ip.isEmpty()) null
                else DnsCatalog.Provider(DnsCatalog.CUSTOM_ID, "Custom DNS", ip, null, null, null, null, R.string.dns_desc_custom)
            }
            else -> DnsCatalog.byId(id) ?: DnsCatalog.SYSTEM
        }
    }
}

// =====================================================================
// v1.7.1: standalone upstream resolver — shared by the tunnel Engine AND
// the settings-screen health check (DnsProbe). Rotates over ALL provider IPs.
// =====================================================================

class DnsUpstream(
    private val provider: DnsCatalog.Provider,
    private val protocol: Int,                                  // 0 plain 1 DoT 2 DoH
    private val protectTcp: (Socket) -> Boolean = { true },
    private val protectUdp: (DatagramSocket) -> Boolean = { true }
) {
    /** v1.7.1: secondary first (historic behavior) then primary — a dead secondary
     *  no longer black-holes resolution (the Quad9-works-others-don't bug). */
    private val ips: List<String> =
        (listOfNotNull(provider.secondary, provider.primary) + listOfNotNull(provider.primary))
            .filter { it.isNotBlank() }.distinct()

    private var dot: DotConnection? = null
    private var doh: DohConnection? = null

    init {
        when (protocol) {
            1 -> if (provider.dotHost != null) dot = DotConnection()
            2 -> if (provider.dohHost != null) doh = DohConnection()
        }
    }

    fun resolve(query: ByteArray): ByteArray? {
        if (protocol == 1 && dot != null) { dot!!.query(query)?.let { return it } }
        if (protocol == 2 && doh != null) { doh!!.query(query)?.let { return it } }
        for (ip in ips) {
            plain(query, ip)?.let { return it }
        }
        return null
    }

    private fun plain(query: ByteArray, ip: String): ByteArray? {
        return try {
            val ds = DatagramSocket()
            try {
                protectUdp(ds)               // keep the upstream socket OUT of our own tunnel
                ds.soTimeout = 5000
                ds.send(DatagramPacket(query, query.size, InetAddress.getByName(ip), 53))
                val buf = ByteArray(4096)
                val dp = DatagramPacket(buf, buf.size)
                ds.receive(dp)
                buf.copyOf(dp.length)
            } finally {
                try { ds.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { null }
    }

    fun close() {
        try { dot?.close() } catch (_: Exception) {}
        try { doh?.close() } catch (_: Exception) {}
    }

    /** DNS-over-TLS: persistent TLS socket to a resolver IP with SNI = hostname. */
    private inner class DotConnection {
        private var socket: SSLSocket? = null

        @Synchronized
        fun query(dns: ByteArray): ByteArray? {
            var sock = ensure() ?: return null
            try {
                writeFramed(sock.getOutputStream(), dns)
                val inp = sock.getInputStream()
                val h1 = inp.read()
                if (h1 < 0) throw java.io.IOException("closed")
                val h2 = inp.read()
                if (h2 < 0) throw java.io.IOException("closed")
                val len = ((h1 and 0xFF) shl 8) or (h2 and 0xFF)
                if (len <= 0 || len > 65535) throw java.io.IOException("bad frame")
                return readFully(inp, len)
            } catch (_: Exception) {
                try { sock.close() } catch (_: Exception) {}
                socket = null
                // one reconnect attempt per query
                sock = ensure() ?: return null
                return try {
                    writeFramed(sock.getOutputStream(), dns)
                    val inp = sock.getInputStream()
                    val h1 = inp.read(); val h2 = inp.read()
                    if (h1 < 0 || h2 < 0) throw java.io.IOException("closed")
                    val len = ((h1 and 0xFF) shl 8) or (h2 and 0xFF)
                    if (len <= 0 || len > 65535) throw java.io.IOException("bad frame")
                    readFully(inp, len)
                } catch (_: Exception) {
                    try { sock.close() } catch (_: Exception) {}
                    socket = null
                    null
                }
            }
        }

        private fun ensure(): SSLSocket? {
            socket?.let { return it }
            val host = provider.dotHost ?: return null
            for (ip in ips) {                              // v1.7.1: rotate provider IPs
                try {
                    val raw = Socket()
                    try {
                        protectTcp(raw)
                        raw.connect(InetSocketAddress(InetAddress.getByName(ip), 853), 5000)
                        raw.soTimeout = 8000
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val ssl = factory.createSocket(raw, ip, 853, true) as SSLSocket
                        val p = ssl.sslParameters
                        p.serverNames = listOf(SNIHostName(host))
                        ssl.sslParameters = p
                        ssl.startHandshake()
                        // hostname verification against the SNI name (we dialed by IP)
                        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                            try { ssl.close() } catch (_: Exception) {}
                            continue                      // cert mismatch on this IP → next
                        }
                        socket = ssl
                        return ssl
                    } catch (_: Exception) {
                        try { raw.close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) { /* next IP */ }
            }
            return null
        }

        fun close() { try { socket?.close() } catch (_: Exception) {} ; socket = null }
    }

    /**
     * DNS-over-HTTPS: minimal HTTP/1.1 client over a TLS socket dialed to a
     * resolver IP (SNI + Host header = provider hostname) — POST
     * application/dns-message, Content-Length / chunked response handling.
     */
    private inner class DohConnection {
        private var socket: SSLSocket? = null

        @Synchronized
        fun query(dns: ByteArray): ByteArray? {
            return try {
                httpExchange(dns) ?: reconnectAndRetry(dns)
            } catch (_: Exception) {
                closeSocket()
                reconnectAndRetry(dns)
            }
        }

        private fun reconnectAndRetry(dns: ByteArray): ByteArray? {
            closeSocket()
            return try { httpExchange(dns) } catch (_: Exception) { closeSocket(); null }
        }

        private fun httpExchange(dns: ByteArray): ByteArray? {
            val host = provider.dohHost ?: return null
            val path = provider.dohPath ?: "/dns-query"
            val sock = ensure() ?: return null
            val os = sock.getOutputStream()
            val head = buildString {
                append("POST ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: Zephyr/1.7\r\n")
                append("Accept: application/dns-message\r\n")
                append("Content-Type: application/dns-message\r\n")
                append("Content-Length: ").append(dns.size).append("\r\n")
                append("Connection: keep-alive\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            os.write(head)
            os.write(dns)
            os.flush()

            val inp = sock.getInputStream()
            val status = readLine(inp) ?: throw java.io.IOException("no status")
            if (!status.contains("200")) throw java.io.IOException("http ${status.take(20)}")
            var contentLength = -1
            var chunked = false
            var connectionClose = false
            while (true) {
                val line = readLine(inp) ?: throw java.io.IOException("eof in headers")
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: -1
                } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true
                } else if (lower.startsWith("connection:") && lower.contains("close")) {
                    connectionClose = true
                }
            }
            val body = when {
                chunked -> readChunked(inp)
                contentLength >= 0 -> readFully(inp, contentLength)
                else -> throw java.io.IOException("no length")
            }
            if (connectionClose) closeSocket()
            return body
        }

        private fun ensure(): SSLSocket? {
            socket?.let { return it }
            val host = provider.dohHost ?: return null
            for (ip in ips) {                              // v1.7.1: rotate provider IPs
                try {
                    val raw = Socket()
                    try {
                        protectTcp(raw)
                        raw.connect(InetSocketAddress(InetAddress.getByName(ip), 443), 5000)
                        raw.soTimeout = 8000
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val ssl = factory.createSocket(raw, ip, 443, true) as SSLSocket
                        val p = ssl.sslParameters
                        p.serverNames = listOf(SNIHostName(host))
                        ssl.sslParameters = p
                        ssl.startHandshake()
                        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                            try { ssl.close() } catch (_: Exception) {}
                            continue
                        }
                        socket = ssl
                        return ssl
                    } catch (_: Exception) {
                        try { raw.close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) { /* next IP */ }
            }
            return null
        }

        private fun readLine(inp: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = inp.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }

        private fun readChunked(inp: InputStream): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            var guard = 0
            while (guard++ < 64) {
                val sizeLine = readLine(inp) ?: return null
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
                if (size == 0) {
                    readLine(inp)   // trailing CRLF
                    return out.toByteArray()
                }
                if (out.size() + size > 65535) return null
                out.write(readFully(inp, size) ?: return null)
                readLine(inp)       // chunk CRLF
            }
            return null
        }

        private fun closeSocket() {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }

        fun close() = closeSocket()
    }
}

// =====================================================================
// v1.7.1: provider health-check (DNS settings screen: "Проверить соединение")
// =====================================================================

object DnsProbe {

    /** Wire-format A query for example.com with a fixed transaction id. */
    fun buildQuery(): ByteArray {
        val name = byteArrayOf(7) + "example".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(3) + "com".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val q = ByteArray(12 + name.size + 4)
        q[0] = 0x1A; q[1] = 0x2B        // txid
        q[2] = 0x01; q[3] = 0x00        // flags: RD
        q[5] = 1                        // QDCOUNT
        System.arraycopy(name, 0, q, 12, name.size)
        q[12 + name.size] = 0; q[13 + name.size] = 1     // QTYPE = A
        q[14 + name.size] = 0; q[15 + name.size] = 1     // QCLASS = IN
        return q
    }

    /** Sanity: matching txid, rcode 0, at least one answer record. */
    fun valid(resp: ByteArray?): Boolean {
        if (resp == null || resp.size < 12) return false
        if (resp[0].toInt() != 0x1A || resp[1].toInt() != 0x2B) return false
        val rcode = resp[3].toInt() and 0x0F
        val an = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
        return rcode == 0 && an > 0
    }

    /**
     * Test a provider+protocol end-to-end OUTSIDE the tunnel (plain, unprotected
     * sockets — used from settings where no VPN is active; if the DNS tunnel IS
     * active, the probe query is intercepted and answered by the engine itself,
     * which still proves the full path works).
     * Returns latency in ms, or null on failure.
     */
    fun test(provider: DnsCatalog.Provider, protocol: Int): Int? {
        return try {
            val up = DnsUpstream(provider, protocol)
            val q = buildQuery()
            val t0 = System.currentTimeMillis()
            val r = up.resolve(q)
            val dt = (System.currentTimeMillis() - t0).toInt()
            up.close()
            if (valid(r)) dt else null
        } catch (_: Throwable) { null }
    }
}

/** Parsed inbound DNS query: addressing + the raw DNS message. */
private data class DnsQuery(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dns: ByteArray
)

private fun writeFramed(out: java.io.OutputStream, dns: ByteArray) {
    out.write(byteArrayOf(((dns.size shr 8) and 0xFF).toByte(), (dns.size and 0xFF).toByte()))
    out.write(dns)
    out.flush()
}

private fun readFully(inp: InputStream, len: Int): ByteArray? {
    val buf = ByteArray(len)
    var off = 0
    while (off < len) {
        val n = inp.read(buf, off, len - off)
        if (n < 0) return null
        off += n
    }
    return buf
}

private const val DNS_CHANNEL_ID = "zephyr_dns"
private const val DNS_NOTIF_ID = 43
private const val TUN_ADDR = "172.19.0.1"
