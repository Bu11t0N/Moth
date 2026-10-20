package org.mothvpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class XraySystemCaTest {
    @Test fun everyTlsSettingsUsesTheSystemCas() {
        val outbound = JSONObject("""{"tag":"proxy","protocol":"vless","settings":{},"streamSettings":{"network":"xhttp","security":"tls",
            "tlsSettings":{"serverName":"a.example"},"xhttpSettings":{"extra":{"downloadSettings":{"security":"tls","tlsSettings":{"serverName":"b.example"}}}}}}""")
        val config = JSONObject(LocalProxyConfig.xray(Profile("p1", "P", "vless", outbound.toString(), "xray"), 1080, "u", "p"))
        val stream = config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals(true, stream.getJSONObject("tlsSettings").getBoolean("useSystemCA"))
        assertEquals(true, stream.getJSONObject("xhttpSettings").getJSONObject("extra").getJSONObject("downloadSettings").getJSONObject("tlsSettings").getBoolean("useSystemCA"))
        // An explicit choice in a full Xray config is kept.
        val own = JSONObject().put("tlsSettings", JSONObject().put("useSystemCA", false)); LocalProxyConfig.useSystemCa(own)
        assertEquals(false, own.getJSONObject("tlsSettings").getBoolean("useSystemCA"))
    }
}
