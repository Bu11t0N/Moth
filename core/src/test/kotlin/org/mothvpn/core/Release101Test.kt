package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class Release101Test {
    @Test fun vpnOnlyIsPerSubscriptionDefaultOffAndRoundTrips() {
        val old = ClientSettings()
        assertFalse(old.updatesViaVpn("one"))
        assertFalse(old.updatesViaVpn(null))
        val selected = old.copy(subVpnOnlySubs = listOf("one"))
        val restored = ClientSettings.from(selected.json())
        assertTrue(restored.updatesViaVpn("one"))
        assertFalse(restored.updatesViaVpn("two"))
        assertFalse(restored.updatesViaVpn(null))
        assertFalse(old.needsReconnect(selected)) // The protected download endpoint is always available during the VPN session.
        assertTrue(ClientSettings.from(old.json().put("compactServers", false)).compactServers)
        assertFailsWith<IllegalArgumentException> { old.copy(subVpnOnlySubs = listOf("bad/id")).validate() }
    }
    @Test fun subscriptionDownloadCannotFollowBypassOrSplitRules() {
        val p = Subscriptions.parseLink("trojan://test@example.com:443?security=tls#Test")
        val via = SubscriptionTunnel(32123, "synthetic-session-password")
        val config = JSONObject(TunnelConfig.create(p, ClientSettings(directRu = true, splitMode = "only", splitApps = listOf("example.app")), appGuard = "org.mothvpn.android", subscriptionTunnel = via))
        val inbound = config.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals("synthetic-session-password", inbound.getJSONArray("users").getJSONObject(0).getString("password"))
        val first = config.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals(SubscriptionTunnel.TAG, first.getJSONArray("inbound").getString(0))
        assertEquals("proxy", first.getString("outbound"))
        assertEquals(1, JSONObject(TunnelConfig.create(p)).getJSONArray("inbounds").length())
    }
    @Test fun artworkSizeSharedAndBounded() {
        assertEquals(260f, MothArtworkSize.side(400f, 850f, 1f))
        assertEquals(60f, MothArtworkSize.side(360f, 600f, 1f))
        assertEquals(48f, MothArtworkSize.side(320f, 500f, 2f))
        assertTrue(MothArtworkSize.side(300f, 700f, 1.5f) in 48f..260f)
    }
}
