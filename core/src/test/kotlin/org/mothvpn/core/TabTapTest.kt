package org.mothvpn.core

import kotlin.test.*

class TabTapTest {
    @Test fun settingsTapAlwaysRequestsTheRoot() {
        // Already on Settings (in a subpage): back to the main list.
        assertEquals(TabTap.Result(TabTap.SETTINGS, 1), TabTap.tap(TabTap.SETTINGS, 0))
        // Tapped again: another request, so it works every time.
        assertEquals(TabTap.Result(TabTap.SETTINGS, 2), TabTap.tap(TabTap.SETTINGS, 1))
    }
    @Test fun otherTabsDoNotTouchSettings() {
        assertEquals(TabTap.Result(TabTap.HOME, 3), TabTap.tap(TabTap.HOME, 3))
        assertEquals(TabTap.Result(TabTap.SERVERS, 3), TabTap.tap(TabTap.SERVERS, 3))
    }
}
