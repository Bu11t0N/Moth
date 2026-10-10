package org.routevpn.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import io.nekohasekai.libbox.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface as JavaInterface
import java.util.concurrent.Executors

enum class TunnelState { OFF, STARTING, RUNNING, STOPPING, ERROR }
object TunnelStatus {
    internal val mutable = MutableStateFlow(TunnelState.OFF)
    val state = mutable.asStateFlow()
}

class TunnelService : VpnService(), PlatformInterface {
    private val executor = Executors.newSingleThreadExecutor()
    private var box: BoxService? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopped = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            TunnelStatus.mutable.value = TunnelState.STOPPING
            executor.execute { closeTunnel(); TunnelStatus.mutable.value = TunnelState.OFF; stopSelf(startId) }
        } else if (intent?.action == "START") {
            val config = intent.getStringExtra("config") ?: return START_NOT_STICKY
            foreground()
            TunnelStatus.mutable.value = TunnelState.STARTING
            executor.execute {
                try {
                    closeTunnel()
                    go.Seq.setContext(applicationContext)
                    val setup = SetupOptions().apply { basePath = filesDir.absolutePath; workingPath = filesDir.absolutePath; tempPath = cacheDir.absolutePath; fixAndroidStack = true }
                    Libbox.setup(setup)
                    Libbox.checkConfig(config)
                    box = Libbox.newService(config, this)
                    box!!.start()
                    if (!stopped) TunnelStatus.mutable.value = TunnelState.RUNNING
                } catch (_: Exception) {
                    closeTunnel()
                    TunnelStatus.mutable.value = TunnelState.ERROR
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn", "VPN-подключение", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "vpn").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Moth")
            .setContentText("VPN запущен — управление в приложении").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
        startForeground(1, notification)
    }
    private fun closeTunnel() {
        runCatching { box?.close() }; box = null
        runCatching { tun?.close() }; tun = null
    }
    override fun onRevoke() { stopped = true; executor.execute { closeTunnel(); TunnelStatus.mutable.value = TunnelState.OFF; stopSelf() } }
    override fun onDestroy() {
        stopped = true
        executor.execute { closeTunnel(); if (TunnelStatus.mutable.value != TunnelState.ERROR) TunnelStatus.mutable.value = TunnelState.OFF }
        executor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun openTun(options: TunOptions): Int {
        check(!stopped) { "Service stopped" }
        val b = Builder().setSession("Moth").setMtu(options.mtu).setBlocking(false)
        fun addAddresses(it: RoutePrefixIterator) { while (it.hasNext()) { val p = it.next(); b.addAddress(p.address(), p.prefix()) } }
        fun addRoutes(it: RoutePrefixIterator) { while (it.hasNext()) { val p = it.next(); b.addRoute(p.address(), p.prefix()) } }
        addAddresses(options.inet4Address); addAddresses(options.inet6Address)
        addRoutes(options.inet4RouteRange); addRoutes(options.inet6RouteRange)
        b.addDnsServer(options.dnsServerAddress.value)
        tun = b.establish() ?: error("VPN permission unavailable")
        return tun!!.fd
    }
    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) { check(protect(fd)) { "Cannot protect upstream socket" } }
    override fun writeLog(message: String?) { /* Never retain core logs containing credentials. */ }
    override fun useProcFS() = false
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): Int = -1
    override fun packageNameByUid(uid: Int): String = packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
    override fun uidByPackageName(packageName: String): Int = packageManager.getApplicationInfo(packageName, 0).uid
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val network = connectivity.allNetworks.firstOrNull { n -> connectivity.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) } == true }
        val name = network?.let { connectivity.getLinkProperties(it)?.interfaceName }.orEmpty()
        val index = runCatching { JavaInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        listener.updateDefaultInterface(name, index, network?.let { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } == true, false)
    }
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) = Unit
    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = connectivity.allNetworks.mapNotNull { n ->
            val caps = connectivity.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            val props = connectivity.getLinkProperties(n) ?: return@mapNotNull null
            val java = props.interfaceName?.let { JavaInterface.getByName(it) } ?: return@mapNotNull null
            NetworkInterface().apply {
                name = java.name; index = java.index; mtu = java.mtu
                flags = 1 or 64 or 4096
                type = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) Libbox.InterfaceTypeWIFI else Libbox.InterfaceTypeCellular
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                addresses = Strings(props.linkAddresses.map { "${it.address.hostAddress!!.substringBefore('%')}/${it.prefixLength}" })
                dnsServer = Strings(props.dnsServers.mapNotNull { it.hostAddress })
            }
        }.iterator()
        return object : NetworkInterfaceIterator { override fun hasNext() = list.hasNext(); override fun next() = list.next() }
    }
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState = Libbox.newWIFIState("", "")
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) = Unit
}

private class Strings(values: List<String>) : StringIterator {
    private val size = values.size
    private val iterator = values.iterator()
    override fun hasNext() = iterator.hasNext()
    override fun next() = iterator.next()
    override fun len() = size
}

