package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class LanProxyTest {
    private val profile = Subscriptions.parseLink("vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&sni=example.com#A")

    @Test fun settingsRoundTripAndValidation() {
        val s = ClientSettings()
        assertFalse(s.lanProxy); assertEquals(LanProxy.DEFAULT_PORT, s.lanProxyPort)
        val on = ClientSettings.from(ClientSettings(lanProxy = true, lanProxyPort = 2080).json())
        assertTrue(on.lanProxy); assertEquals(2080, on.lanProxyPort)
        assertFailsWith<IllegalArgumentException> { ClientSettings(lanProxyPort = 80).validate() }
        assertFailsWith<IllegalArgumentException> { ClientSettings(lanProxyPort = 70000).validate() }
        assertTrue(s.needsReconnect(s.copy(lanProxy = true))); assertTrue(s.needsReconnect(s.copy(lanProxyPort = 2080)))
        assertEquals(listOf("lanProxy=false->true"), LogFacts.settingsChanges(s, s.copy(lanProxy = true)))
    }

    @Test fun offByDefaultNoInbound() {
        val c = JSONObject(TunnelConfig.create(profile))
        assertEquals(1, c.getJSONArray("inbounds").length())
        assertFalse(c.toString().contains(LanProxy.TAG))
    }

    @Test fun mixedInboundAndGuards() {
        val c = JSONObject(TunnelConfig.create(profile, lan = 10808, ownAddresses = listOf("192.168.43.1", "fe80::1%wlan0", "bogus")))
        val inbound = c.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("mixed", inbound.getString("type")); assertEquals(LanProxy.TAG, inbound.getString("tag")); assertEquals(10808, inbound.getInt("listen_port"))
        val rules = c.getJSONObject("route").getJSONArray("rules")
        val public = rules.getJSONObject(0)
        assertEquals("logical", public.getString("type")); assertEquals("reject", public.getString("action"))
        assertEquals(LanProxy.TAG, public.getJSONArray("rules").getJSONObject(0).getJSONArray("inbound").getString(0))
        public.getJSONArray("rules").getJSONObject(1).let { assertTrue(it.getBoolean("source_ip_is_private")); assertTrue(it.getBoolean("invert")) }
        val own = rules.getJSONObject(1).getJSONArray("source_ip_cidr").let { a -> (0 until a.length()).map(a::getString) }
        assertEquals(listOf("127.0.0.0/8", "::1/128", "192.168.43.1/32", "fe80::1/128"), own)
    }

    @Test fun onlySelectedAppsKeepsLanClients() {
        val s = ClientSettings(splitMode = "only", splitApps = listOf("com.example.browser"))
        val plain = TunnelConfig.appGuardRules(s, "org.mothvpn.android")
        assertTrue(plain.last().getBoolean("invert"))
        val shared = TunnelConfig.appGuardRules(s, "org.mothvpn.android", lanShared = true).last()
        assertEquals("logical", shared.getString("type")); assertEquals("and", shared.getString("mode")); assertEquals("reject", shared.getString("action"))
        assertEquals("tun-in", shared.getJSONArray("rules").getJSONObject(0).getJSONArray("inbound").getString(0))
        assertTrue(shared.getJSONArray("rules").getJSONObject(1).getBoolean("invert"))
    }

    @Test fun endpointsPreferSharingInterfaces() {
        val e = LanProxy.endpoints(listOf("wlan0" to "192.168.1.20", "tun0" to "172.19.0.1", "lo" to "127.0.0.1", "ap0" to "192.168.43.1", "rmnet0" to "100.70.1.2", "wlan0" to "fe80::1"), 10808)
        assertEquals(listOf("192.168.43.1:10808", "192.168.1.20:10808"), e.map { it.second })
        assertTrue(LanProxy.privateV4("172.20.0.1")); assertFalse(LanProxy.privateV4("172.32.0.1")); assertFalse(LanProxy.privateV4("8.8.8.8"))
    }
}
