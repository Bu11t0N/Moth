package org.mothvpn.core

import kotlin.test.*

class LatencyBandTest {
    @Test fun thresholds() {
        assertEquals(LatencyBand.GOOD, LatencyBand.of(0))
        assertEquals(LatencyBand.GOOD, LatencyBand.of(149))
        assertEquals(LatencyBand.MEDIUM, LatencyBand.of(150))
        assertEquals(LatencyBand.MEDIUM, LatencyBand.of(400))
        assertEquals(LatencyBand.POOR, LatencyBand.of(401))
    }
}
