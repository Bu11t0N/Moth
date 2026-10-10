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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.routevpn.core.ClientSettings
import org.routevpn.core.Profile
import org.routevpn.core.TunnelConfig
import org.routevpn.core.LocalProxyConfig
import java.net.ServerSocket
import java.util.UUID

enum class TunnelState { OFF, STARTING, RUNNING, STOPPING, ERROR }
object TunnelStatus {
    internal val mutable = MutableStateFlow(TunnelState.OFF)
    val state = mutable.asStateFlow()
}

class TunnelService : VpnService(), PlatformInterface {
    private val executor = Executors.newSingleThreadExecutor()
    private var box: BoxService? = null
    private var xray: MothXrayService? = null
    private val probes = Executors.newSingleThreadExecutor()
    private val healthExecutor = Executors.newSingleThreadScheduledExecutor()
    private var healthTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val generation = AtomicLong()
    private var probeCancel = AtomicBoolean(false)
    @Volatile private var request: ProxyRequest? = null
    private var initialized = false
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopped = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            cancelProbes()
            TunnelStatus.mutable.value = TunnelState.STOPPING
            executor.execute { closeTunnel(); TunnelStatus.mutable.value = TunnelState.OFF; stopSelf(startId) }
        } else if (intent?.action == "START") {
            val id = intent.getStringExtra("profile") ?: return START_NOT_STICKY
            foreground()
            TunnelStatus.mutable.value = TunnelState.STARTING
            executor.execute {
                try {
                    closeTunnel()
                    initCore()
                    val settings = SettingsStore(this).load()
                    var profile = SubscriptionStore(this).load()?.profiles?.firstOrNull { it.id == id } ?: error("Missing profile")
                    if (profile.engine == "xray") {
                        val port = freePort(); val user = UUID.randomUUID().toString(); val password = UUID.randomUUID().toString()
                        xray = Libbox.mothStartXray(LocalProxyConfig.xray(profile, port, user, password), this)
                        profile = LocalProxyConfig.bridge(profile, port, user, password)
                    }
                    val config = TunnelConfig.create(profile, settings)
                    Libbox.checkConfig(config)
                    box = Libbox.newService(config, this)
                    box!!.start()
                    if (!stopped) {
                        TunnelStatus.mutable.value = TunnelState.RUNNING
                        Diagnostics.healthMutable.value = HealthResult(message = if (settings.healthEnabled) "Проверяем интернет через VPN…" else "Проверка отключена в настройках")
                        if (settings.healthEnabled) healthTask = healthExecutor.scheduleWithFixedDelay({ checkHealth(settings) }, 2, settings.healthIntervalSeconds.toLong(), TimeUnit.SECONDS)
                    }
                } catch (_: Exception) {
                    closeTunnel()
                    TunnelStatus.mutable.value = TunnelState.ERROR
                    stopSelf(startId)
                }
            }
        } else if (intent?.action == "HEALTH") {
            if (TunnelStatus.state.value == TunnelState.RUNNING) healthExecutor.execute { checkHealth(SettingsStore(this).load()) }
        } else if (intent?.action == "CANCEL_PROBES") {
            cancelProbes()
        } else if (intent?.action in setOf("PROBE", "SPEED")) {
            if (Diagnostics.running.value) return START_NOT_STICKY
            foreground("Проверяем VPN-серверы — управление в приложении")
            probeCancel = AtomicBoolean(false)
            val cancel = probeCancel
            val id = intent?.getStringExtra("profile")
            val speed = intent?.action == "SPEED"
            Diagnostics.runningMutable.value = true
            if (speed) Diagnostics.speedMutable.value = "Тест загрузки: до 1 МиБ…"
            executor.execute {
                try { initCore(); probes.execute { probeProfiles(id, speed, cancel) } }
                catch (_: Exception) { Diagnostics.runningMutable.value = false; if (TunnelStatus.state.value == TunnelState.OFF) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    private fun foreground(text: String = "VPN запущен — управление в приложении") {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn", "VPN-подключение", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "vpn").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Moth")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
        startForeground(1, notification)
    }
    private fun closeTunnel() {
        generation.incrementAndGet(); healthTask?.cancel(true); healthTask = null
        Diagnostics.healthMutable.value = HealthResult()
        runCatching { box?.close() }; box = null
        runCatching { tun?.close() }; tun = null
        runCatching { xray?.close() }; xray = null
    }
    private fun initCore() {
        if (initialized) return
        go.Seq.setContext(applicationContext)
        Libbox.setup(SetupOptions().apply { basePath = filesDir.absolutePath; workingPath = filesDir.absolutePath; tempPath = cacheDir.absolutePath; fixAndroidStack = true })
        initialized = true
    }
    private fun freePort() = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun cancelProbes() { probeCancel.set(true); request?.cancel() }
    private fun checkHealth(settings: ClientSettings) {
        val current = generation.get()
        if (TunnelStatus.state.value != TunnelState.RUNNING) return
        val previous = Diagnostics.health.value
        Diagnostics.healthMutable.value = previous.copy(checking = true)
        val next = try {
            val ms = Diagnostics.checkTunnel(connectivity, settings.probeUrl, settings.timeoutSeconds)
            HealthResult(latencyMs = ms, message = "Интернет через VPN доступен")
        } catch (_: Exception) { HealthResult(message = "Проверка интернета через VPN не прошла", failures = previous.failures + 1) }
        if (generation.get() == current && TunnelStatus.state.value == TunnelState.RUNNING) Diagnostics.healthMutable.value = next
    }
    private fun probeProfiles(id: String?, speed: Boolean, cancelled: AtomicBoolean) {
        try {
            val settings = SettingsStore(this).load()
            val profiles = SubscriptionStore(this).load()?.profiles.orEmpty().filter { id == null || it.id == id }
            for (profile in profiles) {
                if (cancelled.get()) break
                if (!speed) Diagnostics.result(profile.id, ProbeResult(checking = true))
                var local: BoxService? = null; var xr: MothXrayService? = null
                try {
                    val port = freePort(); val user = UUID.randomUUID().toString(); val password = UUID.randomUUID().toString()
                    if (profile.engine == "xray") xr = Libbox.mothStartXray(LocalProxyConfig.xray(profile, port, user, password), this)
                    else {
                        val config = LocalProxyConfig.singBox(profile, port, user, password)
                        Libbox.checkConfig(config); local = Libbox.newService(config, this); local.start()
                    }
                    val test = ProxyRequest(this, port, user, password, if (speed) 30 else settings.timeoutSeconds, cancelled)
                    request = test
                    val result = test.run(if (speed) "https://speed.cloudflare.com/__down?bytes=1048576" else settings.probeUrl, if (speed) 1048576 else 0)
                    if (!cancelled.get()) {
                        if (speed) Diagnostics.speedMutable.value = "${profile.name}: %.1f Мбит/с · %.2f МиБ".format(java.util.Locale.US, result.first * 8000.0 / result.second, result.first / 1048576.0)
                        else Diagnostics.result(profile.id, ProbeResult(latencyMs = result.first))
                    }
                } catch (e: Exception) {
                    if (!cancelled.get()) {
                        if (speed) Diagnostics.speedMutable.value = "Тест скорости не прошёл"
                        else Diagnostics.result(profile.id, ProbeResult(error = Diagnostics.friendly(e)))
                    }
                } finally {
                    request = null
                    runCatching { local?.close() }; runCatching { xr?.close() }
                    if (!speed && Diagnostics.results.value[profile.id]?.checking == true) Diagnostics.result(profile.id, ProbeResult(error = "Проверка отменена"))
                }
                if (speed) break
            }
            if (cancelled.get() && speed) Diagnostics.speedMutable.value = "Тест отменён"
        } finally {
            Diagnostics.runningMutable.value = false
            if (TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR)) stopSelf()
        }
    }
    override fun onRevoke() { stopped = true; cancelProbes(); executor.execute { closeTunnel(); TunnelStatus.mutable.value = TunnelState.OFF; stopSelf() } }
    override fun onDestroy() {
        stopped = true
        cancelProbes(); healthExecutor.shutdownNow(); probes.shutdown()
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
    override fun autoDetectInterfaceControl(fd: Int) { check(!stopped && protect(fd)) { "Cannot protect upstream socket" } }
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

