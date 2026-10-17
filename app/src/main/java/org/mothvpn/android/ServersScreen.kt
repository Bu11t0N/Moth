package org.mothvpn.android

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import org.mothvpn.core.Favorites
import org.mothvpn.core.SubscriptionQuota
import org.mothvpn.core.TrafficFormat
import org.mothvpn.core.ServerGroups
import org.mothvpn.core.SubscriptionBook
import org.mothvpn.core.SubscriptionRecord
import org.mothvpn.core.SubscriptionSource

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun ServersScreen(saved: SavedSubscription?, book: SubscriptionBook?, settings: ClientSettings, network: AccessNetwork, busy: Boolean, message: String,
    add: () -> Unit, refresh: () -> Unit, refreshOne: (String) -> Unit, edit: (String, String, String) -> Unit, remove: (String) -> Unit, select: (String, String) -> Unit, probe: (String, List<String>) -> Unit,
    probeGroup: (String, List<String>) -> Unit = { _, _ -> }, cancel: () -> Unit, preferences: (ClientSettings) -> Unit,
    editServer: (String, String) -> Unit = { _, _ -> }) {
    // Probe results arrive in bursts; apply at most one update per 250 ms so the list does not recompose constantly.
    var results by remember { mutableStateOf(Diagnostics.results.value) }
    LaunchedEffect(Unit) { Diagnostics.results.collect { results = it; delay(250) } }
    val probing by Diagnostics.running.collectAsState()
    val tunnel by TunnelStatus.state.collectAsState()
    var kind by rememberSaveable { mutableStateOf(network.kind) }
    var search by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf<String?>(null) }
    var actionsFor by remember { mutableStateOf<String?>(null) }
    var editFor by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<org.mothvpn.core.Profile?>(null) }
    var jsonFor by remember { mutableStateOf<org.mothvpn.core.Profile?>(null) }
    var shareFor by remember { mutableStateOf<org.mothvpn.core.Profile?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Collapsed subscription groups (and favorites), saved across restarts.
    var collapsed by remember { mutableStateOf(CollapsedGroups.load(context)) }
    fun toggleGroup(key: String) { collapsed = if (key in collapsed) collapsed - key else collapsed + key; CollapsedGroups.save(context, collapsed) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(results) { now = System.currentTimeMillis(); while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val locked = busy || probing || tunnel in setOf(TunnelState.RUNNING, TunnelState.STARTING, TunnelState.STOPPING)
    val canCheck = saved != null && !busy && !probing && tunnel !in setOf(TunnelState.STARTING, TunnelState.STOPPING) && network.available && kind == network.kind
    val profiles = saved?.profiles.orEmpty()
    val visible = remember(profiles, results, settings.hideUnavailable, settings.serverSort, settings.favorites, search, kind, now, network) {
        profiles.filter { p ->
            val r = results["$kind:${p.id}"]
            val current = r?.networkHandle == network.handle && kind == network.kind
            val failed = current && r != null && !r.checking && !r.cancelled && r.error != null && r.method == "HTTPS" && now - r.checkedAt in 0 until 900_000
            (!settings.hideUnavailable || !failed) && (p.name.contains(search, true) || p.protocol.contains(search, true))
        }.let { ServerOrder.sort(it, settings.serverSort, { p -> p.name }) { p -> results["$kind:${p.id}"]?.latencyMs } }.let { Favorites.first(it, settings.favorites) { p -> p.id } }
    }
    @Composable fun KindPage(k: String) {
        val visibleK = remember(profiles, results, settings.hideUnavailable, settings.serverSort, settings.favorites, search, k, now, network) {
            profiles.filter { p ->
                val r = results["$k:${p.id}"]
                val current = r?.networkHandle == network.handle && k == network.kind
                val failed = current && r != null && !r.checking && !r.cancelled && r.error != null && r.method == "HTTPS" && now - r.checkedAt in 0 until 900_000
                (!settings.hideUnavailable || !failed) && (p.name.contains(search, true) || p.protocol.contains(search, true))
            }.let { ServerOrder.sort(it, settings.serverSort, { p -> p.name }) { p -> results["$k:${p.id}"]?.latencyMs } }.let { Favorites.first(it, settings.favorites) { p -> p.id } }
        }
        if (visibleK.isEmpty()) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (saved != null) Text(tr(R.string.msg_no_servers_found_59))
            if (saved == null) TextButton(enabled = !busy, onClick = add) { Text(tr(R.string.msg_add_subscription_16)) }
            else TextButton(onClick = { search = ""; preferences(settings.copy(hideUnavailable = false)) }) { Text(tr(R.string.msg_show_all_60)) }
        } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (LocalNewDesign.current) 6.dp else 4.dp), contentPadding = PaddingValues(bottom = if (LocalNewDesign.current) 96.dp else 8.dp)) {
            // Favorites first (from every subscription), then one section per subscription.
            val groups = ServerGroups.of(book ?: SubscriptionBook(emptyList()), visibleK, settings.favorites)
            groups.forEach { group ->
            val groupKey = group.sourceId ?: "favorites"
            val folded = book != null && groupKey in collapsed
            if (book != null) item(key = "header:$groupKey") {
                val source = group.sourceId?.let(book::source)
                val checking = group.profiles.any { results["$k:${it.id}"]?.checking == true }
                if (LocalNewDesign.current) {
                    // This group's servers in the order shown (favorites: the favorite servers).
                    val ids = group.profiles.map { it.id }
                    NewGroupHeader(source, group.profiles.size, folded, checking, probing, canRefresh = source != null && !source.local && !locked, canCheck = canCheck && ids.isNotEmpty(),
                        toggle = { toggleGroup(groupKey) }, refresh = { source?.let { refreshOne(it.id) } }, check = { probeGroup(k, ids) }, cancel = cancel,
                        actions = { if (source != null) actionsFor = source.id })
                } else GroupHeader(source, group.profiles.size, folded, checking, toggle = { toggleGroup(groupKey) }) { if (source != null) actionsFor = source.id }
            }
            if (!folded) items(group.profiles, key = { it.id }) { p ->
                val chosen = p.id == saved?.selection(k)
                val r = results["$k:${p.id}"]?.takeUnless { it.cancelled || it.kind == ProbeErrorKind.CANCELLED } // cancelled checks show no indicator
                val nd = LocalNewDesign.current
                Surface(color = if (nd) (if (chosen) NewDesignRow.selectedColor() else MaterialTheme.colorScheme.surfaceContainerHigh)
                        else if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (nd) NewDesignRow.selectedBorder(chosen) else null,
                    shape = if (nd) MaterialTheme.shapes.medium else RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = if (nd) (if (settings.compactServers) 64.dp else 72.dp) else 0.dp).combinedClickable(onLongClick = { menuFor = p }) { if (!busy && !probing && tunnel != TunnelState.STOPPING) select(p.id, k) }
                        .semantics { contentDescription = p.name + if (chosen) tr(R.string.msg_selected_61) else "" }) {
                    Row(Modifier.padding(horizontal = if (nd) 18.dp else 14.dp, vertical = if (settings.compactServers) 10.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (p.id in settings.favorites) CompositionLocalProvider(LocalContentColor provides (if (isSystemInDarkTheme()) Color(0xFFFF6B8A) else Color(0xFFD81B60))) {
                                    MothIcon("heart", Modifier.padding(end = 6.dp).size(16.dp).semantics { contentDescription = tr(R.string.fav_mark) })
                                }
                                Text(p.name, maxLines = if (settings.compactServers) 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(org.mothvpn.core.ServerLabels.label(p, tr(R.string.server_balancer)) + if (org.mothvpn.core.ProxyProtocols.unencrypted(p)) " · " + tr(R.string.proto_unencrypted) else "", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r != null && !r.checking && r.networkHandle != network.handle) Text(tr(R.string.msg_checked_on_a_different_network_66), style = MaterialTheme.typography.labelSmall)
                        }
                        val dark = isSystemInDarkTheme()
                        val detailed = settings.detailedIcons
                        val red = if (dark) Color(0xFFFF6B6B) else Color(0xFFC62828)
                        when {
                            r?.checking == true -> CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp) // only actively probed rows; queued rows show nothing
                            r?.latencyMs != null && nd -> {
                                if (detailed && r.method == "TCP") DetailBadge(DetailIcon.TCP_OK, tr(R.string.msg_port_is_reachable_vpn_traffic_has_not_been_ch_65), "TCP")
                                PingDot(r.latencyMs, false, Modifier.padding(start = 10.dp))
                            }
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
                            } else if (nd) PingDot(null, true, Modifier.padding(start = 12.dp)) else Text("—", color = red, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
            }
        }
    }
    val nd = LocalNewDesign.current
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // New design: no toolbar icons; refresh and check live in each group header, Add is the floating button.
        if (nd) Text(tr(R.string.msg_servers_27), style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.msg_servers_27), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ActionIcon("add", tr(R.string.msg_add_subscription_16), !locked, add)
            ActionIcon("refresh", if ((book?.sources?.count { !it.local } ?: 0) > 1) tr(R.string.sub_update_all) else tr(R.string.msg_update_subscription_48), book?.sources?.any { !it.local } == true && !locked, refresh)
            ActionIcon(if (probing) "stop" else "check", if (probing) tr(R.string.msg_cancel_checks_49) else tr(R.string.msg_check_servers_50), probing || canCheck) { if (probing) cancel() else probe(kind, visible.map { it.id }) } // snapshot of the displayed order at the moment of the tap
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
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val pager = rememberPagerState(initialPage = if (kind == "wifi") 0 else 1) { 2 }
        LaunchedEffect(pager.currentPage) { kind = if (pager.currentPage == 0) "wifi" else "mobile" }
        LaunchedEffect(kind) { val i = if (kind == "wifi") 0 else 1; if (pager.currentPage != i) pager.animateScrollToPage(i) }
        // Fast snap: while a page is still settling the pager owns the touch and the list cannot scroll yet.
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top,
            flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(stiffness = Spring.StiffnessHigh))) { KindPage(if (it == 0) "wifi" else "mobile") }
    }
    // Thumb reach: bottom-right. Adding is not possible while connected or checking (same rule as the classic toolbar button), so the button dims then.
    if (nd) FloatingActionButton(onClick = { if (!locked) add() }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.large,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp).graphicsLayer { alpha = if (locked) 0.45f else 1f }.semantics { contentDescription = tr(R.string.msg_add_subscription_16) }) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(26.dp))
    }
    }
    jsonFor?.let { p -> ServerJsonEditor(p, cancel = { jsonFor = null }) { outbound -> jsonFor = null; editServer(p.id, outbound) } }
    menuFor?.let { p ->
        AlertDialog(onDismissRequest = { menuFor = null }, title = { Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
            Column {
                TextButton(onClick = { menuFor = null; shareFor = p }) { Text(tr(R.string.server_share)) }
                val favorite = p.id in settings.favorites
                TextButton(onClick = { menuFor = null; preferences(settings.copy(favorites = Favorites.toggle(settings.favorites, p.id))) }) { Text(tr(if (favorite) R.string.fav_remove else R.string.fav_add)) }
                if (settings.editServers) TextButton(enabled = !busy, onClick = { menuFor = null; jsonFor = p }) { Text(tr(R.string.edit_json)) }
            }
        }, confirmButton = { TextButton(onClick = { menuFor = null }) { Text(tr(R.string.msg_cancel_32)) } })
    }
    shareFor?.let { p -> ShareServerDialog(p) { shareFor = null } }
    actionsFor?.let { id -> book?.source(id) }?.let { source ->
        AlertDialog(onDismissRequest = { actionsFor = null }, title = { Text(source.title(), maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
            Column {
                TextButton(enabled = !locked, onClick = { actionsFor = null; editFor = source.id }) { Text(tr(R.string.sub_edit)) }
                if (!source.local) TextButton(enabled = !locked, onClick = { actionsFor = null; refreshOne(source.id) }) { Text(tr(R.string.sub_update)) }
                // Checks only this subscription's servers, in the order shown; other groups keep their results.
                val ids = visible.filter { it.id in source.profiles.map { p -> p.id }.toSet() }.map { it.id }.ifEmpty { source.profiles.map { it.id } }
                TextButton(enabled = canCheck, onClick = { actionsFor = null; probeGroup(kind, ids) }) { Text(tr(R.string.sub_check)) }
                TextButton(enabled = !locked, onClick = { actionsFor = null; delete = source.id }) { Text(tr(R.string.sub_delete)) }
                if (locked) Text(tr(R.string.msg_disconnect_and_finish_checks_to_change_subscr_104), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { actionsFor = null }) { Text(tr(R.string.msg_cancel_32)) } })
    }
    editFor?.let { id -> book?.source(id) }?.let { source ->
        var name by remember(source.id) { mutableStateOf(source.name.ifBlank { source.providerName }) }
        var link by remember(source.id) { mutableStateOf(if (source.local) "" else source.url) }
        val nameOk = org.mothvpn.core.SubscriptionSource.validName(name.trim())
        val linkOk = source.local || org.mothvpn.core.ImportInput.classify(link) is org.mothvpn.core.ImportInput.Kind.Subscription
        AlertDialog(onDismissRequest = { editFor = null }, title = { Text(tr(R.string.sub_edit_title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { if (it.length <= org.mothvpn.core.SubscriptionSource.MAX_NAME) name = it.replace("\n", " ") }, label = { Text(tr(R.string.sub_name)) }, singleLine = true, isError = !nameOk, modifier = Modifier.fillMaxWidth())
                if (!source.local) OutlinedTextField(link, { link = it }, label = { Text(tr(R.string.msg_subscription_link_30)) }, singleLine = true, isError = !linkOk,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrect = false), modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = nameOk && linkOk && !locked, onClick = { editFor = null; edit(source.id, name, if (source.local) source.url else link) }) { Text(tr(R.string.sub_save)) } },
            dismissButton = { TextButton(onClick = { editFor = null }) { Text(tr(R.string.msg_cancel_32)) } })
    }
    delete?.let { id -> book?.source(id) }?.let { source ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text(tr(R.string.sub_delete_title, source.title())) },
            text = { Text(if ((book?.sources?.size ?: 0) > 1) tr(R.string.sub_delete_text) else tr(R.string.msg_the_subscription_link_will_need_to_be_added_a_68)) },
            confirmButton = { TextButton(enabled = !locked, onClick = { delete = null; remove(source.id) }) { Text(tr(R.string.msg_remove_69)) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text(tr(R.string.msg_cancel_32)) } })
    }
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

/** Section title in the server list: a subscription (name, traffic and end date, actions button) or the favorites. */
@Composable private fun GroupHeader(source: SubscriptionSource?, count: Int, collapsed: Boolean, checking: Boolean, toggle: () -> Unit, actions: () -> Unit) {
    // Tap the header (or the arrow) to collapse or expand the group; a collapsed group keeps its name, count, traffic and end date.
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable(onClick = toggle).semantics { contentDescription = tr(if (collapsed) R.string.group_expand else R.string.group_collapse) + ": " + (source?.title() ?: tr(R.string.fav_group)) },
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (collapsed) "▸" else "▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
        Column(Modifier.weight(1f)) {
            Text((source?.title() ?: tr(R.string.fav_group)) + " · " + count, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (source != null) SubscriptionQuotaLine(SubscriptionRecord(source.url, source.profiles, "", usedBytes = source.usedBytes, totalBytes = source.totalBytes, expires = source.expires))
        }
        if (checking) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(16.dp), strokeWidth = 2.dp)
        if (source != null) IconButton(onClick = actions, modifier = Modifier.size(36.dp).semantics { contentDescription = tr(R.string.sub_actions) }) { MothIcon("menu", Modifier.size(20.dp)) }
    }
}

/**
 * Group header of the new design: one card with an animated chevron, the subscription name, the server count and quota, and 48 dp icon buttons
 * to update this subscription, check its servers (stop while checking) and open the subscription menu.
 */
@Composable private fun NewGroupHeader(source: SubscriptionSource?, count: Int, collapsed: Boolean, checking: Boolean, probing: Boolean, canRefresh: Boolean, canCheck: Boolean,
    toggle: () -> Unit, refresh: () -> Unit, check: () -> Unit, cancel: () -> Unit, actions: () -> Unit) {
    val title = source?.title() ?: tr(R.string.fav_group)
    val turn by androidx.compose.animation.core.animateFloatAsState(if (collapsed) -90f else 0f, label = "chevron")
    Surface(onClick = toggle, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 64.dp).semantics { contentDescription = tr(if (collapsed) R.string.group_expand else R.string.group_collapse) + ": " + title }) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = turn })
            }
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr(R.string.nd_group_servers, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (source != null) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    SubscriptionQuotaLine(SubscriptionRecord(source.url, source.profiles, "", usedBytes = source.usedBytes, totalBytes = source.totalBytes, expires = source.expires))
                }
            }
            if (source != null && !source.local) IconButton(onClick = refresh, enabled = canRefresh, modifier = Modifier.size(48.dp).semantics { contentDescription = tr(R.string.msg_update_subscription_48) }) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            if (checking) IconButton(onClick = cancel, modifier = Modifier.size(48.dp).semantics { contentDescription = tr(R.string.msg_cancel_checks_49) }) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                    Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            } else IconButton(onClick = check, enabled = canCheck && !probing, modifier = Modifier.size(48.dp).semantics { contentDescription = tr(R.string.msg_check_servers_50) }) {
                Icon(androidx.compose.material.icons.Icons.Rounded.NetworkPing, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            if (source != null) IconButton(onClick = actions, modifier = Modifier.size(48.dp).semantics { contentDescription = tr(R.string.sub_actions) }) {
                Icon(androidx.compose.material.icons.Icons.Rounded.MoreVert, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Traffic left and expiry date from the provider's subscription-userinfo header, when it sent them. */
@Composable fun SubscriptionQuotaLine(saved: org.mothvpn.core.SubscriptionRecord) {
    val left = SubscriptionQuota.remaining(saved.usedBytes, saved.totalBytes)
    val expiry = SubscriptionQuota.expiry(saved.expires)
    if (left == null && expiry == null) return
    val locale = AppLanguage.context().resources.configuration.locales[0]
    val units = tr(R.string.notif_units).split("|")
    val parts = listOfNotNull(
        left?.let { tr(R.string.sub_left, TrafficFormat.bytes(it, units, locale), TrafficFormat.bytes(saved.totalBytes, units, locale)) },
        expiry?.let { tr(R.string.sub_expires, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(it * 1000))) })
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}

/** Keys of the collapsed groups in the server list (subscription ids, "favorites"); kept in app preferences. */
object CollapsedGroups {
    private const val FILE = "server_list"; private const val KEY = "collapsed_groups"
    fun load(context: android.content.Context): Set<String> = runCatching { context.getSharedPreferences(FILE, 0).getStringSet(KEY, emptySet())!!.toSet() }.getOrDefault(emptySet())
    fun save(context: android.content.Context, keys: Set<String>) { runCatching { context.getSharedPreferences(FILE, 0).edit().putStringSet(KEY, keys).apply() } }
}
