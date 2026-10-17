package org.mothvpn.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI
import org.mothvpn.core.VersionLabel
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer

/** No server list or probe-result subscription here: checks cannot redraw the home screen. */
@Composable fun HomeScreen(saved: SavedSubscription?, book: org.mothvpn.core.SubscriptionBook?, network: AccessNetwork, busy: Boolean, message: String,
    add: () -> Unit, connect: () -> Unit, servers: () -> Unit, updateAvailable: Boolean = false, openUpdates: () -> Unit = {}) {
    val tunnel by TunnelStatus.state.collectAsState()
    val health by Diagnostics.health.collectAsState()
    val probing by Diagnostics.running.collectAsState()
    val active by TunnelStatus.activeProfile.collectAsState()
    val selected = remember(saved, network.kind, active, tunnel) {
        saved?.profiles?.firstOrNull { it.id == if (tunnel in setOf(TunnelState.RUNNING, TunnelState.BLOCKED) && active.isNotEmpty()) active else saved.selection(network.kind) }
    }
    val blockingTun by TunnelStatus.blocking.collectAsState()
    // Kill switch holding traffic (also while it retries the connection): the button disconnects and stops blocking.
    val offline by TunnelStatus.offline.collectAsState()
    val blocked = TunnelStatus.trafficBlocked(tunnel, blockingTun, offline)
    val running = tunnel in setOf(TunnelState.RUNNING, TunnelState.BLOCKED) || blocked
    val transition = tunnel in setOf(TunnelState.STARTING, TunnelState.STOPPING) && !blocked
    // Red = not connected, green = connected; in-between states keep the neutral disabled look of the button.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val connectedColor = if (dark) Color(0xFF2E9E57) else Color(0xFF1B7F3A)
    val disconnectedColor = if (dark) Color(0xFFD64545) else Color(0xFFC62828)
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.Bottom) {
            // Same text as Settings → About without the build number: "<name> <version>".
            val about = VersionLabel.about(tr(R.string.app_name), BuildConfig.VERSION_NAME)
            val name = tr(R.string.app_name)
            Text(name, style = MaterialTheme.typography.headlineLarge)
            Text(about.removePrefix(name).trim(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            // Green download arrow, only while the launch-time check found an update; opens the Updates screen.
            if (updateAvailable) IconButton(onClick = openUpdates, modifier = Modifier.semantics { contentDescription = tr(R.string.update_available_arrow) }) {
                CompositionLocalProvider(LocalContentColor provides connectedColor) { MothIcon("update", Modifier.size(28.dp)) }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = connect, enabled = if (blocked) true else !busy && (!probing || running) && !transition && selected != null,
            shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(148.dp)
                .semantics { contentDescription = if (running) tr(R.string.msg_disconnect_vpn_8) else tr(R.string.msg_connect_vpn_9) },
            colors = ButtonDefaults.buttonColors(containerColor = if (running && !blocked) connectedColor else disconnectedColor, contentColor = androidx.compose.ui.graphics.Color.White)) {
            if (transition) CircularProgressIndicator(Modifier.size(44.dp), strokeWidth = 3.dp)
            else MothIcon("power", Modifier.size(56.dp))
        }
        if (blocked) {
            Text(tr(R.string.home_disconnect), style = MaterialTheme.typography.headlineSmall)
            Text(tr(R.string.msg_blocked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } else Text(when (tunnel) { TunnelState.OFF -> tr(R.string.msg_connect_10); TunnelState.RUNNING -> if (TunnelStatus.waitingForNetwork(tunnel, offline)) tr(R.string.tile_reconnecting) else tr(R.string.msg_vpn_is_on_11); TunnelState.STARTING -> tr(R.string.msg_connecting_12); TunnelState.STOPPING -> tr(R.string.msg_disconnecting_13); TunnelState.ERROR -> tr(R.string.msg_could_not_connect_14); TunnelState.BLOCKED -> tr(R.string.msg_blocked) }, style = MaterialTheme.typography.headlineSmall)
        if (tunnel == TunnelState.RUNNING) Text(if (health.checking) tr(R.string.msg_checking_internet_access_15) else localizedError(health.message), style = MaterialTheme.typography.bodyMedium)
        if (selected != null) TextButton(onClick = servers) { Text(selected.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (saved == null) Button(enabled = !busy, onClick = add) { Text(tr(R.string.msg_add_subscription_16)) }
        Spacer(Modifier.weight(1f))
        // The card shows the subscription of the selected server (its own link, traffic and end date).
        val source = remember(book, selected) { selected?.let { p -> book?.sourceOf(p.id) } ?: book?.sources?.firstOrNull() }
        if (saved != null && source != null) HomeSubscriptionCard(book, source, network)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
    }
}

/** The subscription of the selected server: its name, link, traffic, end date and the current network (shared by both designs). */
@Composable fun HomeSubscriptionCard(book: org.mothvpn.core.SubscriptionBook?, source: org.mothvpn.core.SubscriptionSource, network: AccessNetwork, modifier: Modifier = Modifier.fillMaxWidth()) {
    Card(modifier) {
        val saved = source
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val host = remember(saved.url) { runCatching { URI(saved.url).host }.getOrNull().orEmpty() }
            val title = saved.name.ifBlank { saved.providerName }
            if (title.isNotBlank() && title != host) Text(title, style = MaterialTheme.typography.titleMedium)
            if ((book?.sources?.size ?: 0) > 1) Text(tr(R.string.sub_count, book?.sources?.size ?: 0), style = MaterialTheme.typography.labelMedium)
            var showLink by remember { mutableStateOf(false) }
            val clipboard = LocalClipboardManager.current
            var showAll by remember { mutableStateOf(false) }
            val all = book?.sources.orEmpty()
            // "<host> · N servers": with several subscriptions it opens the list of all of them (not only the selected server's one).
            if (all.size > 1) TextButton(onClick = { showAll = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 32.dp)
                .semantics { contentDescription = tr(R.string.sub_all_show) }) {
                Text(if (saved.local) tr(R.string.sub_servers_count, saved.profiles.size) else tr(R.string.msg_host_saved_profiles_size_servers_17, host, saved.profiles.size), style = MaterialTheme.typography.bodySmall)
            }
            else if (!saved.local) TextButton(onClick = { showLink = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 32.dp)
                .semantics { contentDescription = tr(R.string.sub_link_show) }) {
                Text(tr(R.string.msg_host_saved_profiles_size_servers_17, host, saved.profiles.size), style = MaterialTheme.typography.bodySmall)
            }
            else Text(tr(R.string.sub_servers_count, saved.profiles.size), style = MaterialTheme.typography.bodySmall)
            if (showAll) SubscriptionsDialog(all, { showAll = false }) { url -> clipboard.setText(AnnotatedString(url)) }
            // The link is the user's own; it is shown in full here and is never written to the log.
            if (showLink) AlertDialog(onDismissRequest = { showLink = false }, title = { Text(tr(R.string.sub_link_title)) },
                text = { SelectionContainer { Text(saved.url) } },
                confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(saved.url)); showLink = false }) { Text(tr(R.string.sub_link_copy)) } },
                dismissButton = { TextButton(onClick = { showLink = false }) { Text(tr(R.string.sub_link_close)) } })
            if (saved.totalBytes > 0) Text(tr(R.string.msg_traffic_1f_1f_gib_18, saved.usedBytes / 1073741824.0, saved.totalBytes / 1073741824.0), style = MaterialTheme.typography.bodySmall)
            if (saved.expires in 1..253402300799L) Text(tr(R.string.msg_subscription_expires_on_19) + java.text.DateFormat.getDateInstance(java.text.DateFormat.DEFAULT, AppLanguage.context().resources.configuration.locales[0]).format(java.util.Date(saved.expires * 1000)), style = MaterialTheme.typography.bodySmall)
            Text(if (!network.available) tr(R.string.msg_no_network_20) else if (network.kind == "mobile") tr(R.string.msg_mobile_data_21) else "Wi-Fi", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Every subscription with its servers, traffic, end date and last update; each link can be copied (it is not printed here). */
@Composable private fun SubscriptionsDialog(sources: List<org.mothvpn.core.SubscriptionSource>, close: () -> Unit, copy: (String) -> Unit) {
    val locale = AppLanguage.context().resources.configuration.locales[0]
    val dates = java.text.DateFormat.getDateInstance(java.text.DateFormat.DEFAULT, locale)
    AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.sub_all_title)) }, text = {
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sources.size) { i ->
                val s = sources[i]
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.title().ifBlank { tr(R.string.import_local_name) }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(tr(R.string.sub_servers_count, s.profiles.size), style = MaterialTheme.typography.bodySmall)
                    if (s.totalBytes > 0) Text(tr(R.string.msg_traffic_1f_1f_gib_18, s.usedBytes / 1073741824.0, s.totalBytes / 1073741824.0), style = MaterialTheme.typography.bodySmall)
                    if (s.expires in 1..253402300799L) Text(tr(R.string.msg_subscription_expires_on_19) + dates.format(java.util.Date(s.expires * 1000)), style = MaterialTheme.typography.bodySmall)
                    if (s.updatedAt > 0) Text(tr(R.string.sub_updated_on, dates.format(java.util.Date(s.updatedAt))), style = MaterialTheme.typography.bodySmall)
                    if (!s.local) TextButton(onClick = { copy(s.url) }, contentPadding = PaddingValues(0.dp)) { Text(tr(R.string.sub_link_copy_link)) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(tr(R.string.sub_link_close)) } })
}

@Composable fun MothIcon(name: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val s = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x,h*y), Offset(w*x2,h*y2), s.width, StrokeCap.Round)
        when (name) {
            "heart" -> { val p = Path().apply { moveTo(w*.5f,h*.86f); cubicTo(w*.1f,h*.58f,w*.02f,h*.32f,w*.2f,h*.18f); cubicTo(w*.34f,h*.07f,w*.47f,h*.16f,w*.5f,h*.28f); cubicTo(w*.53f,h*.16f,w*.66f,h*.07f,w*.8f,h*.18f); cubicTo(w*.98f,h*.32f,w*.9f,h*.58f,w*.5f,h*.86f); close() }; drawPath(p, color) }
            "power" -> { drawArc(color, -45f, 270f, false, Offset(w*.15f,h*.15f), size*.7f, style=s); line(.5f,.05f,.5f,.45f) }
            "home" -> { val p=Path().apply { moveTo(w*.12f,h*.45f); lineTo(w*.5f,h*.12f); lineTo(w*.88f,h*.45f); moveTo(w*.23f,h*.4f); lineTo(w*.23f,h*.88f); lineTo(w*.77f,h*.88f); lineTo(w*.77f,h*.4f) }; drawPath(p,color,style=s) }
            "settings" -> { for (i in 0..2) { val y=.23f+i*.27f; line(.1f,y,.9f,y); drawCircle(color,w*.09f,Offset(w*(if(i==1).65f else .35f),h*y)) } }
            "refresh" -> { drawArc(color,40f,290f,false,Offset(w*.16f,h*.16f),size*.68f,style=s); line(.84f,.16f,.84f,.42f); line(.84f,.42f,.6f,.42f) }
            "delete" -> { line(.15f,.25f,.85f,.25f); line(.4f,.1f,.6f,.1f); line(.25f,.3f,.3f,.88f); line(.3f,.88f,.7f,.88f); line(.7f,.88f,.75f,.3f); line(.43f,.42f,.43f,.72f); line(.57f,.42f,.57f,.72f) }
            "check" -> { line(.12f,.6f,.3f,.6f); line(.3f,.6f,.42f,.25f); line(.42f,.25f,.58f,.8f); line(.58f,.8f,.72f,.45f); line(.72f,.45f,.9f,.45f) }
            "speed" -> { drawArc(color,180f,180f,false,Offset(w*.12f,h*.2f),androidx.compose.ui.geometry.Size(w*.76f,h*.76f),style=s); line(.5f,.62f,.75f,.32f); line(.15f,.68f,.85f,.68f) }
            "update" -> { line(.5f,.1f,.5f,.68f); line(.5f,.68f,.25f,.43f); line(.5f,.68f,.75f,.43f); line(.18f,.9f,.82f,.9f) }
            "add" -> { line(.5f,.15f,.5f,.85f); line(.15f,.5f,.85f,.5f) }
            "eye" -> { val p = Path().apply { moveTo(w*.06f,h*.5f); quadraticBezierTo(w*.5f,h*.05f,w*.94f,h*.5f); quadraticBezierTo(w*.5f,h*.95f,w*.06f,h*.5f) }; drawPath(p,color,style=s); drawCircle(color,w*.14f,Offset(w*.5f,h*.5f),style=s) }
            "eye_off" -> { val p = Path().apply { moveTo(w*.06f,h*.5f); quadraticBezierTo(w*.5f,h*.05f,w*.94f,h*.5f); quadraticBezierTo(w*.5f,h*.95f,w*.06f,h*.5f) }; drawPath(p,color,style=s); line(.15f,.9f,.85f,.1f) }
            "stop" -> drawRect(color,Offset(w*.25f,h*.25f),size*.5f)
            else -> for(i in 0..2) { val y=.23f+i*.27f; line(.18f,y,.82f,y) }
        }
    }
}
