package org.mothvpn.core

import kotlin.test.*

class PhonePortraitTest {
    @Test fun phonesStayPortraitIncludingWhenRotated() {
        assertTrue(PhonePortrait.lock(360))
        assertTrue(PhonePortrait.lock(411))
        assertTrue(PhonePortrait.lock(599))
        // A phone turned on its side keeps the same smallest width.
        assertTrue(PhonePortrait.lock(360))
    }
    @Test fun tabletsMayRotate() {
        assertFalse(PhonePortrait.lock(600))
        assertFalse(PhonePortrait.lock(720))
        assertFalse(PhonePortrait.lock(800))
    }
}
