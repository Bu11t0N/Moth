package org.mothvpn.android

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import org.mothvpn.core.ServerOrder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.mothvpn.core.ClientSettings
import org.mothvpn.core.VersionLabel
import org.mothvpn.core.DnsServers
import org.mothvpn.core.SubscriptionSchedule

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable fun SettingsScreen(initial: ClientSettings, locked: Boolean, save: (ClientSettings) -> Unit, back: () -> Unit, startUpdates: Boolean = false, started: () -> Unit = {}, restore: (org.mothvpn.core.Backup.Content) -> Unit = {}, rootRequest: Int = 0) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var section by remember { mutableStateOf<String?>(if (startUpdates) tr(R.string.update_title) else null) }
    LaunchedEffect(Unit) { if (startUpdates) started() }
    var logPage by remember { mutableStateOf(0) } // 0 none, 1 log, 2 crash log, 3 changelog, 4 licenses, 5 license text
    var licenseAsset by remember { mutableStateOf("") }
    var mtu by remember(initial) { mutableStateOf(initial.mtu.toString()) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutMs.toString()) }
    var pingTimeout by remember(initial) { mutableStateOf(initial.probeTimeoutMs.toString()) }
    var parallel by remember(initial) { mutableStateOf(initial.probeParallel.toString()) }
    var showHwid by remember { mutableStateOf(false) }
    var interval by remember(initial) { mutableStateOf(initial.healthIntervalSeconds.toString()) }
    var error by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    // A tap on Settings in the bottom bar: back to the main list from any subpage. The pages' sheets and dialogs live inside the
    // pages, so they close with them; the usual page transition plays. (The first composition keeps a pending "open updates".)
    val firstRoot = remember { rootRequest }
    LaunchedEffect(rootRequest) { if (rootRequest != firstRoot) { logPage = 0; licenseAsset = ""; showHwid = false; error = ""; section = null } }
    BackHandler(enabled = section != null) { if (logPage == 5) logPage = 4 else if (logPage == 3 || logPage == 4) { logPage = 0; section = tr(R.string.msg_about_78) } else if (logPage != 0) logPage = 0 else if (section == tr(R.string.rules_title)) section = tr(R.string.msg_connection_75) else section = null }
    fun applySettings(next: ClientSettings) {
        draft = next
        try { save(next.validate()); error = "" }
        catch (e: Exception) { Logger.w("Settings", "invalid value rejected: ${e.javaClass.simpleName}: ${e.message}"); error = if (e is java.net.URISyntaxException) tr(R.string.msg_enter_a_valid_https_url_70) else e.message?.let(::localizedError) ?: tr(R.string.msg_check_your_settings_71) }
    }
    fun number(text: String, field: String) {
        val n = text.toIntOrNull()
        if (n == null) { Logger.w("Settings", "not a number for $field"); error = tr(R.string.msg_enter_a_whole_number_72); return }
        applySettings(when (field) { "mtu" -> draft.copy(mtu = n); "timeout" -> draft.copy(timeoutMs = n); "pingTimeout" -> draft.copy(probeTimeoutMs = n); "parallel" -> draft.copy(probeParallel = n); else -> draft.copy(healthIntervalSeconds = n) })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AnimatedContent(when (logPage) { 1 -> tr(R.string.dev_log); 2 -> tr(R.string.dev_crash_log); 3 -> tr(R.string.set_changelog); 4 -> tr(R.string.set_licenses); 5 -> Licenses.entries.firstOrNull { it.asset == licenseAsset }?.let { tr(it.title) } ?: tr(R.string.set_licenses); else -> (section ?: tr(R.string.msg_settings_28)) }, modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "settingsTitle") { title ->
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            }
        }
        // Settings pages: a page opened from a card (or the About entries) grows out of that card and shrinks back into it on Back
        // (container transform, shared bounds); the rest fades. Pages without a card (logs, a licence text) keep the short slide.
        fun containerKey(page: Pair<String?, Int>): String? = when {
            page.second == 3 -> "changelog"; page.second == 4 -> "licenses"; page.second != 0 -> null
            else -> mapOf(tr(R.string.msg_connection_75) to "connection", tr(R.string.split_title) to "split", tr(R.string.set_cat_routing) to "routing",
                tr(R.string.set_cat_subscription) to "subscription", tr(R.string.stats_title) to "stats", tr(R.string.msg_appearance_74) to "interface",
                tr(R.string.update_title) to "updates", tr(R.string.backup_title) to "backup", tr(R.string.msg_about_78) to "about",
                tr(R.string.dev_for_developers) to "dev", tr(R.string.rules_title) to "rules")[page.first]
        }
        // The page that shows the card a page opens from: the main list, Connection (routing rules) or About (changelog, licences).
        fun parentPage(key: String): Pair<String?, Int> = when (key) {
            "rules" -> tr(R.string.msg_connection_75) to 0; "changelog", "licenses" -> tr(R.string.msg_about_78) to 0; else -> null to 0
        }
        val motion = animationsOn()
        val rootScroll = rememberScrollState()
        SharedTransitionLayout(Modifier.weight(1f).fillMaxWidth()) { CompositionLocalProvider(LocalSharedScope provides this) {
        AnimatedContent(targetState = section to logPage, modifier = Modifier.fillMaxSize(), transitionSpec = {
            if (!motion) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            // A card ↔ page change (the page opened from a card on the other page): the shared bounds do the motion, the pages only fade.
            if (containerKey(targetState)?.let { parentPage(it) == initialState } == true || containerKey(initialState)?.let { parentPage(it) == targetState } == true)
                return@AnimatedContent fadeIn(tween(Motion.SHORT, delayMillis = 40)) togetherWith fadeOut(tween(160))
            val deeper = targetState.first != null && initialState.first == null || (targetState.second != 0 && initialState.second == 0)
            val shallower = targetState.first == null || (targetState.second == 0 && initialState.second != 0)
            val dir = if (deeper && !shallower) 1 else if (shallower && !deeper) -1 else 0
            (fadeIn(tween(220, delayMillis = 60)) + slideInHorizontally(tween(260)) { it / 8 * dir }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(260)) { -it / 8 * dir })
        }, label = "settingsPage") { page ->
            val sec = page.first; val lp = page.second
            CompositionLocalProvider(LocalPageScope provides this) {
            Box(Modifier.fillMaxSize().containerTransform(containerKey(page))) {
        if (sec == tr(R.string.backup_title)) BackupPage(draft, locked, restore, Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (sec == tr(R.string.rules_title)) RoutingRulesPage(draft.routingRules, !locked, { applySettings(draft.copy(routingRules = it)) }, Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (sec == tr(R.string.stats_title)) StatsPage(Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (sec == tr(R.string.split_title)) SplitTunnelPage(draft, !locked, { applySettings(it) }, Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (sec == tr(R.string.update_title)) UpdatePage(draft.updateChannel, { applySettings(draft.copy(updateChannel = it)) }, Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (lp == 3) ChangelogPage(Modifier.fillMaxSize()) else if (lp == 4) LicensesList({ licenseAsset = it; logPage = 5 }, Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (lp == 5) LicenseTextPage(licenseAsset, Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (lp != 0) LogPage(crash = lp == 2, modifier = Modifier.fillMaxSize()) else Column(Modifier.fillMaxSize().verticalScroll(if (sec == null) rootScroll else rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (sec) {
                null -> {
                    val ctxMenu = LocalContext.current
                    val stats by TrafficRecorder.state.collectAsState()
                    LaunchedEffect(Unit) { TrafficRecorder.load(ctxMenu) }
                    val loc = AppLanguage.context().resources.configuration.locales[0]
                    val units = tr(R.string.notif_units).split("|")
                    val today = stats.day(java.time.LocalDate.now())
                    val onOff = listOfNotNull(tr(R.string.set_short_auto).takeIf { draft.autoSelect }, tr(R.string.set_short_ks).takeIf { draft.killSwitch },
                        tr(R.string.set_short_boot).takeIf { draft.bootConnect }, tr(R.string.set_short_health).takeIf { draft.healthEnabled })
                    val theme = when (draft.theme) { "dark" -> tr(R.string.set_theme_dark); "light" -> tr(R.string.set_theme_light); else -> tr(R.string.set_theme_auto) }
                    val split = when (draft.splitMode) { "only" -> tr(R.string.set_split_only, draft.splitApps.size); "bypass" -> tr(R.string.set_split_bypass, draft.splitApps.size); else -> tr(R.string.set_split_off) }
                    val subAuto = if (draft.subUpdateHours == 0) tr(R.string.set_sum_sub_off) else tr(R.string.set_sum_sub_hours, draft.subUpdateHours)
                    SettingsCategory(tr(R.string.msg_connection_75), onOff.joinToString(" · ").ifEmpty { tr(R.string.set_sum_defaults) }, "connection") { section = tr(R.string.msg_connection_75) }
                    SettingsCategory(tr(R.string.split_title), split, "split") { section = tr(R.string.split_title) }
                    SettingsCategory(tr(R.string.set_cat_routing), listOfNotNull(DnsServers.presetOf(draft.dns)?.label ?: tr(R.string.dns_custom), tr(R.string.set_short_ru).takeIf { draft.directRu }).joinToString(" · "), "routing") { section = tr(R.string.set_cat_routing) }
                    SettingsCategory(tr(R.string.set_cat_subscription), subAuto, "subscription") { section = tr(R.string.set_cat_subscription) }
                    SettingsCategory(tr(R.string.stats_title), tr(R.string.stats_today) + ": " + org.mothvpn.core.TrafficFormat.bytes(today.first + today.second, units, loc), "stats") { section = tr(R.string.stats_title) }
                    SettingsCategory(tr(R.string.msg_appearance_74), theme, "interface") { section = tr(R.string.msg_appearance_74) }
                    SettingsCategory(tr(R.string.update_title), tr(R.string.set_sum_version, BuildConfig.VERSION_NAME), "updates") { section = tr(R.string.update_title) }
                    SettingsCategory(tr(R.string.backup_title), tr(R.string.backup_sum), "backup") { section = tr(R.string.backup_title) }
                    SettingsCategory(tr(R.string.msg_about_78), tr(R.string.set_sum_about), "about") { section = tr(R.string.msg_about_78) }
                    SettingsCategory(tr(R.string.dev_for_developers), tr(R.string.set_sum_dev, if (Logger.level == LogLevel.OFF) tr(R.string.dev_level_off) else Logger.level.name), "dev") { section = tr(R.string.dev_for_developers) }
                }
                tr(R.string.msg_connection_75) -> {
                    LiveApplyHint()
                    SettingsToggle(tr(R.string.auto_select), draft.autoSelect, !locked) { applySettings(draft.copy(autoSelect = it)) }
                    Text(tr(R.string.auto_select_hint), style = MaterialTheme.typography.bodySmall)
                    SettingsCategory(tr(R.string.rules_title), if (draft.routingRules.isEmpty()) tr(R.string.rules_sum_none) else tr(R.string.rules_sum_count, draft.routingRules.size), "rules") { section = tr(R.string.rules_title) }
                    SettingsToggle(tr(R.string.kill_switch), draft.killSwitch) { applySettings(draft.copy(killSwitch = it)) }
                    Text(tr(R.string.kill_switch_hint), style = MaterialTheme.typography.bodySmall)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }.onFailure { Logger.w("Settings", "system VPN settings unavailable", it) } }, modifier = Modifier.pressScale()) { Text(tr(R.string.kill_switch_system)) }
                    SettingsToggle(tr(R.string.boot_connect), draft.bootConnect) { applySettings(draft.copy(bootConnect = it)) }
                    Text(tr(R.string.boot_connect_hint), style = MaterialTheme.typography.bodySmall)
                    SectionHeader(tr(R.string.set_head_dpi))
                    var fragDialog by remember { mutableStateOf(false) }
                    var muxDialog by remember { mutableStateOf(false) }
                    val fragOptions = listOf("off" to tr(R.string.fragment_off), "tlshello" to tr(R.string.fragment_tlshello), "tlshello_small" to tr(R.string.fragment_tlshello_small), "first_packets" to tr(R.string.fragment_first_packets))
                    DialogRow(tr(R.string.fragment_title), fragOptions.firstOrNull { it.first == draft.fragment }?.second ?: draft.fragment, !locked) { fragDialog = true }
                    if (fragDialog) ChoiceDialog(tr(R.string.fragment_title), fragOptions, draft.fragment, { fragDialog = false }) { applySettings(draft.copy(fragment = it)) }
                    Text(tr(R.string.fragment_hint), style = MaterialTheme.typography.bodySmall)
                    SettingsToggle(tr(R.string.noise_title), draft.noise, !locked) { applySettings(draft.copy(noise = it)) }
                    Text(tr(R.string.noise_hint), style = MaterialTheme.typography.bodySmall)
                    val muxOptions = listOf("off" to tr(R.string.mux_off), "smux" to "smux", "yamux" to "yamux", "h2mux" to "h2mux")
                    DialogRow(tr(R.string.mux_title), muxOptions.firstOrNull { it.first == draft.mux }?.second ?: draft.mux, !locked) { muxDialog = true }
                    if (muxDialog) ChoiceDialog(tr(R.string.mux_title), muxOptions, draft.mux, { muxDialog = false }) { applySettings(draft.copy(mux = it)) }
                    Text(tr(R.string.mux_hint), style = MaterialTheme.typography.bodySmall)
                    SectionHeader(tr(R.string.set_head_health))
                    SettingsToggle(tr(R.string.msg_check_the_active_tunnel_94), draft.healthEnabled, !locked) { applySettings(draft.copy(healthEnabled = it)) }
                    OutlinedTextField(interval, { interval = it; number(it, "interval") }, enabled = !locked, label = { Text(tr(R.string.msg_interval_30_600_seconds_95)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(timeout, { timeout = it; number(it, "timeout") }, enabled = !locked, label = { Text(tr(R.string.msg_timeout_3_30_seconds_96)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SectionHeader(tr(R.string.set_head_probes))
                    OutlinedTextField(draft.probeUrl, { applySettings(draft.copy(probeUrl = it)) }, enabled = !locked, label = { Text(tr(R.string.msg_https_check_url_97)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(tr(R.string.msg_latency_includes_establishing_an_https_connec_98), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(pingTimeout, { pingTimeout = it; number(it, "pingTimeout") }, enabled = !locked, label = { Text(tr(R.string.set_ping_timeout)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(parallel, { parallel = it; number(it, "parallel") }, enabled = !locked, label = { Text(tr(R.string.set_ping_parallel)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(tr(R.string.msg_check_method_99), style = MaterialTheme.typography.titleMedium)
                    // Latency (default): warm-up request, then the second request on the same connection; Full latency: the first request with
                    // every handshake; TCP: only the server port. Each with a one-line explanation.
                    listOf(Triple(org.mothvpn.core.ProbePolicy.LATENCY, tr(R.string.ping_latency), tr(R.string.ping_latency_hint)),
                        Triple(org.mothvpn.core.ProbePolicy.FULL, tr(R.string.ping_full), tr(R.string.ping_full_hint)),
                        Triple(org.mothvpn.core.ProbePolicy.TCP, tr(R.string.ping_tcp), tr(R.string.ping_tcp_hint))).forEach { (value, label, hint) ->
                        Row(Modifier.pressScale(enabled = !locked).fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !locked) { applySettings(draft.copy(probeMode = value)) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(draft.probeMode == value, { applySettings(draft.copy(probeMode = value)) }, enabled = !locked)
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(tr(R.string.msg_tcp_does_not_verify_vpn_traffic_and_cannot_ch_103), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.set_cat_routing) -> {
                    LiveApplyHint()
                    SettingsToggle(tr(R.string.direct_ru), draft.directRu, !locked) { applySettings(draft.copy(directRu = it)) }
                    Text(tr(R.string.direct_ru_hint), style = MaterialTheme.typography.bodySmall)
                    var dnsDialog by remember { mutableStateOf(false) }
                    var customDns by remember { mutableStateOf(false) }
                    DialogRow(tr(R.string.dns_server), DnsServers.presetOf(draft.dns)?.label ?: (tr(R.string.dns_custom) + ": " + draft.dns), !locked) { dnsDialog = true }
                    if (dnsDialog) ChoiceDialog(tr(R.string.dns_server), DnsServers.presets.map { it.address to it.label } + ("" to tr(R.string.dns_custom)),
                        if (DnsServers.presetOf(draft.dns) != null) draft.dns else "", { dnsDialog = false }) { if (it.isEmpty()) customDns = true else applySettings(draft.copy(dns = it)) }
                    if (customDns) {
                        var text by remember { mutableStateOf(draft.dns) }
                        AlertDialog(onDismissRequest = { customDns = false }, title = { Text(tr(R.string.dns_custom)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(text, { text = it.trim() }, singleLine = true, isError = !DnsServers.valid(text), modifier = Modifier.fillMaxWidth())
                            Text(tr(R.string.dns_custom_hint), style = MaterialTheme.typography.bodySmall)
                        } }, confirmButton = { TextButton(enabled = DnsServers.valid(text), onClick = { customDns = false; applySettings(draft.copy(dns = text)) }, modifier = Modifier.pressScale(enabled = DnsServers.valid(text))) { Text(tr(R.string.ok)) } },
                            dismissButton = { TextButton(onClick = { customDns = false }, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
                    }
                    SettingsToggle(tr(R.string.dns_leak), draft.dnsLeakProtection, !locked) { applySettings(draft.copy(dnsLeakProtection = it)) }
                    Text(tr(R.string.dns_leak_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.bootstrapDns, { applySettings(draft.copy(bootstrapDns = it)) }, enabled = !locked, label = { Text(tr(R.string.msg_server_bootstrap_dns_91)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(tr(R.string.msg_this_dns_is_used_directly_to_connect_to_serve_92), style = MaterialTheme.typography.bodySmall)
                    SettingsToggle(tr(R.string.msg_ipv6_through_vpn_89), draft.ipv6, !locked) { applySettings(draft.copy(ipv6 = it)) }
                    Text(tr(R.string.msg_when_disabled_ipv6_is_blocked_inside_the_tunn_90), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(mtu, { mtu = it; number(it, "mtu") }, enabled = !locked, label = { Text("MTU · 1280–9000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                tr(R.string.set_cat_subscription) -> {
                    if (locked) Text(tr(R.string.msg_disconnect_and_finish_checks_to_change_subscr_104))
                    var subDialog by remember { mutableStateOf(false) }
                    val subOptions = SubscriptionSchedule.intervals.map { it to if (it == 0) tr(R.string.sub_auto_off) else tr(R.string.sub_auto_hours, it) }
                    DialogRow(tr(R.string.sub_auto), subOptions.first { it.first == draft.subUpdateHours }.second) { subDialog = true }
                    if (subDialog) ChoiceDialog(tr(R.string.sub_auto), subOptions, draft.subUpdateHours, { subDialog = false }) { applySettings(draft.copy(subUpdateHours = it)) }
                    var trafficDialog by remember { mutableStateOf(false) }
                    val trafficOptions = org.mothvpn.core.TrafficWarning.thresholds.map { it to if (it == 0) tr(R.string.sub_auto_off) else "$it%" }
                    DialogRow(tr(R.string.traffic_warning_setting), trafficOptions.first { it.first == draft.trafficWarning }.second) { trafficDialog = true }
                    if (trafficDialog) ChoiceDialog(tr(R.string.traffic_warning_setting), trafficOptions, draft.trafficWarning, { trafficDialog = false }) { applySettings(draft.copy(trafficWarning = it)) }
                    Text(tr(R.string.traffic_warning_hint), style = MaterialTheme.typography.bodySmall)
                    SettingsToggle(tr(R.string.expiry_toggle), draft.expiryWarning) { applySettings(draft.copy(expiryWarning = it)) }
                    Text(tr(R.string.expiry_toggle_hint), style = MaterialTheme.typography.bodySmall)
                    SettingsToggle(tr(R.string.msg_send_hwid_to_the_provider_105), draft.hwidEnabled, !locked) { applySettings(draft.copy(hwidEnabled = it)) }
                    Text(tr(R.string.msg_a_persistent_installation_identifier_without_106), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.hwid, { applySettings(draft.copy(hwid = it)) }, enabled = !locked, label = { Text("HWID · x-hwid") }, singleLine = true,
                        visualTransformation = if (showHwid) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showHwid = !showHwid }, modifier = Modifier.pressScale()) { MothIcon(if (showHwid) "eye_off" else "eye") } },
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { clipboard.setText(AnnotatedString(draft.hwid)) }, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_copy_hwid_107)) }
                    SettingsToggle(tr(R.string.msg_send_device_model_and_android_version_108), draft.deviceInfo, !locked) { applySettings(draft.copy(deviceInfo = it, deviceInfoSet = true)) }
                    // The device info that is sent (system values or the user's own), edited in a scrollable dialog. Never logged.
                    if (draft.deviceInfo) {
                        var deviceDialog by remember { mutableStateOf(false) }
                        val sent = DeviceDefaults.sent(draft)
                        DialogRow(tr(R.string.device_title), sent.joinToString(" · "), enabled = !locked) { deviceDialog = true }
                        if (deviceDialog) DeviceInfoDialog(draft, { deviceDialog = false }) { applySettings(it); deviceDialog = false }
                    }
                    // Subscription User-Agent: one row with the current choice; a sheet picks Moth, a client preset or the user's own value. Never logged.
                    var uaSheet by remember { mutableStateOf(false) }
                    DialogRow(tr(R.string.msg_subscription_user_agent_109), org.mothvpn.core.UserAgents.title(draft.userAgent, BuildConfig.VERSION_NAME), enabled = !locked) { uaSheet = true }
                    if (uaSheet) UserAgentSheet(draft.userAgent, close = { uaSheet = false }) { applySettings(draft.copy(userAgent = it)); uaSheet = false }
                    Text(tr(R.string.msg_providers_may_select_the_subscription_format_110), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.msg_appearance_74) -> {
                    val language = AppLanguage.tag().substringBefore(',').substringBefore('-')
                    val languages = listOf("system" to tr(R.string.msg_system_default_80), "en" to "English", "ru" to "Русский")
                    var languageDialog by remember { mutableStateOf(false) }
                    val currentLanguage = languages.firstOrNull { it.first == language }?.second ?: languages[0].second
                    Card(Modifier.pressScale().fillMaxWidth().animateContentSize().clickable { languageDialog = true }, colors = ndCardColors()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr(R.string.language), style = MaterialTheme.typography.titleMedium)
                                Text(currentLanguage, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    if (languageDialog) AlertDialog(onDismissRequest = { languageDialog = false }, title = { Text(tr(R.string.language)) },
                        text = { Column {
                            languages.forEach { (value, label) ->
                                Row(Modifier.pressScale().fillMaxWidth().clickable { languageDialog = false; AppLanguage.select(value) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    RadioButton(language == value || (value == "system" && languages.none { it.first == language }), { languageDialog = false; AppLanguage.select(value) })
                                    Text(label)
                                }
                            }
                        } },
                        confirmButton = { TextButton(onClick = { languageDialog = false }, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
                    val sortModes = listOf(ServerOrder.SUBSCRIPTION to tr(R.string.set_sort_subscription), ServerOrder.LATENCY to tr(R.string.set_sort_latency), ServerOrder.NAME to tr(R.string.set_sort_name))
                    var sortDialog by remember { mutableStateOf(false) }
                    Card(Modifier.pressScale().fillMaxWidth().animateContentSize().clickable { sortDialog = true }, colors = ndCardColors()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr(R.string.set_sort_servers), style = MaterialTheme.typography.titleMedium)
                                Text(sortModes.firstOrNull { it.first == draft.serverSort }?.second ?: sortModes[0].second, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    if (sortDialog) AlertDialog(onDismissRequest = { sortDialog = false }, title = { Text(tr(R.string.set_sort_servers)) },
                        text = { Column {
                            sortModes.forEach { (value, label) ->
                                Row(Modifier.pressScale().fillMaxWidth().clickable { sortDialog = false; applySettings(draft.copy(serverSort = value)) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    RadioButton(draft.serverSort == value, { sortDialog = false; applySettings(draft.copy(serverSort = value)) })
                                    Text(label)
                                }
                            }
                        } },
                        confirmButton = { TextButton(onClick = { sortDialog = false }, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        val context = LocalContext.current
                        TextButton(onClick = {
                            context.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                                android.content.ComponentName(context, VpnTileService::class.java), tr(R.string.app_name),
                                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_moth_tile), context.mainExecutor) { }
                        }, modifier = Modifier.pressScale()) { Text(tr(R.string.add_tile)) }
                    }
                    Text(tr(R.string.msg_theme_79), style = MaterialTheme.typography.titleMedium)
                    listOf("system" to tr(R.string.msg_system_default_80), "dark" to tr(R.string.msg_dark_81), "light" to tr(R.string.msg_light_82)).forEach { (value, label) ->
                        Row(Modifier.pressScale().fillMaxWidth().clickable { applySettings(draft.copy(theme = value)) }) {
                            RadioButton(draft.theme == value, { applySettings(draft.copy(theme = value)) })
                            Text(label, modifier = Modifier.padding(top = 13.dp))
                        }
                    }
                    // Pure black for the dark theme (unavailable while the light theme is chosen).
                    SettingsToggle(tr(R.string.set_amoled), draft.amoled, enabled = draft.theme != "light") { applySettings(draft.copy(amoled = it)) }
                    SettingsToggle(tr(R.string.msg_compact_server_rows_83), draft.compactServers) { applySettings(draft.copy(compactServers = it)) }
                    SettingsToggle(tr(R.string.dev_detailed_icons), draft.detailedIcons) { applySettings(draft.copy(detailedIcons = it)) }
                    Text(tr(R.string.dev_detailed_icons_hint), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.dev_for_developers) -> {
                    DeveloperSection({ logPage = 1 }, { logPage = 2 })
                    var headersDialog by remember { mutableStateOf(false) }
                    DialogRow(tr(R.string.dev_sub_headers), tr(R.string.dev_sub_headers_sum)) { headersDialog = true }
                    if (headersDialog) SubscriptionHeadersDialog { headersDialog = false }
                    SettingsToggle(tr(R.string.dev_edit_servers), draft.editServers) { applySettings(draft.copy(editServers = it)) }
                    Text(tr(R.string.dev_edit_servers_hint), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.msg_about_78) -> {
                    Text(VersionLabel.about(tr(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.titleLarge)
                    Text(tr(R.string.msg_sing_box_1_11_15_xray_25_3_6_nopen_source_gpl_111))
                    SettingsCategory(tr(R.string.set_changelog), "", "changelog") { section = tr(R.string.set_changelog); logPage = 3 }
                    SettingsCategory(tr(R.string.set_licenses), tr(R.string.set_licenses_sum), "licenses") { section = tr(R.string.set_licenses); logPage = 4 }
                    // Opens the browser, not a page: same card, no container transform, an "open in new" mark instead of the arrow.
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    SettingsCategory(tr(R.string.about_github), "github.com/Bu11t0N/Moth", "github", external = true) { runCatching { uri.openUri("https://github.com/Bu11t0N/Moth") } }
                }
            }
            AnimatedVisibility(error.isNotEmpty()) { Text(error, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
            } }
        }
        } }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun SettingsCategory(title: String, subtitle: String, icon: String, external: Boolean = false, open: () -> Unit) {
    // The card is the start/end of its page's container transform (key = icon name) and shrinks slightly while pressed.
    // [external]: a link that opens the browser: no container transform, and an "open in new" mark instead of the arrow.
    Card(Modifier.containerTransform(if (external) null else icon).pressScale().fillMaxWidth().clickable(onClick = open), colors = ndCardColors()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            // A monochrome icon in a grey rounded-square tile (no colours, no circle).
            IconTile(categoryIcon(icon))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(if (external) androidx.compose.material.icons.Icons.Outlined.OpenInNew else androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
private fun categoryIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = with(androidx.compose.material.icons.Icons.Outlined) {
    when (name) {
        "connection" -> PowerSettingsNew; "split" -> androidx.compose.material.icons.Icons.AutoMirrored.Outlined.CallSplit; "routing" -> Dns
        "subscription" -> CardMembership; "stats" -> BarChart; "interface" -> Palette; "updates" -> SystemUpdate
        "about" -> Info; "rules" -> AltRoute; "backup" -> SettingsBackupRestore; "changelog" -> History; "licenses" -> Description; "github" -> Code; else -> Code
    }
}
@Composable private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}
@Composable private fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    // The whole row (at least 56 dp high) toggles the switch.
    Row(Modifier.pressScale(enabled = enabled).fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { change(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        MothSwitch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}

/** Developer: per subscription, the NAMES of the response headers and body fields from its last update (never values or links). */
@Composable fun SubscriptionHeadersDialog(close: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rows by produceState<List<Pair<String, ResponseHeaderNames.Names?>>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { SubscriptionStore(context).loadBook()?.sources.orEmpty().filter { !it.local }.map { s -> s.title() to ResponseHeaderNames.load(context, s.id) } }.getOrDefault(emptyList())
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.dev_sub_headers)) }, text = {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(R.string.dev_sub_headers_hint), style = MaterialTheme.typography.bodySmall)
                val list = rows
                if (list != null && list.isEmpty()) Text(tr(R.string.dev_sub_headers_none))
                list?.forEach { (title, names) ->
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (names == null) Text(tr(R.string.dev_sub_headers_update), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(tr(R.string.dev_sub_headers_http, names.headers.joinToString(", ").ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall)
                        Text(tr(R.string.dev_sub_headers_body, names.body.joinToString(", ").ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close, modifier = Modifier.pressScale()) { Text(tr(R.string.sub_link_close)) } })
}

/** While the VPN is starting or stopping, settings wait. While it is on they can be changed (applied silently, no hint). */
@Composable private fun LiveApplyHint() {
    val tunnel by TunnelStatus.state.collectAsState()
    if (tunnel in setOf(TunnelState.STARTING, TunnelState.STOPPING)) Text(tr(R.string.settings_wait_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The device info Moth sends by default (same values as the subscription headers) and what it sends with [settings]. */
object DeviceDefaults {
    val values get() = listOf("Android", org.mothvpn.core.DeviceInfo.clean(android.os.Build.VERSION.RELEASE.orEmpty(), 64),
        org.mothvpn.core.DeviceInfo.clean("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", 120))
    val limits = listOf(64, 64, 120)
    fun sent(settings: ClientSettings) = listOf(settings.deviceOs, settings.deviceOsVersion, settings.deviceModel).mapIndexed { i, v -> v.trim().ifEmpty { values[i] } }
}

/**
 * Device info dialog: each field holds the value that is sent now (default or the user's own) as editable text; Reset puts the
 * defaults back. A value equal to the default is stored as "default" (empty), so it follows system updates. Scrolls on short
 * screens, with a large font and with the keyboard open. Values are never logged.
 */
@Composable private fun DeviceInfoDialog(settings: ClientSettings, close: () -> Unit, save: (ClientSettings) -> Unit) {
    val defaults = remember { DeviceDefaults.values }
    val fields = remember { DeviceDefaults.sent(settings).map { mutableStateOf(it) } }
    val labels = listOf(tr(R.string.device_os), tr(R.string.device_os_version), tr(R.string.device_model))
    val valid = fields.indices.all { i -> fields[i].value.trim().isNotEmpty() && org.mothvpn.core.DeviceInfo.valid(fields[i].value.trim(), DeviceDefaults.limits[i]) }
    AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.device_title)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.forEachIndexed { i, field ->
                OutlinedTextField(field.value, { field.value = it.take(DeviceDefaults.limits[i]) }, label = { Text(labels[i]) }, singleLine = true,
                    isError = !org.mothvpn.core.DeviceInfo.valid(field.value.trim(), DeviceDefaults.limits[i]) || field.value.isBlank(), modifier = Modifier.fillMaxWidth())
            }
            TextButton(enabled = fields.indices.any { fields[it].value != defaults[it] }, onClick = { fields.forEachIndexed { i, f -> f.value = defaults[i] } }, modifier = Modifier.pressScale(enabled = fields.indices.any { fields[it].value != defaults[it] })) { Text(tr(R.string.device_reset)) }
            Text(tr(R.string.device_hint), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = {
        val v = fields.mapIndexed { i, f -> f.value.trim().takeIf { it != defaults[i] }.orEmpty() }
        save(settings.copy(deviceOs = v[0], deviceOsVersion = v[1], deviceModel = v[2]))
    }, modifier = Modifier.pressScale(enabled = valid)) { Text(tr(R.string.sub_save)) } }, dismissButton = { TextButton(onClick = close, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
}

/** Radio list of User-Agent choices in the Nd sheet style; "Custom" opens an empty field (prefilled only when the saved value is already custom). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun UserAgentSheet(current: String, close: () -> Unit, choose: (String) -> Unit) {
    val version = BuildConfig.VERSION_NAME
    val start = remember(current) { org.mothvpn.core.UserAgents.choice(current, version) }
    var selected by remember { mutableStateOf(start.id) }
    var custom by remember { mutableStateOf(start.custom) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NdTone.sheet()) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(R.string.msg_subscription_user_agent_109), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            org.mothvpn.core.UserAgents.presets(version).forEach { p ->
                Row(Modifier.pressScale().fillMaxWidth().heightIn(min = 56.dp).clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { selected = p.id; choose(p.value) },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == p.id, { selected = p.id; choose(p.value) })
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = MaterialTheme.typography.bodyLarge)
                        Text(p.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.pressScale().fillMaxWidth().heightIn(min = 56.dp).clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { selected = org.mothvpn.core.UserAgents.CUSTOM },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == org.mothvpn.core.UserAgents.CUSTOM, { selected = org.mothvpn.core.UserAgents.CUSTOM })
                Text(tr(R.string.ua_custom), style = MaterialTheme.typography.bodyLarge)
            }
            if (selected == org.mothvpn.core.UserAgents.CUSTOM) {
                val ok = org.mothvpn.core.UserAgents.valid(custom)
                OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text("User-Agent") }, isError = custom.isNotEmpty() && !ok,
                    supportingText = if (custom.isNotEmpty() && !ok) { { Text(tr(R.string.ua_custom_invalid)) } } else null,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (ok) choose(custom) }))
                Button(onClick = { choose(custom) }, enabled = ok, modifier = Modifier.pressScale(enabled = ok).align(Alignment.End)) { Text(tr(R.string.sub_save)) }
            }
        }
    }
}
