package org.mothvpn.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
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
    /** Test hook: replaces the stored history (used by the instrumented screenshot test). */
    internal fun replaceForTest(context: Context, value: TrafficHistory) = synchronized(lock) {
        history = value; dirty = true; flushLocked(context)
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
    val firstDay = remember(locale) { java.time.temporal.WeekFields.of(locale).firstDayOfWeek }
    var range by rememberSaveable { mutableIntStateOf(7) }
    var selected by remember(range) { mutableStateOf<LocalDate?>(null) }
    val series = remember(history, today, range) { history.series(today, range) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(tr(R.string.stats_day), history.day(today), units, locale, Modifier.weight(1f))
            StatCard(tr(R.string.stats_week), history.week(today, firstDay), units, locale, Modifier.weight(1f))
            StatCard(tr(R.string.stats_month), history.month(today), units, locale, Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth(), colors = ndCardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.stats_chart), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    listOf(7 to R.string.stats_range_7, 30 to R.string.stats_range_30).forEach { (days, label) ->
                        FilterChip(selected = range == days, onClick = { range = days }, label = { Text(tr(label)) },
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
                val sel = selected?.let { d -> series.firstOrNull { it.first == d } }
                Column(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                    if (sel == null) Text(tr(R.string.stats_tap_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text(sel.first.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)).replaceFirstChar { it.titlecase(locale) },
                            style = MaterialTheme.typography.labelLarge)
                        Text(tr(R.string.stats_updown, TrafficFormat.bytes(sel.second.first, units, locale), TrafficFormat.bytes(sel.second.second, units, locale)),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TrafficChart(series, selected, { selected = if (selected == it) null else it }, units, locale)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(MaterialTheme.colorScheme.primary, tr(R.string.stats_received))
                    Legend(MaterialTheme.colorScheme.tertiary, tr(R.string.stats_sent))
                }
            }
        }
        Text(tr(R.string.stats_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
    }
}

/** One of the three top items (Day / Week / Month): a short label and the total, nothing else (sent/received are in the chart). */
@Composable private fun StatCard(title: String, value: Pair<Long, Long>, units: List<String>, locale: java.util.Locale, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = NdTone.card(), modifier = modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val big = MaterialTheme.typography.titleLarge
            androidx.compose.foundation.text.BasicText(TrafficFormat.bytes(value.first + value.second, units, locale), style = big.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = big.fontSize, stepSize = 0.5.sp))
        }
    }
}

@Composable private fun TrafficChart(series: List<Pair<LocalDate, Pair<Long, Long>>>, selected: LocalDate?, onSelect: (LocalDate) -> Unit,
                                     units: List<String>, locale: java.util.Locale) {
    val maxValue = series.maxOfOrNull { it.second.first + it.second.second } ?: 0L
    val top = TrafficHistory.niceMax(maxValue)
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(series.size) { grow.snapTo(0f); grow.animateTo(1f, androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val scale by androidx.compose.animation.core.animateFloatAsState(top.toFloat(), androidx.compose.animation.core.tween(400), label = "scale")
    val rx = MaterialTheme.colorScheme.primary
    val tx = MaterialTheme.colorScheme.tertiary
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val dayLabel = remember(locale, series.size) {
        if (series.size <= 7) java.time.format.DateTimeFormatter.ofPattern("EE", locale) else java.time.format.DateTimeFormatter.ofPattern("d.MM", locale)
    }
    val desc = tr(R.string.stats_chart)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(220.dp)
        .semantics { contentDescription = desc }
        .pointerInput(series) {
            detectTapGestures { pos ->
                val left = 48.dp.toPx()
                val slot = (size.width - left) / series.size
                val i = ((pos.x - left) / slot).toInt()
                if (pos.x >= left && i in series.indices) onSelect(series[i].first)
            }
        }) {
        val left = 48.dp.toPx(); val bottomPad = 22.dp.toPx(); val topPad = 8.dp.toPx()
        val h = size.height - bottomPad - topPad
        val slot = (size.width - left) / series.size
        val barW = (slot * if (series.size <= 7) 0.55f else 0.7f).coerceAtMost(36.dp.toPx())
        val radius = androidx.compose.ui.geometry.CornerRadius(minOf(barW / 2, 6.dp.toPx()))
        for (k in 0..2) {
            val y = topPad + h - h * k / 2f
            drawLine(grid, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                pathEffect = if (k == 0) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val text = if (k == 0) "0" else TrafficFormat.bytes((top * k / 2), units, locale)
            val m = measurer.measure(text, labelStyle)
            drawText(m, topLeft = androidx.compose.ui.geometry.Offset(left - m.size.width - 6.dp.toPx(), y - m.size.height / 2f))
        }
        series.forEachIndexed { i, (day, v) ->
            val x = left + slot * i + (slot - barW) / 2
            val total = v.first + v.second
            val dim = selected != null && selected != day
            val base = topPad + h
            if (total <= 0) {
                val e = 3.dp.toPx()
                drawRoundRect(faint, androidx.compose.ui.geometry.Offset(x, base - e), androidx.compose.ui.geometry.Size(barW, e), androidx.compose.ui.geometry.CornerRadius(e / 2))
            } else {
                val full = (h * total / scale * grow.value).coerceAtLeast(3.dp.toPx())
                val rxH = full * v.second / total
                val alpha = if (dim) 0.35f else 1f
                // whole bar in the "sent" colour with the "received" part drawn over its lower section
                drawRoundRect(tx.copy(alpha = alpha), androidx.compose.ui.geometry.Offset(x, base - full), androidx.compose.ui.geometry.Size(barW, full), radius)
                if (rxH > 0) {
                    clipRect(left = x, top = base - rxH, right = x + barW, bottom = base) {
                        drawRoundRect(rx.copy(alpha = alpha), androidx.compose.ui.geometry.Offset(x, base - full), androidx.compose.ui.geometry.Size(barW, full), radius)
                    }
                }
            }
            val showLabel = series.size <= 7 || i == series.lastIndex || (series.lastIndex - i) % 7 == 0
            if (showLabel) {
                val m = measurer.measure(day.format(dayLabel), labelStyle.copy(color = if (day == selected) rx else labelColor))
                drawText(m, topLeft = androidx.compose.ui.geometry.Offset((x + barW / 2 - m.size.width / 2f).coerceIn(left - 8f, size.width - m.size.width), base + 4.dp.toPx()))
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
    /** Each subscription has its own reminder; the provider's end date comes from its subscription-userinfo header. */
    fun check(context: Context) {
        val settings = runCatching { SettingsStore(context).load() }.getOrNull() ?: return
        val book = runCatching { SubscriptionStore(context).loadBook() }.getOrNull() ?: return
        runCatching { checkTraffic(context, settings, book) }.onFailure { Logger.w("Traffic", "check failed", it) }
        if (!settings.expiryWarning) return
        val prefs = context.getSharedPreferences("expiry_warning", Context.MODE_PRIVATE)
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val many = book.sources.count { !it.local } > 1
        book.sources.filter { !it.local }.forEach { source -> runCatching { remind(context, prefs, source, many, today, zone) }.onFailure { Logger.w("Expiry", "check failed", it) } }
    }
    /** Called after every subscription refresh (and daily with the expiry check). */
    fun checkTraffic(context: Context) {
        val settings = runCatching { SettingsStore(context).load() }.getOrNull() ?: return
        val book = runCatching { SubscriptionStore(context).loadBook() }.getOrNull() ?: return
        checkTraffic(context, settings, book)
    }
    /** One notification per subscription, threshold and billing period (see TrafficWarning). Names are shown, never logged. */
    private fun checkTraffic(context: Context, settings: org.mothvpn.core.ClientSettings, book: org.mothvpn.core.SubscriptionBook) {
        val prefs = context.getSharedPreferences("traffic_warning", Context.MODE_PRIVATE)
        val sources = book.sources.filter { !it.local }
        val many = sources.size > 1
        val editor = prefs.edit()
        // Forget removed subscriptions.
        prefs.all.keys.filter { key -> sources.none { it.id == key } }.forEach { editor.remove(it) }
        for (source in sources) {
            val decision = org.mothvpn.core.TrafficWarning.check(source.usedBytes, source.totalBytes, source.expires, settings.trafficWarning,
                org.mothvpn.core.TrafficWarning.State.decode(prefs.getString(source.id, null))) ?: continue
            if (decision.notify && notifyTraffic(context, source, decision.percent, many)) Logger.i("Traffic", "warning shown (${decision.percent}%, threshold ${settings.trafficWarning}%)")
            else if (decision.notify) continue // not shown: try again next time
            editor.putString(source.id, decision.state.encode())
        }
        editor.apply()
    }
    private fun notifyTraffic(context: Context, source: org.mothvpn.core.SubscriptionSource, percent: Int, many: Boolean): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("expiry", tr(R.string.expiry_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 44, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val locale = AppLanguage.context().resources.configuration.locales[0]
        val units = tr(R.string.notif_units).split("|")
        val text = tr(R.string.traffic_warning_text, percent, org.mothvpn.core.TrafficFormat.bytes(source.usedBytes, units, locale), org.mothvpn.core.TrafficFormat.bytes(source.totalBytes, units, locale))
        val title = if (many && source.title().isNotBlank()) tr(R.string.traffic_warning_title_named, source.title()) else tr(R.string.traffic_warning_title)
        return runCatching {
            manager.notify(2000 + (source.id.hashCode() and 0x7fff), android.app.Notification.Builder(context, "expiry").setSmallIcon(R.drawable.ic_moth_notification)
                .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build())
        }.onFailure { Logger.w("Traffic", "notification failed", it) }.isSuccess
    }
    private fun remind(context: Context, prefs: android.content.SharedPreferences, source: org.mothvpn.core.SubscriptionSource, many: Boolean, today: LocalDate, zone: ZoneId) {
        // The subscription migrated from 0.5.1 keeps the old keys, so a reminder already shown is not repeated.
        val prefix = if (source.id == org.mothvpn.core.SubscriptionBook.MIGRATED_ID) "" else "${source.id}:"
        val shownKey = prefix + "shown"; val dayKey = prefix + "day"
        val shown = prefs.getStringSet(shownKey, emptySet()).orEmpty()
        val kind = ExpiryWarning.due(source.expires, today, zone, shown, prefs.getString(dayKey, "").orEmpty()) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("expiry", tr(R.string.expiry_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 43, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, AppLanguage.context().resources.configuration.locales[0]).format(java.util.Date(source.expires * 1000))
        val text = if (kind == ExpiryWarning.Kind.TODAY) tr(R.string.expiry_today) else ExpiryWarning.daysLeft(source.expires, today, zone).toInt().let { days -> trPlural(R.plurals.expiry_soon, days, days, date) }
        // The name is shown only in the notification (it is the user's own subscription) and never logged.
        val title = if (many && source.title().isNotBlank()) tr(R.string.expiry_title_named, source.title()) else tr(R.string.expiry_title)
        val notificationId = if (prefix.isEmpty()) 3 else 1000 + (source.id.hashCode() and 0x7fff)
        runCatching {
            manager.notify(notificationId, android.app.Notification.Builder(context, "expiry").setSmallIcon(R.drawable.ic_moth_notification)
                .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build())
            prefs.edit().putStringSet(shownKey, (shown.filter { it.endsWith(":${source.expires}") } + ExpiryWarning.key(kind, source.expires)).toSet()).putString(dayKey, today.toString()).apply()
            Logger.i("Expiry", "reminder shown ($kind)")
        }.onFailure { Logger.w("Expiry", "notification failed", it) }
    }
}

class ExpiryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result { ExpiryReminder.check(applicationContext); return Result.success() }
}
