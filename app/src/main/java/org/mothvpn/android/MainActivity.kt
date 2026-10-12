package org.mothvpn.android

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mothvpn.core.Subscriptions
import org.mothvpn.core.TunnelConfig
import org.mothvpn.core.ClientSettings
import org.mothvpn.core.LogFacts
import org.mothvpn.core.UpdateChannel
import org.mothvpn.core.ImportInput
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class MainActivity : AppCompatActivity() {
    companion object { const val CONNECT_FROM_TILE = "org.mothvpn.android.CONNECT_FROM_TILE" }
    private lateinit var store: SubscriptionStore
    private var saved by mutableStateOf<SavedSubscription?>(null)
    private var updateAvailable by mutableStateOf(false)
    private var openUpdates by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var messageState by mutableStateOf("")
    /** Every message shown to the user is also written to the log (the text is redacted by Logger). */
    private var message: String
        get() = messageState
        set(value) { messageState = value; if (value.isNotBlank()) Logger.i("UI", "message shown: $value") }
    private var settings by mutableStateOf(ClientSettings())
    private lateinit var networks: NetworkTracker
    private val settingsQueue = Channel<ClientSettings>(Channel.CONFLATED)
    private val settingsApplied = MutableStateFlow<Pair<Long, ClientSettings>?>(null)
    private var pendingAction: Intent? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pendingAction; pendingAction = null
        Logger.i("Permission", "VPN permission result: ${if (result.resultCode == RESULT_OK) "granted" else "denied (code ${result.resultCode})"} pendingAction=${action?.action}")
        if (result.resultCode == RESULT_OK && action != null) launchService(action) else message = tr(R.string.msg_vpn_permission_was_not_granted_22)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Logger.i("Permission", "notification permission ${if (granted) "granted" else "denied"}")
    }
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        Logger.i("Import", "QR scan ${if (text == null) "cancelled" else "finished"}")
        if (text != null) { if (ImportInput.classify(text) is ImportInput.Kind.Invalid) message = tr(R.string.import_invalid) else import(text, "qr") }
    }
    private fun scanQr() = scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(tr(R.string.import_scan_prompt)).setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(PortraitCaptureActivity::class.java))
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
            runCatching { withContext(Dispatchers.IO) { store.load() } }.onSuccess { saved = it; Logger.i("Store", "saved subscription loaded: ${it?.let { s -> "${s.profiles.size} server(s) host=${LogFacts.hostTag(s.url)}" } ?: "none"}") }
                .onFailure { Logger.e("Store", "reading the saved subscription failed", it); message = tr(R.string.msg_could_not_read_the_saved_subscription_add_it_23) }
            runCatching { withContext(Dispatchers.IO) { SettingsStore(this@MainActivity).load() } }.onSuccess { settings = it; Logger.i("Store", "settings loaded: channel=${UpdateChannel.effective(it.updateChannel, BuildConfig.VERSION_NAME).id} theme=${it.theme}") }
                .onFailure { Logger.e("Store", "reading settings failed", it); message = tr(R.string.msg_could_not_read_settings_24) }
            busy = false
            SubscriptionUpdates.schedule(this@MainActivity, settings.subUpdateHours)
            launch { VpnLauncher.changed.collect { if (it > 0) runCatching { withContext(Dispatchers.IO) { store.load() } }.onSuccess { s -> if (s != null) saved = s } } }
            checkForUpdate() // on every launch, silently
            var persisted = settings
            var commit = 0L
            settingsApplied.value = commit to persisted
            consumeTileRequest()
            withContext(kotlinx.coroutines.NonCancellable) {
                for (next in settingsQueue) {
                    try {
                        withContext(Dispatchers.IO) { SettingsStore(this@MainActivity).save(next) }
                        LogFacts.settingsChanges(persisted, next).takeIf { it.isNotEmpty() }?.let { Logger.i("Settings", "saved: ${it.joinToString(", ")}") }
                        persisted = next
                    } catch (e: Exception) {
                        Logger.e("Store", "saving settings failed", e)
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
                            AnimatedContent(tab, Modifier.fillMaxSize(), transitionSpec = {
                                val dir = if (targetState > initialState) 1 else -1
                                (fadeIn(tween(220, delayMillis = 60)) + slideInHorizontally(tween(260)) { it / 12 * dir }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(260)) { -it / 12 * dir })
                            }, label = "tab") { shown ->
                            when (shown) {
                                0 -> HomeScreen(saved, network, busy, message,
                                    add = { url = ""; dialog = true },
                                    connect = { if (TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.BLOCKED)) stop() else connect() }, servers = { tab = 1 },
                                    updateAvailable = updateAvailable, openUpdates = { openUpdates = true; tab = 2 })
                                1 -> ServersScreen(saved, settings, network, busy, message,
                                    add = { url = ""; dialog = true }, refresh = { saved?.takeIf { !it.local }?.let { import(it.url, "refresh") } }, remove = { removeSubscription() },
                                    select = { id, kind -> select(id, kind) }, probe = { kind, order -> diagnose(null, false, kind, order) },
                                    speedTest = { kind, id -> diagnose(id ?: saved?.selection(kind), true, kind) },
                                    cancel = { startService(Intent(this@MainActivity, TunnelService::class.java).setAction("CANCEL_PROBES")) },
                                    preferences = { saveSettings(it) })
                                2 -> {
                                    val tunnel by TunnelStatus.state.collectAsState()
                                    val probing by Diagnostics.running.collectAsState()
                                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else SettingsScreen(settings, probing || tunnel in setOf(TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING, TunnelState.BLOCKED), { saveSettings(it) }, { tab = 0 }, openUpdates, { openUpdates = false })
                                }
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
                            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { url = clip.getText()?.text?.trim().orEmpty(); if (url.isEmpty()) message = tr(R.string.import_clipboard_empty) }) { Text(tr(R.string.import_paste)) }
                                OutlinedButton(onClick = { dialog = false; scanQr() }) { Text(tr(R.string.import_scan)) }
                            }
                            Text(tr(R.string.import_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }, confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { dialog = false; import(url); url = "" }) { Text(tr(R.string.msg_add_31)) } }, dismissButton = { TextButton(onClick = { dialog = false; url = "" }) { Text(tr(R.string.msg_cancel_32)) } })
                }
            }
        }
    }
    /** Silent launch-time update check using the saved channel; a failure is only logged and shows nothing. */
    private fun checkForUpdate() {
        val channel = UpdateChannel.effective(settings.updateChannel, BuildConfig.VERSION_NAME)
        lifecycleScope.launch {
            try { updateAvailable = withContext(Dispatchers.IO) { AppUpdater.check(channel) } != null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { updateAvailable = false; Logger.w("Update", "launch update check failed", e) }
        }
    }
    private fun saveSettings(next: ClientSettings) {
        if (busy) { Logger.w("Settings", "change ignored: another operation is in progress"); return }
        if (next == settings) return
        Logger.d("Settings", "change requested: ${LogFacts.settingsChanges(settings, next).joinToString(", ")}")
        val hours = settings.subUpdateHours
        settings = next.validate()
        settingsQueue.trySend(next)
        if (next.subUpdateHours != hours) SubscriptionUpdates.schedule(this, next.subUpdateHours)
    }
    private fun import(url: String, trigger: String = "add") {
        if (busy) { Logger.w("Import", "ignored: another operation is in progress (trigger=$trigger)"); return }
        busy = true; message = tr(R.string.msg_downloading_servers_33)
        val started = System.currentTimeMillis()
        Logger.i("Import", "subscription ${if (trigger == "refresh") "refresh" else "download"} started (trigger=$trigger, host=${LogFacts.hostTag(url)})")
        lifecycleScope.launch {
            try {
                val previous = saved
                val result = withContext(Dispatchers.IO) {
                    when (val input = ImportInput.classify(url)) {
                        is ImportInput.Kind.Subscription -> SubscriptionRefresh.run(this@MainActivity, input.url, settings, previous, trigger)
                        is ImportInput.Kind.Links -> {
                            val parsed = Subscriptions.parse(input.text)
                            parsed.reasons.forEach { Logger.w("Import", "skipped: $it") }
                            val base = previous?.takeIf { it.local }
                            val profiles = ImportInput.merge(base?.profiles.orEmpty(), parsed.profiles)
                            val first = parsed.profiles.first().id
                            val next = base?.copy(profiles = profiles, updatedAt = System.currentTimeMillis())
                                ?: SavedSubscription(SavedSubscription.LOCAL_URL, profiles, first, first, first, tr(R.string.import_local_name), updatedAt = System.currentTimeMillis())
                            store.save(next)
                            Logger.i("Import", "added ${profiles.size - (base?.profiles?.size ?: 0)} server(s) from links (trigger=$trigger)")
                            next to parsed.rejected
                        }
                        ImportInput.Kind.Invalid -> throw IllegalArgumentException(tr(R.string.import_invalid))
                    }
                }
                saved = result.first
                Logger.i("Import", "subscription saved: ${result.first.profiles.size} server(s) in ${System.currentTimeMillis() - started} ms (trigger=$trigger)")
                message = "" // the import result is only logged, not shown
            } catch (e: Exception) {
                // Logger redacts links and addresses from the exception text.
                Logger.e("Import", "subscription import failed (trigger=$trigger, host=${LogFacts.hostTag(url)}, ${System.currentTimeMillis() - started} ms)", e)
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
        if (busy || Diagnostics.running.value || TunnelStatus.state.value == TunnelState.STOPPING) return
        val switching = TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED) && kind == networks.state.value.kind && id != TunnelStatus.activeProfile.value
        val next = saved?.let { if (kind == "mobile") it.copy(selected = id, mobileSelected = id) else it.copy(selected = id, wifiSelected = id) } ?: return
        busy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(next) }; saved = next
                Logger.i("Store", "selected server ($kind): ${next.profiles.firstOrNull { it.id == id }?.let(LogFacts::profileTag)}")
                if (switching && TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED)) {
                    TunnelStatus.mutable.value = TunnelState.STARTING
                    try { startService(Intent(this@MainActivity, TunnelService::class.java).setAction("SWITCH").putExtra("profile", id)) }
                    catch (e: Exception) { Logger.e("Service", "switching the server failed", e); message = tr(R.string.msg_could_not_start_the_check_or_vpn_45) }
                }
            }
            catch (e: Exception) { Logger.e("Store", "saving the selected server failed", e); message = tr(R.string.msg_could_not_save_the_selected_server_41) }
            finally { busy = false }
        }
    }
    private fun removeSubscription() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { store.clear() }; Logger.i("Store", "subscription removed by the user"); saved = null; message = tr(R.string.msg_subscription_removed_42) }
            catch (e: Exception) { Logger.e("Store", "removing the subscription failed", e); message = tr(R.string.msg_could_not_remove_the_subscription_43) }
            finally { busy = false }
        }
    }
    private fun connect() {
        Logger.i("Tunnel", "connect tapped (network=${networks.state.value.kind}, available=${networks.state.value.available})")
        if (!networks.state.value.available) { message = tr(R.string.msg_no_network_20); return }
        val subscription = saved ?: return
        val profile = subscription.profiles.firstOrNull { it.id == subscription.selection(networks.state.value.kind) } ?: run { Logger.w("Tunnel", "connect ignored: the selected server is missing"); return }
        authorize(Intent(this, TunnelService::class.java).setAction("START").putExtra("profile", profile.id))
    }
    private fun diagnose(id: String?, speed: Boolean, kind: String, order: List<String> = emptyList()) {
        Logger.i("Probe", "${if (speed) "speed test" else "check"} tapped (kind=$kind, network=${networks.state.value.kind}, available=${networks.state.value.available})")
        if (!networks.state.value.available || networks.state.value.kind != kind) { message = tr(R.string.msg_switch_networks_to_run_this_check_44); return }
        authorize(Intent(this, TunnelService::class.java).setAction(if (speed) "SPEED" else "PROBE").putExtra("network", kind).putStringArrayListExtra("order", ArrayList(order)).apply { if (id != null) putExtra("profile", id) })
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
        catch (e: Exception) { Logger.e("Service", "starting the service failed (${action.action})", e); if (action.action == "START") TunnelStatus.mutable.value = TunnelState.ERROR; message = tr(R.string.msg_could_not_start_the_check_or_vpn_45) }
    }
    private fun stop() { Logger.i("Tunnel", "disconnect tapped"); startService(Intent(this, TunnelService::class.java).setAction("STOP")) }
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
        Logger.i("Tile", "connect request from the tile (subscription=${saved != null}, state=${TunnelStatus.state.value})")
        if (saved == null) message = tr(R.string.tile_setup)
        else if (TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !Diagnostics.running.value) connect()
    }
    override fun onDestroy() { Logger.d("App", "activity destroyed (finishing=$isFinishing)"); networks.close(); settingsQueue.close(); super.onDestroy() }
}

