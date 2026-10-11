package org.routevpn.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.routevpn.core.ClientSettings

@Composable fun SettingsScreen(initial: ClientSettings, locked: Boolean, save: (ClientSettings) -> Unit, back: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var section by remember { mutableStateOf<String?>(null) }
    var logPage by remember { mutableStateOf(0) } // 0 none, 1 log, 2 crash log
    var mtu by remember(initial) { mutableStateOf(initial.mtu.toString()) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var pingTimeout by remember(initial) { mutableStateOf(initial.probeTimeoutSeconds.toString()) }
    var parallel by remember(initial) { mutableStateOf(initial.probeParallel.toString()) }
    var showHwid by remember { mutableStateOf(false) }
    var interval by remember(initial) { mutableStateOf(initial.healthIntervalSeconds.toString()) }
    var error by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    BackHandler(enabled = section != null) { if (logPage != 0) logPage = 0 else section = null }
    fun applySettings(next: ClientSettings) {
        draft = next
        try { save(next.validate()); error = "" }
        catch (e: Exception) { error = if (e is java.net.URISyntaxException) tr(R.string.msg_enter_a_valid_https_url_70) else e.message?.let(::localizedError) ?: tr(R.string.msg_check_your_settings_71) }
    }
    fun number(text: String, field: String) {
        val n = text.toIntOrNull()
        if (n == null) { error = tr(R.string.msg_enter_a_whole_number_72); return }
        applySettings(when (field) { "mtu" -> draft.copy(mtu = n); "timeout" -> draft.copy(timeoutSeconds = n); "pingTimeout" -> draft.copy(probeTimeoutSeconds = n); "parallel" -> draft.copy(probeParallel = n); else -> draft.copy(healthIntervalSeconds = n) })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when (logPage) { 1 -> tr(R.string.dev_log); 2 -> tr(R.string.dev_crash_log); else -> (section ?: tr(R.string.msg_settings_28)) }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
        }
        if (logPage != 0) LogPage(crash = logPage == 2, modifier = Modifier.weight(1f)) else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (section) {
                null -> {
                    SettingsCategory(tr(R.string.msg_appearance_74)) { section = tr(R.string.msg_appearance_74) }
                    SettingsCategory(tr(R.string.msg_connection_75)) { section = tr(R.string.msg_connection_75) }
                    SettingsCategory(tr(R.string.msg_checks_76)) { section = tr(R.string.msg_checks_76) }
                    SettingsCategory(tr(R.string.msg_subscription_and_hwid_77)) { section = tr(R.string.msg_subscription_and_hwid_77) }
                    SettingsCategory(tr(R.string.msg_about_78)) { section = tr(R.string.msg_about_78) }
                    SettingsCategory(tr(R.string.dev_for_developers)) { section = tr(R.string.dev_for_developers) }
                }
                tr(R.string.msg_appearance_74) -> {
                    Text(tr(R.string.language), style = MaterialTheme.typography.titleMedium)
                    val language = AppLanguage.tag().substringBefore(',').substringBefore('-')
                    val languages = listOf("system" to tr(R.string.msg_system_default_80), "en" to "English", "ru" to "Русский")
                    var languageMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { languageMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(languages.firstOrNull { it.first == language }?.second ?: languages[0].second, modifier = Modifier.weight(1f))
                            Text("▾")
                        }
                        DropdownMenu(languageMenu, { languageMenu = false }) {
                            languages.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { languageMenu = false; AppLanguage.select(value) }) }
                        }
                    }
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
                    SettingsToggle(tr(R.string.msg_sort_by_latency_84), draft.sortByLatency) { applySettings(draft.copy(sortByLatency = it)) }
                    SettingsToggle(tr(R.string.msg_hide_unavailable_servers_85), draft.hideUnavailable) { applySettings(draft.copy(hideUnavailable = it)) }
                    Text(tr(R.string.msg_servers_with_failed_checks_in_the_last_15_min_86), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.msg_connection_75) -> {
                    if (locked) Text(tr(R.string.msg_network_settings_are_available_after_disconne_88))
                    OutlinedTextField(mtu, { mtu = it; number(it, "mtu") }, enabled = !locked, label = { Text("MTU · 1280–9000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SettingsToggle(tr(R.string.msg_ipv6_through_vpn_89), draft.ipv6, !locked) { applySettings(draft.copy(ipv6 = it)) }
                    Text(tr(R.string.msg_when_disabled_ipv6_is_blocked_inside_the_tunn_90), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.dns, { applySettings(draft.copy(dns = it)) }, enabled = !locked, label = { Text("DNS over HTTPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.bootstrapDns, { applySettings(draft.copy(bootstrapDns = it)) }, enabled = !locked, label = { Text(tr(R.string.msg_server_bootstrap_dns_91)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(tr(R.string.msg_this_dns_is_used_directly_to_connect_to_serve_92), style = MaterialTheme.typography.bodySmall)
                }
                tr(R.string.msg_checks_76) -> {
                    if (locked) Text(tr(R.string.msg_disconnect_vpn_and_finish_checks_to_change_th_93))
                    SettingsToggle(tr(R.string.msg_check_the_active_tunnel_94), draft.healthEnabled, !locked) { applySettings(draft.copy(healthEnabled = it)) }
                    OutlinedTextField(interval, { interval = it; number(it, "interval") }, enabled = !locked, label = { Text(tr(R.string.msg_interval_30_600_seconds_95)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(timeout, { timeout = it; number(it, "timeout") }, enabled = !locked, label = { Text(tr(R.string.msg_timeout_3_30_seconds_96)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                tr(R.string.msg_subscription_and_hwid_77) -> {
                    if (locked) Text(tr(R.string.msg_disconnect_and_finish_checks_to_change_subscr_104))
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
                tr(R.string.dev_for_developers) -> DeveloperSection({ logPage = 1 }, { logPage = 2 })
                tr(R.string.msg_about_78) -> {
                    Text("${tr(R.string.app_name)} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                    Text(tr(R.string.msg_sing_box_1_11_15_xray_25_3_6_nopen_source_gpl_111))
                }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun SettingsCategory(title: String, open: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}
@Composable private fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp, end = 12.dp))
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}
