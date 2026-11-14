package com.zephyr.browser.proxy

import com.zephyr.browser.R

/**
 * v1.7.0: built-in DNS provider catalog.
 *
 * Every entry carries the resolver IPs (used to route queries into the DNS-only
 * tunnel AND to bootstrap encrypted upstreams — we always dial the IP directly
 * and set the TLS SNI to the hostname, the Intra/RethinkDNS bootstrap pattern),
 * plus the DoH path for providers that expose one.
 */
object DnsCatalog {

    data class Provider(
        val id: Int,
        val name: String,              // brand name (locale-independent)
        val primary: String?,          // null for the "system" pseudo-provider
        val secondary: String?,
        val dotHost: String?,          // DoT SNI hostname (served on :853 at the IPs)
        val dohHost: String?,          // DoH authority (host header + SNI, :443 at the IPs)
        val dohPath: String?,          // DoH endpoint path
        val descRes: Int,              // localized policy note
        val blocksAds: Boolean = false,
        val blocksMalware: Boolean = false
    )

    val SYSTEM = Provider(0, "Системный", null, null, null, null, null, R.string.dns_desc_system)

    val ALL = listOf(
        SYSTEM,
        Provider(1, "Cloudflare 1.1.1.1", "1.1.1.1", "1.0.0.1",
            "cloudflare-dns.com", "cloudflare-dns.com", "/dns-query", R.string.dns_desc_cloudflare),
        Provider(2, "Cloudflare Family", "1.1.1.3", "1.0.0.3",
            "family.cloudflare-dns.com", "family.cloudflare-dns.com", "/dns-query",
            R.string.dns_desc_cf_family, blocksMalware = true),
        Provider(3, "Google Public DNS", "8.8.8.8", "8.8.4.4",
            "dns.google", "dns.google", "/dns-query", R.string.dns_desc_google),
        Provider(4, "Quad9", "9.9.9.9", "149.112.112.112",
            "dns.quad9.net", "dns.quad9.net", "/dns-query", R.string.dns_desc_quad9, blocksMalware = true),
        Provider(5, "AdGuard DNS", "94.140.14.14", "94.140.15.15",
            "dns.adguard-dns.com", "dns.adguard-dns.com", "/dns-query",
            R.string.dns_desc_adguard, blocksAds = true),
        Provider(6, "AdGuard Family", "94.140.14.15", "94.140.15.16",
            "family.adguard-dns.com", "family.adguard-dns.com", "/dns-query",
            R.string.dns_desc_adguard_family, blocksAds = true, blocksMalware = true),
        Provider(7, "OpenDNS", "208.67.222.222", "208.67.220.220",
            "dns.opendns.com", "doh.opendns.com", "/dns-query", R.string.dns_desc_opendns),
        Provider(8, "Comodo Secure", "8.26.56.26", "8.20.247.20",
            "dns.comodo.com", null, null, R.string.dns_desc_comodo),
        Provider(9, "Yandex DNS", "77.88.8.8", "77.88.21.8",
            "common.dot.dns.yandex.net", "common.dot.dns.yandex.net", "/dns-query", R.string.dns_desc_yandex),
        Provider(10, "Mullvad DNS", "194.242.2.2", "193.19.108.2",
            "dns.mullvad.net", "dns.mullvad.net", "/dns-query", R.string.dns_desc_mullvad)
    )

    const val CUSTOM_ID = 11

    fun byId(id: Int): Provider? = ALL.firstOrNull { it.id == id }

    /**
     * Well-known public resolvers routed into the DNS-only tunnel when leak
     * protection is on — apps that hardcode a resolver (8.8.8.8 is common)
     * get intercepted and answered via the user-selected upstream instead of
     * silently bypassing the tunnel.
     */
    val LEAK_CATCH_IPS = listOf(
        "8.8.8.8", "8.8.4.4",                    // Google
        "1.1.1.1", "1.0.0.1",                    // Cloudflare
        "9.9.9.9", "149.112.112.112",            // Quad9
        "208.67.222.222", "208.67.220.220",      // OpenDNS
        "94.140.14.14", "94.140.15.15",          // AdGuard
        "77.88.8.8", "77.88.21.8",               // Yandex
        "8.26.56.26", "8.20.247.20",             // Comodo
        "185.228.168.9", "185.228.169.9",        // CleanBrowsing
        "64.6.64.6", "64.6.65.6",                // Verisign/Norton
        "216.146.35.35", "216.146.36.36",        // Norton ConnectSafe
        "114.114.114.114", "223.5.5.5", "223.6.6.6",  // CN public
        "45.90.28.0", "45.90.30.0"               // NextDNS-ish ranges
    )

    /** Known public IPv6 resolvers — routed into the tunnel and blackholed (v4 upstream keeps working). */
    val LEAK_CATCH_V6 = listOf(
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2620:fe::fe", "2620:fe::9",
        "2001:db8:53::", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"
    )
}
