package org.routevpn.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.routevpn.core.LatencyBand
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.routevpn.core.ClientSettings

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun ServersScreen(saved: SavedSubscription?, settings: ClientSettings, network: AccessNetwork, busy: Boolean, message: String,
    add: () -> Unit, refresh: () -> Unit, remove: () -> Unit, select: (String, String) -> Unit, probe: (String) -> Unit,
    speedTest: (String) -> Unit, cancel: () -> Unit, preferences: (ClientSettings) -> Unit) {
    // Probe results arrive in bursts; apply at most one update per 250 ms so the list does not recompose constantly.
    var results by remember { mutableStateOf(Diagnostics.results.value) }
    LaunchedEffect(Unit) { Diagnostics.results.collect { results = it; delay(250) } }
    val probing by Diagnostics.running.collectAsState()
    val speed by Diagnostics.speed.collectAsState()
    val tunnel by TunnelStatus.state.collectAsState()
    var kind by rememberSaveable { mutableStateOf(network.kind) }
    var search by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(results) { now = System.currentTimeMillis(); while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val locked = busy || probing || tunnel in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.STOPPING)
    val canCheck = saved != null && !busy && !probing && tunnel !in setOf(TunnelState.STARTING, TunnelState.STOPPING) && network.available && kind == network.kind
    val profiles = saved?.profiles.orEmpty()
    val visible = remember(profiles, results, settings.hideUnavailable, settings.sortByLatency, search, kind, now, network) {
        profiles.filter { p ->
            val r = results["$kind:${p.id}"]
            val current = r?.networkHandle == network.handle && kind == network.kind
            val failed = current && r != null && !r.checking && !r.cancelled && r.error != null && r.method == "HTTPS" && now - r.checkedAt in 0 until 900_000
            (!settings.hideUnavailable || !failed) && (p.name.contains(search, true) || p.protocol.contains(search, true))
        }.let { if (settings.sortByLatency) it.sortedBy { p -> results["$kind:${p.id}"]?.latencyMs ?: Long.MAX_VALUE } else it }
    }
    @Composable fun KindPage(k: String) {
        val visibleK = remember(profiles, results, settings.hideUnavailable, settings.sortByLatency, search, k, now, network) {
            profiles.filter { p ->
                val r = results["$k:${p.id}"]
                val current = r?.networkHandle == network.handle && k == network.kind
                val failed = current && r != null && !r.checking && !r.cancelled && r.error != null && r.method == "HTTPS" && now - r.checkedAt in 0 until 900_000
                (!settings.hideUnavailable || !failed) && (p.name.contains(search, true) || p.protocol.contains(search, true))
            }.let { if (settings.sortByLatency) it.sortedBy { p -> results["$k:${p.id}"]?.latencyMs ?: Long.MAX_VALUE } else it }
        }
        if (visibleK.isEmpty()) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (saved == null) tr(R.string.msg_add_a_subscription_58) else tr(R.string.msg_no_servers_found_59))
            if (saved == null) TextButton(enabled = !busy, onClick = add) { Text(tr(R.string.msg_add_subscription_16)) }
            else TextButton(onClick = { search = ""; preferences(settings.copy(hideUnavailable = false)) }) { Text(tr(R.string.msg_show_all_60)) }
        } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(visibleK, key = { it.id }) { p ->
                val chosen = p.id == saved?.selection(k)
                val r = results["$k:${p.id}"]
                Surface(color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) { select(p.id, k) }
                        .semantics { contentDescription = p.name + if (chosen) tr(R.string.msg_selected_61) else "" }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = if (settings.compactServers) 10.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(p.name, maxLines = if (settings.compactServers) 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(p.protocol.uppercase() + if (r?.latencyMs != null && r.method == "TCP") " · TCP" else "", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r?.latencyMs != null && r.method == "TCP") Text(tr(R.string.msg_port_is_reachable_vpn_traffic_has_not_been_ch_65), style = MaterialTheme.typography.labelSmall)
                            if (r != null && !r.checking && r.networkHandle != network.handle) Text(tr(R.string.msg_checked_on_a_different_network_66), style = MaterialTheme.typography.labelSmall)
                        }
                        val dark = isSystemInDarkTheme()
                        when {
                            r?.checking == true -> Text(tr(R.string.msg_checking_62), style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.padding(start = 12.dp))
                            r?.latencyMs != null -> Text("${r.latencyMs} ms", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp),
                                color = when (LatencyBand.of(r.latencyMs)) {
                                    LatencyBand.GOOD -> if (dark) Color(0xFF66DD88) else Color(0xFF1B7F3A)
                                    LatencyBand.MEDIUM -> if (dark) Color(0xFFFFC247) else Color(0xFFB36B00)
                                    LatencyBand.POOR -> if (dark) Color(0xFFFF6B6B) else Color(0xFFC62828)
                                })
                            r?.error != null -> Text(localizedError(r.error), style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp).widthIn(max = 120.dp))
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.msg_servers_27), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ActionIcon("add", if (saved == null) tr(R.string.msg_add_subscription_16) else tr(R.string.msg_replace_subscription_47), !locked, add)
            ActionIcon("refresh", tr(R.string.msg_update_subscription_48), saved != null && !locked, refresh)
            ActionIcon(if (probing) "stop" else "check", if (probing) tr(R.string.msg_cancel_checks_49) else tr(R.string.msg_check_servers_50), probing || canCheck) { if (probing) cancel() else probe(kind) }
            ActionIcon("speed", tr(R.string.msg_measure_speed_download_up_to_1_mib_51), canCheck) { speedTest(kind) }
            ActionIcon("delete", tr(R.string.msg_remove_subscription_52), saved != null && !locked) { delete = true }
        }
        TabRow(selectedTabIndex = if (kind == "wifi") 0 else 1) {
            Tab(selected = kind == "wifi", onClick = { kind = "wifi" }, text = { Text("Wi-Fi") })
            Tab(selected = kind == "mobile", onClick = { kind = "mobile" }, text = { Text(tr(R.string.msg_mobile_data_21)) })
        }
        if (!network.available || kind != network.kind) Text(if (!network.available) tr(R.string.msg_no_network_available_for_this_check_53) else if (kind == "mobile") tr(R.string.msg_to_check_mobile_data_enable_it_and_turn_off_w_54) else tr(R.string.msg_connect_to_wi_fi_to_run_this_check_55), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${visible.size} / ${profiles.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            FilterChip(selected = settings.hideUnavailable, onClick = { preferences(settings.copy(hideUnavailable = !settings.hideUnavailable)) }, label = { Text(tr(R.string.msg_hide_unavailable_56)) })
        }
        OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text(tr(R.string.msg_search_servers_57)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        if (busy || probing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (speed.isNotBlank()) Text(speed, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val pager = rememberPagerState(initialPage = if (kind == "wifi") 0 else 1) { 2 }
        LaunchedEffect(pager.currentPage) { kind = if (pager.currentPage == 0) "wifi" else "mobile" }
        LaunchedEffect(kind) { val i = if (kind == "wifi") 0 else 1; if (pager.currentPage != i) pager.animateScrollToPage(i) }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) { KindPage(if (it == 0) "wifi" else "mobile") }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(tr(R.string.msg_remove_subscription_67)) }, text = { Text(tr(R.string.msg_the_subscription_link_will_need_to_be_added_a_68)) }, confirmButton = { TextButton(enabled = !locked, onClick = { delete = false; remove() }) { Text(tr(R.string.msg_remove_69)) } }, dismissButton = { TextButton(onClick = { delete = false }) { Text(tr(R.string.msg_cancel_32)) } })
}

@Composable private fun ActionIcon(icon: String, label: String, enabled: Boolean, action: () -> Unit) {
    IconButton(enabled = enabled, onClick = action, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) { MothIcon(icon) }
}
