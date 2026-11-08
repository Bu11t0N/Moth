package org.mothvpn.android

import org.mothvpn.core.SubscriptionTunnel
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Never falls back to the physical network, even if the VPN stops during a redirect or a chained provider download. */
object TunnelDownloads {
    @Volatile internal var session: SubscriptionTunnel? = null
    fun ready() = TunnelStatus.state.value == TunnelState.RUNNING && session != null
    fun requireSession(): SubscriptionTunnel = session?.takeIf { ready() } ?: throw VpnRequired()
    fun open(url: URL, via: SubscriptionTunnel): HttpsURLConnection {
        if (!ready() || session !== via) throw VpnRequired()
        return (url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", via.port))) as HttpsURLConnection).apply {
            val auth = android.util.Base64.encodeToString("${SubscriptionTunnel.USER}:${via.password}".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            setRequestProperty("Proxy-Authorization", "Basic $auth")
        }
    }
    class VpnRequired : IllegalStateException(tr(R.string.sub_vpn_required))
}
