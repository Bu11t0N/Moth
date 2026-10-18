package org.mothvpn.android

import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mothvpn.core.Subscriptions
import org.mothvpn.core.TunnelConfig
import org.mothvpn.core.ClientSettings
import org.mothvpn.core.LogFacts
import org.mothvpn.core.UpdateChannel
import org.mothvpn.core.ImportInput
import org.mothvpn.core.SubscriptionBook
import org.mothvpn.core.SubscriptionSource
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
    /** All subscriptions; [saved] is the flattened list of their servers with the shared selection. */
    private var book by mutableStateOf<SubscriptionBook?>(null)
    private fun show(next: SubscriptionBook?) { book = next?.takeIf { it.sources.isNotEmpty() }; saved = next?.record(); lifecycleScope.launch(Dispatchers.IO) { runCatching { MothWidget.update(applicationContext) } } }
    private var updateAvailable by mutableStateOf(false)
    private var openUpdates by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    /** True while [select] saves a server choice (busy for that short moment); the new design shows no progress bar for it. */
    private var selecting by mutableStateOf(false)
    /** Import offered by a link that opened the app; shown in a confirmation dialog, never logged. */
    private var deepLink by mutableStateOf<org.mothvpn.core.DeepLinks.Action?>(null)
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
            runCatching { withContext(Dispatchers.IO) { store.loadBook() } }.onSuccess { show(it); Logger.i("Store", "saved subscriptions loaded: ${it?.let { b -> "${b.sources.size} subscription(s), ${b.profiles.size} server(s) hosts=${b.sources.joinToString(",") { s -> LogFacts.hostTag(s.url) }}" } ?: "none"}") }
                .onFailure { Logger.e("Store", "reading the saved subscription failed", it); message = tr(R.string.msg_could_not_read_the_saved_subscription_add_it_23) }
            runCatching { withContext(Dispatchers.IO) { SettingsStore(this@MainActivity).load() } }.onSuccess { settings = it; Logger.i("Store", "settings loaded: channel=${UpdateChannel.effective(it.updateChannel, BuildConfig.VERSION_NAME).id} theme=${it.theme}") }
                .onFailure { Logger.e("Store", "reading settings failed", it); message = tr(R.string.msg_could_not_read_settings_24) }
            busy = false
            SubscriptionUpdates.schedule(this@MainActivity, settings.subUpdateHours)
            runCatching { ExpiryReminder.schedule(this@MainActivity) }.onFailure { Logger.w("Expiry", "scheduling failed", it) }
            launch { VpnLauncher.changed.collect { if (it > 0) runCatching { withContext(Dispatchers.IO) { store.loadBook() } }.onSuccess { b -> if (b != null) show(b) } } }
            checkForUpdate() // on every launch, silently
            var persisted = settings
            var commit = 0L
            settingsApplied.value = commit to persisted
            consumeTileRequest()
            consumeDeepLink()
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
            var subName by remember { mutableStateOf("") }
            val dark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
            val newDesign = settings.newDesign
            val scheme = if (newDesign) (if (dark) (if (settings.amoled) MonoTheme.amoled else MonoTheme.dark) else MonoTheme.light)
                else if (dark) darkColorScheme(primary = Color(0xFF83DCC6), background = Color(0xFF101A23), surface = Color(0xFF1C2A35)) else lightColorScheme()
            CompositionLocalProvider(LocalNewDesign provides newDesign) {
            MaterialTheme(colorScheme = scheme, shapes = if (newDesign) MonoTheme.shapes else MaterialTheme.shapes) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        Box(Modifier.weight(1f)) {
                            AnimatedContent(tab, Modifier.fillMaxSize(), transitionSpec = {
                                val dir = if (targetState > initialState) 1 else -1
                                (fadeIn(tween(220, delayMillis = 60)) + slideInHorizontally(tween(260)) { it / 12 * dir }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(260)) { -it / 12 * dir })
                            }, label = "tab") { shown ->
                            when (shown) {
                                0 -> if (newDesign) NewHomeScreen(saved, book, settings, network, busy, message,
                                    add = { url = ""; subName = ""; dialog = true },
                                    connect = { if (TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.BLOCKED) || TunnelStatus.trafficBlocked(TunnelStatus.state.value, TunnelStatus.blocking.value)) stop() else connect() },
                                    select = { id, kind -> select(id, kind) }, updateAvailable = updateAvailable, openUpdates = { openUpdates = true; tab = 2 })
                                else HomeScreen(saved, book, network, busy, message,
                                    add = { url = ""; subName = ""; dialog = true },
                                    connect = { if (TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.BLOCKED) || TunnelStatus.trafficBlocked(TunnelStatus.state.value, TunnelStatus.blocking.value)) stop() else connect() }, servers = { tab = 1 },
                                    updateAvailable = updateAvailable, openUpdates = { openUpdates = true; tab = 2 })
                                1 -> ServersScreen(saved, book, settings, network, busy, message,
                                    add = { url = ""; subName = ""; dialog = true }, refresh = { refreshAll() }, refreshOne = { id -> book?.source(id)?.takeIf { !it.local }?.let { import(it.url, "refresh", id) } },
                                    edit = { id, name, link -> editSubscription(id, name, link) }, remove = { id -> removeSubscription(id) },
                                    select = { id, kind -> select(id, kind) }, probe = { kind, order -> diagnose(kind, order) }, probeGroup = { kind, ids -> diagnose(kind, ids, only = ids) },
                                    cancel = { startService(Intent(this@MainActivity, TunnelService::class.java).setAction("CANCEL_PROBES")) },
                                    preferences = { saveSettings(it) }, editServer = { id, outbound -> editServer(id, outbound) }, selecting = selecting)
                                2 -> {
                                    val tunnel by TunnelStatus.state.collectAsState()
                                    val probing by Diagnostics.running.collectAsState()
                                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else SettingsScreen(settings, probing || tunnel in setOf(TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING, TunnelState.BLOCKED), { saveSettings(it) }, { tab = 0 }, openUpdates, { openUpdates = false }, { restoreBackup(it) })
                                }
                            }
                            }
                        }
                        // Same colour as the content surface and no tonal overlay: no lighter band at the bottom.
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                            listOf(tr(R.string.msg_home_26) to "home", tr(R.string.msg_servers_27) to "list", tr(R.string.msg_settings_28) to "settings").forEachIndexed { i, item ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(item.first) }, icon = {
                                    // New design: Material icons that are clearly different (house, bulleted list, gear); the classic UI keeps its drawn icons.
                                    if (newDesign) Icon(when (item.second) { "home" -> androidx.compose.material.icons.Icons.Outlined.Home
                                        "list" -> androidx.compose.material.icons.Icons.AutoMirrored.Outlined.FormatListBulleted
                                        else -> androidx.compose.material.icons.Icons.Outlined.Settings }, contentDescription = null)
                                    else MothIcon(item.second) })
                            }
                        }
                    }
                    deepLink?.let { action ->
                        AlertDialog(onDismissRequest = { deepLink = null; Logger.i("DeepLink", "import cancelled") },
                            title = { Text(tr(if (action is org.mothvpn.core.DeepLinks.Action.Subscription) R.string.deeplink_sub_title else R.string.deeplink_links_title)) },
                            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { when (action) {
                                is org.mothvpn.core.DeepLinks.Action.Subscription -> {
                                    Text(tr(R.string.deeplink_sub_text, org.mothvpn.core.DeepLinks.host(action.url)))
                                    action.name?.let { Text(tr(R.string.deeplink_name, it)) }
                                }
                                is org.mothvpn.core.DeepLinks.Action.Links -> {
                                    val parsed = remember(action) { runCatching { Subscriptions.parse(action.text) }.getOrNull() }
                                    if (parsed == null) Text(tr(R.string.import_invalid))
                                    else { Text(tr(R.string.deeplink_links_text, parsed.profiles.size)); parsed.profiles.take(5).forEach { Text("• " + it.name + " (" + it.protocol.uppercase() + ")", style = MaterialTheme.typography.bodySmall) } }
                                }
                            }; Text(tr(R.string.deeplink_warning), style = MaterialTheme.typography.bodySmall) } },
                            confirmButton = { TextButton(enabled = !busy, onClick = {
                                deepLink = null
                                Logger.i("DeepLink", "import confirmed (${if (action is org.mothvpn.core.DeepLinks.Action.Subscription) "subscription" else "links"})")
                                when (action) {
                                    is org.mothvpn.core.DeepLinks.Action.Subscription -> { tab = 1; import(action.url, "deeplink", name = action.name) }
                                    is org.mothvpn.core.DeepLinks.Action.Links -> { tab = 1; import(action.text, "deeplink") }
                                }
                            }) { Text(tr(R.string.msg_add_31)) } },
                            dismissButton = { TextButton(onClick = { deepLink = null; Logger.i("DeepLink", "import cancelled") }) { Text(tr(R.string.msg_cancel_32)) } })
                    }
                    if (dialog) AlertDialog(onDismissRequest = { if (!busy) dialog = false }, title = { Text(tr(R.string.msg_add_subscription_16)) }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(tr(R.string.msg_paste_the_https_link_from_your_vpn_provider_i_29))
                            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(tr(R.string.msg_subscription_link_30)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrect = false), singleLine = true)
                            OutlinedTextField(value = subName, onValueChange = { if (it.length <= org.mothvpn.core.SubscriptionSource.MAX_NAME) subName = it.replace("\n", " ") }, label = { Text(tr(R.string.sub_name_optional)) }, singleLine = true)
                            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { url = clip.getText()?.text?.trim().orEmpty(); if (url.isEmpty()) message = tr(R.string.import_clipboard_empty) }) { Text(tr(R.string.import_paste)) }
                                OutlinedButton(onClick = { dialog = false; scanQr() }) { Text(tr(R.string.import_scan)) }
                            }
                            Text(tr(R.string.import_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }, confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { dialog = false; import(url, name = subName.trim().ifEmpty { null }); url = ""; subName = "" }) { Text(tr(R.string.msg_add_31)) } }, dismissButton = { TextButton(onClick = { dialog = false; url = ""; subName = "" }) { Text(tr(R.string.msg_cancel_32)) } })
                }
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
        val kill = settings.killSwitch
        val splitChanged = next.splitMode != settings.splitMode || next.splitApps != settings.splitApps
        settings = next.validate()
        settingsQueue.trySend(next)
        if (next.subUpdateHours != hours) SubscriptionUpdates.schedule(this, next.subUpdateHours)
        if (next.killSwitch != kill && TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED))
            runCatching { startService(Intent(this, TunnelService::class.java).setAction("KILL_SWITCH").putExtra("on", next.killSwitch)) }.onFailure { Logger.w("Settings", "kill switch update not delivered", it) }
        // Split tunneling is fixed when the tunnel is built: after a (debounced) change while connected, restart on the same server once.
        // The service reads the settings from the store, so wait until the queue has written them.
        if (splitChanged && TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.BLOCKED) && TunnelStatus.activeProfile.value.isNotEmpty()) lifecycleScope.launch {
            withTimeoutOrNull(5_000) { settingsApplied.first { it?.second == next } }
            Logger.i("Settings", "split tunneling changed while connected: reconnecting")
            runCatching { startService(Intent(this@MainActivity, TunnelService::class.java).setAction("SWITCH").putExtra("profile", TunnelStatus.activeProfile.value).putExtra("force", true)) }
                .onFailure { Logger.w("Settings", "split tunneling reconnect not delivered", it) }
        }
    }
    /** [sourceId]: refresh or edit that subscription; null adds a new one. [name]: a name chosen by the user (never logged). */
    private fun import(url: String, trigger: String = "add", sourceId: String? = null, name: String? = null, expectedUrl: String? = null) {
        if (busy) { Logger.w("Import", "ignored: another operation is in progress (trigger=$trigger)"); return }
        busy = true; message = tr(R.string.msg_downloading_servers_33)
        val started = System.currentTimeMillis()
        Logger.i("Import", "subscription ${if (trigger == "refresh") "refresh" else "download"} started (trigger=$trigger, host=${LogFacts.hostTag(url)})")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (val input = ImportInput.classify(url)) {
                        is ImportInput.Kind.Subscription -> SubscriptionRefresh.run(this@MainActivity, sourceId, input.url, name, settings, trigger, expectedUrl ?: input.url).let { it.book to it.rejected }
                        is ImportInput.Kind.Links -> {
                            val parsed = Subscriptions.parse(input.text)
                            parsed.reasons.forEach { Logger.w("Import", "skipped: $it") }
                            var added = 0
                            val next = store.update { b ->
                                val base = b.source(SubscriptionSource.LOCAL_ID)
                                val profiles = ImportInput.merge(base?.profiles.orEmpty(), parsed.profiles)
                                added = profiles.size - (base?.profiles?.size ?: 0)
                                b.upsert(base?.copy(profiles = profiles, updatedAt = System.currentTimeMillis())
                                    ?: SubscriptionSource(SubscriptionSource.LOCAL_ID, tr(R.string.import_local_name), SavedSubscription.LOCAL_URL, profiles, updatedAt = System.currentTimeMillis()))
                            }
                            Logger.i("Import", "added $added server(s) from links (trigger=$trigger)")
                            next to parsed.rejected
                        }
                        ImportInput.Kind.Invalid -> throw IllegalArgumentException(tr(R.string.import_invalid))
                    }
                }
                show(result.first)
                Logger.i("Import", "subscription saved: ${result.first.profiles.size} server(s) in ${System.currentTimeMillis() - started} ms (trigger=$trigger)")
                message = "" // the import result is only logged, not shown
            } catch (e: org.mothvpn.core.StaleSubscriptionRefresh) {
                Logger.i("Import", "update discarded: subscription deleted or link changed")
                runCatching { withContext(Dispatchers.IO) { store.loadBook() } }.onSuccess { show(it) }
                message = ""
            } catch (e: Exception) {
                // Logger redacts links and addresses from the exception text.
                Logger.e("Import", "subscription import failed (trigger=$trigger, host=${LogFacts.hostTag(url)}, ${System.currentTimeMillis() - started} ms)", e)
                message = importError(e)
            } finally { busy = false }
        }
    }
    private fun importError(e: Exception) = when (e) {
        is java.net.SocketTimeoutException -> tr(R.string.msg_subscription_service_timed_out_36)
        is java.net.UnknownHostException -> tr(R.string.msg_could_not_find_the_subscription_service_check_37)
        is javax.net.ssl.SSLException -> tr(R.string.msg_could_not_verify_the_secure_connection_to_the_38)
        is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { !it.contains("://") }?.let(::localizedError) ?: tr(R.string.msg_could_not_add_the_subscription_39)
        else -> tr(R.string.msg_could_not_download_the_subscription_check_the_40)
    }
    /** Refreshes every subscription with a link, one after another; one failure does not stop the others. */
    private fun refreshAll() {
        if (busy) return
        val sources = book?.sources.orEmpty().filter { !it.local }
        if (sources.isEmpty()) return
        busy = true; message = tr(R.string.msg_downloading_servers_33)
        lifecycleScope.launch {
            var error: String? = null
            for (source in sources) {
                try { show(withContext(Dispatchers.IO) { SubscriptionRefresh.run(this@MainActivity, source.id, source.url, null, settings, "refresh").book }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Logger.e("Import", "subscription refresh failed (host=${LogFacts.hostTag(source.url)})", e); error = importError(e) } // no subscription name: shown messages are also logged
            }
            message = error.orEmpty(); busy = false
        }
    }
    /** Renames a subscription and, when the link changed, downloads it again from the new link (same entry, same position). */
    private fun editSubscription(id: String, name: String, link: String) {
        if (busy) return
        val source = book?.source(id) ?: return
        val trimmed = name.trim()
        if (!org.mothvpn.core.SubscriptionSource.validName(trimmed)) { message = tr(R.string.sub_name_invalid); return }
        if (!source.local && link.trim() != source.url) {
            if (ImportInput.classify(link) !is ImportInput.Kind.Subscription) { message = tr(R.string.msg_use_an_https_link_without_a_username_or_passw_112); return }
            import(link.trim(), "edit", id, trimmed, expectedUrl = source.url); return
        }
        busy = true
        lifecycleScope.launch {
            try { show(withContext(Dispatchers.IO) { store.update { it.rename(id, trimmed) } }); Logger.i("Store", "subscription renamed") }
            catch (e: Exception) { Logger.e("Store", "renaming the subscription failed", e); message = tr(R.string.msg_check_your_settings_71) }
            finally { busy = false }
        }
    }
    /** Developer JSON editor: replaces the server's outbound in place; reconnects when it is the connected server. */
    private fun editServer(id: String, outbound: String) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                show(withContext(Dispatchers.IO) { store.update { org.mothvpn.core.ServerEditor.apply(it, id, outbound) } })
                Logger.i("Store", "server edited by hand")
                if (id == TunnelStatus.activeProfile.value && TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED)) {
                    TunnelStatus.mutable.value = TunnelState.STARTING
                    try { startService(Intent(this@MainActivity, TunnelService::class.java).setAction("SWITCH").putExtra("profile", id).putExtra("force", true)) }
                    catch (e: Exception) { Logger.e("Service", "restarting on the edited server failed", e); message = tr(R.string.msg_could_not_start_the_check_or_vpn_45) }
                }
            }
            catch (e: Exception) { Logger.e("Store", "saving the edited server failed", e); message = tr(R.string.msg_check_your_settings_71) }
            finally { busy = false }
        }
    }
    private fun select(id: String, kind: String) {
        if (busy || Diagnostics.running.value || TunnelStatus.state.value == TunnelState.STOPPING) return
        val switching = TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED) && kind == networks.state.value.kind && id != TunnelStatus.activeProfile.value
        val next = saved?.let { if (kind == "mobile") it.copy(selected = id, mobileSelected = id) else it.copy(selected = id, wifiSelected = id) } ?: return
        val nextBook = book?.withSelection(next)
        busy = true; selecting = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(next) }; if (nextBook != null) show(nextBook) else saved = next
                Logger.i("Store", "selected server ($kind): ${next.profiles.firstOrNull { it.id == id }?.let(LogFacts::profileTag)}")
                if (switching && TunnelStatus.state.value in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.BLOCKED)) {
                    TunnelStatus.mutable.value = TunnelState.STARTING
                    try { startService(Intent(this@MainActivity, TunnelService::class.java).setAction("SWITCH").putExtra("profile", id)) }
                    catch (e: Exception) { Logger.e("Service", "switching the server failed", e); message = tr(R.string.msg_could_not_start_the_check_or_vpn_45) }
                }
            }
            catch (e: Exception) { Logger.e("Store", "saving the selected server failed", e); message = tr(R.string.msg_could_not_save_the_selected_server_41) }
            finally { busy = false; selecting = false }
        }
    }
    /** Replaces all subscriptions and settings with a validated backup. Only offered while the VPN is off and no check runs. */
    private fun restoreBackup(content: org.mothvpn.core.Backup.Content) {
        if (busy || TunnelStatus.state.value !in setOf(TunnelState.OFF, TunnelState.ERROR) || Diagnostics.running.value) { Logger.w("Backup", "restore ignored: busy or connected"); return }
        busy = true
        lifecycleScope.launch {
            try {
                val book = withContext(Dispatchers.IO) { if (content.book.sources.isEmpty()) { store.clear(); null } else content.book.also { store.saveBook(it) } }
                show(book)
                busy = false
                // Through the regular settings queue: schedules, kill switch and the persisted copy stay consistent.
                saveSettings(content.settings.withAppVersion(BuildConfig.VERSION_NAME))
                runCatching { ExpiryReminder.schedule(this@MainActivity) }
                Logger.i("Backup", "restored: ${book?.sources?.size ?: 0} subscription(s), ${book?.profiles?.size ?: 0} server(s), ${content.settings.routingRules.size} rule(s)")
            } catch (e: Exception) { Logger.e("Backup", "restore failed", e); message = tr(R.string.backup_failed) }
            finally { busy = false }
        }
    }
    private fun removeSubscription(id: String) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { store.update { it.remove(id) }.also { if (it.sources.isEmpty()) store.clear(); ProviderLogos.remove(this@MainActivity, id) } }
                show(next.takeIf { it.sources.isNotEmpty() })
                Logger.i("Store", "subscription removed by the user (${next.sources.size} left)"); message = tr(R.string.msg_subscription_removed_42)
            }
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
    private fun diagnose(kind: String, order: List<String> = emptyList(), only: List<String> = emptyList()) {
        Logger.i("Probe", "check tapped (kind=$kind, network=${networks.state.value.kind}, available=${networks.state.value.available})")
        if (!networks.state.value.available || networks.state.value.kind != kind) { message = tr(R.string.msg_switch_networks_to_run_this_check_44); return }
        authorize(Intent(this, TunnelService::class.java).setAction("PROBE").putExtra("network", kind).putStringArrayListExtra("order", ArrayList(order)).apply { if (only.isNotEmpty()) putStringArrayListExtra("only", ArrayList(only)) })
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
        if (settingsApplied.value != null) { consumeTileRequest(); consumeDeepLink() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("pending_vpn_action", pendingAction)
        super.onSaveInstanceState(outState)
    }
    /** Links such as moth://import-sub?url=… or vless://…: parsed here, imported only after the user confirms. */
    private fun consumeDeepLink() {
        val i = intent ?: return
        if (i.action != Intent.ACTION_VIEW || i.getBooleanExtra("moth_deeplink_handled", false)) return
        val data = i.dataString ?: return
        i.putExtra("moth_deeplink_handled", true)
        val scheme = data.substringBefore("://", "").lowercase()
        val action = org.mothvpn.core.DeepLinks.parse(data)
        Logger.i("DeepLink", "link opened (scheme=$scheme, ${when (action) { null -> "not recognised"; is org.mothvpn.core.DeepLinks.Action.Subscription -> "subscription"; is org.mothvpn.core.DeepLinks.Action.Links -> "server links" }})")
        if (action == null) { message = tr(R.string.import_invalid); return }
        deepLink = action
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

