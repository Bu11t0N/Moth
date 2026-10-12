package org.mothvpn.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.work.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.mothvpn.core.ExpiryWarning
import org.mothvpn.core.TrafficFormat
import org.mothvpn.core.TrafficHistory
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Per-day VPN traffic, kept in memory and written to disk at most once a minute and on disconnect. */
object TrafficRecorder {
    private const val FILE = "traffic_stats"
    private val lock = Any()
    private var history: TrafficHistory? = null
    private var dirty = false
    private var lastFlush = 0L
    private val mutable = MutableStateFlow(TrafficHistory())
    val state = mutable.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun loaded(context: Context): TrafficHistory = history ?: runCatching {
        TrafficHistory.from(prefs(context).getString("days", null)?.let(::JSONObject))
    }.onFailure { Logger.w("Stats", "reading traffic statistics failed", it) }.getOrDefault(TrafficHistory()).also { history = it; mutable.value = it }
    fun load(context: Context): TrafficHistory = synchronized(lock) { loaded(context) }
    fun add(context: Context, tx: Long, rx: Long) = synchronized(lock) {
        if (tx <= 0 && rx <= 0) return@synchronized
        history = loaded(context).add(LocalDate.now(), tx, rx); dirty = true
        mutable.value = history!!
        if (System.currentTimeMillis() - lastFlush >= 60_000) flushLocked(context)
    }
    fun flush(context: Context) = synchronized(lock) { flushLocked(context) }
    private fun flushLocked(context: Context) {
        lastFlush = System.currentTimeMillis()
        val h = history ?: return
        if (!dirty) return
        val pruned = h.prune(LocalDate.now()); history = pruned; mutable.value = pruned; dirty = false
        prefs(context).edit().putString("days", pruned.json().toString()).apply()
    }
}

@Composable fun StatsPage(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { TrafficRecorder.load(context) }
    val history by TrafficRecorder.state.collectAsState()
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); today = LocalDate.now() } }
    val locale = AppLanguage.context().resources.configuration.locales[0]
    val units = tr(R.string.notif_units).split("|")
    fun fmt(p: Pair<Long, Long>) = tr(R.string.stats_updown, TrafficFormat.bytes(p.first, units, locale), TrafficFormat.bytes(p.second, units, locale))
    val series = remember(history, today) { history.series(today, 30).reversed() }
    val max = series.maxOfOrNull { it.second.first + it.second.second }?.coerceAtLeast(1) ?: 1
    val dayFormat = remember(locale) { java.time.format.DateTimeFormatter.ofPattern("d MMM", locale) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(R.string.stats_today) + ": " + fmt(history.day(today)))
                Text(tr(R.string.stats_month) + ": " + fmt(history.month(today)))
                Text(tr(R.string.stats_30_days) + ": " + fmt(history.lastDays(today, 30)))
                Text(tr(R.string.stats_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(series, key = { it.first.toString() }) { (day, v) ->
                val total = v.first + v.second
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(day.format(dayFormat), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                    Box(Modifier.weight(1f).height(14.dp)) {
                        if (total > 0) Box(Modifier.fillMaxHeight().fillMaxWidth((total.toFloat() / max).coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(if (total > 0) TrafficFormat.bytes(total, units, locale) else "—", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp).width(76.dp))
                }
            }
        }
    }
}

/** Daily check: reminds about the subscription end 3 days before and on the last day. */
object ExpiryReminder {
    private const val NAME = "subscription-expiry"
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ExpiryWorker>(1, TimeUnit.DAYS).build())
        work.enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ExpiryWorker>().build())
    }
    fun check(context: Context) {
        val settings = runCatching { SettingsStore(context).load() }.getOrNull() ?: return
        if (!settings.expiryWarning) return
        val saved = runCatching { SubscriptionStore(context).load() }.getOrNull() ?: return
        val prefs = context.getSharedPreferences("expiry_warning", Context.MODE_PRIVATE)
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val shown = prefs.getStringSet("shown", emptySet()).orEmpty()
        val kind = ExpiryWarning.due(saved.expires, today, zone, shown, prefs.getString("day", "").orEmpty()) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("expiry", tr(R.string.expiry_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 43, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, AppLanguage.context().resources.configuration.locales[0]).format(java.util.Date(saved.expires * 1000))
        val text = if (kind == ExpiryWarning.Kind.TODAY) tr(R.string.expiry_today) else tr(R.string.expiry_soon, ExpiryWarning.daysLeft(saved.expires, today, zone).toInt(), date)
        runCatching {
            manager.notify(3, android.app.Notification.Builder(context, "expiry").setSmallIcon(R.drawable.ic_moth_notification)
                .setContentTitle(tr(R.string.expiry_title)).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build())
            prefs.edit().putStringSet("shown", (shown.filter { it.endsWith(":${saved.expires}") } + ExpiryWarning.key(kind, saved.expires)).toSet()).putString("day", today.toString()).apply()
            Logger.i("Expiry", "reminder shown ($kind)")
        }.onFailure { Logger.w("Expiry", "notification failed", it) }
    }
}

class ExpiryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result { ExpiryReminder.check(applicationContext); return Result.success() }
}
