package com.zephyr.browser.proxy

import android.content.Context
import com.zephyr.browser.R
import java.io.File

/**
 * v1.7.0: WireGuard protocol support built on the official embeddable tunnel
 * library (com.wireguard.android:tunnel — GoBackend + libwg-go natives).
 *
 * v1.7.2 DUAL-BACKEND REWRITE — AmneziaWG is now REAL:
 *  - configs carrying Amnezia obfuscation keys (Jc/Jmin/Jmax/S1..S4/H1..H4/I1..I5/
 *    HeaderProtectionKey/…) are brought up by the vendored org.amnezia.awg engine
 *    (libawg-go.so built from amnezia-wg v3 sources for all 4 ABIs) — the
 *    obfuscation parameters are APPLIED, not dropped. v1.7.1 silently connected
 *    them as vanilla WireGuard, which obfuscated servers reject → "амнезия не
 *    работает!";
 *  - vanilla .conf files keep using the official (fast) WireGuard backend;
 *  - the engine is chosen automatically at connect() by re-parsing the config.
 *
 * v1.7.1 tolerant parsing is kept for both engines: unknown keys are skipped
 * (counted), section/key names are case-insensitive, CRLF/BOM/inline `#`
 * comments are handled.
 *
 * Architecture notes (deliberate, unchanged):
 *  - both GoBackends spin up their LIBRARY's own VpnService in THIS process; we
 *    keep the process alive with TunnelHoldService ACTION_HOLD;
 *  - only ONE tunnel kind may own the TUN device at a time (mutual exclusion);
 *  - handshake watchdog (v1.7.1): UP-without-handshake black-holes get torn down
 *    after 40 s with a clear error.
 */
object WireGuardManager {

    const val STATE_IDLE = "idle"
    const val STATE_CONNECTING = "connecting"
    const val STATE_CONNECTED = "connected"
    const val STATE_FAILED = "failed"
    const val STATE_DISCONNECTED = "disconnected"

    @Volatile var state: String = STATE_IDLE
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var activeProfile: String? = null
        private set
    @Volatile var bytesIn: Long = 0
        private set
    @Volatile var bytesOut: Long = 0
        private set
    @Volatile var handshakeAgoMs: Long = -1

    /** v1.7.2: which engine owns the current tunnel (true = AmneziaWG). */
    @Volatile var usingAmnezia: Boolean = false
        private set

    private var wgBackend: com.wireguard.android.backend.GoBackend? = null
    private var awgBackend: org.amnezia.awg.backend.GoBackend? = null
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "zephyr-wg").apply { isDaemon = true }
    }
    private var watchdog: Thread? = null
    @Volatile private var connectedAt = 0L

    /** Localized parse/connect error. */
    class WgException(message: String) : Exception(message)

    // ------------------------------------------------------------------
    // Tunnel adapters (official + amnezia)
    // ------------------------------------------------------------------

    private val wgTunnel = object : com.wireguard.android.backend.Tunnel {
        override fun getName(): String = activeProfile ?: "ZephyrWireGuard"
        override fun onStateChange(newState: com.wireguard.android.backend.Tunnel.State) {
            when (newState) {
                com.wireguard.android.backend.Tunnel.State.UP -> state = STATE_CONNECTED
                com.wireguard.android.backend.Tunnel.State.DOWN ->
                    if (state != STATE_FAILED && state != STATE_IDLE) state = STATE_DISCONNECTED
                else -> {}
            }
        }
    }

    private val awgTunnel = object : org.amnezia.awg.backend.Tunnel {
        override fun getName(): String = activeProfile ?: "ZephyrAmnezia"
        override fun onStateChange(newState: org.amnezia.awg.backend.Tunnel.State) {
            when (newState) {
                org.amnezia.awg.backend.Tunnel.State.UP -> state = STATE_CONNECTED
                org.amnezia.awg.backend.Tunnel.State.DOWN ->
                    if (state != STATE_FAILED && state != STATE_IDLE) state = STATE_DISCONNECTED
                else -> {}
            }
        }
    }

    fun wgDir(context: Context): File = File(context.filesDir, "wireguard").apply { mkdirs() }

    // ------------------------------------------------------------------
    // tolerant parsers (vanilla + AmneziaWG)
    // ------------------------------------------------------------------

    /** AmneziaWG obfuscation keys — the signal to route to the awg engine. */
    private val AMNEZIA_KEYS = hashSetOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
        "ittl", "obfuscate", "obfshdr", "obf4data", "magic",
        "headerprotectionkey", "contentpaddingaddition",
        "rekeyaftertime", "rekeytimeout", "rejectaftertime", "keepalivetimeout")

    /** Valid WireGuard keys we accept but do not act on (client-side irrelevant). */
    private val IGNORED_KEYS = hashSetOf(
        "fwmark", "table", "preup", "postup", "predown", "postdown", "saveconfig",
        "resolvconf", "socks5proxy", "workerthreads")

    class TolerantParse(
        val config: com.wireguard.config.Config,
        val amnezia: Boolean,
        val skipped: Int
    )

    class AwgParse(val config: org.amnezia.awg.config.Config, val skipped: Int)

    class ValidateResult(val error: String?, val amnezia: Boolean, val skipped: Int)

    /**
     * Parse a .conf body tolerantly into the OFFICIAL library [Config].
     * Amnezia keys are counted (→ amnezia flag) but not stored here.
     */
    fun parseTolerant(context: Context, text: String, excludedApps: List<String> = emptyList()): TolerantParse {
        val app = context.applicationContext
        var section = 0            // 0 = none, 1 = [Interface], 2 = [Peer]
        val ib = com.wireguard.config.Interface.Builder()
        // v1.7.2: split tunneling — apps listed here BYPASS the tunnel
        if (excludedApps.isNotEmpty()) {
            try { ib.parseExcludedApplications(excludedApps.joinToString("\n")) } catch (_: Throwable) {}
        }
        val peerBuilders = mutableListOf<com.wireguard.config.Peer.Builder>()
        var hasPrivateKey = false
        var hasEndpoint = false
        var hasPeerKey = false
        var skipped = 0
        var amnezia = false

        text.lines().forEachIndexed { idx, raw ->
            var line = raw.trim()
            if (line.startsWith("﻿")) line = line.substring(1)   // BOM
            if (line.isEmpty()) return@forEachIndexed
            val lower = line.lowercase()
            if (lower == "[interface]") { section = 1; return@forEachIndexed }
            if (lower == "[peer]") { peerBuilders.add(com.wireguard.config.Peer.Builder()); section = 2; return@forEachIndexed }
            if (lower.startsWith("[") && lower.endsWith("]")) { skipped++; return@forEachIndexed }
            val eq = line.indexOf('=')
            if (eq < 1) { skipped++; return@forEachIndexed }
            val key = line.substring(0, eq).trim().lowercase()
            // strip trailing inline comment (base64/IPs/values never contain '#')
            val value = line.substring(eq + 1).trim().substringBefore('#').trim()
            if (value.isEmpty()) return@forEachIndexed
            try {
                if (section == 1) when (key) {
                    "privatekey" -> { ib.parsePrivateKey(value); hasPrivateKey = true }
                    "address" -> ib.parseAddresses(value)
                    "dns" -> ib.parseDnsServers(value)
                    "dnssearchdomains" -> value.split(',').map { it.trim() }
                        .filter { it.isNotEmpty() }.forEach { ib.addDnsSearchDomain(it) }
                    "mtu" -> ib.parseMtu(value)
                    "listenport" -> ib.parseListenPort(value)
                    in AMNEZIA_KEYS -> amnezia = true
                    in IGNORED_KEYS -> { /* accepted, not acted on */ }
                    else -> skipped++
                } else if (section == 2) {
                    val pb = peerBuilders.lastOrNull() ?: run { skipped++; return@forEachIndexed }
                    when (key) {
                        "publickey" -> { pb.parsePublicKey(value); hasPeerKey = true }
                        "allowedips" -> pb.parseAllowedIPs(value)
                        "endpoint" -> { pb.parseEndpoint(value); hasEndpoint = true }
                        "presharedkey" -> pb.parsePreSharedKey(value)
                        "persistentkeepalive" -> pb.parsePersistentKeepalive(value)
                        in AMNEZIA_KEYS -> amnezia = true
                        else -> skipped++
                    }
                } else skipped++
            } catch (t: Throwable) {
                throw WgException(app.getString(
                    R.string.vpn_wg_badline, idx + 1,
                    "$key = ${value.take(24)} — ${(t.message ?: "").take(60)}"))
            }
        }
        if (!hasPrivateKey) throw WgException(app.getString(R.string.vpn_wg_nokey))
        if (peerBuilders.isEmpty() || !hasPeerKey) throw WgException(app.getString(R.string.vpn_wg_nopeer))
        if (!hasEndpoint) throw WgException(app.getString(R.string.vpn_wg_noendpoint))
        val iface = try { ib.build() } catch (t: Throwable) {
            throw WgException(app.getString(R.string.vpn_wg_badiface, (t.message ?: "").take(80)))
        }
        val peers = try { peerBuilders.map { it.build() } } catch (t: Throwable) {
            throw WgException(app.getString(R.string.vpn_wg_badpeer, (t.message ?: "").take(80)))
        }
        val config = com.wireguard.config.Config.Builder().setInterface(iface)
            .apply { peers.forEach { addPeer(it) } }.build()
        return TolerantParse(config, amnezia, skipped)
    }

    /**
     * v1.7.2: tolerant parse into the AMNEZIA engine's [org.amnezia.awg.config.Config]
     * with ALL obfuscation parameters REALLY applied (Jc/Jmin/Jmax/S1..S4/H1..H4/
     * I1..I5/HeaderProtectionKey/…) — this is what makes obfuscated servers accept us.
     */
    fun parseTolerantAwg(context: Context, text: String, excludedApps: List<String> = emptyList()): AwgParse {
        val app = context.applicationContext
        var section = 0
        val ib = org.amnezia.awg.config.Interface.Builder()
        // v1.7.2: split tunneling — apps listed here BYPASS the tunnel
        if (excludedApps.isNotEmpty()) {
            try { ib.parseExcludedApplications(excludedApps.joinToString("\n")) } catch (_: Throwable) {}
        }
        val peerBuilders = mutableListOf<org.amnezia.awg.config.Peer.Builder>()
        var hasPrivateKey = false
        var hasEndpoint = false
        var hasPeerKey = false
        var skipped = 0

        text.lines().forEachIndexed { idx, raw ->
            var line = raw.trim()
            if (line.startsWith("﻿")) line = line.substring(1)   // BOM
            if (line.isEmpty()) return@forEachIndexed
            val lower = line.lowercase()
            if (lower == "[interface]") { section = 1; return@forEachIndexed }
            if (lower == "[peer]") { peerBuilders.add(org.amnezia.awg.config.Peer.Builder()); section = 2; return@forEachIndexed }
            if (lower.startsWith("[") && lower.endsWith("]")) { skipped++; return@forEachIndexed }
            val eq = line.indexOf('=')
            if (eq < 1) { skipped++; return@forEachIndexed }
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim().substringBefore('#').trim()
            if (value.isEmpty()) return@forEachIndexed
            try {
                if (section == 1) when (key) {
                    "privatekey" -> { ib.parsePrivateKey(value); hasPrivateKey = true }
                    "address" -> ib.parseAddresses(value)
                    "dns" -> ib.parseDnsServers(value)
                    "mtu" -> ib.parseMtu(value)
                    "listenport" -> ib.parseListenPort(value)
                    "excludedapplications" -> ib.parseExcludedApplications(value)
                    "includedapplications" -> ib.parseIncludedApplications(value)
                    // ---- AmneziaWG obfuscation (the whole point) ----
                    "jc" -> ib.parseJunkPacketCount(value)
                    "jmin" -> ib.parseJunkPacketMinSize(value)
                    "jmax" -> ib.parseJunkPacketMaxSize(value)
                    "s1" -> ib.parseInitPacketJunkSize(value)
                    "s2" -> ib.parseResponsePacketJunkSize(value)
                    "s3" -> ib.parseCookieReplyPacketJunkSize(value)
                    "s4" -> ib.parseTransportPacketJunkSize(value)
                    "h1" -> ib.parseInitPacketMagicHeader(value)
                    "h2" -> ib.parseResponsePacketMagicHeader(value)
                    "h3" -> ib.parseUnderloadPacketMagicHeader(value)
                    "h4" -> ib.parseTransportPacketMagicHeader(value)
                    "i1" -> ib.parseSpecialJunkI1(value)
                    "i2" -> ib.parseSpecialJunkI2(value)
                    "i3" -> ib.parseSpecialJunkI3(value)
                    "i4" -> ib.parseSpecialJunkI4(value)
                    "i5" -> ib.parseSpecialJunkI5(value)
                    "headerprotectionkey" -> ib.parseHeaderProtectionKey(value)
                    "contentpaddingaddition" -> ib.parseContentPaddingAddition(value)
                    "rekeyaftertime" -> ib.parseRekeyAfterTime(value)
                    "rekeytimeout" -> ib.parseRekeyTimeout(value)
                    "rejectaftertime" -> ib.parseRejectAfterTime(value)
                    "keepalivetimeout" -> ib.parseKeepaliveTimeout(value)
                    in IGNORED_KEYS -> { /* accepted, not acted on */ }
                    else -> skipped++
                } else if (section == 2) {
                    val pb = peerBuilders.lastOrNull() ?: run { skipped++; return@forEachIndexed }
                    when (key) {
                        "publickey" -> { pb.parsePublicKey(value); hasPeerKey = true }
                        "allowedips" -> pb.parseAllowedIPs(value)
                        "endpoint" -> { pb.parseEndpoint(value); hasEndpoint = true }
                        "presharedkey" -> pb.parsePreSharedKey(value)
                        "persistentkeepalive" -> pb.parsePersistentKeepalive(value)
                        else -> skipped++
                    }
                } else skipped++
            } catch (t: Throwable) {
                throw WgException(app.getString(
                    R.string.vpn_wg_badline, idx + 1,
                    "$key = ${value.take(24)} — ${(t.message ?: "").take(60)}"))
            }
        }
        if (!hasPrivateKey) throw WgException(app.getString(R.string.vpn_wg_nokey))
        if (peerBuilders.isEmpty() || !hasPeerKey) throw WgException(app.getString(R.string.vpn_wg_nopeer))
        if (!hasEndpoint) throw WgException(app.getString(R.string.vpn_wg_noendpoint))
        val iface = try { ib.build() } catch (t: Throwable) {
            throw WgException(app.getString(R.string.vpn_wg_badiface, (t.message ?: "").take(80)))
        }
        val peers = try { peerBuilders.map { it.build() } } catch (t: Throwable) {
            throw WgException(app.getString(R.string.vpn_wg_badpeer, (t.message ?: "").take(80)))
        }
        val config = org.amnezia.awg.config.Config.Builder().setInterface(iface)
            .apply { peers.forEach { addPeer(it) } }.build()
        return AwgParse(config, skipped)
    }

    /** Parse-validate a .conf without connecting; null error = usable. */
    fun validate(context: Context, file: File): ValidateResult = try {
        // v1.7.2: validate with the engine that will actually serve the config —
        // Amnezia configs must pass through the amnezia parser (params now applied).
        val text = file.readText()
        val amnezia = text.lines().any { l ->
            val t = l.trim().lowercase()
            val eq = t.indexOf('=')
            eq > 0 && AMNEZIA_KEYS.contains(t.substring(0, eq).trim())
        }
        if (amnezia) {
            val r = parseTolerantAwg(context, text)
            ValidateResult(null, true, r.skipped)
        } else {
            val r = parseTolerant(context, text)
            ValidateResult(null, r.amnezia, r.skipped)
        }
    } catch (t: Throwable) {
        ValidateResult(humanError(context, t), false, 0)
    }

    /** Human-readable localized error for config/bring-up problems. */
    fun humanError(context: Context, t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return when {
            msg.contains("VPN_NOT_AUTHORIZED", true) -> context.getString(R.string.vpn_err_perm)
            msg.contains("UNABLE_TO_START_VPN", true) -> context.getString(R.string.vpn_err_start)
            msg.contains("DNS_RESOLUTION", true) -> context.getString(R.string.vpn_wg_dns)
            else -> msg.take(200)
        }
    }

    // ------------------------------------------------------------------
    // connect / disconnect
    // ------------------------------------------------------------------

    /**
     * v1.7.2: apps whose traffic BYPASSES the tunnel (split tunneling) — read from
     * the same "vpn_ui" SharedPreferences the VPN screen writes to.
     */
    private fun excludedApps(ctx: Context): List<String> =
        ctx.getSharedPreferences("vpn_ui", android.content.Context.MODE_PRIVATE)
            .getStringSet("vpn_excluded_apps", emptySet())
            ?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * Bring the tunnel up. MUST be called from a background thread (blocks on
     * endpoint DNS resolution + wgTurnOn/awgTurnOn). Throws on failure after
     * setting [state]/[lastError].
     */
    fun connect(context: Context, confFile: File) {
        state = STATE_CONNECTING
        lastError = null
        bytesIn = 0; bytesOut = 0; handshakeAgoMs = -1
        connectedAt = 0
        try {
            // mutual exclusion: only one tunnel kind can hold the TUN device
            try { TunnelHoldService.stop(context) } catch (_: Throwable) {}
            try { DnsVpnService.stop(context) } catch (_: Throwable) {}
            Thread.sleep(150)   // let the old TUN close (establish() would fail otherwise)

            val text = confFile.readText()
            // v1.7.2: route to the Amnezia engine when obfuscation keys are present
            val isAmnezia = text.lines().any { l ->
                val t = l.trim().lowercase()
                val eq = t.indexOf('=')
                eq > 0 && AMNEZIA_KEYS.contains(t.substring(0, eq).trim())
            }
            usingAmnezia = isAmnezia

            // foreground hold: keeps THIS process (and the library's VpnService in it) alive
            TunnelHoldService.hold(context)

            // consent check: both engines throw if prepare() != null
            if (android.net.VpnService.prepare(context) != null) {
                throw IllegalStateException(context.getString(R.string.vpn_err_perm))
            }
            activeProfile = confFile.nameWithoutExtension

            if (isAmnezia) {
                val parsed = parseTolerantAwg(context, text, excludedApps(context))
                if (awgBackend == null) synchronized(this) {
                    if (awgBackend == null) {
                        awgBackend = org.amnezia.awg.backend.GoBackend(context.applicationContext)
                    }
                }
                val bk = awgBackend ?: throw IllegalStateException("awg backend init failed")
                bk.setState(awgTunnel, org.amnezia.awg.backend.Tunnel.State.UP, parsed.config)
            } else {
                val parsed = parseTolerant(context, text, excludedApps(context))
                if (wgBackend == null) synchronized(this) {
                    if (wgBackend == null) {
                        wgBackend = com.wireguard.android.backend.GoBackend(context.applicationContext)
                    }
                }
                val bk = wgBackend ?: throw IllegalStateException("backend init failed")
                bk.setState(wgTunnel, com.wireguard.android.backend.Tunnel.State.UP, parsed.config)
            }
            state = STATE_CONNECTED
            connectedAt = System.currentTimeMillis()
            startHandshakeWatchdog(context)
        } catch (t: Throwable) {
            state = STATE_FAILED
            lastError = humanError(context, t)
            try {
                if (usingAmnezia) awgBackend?.setState(awgTunnel, org.amnezia.awg.backend.Tunnel.State.DOWN, null)
                else wgBackend?.setState(wgTunnel, com.wireguard.android.backend.Tunnel.State.DOWN, null)
            } catch (_: Throwable) {}
            try { TunnelHoldService.stop(context) } catch (_: Throwable) {}
            throw t
        }
    }

    /** Async variant used by the UI (posts onto the private executor). */
    fun connectAsync(context: Context, confFile: File, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        exec.execute {
            try { connect(app, confFile) } catch (_: Throwable) {}
            onDone?.invoke()
        }
    }

    fun disconnect(context: Context) {
        watchdog?.interrupt()
        watchdog = null
        if (state == STATE_IDLE || state == STATE_DISCONNECTED) {
            try { TunnelHoldService.stop(context) } catch (_: Throwable) {}
            return
        }
        val awg = awgBackend
        val wg = wgBackend
        val amnezia = usingAmnezia
        exec.execute {
            try {
                if (amnezia && awg != null) {
                    awg.setState(awgTunnel, org.amnezia.awg.backend.Tunnel.State.DOWN, null)
                } else if (wg != null) {
                    wg.setState(wgTunnel, com.wireguard.android.backend.Tunnel.State.DOWN, null)
                }
            } catch (_: Throwable) {}
            // v1.7.2: make sure BOTH engines are down (engine switch leftovers)
            try { if (awg != null) awg.setState(awgTunnel, org.amnezia.awg.backend.Tunnel.State.DOWN, null) } catch (_: Throwable) {}
            try { if (wg != null) wg.setState(wgTunnel, com.wireguard.android.backend.Tunnel.State.DOWN, null) } catch (_: Throwable) {}
            state = STATE_DISCONNECTED
            try { TunnelHoldService.stop(context) } catch (_: Throwable) {}
        }
    }

    /**
     * v1.7.1: GoBackend marks the tunnel UP the moment the interface exists — a
     * dead/unreachable endpoint then black-holes ALL device traffic forever with
     * the UI happily showing "connected". If no peer handshake is seen within
     * 40 s we tear the tunnel down (internet restored) and surface a clear error.
     */
    private fun startHandshakeWatchdog(context: Context) {
        watchdog?.interrupt()
        val t = Thread {
            val start = System.currentTimeMillis()
            try {
                while (System.currentTimeMillis() - start < 40_000) {
                    Thread.sleep(3_000)
                    if (state != STATE_CONNECTED) return@Thread
                    pollStats()
                    if (handshakeAgoMs > 0) return@Thread   // handshake seen → healthy
                }
                if (state == STATE_CONNECTED) {
                    lastError = context.getString(R.string.vpn_wg_no_handshake)
                    state = STATE_FAILED
                    try {
                        if (usingAmnezia) awgBackend?.setState(awgTunnel, org.amnezia.awg.backend.Tunnel.State.DOWN, null)
                        else wgBackend?.setState(wgTunnel, com.wireguard.android.backend.Tunnel.State.DOWN, null)
                    } catch (_: Throwable) {}
                    try { TunnelHoldService.stop(context) } catch (_: Throwable) {}
                }
            } catch (_: InterruptedException) {}
        }
        t.isDaemon = true
        watchdog = t
        t.start()
    }

    /** Poll traffic stats for the UI (cheap; called from the status poller). */
    fun pollStats() {
        if (state != STATE_CONNECTED) return
        try {
            if (usingAmnezia) {
                val bk = awgBackend ?: return
                val st = bk.getStatistics(awgTunnel)
                bytesIn = st.totalRx()
                bytesOut = st.totalTx()
                val peers = st.peers()
                if (peers.isNotEmpty()) {
                    val hs = st.peer(peers.iterator().next())?.latestHandshakeEpochMillis() ?: -1L
                    handshakeAgoMs = if (hs > 0) System.currentTimeMillis() - hs else -1
                }
            } else {
                val bk = wgBackend ?: return
                val st = bk.getStatistics(wgTunnel)
                bytesIn = st.totalRx()
                bytesOut = st.totalTx()
                val peers = st.peers()
                if (peers.isNotEmpty()) {
                    val hs = st.peer(peers[0])?.latestHandshakeEpochMillis() ?: -1L
                    handshakeAgoMs = if (hs > 0) System.currentTimeMillis() - hs else -1
                }
            }
        } catch (_: Throwable) {}
    }

    fun isActive(): Boolean = state == STATE_CONNECTED || state == STATE_CONNECTING

    /** WireGuard configs stored by the import flow, sorted by name (stable list). */
    fun listConfigs(context: Context): List<File> =
        wgDir(context).listFiles { f -> f.isFile && f.name.endsWith(".conf", true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Copy a SAF uri into the wireguard dir with a safe name; validates content. */
    fun importConfig(context: Context, uri: android.net.Uri, displayName: String): File {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._ ()\\-\\[\\]]"), "_")
            .substringBeforeLast('.') + ".conf"
        var target = File(wgDir(context), safe)
        var n = 1
        while (target.exists()) {
            target = File(wgDir(context), safe.substringBeforeLast('.') + " ($n).conf"); n++
        }
        val text = context.contentResolver.openInputStream(uri)?.use { ins ->
            ins.readBytes().toString(Charsets.UTF_8)
        } ?: throw java.io.IOException("cannot read")
        if (!text.contains("[Interface]", ignoreCase = true) &&
            !text.contains("[Peer]", ignoreCase = true)) {
            throw WgException(context.getString(R.string.vpn_wg_notwg))
        }
        target.writeText(text)
        return target
    }
}
