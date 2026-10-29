package org.mothvpn.android

import androidx.compose.material.icons.outlined.Android
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import android.provider.Settings
import androidx.compose.foundation.Image
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
@Composable private fun resumeCount(): Int {
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
@Composable private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------------------------------------------------------------------- Battery

private class BatteryNow(val level: Int?, val status: Int, val tempTenths: Int?, val currentMa: Long?)

/** The device battery now: the sticky ACTION_BATTERY_CHANGED broadcast and BatteryManager (no permission). */
private fun batteryNow(context: Context): BatteryNow? = runCatching {
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    // CURRENT_NOW is in microamperes; devices that report nonsense (0, MIN_VALUE, absurd values) get no row.
    val now = context.getSystemService(BatteryManager::class.java)?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    val ma = now?.takeIf { it != Long.MIN_VALUE && it != 0L }?.let { kotlin.math.abs(it) / 1000 }?.takeIf { it in 1..20_000 }
    BatteryNow(if (level >= 0 && scale > 0) level * 100 / scale else null, i.getIntExtra(BatteryManager.EXTRA_STATUS, -1), temp.takeIf { it != Int.MIN_VALUE }, ma)
}.onFailure { Logger.w("Battery", "battery state unavailable: ${it.javaClass.simpleName}") }.getOrNull()

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
 * Settings › Data › Battery. Only data Android gives without special permissions: the battery now (level, state, temperature, current),
 * Moth's own counters from Android's battery stats (processor time, estimated energy, background/foreground time, wakelocks) and the
 * processor time of the running Moth process; the battery optimization state, with links to Android's battery screens for the rest.
 */
@Composable fun BatteryPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resumed = resumeCount()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    val now by produceState<BatteryNow?>(null, resumed, tick) { value = withContext(Dispatchers.IO) { batteryNow(context) } }
    val moth by produceState<MothBattery?>(null, resumed, tick / 6) { value = withContext(Dispatchers.IO) { mothBattery(context) } }
    val session by produceState<Pair<Long, Long>?>(null, resumed, tick) {
        value = runCatching { Process.getElapsedCpuTime() to (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) }.getOrNull()
    }
    val ignoring = remember(resumed) { runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() }
    val locale = AppLanguage.context().resources.configuration.locales[0]
    fun mah(mAms: Long) = String.format(locale, "%.1f mAh", UsageStatsMath.mAh(mAms))
    fun time(ms: Long) = TrafficFormat.duration(ms / 1000)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DataCard {
            Text(tr(R.string.battery_now), style = MaterialTheme.typography.titleMedium)
            now?.let { b ->
                b.level?.let { ValueRow(tr(R.string.battery_level), "$it%") }
                ValueRow(tr(R.string.battery_status), when (b.status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> tr(R.string.battery_charging); BatteryManager.BATTERY_STATUS_FULL -> tr(R.string.battery_full)
                    else -> tr(R.string.battery_discharging) })
                b.tempTenths?.let { ValueRow(tr(R.string.battery_temperature), String.format(locale, "%.1f °C", it / 10.0)) }
                b.currentMa?.let { ValueRow(tr(R.string.battery_current), "$it mA") }
            }
        }
        DataCard {
            Text(tr(R.string.battery_moth), style = MaterialTheme.typography.titleMedium)
            val m = moth
            val energy = m?.let { listOfNotNull(it.cpuMams?.takeIf { v -> v > 0 }?.let { v -> tr(R.string.battery_energy_cpu, mah(v)) },
                it.wifiMams?.takeIf { v -> v > 0 }?.let { v -> tr(R.string.battery_energy_wifi, mah(v)) },
                it.mobileMams?.takeIf { v -> v > 0 }?.let { v -> tr(R.string.battery_energy_mobile, mah(v)) }) }.orEmpty()
            val rows = listOfNotNull(m?.cpuMs?.let { tr(R.string.battery_cpu) to time(it) }, m?.serviceMs?.let { tr(R.string.battery_background) to time(it) },
                m?.topMs?.let { tr(R.string.battery_foreground) to time(it) }, m?.wakeMs?.takeIf { it > 0 }?.let { tr(R.string.battery_wakelock) to time(it) },
                m?.radioMs?.takeIf { it > 0 }?.let { tr(R.string.battery_radio) to time(it) })
            if (energy.isNotEmpty()) ValueRow(tr(R.string.battery_energy), mah(listOfNotNull(m?.cpuMams, m?.wifiMams, m?.mobileMams).sumOf { it.coerceAtLeast(0) }))
            energy.forEach { Hint("· $it") }
            rows.forEach { (label, value) -> ValueRow(label, value) }
            if (m != null && energy.isEmpty() && rows.isEmpty()) Hint(tr(R.string.battery_unavailable))
            session?.let { (cpu, up) ->
                ValueRow(tr(R.string.battery_session), tr(R.string.battery_session_value, time(cpu), time(up), String.format(locale, "%.1f", UsageStatsMath.percent(cpu, up))))
            }
            Hint(tr(R.string.battery_moth_hint))
        }
        DataCard {
            Text(tr(R.string.battery_optimization), style = MaterialTheme.typography.titleMedium)
            if (ignoring != null) Text(tr(if (ignoring) R.string.battery_opt_off else R.string.battery_opt_on), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.pressScale()) { Text(tr(R.string.battery_opt_open)) }
            TextButton(onClick = { open(context, Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.pressScale()) { Text(tr(R.string.battery_system)) }
        }
        Hint(tr(R.string.battery_hint))
    }
}

// ---------------------------------------------------------------------------------------------------------------------------- App data

/** Whether the user granted Moth "Usage access" (PACKAGE_USAGE_STATS is an app op, not a runtime permission). */
fun hasUsageAccess(context: Context): Boolean = runCatching {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    if (mode == AppOpsManager.MODE_DEFAULT) context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    else mode == AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

/** Per-uid traffic from Android's data usage counter (NetworkStatsManager.querySummary) on Wi-Fi and/or mobile; null when nothing could be read. */
private fun queryUsage(context: Context, days: Int, network: Int): List<UsageStatsMath.UidTraffic>? {
    val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return null
    val start = UsageStatsMath.periodStart(days, LocalDate.now(), ZoneId.systemDefault())
    val end = System.currentTimeMillis()
    val types = when (network) { 1 -> listOf(ConnectivityManager.TYPE_WIFI); 2 -> listOf(ConnectivityManager.TYPE_MOBILE); else -> listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE) }
    val rows = mutableListOf<UsageStatsMath.UidTraffic>(); var read = false
    for (type in types) runCatching {
        @Suppress("DEPRECATION") val stats = nsm.querySummary(type, null, start, end)
        try {
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) { stats.getNextBucket(bucket); rows += UsageStatsMath.UidTraffic(bucket.uid, bucket.rxBytes, bucket.txBytes) }
        } finally { stats.close() }
        read = true
    }.onFailure { Logger.w("AppData", "data usage query failed: ${it.javaClass.simpleName}") }
    return if (read) UsageStatsMath.aggregate(rows) else null
}

private class AppUsageRow(val key: String, val label: String, val packageName: String?, val rx: Long, val tx: Long) { val total get() = rx + tx }

/** Names the uids (apps Moth cannot see because of package visibility, system uids, removed apps) and merges the ones with the same name. */
private fun labelled(context: Context, list: List<UsageStatsMath.UidTraffic>): List<AppUsageRow> {
    val pm = context.packageManager
    return list.map { u ->
        val (key, label, pkg) = when {
            u.uid == NetworkStats.Bucket.UID_REMOVED -> Triple("removed", tr(R.string.appdata_removed), null)
            u.uid == NetworkStats.Bucket.UID_TETHERING -> Triple("tethering", tr(R.string.appdata_tethering), null)
            u.uid == Process.myUid() -> Triple("moth", tr(R.string.appdata_moth), context.packageName)
            u.uid < Process.FIRST_APPLICATION_UID -> Triple("system", tr(R.string.appdata_system), null)
            else -> {
                val name = pm.getPackagesForUid(u.uid)?.firstOrNull()
                val app = name?.let { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
                if (name == null || app == null) Triple("other", tr(R.string.appdata_other), null) else Triple(name, app.loadLabel(pm).toString(), name)
            }
        }
        AppUsageRow(key, label, pkg, u.rx, u.tx)
    }.groupBy { it.key }.map { (key, rows) -> AppUsageRow(key, rows.first().label, rows.first().packageName, rows.sumOf { it.rx }, rows.sumOf { it.tx }) }
        .sortedByDescending { it.total }
}

/**
 * Settings › Data › App data usage: traffic per app for today / 7 / 30 days on Wi-Fi, mobile or both, from Android's own counter. Needs
 * "Usage access" (explained on the page, with a button to Android's settings); without it only Moth's own traffic (TrafficStats) is shown.
 */
@Composable fun AppDataUsagePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resumed = resumeCount()
    val access = remember(resumed) { hasUsageAccess(context) }
    var days by rememberSaveable { mutableIntStateOf(1) }
    var network by rememberSaveable { mutableIntStateOf(0) }
    val locale = AppLanguage.context().resources.configuration.locales[0]
    val units = tr(R.string.notif_units).split("|")
    fun bytes(v: Long) = TrafficFormat.bytes(v, units, locale)
    // null while loading; the list (empty when Android returned nothing) and whether the query failed.
    val result by produceState<Pair<List<AppUsageRow>, Boolean>?>(null, access, days, network, resumed) {
        value = null
        if (access) value = withContext(Dispatchers.IO) { queryUsage(context, days, network)?.let { labelled(context, it) to false } ?: (emptyList<AppUsageRow>() to true) }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!access) {
            DataCard {
                Text(tr(R.string.appdata_access_title), style = MaterialTheme.typography.titleMedium)
                Text(tr(R.string.appdata_access_text), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { open(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, modifier = Modifier.pressScale()) { Text(tr(R.string.appdata_access_button)) }
            }
            val own = remember(resumed) { Process.myUid().let { TrafficStats.getUidTxBytes(it) to TrafficStats.getUidRxBytes(it) } }
            if (own.first >= 0 && own.second >= 0) DataCard {
                ValueRow(tr(R.string.appdata_own), tr(R.string.stats_updown, bytes(own.first), bytes(own.second)))
            }
        } else {
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
            val list = result?.first; val failed = result?.second == true
            if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else DataCard {
                Text(tr(R.string.appdata_total, bytes(list.sumOf { it.total })), style = MaterialTheme.typography.titleMedium)
                if (list.isEmpty()) Hint(tr(if (failed) R.string.appdata_failed else R.string.appdata_empty))
                val top = list.firstOrNull()?.total?.coerceAtLeast(1) ?: 1
                list.take(100).forEach { row -> AppUsageItem(row, top, ::bytes) }
            }
        }
        Hint(tr(R.string.appdata_hint))
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
