package org.routevpn.core

import kotlin.test.*

class LogRedactorTest {
    @Test fun masksServersAndSecrets() {
        val raw = "failed vless://3f2c1d4e-aaaa-bbbb-cccc-1234567890ab@vpn.example.com:443?x=1 via 203.0.113.7:8443, host node1.example.org, " +
            "uuid 3f2c1d4e-aaaa-bbbb-cccc-1234567890ab key QWxhZGRpbjpvcGVuIHNlc2FtZQAAAAAAAAAA v6 2001:db8::1"
        val out = LogRedactor.redact(raw)
        for (leak in listOf("example", "203.0.113", "3f2c1d4e", "QWxhZGRpbjpv", "2001:db8")) assertFalse(out.contains(leak), "$leak in $out")
        assertTrue(out.contains("failed"))
    }
    @Test fun nullAndNewlines() {
        assertEquals("", LogRedactor.redact(null))
        assertFalse(LogRedactor.redact("a\nb").contains('\n'))
    }
}
