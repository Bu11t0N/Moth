package org.routevpn.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

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
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Moth", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
        Spacer(Modifier.weight(1f))
        Button(onClick = connect, enabled = !busy && (!probing || running) && !transition && selected != null,
            shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(148.dp)
                .semantics { contentDescription = if (running) "Отключить VPN" else "Подключить VPN" },
            colors = ButtonDefaults.buttonColors(containerColor = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)) {
            if (transition) CircularProgressIndicator(Modifier.size(44.dp), strokeWidth = 3.dp)
            else MothIcon("power", Modifier.size(56.dp))
        }
        Text(when (tunnel) { TunnelState.OFF -> "Подключиться"; TunnelState.RUNNING -> "VPN включён"; TunnelState.STARTING -> "Подключаемся…"; TunnelState.STOPPING -> "Отключаем…"; TunnelState.ERROR -> "Не удалось подключиться" }, style = MaterialTheme.typography.headlineSmall)
        if (running) Text(if (health.checking) "Проверяем интернет…" else health.message, style = MaterialTheme.typography.bodyMedium)
        if (selected != null) TextButton(onClick = servers) { Text(selected.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (saved == null) Button(enabled = !busy, onClick = add) { Text("Добавить подписку") }
        Spacer(Modifier.weight(1f))
        if (saved != null) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val host = remember(saved.url) { runCatching { URI(saved.url).host }.getOrNull().orEmpty() }
                Text(saved.providerName.ifBlank { host }, style = MaterialTheme.typography.titleMedium)
                Text("$host · ${saved.profiles.size} серверов", style = MaterialTheme.typography.bodySmall)
                if (saved.totalBytes > 0) Text("Трафик: %.1f / %.1f ГиБ".format(saved.usedBytes / 1073741824.0, saved.totalBytes / 1073741824.0), style = MaterialTheme.typography.bodySmall)
                if (saved.expires in 1..253402300799L) Text("Подписка до " + java.text.DateFormat.getDateInstance().format(java.util.Date(saved.expires * 1000)), style = MaterialTheme.typography.bodySmall)
                Text(if (!network.available) "Нет сети" else if (network.kind == "mobile") "Мобильные данные" else "Wi-Fi", style = MaterialTheme.typography.labelMedium)
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
            "stop" -> drawRect(color,Offset(w*.25f,h*.25f),size*.5f)
            else -> for(i in 0..2) { val y=.23f+i*.27f; line(.18f,y,.82f,y) }
        }
    }
}
