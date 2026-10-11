package org.routevpn.android

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.routevpn.core.Subscriptions
import org.routevpn.core.TunnelConfig
import org.routevpn.core.ClientSettings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class MainActivity : AppCompatActivity() {
    companion object { const val CONNECT_FROM_TILE = "org.routevpn.android.CONNECT_FROM_TILE" }
    private lateinit var store: SubscriptionStore
    private var saved by mutableStateOf<SavedSubscription?>(null)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var settings by mutableStateOf(ClientSettings())
    private lateinit var networks: NetworkTracker
    private val settingsQueue = Channel<ClientSettings>(Channel.CONFLATED)
    private val settingsApplied = MutableStateFlow<Pair<Long, ClientSettings>?>(null)
    private var pendingAction: Intent? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pendingAction; pendingAction = null
        if (result.resultCode == RESULT_OK && action != null) launchService(action) else message = tr(R.string.msg_vpn_permission_was_not_granted_22)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        @Suppress("DEPRECATION")
        val restoredAction = state?.getParcelable<Intent>("pending_vpn_action")
        pendingAction = restoredAction
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = SubscriptionStore(this)
        networks = NetworkTracker(this)
        busy = true
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.load() } }.onSuccess { saved = it }
                .onFailure { message = tr(R.string.msg_could_not_read_the_saved_subscription_add_it_23) }
            runCatching { withContext(Dispatchers.IO) { SettingsStore(this@MainActivity).load() } }.onSuccess { settings = it }
                .onFailure { message = tr(R.string.msg_could_not_read_settings_24) }
            busy = false
            var persisted = settings
            var commit = 0L
            settingsApplied.value = commit to persisted
            consumeTileRequest()
            withContext(kotlinx.coroutines.NonCancellable) {
                for (next in settingsQueue) {
                    try {
                        withContext(Dispatchers.IO) { SettingsStore(this@MainActivity).save(next) }
                        persisted = next
                    } catch (_: Exception) {
                        if (settings == next) settings = persisted
                        message = tr(R.string.msg_could_not_apply_settings_25)
                    }
                    settingsApplied.value = ++commit to persisted
                }
            }
        }
        setContent {
            var tab by rememberSaveable { mutableStateOf(0) }
            BackHandler(enabled = tab != 0) { tab = 0 }
            val network by networks.state.collectAsState()
            var dialog by remember { mutableStateOf(false) }
            var url by remember { mutableStateOf("") }
            val dark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF83DCC6), background = Color(0xFF101A23), surface = Color(0xFF1C2A35)) else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> HomeScreen(saved, network, busy, message,
                                    add = { url = ""; dialog = true },
                                    connect = { if (TunnelStatus.state.value == TunnelState.RUNNING) stop() else connect() }, servers = { tab = 1 })
                                1 -> ServersScreen(saved, settings, network, busy, message,
                                    add = { url = ""; dialog = true }, refresh = { saved?.let { import(it.url) } }, remove = { removeSubscription() },
                                    select = { id, kind -> select(id, kind) }, probe = { kind -> diagnose(null, false, kind) },
                                    speedTest = { kind -> diagnose(saved?.selection(kind), true, kind) },
                                    cancel = { startService(Intent(this@MainActivity, TunnelService::class.java).setAction("CANCEL_PROBES")) },
                                    preferences = { saveSettings(it) })
                                2 -> {
                                    val tunnel by TunnelStatus.state.collectAsState()
                                    val probing by Diagnostics.running.collectAsState()
                                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else SettingsScreen(settings, probing || tunnel in setOf(TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING), { saveSettings(it) }, { tab = 0 })
                                }
                            }
                        }
                        // Same colour as the content surface and no tonal overlay: no lighter band at the bottom.
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                            listOf(tr(R.string.msg_home_26) to "home", tr(R.string.msg_servers_27) to "list", tr(R.string.msg_settings_28) to "settings").forEachIndexed { i, item ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { MothIcon(item.second) }, label = { Text(item.first) })
                            }
                        }
                    }
                    if (dialog) AlertDialog(onDismissRequest = { if (!busy) dialog = false }, title = { Text(tr(R.string.msg_add_subscription_16)) }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(tr(R.string.msg_paste_the_https_link_from_your_vpn_provider_i_29))
                            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(tr(R.string.msg_subscription_link_30)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        }
                    }, confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { dialog = false; import(url); url = "" }) { Text(tr(R.string.msg_add_31)) } }, dismissButton = { TextButton(onClick = { dialog = false; url = "" }) { Text(tr(R.string.msg_cancel_32)) } })
                }
            }
        }
    }
    private fun saveSettings(next: ClientSettings) {
        if (busy) return
        if (next == settings) return
        settings = next.validate()
        settingsQueue.trySend(next)
    }
    private fun import(url: String) {
        if (busy) return
        busy = true; message = tr(R.string.msg_downloading_servers_33)
        lifecycleScope.launch {
            try {
                val previous = saved
                val result = withContext(Dispatchers.IO) {
                    val response = SubscriptionDownload.fetchDetails(url, settings)
                    val parsed = Subscriptions.parse(response.body)
                    Logger.i("Import", "parsed ${parsed.profiles.size} profile(s), rejected ${parsed.rejected}")
                    parsed.reasons.forEach { Logger.w("Import", "rejected: $it") }
                    val selected = previous?.selected?.takeIf { id -> parsed.profiles.any { it.id == id } } ?: parsed.profiles.first().id
                    val sameProvider = previous?.url == url.trim()
                    fun keep(id: String?) = id?.takeIf { n -> parsed.profiles.any { it.id == n } } ?: selected
                    val next = SavedSubscription(url.trim(), parsed.profiles, selected,
                        if (sameProvider) keep(previous?.wifiSelected) else selected, if (sameProvider) keep(previous?.mobileSelected) else selected,
                        response.name, response.expires, response.used, response.total, System.currentTimeMillis())
                    store.save(next)
                    next to parsed.rejected
                }
                saved = result.first
                message = tr(R.string.msg_servers_added_result_first_profiles_size_34, result.first.profiles.size) + if (result.second > 0) tr(R.string.msg_unsupported_profiles_skipped_result_second_35, result.second) else ""
            } catch (e: Exception) {
                message = when(e) {
                    is java.net.SocketTimeoutException -> tr(R.string.msg_subscription_service_timed_out_36)
                    is java.net.UnknownHostException -> tr(R.string.msg_could_not_find_the_subscription_service_check_37)
                    is javax.net.ssl.SSLException -> tr(R.string.msg_could_not_verify_the_secure_connection_to_the_38)
                    is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { !it.contains("://") }?.let(::localizedError) ?: tr(R.string.msg_could_not_add_the_subscription_39)
                    else -> tr(R.string.msg_could_not_download_the_subscription_check_the_40)
                }
            } finally { busy = false }
        }
    }
    private fun select(id: String, kind: String) {
        if (busy || Diagnostics.running.value || TunnelStatus.state.value in setOf(TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING)) return
        val next = saved?.let { if (kind == "mobile") it.copy(selected = id, mobileSelected = id) else it.copy(selected = id, wifiSelected = id) } ?: return
        busy = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { store.save(next) }; saved = next }
            catch (_: Exception) { message = tr(R.string.msg_could_not_save_the_selected_server_41) }
            finally { busy = false }
        }
    }
    private fun removeSubscription() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { store.clear() }; saved = null; message = tr(R.string.msg_subscription_removed_42) }
            catch (_: Exception) { message = tr(R.string.msg_could_not_remove_the_subscription_43) }
            finally { busy = false }
        }
    }
    private fun connect() {
        if (!networks.state.value.available) { message = tr(R.string.msg_no_network_20); return }
        val subscription = saved ?: return
        val profile = subscription.profiles.firstOrNull { it.id == subscription.selection(networks.state.value.kind) } ?: return
        authorize(Intent(this, TunnelService::class.java).setAction("START").putExtra("profile", profile.id))
    }
    private fun diagnose(id: String?, speed: Boolean, kind: String) {
        if (!networks.state.value.available || networks.state.value.kind != kind) { message = tr(R.string.msg_switch_networks_to_run_this_check_44); return }
        authorize(Intent(this, TunnelService::class.java).setAction(if (speed) "SPEED" else "PROBE").putExtra("network", kind).apply { if (id != null) putExtra("profile", id) })
    }
    private fun authorize(action: Intent) {
        lifecycleScope.launch {
            settingsApplied.first { it?.second == settings }
            val permission = VpnService.prepare(this@MainActivity)
            if (permission != null) { pendingAction = action; vpnPermission.launch(permission) } else launchService(action)
        }
    }
    private fun launchService(action: Intent) {
        if (action.action == "START") TunnelStatus.mutable.value = TunnelState.STARTING
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        try { startForegroundService(action) }
        catch (_: Exception) { if (action.action == "START") TunnelStatus.mutable.value = TunnelState.ERROR; message = tr(R.string.msg_could_not_start_the_check_or_vpn_45) }
    }
    private fun stop() { startService(Intent(this, TunnelService::class.java).setAction("STOP")) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (settingsApplied.value != null) consumeTileRequest()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("pending_vpn_action", pendingAction)
        super.onSaveInstanceState(outState)
    }
    private fun consumeTileRequest() {
        if (!intent.getBooleanExtra(CONNECT_FROM_TILE, false)) return
        intent.removeExtra(CONNECT_FROM_TILE)
        if (saved == null) message = tr(R.string.tile_setup)
        else if (TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !Diagnostics.running.value) connect()
    }
    override fun onDestroy() { networks.close(); settingsQueue.close(); super.onDestroy() }
}

