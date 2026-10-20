package org.mothvpn.core

/** HTTP/1.1 pieces of the HTTPS server check (Android-free so they are unit-tested): request text, keep-alive and body length rules. */
object ProbeHttp {
    /** A GET for [target] on [authority]; [keepAlive] asks to reuse the connection for the measured second request. */
    fun request(target: String, authority: String, keepAlive: Boolean): String {
        require(target.none { it == '\r' || it == '\n' } && authority.none { it == '\r' || it == '\n' })
        return "GET $target HTTP/1.1\r\nHost: $authority\r\nConnection: ${if (keepAlive) "keep-alive" else "close"}\r\n" +
            "Accept-Encoding: identity\r\nCache-Control: no-cache\r\n\r\n"
    }

    /** Status code of a status line ("HTTP/1.1 204 No Content" → 204), or null. */
    fun status(line: String): Int? = line.split(' ').getOrNull(1)?.toIntOrNull()

    /** Header lines ("Name: value") as lower-case names to values; repeated names keep the last value. */
    fun headers(lines: List<String>): Map<String, String> = lines.mapNotNull { l ->
        val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
    }.toMap()

    /** Body length to skip before the connection can carry the next request: bytes, [CHUNKED], or null when it is unknown (read until close). */
    fun bodyLength(status: Int, headers: Map<String, String>): Long? = when {
        status == 204 || status == 304 || status in 100..199 -> 0
        headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> CHUNKED
        else -> headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
    }
    const val CHUNKED = -1L

    /** The server keeps the connection open for another request: HTTP/1.1 without "Connection: close" (HTTP/1.0 only with keep-alive). */
    fun reusable(statusLine: String, headers: Map<String, String>, bodyLength: Long?): Boolean {
        if (bodyLength == null) return false
        val connection = headers["connection"]?.lowercase().orEmpty()
        return when {
            connection.contains("close") -> false
            statusLine.startsWith("HTTP/1.1") -> true
            else -> connection.contains("keep-alive")
        }
    }

    /** Size of one chunk from its size line ("1a;ext=x" → 26); throws on garbage. */
    fun chunkSize(line: String): Long = line.substringBefore(';').trim().toLong(16).also { require(it >= 0) }

    /** Shown latency: the warm second request when it worked (never above the first), else the first request's time. */
    fun shown(first: Long, second: Long?): Long = if (second == null) first else minOf(first, second)
}
