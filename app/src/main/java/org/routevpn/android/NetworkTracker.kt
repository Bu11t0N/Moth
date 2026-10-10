package org.routevpn.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccessNetwork(val kind: String = "wifi", val handle: Long = 0, val available: Boolean = false)
class NetworkTracker(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(read(manager))
    val state = mutable.asStateFlow()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { mutable.value = read(manager) }
        override fun onLost(network: Network) { mutable.value = read(manager) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { mutable.value = read(manager) }
    }
    init { manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())) }
    override fun close() { manager.unregisterNetworkCallback(callback) }
    companion object {
        fun physical(manager: ConnectivityManager): Network? {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            val kind = when { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkCapabilities.TRANSPORT_WIFI
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkCapabilities.TRANSPORT_CELLULAR
                else -> null }
            val candidates = manager.allNetworks.filter { n -> manager.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true }
            return candidates.firstOrNull { it == manager.activeNetwork } ?: candidates.firstOrNull { n -> kind != null && manager.getNetworkCapabilities(n)?.hasTransport(kind) == true }
                ?: candidates.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } ?: candidates.firstOrNull()
        }
        fun read(manager: ConnectivityManager): AccessNetwork {
            val network = physical(manager) ?: return AccessNetwork()
            return AccessNetwork(if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) "mobile" else "wifi", network.networkHandle, true)
        }
    }
}
