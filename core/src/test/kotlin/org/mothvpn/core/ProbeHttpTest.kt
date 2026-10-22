package org.mothvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeHttpTest {
    @Test fun warmUpRequestAsksForKeepAliveAndTheMeasuredOneCloses() {
        assertTrue(ProbeHttp.request("/generate_204", "www.gstatic.com", true).contains("\r\nConnection: keep-alive\r\n"))
        assertTrue(ProbeHttp.request("/generate_204", "www.gstatic.com", false).contains("\r\nConnection: close\r\n"))
        assertTrue(ProbeHttp.request("/x", "h", true).endsWith("\r\n\r\n"))
    }

    @Test fun generate204IsReusableOnHttp11() {
        val h = ProbeHttp.headers(listOf("Content-Length: 0", "Date: x"))
        val len = ProbeHttp.bodyLength(204, h)
        assertEquals(0L, len)
        assertTrue(ProbeHttp.reusable("HTTP/1.1 204 No Content", h, len))
        assertEquals(204, ProbeHttp.status("HTTP/1.1 204 No Content"))
    }

    @Test fun closeUnknownLengthAndHttp10AreNotReusable() {
        assertFalse(ProbeHttp.reusable("HTTP/1.1 204 No Content", ProbeHttp.headers(listOf("Connection: close")), 0))
        val noLength = ProbeHttp.headers(listOf("Content-Type: text/html"))
        assertNull(ProbeHttp.bodyLength(200, noLength))
        assertFalse(ProbeHttp.reusable("HTTP/1.1 200 OK", noLength, ProbeHttp.bodyLength(200, noLength)))
        assertFalse(ProbeHttp.reusable("HTTP/1.0 204 No Content", emptyMap(), 0))
        assertTrue(ProbeHttp.reusable("HTTP/1.0 204 No Content", ProbeHttp.headers(listOf("Connection: Keep-Alive")), 0))
    }

    @Test fun chunkedAndContentLengthBodies() {
        assertEquals(ProbeHttp.CHUNKED, ProbeHttp.bodyLength(200, ProbeHttp.headers(listOf("Transfer-Encoding: chunked"))))
        assertEquals(12L, ProbeHttp.bodyLength(200, ProbeHttp.headers(listOf("content-length: 12"))))
        assertEquals(26L, ProbeHttp.chunkSize("1a;name=x"))
        assertEquals(0L, ProbeHttp.chunkSize("0"))
    }

    @Test fun shownLatencyPrefersTheWarmRequestAndFallsBackToTheFirst() {
        assertEquals(240L, ProbeHttp.shown(750, 240))
        assertEquals(750L, ProbeHttp.shown(750, null))
        assertEquals(300L, ProbeHttp.shown(300, 420))
    }

    @Test fun probeMethodsAndMigration() {
        assertEquals(ProbePolicy.LATENCY, ClientSettings().probeMode)
        fun from(mode: String) = ClientSettings.from(ClientSettings().json().put("probeMode", mode))
        assertEquals(ProbePolicy.LATENCY, from("auto").probeMode)
        assertEquals(ProbePolicy.LATENCY, from("https").probeMode)
        assertEquals(ProbePolicy.TCP, from("tcp").probeMode)
        assertEquals(ProbePolicy.FULL, from("full").probeMode)
        assertEquals(ProbePolicy.LATENCY, from("icmp").probeMode)
        val full = ClientSettings(probeMode = ProbePolicy.FULL)
        assertEquals(full, ClientSettings.from(full.json()))
        // Old saved "only this address" values are ignored; there is always exactly the one address from settings.
        val old = ClientSettings.from(ClientSettings().json().put("probeUrlOnly", true).put("probeUrl", "https://example.com/ping"))
        assertEquals(listOf("https://example.com/ping"), ProbePolicy.urls(old))
        assertFalse(old.json().has("probeUrlOnly"))
        for (s in listOf(ClientSettings(), ClientSettings(probeMode = ProbePolicy.TCP), full)) assertEquals(listOf(s.probeUrl), ProbePolicy.urls(s))
    }
}
