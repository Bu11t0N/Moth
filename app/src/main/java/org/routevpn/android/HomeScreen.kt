package org.routevpn.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.routevpn.core.ClientSettings
import kotlinx.coroutines.delay

@Composable fun HomeScreen(
    saved: SavedSubscription?, settings: ClientSettings, tunnel: TunnelState, health: HealthResult,
    results: Map<String, ProbeResult>, busy: Boolean, probing: Boolean, message: String, speed: String,
    openSettings: () -> Unit, add: () -> Unit, refresh: () -> Unit, remove: () -> Unit,
    toggleConnection: () -> Unit, select: (String) -> Unit, probe: (String?) -> Unit,
    speedTest: () -> Unit, cancel: () -> Unit, checkHealth: () -> Unit, preferences: (ClientSettings) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var speedDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var expandedMessage by remember(message, speed) { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(results) {
        now = System.currentTimeMillis()
        while (true) { delay(60_000); now = System.currentTimeMillis() }
    }
    val transitioning = tunnel in setOf(TunnelState.STARTING, TunnelState.STOPPING)
    val connected = tunnel == TunnelState.RUNNING
    val locked = busy || probing || transitioning || connected
    val canProbe = saved != null && !busy && !probing && !transitioning
    val profiles = saved?.profiles.orEmpty()
    val selected = profiles.firstOrNull { it.id == saved?.selected }
    val visible = profiles.filter { p ->
        val r = results[p.id]
        val failed = r != null && !r.checking && r.error != null && r.error != "Проверка отменена" && now - r.checkedAt in 0 until 15 * 60 * 1000
        (!settings.hideUnavailable || !failed) && (p.name.contains(search, true) || p.protocol.contains(search, true))
    }.let { if (settings.sortByLatency) it.sortedBy { p -> results[p.id]?.latencyMs ?: Long.MAX_VALUE } else it }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Moth", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = openSettings) { Text("Настройки") }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Действия с подпиской и проверки" }) { Text("⋮", fontSize = 28.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (saved == null) "Добавить подписку" else "Заменить подписку") }, enabled = !locked, onClick = { menu = false; add() })
                    if (saved != null) {
                        DropdownMenuItem(text = { Text("Обновить подписку") }, enabled = !locked, onClick = { menu = false; refresh() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Проверить все серверы") }, enabled = canProbe, onClick = { menu = false; probe(null) })
                        DropdownMenuItem(text = { Text("Измерить скорость") }, enabled = canProbe, onClick = { menu = false; speedDialog = true })
                        if (connected) DropdownMenuItem(text = { Text("Проверить туннель") }, enabled = !health.checking, onClick = { menu = false; checkHealth() })
                        if (probing) DropdownMenuItem(text = { Text("Остановить проверки") }, onClick = { menu = false; cancel() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Удалить подписку", color = MaterialTheme.colorScheme.error) }, enabled = !locked, onClick = { menu = false; deleteDialog = true })
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when (tunnel) { TunnelState.OFF -> "Готов к подключению"; TunnelState.STARTING -> "Подключаемся…"; TunnelState.RUNNING -> "VPN включён"; TunnelState.STOPPING -> "Отключаем…"; TunnelState.ERROR -> "Не удалось подключиться" }, style = MaterialTheme.typography.titleLarge)
                    Text(selected?.name ?: "Добавь свою подписку", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (connected) Text(if (health.checking) "Проверяем интернет…" else health.message, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = toggleConnection, enabled = !busy && (!probing || connected) && !transitioning && selected != null,
                    shape = CircleShape, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(80.dp).semantics { contentDescription = if (connected) "Отключить VPN" else "Подключить VPN" }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (transitioning) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        else {
                            val iconColor = LocalContentColor.current
                            Canvas(Modifier.size(30.dp)) {
                                val stroke = 2.5.dp.toPx()
                                drawArc(iconColor, -45f, 270f, false, topLeft = Offset(size.width * .15f, size.height * .15f), size = size * .7f, style = Stroke(stroke, cap = StrokeCap.Round))
                                drawLine(iconColor, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height * .45f), strokeWidth = stroke, cap = StrokeCap.Round)
                            }
                        }
                        Text(if (connected) "Выключить" else "Включить", fontSize = 10.sp)
                    }
                }
            }
        }
        if (message.isNotBlank() || speed.isNotBlank()) {
            Text(listOf(message, speed).filter { it.isNotBlank() }.joinToString("\n"), maxLines = if (expandedMessage) 8 else if (message.isNotBlank() && speed.isNotBlank()) 2 else 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { expandedMessage = !expandedMessage })
        }
        if (busy || probing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (probing) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Проверяем серверы…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = cancel, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Отменить") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Серверы · ${visible.size}/${profiles.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            FilterChip(selected = settings.hideUnavailable, onClick = { preferences(settings.copy(hideUnavailable = !settings.hideUnavailable)) }, label = { Text("Без недоступных", fontSize = 12.sp) })
        }
        if (profiles.isNotEmpty()) OutlinedTextField(search, { search = it }, placeholder = { Text("Поиск сервера или протокола") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        if (visible.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when { saved == null -> "Подключение начинается с подписки"; search.isNotBlank() -> "Ничего не найдено"; settings.hideUnavailable -> "Все проверенные серверы скрыты"; else -> "В подписке нет серверов" })
                when {
                    saved == null -> Button(enabled = !busy, onClick = add) { Text("Добавить подписку") }
                    search.isNotBlank() -> TextButton(onClick = { search = "" }) { Text("Сбросить поиск") }
                    settings.hideUnavailable -> TextButton(onClick = { preferences(settings.copy(hideUnavailable = false)) }) { Text("Показать все серверы") }
                }
            }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (settings.compactServers) 4.dp else 8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(visible, key = { it.id }) { profile ->
                var rowMenu by remember { mutableStateOf(false) }
                val active = profile.id == saved?.selected
                val result = results[profile.id]
                Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) { select(profile.id) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = if (settings.compactServers) 4.dp else 12.dp, bottom = if (settings.compactServers) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (active) "●" else "○", color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(profile.name, maxLines = if (settings.compactServers) 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(profile.protocol.uppercase() + " · " + when { result?.checking == true -> "проверяем…"; result?.latencyMs != null -> "${result.latencyMs} мс"; result?.error == "Проверка отменена" -> "проверка отменена"; result?.error != null -> "не отвечает"; else -> "не проверен" }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!settings.compactServers && result != null && !result.checking) Text(result.error ?: "HTTPS · проверено " + android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(java.util.Date(result.checkedAt)), style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { rowMenu = true }, modifier = Modifier.semantics { contentDescription = "Действия сервера ${profile.name}" }) { Text("⋮", fontSize = 24.sp) }
                            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                DropdownMenuItem(text = { Text("Выбрать сервер") }, enabled = !locked, onClick = { rowMenu = false; select(profile.id) })
                                DropdownMenuItem(text = { Text("Проверить доступность") }, enabled = canProbe, onClick = { rowMenu = false; probe(profile.id) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (speedDialog) AlertDialog(onDismissRequest = { speedDialog = false }, title = { Text("Измерить скорость") }, text = { Text("Загрузим до 1 МиБ тестовых данных через сервер «${selected?.name.orEmpty()}». Покажем скорость в Мбит/с. Это короткий тест загрузки, он расходует трафик.") }, confirmButton = { TextButton(enabled = canProbe, onClick = { speedDialog = false; speedTest() }) { Text("Начать тест") } }, dismissButton = { TextButton(onClick = { speedDialog = false }) { Text("Отмена") } })
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Удалить подписку?") }, text = { Text("Список серверов будет удалён с устройства. Для подключения понадобится снова добавить ссылку.") }, confirmButton = { TextButton(enabled = !locked, onClick = { deleteDialog = false; remove() }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Отмена") } })
}
