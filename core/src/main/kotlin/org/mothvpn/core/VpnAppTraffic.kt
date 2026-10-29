package org.mothvpn.core

import org.json.JSONObject

/**
 * Per-app traffic measured by the VPN itself (Settings › Data › App traffic). The tunnel core reports every connection with its
 * running byte totals; the connection owner (uid) comes from Android when the core asks for it. No Android permission needed.
 */
object VpnAppTraffic {
    /** Key of a connection as the core sees it inside the tunnel: "tcp|172.19.0.2|43210". */
    fun ownerKey(network: String, address: String, port: Int): String = "${network.lowercase()}|${address.removePrefix("[").removeSuffix("]").lowercase()}|$port"
    /** Owner key of the core's connection source ("a.b.c.d:port" or "[v6]:port"); null when it cannot be parsed. */
    fun ownerKey(network: String, source: String): String? {
        val colon = source.lastIndexOf(':')
        if (colon <= 0) return null
        val port = source.substring(colon + 1).toIntOrNull() ?: return null
        return ownerKey(network, source.substring(0, colon), port)
    }
    /** IP protocol number from Android's / the core's owner lookup to the core's network name. */
    fun network(ipProtocol: Int): String = if (ipProtocol == 17) "udp" else "tcp"

    /** Turns the running totals of each connection (active ones and recently closed ones) into byte deltas; counts each byte once. */
    class Deltas(private val maxClosed: Int = 4096) {
        private val running = HashMap<String, LongArray>()
        private val closed = LinkedHashSet<String>()
        /** Bytes ([up], [down]) the connection [id] moved since the last report; null when it was already counted to the end. */
        fun update(id: String, up: Long, down: Long, isClosed: Boolean): LongArray? {
            if (id in closed) return null
            val last = running[id] ?: LongArray(2)
            val delta = longArrayOf((up - last[0]).coerceAtLeast(0), (down - last[1]).coerceAtLeast(0))
            if (isClosed) {
                running.remove(id); closed.add(id)
                if (closed.size > maxClosed) closed.iterator().let { it.next(); it.remove() }
            } else running[id] = longArrayOf(maxOf(up, last[0]), maxOf(down, last[1]))
            return delta
        }
        /** Drops active connections the core no longer reports (closed and already out of its closed list). */
        fun retain(ids: Set<String>) { running.keys.retainAll(ids) }
        fun clear() { running.clear(); closed.clear() }
    }

    /** One app's traffic in a period. */
    data class AppBytes(val app: String, val tx: Long, val rx: Long) { val total get() = tx + rx }

    /** Daily per-app, per-network ("wifi"/"mobile") byte counts, kept for [keepDays] days. */
    class Ledger(private val keepDays: Int = 35) {
        private val days = java.util.TreeMap<Long, HashMap<String, LongArray>>()
        @Synchronized fun add(day: Long, kind: String, app: String, tx: Long, rx: Long) {
            if (tx <= 0 && rx <= 0 || app.isBlank()) return
            val row = days.getOrPut(day) { HashMap() }.getOrPut("${kindOf(kind)}|$app") { LongArray(2) }
            row[0] += tx.coerceAtLeast(0); row[1] += rx.coerceAtLeast(0)
            while (days.isNotEmpty() && days.firstKey() <= day - keepDays) days.pollFirstEntry()
        }
        /** Apps with traffic from [fromDay] to [toDay] (inclusive), only [kind] when it is "wifi"/"mobile"; biggest first. */
        @Synchronized fun query(fromDay: Long, toDay: Long, kind: String? = null): List<AppBytes> {
            val sums = HashMap<String, LongArray>()
            days.subMap(fromDay, true, toDay, true).values.forEach { rows ->
                rows.forEach { (key, v) ->
                    val k = key.substringBefore('|'); val app = key.substringAfter('|')
                    if (kind == null || k == kindOf(kind)) sums.getOrPut(app) { LongArray(2) }.let { it[0] += v[0]; it[1] += v[1] }
                }
            }
            return sums.map { (app, v) -> AppBytes(app, v[0], v[1]) }.filter { it.total > 0 }
                .sortedWith(compareByDescending<AppBytes> { it.total }.thenBy { it.app })
        }
        @Synchronized fun isEmpty() = days.isEmpty()
        @Synchronized fun toJson(): String = JSONObject().apply {
            days.forEach { (day, rows) -> put(day.toString(), JSONObject().apply { rows.forEach { (key, v) -> put(key, org.json.JSONArray(listOf(v[0], v[1]))) } }) }
        }.toString()
        @Synchronized fun load(json: String) {
            days.clear()
            runCatching {
                val root = JSONObject(json)
                root.keys().forEach { day ->
                    val rows = root.getJSONObject(day); val map = HashMap<String, LongArray>()
                    rows.keys().forEach { key -> rows.getJSONArray(key).let { map[key] = longArrayOf(it.getLong(0), it.getLong(1)) } }
                    day.toLongOrNull()?.let { days[it] = map }
                }
            }
        }
        private fun kindOf(kind: String) = if (kind == "mobile") "mobile" else "wifi"
    }
}
