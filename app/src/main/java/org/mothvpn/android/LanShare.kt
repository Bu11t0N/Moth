package org.mothvpn.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mothvpn.core.LanProxy
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/** State of the sharing proxy ([LanProxy]) of the running tunnel, shown in Settings. */
object LanShare {
    sealed interface State {
        data object Off : State
        data class Running(val port: Int) : State
        /** The port is taken by another app: the VPN runs without the sharing proxy. */
        data class PortBusy(val port: Int) : State
    }
    private val mutable = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = mutable.asStateFlow()
    internal fun set(value: State) { mutable.value = value }

    /** True when [port] can be listened on all interfaces right now (checked before the tunnel starts, so a busy port never breaks the VPN). */
    fun portFree(port: Int): Boolean = runCatching {
        ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port)) }; true
    }.getOrDefault(false)

    /** Interface name to address pairs of the phone (all of them; [LanProxy] filters). */
    fun interfaces(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().mapNotNull { a -> a.hostAddress?.let { nif.name to it } } }
    }.getOrDefault(emptyList())

    /** The phone's own addresses outside the tunnel, refused by the sharing proxy so apps on the phone cannot use it. */
    fun ownAddresses(): List<String> = interfaces().filter { (name, _) -> !name.startsWith("tun") }.map { it.second }
}
