package org.mothvpn.core

/**
 * Settings › Data › Connections: the tunnel core's live connection list (its clash_api connection tracker, read over the private
 * command socket) in plain values for the page. Nothing is decrypted: only addresses, ports, the sniffed protocol name and byte counts.
 */
object LiveConnections {
    /** One open connection. [up]/[down]: bytes in the last report interval; [upTotal]/[downTotal]: since it opened. */
    data class Item(
        val id: String, val network: String, val protocol: String, val source: String, val destination: String, val domain: String,
        val outbound: String, val createdAtMs: Long, val up: Long, val down: Long, val upTotal: Long, val downTotal: Long, val app: String?,
    ) { val total get() = upTotal + downTotal }

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
}
