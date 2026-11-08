package org.mothvpn.android

import org.mothvpn.core.SubscriptionTunnel
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Never falls back to the physical network, even if the VPN stops during a redirect or a chained provider download. */
object TunnelDownloads {
    init {
        // This application owns its Authenticator. Never supply the session secret to an origin,
        // remote proxy or a stale local session. Android's HTTPS implementation uses this for CONNECT.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val active = session ?: return null
                if (!ready() || requestorType != RequestorType.PROXY || requestingSite?.hostAddress != "127.0.0.1" || requestingHost !in setOf("127.0.0.1", "localhost") || requestingPort != active.port || !requestingScheme.equals("basic", true)) return null
                return PasswordAuthentication(SubscriptionTunnel.USER, active.password.toCharArray())
            }
        })
    }
    @Volatile internal var session: SubscriptionTunnel? = null
    fun ready() = TunnelStatus.state.value == TunnelState.RUNNING && session != null
    fun requireSession(): SubscriptionTunnel = session?.takeIf { ready() } ?: throw VpnRequired()
    fun open(url: URL, via: SubscriptionTunnel): HttpsURLConnection {
        if (!ready() || session !== via) throw VpnRequired()
        return url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", via.port))) as HttpsURLConnection
    }
    class VpnRequired : IllegalStateException(tr(R.string.sub_vpn_required))
}
