package org.mothvpn.core

/**
 * Timing of the staggered rows when a server group opens or closes: rows unfold from under the header one after another and fold
 * back faster in reverse order. The stagger shrinks for big groups so the whole group always finishes within [OPEN_TOTAL] / [CLOSE_TOTAL].
 */
object GroupUnfold {
    const val OPEN_ROW = 200; const val OPEN_STAGGER = 25; const val OPEN_TOTAL = 400
    const val CLOSE_ROW = 120; const val CLOSE_STAGGER = 15; const val CLOSE_TOTAL = 240

    data class Timing(val delayMs: Int, val durationMs: Int)

    fun stagger(count: Int, opening: Boolean): Int {
        if (count <= 1) return 0
        val (row, step, total) = if (opening) Triple(OPEN_ROW, OPEN_STAGGER, OPEN_TOTAL) else Triple(CLOSE_ROW, CLOSE_STAGGER, CLOSE_TOTAL)
        return minOf(step, (total - row) / (count - 1))
    }

    /** Row [index] of [count]: opening goes top-down, closing bottom-up (the reverse). */
    fun timing(index: Int, count: Int, opening: Boolean): Timing {
        val i = index.coerceIn(0, (count - 1).coerceAtLeast(0))
        val order = if (opening) i else count - 1 - i
        return Timing(order * stagger(count, opening), if (opening) OPEN_ROW else CLOSE_ROW)
    }

    /** When the last row has finished. */
    fun total(count: Int, opening: Boolean): Int = if (count <= 0) 0 else timing(if (opening) count - 1 else 0, count, opening).let { it.delayMs + it.durationMs }
}
