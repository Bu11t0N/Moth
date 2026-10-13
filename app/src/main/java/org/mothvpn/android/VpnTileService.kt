package org.mothvpn.android

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/** Shares the same subscription, per-network selection and VPN service as the app. */
class VpnTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var toggle: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            TunnelStatus.state.combine(TunnelStatus.blocking) { st, b -> st to b }.collectLatest { (st, b) -> updateTile(st, b) }
        }
    }
    override fun onStopListening() {
        listening?.cancel()
        super.onStopListening()
    }
    private fun updateTile(state: TunnelState, blocking: Boolean) {
        // Kill switch holding traffic: active tile; while it reconnects from the blocked state the subtitle says so.
        val blocked = TunnelStatus.trafficBlocked(state, blocking)
        qsTile?.apply {
            label = tr(R.string.app_name)
            icon = Icon.createWithResource(this@VpnTileService, R.drawable.ic_moth_tile)
            this.state = if (blocked) Tile.STATE_ACTIVE else when (state) {
                TunnelState.RUNNING, TunnelState.BLOCKED -> Tile.STATE_ACTIVE
                TunnelState.STARTING, TunnelState.STOPPING -> Tile.STATE_UNAVAILABLE
                else -> Tile.STATE_INACTIVE
            }
            if (Build.VERSION.SDK_INT >= 29) subtitle = tr(if (blocked) (if (state == TunnelState.STARTING) R.string.tile_reconnecting else R.string.tile_blocked) else when (state) {
                TunnelState.RUNNING -> R.string.tile_on
                TunnelState.STARTING -> R.string.tile_starting
                TunnelState.STOPPING -> R.string.tile_stopping
                TunnelState.BLOCKED -> R.string.tile_blocked
                else -> R.string.tile_off
            })
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        Logger.i("Tile", "tile tapped (state=${TunnelStatus.state.value})")
        unlockAndRun {
            if (toggle?.isActive == true) return@unlockAndRun
            toggle = scope.launch {
                val state = TunnelStatus.state.value
                val blocked = TunnelStatus.trafficBlocked(state, TunnelStatus.blocking.value)
                if (!blocked && state in setOf(TunnelState.STARTING, TunnelState.STOPPING)) return@launch
                if (state == TunnelState.RUNNING || blocked) {
                    // Same as "Disconnect" on the home screen: stops the core and removes the blocking interface.
                    startService(Intent(this@VpnTileService, TunnelService::class.java).setAction("STOP"))
                    return@launch
                }
                if (Diagnostics.running.value) return@launch
                val subscription = withContext(Dispatchers.IO) {
                    runCatching { SubscriptionStore(this@VpnTileService).load() }.onFailure { Logger.e("Tile", "reading the saved subscription failed", it) }.getOrNull()
                }
                val network = NetworkTracker.read(getSystemService(ConnectivityManager::class.java))
                val profile = subscription?.profiles?.firstOrNull { it.id == subscription.selection(network.kind) }
                if (profile == null || !network.available || VpnService.prepare(this@VpnTileService) != null) {
                    Logger.i("Tile", "opening the app instead of connecting: server=${profile != null} network=${network.available}")
                    openApp()
                    return@launch
                }
                try {
                    TunnelStatus.mutable.value = TunnelState.STARTING
                    startForegroundService(Intent(this@VpnTileService, TunnelService::class.java)
                        .setAction("START").putExtra("profile", profile.id))
                } catch (e: Exception) {
                    Logger.e("Tile", "starting the VPN from the tile failed", e)
                    TunnelStatus.mutable.value = TunnelState.ERROR
                    openApp()
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // Intent overload is only used below API 34.
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.CONNECT_FROM_TILE, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 41, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else startActivityAndCollapse(intent)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
