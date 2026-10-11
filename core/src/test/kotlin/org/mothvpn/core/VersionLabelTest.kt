package org.mothvpn.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionLabelTest {
    @Test fun betaAndReleaseLabels() {
        assertEquals("0.4.6b1", VersionLabel.of("0.4.6-beta.1"))
        assertEquals("0.4.6b12", VersionLabel.of("0.4.6-beta.12"))
        assertEquals("0.4.6", VersionLabel.of("0.4.6"))
    }
}
