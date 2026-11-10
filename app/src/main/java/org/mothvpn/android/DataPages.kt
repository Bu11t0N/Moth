package org.mothvpn.android

import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.mothvpn.core.TrafficFormat
import org.mothvpn.core.UsageStatsMath
import java.time.LocalDate
import java.time.ZoneId

/** Bumps a counter every time the screen comes back (e.g. from Android's settings), so a page re-reads permissions and numbers. */
@Composable internal fun resumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) count++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return count
}

private fun open(context: Context, vararg intents: Intent) {
    for (intent in intents) if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    Logger.w("Data", "no settings screen for ${intents.firstOrNull()?.action}")
}

@Composable private fun DataCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = ndCardColors()) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}
@Composable private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A card of list rows (like the Settings cards): an optional header (title + one quiet line), rows separated by hairlines. */
@Composable private fun ListCard(title: String?, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = ndCardColors()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (title != null) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}
@Composable private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** One list row: a short label (with an optional second line under it) and its value on the right, on one line each. */
@Composable private fun InfoRow(label: String, value: String?, supporting: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.pressScale(0.98f).clickable(onClick = onClick) else it }.heightIn(min = 52.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
        if (onClick != null) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}
private class Line(val label: String, val value: String?, val supporting: String? = null)

// ---------------------------------------------------------------------------------------------------------------------------- Battery

/** What Android's battery stats counted for Moth's own uid (SystemHealthManager, no permission); null fields are not reported by the device. */
private class MothBattery(val cpuMs: Long?, val cpuMams: Long?, val wifiMams: Long?, val mobileMams: Long?, val serviceMs: Long?, val topMs: Long?, val wakeMs: Long?, val radioMs: Long?)

private fun mothBattery(context: Context): MothBattery? = runCatching {
    val h = context.getSystemService(SystemHealthManager::class.java)?.takeMyUidSnapshot() ?: return null
    fun m(key: Int) = if (h.hasMeasurement(key)) h.getMeasurement(key) else null
    fun t(key: Int) = if (h.hasTimer(key)) h.getTimerTime(key) else null
    val cpu = listOfNotNull(m(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS), m(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS)).takeIf { it.isNotEmpty() }?.sum()
    val wake = if (h.hasTimers(UidHealthStats.TIMERS_WAKELOCKS_PARTIAL)) h.getTimers(UidHealthStats.TIMERS_WAKELOCKS_PARTIAL).values.sumOf { it.time } else null
    MothBattery(cpu, m(UidHealthStats.MEASUREMENT_CPU_POWER_MAMS), m(UidHealthStats.MEASUREMENT_WIFI_POWER_MAMS), m(UidHealthStats.MEASUREMENT_MOBILE_POWER_MAMS),
        t(UidHealthStats.TIMER_PROCESS_STATE_FOREGROUND_SERVICE_MS), t(UidHealthStats.TIMER_PROCESS_STATE_TOP_MS), wake, t(UidHealthStats.TIMER_MOBILE_RADIO_ACTIVE))
}.onFailure { Logger.w("Battery", "battery stats unavailable: ${it.javaClass.simpleName}") }.getOrNull()

/**
 * Settings › Data › Battery. Only Moth's own numbers, which Android gives without special permissions: its counters from Android's
 * battery stats since the last charge (estimated energy, processor time, background/foreground time, wakelocks), the processor share
 * of the running process and the battery optimization state, with links to Android's battery screens for the rest.
 */
@Composable fun BatteryPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resumed = resumeCount()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    val moth by produceState<MothBattery?>(null, resumed, tick / 6) { value = withContext(Dispatchers.IO) { mothBattery(context) } }
    val session by produceState<Pair<Long, Long>?>(null, resumed, tick) {
        value = runCatching { Process.getElapsedCpuTime() to (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) }.getOrNull()
    }
    // Re-read on every return to the page and every 5 s: the user changes it in Android's settings and comes back.
    val ignoring = remember(resumed, tick) { runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() }
    val locale = AppLanguage.context().resources.configuration.locales[0]
    fun mah(mAms: Long) = String.format(locale, "%.1f mAh", UsageStatsMath.mAh(mAms))
    fun time(ms: Long) = TrafficFormat.duration(ms / 1000)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val m = moth
        ListCard(tr(R.string.battery_moth), tr(R.string.battery_moth_hint)) {
            val parts = m?.let { listOfNotNull(it.cpuMams?.takeIf { v -> v > 0 }?.let { v -> tr(R.string.battery_energy_cpu, mah(v)) },
                it.wifiMams?.takeIf { v -> v > 0 }?.let { v -> tr(R.string.battery_energy_wifi, mah(v)) },
                it.mobileMams?.takeIf { v -> v > 0 }?.let { v -> tr(R.string.battery_energy_mobile, mah(v)) }) }.orEmpty()
            // Times under a second are noise: no row for them.
            fun timed(label: Int, ms: Long?) = ms?.takeIf { it >= 1000 }?.let { Line(tr(label), time(it)) }
            val rows = listOfNotNull(
                parts.takeIf { it.isNotEmpty() }?.let { Line(tr(R.string.battery_energy), mah(listOfNotNull(m?.cpuMams, m?.wifiMams, m?.mobileMams).sumOf { v -> v.coerceAtLeast(0) }), it.joinToString(" · ")) },
                timed(R.string.battery_cpu, m?.cpuMs), timed(R.string.battery_background, m?.serviceMs), timed(R.string.battery_foreground, m?.topMs),
                timed(R.string.battery_wakelock, m?.wakeMs), timed(R.string.battery_radio, m?.radioMs))
            if (rows.isEmpty()) Text(tr(if (m == null) R.string.battery_unavailable else R.string.battery_nothing_yet), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            rows.forEachIndexed { i, row -> if (i > 0) RowDivider(); InfoRow(row.label, row.value, row.supporting) }
        }
        session?.let { (cpu, up) ->
            ListCard(null) {
                InfoRow(tr(R.string.battery_session), String.format(locale, "%.1f %%", UsageStatsMath.percent(cpu, up)), tr(R.string.battery_session_value, time(cpu), time(up)))
            }
        }
        ListCard(tr(R.string.battery_optimization)) {
            // The real state from PowerManager.isIgnoringBatteryOptimizations (Android's own optimization list), read just now.
            if (ignoring != null) {
                val xiaomi = Build.MANUFACTURER.lowercase().let { it == "xiaomi" || it == "redmi" || it == "poco" }
                InfoRow(tr(R.string.battery_opt_state), tr(if (ignoring) R.string.battery_opt_state_off else R.string.battery_opt_state_on),
                    tr(if (ignoring) R.string.battery_opt_off else R.string.battery_opt_on) + if (xiaomi) "\n\n" + tr(R.string.battery_opt_xiaomi) else "")
                RowDivider()
            }
            InfoRow(tr(R.string.battery_opt_open), null, onClick = { open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
            RowDivider()
            InfoRow(tr(R.string.battery_system), null, onClick = { open(context, Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))) })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------- App data

private class AppUsageRow(val key: String, val label: String, val packageName: String?, val rx: Long, val tx: Long) { val total get() = rx + tx }

/** Rows of the VPN's own per-app counter ([VpnTraffic]): package names to labels and icons, system uids and unknown owners grouped. */
private fun labelledVpn(context: Context, list: List<org.mothvpn.core.VpnAppTraffic.AppBytes>): List<AppUsageRow> {
    val pm = context.packageManager
    return list.map { a ->
        val (key, label, pkg) = when {
            a.app == VpnTraffic.UNKNOWN -> Triple("unknown", tr(R.string.appdata_unknown), null)
            a.app.startsWith("uid:") -> if ((a.app.removePrefix("uid:").toIntOrNull() ?: 0) % 100_000 < Process.FIRST_APPLICATION_UID) Triple("system", tr(R.string.appdata_system), null)
                else Triple("other", tr(R.string.appdata_other), null)
            else -> runCatching { pm.getApplicationInfo(a.app, 0) }.getOrNull()?.let { Triple(a.app, it.loadLabel(pm).toString(), a.app) } ?: Triple(a.app, a.app, null)
        }
        AppUsageRow(key, label, pkg, a.rx, a.tx)
    }.groupBy { it.key }.map { (key, rows) -> AppUsageRow(key, rows.first().label, rows.first().packageName, rows.sumOf { it.rx }, rows.sumOf { it.tx }) }
        .sortedByDescending { it.total }
}

/** Only the VPN counter: period and physical-network filters remain, no Usage access is needed. */
@Composable fun AppDataUsagePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resumed = resumeCount()
    var days by rememberSaveable { mutableIntStateOf(1) }
    var network by rememberSaveable { mutableIntStateOf(0) }
    val counted by VpnTraffic.changed.collectAsState()
    val locale = AppLanguage.context().resources.configuration.locales[0]
    val units = tr(R.string.notif_units).split("|")
    fun bytes(v: Long) = TrafficFormat.bytes(v, units, locale)
    val list by produceState<List<AppUsageRow>?>(null, days, network, resumed, counted / 10_000) {
        value = withContext(Dispatchers.IO) { labelledVpn(context, VpnTraffic.query(context, days, when (network) { 1 -> "wifi"; 2 -> "mobile"; else -> null })) }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to R.string.appdata_today, 7 to R.string.appdata_7, 30 to R.string.appdata_30).forEach { (d, label) ->
                FilterChip(selected = days == d, onClick = { days = d }, label = { Text(tr(label)) }, modifier = Modifier.pressScale())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to R.string.appdata_all, 1 to R.string.appdata_wifi, 2 to R.string.appdata_mobile).forEach { (n, label) ->
                FilterChip(selected = network == n, onClick = { network = n }, label = { Text(tr(label)) }, modifier = Modifier.pressScale())
            }
        }
        val rows = list
        if (rows == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else DataCard {
            Text(tr(R.string.appdata_total, bytes(rows.sumOf { it.total })), style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) Hint(tr(if (Build.VERSION.SDK_INT < 29) R.string.appdata_vpn_old else R.string.appdata_vpn_empty))
            val top = rows.firstOrNull()?.total?.coerceAtLeast(1) ?: 1
            rows.take(100).forEach { row -> AppUsageItem(row, top, ::bytes) }
        }
        Hint(tr(R.string.appdata_vpn_hint))
    }
}

@Composable private fun AppUsageItem(row: AppUsageRow, top: Long, bytes: (Long) -> String) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon by produceState<ImageBitmap?>(null, row.packageName) {
            value = row.packageName?.let { p -> withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(p).toBitmap(96, 96).asImageBitmap() }.getOrNull() } }
        }
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                ?: Icon(androidx.compose.material.icons.Icons.Outlined.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Text(bytes(row.total), style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(progress = { (row.total.toFloat() / top).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), drawStopIndicator = {})
            Text(tr(R.string.stats_updown, bytes(row.tx), bytes(row.rx)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------------------------------------------------------------ Connections

/**
 * Live connection list for Settings › Data › Connections: its own client on the core's private command socket ([VpnTraffic.serving]),
 * a report every second while the page is on screen, the active connections only. Close one / all through the same socket (the core's
 * clash_api connection tracker closes them). Nothing is decrypted or logged: hosts never reach the log.
 */
private class LiveConnectionsClient(private val onList: (List<org.mothvpn.core.LiveConnections.Item>, ClosedLists) -> Unit,
    private val onLost: () -> Unit) {
    private var client: io.nekohasekai.libbox.CommandClient? = null
    private fun item(c: io.nekohasekai.libbox.Connection, id: String): org.mothvpn.core.LiveConnections.Item {
        val network = c.network.orEmpty(); val source = c.source.orEmpty()
        return org.mothvpn.core.LiveConnections.Item(id, network, c.protocol.orEmpty(), source, c.destination.orEmpty(), c.domain.orEmpty(),
            c.outbound.orEmpty(), c.createdAt, c.uplink, c.downlink, c.uplinkTotal, c.downlinkTotal, VpnTraffic.ownerApp(id, network, source), c.closedAt,
            c.rule.orEmpty(), c.outboundType.orEmpty(), c.inbound.orEmpty())
    }
    private val handler = object : io.nekohasekai.libbox.CommandClientHandler {
        override fun connected() {}
        override fun disconnected(message: String?) { onLost() }
        override fun clearLogs() {}
        override fun writeLogs(messageList: io.nekohasekai.libbox.StringIterator?) {}
        override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage?) {}
        override fun writeGroups(message: io.nekohasekai.libbox.OutboundGroupIterator?) {}
        override fun initializeClashMode(modeList: io.nekohasekai.libbox.StringIterator?, currentMode: String?) {}
        override fun updateClashMode(newMode: String?) {}
        override fun writeConnections(message: io.nekohasekai.libbox.Connections?) {
            message ?: return
            runCatching {
                message.filterState(io.nekohasekai.libbox.Libbox.ConnectionStateActive.toInt())
                val out = ArrayList<org.mothvpn.core.LiveConnections.Item>()
                val it = message.iterator()
                while (it.hasNext()) { val c = it.next(); val id = c.id ?: continue; out += item(c, id) }
                // The core already keeps a bounded list of closed connections for this VPN session (gone when the VPN stops); only the
                // newest few of those that ended by themselves and of those the user closed here ([VpnTraffic.closedByUser]) are converted.
                message.filterState(io.nekohasekai.libbox.Libbox.ConnectionStateClosed.toInt())
                val manual = VpnTraffic.closedByUser()
                val limit = org.mothvpn.core.LiveConnections.RECENT_LIMIT
                val natural = ArrayList<Pair<Long, io.nekohasekai.libbox.Connection>>(); val mine = ArrayList<Pair<Long, io.nekohasekai.libbox.Connection>>()
                val ci = message.iterator()
                while (ci.hasNext()) { val c = ci.next(); val id = c.id ?: continue; (if (id in manual) mine else natural) += c.closedAt to c }
                fun convert(raw: List<Pair<Long, io.nekohasekai.libbox.Connection>>) = raw.sortedByDescending { it.first }.take(limit)
                    .mapNotNull { (_, c) -> c.id?.let { id -> item(c, id) } }
                val (n, m) = org.mothvpn.core.LiveConnections.recentlyClosedSplit(convert(natural) + convert(mine), manual)
                onList(out, ClosedLists(n, m))
            }.onFailure { Logger.w("Connections", "reading the connection list failed: ${it.javaClass.simpleName}") }
        }
    }
    fun connect(): Boolean = runCatching {
        client = io.nekohasekai.libbox.CommandClient(handler, io.nekohasekai.libbox.CommandClientOptions().apply {
            command = io.nekohasekai.libbox.Libbox.CommandConnections; statusInterval = LIVE_INTERVAL_MS * 1_000_000L
        }).also { it.connect() }
    }.onFailure { Logger.w("Connections", "connection list unavailable: ${it.javaClass.simpleName}") }.isSuccess
    fun close(id: String) = runCatching { client?.closeConnection(id) }.onFailure { Logger.w("Connections", "closing a connection failed: ${it.javaClass.simpleName}") }.isSuccess
    fun closeAll() = runCatching { client?.closeConnections() }.onFailure { Logger.w("Connections", "closing all connections failed: ${it.javaClass.simpleName}") }.isSuccess
    fun disconnect() { client?.let { runCatching { it.disconnect() } }; client = null }
}
private const val LIVE_INTERVAL_MS = 1_000L
/** Recently closed connections: those that ended by themselves and those the user closed on the page. */
private data class ClosedLists(val natural: List<org.mothvpn.core.LiveConnections.Item>, val manual: List<org.mothvpn.core.LiveConnections.Item>)

/** App name and package (for the icon) of a connection owner ([VpnTraffic.ownerApp]): a package, "uid:N" or null (not identified). */
private fun ownerLabel(context: Context, app: String?): Pair<String, String?> = when {
    app == null -> tr(R.string.appdata_unknown) to null
    app.startsWith("uid:") -> {
        val uid = app.removePrefix("uid:").toIntOrNull() ?: 0
        (if (uid % 100_000 < Process.FIRST_APPLICATION_UID) tr(R.string.appdata_system) + " · UID $uid" else "UID $uid") to null
    }
    app == context.packageName -> tr(R.string.appdata_moth) to app
    else -> (runCatching { context.packageManager.getApplicationInfo(app, 0).loadLabel(context.packageManager).toString() }.getOrNull() ?: app) to app
}

/**
 * Settings › Data › Connections (like Clash clients' connection monitor): what goes through the VPN right now — the app (or its UID),
 * where it goes (domain or IP and port), TCP/UDP and the detected protocol, speed and bytes — refreshed every second while the page
 * is open, with a button to close a connection (the app usually opens a new one) or all of them. Needs the VPN on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ConnectionsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val serving by VpnTraffic.serving.collectAsState()
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> visible = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // null: waiting for the first report; failed: the socket could not be reached.
    var items by remember { mutableStateOf<List<org.mothvpn.core.LiveConnections.Item>?>(null) }
    var closedItems by remember { mutableStateOf<ClosedLists?>(null) }
    var failed by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<LiveConnectionsClient?>(null) }
    DisposableEffect(serving, visible) {
        items = null; closedItems = null; failed = false
        val c = if (serving && visible) LiveConnectionsClient({ list, closed -> items = list; closedItems = closed; failed = false },
            { if (items == null) failed = true }) else null
        if (c != null && !c.connect()) failed = true
        live = c
        onDispose { c?.disconnect(); if (live === c) live = null }
    }
    var sort by rememberSaveable { mutableStateOf(org.mothvpn.core.LiveConnections.Sort.NEWEST) }
    var groupBy by rememberSaveable { mutableStateOf(org.mothvpn.core.LiveConnections.GroupBy.APP) }
    val expanded = remember { mutableStateListOf<String>() } // "GROUPBY:key" of open groups
    var confirmAll by remember { mutableStateOf(false) }
    val closing = remember { mutableStateMapOf<String, Long>() } // id -> when its close was sent: hidden until the core drops it
    val locale = AppLanguage.context().resources.configuration.locales[0]
    val units = tr(R.string.notif_units).split("|")
    fun bytes(v: Long) = TrafficFormat.bytes(v, units, locale)
    val labels = remember { HashMap<String?, Pair<String, String?>>() }
    val userRules by produceState(emptyList<org.mothvpn.core.RoutingRule>()) {
        value = withContext(Dispatchers.IO) { runCatching { SettingsStore(context).load().routingRules }.getOrDefault(emptyList()) }
    }
    CompositionLocalProvider(LocalUserRules provides userRules) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val list = items
        when {
            !serving -> ListCard(tr(R.string.conn_off_title), tr(R.string.conn_off_text)) { Spacer(Modifier.height(8.dp)) }
            failed -> ListCard(tr(R.string.conn_failed_title), tr(R.string.conn_failed_text)) { Spacer(Modifier.height(8.dp)) }
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                val now = System.currentTimeMillis()
                closing.entries.removeIf { (id, at) -> now - at > 5_000 || list.none { it.id == id } }
                val shown = org.mothvpn.core.LiveConnections.sorted(list.filter { it.id !in closing }, sort)
                ListCard(tr(R.string.conn_active, shown.size), tr(R.string.notif_speed,
                    bytes(org.mothvpn.core.LiveConnections.perSecond(shown.sumOf { it.up }, LIVE_INTERVAL_MS)),
                    bytes(org.mothvpn.core.LiveConnections.perSecond(shown.sumOf { it.down }, LIVE_INTERVAL_MS)))) {
                    if (shown.isNotEmpty()) InfoRow(tr(R.string.conn_close_all), null, onClick = { confirmAll = true })
                    else Spacer(Modifier.height(8.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(org.mothvpn.core.LiveConnections.Sort.NEWEST to R.string.conn_sort_new, org.mothvpn.core.LiveConnections.Sort.SPEED to R.string.conn_sort_speed,
                        org.mothvpn.core.LiveConnections.Sort.TRAFFIC to R.string.conn_sort_traffic).forEach { (s, label) ->
                        FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(tr(label)) }, modifier = Modifier.pressScale())
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(org.mothvpn.core.LiveConnections.GroupBy.APP to R.string.conn_group_app, org.mothvpn.core.LiveConnections.GroupBy.HOST to R.string.conn_group_host,
                        org.mothvpn.core.LiveConnections.GroupBy.NONE to R.string.conn_group_none).forEach { (g, label) ->
                        FilterChip(selected = groupBy == g, onClick = { groupBy = g }, label = { Text(tr(label)) }, modifier = Modifier.pressScale())
                    }
                }
                if (shown.isEmpty()) DataCard { Hint(tr(R.string.conn_empty)) }
                else Card(Modifier.fillMaxWidth(), colors = ndCardColors()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        fun close(c: org.mothvpn.core.LiveConnections.Item) { if (live?.close(c.id) == true) { closing[c.id] = System.currentTimeMillis(); VpnTraffic.markClosedByUser(listOf(c.id)) } }
                        val byApp = groupBy == org.mothvpn.core.LiveConnections.GroupBy.APP
                        // Groups (or single connections) shown; a group's rows count against the same limit so a huge list stays light.
                        var budget = 200
                        org.mothvpn.core.LiveConnections.grouped(shown, groupBy, sort).forEachIndexed { i, g ->
                            if (budget > 0) key("${groupBy.name}:${g.key}") {
                                if (i > 0) RowDivider()
                                val (appLabel, pkg) = labels.getOrPut(g.app) { ownerLabel(context, g.app) }
                                budget--
                                if (g.items.size == 1) {
                                    val c = g.items[0]
                                    val dk = "detail:${c.id}"
                                    ConnectionRow(c, appLabel, pkg, now, ::bytes, open = dk in expanded, toggle = { if (dk in expanded) expanded.remove(dk) else expanded.add(dk) }) { close(c) }
                                } else {
                                    val stateKey = "${groupBy.name}:${g.key}"
                                    val open = stateKey in expanded
                                    val title = if (byApp) appLabel else g.key
                                    ConnectionGroupRow(g, title, if (byApp) pkg else null, byApp, open, ::bytes,
                                        toggle = { if (open) expanded.remove(stateKey) else expanded.add(stateKey) },
                                        onClose = {
                                            // No confirmation: closing an app's (or host's) connections is harmless, the app reconnects.
                                            val at = System.currentTimeMillis()
                                            val sent = g.items.filter { c -> live?.close(c.id) == true }
                                            sent.forEach { c -> closing[c.id] = at }
                                            VpnTraffic.markClosedByUser(sent.map { it.id })
                                        })
                                    // The rows fold out of the group row (the same fold as nested settings) and back into it.
                                    val rows = g.items.take(budget.coerceAtLeast(0))
                                    if (open) budget -= rows.size
                                    FoldIn(open) {
                                        rows.forEach { c ->
                                            key(c.id) {
                                                val (childLabel, childPkg) = labels.getOrPut(c.app) { ownerLabel(context, c.app) }
                                                val dk = "detail:${c.id}"
                                                RowDivider()
                                                ConnectionRow(c, if (byApp) null else childLabel, childPkg, now, ::bytes, nested = true,
                                                    open = dk in expanded, toggle = { if (dk in expanded) expanded.remove(dk) else expanded.add(dk) }) { close(c) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                RecentlyClosedCard(closedItems, groupBy, now, labels, expanded, ::bytes)
            }
        }
    }
    }
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false }, title = { Text(tr(R.string.conn_close_all)) }, text = { Text(tr(R.string.conn_close_all_text)) },
        confirmButton = { TextButton(onClick = {
            confirmAll = false
            val ids = items.orEmpty().map { it.id }
            if (live?.closeAll() == true) VpnTraffic.markClosedByUser(ids)
        }) { Text(tr(R.string.conn_close_all_ok)) } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(tr(R.string.msg_cancel_32)) } })
}

/** A group of connections (one app or one host): count, summed totals and rate; tap to expand, × closes every connection in it. */
@Composable private fun ConnectionGroupRow(g: org.mothvpn.core.LiveConnections.Group, title: String, pkg: String?, byApp: Boolean, open: Boolean, bytes: (Long) -> String,
    toggle: () -> Unit, onClose: (() -> Unit)?) {
    val context = LocalContext.current
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 0f else -90f, label = "chevron")
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle)
        .semantics { contentDescription = tr(if (open) R.string.group_collapse else R.string.group_expand) + ": " + title }
        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon by produceState<ImageBitmap?>(null, pkg) {
            value = pkg?.let { p -> withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(p).toBitmap(96, 96).asImageBitmap() }.getOrNull() } }
        }
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                ?: Icon(if (byApp) androidx.compose.material.icons.Icons.Outlined.Android else androidx.compose.material.icons.Icons.Outlined.Public,
                    contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr(R.string.conn_group_count, g.items.size), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(tr(R.string.stats_updown, bytes(g.upTotal), bytes(g.downTotal)) + if (g.up + g.down > 0)
                "  (" + tr(R.string.conn_rate, bytes(org.mothvpn.core.LiveConnections.perSecond(g.up + g.down, LIVE_INTERVAL_MS))) + ")" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn })
        if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.pressScale()) {
            Icon(androidx.compose.material.icons.Icons.Outlined.Close, contentDescription = tr(R.string.conn_close_group, title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Spacer(Modifier.width(12.dp))
    }
}

/**
 * One connection. [label]: the owner app shown as the headline; null inside an app group (the group row names the app), then the target
 * is the headline. [nested]: a row inside an expanded group, indented under the group's icon. Tapping the row ([toggle]) folds out its
 * details under it ([open]): every field in full, wrapped instead of cut off. Only what the core reports about the connection
 * (addresses, protocol, route, time, byte counts); nothing of the traffic itself.
 */
@Composable private fun ConnectionRow(c: org.mothvpn.core.LiveConnections.Item, label: String?, pkg: String?, now: Long, bytes: (Long) -> String,
    nested: Boolean = false, byUser: Boolean = false, open: Boolean = false, toggle: (() -> Unit)? = null, onClose: (() -> Unit)?) {
    val context = LocalContext.current
    val target = org.mothvpn.core.LiveConnections.target(c.domain, c.destination)
    val route = when (c.outbound) { "direct" -> tr(R.string.conn_direct); "block" -> tr(R.string.conn_blocked); "", "dns-out", "dns" -> null; else -> tr(R.string.conn_proxy) }
    val age = when {
        c.closedAtMs > 0 -> tr(if (byUser) R.string.conn_closed_by_you_ago else R.string.conn_closed_ago, TrafficFormat.duration(((now - c.closedAtMs) / 1000).coerceAtLeast(0)))
        c.createdAtMs > 0 -> TrafficFormat.duration(((now - c.createdAtMs) / 1000).coerceAtLeast(0))
        else -> null
    }
    val proto = org.mothvpn.core.LiveConnections.protocolLabel(c.network, c.protocol)
    val rate = if (c.up + c.down > 0) tr(R.string.conn_rate, bytes(org.mothvpn.core.LiveConnections.perSecond(c.up + c.down, LIVE_INTERVAL_MS))) else null
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().let { m -> if (toggle != null) m.clickable(onClick = toggle)
            .semantics { contentDescription = tr(if (open) R.string.group_collapse else R.string.group_expand) + ": " + (label ?: target) } else m }
        .padding(start = if (nested) 60.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      if (!nested || label != null) {
        val icon by produceState<ImageBitmap?>(null, pkg) {
            value = pkg?.let { p -> withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(p).toBitmap(96, 96).asImageBitmap() }.getOrNull() } }
        }
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                ?: Icon(androidx.compose.material.icons.Icons.Outlined.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
        Column(Modifier.weight(1f).padding(start = if (nested && label == null) 0.dp else 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (label != null) Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(target, style = if (label != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(proto.ifBlank { null }, route, age).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr(R.string.stats_updown, bytes(c.upTotal), bytes(c.downTotal)) + (rate?.let { "  ($it)" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.pressScale()) {
            Icon(androidx.compose.material.icons.Icons.Outlined.Close, contentDescription = tr(R.string.conn_close, label ?: target), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Spacer(Modifier.width(12.dp))
    }
    if (toggle != null) FoldIn(open, Modifier.padding(start = 60.dp, end = 16.dp, bottom = 12.dp)) {
        ConnectionDetails(c, pkg, now, bytes, route)
    }
    }
}

/** The user's routing rules (for "Your rule #N"), loaded once by the Connections page. */
private val LocalUserRules = staticCompositionLocalOf { emptyList<org.mothvpn.core.RoutingRule>() }

/**
 * Everything Moth knows about one connection, in four groups split by thin dividers: what (app, package, who opened it), where
 * (domain, address, protocol), via (route, server and its protocol, rule), time and traffic. One row per field: a small muted label
 * and the value; short values sit to the right of the label, long ones wrap under it. Only what the core and Moth know about the
 * connection, never what goes through it.
 */
@Composable private fun ConnectionDetails(c: org.mothvpn.core.LiveConnections.Item, pkg: String?, now: Long, bytes: (Long) -> String, route: String?) {
    val context = LocalContext.current
    val activeServer by TunnelStatus.activeServer.collectAsState()
    val userRules = LocalUserRules.current
    val clock = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss") }
    fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(clock)
    val origin = org.mothvpn.core.LiveConnections.origin(c.inbound)
    val groups = buildList<List<Pair<String, @Composable () -> Unit>>> {
        // What
        add(buildList {
            if (origin != org.mothvpn.core.LiveConnections.Origin.SUBSCRIPTION) {
                val (appName, _) = ownerLabel(context, c.app)
                add(tr(R.string.conn_detail_app) to { DetailValue(appName) })
                pkg?.takeIf { it != appName }?.let { add(tr(R.string.conn_detail_package) to { DetailValue(it) }) }
            }
            add(tr(R.string.conn_detail_opened_by) to { DetailValue(tr(when (origin) {
                org.mothvpn.core.LiveConnections.Origin.SHARING -> R.string.conn_origin_sharing
                org.mothvpn.core.LiveConnections.Origin.SUBSCRIPTION -> R.string.conn_origin_subscription
                else -> R.string.conn_origin_device })) })
            // The tunnel's own source (an internal address of the VPN interface) means nothing; a sharing client's address does.
            if (origin == org.mothvpn.core.LiveConnections.Origin.SHARING && c.source.isNotBlank()) add(tr(R.string.conn_detail_source) to { DetailValue(c.source) })
        })
        // Where
        add(buildList {
            if (c.domain.isNotBlank()) add(tr(R.string.conn_detail_domain) to { DetailValue(c.domain) })
            if (c.destination.isNotBlank()) add(tr(R.string.conn_detail_destination) to { DetailValue(c.destination) })
            val proto = org.mothvpn.core.LiveConnections.protocolLabel(c.network, c.protocol)
            if (proto.isNotBlank()) add(tr(R.string.conn_detail_protocol) to { DetailValue(proto) })
        })
        // Via
        add(buildList {
            route?.let { r -> add(tr(R.string.conn_detail_route) to { DetailValue(r) }) }
            val proxied = c.outbound.isNotBlank() && c.outbound !in setOf("direct", "block", "dns", "dns-out")
            activeServer?.takeIf { proxied }?.let { p -> add(tr(R.string.conn_detail_server) to {
                Column { DetailValue(p.name); Text(serverSubtitle(p), style = MaterialTheme.typography.bodySmall) } }) }
            val rule = when (val m = org.mothvpn.core.LiveConnections.ruleMatch(c.rule, userRules)) {
                org.mothvpn.core.LiveConnections.RuleMatch.Default -> tr(R.string.conn_rule_default)
                org.mothvpn.core.LiveConnections.RuleMatch.RussiaDirect -> tr(R.string.set_short_ru)
                org.mothvpn.core.LiveConnections.RuleMatch.Dns -> tr(R.string.conn_rule_dns)
                org.mothvpn.core.LiveConnections.RuleMatch.SubscriptionUpdate -> tr(R.string.conn_origin_subscription)
                org.mothvpn.core.LiveConnections.RuleMatch.SplitTunnel -> tr(R.string.split_title)
                org.mothvpn.core.LiveConnections.RuleMatch.Ipv6Off -> tr(R.string.conn_rule_ipv6)
                org.mothvpn.core.LiveConnections.RuleMatch.Sharing -> tr(R.string.conn_rule_sharing)
                is org.mothvpn.core.LiveConnections.RuleMatch.Custom -> tr(R.string.conn_rule_custom, m.number)
                is org.mothvpn.core.LiveConnections.RuleMatch.Other -> m.text
            }
            add(tr(R.string.conn_detail_rule) to { DetailValue(rule) })
        })
        // Time and traffic
        add(buildList {
            if (c.createdAtMs > 0) add(tr(R.string.conn_detail_opened_at) to { DetailValue(at(c.createdAtMs)) })
            if (c.closedAtMs > 0) add(tr(R.string.conn_detail_closed_at) to { DetailValue(at(c.closedAtMs)) })
            if (c.createdAtMs > 0) add(tr(R.string.conn_detail_duration) to {
                DetailValue(TrafficFormat.duration((((if (c.closedAtMs > 0) c.closedAtMs else now) - c.createdAtMs) / 1000).coerceAtLeast(0))) })
            add(tr(R.string.conn_detail_sent) to { DetailValue(bytes(c.upTotal)) })
            add(tr(R.string.conn_detail_received) to { DetailValue(bytes(c.downTotal)) })
            if (c.closedAtMs == 0L) add(tr(R.string.conn_detail_speed) to { DetailValue(tr(R.string.stats_updown,
                tr(R.string.conn_rate, bytes(org.mothvpn.core.LiveConnections.perSecond(c.up, LIVE_INTERVAL_MS))),
                tr(R.string.conn_rate, bytes(org.mothvpn.core.LiveConnections.perSecond(c.down, LIVE_INTERVAL_MS))))) })
        })
    }.filter { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth()) {
        groups.forEachIndexed { gi, rows ->
            if (gi > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { rows.forEach { (label, value) -> DetailRow(label, value) } }
        }
    }
}

/** One details field: the muted label on the left (fixed width), the value on the right; the value wraps within its column. */
@Composable private fun DetailRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp).padding(top = 2.dp, end = 8.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}
@Composable private fun DetailValue(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, softWrap = true)

/**
 * "Recently closed" under the active list, always open: the newest closed connections of this VPN session (the core's own bounded list;
 * it empties when the VPN stops), grouped like the active list (tap a group to expand), and at the bottom the ones the user closed here
 * (×, a group's ×, Close all). Read-only rows.
 */
@Composable private fun RecentlyClosedCard(lists: ClosedLists?, groupBy: org.mothvpn.core.LiveConnections.GroupBy, now: Long,
    labels: HashMap<String?, Pair<String, String?>>, expanded: MutableList<String>, bytes: (Long) -> String) {
    Card(Modifier.fillMaxWidth(), colors = ndCardColors()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr(R.string.conn_recent_title), style = MaterialTheme.typography.titleMedium)
                Text(tr(R.string.conn_recent_sum, org.mothvpn.core.LiveConnections.RECENT_LIMIT), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                lists == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                lists.natural.isEmpty() && lists.manual.isEmpty() -> Text(tr(R.string.conn_recent_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                else -> {
                    ClosedSection(lists.natural, false, groupBy, now, labels, expanded, bytes)
                    if (lists.manual.isNotEmpty()) {
                        RowDivider()
                        Text(tr(R.string.conn_recent_manual, lists.manual.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
                        ClosedSection(lists.manual, true, groupBy, now, labels, expanded, bytes)
                    }
                }
            }
        }
    }
}

/** One part of Recently closed (ended by themselves, or closed by the user): grouped rows, a group expands on tap. */
@Composable private fun ClosedSection(items: List<org.mothvpn.core.LiveConnections.Item>, byUser: Boolean, groupBy: org.mothvpn.core.LiveConnections.GroupBy, now: Long,
    labels: HashMap<String?, Pair<String, String?>>, expanded: MutableList<String>, bytes: (Long) -> String) {
    val context = LocalContext.current
    val byApp = groupBy == org.mothvpn.core.LiveConnections.GroupBy.APP
    org.mothvpn.core.LiveConnections.groupedClosed(items, groupBy).forEach { g ->
        val stateKey = (if (byUser) "closed-user:" else "closed:") + "${groupBy.name}:${g.key}"
        key(stateKey) {
            RowDivider()
            val (appLabel, pkg) = labels.getOrPut(g.app) { ownerLabel(context, g.app) }
            if (g.items.size == 1) {
                val c = g.items[0]; val dk = "closed-detail:${c.id}"
                ConnectionRow(c, appLabel, pkg, now, bytes, byUser = byUser, open = dk in expanded, toggle = { if (dk in expanded) expanded.remove(dk) else expanded.add(dk) }, onClose = null)
            }
            else {
                val open = stateKey in expanded
                ConnectionGroupRow(g, if (byApp) appLabel else g.key, if (byApp) pkg else null, byApp, open, bytes,
                    toggle = { if (open) expanded.remove(stateKey) else expanded.add(stateKey) }, onClose = null)
                FoldIn(open) {
                    g.items.forEach { c ->
                        key(c.id) {
                            val (childLabel, childPkg) = labels.getOrPut(c.app) { ownerLabel(context, c.app) }
                            val dk = "closed-detail:${c.id}"
                            RowDivider()
                            ConnectionRow(c, if (byApp) null else childLabel, childPkg, now, bytes, nested = true, byUser = byUser,
                                open = dk in expanded, toggle = { if (dk in expanded) expanded.remove(dk) else expanded.add(dk) }, onClose = null)
                        }
                    }
                }
            }
        }
    }
}
