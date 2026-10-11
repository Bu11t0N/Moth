package org.mothvpn.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionLabelTest {
    @Test fun homeLabelIsTheAboutTextWithoutTheBuildNumber() {
        assertEquals("Moth 0.4.6-beta.1 (11)", VersionLabel.about("Moth", "0.4.6-beta.1", 11))
        assertEquals("Moth 0.4.6-beta.1", VersionLabel.about("Moth", "0.4.6-beta.1"))
        assertEquals("Moth 0.4.6", VersionLabel.about("Moth", "0.4.6"))
        assertEquals(VersionLabel.about("Moth", "0.4.6-beta.2", 12).removeSuffix(" (12)"), VersionLabel.about("Moth", "0.4.6-beta.2"))
    }
}
