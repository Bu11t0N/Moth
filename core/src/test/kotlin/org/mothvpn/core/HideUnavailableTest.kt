package org.mothvpn.core

import kotlin.test.*

class HideUnavailableTest {
    @Test fun failedStaysHiddenWithNoTimeLimit() {
        assertTrue(HideUnavailable.failed(checking = false, cancelled = false, error = true))
        assertFalse(HideUnavailable.failed(checking = true, cancelled = false, error = true), "in progress stays visible")
        assertFalse(HideUnavailable.failed(checking = false, cancelled = true, error = true), "cancelled is not a failure")
        assertFalse(HideUnavailable.failed(checking = false, cancelled = false, error = false), "success")
        assertFalse(HideUnavailable.failed(checking = false, cancelled = false, error = false), "unchecked has no error")
    }

    @Test fun checkStillMeasuresServersHiddenOnlyAsUnavailable() {
        // Keyword filter still drops servers from the check; hide-unavailable does not (it is display-only).
        val all = listOf(Profile("1", "Germany 1", "vless", "{}"), Profile("2", "Netherlands 1", "vless", "{}"))
        val filtered = ClientSettings().withFilter("wifi", ServerFilter.Rule(listOf("Germany"), ServerFilter.EXCLUDE))
        assertEquals(listOf("2"), ProbePlan.targets(all, filtered, "wifi").map { it.id })
        val hideOn = ClientSettings(hideUnavailable = true)
        assertEquals(listOf("1", "2"), ProbePlan.targets(all, hideOn, "wifi").map { it.id })
    }
}
