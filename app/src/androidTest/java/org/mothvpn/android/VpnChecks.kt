package org.mothvpn.android

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Shared E2E checks for the VPN network itself (not just the app's UI state). */
object VpnChecks {
    /** Tun device descriptors still open in this (app) process, e.g. "12 -> /dev/tun". */
    fun tunFds(): List<String> = (java.io.File("/proc/self/fd").listFiles() ?: emptyArray()).mapNotNull { f ->
        runCatching { android.system.Os.readlink(f.path) }.getOrNull()?.takeIf { it.contains("tun") }?.let { "${f.name} -> $it" }
    }
    @Suppress("DEPRECATION")
    fun vpnUp(cm: ConnectivityManager) = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    /** After Disconnect the VPN network must disappear promptly: Android drops it once every tun descriptor is closed. */
    fun awaitVpnGone(cm: ConnectivityManager, log: (String) -> Unit, ms: Long = 5_000, onFail: () -> Unit = {}) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < ms) {
            if (!vpnUp(cm)) { log("VPN network gone after ${System.currentTimeMillis() - start} ms"); return }
            Thread.sleep(100)
        }
        val fds = tunFds()
        log("VPN network still up $ms ms after disconnect; tun fds: $fds; state=${TunnelStatus.state.value}")
        onFail()
        throw AssertionError("VPN network still up $ms ms after disconnect (tun fds: $fds)")
    }
}
