package org.mothvpn.core

/** Pure helpers for log files and crash reports (no Android dependencies). */
object LogText {
    /** Last [count] lines of [text]; empty lines at the end are ignored. */
    fun lastLines(text: String, count: Int): List<String> = text.lineSequence().toList().dropLastWhile { it.isEmpty() }.takeLast(count.coerceAtLeast(0))

    /** Keeps at most [max] characters, cutting the middle-free tail and marking the truncation. */
    fun truncate(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + "\n…[truncated ${text.length - max} chars]"

    /** Replaces control and non-ASCII-printable bytes of a binary trace by '.', keeping newlines and tabs. */
    fun printable(bytes: ByteArray): String = buildString(bytes.size) {
        for (b in bytes) { val c = b.toInt() and 0xFF; append(if (c == 10 || c == 9 || c in 32..126) c.toChar() else '.') }
    }

    /** Exit records newer than the last processed timestamp, oldest first. */
    fun <T> unprocessed(items: List<T>, last: Long, timestamp: (T) -> Long): List<T> = items.filter { timestamp(it) > last }.sortedBy(timestamp)
}
