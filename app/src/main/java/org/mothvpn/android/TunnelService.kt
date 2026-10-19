package org.mothvpn.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.mothvpn.core.TrafficFormat
import org.mothvpn.core.TrafficHistory
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
import org.mothvpn.core.ProbeErrorKind
import org.mothvpn.core.TunnelConfig
import org.mothvpn.core.LocalProxyConfig
import org.mothvpn.core.LocalHop
import org.mothvpn.core.DpiTricks
import org.mothvpn.core.ProbePolicy
import org.mothvpn.core.ProbeOrder
import kotlin.concurrent.withLock
import org.mothvpn.core.ProtectPolicy
import org.mothvpn.core.ServerPicker
import org.mothvpn.core.SplitTunnel
import java.net.ServerSocket
import java.util.UUID

/** BLOCKED: kill switch active, the core failed and the VPN interface stays up without a core, so no traffic leaves the phone. */
enum class TunnelState { OFF, STARTING, RUNNING, STOPPING, ERROR, BLOCKED }
object TunnelStatus {
    internal val mutable = MutableStateFlow(TunnelState.OFF)
    val state = mutable.asStateFlow()
    internal val activeMutable = MutableStateFlow("")
    val activeProfile = activeMutable.asStateFlow()
    /** True while the kill switch's blocking interface is up (BLOCKED, and the reconnect attempts made from it). */
    internal val blockingMutable = MutableStateFlow(false)
    val blocking = blockingMutable.asStateFlow()
    /** Connected, but the phone has no network at all (set by the service from its default-network callback). */
    internal val offlineMutable = MutableStateFlow(org.mothvpn.core.OfflineStatus.Kind.ONLINE)
    val offline = offlineMutable.asStateFlow()
    /** Live session numbers while connected (same sample as the notification: speed, session traffic, time); null when not connected. */
    internal val statsMutable = MutableStateFlow<org.mothvpn.core.TrafficFormat.Stats?>(null)
    val stats = statsMutable.asStateFlow()
    /** Auto-select found no candidate because the keyword server filter hides every server (it then keeps the selected server). */
    internal val filterHidesAllMutable = MutableStateFlow(false)
    val filterHidesAll = filterHidesAllMutable.asStateFlow()
    /** Traffic is held by the kill switch: the user sees "Disconnect" and "Traffic blocked", not "Connect". Also when the network is gone with the kill switch on. */
    fun trafficBlocked(state: TunnelState, blocking: Boolean, offline: org.mothvpn.core.OfflineStatus.Kind = this.offline.value) = state == TunnelState.BLOCKED || (blocking && state == TunnelState.STARTING) ||
        (offline == org.mothvpn.core.OfflineStatus.Kind.BLOCKED && state in setOf(TunnelState.RUNNING, TunnelState.STARTING))
    /** Connected without the kill switch while there is no network: "Reconnecting…". */
    fun waitingForNetwork(state: TunnelState, offline: org.mothvpn.core.OfflineStatus.Kind = this.offline.value) = offline == org.mothvpn.core.OfflineStatus.Kind.WAITING && state == TunnelState.RUNNING
}

class TunnelService : VpnService(), PlatformInterface {
    private val executor = Executors.newSingleThreadExecutor()
    private var box: BoxService? = null
    private var xray: MothXrayService? = null
    /** Xray helper making TLS fragments / UDP noise for sing-box profiles (see DpiTricks). */
    private var dpiXray: MothXrayService? = null
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
    /** Newest start id: finish() stops the service only if no command arrived after it (a Connect right after Disconnect must survive). */
    @Volatile private var lastStartId = 0
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        Logger.d("Tunnel", "onStartCommand action=${intent?.action} state=${TunnelStatus.state.value}")
        if (intent?.action == "STOP") {
            Logger.i("Tunnel", "stop requested (state=${TunnelStatus.state.value})")
            cancelProbes()
            TunnelStatus.mutable.value = TunnelState.STOPPING
            stopStats(); foreground()
            cancelRetry()
            executor.execute { closeTunnel(); closeBlock(); Logger.i("Tunnel", "VPN disconnected (tunnel stopped)"); TunnelStatus.mutable.value = TunnelState.OFF; finish() }
        } else if (intent == null || intent.action == null || intent.action == SERVICE_INTERFACE || intent.action == "START") {
            // "START" comes from the app, the tile and the boot receiver; a null intent or android.net.VpnService comes from Always-on VPN.
            val alwaysOn = intent?.action != "START"
            val id = intent?.getStringExtra("profile") ?: VpnLauncher.lastProfileId(this)
            if (id == null) {
                Logger.w("Tunnel", "start without a server ignored (alwaysOn=$alwaysOn)")
                runCatching { foreground() }; if (TunnelStatus.state.value !in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                return START_NOT_STICKY
            }
            if (alwaysOn && TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED)) { Logger.i("Tunnel", "always-on start ignored: already connected"); return START_NOT_STICKY }
            Logger.i("Tunnel", "VPN connect requested (previous state=${TunnelStatus.state.value}, alwaysOn=$alwaysOn)")
            serverName = ""
            TunnelStatus.mutable.value = TunnelState.STARTING
            try { foreground() } catch (e: Exception) { Logger.e("Tunnel", "could not enter the foreground", e) }
            ranking = emptyList(); tried.clear(); manualPick = false; cancelRetry(); killSwitchLive = null
            executor.execute { startTunnel(id, startId, pickBest = true) }
        } else if (intent?.action == "KILL_SWITCH") {
            // Toggled in Settings while connected: applies immediately, no reconnect.
            val on = intent.getBooleanExtra("on", false)
            killSwitchLive = on
            Logger.i("Tunnel", "kill switch ${if (on) "on" else "off"} (state=${TunnelStatus.state.value})")
            // Toggled while there is no network: "Traffic blocked" <-> "Reconnecting…" everywhere.
            if (TunnelStatus.offline.value != org.mothvpn.core.OfflineStatus.Kind.ONLINE) updateOffline(false)
            if (!on && TunnelStatus.state.value == TunnelState.BLOCKED) {
                cancelRetry()
                executor.execute { if (TunnelStatus.state.value == TunnelState.BLOCKED) { closeBlock(); TunnelStatus.mutable.value = TunnelState.ERROR; finish() } }
            } else if (TunnelStatus.state.value == TunnelState.OFF && !Diagnostics.running.value) stopSelf(startId)
        } else if (intent?.action == "SWITCH") {
            // Manual server pick while connected: restart the core on the new server without stopping the service.
            // The user's choice overrides auto-select (no re-measuring, no failover) until the next regular connect.
            val id = intent.getStringExtra("profile")
            if (id == null || TunnelStatus.state.value !in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.ERROR, TunnelState.BLOCKED) || id == TunnelStatus.activeProfile.value && TunnelStatus.state.value == TunnelState.RUNNING && !intent.getBooleanExtra("force", false)) return START_NOT_STICKY
            Logger.i("Tunnel", "server switch requested (state=${TunnelStatus.state.value})")
            cancelProbes()
            // A reconnect that only applies changed settings keeps the same server and does not pause auto-select.
            if (!intent.getBooleanExtra("settings", false)) manualPick = true
            ranking = emptyList(); tried.clear(); cancelRetry()
            serverName = ""; stopped = false
            TunnelStatus.mutable.value = TunnelState.STARTING
            try { foreground() } catch (e: Exception) { Logger.e("Tunnel", "could not enter the foreground", e) }
            executor.execute { if (TunnelStatus.state.value == TunnelState.STARTING) startTunnel(id, startId, pickBest = false) }
        } else if (intent?.action == "HEALTH") {
            if (TunnelStatus.state.value == TunnelState.RUNNING) healthExecutor.execute { checkHealth(SettingsStore(this).load()) }
        } else if (intent?.action == "CANCEL_PROBES") {
            cancelProbes()
        } else if (intent?.action == "PROBE") {
            if (Diagnostics.running.value) { Logger.w("Probe", "request ignored: a check is already running"); return START_NOT_STICKY }
            foreground() // while connected the notification keeps showing the server name
            probeCancel = AtomicBoolean(false)
            val cancel = probeCancel
            val id = intent?.getStringExtra("profile")
            val order = intent?.getStringArrayListExtra("order").orEmpty()
            Diagnostics.runningMutable.value = true
            Logger.i("Probe", "probe requested for ${if (id != null) "one profile" else if (intent?.getStringArrayListExtra("only").isNullOrEmpty()) "all profiles" else "one subscription"} requestedNetwork=${intent?.getStringExtra("network")}")
            executor.execute {
                try { if (SettingsStore(this).load().probeMode != "tcp") initCore(); probes.execute { probeProfiles(id, cancel, intent?.getStringExtra("network"), order, intent?.getStringArrayListExtra("only").orEmpty().toSet()) } }
                catch (t: Throwable) { Logger.e("Probe", "probe setup failed (initCore or scheduling)", t); Diagnostics.runningMutable.value = false; finish() }
            }
        }
        return START_NOT_STICKY
    }
    @Volatile private var ranking = emptyList<String>()
    @Volatile private var manualPick = false // set by a manual server switch while connected: auto-select is paused for this session
    private val tried = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var activeSettings = ClientSettings()
    /** Runs on [executor]. [pickBest]: with auto-select on, measure all servers first (existing probe code) and use the fastest. */
    private fun startTunnel(requested: String, startId: Int?, pickBest: Boolean) {
        var id = requested
        var stage = "init"
        targetId = requested; activeKind = trackedKind()
        try {
            // Kill switch: hold a blocking interface while the core restarts so traffic never leaves outside the tunnel.
            if (tun != null && killSwitchOn()) openBlock()
            closeTunnel()
            initCore()
            stage = "load settings/profile"
            val settings = SettingsStore(this).load(); activeSettings = settings
            val subscription = SubscriptionStore(this).load() ?: error("Missing subscription")
            if (settings.autoSelect && pickBest && subscription.profiles.size > 1 && !stopped) { stage = "auto select"; id = pickFastest(subscription, settings) ?: id }
            if (stopped || TunnelStatus.state.value != TunnelState.STARTING) { Logger.i("Tunnel", "start abandoned (state=${TunnelStatus.state.value})"); return }
            var profile = subscription.profiles.firstOrNull { it.id == id } ?: error("Missing profile")
            activeEngine = profile.engine; activeKind = trackedKind()
            serverName = profile.name; foreground() // the name is shown in the notification only, never logged
            Logger.i("Tunnel", "start: ${describe(profile)} network=${NetworkTracker.read(connectivity).kind} mtu=${settings.mtu} ipv6=${settings.ipv6} health=${settings.healthEnabled} split=${settings.splitMode} directRu=${settings.directRu} auto=${settings.autoSelect}")
            val ruleSets = if (settings.directRu) { stage = "rule sets"; RuleSetCache.prepare(this, connectivity) } else emptyMap()
            var dpi: LocalHop? = null
            if (profile.engine == "xray") {
                stage = "xray start"
                // Authenticated VLESS bridge (random UUID, UDP inside the stream): no unauthenticated port and no UDP relay on the phone.
                val port = freePort(); val uuid = UUID.randomUUID().toString()
                xray = Libbox.mothStartXray(LocalProxyConfig.xray(profile, port, uuid, "", settings, bridge = true), this)
                Logger.d("Tunnel", "xray started")
                profile = LocalProxyConfig.bridge(profile, port, uuid)
            } else if (DpiTricks.singBoxHelper(settings)) {
                stage = "dpi helper start"
                val hop = LocalHop(freePort(), UUID.randomUUID().toString())
                dpiXray = Libbox.mothStartXray(LocalProxyConfig.dpiHelper(hop.port, hop.uuid, settings), this)
                dpi = hop
                Logger.d("Tunnel", "dpi helper started (fragment=${settings.fragment} noise=${settings.noise})")
            }
            stage = "build config"
            val config = TunnelConfig.create(profile, settings, ruleSets, dpi, appGuard = if (Build.VERSION.SDK_INT >= 29) packageName else null)
            stage = "Libbox.checkConfig"
            Libbox.checkConfig(config)
            stage = "Libbox.newService"
            box = Libbox.newService(config, this)
            stage = "BoxService.start"
            box!!.start()
            Logger.i("Tunnel", "VPN connected (stopped=$stopped, engine=${profile.engine})")
            closeBlock(); cancelRetry()
            if (!stopped) {
                TunnelStatus.mutable.value = TunnelState.RUNNING
                updateOffline(networkState.current != null || NetworkTracker.read(connectivity).available)
                startStats(); foreground() // startStats first: it sets the connect time the notification chronometer counts from
                TunnelStatus.activeMutable.value = id
                Diagnostics.healthMutable.value = HealthResult(message = if (settings.healthEnabled) tr(R.string.msg_checking_internet_access_through_vpn_120) else tr(R.string.msg_checks_are_disabled_in_settings_121))
                if (settings.healthEnabled) healthTask = healthExecutor.scheduleWithFixedDelay({ checkHealth(settings) }, 2, settings.healthIntervalSeconds.toLong(), TimeUnit.SECONDS)
            }
        } catch (t: Throwable) {
            val kill = !stopped && TunnelStatus.state.value == TunnelState.STARTING && killSwitchOn()
            Logger.e("Tunnel", "VPN connect failed at stage '$stage' (state -> ${if (kill) "BLOCKED" else "ERROR"})", t)
            if (kill && openBlock()) {
                closeTunnel()
                TunnelStatus.mutable.value = TunnelState.BLOCKED; serverName = ""
                runCatching { foreground() }
                scheduleRetry()
                return
            }
            closeTunnel(); closeBlock()
            TunnelStatus.mutable.value = TunnelState.ERROR
            finish()
        }
    }
    /** Measures every server with the regular probe (same method as the Servers screen) and returns the fastest one; also saves it as the selection. */
    private fun pickFastest(subscription: SavedSubscription, settings: ClientSettings): String? {
        val access = NetworkTracker.read(connectivity)
        // Only servers the keyword filter shows are candidates (failover walks this ranking too), so a hidden server is never picked.
        val pool = org.mothvpn.core.ServerFilter.pool(subscription.profiles, settings, access.kind) // the filter of the network in use
        TunnelStatus.filterHidesAllMutable.value = pool.isEmpty()
        if (pool.isEmpty()) { ranking = emptyList(); Logger.w("Tunnel", "auto select skipped: the server filter hides all ${subscription.profiles.size} server(s); keeping the selected server"); return null }
        if (pool.size < subscription.profiles.size) Logger.i("Tunnel", "auto select: ${pool.size} of ${subscription.profiles.size} server(s) pass the server filter")
        if (pool.size == 1) { ranking = listOf(pool[0].id); saveSelection(pool[0].id, access.kind); return pool[0].id }
        val order = pool.map { it.id }
        val now = System.currentTimeMillis()
        val recent = ServerPicker.recentRanking(order, Diagnostics.results.value.let { results -> order.mapNotNull { id ->
            results["${access.kind}:$id"]?.takeIf { r -> !r.checking && !r.cancelled && r.networkHandle == access.handle }?.let { r -> id to (r.latencyMs to r.checkedAt) } }.toMap() }, now)
        if (recent != null) {
            ranking = recent
            Logger.i("Tunnel", "auto select: reusing recent results (${recent.size} of ${order.size} answered), best=${order.indexOf(recent.first())}")
            saveSelection(recent.first(), access.kind)
            return recent.first()
        }
        if (Diagnostics.running.value) { Logger.w("Tunnel", "auto select skipped: a check is already running"); return null }
        probeCancel = AtomicBoolean(false)
        Diagnostics.runningMutable.value = true
        probeProfiles(null, probeCancel, access.kind, order)
        val results = Diagnostics.results.value
        ranking = ServerPicker.ranking(order, order.associateWith { results["${access.kind}:$it"]?.takeIf { r -> !r.checking && r.networkHandle == access.handle }?.latencyMs })
        val best = ranking.firstOrNull()
        Logger.i("Tunnel", "auto select: ${ranking.size} of ${order.size} server(s) answered, best=${best?.let { order.indexOf(it) } ?: "none"}")
        if (best != null) saveSelection(best, access.kind)
        return best
    }
    private fun saveSelection(best: String, kind: String) = runCatching {
        val store = SubscriptionStore(this)
        store.load()?.let { s -> store.save(if (kind == "mobile") s.copy(selected = best, mobileSelected = best) else s.copy(selected = best, wifiSelected = best)) }
        VpnLauncher.changed.value++
    }.onFailure { Logger.w("Tunnel", "saving the auto-selected server failed", it) }
    // ---- kill switch: a VPN interface without a core drops every packet ----
    @Volatile private var blockTun: ParcelFileDescriptor? = null
    @Volatile private var killSwitchLive: Boolean? = null // latest value sent by the app while connected
    private fun killSwitchOn() = killSwitchLive ?: runCatching { SettingsStore(this).load().killSwitch }.getOrDefault(activeSettings.killSwitch)
    @Volatile private var targetId = ""
    private var retryTask: java.util.concurrent.ScheduledFuture<*>? = null
    /** Establishes (or keeps) an interface that captures all traffic and is never read: nothing leaves the phone outside the tunnel. */
    @Synchronized private fun openBlock(): Boolean {
        if (blockTun != null) return true
        return try {
            val b = Builder().setSession("Moth").setBlocking(false).setMtu(1400)
                .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
            val plan = SplitTunnel.plan(activeSettings.splitMode, activeSettings.splitApps, packageName)
            plan.allowed.forEach { runCatching { b.addAllowedApplication(it) } }
            plan.disallowed.forEach { runCatching { b.addDisallowedApplication(it) } }
            blockTun = b.establish()
            TunnelStatus.blockingMutable.value = blockTun != null
            Logger.i("Tunnel", "kill switch: blocking interface ${if (blockTun != null) "up" else "unavailable"}")
            blockTun != null
        } catch (e: Exception) { Logger.e("Tunnel", "kill switch: could not establish the blocking interface", e); false }
    }
    @Synchronized private fun closeBlock() {
        val b = blockTun ?: return
        blockTun = null
        TunnelStatus.blockingMutable.value = false
        runCatching { b.close() }.onFailure { Logger.w("Tunnel", "closing the blocking interface failed", it) }
        Logger.d("Tunnel", "kill switch: blocking interface closed")
    }
    private fun scheduleRetry(delaySeconds: Long = 15) = synchronized(this) {
        retryTask?.cancel(false)
        retryTask = runCatching { healthExecutor.schedule({ retryBlocked("timer") }, delaySeconds, TimeUnit.SECONDS) }.getOrNull()
    }
    private fun cancelRetry() = synchronized(this) { retryTask?.cancel(false); retryTask = null }
    /** While blocked: try the same server again (on a timer and when the network changes). */
    private fun retryBlocked(reason: String) {
        if (TunnelStatus.state.value != TunnelState.BLOCKED || stopped) return
        if (!NetworkTracker.read(connectivity).available) { scheduleRetry(); return }
        executor.execute {
            if (TunnelStatus.state.value != TunnelState.BLOCKED || stopped) return@execute
            Logger.i("Tunnel", "kill switch: reconnecting ($reason)")
            serverName = ""; TunnelStatus.mutable.value = TunnelState.STARTING; runCatching { foreground() }
            startTunnel(targetId.ifBlank { VpnLauncher.lastProfileId(this) ?: return@execute }, null, pickBest = false)
        }
    }
    /** Called after repeated failed health checks with auto-select on: switch to the next fastest server (re-measure when all were tried). */
    private fun failover(current: Long) {
        val active = TunnelStatus.activeProfile.value
        tried += active
        val next = ServerPicker.next(ranking, active, tried)
        Logger.w("Health", "failover: ${if (next == null) "all ranked servers tried, measuring again" else "switching to the next fastest server"}")
        if (next == null) tried.clear()
        executor.execute {
            if (generation.get() != current || TunnelStatus.state.value != TunnelState.RUNNING || stopped) return@execute
            serverName = ""; TunnelStatus.mutable.value = TunnelState.STARTING; foreground()
            if (next != null) runCatching {
                val store = SubscriptionStore(this); val kind = NetworkTracker.read(connectivity).kind
                store.load()?.let { s -> store.save(if (kind == "mobile") s.copy(selected = next, mobileSelected = next) else s.copy(selected = next, wifiSelected = next)) }
                VpnLauncher.changed.value++
            }
            startTunnel(next ?: active, null, pickBest = next == null)
        }
    }
    @Volatile private var serverName = ""
    /** Posts (or re-posts) the foreground notification for the current state. The app name comes only from the system app-name line. */
    private fun foreground() = startForeground(1, buildNotification())
    private val CHANNEL = "vpn_status"
    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Kept as high in the shade as Android allows: the old "vpn" channel was LOW, which puts the notification in the bottom "Silent"
        // section. The "vpn_status" channel is DEFAULT importance with no sound or vibration (the 1 Hz updates stay quiet: only-alert-once),
        // and an ongoing foreground-service notification is ranked high within its section by the system comparator.
        // Channel importance can't be raised in place, so the old channel is deleted once.
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.msg_vpn_connection_125), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false); enableLights(false); setShowBadge(false)
            })
            runCatching { manager.deleteNotificationChannel("vpn") }
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val name = serverName
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_moth_notification)
            .setContentIntent(open).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            // No setColor / setColorized: a hard-set grey made the moth icon grey even where the system tints notifications
            // (Material You, API 31+). Without them the system applies its own (dynamic) colour to the white-on-transparent moth.
            .setCategory(Notification.CATEGORY_SERVICE).setLocalOnly(true)
            .apply { if (android.os.Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .addAction(Notification.Action.Builder(null, tr(R.string.msg_disconnect_126), stop).build())
        val offline = TunnelStatus.offline.value
        if (TunnelStatus.state.value == TunnelState.RUNNING && offline != org.mothvpn.core.OfflineStatus.Kind.ONLINE) {
            // No network at all: say so instead of showing a stale speed line.
            if (offline == org.mothvpn.core.OfflineStatus.Kind.BLOCKED) builder.setContentTitle(tr(R.string.notif_blocked)).setContentText(tr(R.string.notif_blocked_text))
            else { builder.setContentTitle(tr(R.string.tile_reconnecting)); if (name.isNotBlank()) builder.setContentText(name) }
            return builder.build()
        }
        when (TunnelStatus.state.value) {
            TunnelState.RUNNING -> {
                // Collapsed: title = server name, text = speed; the header shows only the system app-name line (no sub text, no time).
                // Expanded: the same title, then speed, total and time lines (the Time line ticks via the 1 Hz update).
                builder.setContentTitle(name.ifBlank { tr(R.string.msg_vpn_connection_125) })
                lastDetails?.let { builder.setContentText(it.speed).setStyle(Notification.BigTextStyle().bigText(it.expanded)) }
            }
            TunnelState.STARTING -> { builder.setContentTitle(tr(R.string.notif_connecting)); if (name.isNotBlank()) builder.setContentText(name) }
            TunnelState.STOPPING -> builder.setContentTitle(tr(R.string.notif_disconnecting))
            TunnelState.BLOCKED -> builder.setContentTitle(tr(R.string.notif_blocked)).setContentText(tr(R.string.notif_blocked_text))
            else -> builder.setContentTitle(tr(R.string.notif_checking))
        }
        return builder.build()
    }
    // ---- live traffic statistics in the notification (at most once per second, only while connected) ----
    private class Details(val speed: String, val expanded: String)
    @Volatile private var lastDetails: Details? = null
    private val statsExecutor = Executors.newSingleThreadScheduledExecutor()
    private var statsTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val statsLock = Any()
    private var statsActive = false
    @Volatile private var connectStartMillis = 0L // wall-clock time of the connection, reset on every connect
    private var baseTx = 0L; private var baseRx = 0L; private var lastTx = 0L; private var lastRx = 0L; private var lastAt = 0L; private var startedAt = 0L
    /** Traffic is measured with TrafficStats for this app's uid (the core's sockets to the server), relative to the moment of connecting. libbox status is not wired up in this app. */
    private fun startStats(): Unit = synchronized(statsLock) {
        connectStartMillis = System.currentTimeMillis()
        // Without TrafficStats support the counters stay 0: the notification still shows its full layout and the connection time.
        val tx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0); val rx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0)
        if (tx == 0L && rx == 0L) Logger.w("Tunnel", "traffic statistics are unavailable on this device")
        baseTx = tx; baseRx = rx; lastTx = tx; lastRx = rx; lastAt = SystemClock.elapsedRealtime(); startedAt = lastAt
        // The first RUNNING notification already has the full layout (speed 0, totals 0, time 00:00); the ticker updates it every second.
        statsActive = true; lastDetails = details(TrafficFormat.Stats.CONNECTED); TunnelStatus.statsMutable.value = TrafficFormat.Stats.CONNECTED
        statsTask?.cancel(false)
        statsTask = statsExecutor.scheduleWithFixedDelay({ runCatching { tickStats() }.onFailure { Logger.w("Tunnel", "notification update failed", it) } }, 0, 1, TimeUnit.SECONDS)
    }
    private fun stopStats() = synchronized(statsLock) { if (statsActive) runCatching { TrafficRecorder.flush(this) }; statsActive = false; statsTask?.cancel(false); statsTask = null; lastDetails = null; connectStartMillis = 0; TunnelStatus.statsMutable.value = null }
    /** Speed line (collapsed text) and speed/total/time lines (expanded text). */
    private fun details(st: TrafficFormat.Stats): Details {
        val units = tr(R.string.notif_units).split("|"); val locale = AppLanguage.context().resources.configuration.locales[0]
        val speed = tr(R.string.notif_speed, TrafficFormat.bytes(st.txRate, units, locale), TrafficFormat.bytes(st.rxRate, units, locale))
        val expanded = listOf(speed, tr(R.string.notif_total, TrafficFormat.bytes(st.txTotal, units, locale), TrafficFormat.bytes(st.rxTotal, units, locale)),
            tr(R.string.notif_uptime, TrafficFormat.duration(st.seconds))).joinToString("\n")
        return Details(speed, expanded)
    }
    private fun tickStats(): Unit = synchronized(statsLock) {
        if (!statsActive || TunnelStatus.state.value != TunnelState.RUNNING) return
        val now = SystemClock.elapsedRealtime()
        val tx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0); val rx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0)
        val sample = TrafficFormat.Stats.sample(baseTx, baseRx, lastTx, lastRx, tx, rx, startedAt, lastAt, now)
        TunnelStatus.statsMutable.value = sample
        val details = details(sample)
        TrafficRecorder.add(this, TrafficHistory.delta(lastTx, tx), TrafficHistory.delta(lastRx, rx))
        lastTx = tx; lastRx = rx; lastAt = now
        if (lastDetails?.expanded == details.expanded) return // nothing changed (uptime changes every second, so this only skips duplicate ticks)
        lastDetails = details
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }
    /**
     * The tunnel is down (OFF/ERROR): remove the notification ("Disconnecting…" must not stay) and stop the service.
     * stopSelf(lastStartId): stops unless a newer command (e.g. Connect tapped right after Disconnect) is pending.
     * A running check keeps the service; probeProfiles calls this again when it ends.
     */
    private fun finish() {
        Logger.d("Tunnel", "finish (state=${TunnelStatus.state.value}, checking=${Diagnostics.running.value}, startId=$lastStartId)")
        if (TunnelStatus.state.value !in setOf(TunnelState.OFF, TunnelState.ERROR)) return
        TunnelStatus.offlineMutable.value = org.mothvpn.core.OfflineStatus.Kind.ONLINE
        if (Diagnostics.running.value) { runCatching { foreground() }; return }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(lastStartId)
    }
    private fun closeTunnel() {
        Logger.d("Tunnel", "closeTunnel (box=${box != null}, xray=${xray != null})")
        stopStats()
        generation.incrementAndGet(); healthTask?.cancel(true); healthTask = null
        TunnelStatus.activeMutable.value = ""
        Diagnostics.healthMutable.value = HealthResult()
        runCatching { box?.close() }.onFailure { Logger.w("Core", "closing the core failed", it) }; box = null
        runCatching { tun?.close() }.onFailure { Logger.w("Tunnel", "closing the tun device failed", it) }; tun = null
        runCatching { xray?.close() }.onFailure { Logger.w("Core", "closing the xray core failed", it) }; xray = null
        runCatching { dpiXray?.close() }.onFailure { Logger.w("Core", "closing the dpi helper failed", it) }; dpiXray = null
        if (Logger.enabled(LogLevel.DEBUG)) Logger.d("Tunnel", "tun descriptors open after close: ${openTunDescriptors()}")
    }
    /** Number of /dev/tun descriptors this process still holds (diagnostics; 0 once the VPN is fully down, except the blocking interface). */
    private fun openTunDescriptors(): Int = (java.io.File("/proc/self/fd").listFiles() ?: emptyArray())
        .count { f -> runCatching { android.system.Os.readlink(f.path) }.getOrNull()?.contains("tun") == true }
    private fun initCore() {
        if (initialized) return
        try {
            Logger.d("Core", "initCore: loading libbox")
            TrustedCertificates.write(this) // fresh bundle (user CAs may have changed) before the first TLS handshake
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
        if (generation.get() == current && TunnelStatus.state.value == TunnelState.RUNNING) {
            Diagnostics.healthMutable.value = next
            if (settings.autoSelect && !manualPick && next.failures >= ServerPicker.failoverAfter) failover(current)
        }
    }
    /** [only]: check just these servers (one subscription from its header menu); empty = all. */
    private fun probeProfiles(id: String?, cancelled: AtomicBoolean, wantedNetwork: String?, shownOrder: List<String>, only: Set<String> = emptySet()) {
        try {
            val settings = SettingsStore(this).load()
            val access = NetworkTracker.read(connectivity)
            Logger.i("Probe", "probeProfiles: mode=${settings.probeMode} network=${access.kind} available=${access.available} wanted=$wantedNetwork")
            if (!access.available || (wantedNetwork != null && wantedNetwork != access.kind)) { Logger.w("Probe", "skipped: network unavailable or changed"); return }
            val profiles = SubscriptionStore(this).load()?.profiles.orEmpty().filter { (id == null || it.id == id) && (only.isEmpty() || it.id in only) }.let { all -> ProbeOrder.arrange(all, shownOrder) { it.id } } // list order: the pool queue is FIFO
            val pool = Executors.newFixedThreadPool(settings.probeParallel.coerceAtMost(profiles.size).coerceAtLeast(1))
            fun probeOne(profile: Profile) {
                if (cancelled.get() || NetworkTracker.read(connectivity).handle != access.handle) return
                val key = "${access.kind}:${profile.id}"
                val method = if (settings.probeMode == "tcp") "TCP" else "HTTPS"
                fun result(ms: Long? = null, error: String? = null, checking: Boolean = false, wasCancelled: Boolean = false, kind: ProbeErrorKind? = null) = Diagnostics.result(key,
                    ProbeResult(checking = checking, latencyMs = ms, error = error, method = method, networkHandle = access.handle, cancelled = wasCancelled, kind = kind))
                val before = Diagnostics.results.value[key]?.takeIf { !it.checking && !it.cancelled && it.networkHandle == access.handle }
                result(checking = true)
                val label = describe(profile)
                var stage = "start"
                Logger.i("Probe", "profile ${profiles.indexOf(profile)} method=$method $label network=${access.kind}")
                var allocated = 0; var dpiPort = 0
                var local: BoxService? = null; var xr: MothXrayService? = null; var helper: MothXrayService? = null
                try {
                    if (method == "TCP") {
                        val endpoint = ProbePolicy.tcpEndpoint(profile)
                        stage = "tcp"
                        if (endpoint == null) result(error = tr(R.string.msg_udp_protocol_choose_an_https_check_129), kind = ProbeErrorKind.UDP_PROTOCOL)
                        else { val ms = tcpProbe(endpoint, settings.probeTimeoutMs.toLong(), cancelled); Logger.i("Probe", "TCP ok in $ms ms ($label)"); if (!cancelled.get() && NetworkTracker.read(connectivity).handle == access.handle) result(ms = ms) }
                        return
                    }
                    stage = "free port"
                    val port = allocatePort(); allocated = port; val user = UUID.randomUUID().toString(); val password = UUID.randomUUID().toString()
                    if (profile.engine == "xray") { stage = "xray start"; coreStartLock.withLock { xr = Libbox.mothStartXray(LocalProxyConfig.xray(profile, port, user, password, settings), this) } }
                    else {
                        stage = "build config"
                        var hop: LocalHop? = null
                        if (DpiTricks.singBoxHelper(settings)) {
                            stage = "dpi helper start"
                            hop = LocalHop(allocatePort().also { dpiPort = it }, UUID.randomUUID().toString())
                            coreStartLock.withLock { helper = Libbox.mothStartXray(LocalProxyConfig.dpiHelper(hop.port, hop.uuid, settings), this) }
                        }
                        val config = LocalProxyConfig.singBox(profile, port, user, password, settings, hop)
                        stage = "Libbox.checkConfig"; Libbox.checkConfig(config)
                        coreStartLock.withLock {
                            stage = "Libbox.newService"; local = Libbox.newService(config, this)
                            stage = "BoxService.start"; local!!.start()
                        }
                    }
                    stage = "local proxy ready"
                    Logger.d("Probe", "local proxy started ($label)")
                    var measured: Long? = null
                    var last: Throwable? = null
                    for (url in ProbePolicy.urls(settings)) {
                        if (cancelled.get() || NetworkTracker.read(connectivity).handle != access.handle) break
                        val test = ProxyRequest(this, port, user, password, settings.probeTimeoutMs.toLong(), cancelled)
                        requests.add(test)
                        stage = "request ${Diagnostics.describeUrl(url)}"
                        try {
                            measured = test.run(url, warm = settings.probeMode == org.mothvpn.core.ProbePolicy.LATENCY)
                            Logger.i("Probe", "request ok ${Diagnostics.describeUrl(url)} $measured ms (first ${test.firstMs} ms, warm ${test.warmMs ?: "-"} ms, mode=${settings.probeMode}) ($label)"); break
                        }
                        catch (e: Throwable) {
                            last = e
                            Logger.w("Probe", "request failed url=${Diagnostics.describeUrl(url)} handshakeStage=${test.stage} method=$method network=${access.kind} ($label)", e)
                        } finally {
                            requests.remove(test)
                        }
                    }
                    if (!cancelled.get() && NetworkTracker.read(connectivity).handle == access.handle) {
                        result(ms = measured ?: throw (last ?: java.net.SocketTimeoutException()))
                    }
                } catch (e: Throwable) {
                    Logger.e("Probe", "probe failed at stage '$stage' method=$method network=${access.kind} cancelled=${cancelled.get()} ($label)", e)
                    if (!cancelled.get() && NetworkTracker.read(connectivity).handle == access.handle) {
                        if (ProbeErrorKind.of(e) == ProbeErrorKind.CANCELLED) Diagnostics.restore(key, before)
                        else result(error = Diagnostics.friendly(e), kind = ProbeErrorKind.of(e))
                    }
                } finally {
                    if (allocated != 0) releasePort(allocated)
                    if (dpiPort != 0) releasePort(dpiPort)
                    runCatching { helper?.close() }.onFailure { Logger.w("Probe", "closing the dpi helper failed", it) }
                    runCatching { local?.close() }.onFailure { Logger.w("Probe", "closing the local core failed", it) }; runCatching { xr?.close() }.onFailure { Logger.w("Probe", "closing the local xray failed", it) }
                    // An interrupted check is not a failure: restore the last completed result from this network, or show nothing.
                    if (Diagnostics.results.value[key]?.checking == true) Diagnostics.restore(key, before)
                }
            }
            val futures = profiles.map { p -> pool.submit { try { probeOne(p) } catch (t: Throwable) { Logger.e("Probe", "probe task crashed", t) } } }
            try { futures.forEach { runCatching { it.get() } } } finally { pool.shutdownNow() }
        } catch (t: Throwable) {
            Logger.e("Probe", "probeProfiles aborted", t)
        } finally {
            Diagnostics.runningMutable.value = false
            Logger.d("Probe", "probeProfiles finished")
            if (TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR)) finish()
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
    override fun onRevoke() { Logger.w("Tunnel", "VPN revoked by the system or another VPN app (state=${TunnelStatus.state.value})"); stopped = true; cancelProbes(); cancelRetry(); executor.execute { closeTunnel(); closeBlock(); TunnelStatus.mutable.value = TunnelState.OFF; finish() } }
    override fun onDestroy() {
        Logger.i("Tunnel", "service destroyed (state=${TunnelStatus.state.value})")
        stopped = true
        cancelProbes(); stopStats(); statsExecutor.shutdownNow(); healthExecutor.shutdownNow(); lookupExecutor.shutdownNow(); probes.shutdown()
        cancelRetry(); networkExecutor.shutdownNow(); verifyTask?.cancel(false)
        defaultCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }; defaultCallback = null
        executor.execute { closeTunnel(); closeBlock(); if (TunnelStatus.mutable.value != TunnelState.ERROR) TunnelStatus.mutable.value = TunnelState.OFF; TunnelStatus.offlineMutable.value = org.mothvpn.core.OfflineStatus.Kind.ONLINE }
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
        val plan = SplitTunnel.plan(activeSettings.splitMode, activeSettings.splitApps, packageName)
        plan.allowed.forEach { runCatching { b.addAllowedApplication(it) }.onFailure { Logger.d("Tunnel", "split tunneling: an app is not installed") } }
        plan.disallowed.forEach { runCatching { b.addDisallowedApplication(it) }.onFailure { Logger.d("Tunnel", "split tunneling: an app is not installed") } }
        if (plan.allowed.isNotEmpty() || plan.disallowed.isNotEmpty()) Logger.i("Tunnel", "split tunneling: mode=${activeSettings.splitMode} apps=${activeSettings.splitApps.size}")
        tun = b.establish() ?: run { Logger.e("Tunnel", "establish() returned null: VPN permission revoked or unavailable"); error("VPN permission unavailable") }
        Logger.i("Tunnel", "tun device established (mtu=${options.mtu})")
        closeBlock() // the new interface replaced the blocking one
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
    /** Owner uid of a connection inside the tunnel (Android 10+, allowed for the active VPN app); used by the split-tunnel guard rules. */
    /** Android's built-in CAs and the user-installed ones as PEM (like the official sing-box app), see TrustedCertificates. */
    override fun systemCertificates(): io.nekohasekai.libbox.StringIterator = Strings(TrustedCertificates.pems())
    /** null: sing-box uses its own resolver for "local" DNS servers (only used to find a bootstrap DNS server given by name). */
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? = null
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): Int {
        if (Build.VERSION.SDK_INT < 29 || sourceAddress == null || destinationAddress == null) return -1
        return runCatching {
            connectivity.getConnectionOwnerUid(ipProtocol, java.net.InetSocketAddress(java.net.InetAddress.getByName(sourceAddress), sourcePort),
                java.net.InetSocketAddress(java.net.InetAddress.getByName(destinationAddress), destinationPort))
        }.getOrDefault(-1)
    }
    override fun packageNameByUid(uid: Int): String = packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
    override fun uidByPackageName(packageName: String): Int = packageManager.getApplicationInfo(packageName, 0).uid
    // ---- default network monitor ----
    // One requestNetwork / best-matching callback (NOT_VPN + INTERNET) follows the network the system would use without
    // the VPN. Its state, not activeNetwork (which is the VPN itself for this app), drives sing-box's default interface,
    // setUnderlyingNetworks and the recovery after Wi-Fi <-> mobile switches.
    private val networkExecutor = Executors.newSingleThreadScheduledExecutor()
    private val networkState = org.mothvpn.core.DefaultNetworkState()
    private val networks = java.util.concurrent.ConcurrentHashMap<Long, android.net.Network>()
    private val monitors = java.util.concurrent.ConcurrentHashMap.newKeySet<InterfaceUpdateListener>()
    private val lastInterface = java.util.concurrent.ConcurrentHashMap<InterfaceUpdateListener, String>()
    private var networkTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var verifyTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var activeEngine = "sing-box"
    @Volatile private var activeKind = "" // network type ("wifi"/"mobile") the core was started on
    /** Type of the real underlying network from the default-network callback (never the VPN); falls back to the tracker. */
    private fun trackedKind(): String = networkState.current?.let { networks[it.handle] }?.let { n ->
        connectivity.getNetworkCapabilities(n)?.let { if (it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "mobile" else "wifi" } } ?: NetworkTracker.read(connectivity).kind
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private fun iface(network: android.net.Network) = connectivity.getLinkProperties(network)?.interfaceName.orEmpty()
    private fun defaultNetwork(): android.net.Network? = networkState.current?.let { networks[it.handle] } ?: NetworkTracker.physical(connectivity)
    override fun onCreate() {
        super.onCreate()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { networks[network.networkHandle] = network; networkState.available(network.networkHandle, iface(network)); networkChanged("available") }
            override fun onLost(network: android.net.Network) { networkState.lost(network.networkHandle); networks.remove(network.networkHandle); networkChanged("lost") }
            override fun onLinkPropertiesChanged(network: android.net.Network, linkProperties: android.net.LinkProperties) { networkState.linkChanged(network.networkHandle, linkProperties.interfaceName.orEmpty()); networkChanged("link") }
        }
        val request = android.net.NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) connectivity.registerBestMatchingNetworkCallback(request, callback, android.os.Handler(android.os.Looper.getMainLooper()))
            else connectivity.requestNetwork(request, callback)
            defaultCallback = callback
        }.onFailure { Logger.w("Network", "default network callback registration failed", it) }
    }
    private fun pushInterface(listener: InterfaceUpdateListener, network: android.net.Network?): Boolean {
        val name = network?.let(::iface).orEmpty()
        val index = runCatching { JavaInterface.getByName(name)?.index ?: -1 }.onFailure { Logger.w("Core", "interface lookup failed", it) }.getOrDefault(-1)
        val key = "$name/$index/${network?.networkHandle}"
        if (lastInterface.put(listener, key) == key) return false
        val cellular = network?.let { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } == true
        Logger.d("Core", "default interface: ${if (network == null) "none" else if (cellular) "mobile" else "wifi"} index=$index")
        runCatching { listener.updateDefaultInterface(name, index, cellular, false) }.onFailure { Logger.w("Core", "interface update failed", it) }
        return true
    }
    /** Debounced; the decision compares the settled state with the last applied one, so no event is lost. */
    private fun networkChanged(reason: String) = synchronized(monitors) {
        Logger.v("Network", "default network callback: $reason")
        networkTask?.cancel(false)
        networkTask = runCatching { networkExecutor.schedule({ applyNetwork() }, 500, TimeUnit.MILLISECONDS) }.getOrNull()
    }
    private fun applyNetwork() {
        val action = networkState.settle()
        val network = networkState.current?.let { networks[it.handle] }
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }.onFailure { Logger.w("Tunnel", "setUnderlyingNetworks failed", it) }
        monitors.forEach { pushInterface(it, network) }
        updateOffline(network != null)
        if (action == org.mothvpn.core.DefaultNetworkState.Action.NONE) return
        val kind = network?.let { if (connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) "mobile" else "wifi" } ?: "none"
        Logger.i("Tunnel", "default network ${if (action == org.mothvpn.core.DefaultNetworkState.Action.LOST) "lost" else "switched to $kind"} (state=${TunnelStatus.state.value}, engine=$activeEngine)")
        if (action == org.mothvpn.core.DefaultNetworkState.Action.LOST) return
        // Wi-Fi and mobile data have their own server selection: follow it when the network type changed.
        val state = TunnelStatus.state.value
        if (state == TunnelState.RUNNING || state == TunnelState.BLOCKED) {
            val previous = activeKind
            val subscription = runCatching { SubscriptionStore(this).load() }.getOrNull()
            val decision = if (subscription == null || previous.isEmpty() || kind == "none") org.mothvpn.core.NetworkServerChoice.Decision.Keep
                else org.mothvpn.core.NetworkServerChoice.decide(previous, kind, TunnelStatus.activeProfile.value.ifBlank { targetId }, subscription.selection(kind),
                    subscription.profiles.map { it.id }.toSet(), runCatching { SettingsStore(this).load().autoSelect }.getOrDefault(activeSettings.autoSelect), manualPick)
            if (decision !is org.mothvpn.core.NetworkServerChoice.Decision.Keep) {
                Logger.i("Tunnel", "network type $previous -> $kind: ${if (decision is org.mothvpn.core.NetworkServerChoice.Decision.Switch) "switching to the server selected for $kind" else "auto-selecting on the $kind list"}")
                val id = (decision as? org.mothvpn.core.NetworkServerChoice.Decision.Switch)?.id ?: TunnelStatus.activeProfile.value.ifBlank { targetId }
                val auto = decision is org.mothvpn.core.NetworkServerChoice.Decision.AutoSelect
                activeKind = kind; targetId = id
                val current = generation.get()
                executor.execute {
                    if (generation.get() != current || stopped || TunnelStatus.state.value !in setOf(TunnelState.RUNNING, TunnelState.BLOCKED)) return@execute
                    if (auto) { ranking = emptyList(); tried.clear() }
                    serverName = ""; TunnelStatus.mutable.value = TunnelState.STARTING; runCatching { foreground() }
                    startTunnel(id, null, pickBest = auto)
                }
                return
            }
        }
        when (state) {
            TunnelState.BLOCKED -> retryBlocked("network changed")
            TunnelState.RUNNING -> {
                if (org.mothvpn.core.NetworkRecovery.afterSwitch(activeEngine) == org.mothvpn.core.NetworkRecovery.Step.RESTART) restartSameServer("network changed (xray)")
                else {
                    runCatching { box?.resetNetwork() }.onFailure { Logger.w("Core", "resetNetwork failed", it) }
                    val current = generation.get()
                    verifyTask?.cancel(false)
                    verifyTask = runCatching { healthExecutor.schedule({ verifyAfterSwitch(current) }, org.mothvpn.core.NetworkRecovery.verifyAfterSeconds, TimeUnit.SECONDS) }.getOrNull()
                }
            }
            else -> Unit
        }
    }
    /** Publishes "no network" for the home screen, tile, widget and notification (all read TunnelStatus). */
    private fun updateOffline(hasNetwork: Boolean) {
        val connected = TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING)
        val next = org.mothvpn.core.OfflineStatus.of(connected, hasNetwork, killSwitchOn())
        if (TunnelStatus.offlineMutable.value == next) return
        Logger.i("Tunnel", "network ${if (hasNetwork) "available" else "unavailable"}: offline status $next (state=${TunnelStatus.state.value})")
        TunnelStatus.offlineMutable.value = next
        if (connected) runCatching { getSystemService(NotificationManager::class.java).notify(1, buildNotification()) }
    }
    /** One internet check through the tunnel after a reset; a failure means stale connections: restart the core. */
    private fun verifyAfterSwitch(current: Long) {
        if (generation.get() != current || TunnelStatus.state.value != TunnelState.RUNNING) return
        val settings = activeSettings
        val ok = ProbePolicy.urls(settings).any { url -> generation.get() == current && runCatching { Diagnostics.checkTunnel(connectivity, url, minOf(settings.timeoutMs, 5000).toLong()) }.isSuccess }
        Logger.i("Tunnel", "check after network switch: ${if (ok) "ok" else "failed"}")
        if (generation.get() == current && org.mothvpn.core.NetworkRecovery.afterVerify(ok) == org.mothvpn.core.NetworkRecovery.Step.RESTART) restartSameServer("check after network switch failed")
    }
    /** Fast core restart on the active server without stopping the service (kill switch holds the block meanwhile). */
    private fun restartSameServer(reason: String) {
        val current = generation.get()
        executor.execute {
            if (generation.get() != current || TunnelStatus.state.value != TunnelState.RUNNING || stopped) return@execute
            val id = TunnelStatus.activeProfile.value.ifBlank { targetId }
            if (id.isBlank()) return@execute
            Logger.i("Tunnel", "restarting the core on the same server ($reason)")
            serverName = ""; TunnelStatus.mutable.value = TunnelState.STARTING; runCatching { foreground() }
            startTunnel(id, null, pickBest = false)
        }
    }
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        monitors.add(listener)
        pushInterface(listener, defaultNetwork())
    }
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        monitors.remove(listener); lastInterface.remove(listener)
    }
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

