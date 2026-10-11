package org.routevpn.core

import kotlin.test.*

class ProtectPolicyTest {
    @Test fun failedProtectIsFatalOnlyWithActiveTunnel() {
        assertTrue(ProtectPolicy.allows(true, true))
        assertTrue(ProtectPolicy.allows(true, false))
        assertTrue(ProtectPolicy.allows(false, false))
        assertFalse(ProtectPolicy.allows(false, true))
    }
}
