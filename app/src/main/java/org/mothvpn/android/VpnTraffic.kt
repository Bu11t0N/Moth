package org.mothvpn.android

import android.content.Context
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mothvpn.core.VpnAppTraffic
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-app traffic counted by the tunnel (Settings › Data › App traffic), no "Usage access" needed: the core's connection list comes
 * over its private command socket in the app's files dir (a Unix socket, no network port), and the owner uid of every connection
 * is recorded when the core asks Android for it ([TunnelService.findConnectionOwner]). Only byte totals per app and day are kept.
 */
object VpnTraffic {
    const val UNKNOWN = "?"
    private const val FILE = "vpn_app_traffic.json"
    private const val INTERVAL_NS = 5_000_000_000L
    private const val SAVE_MS = 60_000L
    private val ledger = VpnAppTraffic.Ledger()
    private val deltas = VpnAppTraffic.Deltas()
    private val owners = ConcurrentHashMap<String, Long>() // owner key -> (uid shl 32) or recorded time in the low bits
    private val connectionApp = HashMap<String, String>()
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var file: File? = null
    @Volatile private var server: CommandServer? = null
    @Volatile private var client: CommandClient? = null
    @Volatile private var kind: () -> String = { "wifi" }
    @Volatile private var appOf: (Int) -> String = { "uid:$it" }
    private var savedAt = 0L
    private val changedMutable = MutableStateFlow(0L)
    /** Bumps when new traffic was counted (the page refreshes). */
    val changed: StateFlow<Long> = changedMutable
    private val servingMutable = MutableStateFlow(false)
    /** True while the core's command socket serves a running tunnel (Settings › Data › Connections connects to it then). */
    val serving: StateFlow<Boolean> = servingMutable

    /** The app that opened the core's connection [id] (from [network] "tcp"/"udp" and [source] "ip:port"): a package name, "uid:N" or null. */
    fun ownerApp(id: String, network: String, source: String): String? {
        synchronized(lock) { connectionApp[id]?.takeIf { it != UNKNOWN }?.let { return it } }
        return VpnAppTraffic.ownerKey(network, source)?.let { owners[it] }?.let { appOf((it ushr 32).toInt()) }
    }

    /** Called from the core's owner lookup: remembers which app (uid) opened the connection from [address]:[port]. */
    fun recordOwner(ipProtocol: Int, address: String, port: Int, uid: Int) {
        if (uid < 0) return
        owners[VpnAppTraffic.ownerKey(VpnAppTraffic.network(ipProtocol), address, port)] = (uid.toLong() shl 32) or (System.currentTimeMillis() / 1000 and 0xffffffffL)
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = File(context.filesDir, FILE); file = f
            runCatching { if (f.exists()) ledger.load(f.readText()) }.onFailure { Logger.w("Traffic", "per-app traffic file unreadable; starting over") }
            loaded = true
        }
    }

    /** Apps with traffic in the last [days] calendar days (1 = today), only [kind] ("wifi"/"mobile") when set. */
    fun query(context: Context, days: Int, kind: String?): List<VpnAppTraffic.AppBytes> {
        ensureLoaded(context)
        val today = LocalDate.now().toEpochDay()
        return ledger.query(today - (days.coerceAtLeast(1) - 1), today, kind)
    }

    /**
     * Serves the core [box] (after it started) on the command socket and, when [count] (Android 10+, owners known), counts traffic per app;
     * [networkKind]: "wifi"/"mobile" of the real network now.
     */
    fun start(context: Context, box: BoxService, networkKind: () -> String, packageOf: (Int) -> String, count: Boolean = true) {
        ensureLoaded(context)
        kind = networkKind; appOf = packageOf
        runCatching {
            val s = server ?: Libbox.newCommandServer(NoCommands, 0).also { it.start(); server = it }
            s.setService(box)
            synchronized(lock) { deltas.clear(); connectionApp.clear() }
            servingMutable.value = true
            client?.let { runCatching { it.disconnect() } }; client = null
            if (count) client = CommandClient(Reader, CommandClientOptions().apply { command = Libbox.CommandConnections; statusInterval = INTERVAL_NS }).also { it.connect() }
        }.onFailure { Logger.w("Traffic", "per-app traffic counter unavailable", it) }
    }

    /** Stops counting (before the core closes) and saves what was counted. */
    fun stop() {
        servingMutable.value = false
        client?.let { runCatching { it.disconnect() } }; client = null
        runCatching { server?.setService(null) }
        save(force = true)
    }

    private fun save(force: Boolean) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!force && now - savedAt < SAVE_MS) return
        savedAt = now
        runCatching {
            val tmp = File(f.parentFile, "$FILE.tmp"); tmp.writeText(ledger.toJson())
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }.onFailure { Logger.w("Traffic", "saving per-app traffic failed", it) }
    }

    private fun count(connections: Connections) {
        val day = LocalDate.now().toEpochDay(); val network = kind()
        var added = false
        synchronized(lock) {
            val ids = HashSet<String>()
            val it = connections.iterator()
            while (it.hasNext()) {
                val c = it.next(); val id = c.id ?: continue
                ids.add(id)
                val delta = deltas.update(id, c.uplinkTotal, c.downlinkTotal, c.closedAt != 0L) ?: run { connectionApp.remove(id); null } ?: continue
                val app = connectionApp.getOrPut(id) {
                    VpnAppTraffic.ownerKey(c.network.orEmpty(), c.source.orEmpty())?.let { owners[it] }?.let { appOf((it ushr 32).toInt()) } ?: UNKNOWN
                }
                if (delta[0] > 0 || delta[1] > 0) { ledger.add(day, network, app, delta[0], delta[1]); added = true }
                if (c.closedAt != 0L) connectionApp.remove(id)
            }
            deltas.retain(ids); connectionApp.keys.retainAll(ids)
            // Owner records live 10 minutes: a connection is listed within seconds of opening.
            val cutoff = System.currentTimeMillis() / 1000 - 600
            owners.entries.removeIf { (it.value and 0xffffffffL) < cutoff }
        }
        if (added) { changedMutable.value = System.currentTimeMillis(); save(force = false) }
    }

    private object Reader : CommandClientHandler {
        override fun connected() {}
        override fun disconnected(message: String?) {}
        override fun clearLogs() {}
        override fun writeLogs(messageList: StringIterator?) {}
        override fun writeStatus(message: StatusMessage?) {}
        override fun writeGroups(message: OutboundGroupIterator?) {}
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun updateClashMode(newMode: String?) {}
        override fun writeConnections(message: Connections?) { message?.let { runCatching { count(it) }.onFailure { e -> Logger.w("Traffic", "counting per-app traffic failed", e) } } }
    }

    /** The command socket only serves the connection list here: reload / close / system proxy requests are ignored. */
    private object NoCommands : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
        override fun postServiceClose() {}
        override fun serviceReload() {}
        override fun setSystemProxyEnabled(enabled: Boolean) {}
    }
}
