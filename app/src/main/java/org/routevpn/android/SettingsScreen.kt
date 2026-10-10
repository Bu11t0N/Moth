package org.routevpn.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.routevpn.core.ClientSettings

@Composable fun SettingsScreen(initial: ClientSettings, locked: Boolean, save: (ClientSettings) -> Unit, back: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var mtu by remember(initial) { mutableStateOf(initial.mtu.toString()) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var interval by remember(initial) { mutableStateOf(initial.healthIntervalSeconds.toString()) }
    var error by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = back) { Text("Назад") }
        }
        if (locked) Text("Отключи VPN и заверши проверки, чтобы сохранить изменения.")
        Text("Оформление", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system" to "Системная", "dark" to "Тёмная", "light" to "Светлая").forEach { (value, label) ->
                FilterChip(selected = draft.theme == value, onClick = { draft = draft.copy(theme = value) }, label = { Text(label) })
            }
        }
        SettingsToggle("Сортировать серверы по задержке", draft.sortByLatency) { draft = draft.copy(sortByLatency = it) }
        Text("Подключение", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(mtu, { mtu = it }, label = { Text("MTU · 1280–9000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SettingsToggle("IPv6 через VPN", draft.ipv6) { draft = draft.copy(ipv6 = it) }
        Text("При выключении IPv6 блокируется внутри туннеля.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.dns, { draft = draft.copy(dns = it) }, label = { Text("DNS over HTTPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("DNS для адресов VPN-серверов: Cloudflare DoH напрямую. DNS приложений идёт через VPN.", style = MaterialTheme.typography.bodySmall)
        Text("Проверки соединения", style = MaterialTheme.typography.titleMedium)
        SettingsToggle("Периодически проверять активный туннель", draft.healthEnabled) { draft = draft.copy(healthEnabled = it) }
        OutlinedTextField(interval, { interval = it }, label = { Text("Интервал · 30–600 секунд") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(timeout, { timeout = it }, label = { Text("Тайм-аут серверов · 3–30 секунд") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.probeUrl, { draft = draft.copy(probeUrl = it) }, label = { Text("HTTPS-адрес проверки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Успешный HTTPS-запрос означает доступность этого адреса. Задержка включает установление соединения. Серверы автоматически не переключаются.", style = MaterialTheme.typography.bodySmall)
        Text("Подписка и HWID", style = MaterialTheme.typography.titleMedium)
        SettingsToggle("Передавать HWID провайдеру подписки", draft.hwidEnabled) { draft = draft.copy(hwidEnabled = it) }
        Text("HWID создаётся один раз для этой установки. Это не IMEI и не серийный номер. Удаление приложения меняет HWID; у провайдера может потребоваться удалить старое устройство.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.hwid, { draft = draft.copy(hwid = it) }, label = { Text("HWID · x-hwid") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { clipboard.setText(AnnotatedString(draft.hwid)) }) { Text("Скопировать HWID") }
        Text("Меняй HWID только если провайдер выдал конкретное значение для устройства.", style = MaterialTheme.typography.bodySmall)
        SettingsToggle("Передавать модель и версию Android", draft.deviceInfo) { draft = draft.copy(deviceInfo = it) }
        OutlinedTextField(draft.userAgent, { draft = draft.copy(userAgent = it) }, label = { Text("User-Agent подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Провайдер может выбирать формат подписки по User-Agent. HWID отправляется только исходному сайту подписки.", style = MaterialTheme.typography.bodySmall)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !locked, onClick = {
            try { save(draft.copy(mtu = mtu.toInt(), timeoutSeconds = timeout.toInt(), healthIntervalSeconds = interval.toInt()).validate()); error = "" }
            catch (e: Exception) { error = if (e is NumberFormatException) "Введи целые числа в числовые поля" else e.message ?: "Проверь настройки" }
        }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        Text("Moth ${BuildConfig.VERSION_NAME} · sing-box 1.11.15 + Xray 25.3.6\nОткрытый исходный код · GPL-3.0-or-later\nhttps://github.com/Bu11t0N/Moth", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable private fun SettingsToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp, end = 12.dp))
        Switch(checked = checked, onCheckedChange = change)
    }
}
