package org.mothvpn.core

import java.net.URI

/** Per-app split tunneling: which packages go to VpnService.Builder.addAllowedApplication / addDisallowedApplication. */
object SplitTunnel {
    /** "bypass" first (the default); with nothing selected both modes put every app in the VPN. "off" existed until 0.7.3 (see [migrateMode]). */
    val modes = listOf("bypass", "only")
    /** A saved mode from any version: 0.7.3 and earlier had "off" (same as an empty list), it and unknown values become "bypass". */
    fun migrateMode(saved: String): String = saved.takeIf { it in modes } ?: "bypass"
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    fun validPackage(name: String) = name.length <= 255 && packageName.matches(name)
    data class Plan(val allowed: List<String>, val disallowed: List<String>)
    /**
     * "only": the selected apps plus Moth itself (its health checks run through the VPN); with nothing selected the VPN applies to all apps.
     * "bypass": the selected apps except Moth; with nothing selected the VPN applies to all apps. Moth itself always stays in the VPN, exactly as before; the cores protect their own sockets.
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
    val intervals = listOf(0, 1, 2, 3, 6, 12, 24)
    /** Maps any saved value to a supported interval (nearest not-shorter one; 0 stays off). */
    fun normalize(hours: Int): Int = if (hours <= 0) 0 else intervals.firstOrNull { it >= hours } ?: 24
    /** Whether a subscription saved at [updatedAt] is due at [now] (with 10 % slack for WorkManager timing). */
    fun due(hours: Int, updatedAt: Long, now: Long): Boolean = hours > 0 && now - updatedAt >= hours * 3_600_000L * 9 / 10
}

/** DNS server choices for the tunnel. All of them are reached through the VPN server. */
object DnsServers {
    /** [label]: provider and the server address the DoH URL points to, shown in the DNS dialog. */
    data class Preset(val id: String, val address: String, val label: String)
    val presets = listOf(
        Preset("cloudflare", "https://1.1.1.1/dns-query", "Cloudflare (1.1.1.1)"),
        Preset("google", "https://8.8.8.8/dns-query", "Google (8.8.8.8)"),
        Preset("quad9", "https://9.9.9.9/dns-query", "Quad9 (9.9.9.9)"),
        Preset("adguard", "https://94.140.14.14/dns-query", "AdGuard (94.140.14.14)"),
    )
    const val DEFAULT = "https://1.1.1.1/dns-query"
    /** The removed "plain Cloudflare" preset (classic DNS to 1.1.1.1) becomes the Cloudflare DoH default. */
    fun migrate(address: String): String = if (address == "1.1.1.1") DEFAULT else address
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
    /** Probe results younger than this are reused by auto-select instead of measuring every server again. */
    const val freshMs = 5 * 60_000L
    /**
     * Ranking from recent measurements, or null when they are stale: every server must have a finished result
     * (success or failure) newer than [maxAgeMs] on the current network and at least one must have answered.
     * [results]: id to (latency or null for a failure, checkedAt).
     */
    fun recentRanking(order: List<String>, results: Map<String, Pair<Long?, Long>>, now: Long, maxAgeMs: Long = freshMs): List<String>? {
        if (order.isEmpty() || order.any { id -> results[id]?.let { now - it.second in 0..maxAgeMs } != true }) return null
        return ranking(order, order.associateWith { results[it]?.first }).takeIf { it.isNotEmpty() }
    }
}

/** Favorite servers: always shown first, in the current sort order; persisted by the server's stable id. */
object Favorites {
    private val idPattern = Regex("[0-9a-f]{16,64}")
    fun valid(ids: List<String>) = ids.size <= 5000 && ids.all { idPattern.matches(it) } && ids.distinct().size == ids.size
    fun toggle(ids: List<String>, id: String): List<String> = if (id in ids) ids - id else ids + id
    fun <T> first(items: List<T>, favorites: Collection<String>, id: (T) -> String): List<T> {
        if (favorites.isEmpty()) return items
        val set = favorites.toHashSet()
        val (fav, rest) = items.partition { id(it) in set }
        return fav + rest
    }
}

/** Data left and expiry from the provider's subscription-userinfo header. */
object SubscriptionQuota {
    /** Bytes left, or null when the provider gave no limit. */
    fun remaining(used: Long, total: Long): Long? = if (total <= 0) null else (total - used).coerceAtLeast(0)
    /** Expiry in epoch seconds when it is a real date (0 means "no expiry"). */
    fun expiry(expires: Long): Long? = expires.takeIf { it in 1..253402300799L }
    /** Parses "upload=1; download=2; total=3; expire=4". Unknown or malformed parts are ignored. */
    fun parse(header: String): Map<String, Long> = header.split(';').mapNotNull {
        val pair = it.trim().split('=', limit = 2)
        if (pair.size == 2) pair[1].trim().toLongOrNull()?.coerceAtLeast(0)?.let { n -> pair[0].trim().lowercase() to n } else null
    }.toMap()
}

/** What a pasted or scanned text is. */
object ImportInput {
    sealed class Kind { data class Subscription(val url: String) : Kind(); data class Links(val text: String) : Kind(); object Invalid : Kind() }
    private val schemes = setOf("vless", "vmess", "trojan", "ss", "hy2", "hysteria2", "hysteria", "tuic", "socks", "socks5", "socks5h", "wireguard", "wg", "ssh", "anytls")
    /** An http(s) line is a proxy server (not a subscription) when it is plain http or carries credentials: https://user:pass@host:port. */
    fun proxyLink(line: String): Boolean {
        val scheme = line.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        val u = runCatching { java.net.URI(line.trim()) }.getOrNull() ?: return false
        // A proxy has no path; web pages and subscription URLs do.
        if (u.host == null || u.rawPath.orEmpty() !in setOf("", "/")) return false
        return u.rawUserInfo != null || (scheme == "http" && u.port != -1)
    }
    fun classify(raw: String): Kind {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty() || text.length > 2 * 1024 * 1024) return Kind.Invalid
        val lines = text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.size == 1 && lines[0].startsWith("https://", ignoreCase = true) && !proxyLink(lines[0])) return Kind.Subscription(lines[0])
        if (lines.isNotEmpty() && lines.all { it.substringBefore("://", "").lowercase() in schemes || proxyLink(it) }) return Kind.Links(lines.joinToString("\n"))
        return Kind.Invalid
    }
    /** Local servers: new ones are added after the existing ones, duplicates (same id) are skipped. */
    fun merge(old: List<Profile>, added: List<Profile>): List<Profile> = (old + added).distinctBy { it.id }
}
