package org.mothvpn.core

import kotlin.test.*

class GroupUnfoldTest {
    @Test fun smallGroupUsesTheFullStagger() {
        assertEquals(GroupUnfold.Timing(0, 200), GroupUnfold.timing(0, 5, true))
        assertEquals(GroupUnfold.Timing(100, 200), GroupUnfold.timing(4, 5, true))
        assertEquals(300, GroupUnfold.total(5, true))
    }
    @Test fun bigGroupsFinishInTime() {
        for (n in listOf(1, 2, 9, 30, 150, 1000)) {
            assertTrue(GroupUnfold.total(n, true) <= GroupUnfold.OPEN_TOTAL, "open $n")
            assertTrue(GroupUnfold.total(n, false) <= GroupUnfold.CLOSE_TOTAL, "close $n")
        }
    }
    @Test fun closingIsFasterAndReversed() {
        assertTrue(GroupUnfold.total(8, false) < GroupUnfold.total(8, true))
        assertEquals(0, GroupUnfold.timing(7, 8, false).delayMs) // the last row folds first
        assertTrue(GroupUnfold.timing(0, 8, false).delayMs > 0)
    }
    @Test fun staggerStaysInRange() {
        assertEquals(0, GroupUnfold.stagger(1, true))
        assertEquals(25, GroupUnfold.stagger(3, true))
        assertTrue(GroupUnfold.stagger(100, true) in 0..25)
    }
}
