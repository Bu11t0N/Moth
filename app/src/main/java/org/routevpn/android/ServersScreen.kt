package org.routevpn.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.routevpn.core.ClientSettings

@Composable fun ServersScreen(saved: SavedSubscription?, settings: ClientSettings, network: AccessNetwork, busy: Boolean, message: String,
    add: () -> Unit, refresh: () -> Unit, remove: () -> Unit, select: (String, String) -> Unit, probe: (String) -> Unit,
    speedTest: (String) -> Unit, cancel: () -> Unit, preferences: (ClientSettings) -> Unit) {
    val results by Diagnostics.results.collectAsState()
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
            val failed = current && r != null && !r.checking && r.error != null && r.error != "Проверка отменена" && r.method == "HTTPS" && now - r.checkedAt in 0 until 900_000
            (!settings.hideUnavailable || !failed) && (p.name.contains(search, true) || p.protocol.contains(search, true))
        }.let { if (settings.sortByLatency) it.sortedBy { p -> results["$kind:${p.id}"]?.latencyMs ?: Long.MAX_VALUE } else it }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Серверы", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ActionIcon("add", if (saved == null) "Добавить подписку" else "Заменить подписку", !locked, add)
            ActionIcon("refresh", "Обновить подписку", saved != null && !locked, refresh)
            ActionIcon(if (probing) "stop" else "check", if (probing) "Отменить проверки" else "Проверить серверы", probing || canCheck) { if (probing) cancel() else probe(kind) }
            ActionIcon("speed", "Измерить скорость: загрузка до 1 МиБ", canCheck) { speedTest(kind) }
            ActionIcon("delete", "Удалить подписку", saved != null && !locked) { delete = true }
        }
        TabRow(selectedTabIndex = if (kind == "wifi") 0 else 1) {
            Tab(selected = kind == "wifi", onClick = { kind = "wifi" }, text = { Text("Wi-Fi") })
            Tab(selected = kind == "mobile", onClick = { kind = "mobile" }, text = { Text("Мобильные данные") })
        }
        if (!network.available || kind != network.kind) Text(if (!network.available) "Нет сети для проверки" else if (kind == "mobile") "Для проверки включи мобильные данные и отключи Wi-Fi" else "Для проверки подключись к Wi-Fi", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${visible.size} / ${profiles.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            FilterChip(selected = settings.hideUnavailable, onClick = { preferences(settings.copy(hideUnavailable = !settings.hideUnavailable)) }, label = { Text("Без недоступных") })
        }
        OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("Поиск сервера") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        if (busy || probing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (speed.isNotBlank()) Text(speed, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (visible.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (saved == null) "Добавь подписку" else "Серверы не найдены")
            if (saved == null) TextButton(enabled = !busy, onClick = add) { Text("Добавить подписку") }
            else TextButton(onClick = { search = ""; preferences(settings.copy(hideUnavailable = false)) }) { Text("Показать все") }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(visible, key = { it.id }) { p ->
                val chosen = p.id == saved?.selection(kind)
                val r = results["$kind:${p.id}"]
                Surface(color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) { select(p.id, kind) }
                        .semantics { contentDescription = p.name + if (chosen) ", выбран" else "" }) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = if (settings.compactServers) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(p.name, maxLines = if (settings.compactServers) 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(p.protocol.uppercase() + " · " + when { r?.checking == true -> "проверяем…"; r?.latencyMs != null -> "${r.latencyMs} мс · ${r.method}"; r?.error != null -> r.error; else -> "не проверен" }, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (r?.latencyMs != null && r.method == "TCP") Text("Порт доступен; работа VPN не проверена", style = MaterialTheme.typography.labelSmall)
                        if (r != null && !r.checking && r.networkHandle != network.handle) Text("Проверено в другой сети", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Удалить подписку?") }, text = { Text("Для подключения понадобится снова добавить ссылку.") }, confirmButton = { TextButton(enabled = !locked, onClick = { delete = false; remove() }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } })
}

@Composable private fun ActionIcon(icon: String, label: String, enabled: Boolean, action: () -> Unit) {
    IconButton(enabled = enabled, onClick = action, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) { MothIcon(icon) }
}
