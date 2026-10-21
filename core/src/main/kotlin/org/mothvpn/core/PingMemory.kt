package org.mothvpn.core

/**
 * The last finished ping of every server, per network page ("wifi:<id>", "mobile:<id>"), kept until that server is checked again:
 * no expiry, nothing cleared on network changes, VPN on/off or an app restart (saved to a small private file). Only finished
 * results are kept; a check that is running or was interrupted never replaces them.
 */
object PingMemory {
    data class Entry(val key: String, val latencyMs: Long?, val kind: String?, val method: String, val checkedAt: Long, val networkHandle: Long)

    private const val HEADER = "moth-pings 1"
    /** At most this many entries are saved (newest first), so servers removed long ago don't pile up. */
    const val MAX = 4000

    fun encode(entries: Collection<Entry>): String = buildString {
        append(HEADER).append('\n')
        entries.sortedByDescending { it.checkedAt }.take(MAX).forEach { e ->
            if ('\t' in e.key || '\n' in e.key) return@forEach
            append(e.key).append('\t').append(e.latencyMs ?: "").append('\t').append(e.kind ?: "").append('\t').append(e.method).append('\t')
                .append(e.checkedAt).append('\t').append(e.networkHandle).append('\n')
        }
    }

    fun decode(text: String): List<Entry> {
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 6 || f[0].isEmpty()) return@mapNotNull null
            val latency = f[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
            val kind = f[2].takeIf { it.isNotEmpty() }
            if (latency == null && kind == null) return@mapNotNull null // neither a value nor a failure: nothing to show
            Entry(f[0], latency, kind, f[3], f[4].toLongOrNull() ?: return@mapNotNull null, f[5].toLongOrNull() ?: 0L)
        }
    }

    /** Whether a result may be remembered: finished (not running, not cancelled) and with a value or a failure. */
    fun keeps(checking: Boolean, cancelled: Boolean, latencyMs: Long?, failed: Boolean): Boolean = !checking && !cancelled && (latencyMs != null || failed)
}
