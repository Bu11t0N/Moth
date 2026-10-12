package org.mothvpn.android

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

@Composable fun SettingsScreen(initial: ClientSettings, locked: Boolean, save: (ClientSettings) -> Unit, back: () -> Unit, startUpdates: Boolean = false, started: () -> Unit = {}) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var section by remember { mutableStateOf<String?>(if (startUpdates) tr(R.string.update_title) else null) }
    LaunchedEffect(Unit) { if (startUpdates) started() }
    var logPage by remember { mutableStateOf(0) } // 0 none, 1 log, 2 crash log
    var mtu by remember(initial) { mutableStateOf(initial.mtu.toString()) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutMs.toString()) }
    var pingTimeout by remember(initial) { mutableStateOf(initial.probeTimeoutMs.toString()) }
    var parallel by remember(initial) { mutableStateOf(initial.probeParallel.toString()) }
    var showHwid by remember { mutableStateOf(false) }
    var interval by remember(initial) { mutableStateOf(initial.healthIntervalSeconds.toString()) }
    var error by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    BackHandler(enabled = section != null) { if (logPage == 3) { logPage = 0; section = tr(R.string.msg_about_78) } else if (logPage != 0) logPage = 0 else section = null }
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
            AnimatedContent(when (logPage) { 1 -> tr(R.string.dev_log); 2 -> tr(R.string.dev_crash_log); 3 -> tr(R.string.set_changelog); else -> (section ?: tr(R.string.msg_settings_28)) }, modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "settingsTitle") { title ->
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            }
        }
        AnimatedContent(targetState = section to logPage, modifier = Modifier.weight(1f).fillMaxWidth(), transitionSpec = {
            val deeper = targetState.first != null && initialState.first == null || (targetState.second != 0 && initialState.second == 0)
            val shallower = targetState.first == null || (targetState.second == 0 && initialState.second != 0)
            val dir = if (deeper && !shallower) 1 else if (shallower && !deeper) -1 else 0
            (fadeIn(tween(220, delayMillis = 60)) + slideInHorizontally(tween(260)) { it / 8 * dir }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(260)) { -it / 8 * dir })
        }, label = "settingsPage") { page ->
            val sec = page.first; val lp = page.second
        if (sec == tr(R.string.stats_title)) StatsPage(Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (sec == tr(R.string.split_title)) SplitTunnelPage(draft, !locked, { applySettings(it) }, Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (sec == tr(R.string.update_title)) UpdatePage(draft.updateChannel, { applySettings(draft.copy(updateChannel = it)) }, Modifier.fillMaxSize().padding(bottom = 8.dp)) else if (lp == 3) ChangelogPage(Modifier.fillMaxSize()) else if (lp != 0) LogPage(crash = lp == 2, modifier = Modifier.weight(1f)) else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    SettingsCategory(tr(R.string.msg_about_78), tr(R.string.set_sum_about), "about") { section = tr(R.string.msg_about_78) }
                    SettingsCategory(tr(R.string.dev_for_developers), tr(R.string.set_sum_dev, if (Logger.level == LogLevel.OFF) tr(R.string.dev_level_off) else Logger.level.name), "dev") { section = tr(R.string.dev_for_developers) }
                }
                tr(R.string.msg_connection_75) -> {
                    if (locked) Text(tr(R.string.msg_network_settings_are_available_after_disconne_88))
                    SettingsToggle(tr(R.string.auto_select), draft.autoSelect, !locked) { applySettings(draft.copy(autoSelect = it)) }
                    Text(tr(R.string.auto_select_hint), style = MaterialTheme.typography.bodySmall)
                    SettingsToggle(tr(R.string.kill_switch), draft.killSwitch) { applySettings(draft.copy(killSwitch = it)) }
                    Text(tr(R.string.kill_switch_hint), style = MaterialTheme.typography.bodySmall)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }.onFailure { Logger.w("Settings", "system VPN settings unavailable", it) } }) { Text(tr(R.string.kill_switch_system)) }
                    SettingsToggle(tr(R.string.boot_connect), draft.bootConnect) { applySettings(draft.copy(bootConnect = it)) }
                    Text(tr(R.string.boot_connect_hint), style = MaterialTheme.typography.bodySmall)
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
                    listOf("auto" to tr(R.string.msg_https_multiple_urls_100), "https" to tr(R.string.msg_https_custom_url_101), "tcp" to tr(R.string.msg_tcp_port_reachability_102)).forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { applySettings(draft.copy(probeMode = value)) }) {
                            RadioButton(draft.probeMode == value, { applySettings(draft.copy(probeMode = value)) }, enabled = !locked)
                            Text(label, modifier = Modifier.padding(top = 13.dp))
                        }
                    }
                    Text(tr(R.string.msg_tcp_does_not_verify_vpn_traffic_and_cannot_ch_103), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.set_cat_routing) -> {
                    if (locked) Text(tr(R.string.msg_network_settings_are_available_after_disconne_88))
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
                        } }, confirmButton = { TextButton(enabled = DnsServers.valid(text), onClick = { customDns = false; applySettings(draft.copy(dns = text)) }) { Text("OK") } },
                            dismissButton = { TextButton(onClick = { customDns = false }) { Text(tr(R.string.msg_cancel_32)) } })
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
                    SettingsToggle(tr(R.string.expiry_toggle), draft.expiryWarning) { applySettings(draft.copy(expiryWarning = it)) }
                    Text(tr(R.string.expiry_toggle_hint), style = MaterialTheme.typography.bodySmall)
                    SettingsToggle(tr(R.string.msg_send_hwid_to_the_provider_105), draft.hwidEnabled, !locked) { applySettings(draft.copy(hwidEnabled = it)) }
                    Text(tr(R.string.msg_a_persistent_installation_identifier_without_106), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.hwid, { applySettings(draft.copy(hwid = it)) }, enabled = !locked, label = { Text("HWID · x-hwid") }, singleLine = true,
                        visualTransformation = if (showHwid) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showHwid = !showHwid }) { MothIcon(if (showHwid) "eye_off" else "eye") } },
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { clipboard.setText(AnnotatedString(draft.hwid)) }) { Text(tr(R.string.msg_copy_hwid_107)) }
                    SettingsToggle(tr(R.string.msg_send_device_model_and_android_version_108), draft.deviceInfo, !locked) { applySettings(draft.copy(deviceInfo = it)) }
                    OutlinedTextField(draft.userAgent, { applySettings(draft.copy(userAgent = it)) }, enabled = !locked, label = { Text(tr(R.string.msg_subscription_user_agent_109)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(tr(R.string.msg_providers_may_select_the_subscription_format_110), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.msg_appearance_74) -> {
                    val language = AppLanguage.tag().substringBefore(',').substringBefore('-')
                    val languages = listOf("system" to tr(R.string.msg_system_default_80), "en" to "English", "ru" to "Русский")
                    var languageDialog by remember { mutableStateOf(false) }
                    val currentLanguage = languages.firstOrNull { it.first == language }?.second ?: languages[0].second
                    Card(Modifier.fillMaxWidth().animateContentSize().clickable { languageDialog = true }) {
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
                                Row(Modifier.fillMaxWidth().clickable { languageDialog = false; AppLanguage.select(value) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    RadioButton(language == value || (value == "system" && languages.none { it.first == language }), { languageDialog = false; AppLanguage.select(value) })
                                    Text(label)
                                }
                            }
                        } },
                        confirmButton = { TextButton(onClick = { languageDialog = false }) { Text(tr(R.string.msg_cancel_32)) } })
                    val sortModes = listOf(ServerOrder.NAME to tr(R.string.set_sort_name), ServerOrder.LATENCY to tr(R.string.set_sort_latency))
                    var sortDialog by remember { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth().animateContentSize().clickable { sortDialog = true }) {
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
                                Row(Modifier.fillMaxWidth().clickable { sortDialog = false; applySettings(draft.copy(serverSort = value)) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    RadioButton(draft.serverSort == value, { sortDialog = false; applySettings(draft.copy(serverSort = value)) })
                                    Text(label)
                                }
                            }
                        } },
                        confirmButton = { TextButton(onClick = { sortDialog = false }) { Text(tr(R.string.msg_cancel_32)) } })
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        val context = LocalContext.current
                        TextButton(onClick = {
                            context.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                                android.content.ComponentName(context, VpnTileService::class.java), tr(R.string.app_name),
                                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_moth_tile), context.mainExecutor) { }
                        }) { Text(tr(R.string.add_tile)) }
                    }
                    Text(tr(R.string.msg_theme_79), style = MaterialTheme.typography.titleMedium)
                    listOf("system" to tr(R.string.msg_system_default_80), "dark" to tr(R.string.msg_dark_81), "light" to tr(R.string.msg_light_82)).forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable { applySettings(draft.copy(theme = value)) }) {
                            RadioButton(draft.theme == value, { applySettings(draft.copy(theme = value)) })
                            Text(label, modifier = Modifier.padding(top = 13.dp))
                        }
                    }
                    SettingsToggle(tr(R.string.msg_compact_server_rows_83), draft.compactServers) { applySettings(draft.copy(compactServers = it)) }
                    SettingsToggle(tr(R.string.dev_detailed_icons), draft.detailedIcons) { applySettings(draft.copy(detailedIcons = it)) }
                    Text(tr(R.string.dev_detailed_icons_hint), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.dev_for_developers) -> {
                    DeveloperSection({ logPage = 1 }, { logPage = 2 })
                }
                tr(R.string.msg_about_78) -> {
                    Text(VersionLabel.about(tr(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.titleLarge)
                    Text(tr(R.string.msg_sing_box_1_11_15_xray_25_3_6_nopen_source_gpl_111))
                    SettingsCategory(tr(R.string.set_changelog), "", "changelog") { section = tr(R.string.set_changelog); logPage = 3 }
                }
            }
            AnimatedVisibility(error.isNotEmpty()) { Text(error, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun SettingsCategory(title: String, subtitle: String, icon: String, open: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(categoryIcon(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
private fun categoryIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = with(androidx.compose.material.icons.Icons.Outlined) {
    when (name) {
        "connection" -> PowerSettingsNew; "split" -> androidx.compose.material.icons.Icons.AutoMirrored.Outlined.CallSplit; "routing" -> Dns
        "subscription" -> CardMembership; "stats" -> BarChart; "interface" -> Palette; "updates" -> SystemUpdate
        "about" -> Info; "changelog" -> History; else -> Code
    }
}
@Composable private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}
@Composable private fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp, end = 12.dp))
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}
