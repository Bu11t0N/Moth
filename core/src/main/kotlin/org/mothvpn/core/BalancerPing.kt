package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ping of a balancer (an Xray JSON group profile, outbound {"group":true,"outbounds":[...]}). Probing the group itself measures
 * only the member Xray happens to pick (usually the fallback), so instead its ping is the best result among its members that
 * are also listed as plain servers and were measured on the same page. Only when no member is listed or measured is the group
 * probed itself. Matching: the member outbound equal to a server's outbound apart from the tag (both come from the same
 * converter), else the same protocol + address + port + user id / password (servers imported from links or other formats).
 * Identities stay in memory; they are never logged.
 */
object BalancerPing {
    fun isGroup(p: Profile): Boolean = runCatching { JSONObject(p.outbound).optBoolean("group") }.getOrDefault(false)

    /** Key-order independent text of [o] without its tag (Android's org.json has no JSONObject.similar). */
    private fun withoutTag(o: JSONObject): String = canonical(JSONObject(o.toString()).apply { remove("tag") })
    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonical(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        is Number -> v.toDouble().let { d -> if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString() }
        null, JSONObject.NULL -> "null"
        else -> v.toString()
    }

    /** protocol|address|port|secret, or null when the outbound has no single server and user. */
    fun identity(o: JSONObject): String? {
        if (o.has("protocol")) { // Xray
            val protocol = o.optString("protocol").lowercase()
            val s = o.optJSONObject("settings") ?: return null
            val node = (s.optJSONArray("vnext") ?: s.optJSONArray("servers"))?.optJSONObject(0) ?: s
            val address = node.optString("address").removeSurrounding("[", "]").lowercase().ifBlank { return null }
            val port = node.optInt("port", -1).takeIf { it > 0 } ?: return null
            val user = node.optJSONArray("users")?.optJSONObject(0)
            val secret = user?.optString("id")?.ifBlank { null } ?: node.optString("password").ifBlank { null } ?: user?.optString("user").orEmpty()
            return "$protocol|$address|$port|$secret"
        }
        val type = o.optString("type").lowercase().ifBlank { return null }
        val address = o.optString("server").removeSurrounding("[", "]").lowercase().ifBlank { return null }
        val port = o.optInt("server_port", -1).takeIf { it > 0 } ?: return null
        val secret = o.optString("uuid").ifBlank { null } ?: o.optString("password").ifBlank { null } ?: o.optString("username")
        return "$type|$address|$port|$secret"
    }

    /** Ids of the plain servers in [profiles] that are members of [group] (in member order, without duplicates). */
    fun members(group: Profile, profiles: List<Profile>): List<String> {
        val outbounds: JSONArray = runCatching { JSONObject(group.outbound).optJSONArray("outbounds") }.getOrNull() ?: return emptyList()
        val plain = profiles.filter { it.id != group.id && !isGroup(it) }
            .mapNotNull { p -> runCatching { JSONObject(p.outbound) }.getOrNull()?.let { p.id to it } }
        val ids = LinkedHashSet<String>()
        for (i in 0 until outbounds.length()) {
            val m = outbounds.optJSONObject(i) ?: continue
            val bare = withoutTag(m)
            val exact = plain.firstOrNull { (_, o) -> withoutTag(o) == bare }?.first
            val key = identity(m)
            (exact ?: key?.let { k -> plain.firstOrNull { (_, o) -> identity(o) == k }?.first })?.let(ids::add)
        }
        return ids.toList()
    }

    /** A member's last finished result on the page: a latency, or failed. */
    data class Member(val latencyMs: Long?, val failed: Boolean)

    sealed class Derived {
        data class Value(val latencyMs: Long, val memberId: String) : Derived()
        /** Every matched member was measured and failed. */
        data class Failed(val memberId: String) : Derived()
        /** Not enough member results: probe the group itself. */
        object Unknown : Derived()
    }

    /** [results]: member id → its result (missing = never measured on this page). */
    fun derive(members: List<String>, results: Map<String, Member>): Derived {
        if (members.isEmpty()) return Derived.Unknown
        members.mapNotNull { id -> results[id]?.latencyMs?.let { id to it } }.minByOrNull { it.second }?.let { return Derived.Value(it.second, it.first) }
        return if (members.all { results[it]?.failed == true }) Derived.Failed(members.first()) else Derived.Unknown
    }

    /**
     * What a check runs. [targets]: the servers the check covers (already filtered). Groups with listed members are not probed:
     * a check of all servers relies on the members' results (members in the check are measured by it), and a check of one group
     * ([single]) measures its members instead. Groups without members are probed themselves.
     */
    data class Plan(val probe: List<Profile>, val derived: Map<String, List<String>>)

    fun plan(targets: List<Profile>, all: List<Profile>, single: Boolean): Plan {
        val probe = mutableListOf<Profile>()
        val derived = LinkedHashMap<String, List<String>>()
        val byId = all.associateBy { it.id }
        for (p in targets) {
            if (!isGroup(p)) { probe += p; continue }
            val members = members(p, all)
            if (members.isEmpty()) { probe += p; continue }
            derived[p.id] = members
            if (single) members.mapNotNull(byId::get).forEach { m -> if (probe.none { it.id == m.id }) probe += m }
        }
        return Plan(probe.distinctBy { it.id }, derived)
    }
}
