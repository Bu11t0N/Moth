package org.mothvpn.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ProbeOrderTest {
    @Test fun followsShownOrderThenRest() {
        assertEquals(listOf("c", "a", "b", "d"), ProbeOrder.arrange(listOf("a", "b", "c", "d"), listOf("c", "a")) { it })
    }
    @Test fun noShownIdsKeepsSavedOrder() {
        assertEquals(listOf("a", "b"), ProbeOrder.arrange(listOf("a", "b"), emptyList()) { it })
    }
    @Test fun unknownShownIdsIgnoredAndLatencySnapshotRespected() {
        val sorted = ServerOrder.sort(listOf("x", "y", "z"), ServerOrder.LATENCY, { it }) { mapOf("x" to 300L, "y" to 50L)[it] }
        assertEquals(listOf("y", "x", "z"), ProbeOrder.arrange(listOf("x", "y", "z"), listOf("gone") + sorted) { it })
    }
}
