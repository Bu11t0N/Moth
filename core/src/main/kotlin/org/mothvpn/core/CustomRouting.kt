package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/** A user routing rule: [type] is one of [CustomRouting.types], [action] one of [CustomRouting.actions]. */
data class RoutingRule(val type: String, val value: String, val action: String) {
    fun json(): JSONObject = JSONObject().put("type", type).put("value", value).put("action", action)
    companion object { fun from(j: JSONObject) = RoutingRule(j.optString("type"), j.optString("value"), j.optString("action")) }
}

/** User routing rules, applied in order before the built-in rules ("Russian sites directly"). */
object CustomRouting {
    const val DOMAIN = "domain"; const val SUFFIX = "domain_suffix"; const val KEYWORD = "domain_keyword"; const val CIDR = "ip_cidr"
    const val PROXY = "proxy"; const val DIRECT = "direct"; const val BLOCK = "block"
    val types = listOf(DOMAIN, SUFFIX, KEYWORD, CIDR)
    val actions = listOf(PROXY, DIRECT, BLOCK)
    const val MAX_RULES = 500
    private val label = "[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?"
    private val hostPattern = Regex("$label(\\.$label)*")
    private val keywordPattern = Regex("[a-z0-9._-]{1,64}")
    private val ipv4 = Regex("(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}")
    private val ipv6Chars = Regex("[0-9a-f:.]{2,45}")

    /** Canonical form of [raw] for [type] (lower case, punycode, "/32" added to a bare address), or null when it is invalid. */
    fun normalize(type: String, raw: String): String? {
        val text = raw.trim().lowercase()
        if (text.isEmpty() || text.length > 253) return null
        return when (type) {
            DOMAIN, SUFFIX -> {
                val bare = if (type == SUFFIX) text.removePrefix("*.").removePrefix(".") else text
                val ascii = runCatching { java.net.IDN.toASCII(bare).lowercase() }.getOrNull() ?: return null
                ascii.takeIf { it.length <= 253 && hostPattern.matches(it) }
            }
            KEYWORD -> text.takeIf { keywordPattern.matches(it) }
            CIDR -> cidr(text)
            else -> null
        }
    }
    private fun cidr(text: String): String? {
        val parts = text.split('/')
        if (parts.size > 2) return null
        val address = parts[0]
        val v6 = ':' in address
        val max = if (v6) 128 else 32
        val prefix = if (parts.size == 2) parts[1].takeIf { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) }?.toInt()?.takeIf { it in 0..max } ?: return null else max
        if (v6) {
            // Only hex digits, ':' and '.' reach the parser, so this never triggers a DNS lookup.
            if (!ipv6Chars.matches(address)) return null
            runCatching { java.net.InetAddress.getByName(address) as java.net.Inet6Address }.getOrNull() ?: return null
        } else if (!ipv4.matches(address)) return null
        return "$address/$prefix"
    }
    fun valid(rule: RoutingRule): Boolean = rule.action in actions && rule.type in types && normalize(rule.type, rule.value) == rule.value
    fun valid(rules: List<RoutingRule>): Boolean = rules.size <= MAX_RULES && rules.all { valid(it) }
    /** Moves the rule at [from] to [to] (both clamped); used by the up/down buttons. */
    fun move(rules: List<RoutingRule>, from: Int, to: Int): List<RoutingRule> {
        if (from !in rules.indices) return rules
        val list = rules.toMutableList(); val r = list.removeAt(from); list.add(to.coerceIn(0, list.size), r); return list
    }
    /** Domain rules only match when the destination name is known: the cores must sniff it from the traffic. */
    fun needsSniff(rules: List<RoutingRule>) = rules.any { it.type != CIDR }

    /** sing-box route rules, one per user rule, in order. "proxy" and "direct" are the outbound tags of the tunnel config. */
    fun singBox(rules: List<RoutingRule>): List<JSONObject> = rules.map { r ->
        val o = JSONObject().put(r.type, JSONArray().put(r.value))
        when (r.action) { BLOCK -> o.put("action", "reject"); DIRECT -> o.put("outbound", "direct"); else -> o.put("outbound", "proxy") }
    }
    /** Xray routing rules. [proxy] sets where "proxy" goes (an outbound or the balancer); "direct" and "block" are the tags added by the config. */
    fun xray(rules: List<RoutingRule>, proxy: (JSONObject) -> JSONObject): List<JSONObject> = rules.map { r ->
        val o = JSONObject().put("type", "field")
        when (r.type) {
            DOMAIN -> o.put("domain", JSONArray().put("full:${r.value}"))
            SUFFIX -> o.put("domain", JSONArray().put("domain:${r.value}"))
            KEYWORD -> o.put("domain", JSONArray().put(r.value)) // a plain string is a substring match in Xray
            else -> o.put("ip", JSONArray().put(r.value))
        }
        when (r.action) { BLOCK -> o.put("outboundTag", "block"); DIRECT -> o.put("outboundTag", "direct"); else -> proxy(o) }
    }
}
