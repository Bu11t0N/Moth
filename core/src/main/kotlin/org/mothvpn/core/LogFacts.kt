package org.mothvpn.core

import java.net.URI
import java.security.MessageDigest

/** Redaction-safe facts for the developer log (no Android dependencies). */
object LogFacts {
    /** Settings that are safe to show with values; the rest (addresses, identifiers) are reported as "changed" only. */
    fun settingsChanges(old: ClientSettings, new: ClientSettings): List<String> {
        val out = mutableListOf<String>()
        fun plain(name: String, a: Any, b: Any) { if (a != b) out += "$name=$a->$b" }
        fun hidden(name: String, a: String, b: String) { if (a != b) out += "$name changed" }
        plain("theme", old.theme, new.theme); plain("mtu", old.mtu, new.mtu); plain("ipv6", old.ipv6, new.ipv6)
        hidden("dns", old.dns, new.dns); hidden("probeUrl", old.probeUrl, new.probeUrl); hidden("bootstrapDns", old.bootstrapDns, new.bootstrapDns)
        plain("timeoutMs", old.timeoutMs, new.timeoutMs); plain("healthEnabled", old.healthEnabled, new.healthEnabled)
        plain("healthIntervalSeconds", old.healthIntervalSeconds, new.healthIntervalSeconds)
        plain("hwidEnabled", old.hwidEnabled, new.hwidEnabled); hidden("hwid", old.hwid, new.hwid)
        plain("deviceInfo", old.deviceInfo, new.deviceInfo); hidden("userAgent", old.userAgent, new.userAgent)
        plain("serverSort", old.serverSort, new.serverSort); plain("compactServers", old.compactServers, new.compactServers)
        plain("hideUnavailable", old.hideUnavailable, new.hideUnavailable); plain("probeMode", old.probeMode, new.probeMode)
        plain("probeParallel", old.probeParallel, new.probeParallel); plain("probeTimeoutMs", old.probeTimeoutMs, new.probeTimeoutMs)
        plain("updateChannel", old.updateChannel.ifEmpty { "auto" }, new.updateChannel.ifEmpty { "auto" })
        plain("splitMode", old.splitMode, new.splitMode); plain("splitApps", old.splitApps.size, new.splitApps.size)
        plain("directRu", old.directRu, new.directRu); plain("subUpdateHours", old.subUpdateHours, new.subUpdateHours)
        plain("autoSelect", old.autoSelect, new.autoSelect); plain("bootConnect", old.bootConnect, new.bootConnect)
        plain("dnsLeakProtection", old.dnsLeakProtection, new.dnsLeakProtection)
        plain("killSwitch", old.killSwitch, new.killSwitch); plain("favorites", old.favorites.size, new.favorites.size)
        plain("expiryWarning", old.expiryWarning, new.expiryWarning)
        plain("routingRules", old.routingRules.size, new.routingRules.size)
        plain("trafficWarning", old.trafficWarning, new.trafficWarning)
        plain("fragment", old.fragment, new.fragment); plain("noise", old.noise, new.noise); plain("mux", old.mux, new.mux)
        plain("editServers", old.editServers, new.editServers); plain("newDesign", old.newDesign, new.newDesign); plain("amoled", old.amoled, new.amoled)
        // Keywords can be provider or location names: only the count is logged.
        plain("serverFilter", old.serverFilter.size, new.serverFilter.size); plain("serverFilterMode", old.serverFilterMode, new.serverFilterMode)
        return out
    }

    /** The log redactor hides host names, so a provider is identified by a short hash of its host: same provider, same tag. */
    fun hostTag(url: String): String {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()
        if (host.isNullOrBlank()) return "none"
        return MessageDigest.getInstance("SHA-256").digest(host.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    }

    /** Short, non-reversible description of a server: protocol and the first 8 characters of its id. */
    fun profileTag(p: Profile) = "${p.protocol.take(24)}#${p.id.take(8)}"
}
