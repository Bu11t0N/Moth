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
        log("net interfaces: ${java.io.File("/sys/class/net").list()?.sorted()}")
        // Threads blocked in a syscall can pin a closed tun file: comm, wchan and the raw syscall line of every thread.
        (java.io.File("/proc/self/task").listFiles() ?: emptyArray()).forEach { t ->
            fun r(n: String) = runCatching { java.io.File(t, n).readText().trim() }.getOrDefault("?")
            val sc = r("syscall")
            if (sc != "running" && !sc.startsWith("-1")) log("task ${t.name} ${r("comm")} wchan=${r("wchan")} syscall=$sc")
        }
        onFail()
        throw AssertionError("VPN network still up $ms ms after disconnect (tun fds: $fds)")
    }
    /** The foreground "disconnecting" notification must be gone shortly after Disconnect. */
    fun awaitNotificationGone(context: android.content.Context, log: (String) -> Unit, ms: Long = 3_000) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < ms) {
            if (nm.activeNotifications.isEmpty()) { log("notification gone after ${System.currentTimeMillis() - start} ms"); return }
            Thread.sleep(100)
        }
        val ids = nm.activeNotifications.map { it.id }
        log("notifications still shown $ms ms after disconnect: ids=$ids")
        throw AssertionError("notification still shown $ms ms after disconnect (ids=$ids)")
    }
}
