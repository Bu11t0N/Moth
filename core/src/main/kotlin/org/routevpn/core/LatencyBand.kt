package org.routevpn.core

/** Colour class of a measured ping: under 150 ms good, 150..400 ms medium, above 400 ms poor. */
enum class LatencyBand {
    GOOD, MEDIUM, POOR;
    companion object {
        const val GOOD_BELOW_MS = 150L
        const val MEDIUM_UP_TO_MS = 400L
        fun of(ms: Long): LatencyBand = when {
            ms < GOOD_BELOW_MS -> GOOD
            ms <= MEDIUM_UP_TO_MS -> MEDIUM
            else -> POOR
        }
    }
}
