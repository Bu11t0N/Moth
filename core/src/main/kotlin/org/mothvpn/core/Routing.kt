package org.mothvpn.core

import java.net.URI

/** Per-app split tunneling: which packages go to VpnService.Builder.addAllowedApplication / addDisallowedApplication. */
object SplitTunnel {
    val modes = listOf("off", "only", "bypass")
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    fun validPackage(name: String) = name.length <= 255 && packageName.matches(name)
    data class Plan(val allowed: List<String>, val disallowed: List<String>)
    /**
     * "only": the selected apps plus Moth itself (its health checks run through the VPN); with nothing selected the VPN applies to all apps.
     * "bypass": the selected apps except Moth. Moth itself always stays in the VPN, exactly as before; the cores protect their own sockets.
     */
    fun plan(mode: String, apps: List<String>, self: String): Plan {
        val chosen = apps.filter { it != self && validPackage(it) }.distinct().sorted()
        return when {
            mode == "only" && chosen.isNotEmpty() -> Plan(chosen + self, emptyList())
            mode == "bypass" -> Plan(emptyList(), chosen)
            else -> Plan(emptyList(), emptyList())
        }
    }
}

/** Subscription auto-update interval choices (hours, 0 = off). */
object SubscriptionSchedule {
    val intervals = listOf(0, 6, 12, 24)
    /** Whether a subscription saved at [updatedAt] is due at [now] (with 10 % slack for WorkManager timing). */
    fun due(hours: Int, updatedAt: Long, now: Long): Boolean = hours > 0 && now - updatedAt >= hours * 3_600_000L * 9 / 10
}

/** DNS server choices for the tunnel. All of them are reached through the VPN server. */
object DnsServers {
    data class Preset(val id: String, val address: String)
    val presets = listOf(
        Preset("cloudflare", "https://1.1.1.1/dns-query"),
        Preset("google", "https://8.8.8.8/dns-query"),
        Preset("quad9", "https://9.9.9.9/dns-query"),
        Preset("adguard", "https://94.140.14.14/dns-query"),
        Preset("cloudflare-udp", "1.1.1.1"),
    )
    fun presetOf(address: String) = presets.firstOrNull { it.address == address }
    private val ipv4 = Regex("(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])){3}")
    /** "https://host/path" (DNS over HTTPS), "tls://host" (DNS over TLS) or a plain IPv4 address (classic DNS, still sent through the tunnel). */
    fun valid(address: String): Boolean {
        val a = address.trim()
        if (a != address || a.isEmpty() || a.length > 300) return false
        if (ipv4.matches(a)) return true
        val u = runCatching { URI(a) }.getOrNull() ?: return false
        return when (u.scheme) {
            "https" -> !u.host.isNullOrBlank() && u.rawUserInfo == null && u.fragment == null
            "tls" -> !u.host.isNullOrBlank() && u.rawUserInfo == null && u.rawPath.isNullOrEmpty() && u.rawQuery == null
            else -> false
        }
    }
}

/** "Russian sites directly": rule sets from SagerNet (cached on the device) plus a built-in domain rule. */
object DirectRoutes {
    val suffixes = listOf("ru", "su", "xn--p1ai")
    val ruleSets = mapOf(
        "geoip-ru" to "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs",
        "geosite-category-ru" to "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs",
    )
    /** Rule sets are refreshed weekly; an older copy is still used when the download fails. */
    const val maxAgeMs = 7 * 24 * 3_600_000L
    fun stale(modified: Long, now: Long) = modified <= 0 || now - modified > maxAgeMs
}

/** Auto-select and failover ordering based on measured latencies. */
object ServerPicker {
    /** Ids with a successful measurement, fastest first; ties keep the list order. */
    fun ranking(order: List<String>, latency: Map<String, Long?>): List<String> =
        order.withIndex().filter { latency[it.value] != null }.sortedWith(compareBy({ latency[it.value]!! }, { it.index })).map { it.value }
    /** Next server to try after [current] failed: the best one not tried yet, or null when everything was tried. */
    fun next(ranking: List<String>, current: String, tried: Set<String>): String? = ranking.firstOrNull { it != current && it !in tried }
    /** Consecutive failed health checks before switching servers. */
    const val failoverAfter = 3
}

/** What a pasted or scanned text is. */
object ImportInput {
    sealed class Kind { data class Subscription(val url: String) : Kind(); data class Links(val text: String) : Kind(); object Invalid : Kind() }
    private val schemes = setOf("vless", "vmess", "trojan", "ss", "hy2", "hysteria2", "hysteria", "tuic")
    fun classify(raw: String): Kind {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty() || text.length > 2 * 1024 * 1024) return Kind.Invalid
        val lines = text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.size == 1 && lines[0].startsWith("https://", ignoreCase = true)) return Kind.Subscription(lines[0])
        if (lines.isNotEmpty() && lines.all { it.substringBefore("://", "").lowercase() in schemes }) return Kind.Links(lines.joinToString("\n"))
        return Kind.Invalid
    }
    /** Local servers: new ones are added after the existing ones, duplicates (same id) are skipped. */
    fun merge(old: List<Profile>, added: List<Profile>): List<Profile> = (old + added).distinctBy { it.id }
}

object SpeedTest {
    const val bytes = 10_000_000
    const val url = "https://speed.cloudflare.com/__down?bytes=$bytes"
    fun mbps(bytes: Long, millis: Long): Double = if (millis <= 0) 0.0 else bytes * 8.0 / 1000.0 / millis
}
