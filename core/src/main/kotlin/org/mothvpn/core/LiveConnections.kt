package org.mothvpn.core

/**
 * Settings › Data › Connections: the tunnel core's live connection list (its clash_api connection tracker, read over the private
 * command socket) in plain values for the page. Nothing is decrypted: only addresses, ports, the sniffed protocol name and byte counts.
 */
object LiveConnections {
    /**
     * One connection. [up]/[down]: bytes in the last report interval; [upTotal]/[downTotal]: since it opened.
     * [closedAtMs]: when the core closed it (0 while it is open).
     */
    data class Item(
        val id: String, val network: String, val protocol: String, val source: String, val destination: String, val domain: String,
        val outbound: String, val createdAtMs: Long, val up: Long, val down: Long, val upTotal: Long, val downTotal: Long, val app: String?,
        val closedAtMs: Long = 0,
    ) { val total get() = upTotal + downTotal }

    /** How many recently closed connections the page shows (the core already keeps its own bounded list of closed ones). */
    const val RECENT_LIMIT = 30

    /** Most recently closed first, at most [limit]; open connections are ignored. */
    fun recentlyClosed(items: List<Item>, limit: Int = RECENT_LIMIT): List<Item> =
        items.filter { it.closedAtMs > 0 }.sortedWith(compareByDescending<Item> { it.closedAtMs }.thenBy { it.id }).take(limit.coerceAtLeast(0))

    /**
     * Recently closed connections split in two: those that ended by themselves and those the user closed on the page ([manualIds]),
     * each most recently closed first and at most [limit], so a burst of natural closes never pushes the user's own closes out.
     */
    fun recentlyClosedSplit(items: List<Item>, manualIds: Set<String>, limit: Int = RECENT_LIMIT): Pair<List<Item>, List<Item>> {
        val (manual, natural) = items.filter { it.closedAtMs > 0 }.partition { it.id in manualIds }
        return recentlyClosed(natural, limit) to recentlyClosed(manual, limit)
    }

    /** Closed connections grouped [by] like the active list: groups and their rows ordered by the newest close first. */
    fun groupedClosed(items: List<Item>, by: GroupBy): List<Group> =
        items.groupBy { key(it, by) }.map { (k, v) -> Group(k, v.sortedWith(compareByDescending<Item> { it.closedAtMs }.thenBy { it.id })) }
            .sortedWith(compareByDescending<Group> { g -> g.items.maxOf { it.closedAtMs } }.thenBy { it.key })

    enum class Sort { NEWEST, TRAFFIC, SPEED }

    /** Port of "host:port" / "[v6]:port"; null when there is none. */
    fun port(address: String): Int? {
        val colon = address.lastIndexOf(':')
        if (colon <= 0 || (address.startsWith("[") && address.lastIndexOf(']') > colon)) return null
        return address.substring(colon + 1).toIntOrNull()
    }

    /** Host without the port ("a.b.c.d", "2001:db8::1"); the address itself when it has no port. */
    fun host(address: String): String {
        val p = port(address) ?: return address.removePrefix("[").removeSuffix("]")
        return address.substring(0, address.length - p.toString().length - 1).removePrefix("[").removeSuffix("]")
    }

    /** What the connection goes to: the sniffed / requested domain when known, else the IP, with the destination port. */
    fun target(domain: String, destination: String): String {
        val p = port(destination)
        val h = domain.ifBlank { host(destination) }.let { if (domain.isBlank() && ':' in it) "[$it]" else it }
        return if (p == null) h else "$h:$p"
    }

    /** "TCP · TLS" style label from the core's network and sniffed protocol (protocol omitted when unknown). */
    fun protocolLabel(network: String, protocol: String): String =
        listOf(network.uppercase(), protocol.uppercase()).filter { it.isNotBlank() }.distinct().joinToString(" · ")

    /** Per-second rate from bytes counted over one report interval of [intervalMs]. */
    fun perSecond(bytes: Long, intervalMs: Long): Long = if (intervalMs <= 0) bytes.coerceAtLeast(0) else (bytes.coerceAtLeast(0) * 1000 / intervalMs)

    fun sorted(items: List<Item>, sort: Sort): List<Item> = when (sort) {
        Sort.NEWEST -> items.sortedWith(compareByDescending<Item> { it.createdAtMs }.thenBy { it.id })
        Sort.TRAFFIC -> items.sortedWith(compareByDescending<Item> { it.total }.thenBy { it.id })
        Sort.SPEED -> items.sortedWith(compareByDescending<Item> { it.up + it.down }.thenByDescending { it.total }.thenBy { it.id })
    }

    /** How the page groups connections: by owner app (default), by target host, or a flat list. */
    enum class GroupBy { APP, HOST, NONE }

    /** Host part of what a connection goes to (domain when known, else the IP), for [GroupBy.HOST]. */
    fun hostKey(item: Item): String = item.domain.ifBlank { host(item.destination) }.ifBlank { "?" }

    /** Group key of [item]: the owner (package / "uid:N" / "" when not identified) or the host. */
    fun key(item: Item, by: GroupBy): String = when (by) {
        GroupBy.APP -> item.app.orEmpty()
        GroupBy.HOST -> hostKey(item)
        GroupBy.NONE -> item.id
    }

    /** Connections that share one [key]: [items] already sorted; sums for the group row. */
    data class Group(val key: String, val items: List<Item>) {
        val up get() = items.sumOf { it.up }
        val down get() = items.sumOf { it.down }
        val upTotal get() = items.sumOf { it.upTotal }
        val downTotal get() = items.sumOf { it.downTotal }
        val total get() = upTotal + downTotal
        val newest get() = items.maxOf { it.createdAtMs }
        val app get() = items.first().app
    }

    /**
     * [items] grouped [by], groups ordered by the same [sort] as connections (newest = the group's newest connection, speed = summed rate,
     * traffic = summed bytes), connections inside each group sorted by [sort] too. Ties fall back to the key, so the order stays stable.
     */
    fun grouped(items: List<Item>, by: GroupBy, sort: Sort): List<Group> {
        val groups = items.groupBy { key(it, by) }.map { (k, v) -> Group(k, sorted(v, sort)) }
        return when (sort) {
            Sort.NEWEST -> groups.sortedWith(compareByDescending<Group> { it.newest }.thenBy { it.key })
            Sort.TRAFFIC -> groups.sortedWith(compareByDescending<Group> { it.total }.thenBy { it.key })
            Sort.SPEED -> groups.sortedWith(compareByDescending<Group> { it.up + it.down }.thenByDescending { it.total }.thenBy { it.key })
        }
    }
}
