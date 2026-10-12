package org.mothvpn.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.mothvpn.core.LatencyBand
import org.mothvpn.core.ProbeErrorKind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mothvpn.core.ClientSettings
import org.mothvpn.core.ServerOrder

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun ServersScreen(saved: SavedSubscription?, settings: ClientSettings, network: AccessNetwork, busy: Boolean, message: String,
    add: () -> Unit, refresh: () -> Unit, remove: () -> Unit, select: (String, String) -> Unit, probe: (String, List<String>) -> Unit,
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
    val visible = remember(profiles, results, settings.hideUnavailable, settings.serverSort, search, kind, now, network) {
        profiles.filter { p ->
            val r = results["$kind:${p.id}"]
            val current = r?.networkHandle == network.handle && kind == network.kind
            val failed = current && r != null && !r.checking && !r.cancelled && r.error != null && r.method == "HTTPS" && now - r.checkedAt in 0 until 900_000
            (!settings.hideUnavailable || !failed) && (p.name.contains(search, true) || p.protocol.contains(search, true))
        }.let { ServerOrder.sort(it, settings.serverSort, { p -> p.name }) { p -> results["$kind:${p.id}"]?.latencyMs } }
    }
    @Composable fun KindPage(k: String) {
        val visibleK = remember(profiles, results, settings.hideUnavailable, settings.serverSort, search, k, now, network) {
            profiles.filter { p ->
                val r = results["$k:${p.id}"]
                val current = r?.networkHandle == network.handle && k == network.kind
                val failed = current && r != null && !r.checking && !r.cancelled && r.error != null && r.method == "HTTPS" && now - r.checkedAt in 0 until 900_000
                (!settings.hideUnavailable || !failed) && (p.name.contains(search, true) || p.protocol.contains(search, true))
            }.let { ServerOrder.sort(it, settings.serverSort, { p -> p.name }) { p -> results["$k:${p.id}"]?.latencyMs } }
        }
        if (visibleK.isEmpty()) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (saved != null) Text(tr(R.string.msg_no_servers_found_59))
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
                            Text(p.protocol.uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r != null && !r.checking && r.networkHandle != network.handle) Text(tr(R.string.msg_checked_on_a_different_network_66), style = MaterialTheme.typography.labelSmall)
                        }
                        val dark = isSystemInDarkTheme()
                        val detailed = settings.detailedIcons
                        val red = if (dark) Color(0xFFFF6B6B) else Color(0xFFC62828)
                        when {
                            r?.checking == true -> CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp) // only actively probed rows; queued rows show nothing
                            r?.latencyMs != null -> {
                                if (detailed && r.method == "TCP") DetailBadge(DetailIcon.TCP_OK, tr(R.string.msg_port_is_reachable_vpn_traffic_has_not_been_ch_65), "TCP")
                                Text("${r.latencyMs} ms", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp),
                                    color = when (LatencyBand.of(r.latencyMs)) {
                                        LatencyBand.GOOD -> if (dark) Color(0xFF66DD88) else Color(0xFF1B7F3A)
                                        LatencyBand.MEDIUM -> if (dark) Color(0xFFFFC247) else Color(0xFFB36B00)
                                        LatencyBand.POOR -> red
                                    })
                            }
                            r?.error != null -> if (detailed) {
                                val kind = r.kind ?: ProbeErrorKind.FAILED
                                val text = when (kind) {
                                    ProbeErrorKind.DNS_TIMEOUT, ProbeErrorKind.DNS_UNRESOLVED -> Diagnostics.kindText(ProbeErrorKind.DNS_TIMEOUT) + "\n" + Diagnostics.kindText(ProbeErrorKind.DNS_UNRESOLVED)
                                    else -> Diagnostics.kindText(kind)
                                }
                                DetailBadge(DetailIcon.of(kind), text, null)
                            } else Text("—", color = red, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 12.dp))
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
            ActionIcon(if (probing) "stop" else "check", if (probing) tr(R.string.msg_cancel_checks_49) else tr(R.string.msg_check_servers_50), probing || canCheck) { if (probing) cancel() else probe(kind, visible.map { it.id }) } // snapshot of the displayed order at the moment of the tap
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
        // Fast snap: while a page is still settling the pager owns the touch and the list cannot scroll yet.
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top,
            flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(stiffness = Spring.StiffnessHigh))) { KindPage(if (it == 0) "wifi" else "mobile") }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(tr(R.string.msg_remove_subscription_67)) }, text = { Text(tr(R.string.msg_the_subscription_link_will_need_to_be_added_a_68)) }, confirmButton = { TextButton(enabled = !locked, onClick = { delete = false; remove() }) { Text(tr(R.string.msg_remove_69)) } }, dismissButton = { TextButton(onClick = { delete = false }) { Text(tr(R.string.msg_cancel_32)) } })
}

@Composable private fun ActionIcon(icon: String, label: String, enabled: Boolean, action: () -> Unit) {
    IconButton(enabled = enabled, onClick = action, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) { MothIcon(icon) }
}

private enum class DetailIcon {
    TIMEOUT, DNS, PORT, TLS, FAILED, CANCELLED, TCP_OK, INFO;
    companion object {
        fun of(k: ProbeErrorKind) = when (k) {
            ProbeErrorKind.TIMEOUT -> TIMEOUT
            ProbeErrorKind.DNS_TIMEOUT, ProbeErrorKind.DNS_UNRESOLVED -> DNS
            ProbeErrorKind.PORT_CLOSED -> PORT
            ProbeErrorKind.TLS -> TLS
            ProbeErrorKind.FAILED -> FAILED
            ProbeErrorKind.CANCELLED -> CANCELLED
            ProbeErrorKind.UDP_PROTOCOL -> INFO
        }
    }
}

/** Small gray icon (optionally with a short label); a tap shows [text] in a popup. Only used when "Show detailed icons" is on. */
@Composable private fun DetailBadge(icon: DetailIcon, text: String, label: String?) {
    var open by remember { mutableStateOf(false) }
    val gray = Color.Gray.copy(alpha = if (icon == DetailIcon.CANCELLED) 0.55f else 1f)
    Row(Modifier.padding(start = 12.dp).clickable { open = true }.semantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width; val h = size.height; val sw = 1.8.dp.toPx(); val st = Stroke(sw, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(gray, Offset(w * x, h * y), Offset(w * x2, h * y2), sw, StrokeCap.Round)
            fun ring() = drawCircle(gray, w * .42f, Offset(w / 2, h / 2), style = st)
            when (icon) {
                DetailIcon.TIMEOUT -> { ring(); line(.5f, .25f, .5f, .5f); line(.5f, .5f, .68f, .62f) }
                DetailIcon.DNS -> { ring(); drawOval(gray, Offset(w * .32f, h * .08f), androidx.compose.ui.geometry.Size(w * .36f, h * .84f), style = st); line(.08f, .5f, .92f, .5f); line(.15f, .9f, .85f, .1f) }
                DetailIcon.PORT -> { ring(); line(.2f, .8f, .8f, .2f) }
                DetailIcon.TLS -> { drawRect(gray, Offset(w * .2f, h * .45f), androidx.compose.ui.geometry.Size(w * .6f, h * .45f), style = st); drawArc(gray, 180f, 180f, false, Offset(w * .3f, h * .12f), androidx.compose.ui.geometry.Size(w * .4f, h * .66f), style = st); line(.5f, .56f, .5f, .7f); drawCircle(gray, sw * .6f, Offset(w * .5f, h * .8f)) }
                DetailIcon.FAILED -> { ring(); line(.5f, .26f, .5f, .56f); drawCircle(gray, sw * .6f, Offset(w * .5f, h * .72f)) }
                DetailIcon.CANCELLED -> { ring(); line(.34f, .34f, .66f, .66f); line(.66f, .34f, .34f, .66f) }
                DetailIcon.TCP_OK -> { ring(); line(.3f, .52f, .45f, .67f); line(.45f, .67f, .72f, .35f) }
                DetailIcon.INFO -> { ring(); line(.5f, .45f, .5f, .74f); drawCircle(gray, sw * .6f, Offset(w * .5f, h * .28f)) }
            }
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = gray, modifier = Modifier.padding(start = 2.dp))
    }
    if (open) androidx.compose.ui.window.Popup(alignment = Alignment.BottomEnd, onDismissRequest = { open = false }) {
        Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 6.dp, shadowElevation = 4.dp, modifier = Modifier.clickable { open = false }) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 240.dp))
        }
    }
}
