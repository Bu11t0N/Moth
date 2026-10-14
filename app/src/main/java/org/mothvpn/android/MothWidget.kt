package org.mothvpn.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Home-screen widget: Moth icon, server name, tunnel state and a Connect/Disconnect button (same logic as the tile). */
class MothWidget : AppWidgetProvider() {
    companion object {
        private const val TOGGLE = "org.mothvpn.android.WIDGET_TOGGLE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Redraws the widgets whenever the tunnel state changes (started once per process). */
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        fun observe(context: Context) {
            val app = context.applicationContext
            scope.launch {
                combine(TunnelStatus.state, TunnelStatus.blocking, TunnelStatus.activeProfile, VpnLauncher.changed) { _, _, _, _ -> Unit }
                    .debounce(150).collect { runCatching { update(app) }.onFailure { Logger.w("Widget", "update failed", it) } }
            }
        }

        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MothWidget::class.java))
            if (ids.isEmpty()) return
            val state = TunnelStatus.state.value
            val blocked = TunnelStatus.trafficBlocked(state, TunnelStatus.blocking.value)
            val subscription = runCatching { SubscriptionStore(context).load() }.getOrNull()
            val network = runCatching { NetworkTracker.read(context.getSystemService(ConnectivityManager::class.java)) }.getOrNull()
            val active = TunnelStatus.activeProfile.value.takeIf { state != TunnelState.OFF && it.isNotEmpty() }
            val profile = subscription?.profiles?.firstOrNull { it.id == (active ?: subscription.selection(network?.kind ?: "wifi")) }
            val on = blocked || state == TunnelState.RUNNING || state == TunnelState.BLOCKED
            val status = tr(if (blocked) (if (state == TunnelState.STARTING) R.string.tile_reconnecting else R.string.tile_blocked) else when (state) {
                TunnelState.RUNNING -> R.string.tile_on
                TunnelState.STARTING -> R.string.tile_starting
                TunnelState.STOPPING -> R.string.tile_stopping
                TunnelState.BLOCKED -> R.string.tile_blocked
                else -> R.string.tile_off
            })
            val views = RemoteViews(context.packageName, R.layout.moth_widget).apply {
                setTextViewText(R.id.widget_server, profile?.name ?: tr(R.string.tile_setup))
                setTextViewText(R.id.widget_state, status)
                setTextViewText(R.id.widget_button, tr(if (on) R.string.widget_disconnect else R.string.widget_connect))
                setInt(R.id.widget_icon, "setAlpha", if (on) 255 else 140)
                setOnClickPendingIntent(R.id.widget_button, PendingIntent.getBroadcast(context, 0,
                    Intent(context, MothWidget::class.java).setAction(TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch { try { update(context) } catch (e: Exception) { Logger.w("Widget", "update failed", e) } finally { pending.finish() } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != TOGGLE) return
        Logger.i("Widget", "button tapped (state=${TunnelStatus.state.value})")
        val pending = goAsync()
        scope.launch { try { toggle(context.applicationContext) } finally { pending.finish() } }
    }

    private fun toggle(context: Context) {
        val state = TunnelStatus.state.value
        val blocked = TunnelStatus.trafficBlocked(state, TunnelStatus.blocking.value)
        if (!blocked && state in setOf(TunnelState.STARTING, TunnelState.STOPPING)) return
        if (state == TunnelState.RUNNING || blocked) {
            runCatching { context.startService(Intent(context, TunnelService::class.java).setAction("STOP")) }.onFailure { Logger.e("Widget", "stop failed", it) }
            return
        }
        if (Diagnostics.running.value) return
        val subscription = runCatching { SubscriptionStore(context).load() }.onFailure { Logger.e("Widget", "reading the saved subscription failed", it) }.getOrNull()
        val network = NetworkTracker.read(context.getSystemService(ConnectivityManager::class.java))
        val profile = subscription?.profiles?.firstOrNull { it.id == subscription.selection(network.kind) }
        if (profile == null || !network.available || VpnService.prepare(context) != null) {
            Logger.i("Widget", "opening the app instead of connecting: server=${profile != null} network=${network.available}")
            openApp(context); return
        }
        try {
            TunnelStatus.mutable.value = TunnelState.STARTING
            context.startForegroundService(Intent(context, TunnelService::class.java).setAction("START").putExtra("profile", profile.id))
        } catch (e: Exception) {
            Logger.e("Widget", "starting the VPN from the widget failed", e)
            TunnelStatus.mutable.value = TunnelState.ERROR
            openApp(context)
        }
    }

    private fun openApp(context: Context) = runCatching {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.CONNECT_FROM_TILE, true))
    }.onFailure { Logger.w("Widget", "opening the app failed", it) }
}
