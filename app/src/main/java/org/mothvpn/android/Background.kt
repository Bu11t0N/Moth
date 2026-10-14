package org.mothvpn.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.mothvpn.core.DirectRoutes
import org.mothvpn.core.LogFacts
import org.mothvpn.core.SubscriptionSchedule
import org.mothvpn.core.Subscriptions
import org.mothvpn.core.SubscriptionBook
import org.mothvpn.core.SubscriptionSource
import java.io.File
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Starting the VPN outside the app screen: tile, boot, Always-on VPN. */
object VpnLauncher {
    /** Bumped when the saved subscription changes in the background (auto-select, failover, scheduled update) so the screen reloads it. */
    val changed = MutableStateFlow(0)
    /** The last selected server for the current network, or null when there is no subscription. */
    fun lastProfileId(context: Context): String? {
        val subscription = runCatching { SubscriptionStore(context).load() }.onFailure { Logger.e("Launcher", "reading the saved subscription failed", it) }.getOrNull() ?: return null
        val kind = NetworkTracker.read(context.getSystemService(ConnectivityManager::class.java)).kind
        return subscription.profiles.firstOrNull { it.id == subscription.selection(kind) }?.id ?: subscription.profiles.firstOrNull()?.id
    }
}

/** "Connect when the phone starts": connects to the last server, or shows a notification when the VPN permission is missing. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return // storage keys are not available yet; wait for BOOT_COMPLETED
        SubscriptionUpdates.schedule(context, runCatching { SettingsStore(context).load().subUpdateHours }.getOrDefault(0))
        runCatching { ExpiryReminder.schedule(context) }
        val settings = runCatching { SettingsStore(context).load() }.getOrNull() ?: return
        if (!settings.bootConnect) return
        Logger.i("Boot", "connect on boot is on")
        if (TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED)) return
        val id = VpnLauncher.lastProfileId(context) ?: run { Logger.w("Boot", "no saved server"); return }
        if (VpnService.prepare(context) != null) { Logger.w("Boot", "VPN permission is not granted; showing a notification"); askToOpen(context); return }
        try { context.startForegroundService(Intent(context, TunnelService::class.java).setAction("START").putExtra("profile", id)) }
        catch (e: Exception) { Logger.e("Boot", "starting the VPN on boot failed", e); askToOpen(context) }
    }
    private fun askToOpen(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("boot", tr(R.string.boot_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 42, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.CONNECT_FROM_TILE, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            manager.notify(2, android.app.Notification.Builder(context, "boot").setSmallIcon(R.drawable.ic_moth_notification)
                .setContentTitle(tr(R.string.boot_permission_title)).setContentText(tr(R.string.boot_permission_text)).setContentIntent(open).setAutoCancel(true).build())
        }.onFailure { Logger.w("Boot", "notification failed", it) }
    }
}

/** Downloads one subscription and saves it, keeping the other subscriptions and the selected servers. Shared by the screen and the scheduled update. */
object SubscriptionRefresh {
    /**
     * [sourceId]: the subscription to refresh or edit; null adds a new one (or refreshes the one with the same link).
     * [name]: a new name chosen by the user, or null to keep the current one. Returns the saved book, the id and the number of rejected entries.
     */
    data class Result(val book: SubscriptionBook, val sourceId: String, val rejected: Int)
    fun run(context: Context, sourceId: String?, url: String, name: String?, settings: org.mothvpn.core.ClientSettings, trigger: String): Result {
        val link = url.trim()
        val response = SubscriptionDownload.fetchDetails(link, settings)
        val parsed = Subscriptions.parse(response.body) { provider -> SubscriptionDownload.fetchDetails(provider, settings).body }
        Logger.i("Import", "${parsed.summary()}, comment lines skipped ${parsed.comments}, kept ${parsed.profiles.size} (trigger=$trigger)")
        parsed.duplicates.forEach { Logger.d("Import", "dropped: $it") }
        parsed.reasons.forEach { Logger.w("Import", "skipped: $it") }
        var id = ""
        val book = SubscriptionStore(context).update { book ->
            val old = sourceId?.let(book::source) ?: book.sources.firstOrNull { !it.local && it.url == link }
            id = old?.id ?: SubscriptionSource.newId(link, System.currentTimeMillis())
            old?.let { o ->
                val gone = o.profiles.count { p -> parsed.profiles.none { it.id == p.id } }
                val added = parsed.profiles.count { n -> o.profiles.none { it.id == n.id } }
                Logger.i("Import", "refresh result: servers ${o.profiles.size} -> ${parsed.profiles.size} (added $added, removed $gone)")
            }
            book.upsert(SubscriptionSource(id, name?.trim() ?: old?.name.orEmpty(), link, parsed.profiles, response.name,
                response.expires, response.used, response.total, System.currentTimeMillis()))
        }
        Logger.i("Import", "subscriptions saved: ${book.sources.size} subscription(s), ${book.profiles.size} server(s)")
        runCatching { ExpiryReminder.checkTraffic(context) }.onFailure { Logger.w("Traffic", "check after refresh failed", it) }
        return Result(book, id, parsed.rejected)
    }
}

/** Scheduled subscription update (WorkManager, only with a network connection): every subscription that is due is refreshed. */
class SubscriptionUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = runCatching { SettingsStore(applicationContext).load() }.getOrNull() ?: return Result.success()
        val book = runCatching { SubscriptionStore(applicationContext).loadBook() }.getOrNull() ?: return Result.success()
        if (settings.subUpdateHours == 0) return Result.success()
        val now = System.currentTimeMillis()
        val due = book.sources.filter { !it.local && it.url.startsWith("https://") && SubscriptionSchedule.due(settings.subUpdateHours, it.updatedAt, now) }
        var failed = 0
        for (source in due) {
            try {
                SubscriptionRefresh.run(applicationContext, source.id, source.url, null, settings, "schedule")
                VpnLauncher.changed.value++
                Logger.i("Import", "scheduled update done (host=${LogFacts.hostTag(source.url)})")
            } catch (e: Exception) {
                failed++
                Logger.w("Import", "scheduled update failed (host=${LogFacts.hostTag(source.url)}, attempt ${runAttemptCount + 1})", e)
            }
        }
        return if (failed > 0 && runAttemptCount < 2) Result.retry() else Result.success()
    }
}

object SubscriptionUpdates {
    private const val NAME = "subscription-update"
    fun schedule(context: Context, hours: Int) {
        val work = WorkManager.getInstance(context)
        if (hours <= 0) { work.cancelUniqueWork(NAME); return }
        val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        Logger.d("Import", "scheduled update every $hours h")
    }
}

/** Local copies of the SagerNet rule sets for "Russian sites directly", downloaded over the physical network before the tunnel starts. */
object RuleSetCache {
    fun prepare(context: Context, connectivity: ConnectivityManager): Map<String, String> {
        val dir = File(context.filesDir, "rules").apply { mkdirs() }
        val out = mutableMapOf<String, String>()
        for ((tag, url) in DirectRoutes.ruleSets) {
            val file = File(dir, "$tag.srs")
            if (DirectRoutes.stale(if (file.exists()) file.lastModified() else 0, System.currentTimeMillis())) {
                runCatching { download(url, file, connectivity) }.onSuccess { Logger.i("Rules", "rule set $tag updated (${file.length()} bytes)") }
                    .onFailure { Logger.w("Rules", "rule set $tag download failed; ${if (file.exists()) "using the cached copy" else "skipped"}", it) }
            }
            if (file.exists() && file.length() > 0) out[tag] = file.absolutePath
        }
        return out
    }
    private fun download(address: String, target: File, connectivity: ConnectivityManager) {
        val network = NetworkTracker.physical(connectivity) ?: error("no network")
        val c = network.openConnection(java.net.URL(address)) as HttpsURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 20000
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val tmp = File(target.parentFile, target.name + ".tmp")
            c.inputStream.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(16384); var total = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; total += n; require(total <= 32L * 1024 * 1024) { "too large" }; out.write(buf, 0, n) }
            } }
            check(tmp.length() > 0 && tmp.renameTo(target)) { "could not save" }
        } finally { c.disconnect() }
    }
}
