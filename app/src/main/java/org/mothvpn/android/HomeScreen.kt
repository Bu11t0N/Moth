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
@Composable fun HomeScreen(saved: SavedSubscription?, network: AccessNetwork, busy: Boolean, message: String,
    add: () -> Unit, connect: () -> Unit, servers: () -> Unit) {
    val tunnel by TunnelStatus.state.collectAsState()
    val health by Diagnostics.health.collectAsState()
    val probing by Diagnostics.running.collectAsState()
    val active by TunnelStatus.activeProfile.collectAsState()
    val selected = remember(saved, network.kind, active, tunnel) {
        saved?.profiles?.firstOrNull { it.id == if (tunnel == TunnelState.RUNNING) active else saved.selection(network.kind) }
    }
    val running = tunnel == TunnelState.RUNNING
    val transition = tunnel in setOf(TunnelState.STARTING, TunnelState.STOPPING)
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
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = connect, enabled = !busy && (!probing || running) && !transition && selected != null,
            shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(148.dp)
                .semantics { contentDescription = if (running) tr(R.string.msg_disconnect_vpn_8) else tr(R.string.msg_connect_vpn_9) },
            colors = ButtonDefaults.buttonColors(containerColor = if (running) connectedColor else disconnectedColor, contentColor = androidx.compose.ui.graphics.Color.White)) {
            if (transition) CircularProgressIndicator(Modifier.size(44.dp), strokeWidth = 3.dp)
            else MothIcon("power", Modifier.size(56.dp))
        }
        Text(when (tunnel) { TunnelState.OFF -> tr(R.string.msg_connect_10); TunnelState.RUNNING -> tr(R.string.msg_vpn_is_on_11); TunnelState.STARTING -> tr(R.string.msg_connecting_12); TunnelState.STOPPING -> tr(R.string.msg_disconnecting_13); TunnelState.ERROR -> tr(R.string.msg_could_not_connect_14) }, style = MaterialTheme.typography.headlineSmall)
        if (running) Text(if (health.checking) tr(R.string.msg_checking_internet_access_15) else localizedError(health.message), style = MaterialTheme.typography.bodyMedium)
        if (selected != null) TextButton(onClick = servers) { Text(selected.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (saved == null) Button(enabled = !busy, onClick = add) { Text(tr(R.string.msg_add_subscription_16)) }
        Spacer(Modifier.weight(1f))
        if (saved != null) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val host = remember(saved.url) { runCatching { URI(saved.url).host }.getOrNull().orEmpty() }
                if (saved.providerName.isNotBlank() && saved.providerName != host) Text(saved.providerName, style = MaterialTheme.typography.titleMedium)
                var showLink by remember { mutableStateOf(false) }
                val clipboard = LocalClipboardManager.current
                TextButton(onClick = { showLink = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 32.dp)
                    .semantics { contentDescription = tr(R.string.sub_link_show) }) {
                    Text(tr(R.string.msg_host_saved_profiles_size_servers_17, host, saved.profiles.size), style = MaterialTheme.typography.bodySmall)
                }
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
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable fun MothIcon(name: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val s = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x,h*y), Offset(w*x2,h*y2), s.width, StrokeCap.Round)
        when (name) {
            "power" -> { drawArc(color, -45f, 270f, false, Offset(w*.15f,h*.15f), size*.7f, style=s); line(.5f,.05f,.5f,.45f) }
            "home" -> { val p=Path().apply { moveTo(w*.12f,h*.45f); lineTo(w*.5f,h*.12f); lineTo(w*.88f,h*.45f); moveTo(w*.23f,h*.4f); lineTo(w*.23f,h*.88f); lineTo(w*.77f,h*.88f); lineTo(w*.77f,h*.4f) }; drawPath(p,color,style=s) }
            "settings" -> { for (i in 0..2) { val y=.23f+i*.27f; line(.1f,y,.9f,y); drawCircle(color,w*.09f,Offset(w*(if(i==1).65f else .35f),h*y)) } }
            "refresh" -> { drawArc(color,40f,290f,false,Offset(w*.16f,h*.16f),size*.68f,style=s); line(.84f,.16f,.84f,.42f); line(.84f,.42f,.6f,.42f) }
            "delete" -> { line(.15f,.25f,.85f,.25f); line(.4f,.1f,.6f,.1f); line(.25f,.3f,.3f,.88f); line(.3f,.88f,.7f,.88f); line(.7f,.88f,.75f,.3f); line(.43f,.42f,.43f,.72f); line(.57f,.42f,.57f,.72f) }
            "check" -> { line(.12f,.6f,.3f,.6f); line(.3f,.6f,.42f,.25f); line(.42f,.25f,.58f,.8f); line(.58f,.8f,.72f,.45f); line(.72f,.45f,.9f,.45f) }
            "speed" -> { drawArc(color,180f,180f,false,Offset(w*.12f,h*.2f),androidx.compose.ui.geometry.Size(w*.76f,h*.76f),style=s); line(.5f,.62f,.75f,.32f); line(.15f,.68f,.85f,.68f) }
            "add" -> { line(.5f,.15f,.5f,.85f); line(.15f,.5f,.85f,.5f) }
            "eye" -> { val p = Path().apply { moveTo(w*.06f,h*.5f); quadraticBezierTo(w*.5f,h*.05f,w*.94f,h*.5f); quadraticBezierTo(w*.5f,h*.95f,w*.06f,h*.5f) }; drawPath(p,color,style=s); drawCircle(color,w*.14f,Offset(w*.5f,h*.5f),style=s) }
            "eye_off" -> { val p = Path().apply { moveTo(w*.06f,h*.5f); quadraticBezierTo(w*.5f,h*.05f,w*.94f,h*.5f); quadraticBezierTo(w*.5f,h*.95f,w*.06f,h*.5f) }; drawPath(p,color,style=s); line(.15f,.9f,.85f,.1f) }
            "stop" -> drawRect(color,Offset(w*.25f,h*.25f),size*.5f)
            else -> for(i in 0..2) { val y=.23f+i*.27f; line(.18f,y,.82f,y) }
        }
    }
}
