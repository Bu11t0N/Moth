package org.mothvpn.android

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
import org.mothvpn.core.ClientSettings
import org.mothvpn.core.Profile
import org.mothvpn.core.TunnelConfig
import org.mothvpn.core.LocalProxyConfig
import org.mothvpn.core.ProbePolicy
import org.mothvpn.core.ProbeOrder
import kotlin.concurrent.withLock
import org.mothvpn.core.ProtectPolicy
import java.net.ServerSocket
import java.util.UUID

enum class TunnelState { OFF, STARTING, RUNNING, STOPPING, ERROR }
object TunnelStatus {
    internal val mutable = MutableStateFlow(TunnelState.OFF)
    val state = mutable.asStateFlow()
    internal val activeMutable = MutableStateFlow("")
    val activeProfile = activeMutable.asStateFlow()
}

class TunnelService : VpnService(), PlatformInterface {
    private val executor = Executors.newSingleThreadExecutor()
    private var box: BoxService? = null
    private var xray: MothXrayService? = null
    private val probes = Executors.newSingleThreadExecutor() // coordinator: runs one probe batch at a time
    private val requests = java.util.concurrent.ConcurrentHashMap.newKeySet<ProxyRequest>()
    private val tcpSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
    private val usedPorts = HashSet<Int>()
    private val coreStartLock = java.util.concurrent.locks.ReentrantLock(true) // local probe cores are started one at a time; requests still run in parallel
    private val healthExecutor = Executors.newSingleThreadScheduledExecutor()
    private val lookupExecutor = Executors.newCachedThreadPool()
    private var healthTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val generation = AtomicLong()
    private var probeCancel = AtomicBoolean(false)
    private var initialized = false
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopped = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d("Tunnel", "onStartCommand action=${intent?.action} state=${TunnelStatus.state.value}")
        if (intent?.action == "STOP") {
            Logger.i("Tunnel", "stop requested (state=${TunnelStatus.state.value})")
            cancelProbes()
            TunnelStatus.mutable.value = TunnelState.STOPPING
            executor.execute { closeTunnel(); Logger.i("Tunnel", "VPN disconnected (tunnel stopped)"); TunnelStatus.mutable.value = TunnelState.OFF; stopSelf(startId) }
        } else if (intent?.action == "START") {
            val id = intent.getStringExtra("profile") ?: run { Logger.w("Tunnel", "START without a profile ignored"); return START_NOT_STICKY }
            Logger.i("Tunnel", "VPN connect requested (previous state=${TunnelStatus.state.value})")
            foreground()
            TunnelStatus.mutable.value = TunnelState.STARTING
            executor.execute {
                var stage = "init"
                try {
                    closeTunnel()
                    initCore()
                    stage = "load settings/profile"
                    val settings = SettingsStore(this).load()
                    var profile = SubscriptionStore(this).load()?.profiles?.firstOrNull { it.id == id } ?: error("Missing profile")
                    Logger.i("Tunnel", "start: ${describe(profile)} network=${NetworkTracker.read(connectivity).kind} mtu=${settings.mtu} ipv6=${settings.ipv6} health=${settings.healthEnabled}")
                    if (profile.engine == "xray") {
                        stage = "xray start"
                        val port = freePort(); val user = UUID.randomUUID().toString(); val password = UUID.randomUUID().toString()
                        xray = Libbox.mothStartXray(LocalProxyConfig.xray(profile, port, user, password, settings), this)
                        Logger.d("Tunnel", "xray started")
                        profile = LocalProxyConfig.bridge(profile, port, user, password)
                    }
                    stage = "build config"
                    val config = TunnelConfig.create(profile, settings)
                    stage = "Libbox.checkConfig"
                    Libbox.checkConfig(config)
                    stage = "Libbox.newService"
                    box = Libbox.newService(config, this)
                    stage = "BoxService.start"
                    box!!.start()
                    Logger.i("Tunnel", "VPN connected (stopped=$stopped, engine=${profile.engine})")
                    if (!stopped) {
                        TunnelStatus.mutable.value = TunnelState.RUNNING
                        TunnelStatus.activeMutable.value = id
                        Diagnostics.healthMutable.value = HealthResult(message = if (settings.healthEnabled) tr(R.string.msg_checking_internet_access_through_vpn_120) else tr(R.string.msg_checks_are_disabled_in_settings_121))
                        if (settings.healthEnabled) healthTask = healthExecutor.scheduleWithFixedDelay({ checkHealth(settings) }, 2, settings.healthIntervalSeconds.toLong(), TimeUnit.SECONDS)
                    }
                } catch (t: Throwable) {
                    Logger.e("Tunnel", "VPN connect failed at stage '$stage' (state -> ERROR)", t)
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
            if (Diagnostics.running.value) { Logger.w("Probe", "request ignored: a check is already running"); return START_NOT_STICKY }
            foreground(tr(R.string.msg_checking_vpn_servers_manage_in_the_app_122))
            probeCancel = AtomicBoolean(false)
            val cancel = probeCancel
            val id = intent?.getStringExtra("profile")
            val speed = intent?.action == "SPEED"
            val order = intent?.getStringArrayListExtra("order").orEmpty()
            Diagnostics.runningMutable.value = true
            Logger.i("Probe", "${if (speed) "speed test" else "probe"} requested for ${if (id == null) "all profiles" else "one profile"} requestedNetwork=${intent?.getStringExtra("network")}")
            if (speed) Diagnostics.speedMutable.value = tr(R.string.msg_download_test_up_to_1_mib_123)
            executor.execute {
                try { if (speed || SettingsStore(this).load().probeMode != "tcp") initCore(); probes.execute { probeProfiles(id, speed, cancel, intent?.getStringExtra("network"), order) } }
                catch (t: Throwable) { Logger.e("Probe", "probe setup failed (initCore or scheduling)", t); Diagnostics.runningMutable.value = false; if (TunnelStatus.state.value == TunnelState.OFF) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    private fun foreground(text: String = tr(R.string.msg_vpn_is_running_manage_in_the_app_124)) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn", tr(R.string.msg_vpn_connection_125), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "vpn").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle(tr(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, tr(R.string.msg_disconnect_126), stop).build()).build()
        startForeground(1, notification)
    }
    private fun closeTunnel() {
        Logger.d("Tunnel", "closeTunnel (box=${box != null}, xray=${xray != null})")
        generation.incrementAndGet(); healthTask?.cancel(true); healthTask = null
        TunnelStatus.activeMutable.value = ""
        Diagnostics.healthMutable.value = HealthResult()
        runCatching { box?.close() }.onFailure { Logger.w("Core", "closing the core failed", it) }; box = null
        runCatching { tun?.close() }.onFailure { Logger.w("Tunnel", "closing the tun device failed", it) }; tun = null
        runCatching { xray?.close() }.onFailure { Logger.w("Core", "closing the xray core failed", it) }; xray = null
    }
    private fun initCore() {
        if (initialized) return
        try {
            Logger.d("Core", "initCore: loading libbox")
            go.Seq.setContext(applicationContext)
            Libbox.setup(SetupOptions().apply { basePath = filesDir.absolutePath; workingPath = filesDir.absolutePath; tempPath = cacheDir.absolutePath; fixAndroidStack = true })
            initialized = true
            Logger.i("Core", "initCore ok")
        } catch (t: Throwable) { Logger.e("Core", "initCore failed", t); throw t }
    }
    /** Unique loopback port per concurrent probe: remembered until the probe ends so two tasks never get the same one. */
    private fun allocatePort(): Int = synchronized(usedPorts) {
        repeat(20) {
            val p = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (usedPorts.add(p)) return p
        }
        error("No free local port")
    }
    private fun releasePort(port: Int) { synchronized(usedPorts) { usedPorts.remove(port) } }
    private fun freePort() = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun cancelProbes() { probeCancel.set(true); requests.forEach { it.cancel() }; tcpSockets.forEach { s -> runCatching { s.close() } } }
    private fun checkHealth(settings: ClientSettings) {
        val current = generation.get()
        if (TunnelStatus.state.value != TunnelState.RUNNING) return
        val previous = Diagnostics.health.value
        Diagnostics.healthMutable.value = previous.copy(checking = true)
        val next = try {
            var ms: Long? = null
            for (url in ProbePolicy.urls(settings)) {
                if (generation.get() != current) return
                try { ms = Diagnostics.checkTunnel(connectivity, url, settings.timeoutMs.toLong()); Logger.v("Health", "ok via ${Diagnostics.describeUrl(url)} in $ms ms"); break }
                catch (e: Exception) { Logger.w("Health", "check via ${Diagnostics.describeUrl(url)} failed", e) }
            }
            check(ms != null)
            if (previous.failures > 0) Logger.i("Health", "internet access through the VPN recovered after ${previous.failures} failed check(s)")
            HealthResult(latencyMs = ms, message = tr(R.string.msg_internet_access_through_vpn_is_available_127))
        } catch (e: Exception) { Logger.e("Health", "internet check through VPN failed (consecutive failures=${previous.failures + 1})", e); HealthResult(message = tr(R.string.msg_internet_check_through_vpn_failed_128), failures = previous.failures + 1) }
        if (generation.get() == current && TunnelStatus.state.value == TunnelState.RUNNING) Diagnostics.healthMutable.value = next
    }
    private fun probeProfiles(id: String?, speed: Boolean, cancelled: AtomicBoolean, wantedNetwork: String?, shownOrder: List<String>) {
        try {
            val settings = SettingsStore(this).load()
            val access = NetworkTracker.read(connectivity)
            Logger.i("Probe", "probeProfiles: speed=$speed mode=${settings.probeMode} network=${access.kind} available=${access.available} wanted=$wantedNetwork")
            if (!access.available || (wantedNetwork != null && wantedNetwork != access.kind)) { Logger.w("Probe", "skipped: network unavailable or changed"); return }
            val profiles = SubscriptionStore(this).load()?.profiles.orEmpty().filter { id == null || it.id == id }.let { all -> ProbeOrder.arrange(all, shownOrder) { it.id } } // list order: the pool queue is FIFO
            val pool = Executors.newFixedThreadPool(if (speed) 1 else settings.probeParallel.coerceAtMost(profiles.size).coerceAtLeast(1))
            fun probeOne(profile: Profile) {
                if (cancelled.get() || NetworkTracker.read(connectivity).handle != access.handle) return
                val key = "${access.kind}:${profile.id}"
                val method = if (!speed && settings.probeMode == "tcp") "TCP" else "HTTPS"
                fun result(ms: Long? = null, error: String? = null, checking: Boolean = false, wasCancelled: Boolean = false) = Diagnostics.result(key,
                    ProbeResult(checking = checking, latencyMs = ms, error = error, method = method, networkHandle = access.handle, cancelled = wasCancelled))
                if (!speed) result(checking = true)
                val label = describe(profile)
                var stage = "start"
                Logger.i("Probe", "profile ${profiles.indexOf(profile)} method=$method $label network=${access.kind}")
                var allocated = 0
                var local: BoxService? = null; var xr: MothXrayService? = null
                try {
                    if (method == "TCP") {
                        val endpoint = ProbePolicy.tcpEndpoint(profile)
                        stage = "tcp"
                        if (endpoint == null) result(error = tr(R.string.msg_udp_protocol_choose_an_https_check_129))
                        else { val ms = tcpProbe(endpoint, settings.probeTimeoutMs.toLong(), cancelled); Logger.i("Probe", "TCP ok in $ms ms ($label)"); if (!cancelled.get() && NetworkTracker.read(connectivity).handle == access.handle) result(ms = ms) }
                        return
                    }
                    stage = "free port"
                    val port = allocatePort(); allocated = port; val user = UUID.randomUUID().toString(); val password = UUID.randomUUID().toString()
                    if (profile.engine == "xray") { stage = "xray start"; coreStartLock.withLock { xr = Libbox.mothStartXray(LocalProxyConfig.xray(profile, port, user, password, settings), this) } }
                    else {
                        stage = "build config"
                        val config = LocalProxyConfig.singBox(profile, port, user, password, settings)
                        stage = "Libbox.checkConfig"; Libbox.checkConfig(config)
                        coreStartLock.withLock {
                            stage = "Libbox.newService"; local = Libbox.newService(config, this)
                            stage = "BoxService.start"; local!!.start()
                        }
                    }
                    stage = "local proxy ready"
                    Logger.d("Probe", "local proxy started ($label)")
                    var measured: Pair<Long, Long>? = null
                    var last: Throwable? = null
                    for (url in if (speed) listOf("https://speed.cloudflare.com/__down?bytes=1048576") else ProbePolicy.urls(settings)) {
                        if (cancelled.get() || NetworkTracker.read(connectivity).handle != access.handle) break
                        val test = ProxyRequest(this, port, user, password, if (speed) 30_000L else settings.probeTimeoutMs.toLong(), cancelled)
                        requests.add(test)
                        stage = "request ${Diagnostics.describeUrl(url)}"
                        try { measured = test.run(url, if (speed) 1048576 else 0); Logger.i("Probe", "request ok ${Diagnostics.describeUrl(url)} ${measured?.first}${if (speed) " bytes" else " ms"} ($label)"); break }
                        catch (e: Throwable) {
                            last = e
                            Logger.w("Probe", "request failed url=${Diagnostics.describeUrl(url)} handshakeStage=${test.stage} method=$method network=${access.kind} ($label)", e)
                        } finally {
                            requests.remove(test)
                        }
                    }
                    if (!cancelled.get() && NetworkTracker.read(connectivity).handle == access.handle) {
                        val measurement = measured ?: throw (last ?: java.net.SocketTimeoutException())
                        if (speed) Diagnostics.speedMutable.value = tr(R.string.msg_profile_name_1f_mbit_s_2f_mib_130, profile.name, measurement.first * 8000.0 / measurement.second, measurement.first / 1048576.0)
                        else result(ms = measurement.first)
                    }
                } catch (e: Throwable) {
                    Logger.e("Probe", "probe failed at stage '$stage' method=$method network=${access.kind} cancelled=${cancelled.get()} ($label)", e)
                    if (!cancelled.get() && NetworkTracker.read(connectivity).handle == access.handle) {
                        if (speed) Diagnostics.speedMutable.value = tr(R.string.msg_speed_test_failed_131)
                        else result(error = Diagnostics.friendly(e))
                    }
                } finally {
                    if (allocated != 0) releasePort(allocated)
                    runCatching { local?.close() }.onFailure { Logger.w("Probe", "closing the local core failed", it) }; runCatching { xr?.close() }.onFailure { Logger.w("Probe", "closing the local xray failed", it) }
                    if (!speed && Diagnostics.results.value[key]?.checking == true) result(error = tr(R.string.msg_check_cancelled_46), wasCancelled = true)
                }
            }
            val futures = profiles.map { p -> pool.submit { try { probeOne(p) } catch (t: Throwable) { Logger.e("Probe", "probe task crashed", t) } } }
            try { futures.forEach { runCatching { it.get() } } } finally { pool.shutdownNow() }
            if (speed && (cancelled.get() || NetworkTracker.read(connectivity).handle != access.handle)) Diagnostics.speedMutable.value = tr(R.string.msg_test_cancelled_132)
        } catch (t: Throwable) {
            Logger.e("Probe", "probeProfiles aborted", t)
        } finally {
            Diagnostics.runningMutable.value = false
            Logger.d("Probe", "probeProfiles finished")
            if (TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR)) stopSelf()
        }
    }
    private fun tcpProbe(endpoint: Pair<String, Int>, timeoutMs: Long, cancelled: AtomicBoolean): Long {
        val network = NetworkTracker.physical(connectivity) ?: run { Logger.w("Probe", "tcpProbe: no physical network"); error(tr(R.string.msg_no_network_20)) }
        var stage = "dns lookup"
        val start = System.nanoTime()
        val deadline = start + timeoutMs * 1_000_000L
        fun remaining() = ((deadline - System.nanoTime()) / 1_000_000).toInt().also { if (it <= 0 || cancelled.get()) throw java.net.SocketTimeoutException() }
        val lookup = lookupExecutor.submit<java.net.InetAddress?> { network.getAllByName(endpoint.first).let { addresses -> addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses.firstOrNull() } }
        try {
            val address = try { lookup.get(remaining().toLong(), TimeUnit.MILLISECONDS) ?: error(tr(R.string.msg_no_address_found_133)) }
                finally { lookup.cancel(true) }
            Logger.d("Probe", "tcpProbe: dns ok in ${(System.nanoTime() - start) / 1_000_000} ms (family=${if (address is java.net.Inet4Address) "v4" else "v6"}) port=${endpoint.second}")
            stage = "tcp connect"
            java.net.Socket().use { socket ->
                tcpSockets.add(socket)
                try {
                    val tunnelActive = TunnelStatus.state.value !in setOf(TunnelState.OFF, TunnelState.ERROR)
                    val protectedOk = protect(socket)
                    if (!protectedOk) Logger.w("Probe", "tcpProbe: protect(socket) returned false (tunnelActive=$tunnelActive)")
                    check(ProtectPolicy.allows(protectedOk, tunnelActive)) { "protect(socket) failed" }
                    network.bindSocket(socket); socket.connect(java.net.InetSocketAddress(address, endpoint.second), remaining()); return (System.nanoTime() - start) / 1_000_000 }
                finally { tcpSockets.remove(socket) }
            }
        } catch (t: Throwable) {
            Logger.w("Probe", "tcpProbe failed at stage '$stage' after ${(System.nanoTime() - start) / 1_000_000} ms (timeout=${timeoutMs}ms)", t)
            throw t
        }
    }
    private fun describe(profile: Profile) = "protocol=${profile.protocol} engine=${profile.engine}"
    override fun onRevoke() { Logger.w("Tunnel", "VPN revoked by the system or another VPN app (state=${TunnelStatus.state.value})"); stopped = true; cancelProbes(); executor.execute { closeTunnel(); TunnelStatus.mutable.value = TunnelState.OFF; stopSelf() } }
    override fun onDestroy() {
        Logger.i("Tunnel", "service destroyed (state=${TunnelStatus.state.value})")
        stopped = true
        cancelProbes(); healthExecutor.shutdownNow(); lookupExecutor.shutdownNow(); probes.shutdown()
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
        tun = b.establish() ?: run { Logger.e("Tunnel", "establish() returned null: VPN permission revoked or unavailable"); error("VPN permission unavailable") }
        Logger.i("Tunnel", "tun device established (mtu=${options.mtu})")
        return tun!!.fd
    }
    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) {
        check(!stopped) { "Service stopped" }
        val protectedOk = protect(fd)
        val tunnelActive = TunnelStatus.state.value !in setOf(TunnelState.OFF, TunnelState.ERROR)
        if (!protectedOk) Logger.w("Core", "autoDetectInterfaceControl: protect(fd) returned false (tunnelActive=$tunnelActive, tunnelState=${TunnelStatus.state.value})")
        check(ProtectPolicy.allows(protectedOk, tunnelActive)) { "Cannot protect upstream socket" }
    }
    override fun writeLog(message: String?) { /* Never retain core logs containing credentials. */ }
    override fun useProcFS() = false
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): Int = -1
    override fun packageNameByUid(uid: Int): String = packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
    override fun uidByPackageName(packageName: String): Int = packageManager.getApplicationInfo(packageName, 0).uid
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val network = NetworkTracker.physical(connectivity)
        val name = network?.let { connectivity.getLinkProperties(it)?.interfaceName }.orEmpty()
        val index = runCatching { JavaInterface.getByName(name)?.index ?: -1 }.onFailure { Logger.w("Core", "interface lookup failed", it) }.getOrDefault(-1)
        Logger.d("Core", "default interface monitor: ${if (network == null) "no physical network" else "found"} index=$index")
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

