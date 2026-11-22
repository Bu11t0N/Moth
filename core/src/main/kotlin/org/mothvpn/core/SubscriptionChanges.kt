package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Change history of a subscription (developer option "Subscription changes"): for every update that changed provider content
 * (the moment shown as "Changed"), which servers were added, changed and removed, and for a changed server exactly which fields.
 * A server keeps its identity across an update by its id (same link) or, when the link changed, by the same name.
 * Secret fields (keys, passwords, UUIDs) are only marked as changed; their values are never stored. Bounded to [MAX_ENTRIES]
 * per subscription. Kept in the app's encrypted storage and shown on screen only, never written to the log.
 */
object SubscriptionChanges {
    const val MAX_ENTRIES = 20
    const val MAX_SERVERS = 500
    const val MAX_FIELDS = 60
    private const val MAX_VALUE = 200

    /** Keys whose values are secrets: only "changed" is recorded. */
    private val secretKeys = setOf("uuid", "id", "password", "pass", "private_key", "pre_shared_key", "psk", "auth", "auth_str", "secret",
        "privatekey", "presharedkey", "token", "key")

    data class Server(val name: String, val protocol: String, val address: String = "")
    /** One field of a changed server: [old] / [new] are null when the field is absent on that side; [secret]: values not stored. */
    data class Field(val key: String, val old: String?, val new: String?, val secret: Boolean = false)
    data class Changed(val before: Server, val after: Server, val fields: List<Field>)
    data class Entry(val at: Long, val added: List<Server> = emptyList(), val removed: List<Server> = emptyList(),
        val changed: List<Changed> = emptyList(), val other: List<String> = emptyList()) {
        val empty get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty() && other.isEmpty()
    }

    const val OTHER_EXPIRES = "expires"
    const val OTHER_TOTAL = "total"
    const val OTHER_PROVIDER = "provider"
    const val OTHER_ANNOUNCE = "announce"

    /** Field keys with a fixed meaning (shown with a translated label); everything else is shown as its JSON path. */
    const val FIELD_NAME = "name"
    const val FIELD_PROTOCOL = "protocol"

    /** What the update from [old] to [new] changed, or null when provider content did not change (or [old] is a new subscription). */
    fun diff(old: SubscriptionSource?, new: SubscriptionSource, at: Long): Entry? {
        if (old == null || !new.contentChanged(old)) return null
        val oldById = old.profiles.associateBy { it.id }
        val newIds = new.profiles.map { it.id }.toHashSet()
        val changed = mutableListOf<Changed>()
        val restNew = mutableListOf<Profile>()
        new.profiles.forEach { n ->
            val o = oldById[n.id]
            if (o == null) restNew += n
            else if (o.name != n.name || o.protocol != n.protocol || o.outbound != n.outbound) fields(o, n).takeIf { it.isNotEmpty() }?.let { changed += Changed(server(o), server(n), it) }
        }
        val restOld = old.profiles.filter { it.id !in newIds }.toMutableList()
        // A server whose link changed keeps its name: shown as changed, not as removed + added.
        val added = mutableListOf<Profile>()
        restNew.forEach { n ->
            val i = restOld.indexOfFirst { it.name == n.name }
            if (i < 0) added += n
            else { val o = restOld.removeAt(i); fields(o, n).takeIf { it.isNotEmpty() }?.let { changed += Changed(server(o), server(n), it) } }
        }
        val other = listOfNotNull(OTHER_EXPIRES.takeIf { new.expires != old.expires }, OTHER_TOTAL.takeIf { new.totalBytes != old.totalBytes },
            OTHER_PROVIDER.takeIf { new.providerName != old.providerName }, OTHER_ANNOUNCE.takeIf { new.links.announceText != old.links.announceText })
        return Entry(at, added.take(MAX_SERVERS).map(::server), restOld.take(MAX_SERVERS).map(::server), changed.take(MAX_SERVERS), other).takeIf { !it.empty }
    }

    /** [entry] first, then [history], at most [MAX_ENTRIES]. */
    fun append(history: List<Entry>, entry: Entry): List<Entry> = (listOf(entry) + history).take(MAX_ENTRIES)

    fun server(p: Profile) = Server(p.name, p.protocol, address(flatten(p.outbound)))

    /** Field-level differences between two versions of one server (name, protocol and every outbound setting except the tag). */
    fun fields(old: Profile, new: Profile): List<Field> {
        val out = mutableListOf<Field>()
        if (old.name != new.name) out += Field(FIELD_NAME, old.name.take(MAX_VALUE), new.name.take(MAX_VALUE))
        if (old.protocol != new.protocol) out += Field(FIELD_PROTOCOL, old.protocol.take(MAX_VALUE), new.protocol.take(MAX_VALUE))
        val a = flatten(old.outbound); val b = flatten(new.outbound)
        (a.keys + b.keys).distinct().filter { it != "tag" && a[it] != b[it] }.forEach { key ->
            out += if (secret(key)) Field(key, a[key]?.let { "" }, b[key]?.let { "" }, secret = true)
            else Field(key, a[key]?.take(MAX_VALUE), b[key]?.take(MAX_VALUE))
        }
        return out.take(MAX_FIELDS)
    }

    fun secret(key: String): Boolean = key.substringAfterLast('.').substringBefore('[').lowercase() in secretKeys

    /** Outbound JSON as "path" → value ("tls.server_name", "transport.headers.Host", "settings.vnext[0].port"). Not JSON: one "config" value. */
    fun flatten(outbound: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(prefix: String, v: Any?) {
            when (v) {
                is JSONObject -> v.keys().asSequence().sorted().forEach { k -> walk(if (prefix.isEmpty()) k else "$prefix.$k", v.opt(k)) }
                is JSONArray -> if (v.length() == 0) out[prefix] = "[]" else for (i in 0 until v.length()) walk("$prefix[$i]", v.opt(i))
                null, JSONObject.NULL -> out[prefix] = "null"
                else -> out[prefix] = v.toString()
            }
        }
        val parsed = runCatching { JSONObject(outbound) }.getOrNull()
        if (parsed == null) out["config"] = outbound.take(MAX_VALUE) else walk("", parsed)
        return out
    }

    /** "host:port" from a flattened outbound (sing-box server/server_port, Xray vnext/servers/peers), or "". */
    fun address(f: Map<String, String>): String {
        val host = f["server"] ?: f["settings.vnext[0].address"] ?: f["settings.servers[0].address"] ?: f["settings.address"] ?: f["peers[0].server"] ?: f["address"] ?: ""
        val port = f["server_port"] ?: f["settings.vnext[0].port"] ?: f["settings.servers[0].port"] ?: f["settings.port"] ?: f["peers[0].server_port"] ?: f["port"] ?: ""
        if (host.isEmpty()) return ""
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return if (port.isEmpty()) h else "$h:$port"
    }

    // --- storage format ---------------------------------------------------------------------------------------------------
    private fun Server.json() = JSONObject().put("n", name).put("p", protocol).put("a", address)
    private fun server(j: JSONObject) = Server(j.optString("n"), j.optString("p"), j.optString("a"))
    private fun Field.json() = JSONObject().put("k", key).put("s", secret).apply { old?.let { put("o", it) }; new?.let { put("v", it) } }
    private fun field(j: JSONObject) = Field(j.optString("k"), if (j.has("o")) j.optString("o") else null, if (j.has("v")) j.optString("v") else null, j.optBoolean("s"))
    private fun <T> list(a: JSONArray?, read: (JSONObject) -> T): List<T> = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(read) } }.orEmpty()

    fun Entry.json(): JSONObject = JSONObject().put("at", at)
        .put("added", JSONArray().apply { added.forEach { put(it.json()) } })
        .put("removed", JSONArray().apply { removed.forEach { put(it.json()) } })
        .put("changed", JSONArray().apply { changed.forEach { c -> put(JSONObject().put("b", c.before.json()).put("a", c.after.json()).put("f", JSONArray().apply { c.fields.forEach { put(it.json()) } })) } })
        .put("other", JSONArray(other))
    fun entry(j: JSONObject) = Entry(j.optLong("at"), list(j.optJSONArray("added"), ::server), list(j.optJSONArray("removed"), ::server),
        list(j.optJSONArray("changed")) { c -> Changed(server(c.optJSONObject("b") ?: JSONObject()), server(c.optJSONObject("a") ?: JSONObject()), list(c.optJSONArray("f"), ::field)) },
        j.optJSONArray("other")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())

    /** All subscriptions' histories: subscription id → entries, newest first. */
    fun toJson(all: Map<String, List<Entry>>): JSONObject = JSONObject().apply { all.forEach { (id, list) -> put(id, JSONArray().apply { list.take(MAX_ENTRIES).forEach { put(it.json()) } }) } }
    fun fromJson(j: JSONObject): Map<String, List<Entry>> = j.keys().asSequence().associateWith { id -> list(j.optJSONArray(id), ::entry).take(MAX_ENTRIES) }
}
