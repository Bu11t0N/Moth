package org.mothvpn.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ProbeErrorKindTest {
    @Test fun exceptionsMapToStableKinds() {
        assertEquals(ProbeErrorKind.TIMEOUT, ProbeErrorKind.of(java.net.SocketTimeoutException()))
        assertEquals(ProbeErrorKind.DNS_TIMEOUT, ProbeErrorKind.of(java.util.concurrent.TimeoutException()))
        assertEquals(ProbeErrorKind.DNS_UNRESOLVED, ProbeErrorKind.of(java.net.UnknownHostException()))
        assertEquals(ProbeErrorKind.PORT_CLOSED, ProbeErrorKind.of(java.net.ConnectException()))
        assertEquals(ProbeErrorKind.TLS, ProbeErrorKind.of(javax.net.ssl.SSLHandshakeException("x")))
        assertEquals(ProbeErrorKind.FAILED, ProbeErrorKind.of(IllegalStateException()))
    }
    @Test fun detailedIconsSettingDefaultsOffAndPersists() {
        assertEquals(false, ClientSettings().detailedIcons)
        val s = ClientSettings(detailedIcons = true)
        assertEquals(true, ClientSettings.from(s.json()).detailedIcons)
        val old = ClientSettings().json().apply { remove("detailedIcons") }
        assertEquals(false, ClientSettings.from(old).detailedIcons)
    }
}
