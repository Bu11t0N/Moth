package org.mothvpn.android

import androidx.compose.material.icons.outlined.Android
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
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
    val ignoring = remember(resumed) { runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() }
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
        ListCard(tr(R.string.battery_optimization), ignoring?.let { tr(if (it) R.string.battery_opt_off else R.string.battery_opt_on) }) {
            InfoRow(tr(R.string.battery_opt_open), null, onClick = { open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
            RowDivider()
            InfoRow(tr(R.string.battery_system), null, onClick = { open(context, Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))) })
        }
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

/**
 * Settings › Data › App traffic: traffic per app for today / 7 / 30 days on Wi-Fi, mobile or both. By default from the VPN's own
 * counter (apps going through the VPN while it is on, Android 10+, no permission). With "Usage access" also all traffic from Android's
 * data usage counter; the page explains how to allow it, including Android 13+'s "restricted settings" block for apps not from a store.
 */
@Composable fun AppDataUsagePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resumed = resumeCount()
    val access = remember(resumed) { hasUsageAccess(context) }
    var source by rememberSaveable { mutableIntStateOf(0) } // 0: the VPN's counter, 1: Android's (needs usage access)
    var days by rememberSaveable { mutableIntStateOf(1) }
    var network by rememberSaveable { mutableIntStateOf(0) }
    val android = source == 1 && access
    val counted by VpnTraffic.changed.collectAsState()
    val locale = AppLanguage.context().resources.configuration.locales[0]
    val units = tr(R.string.notif_units).split("|")
    fun bytes(v: Long) = TrafficFormat.bytes(v, units, locale)
    // null while loading; the list (empty when nothing was counted) and whether the query failed.
    val result by produceState<Pair<List<AppUsageRow>, Boolean>?>(null, android, days, network, resumed, if (android) 0L else counted / 10_000) {
        if (android) {
            value = null
            value = withContext(Dispatchers.IO) { queryUsage(context, days, network)?.let { labelled(context, it) to false } ?: (emptyList<AppUsageRow>() to true) }
        } else value = withContext(Dispatchers.IO) {
            labelledVpn(context, VpnTraffic.query(context, days, when (network) { 1 -> "wifi"; 2 -> "mobile"; else -> null })) to false
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (access) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to R.string.appdata_source_vpn, 1 to R.string.appdata_source_android).forEach { (n, label) ->
                FilterChip(selected = (if (android) 1 else 0) == n, onClick = { source = n }, label = { Text(tr(label)) }, modifier = Modifier.pressScale())
            }
        }
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
            if (list.isEmpty()) Hint(tr(when {
                android -> if (failed) R.string.appdata_failed else R.string.appdata_empty
                Build.VERSION.SDK_INT < 29 -> R.string.appdata_vpn_old
                else -> R.string.appdata_vpn_empty }))
            val top = list.firstOrNull()?.total?.coerceAtLeast(1) ?: 1
            list.take(100).forEach { row -> AppUsageItem(row, top, ::bytes) }
        }
        Hint(tr(if (android) R.string.appdata_hint else R.string.appdata_vpn_hint))
        if (!access) UsageAccessCard(context)
    }
}

/** Optional "all traffic" source: what usage access gives, the button to allow it and what to do when Android blocks it (restricted settings). */
@Composable private fun UsageAccessCard(context: Context) {
    ListCard(tr(R.string.appdata_access_title), tr(R.string.appdata_access_text)) {
        InfoRow(tr(R.string.appdata_access_button), null, onClick = {
            open(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        // Android 13+ blocks usage access for apps installed from a file ("Access to this app is restricted") until it is allowed in App info.
        if (Build.VERSION.SDK_INT >= 33) {
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr(R.string.appdata_restricted_title), style = MaterialTheme.typography.bodyLarge)
                Text(tr(R.string.appdata_restricted_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InfoRow(tr(R.string.appdata_restricted_button), null, onClick = {
                open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
            })
        }
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
