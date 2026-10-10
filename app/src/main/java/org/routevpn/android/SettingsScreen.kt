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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.routevpn.core.ClientSettings

@Composable fun SettingsScreen(initial: ClientSettings, locked: Boolean, save: (ClientSettings) -> Unit, back: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var section by remember { mutableStateOf<String?>(null) }
    var mtu by remember(initial) { mutableStateOf(initial.mtu.toString()) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var interval by remember(initial) { mutableStateOf(initial.healthIntervalSeconds.toString()) }
    var error by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    BackHandler(enabled = section != null) { section = null }
    fun applySettings(next: ClientSettings) {
        draft = next
        try { save(next.validate()); error = "" }
        catch (e: Exception) { error = if (e is java.net.URISyntaxException) "Нужен корректный HTTPS-адрес" else e.message ?: "Проверь настройки" }
    }
    fun number(text: String, field: String) {
        val n = text.toIntOrNull()
        if (n == null) { error = "Введи целое число"; return }
        applySettings(when (field) { "mtu" -> draft.copy(mtu = n); "timeout" -> draft.copy(timeoutSeconds = n); else -> draft.copy(healthIntervalSeconds = n) })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(section ?: "Настройки", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { if (section != null) section = null else back() }) { Text("Назад") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (section) {
                null -> {
                    SettingsCategory("Интерфейс") { section = "Интерфейс" }
                    SettingsCategory("Подключение") { section = "Подключение" }
                    SettingsCategory("Проверки") { section = "Проверки" }
                    SettingsCategory("Подписка и HWID") { section = "Подписка и HWID" }
                    SettingsCategory("О приложении") { section = "О приложении" }
                }
                "Интерфейс" -> {
                    Text("Тема", style = MaterialTheme.typography.titleMedium)
                    listOf("system" to "Как в системе", "dark" to "Тёмная", "light" to "Светлая").forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable { applySettings(draft.copy(theme = value)) }) {
                            RadioButton(draft.theme == value, { applySettings(draft.copy(theme = value)) })
                            Text(label, modifier = Modifier.padding(top = 13.dp))
                        }
                    }
                    SettingsToggle("Компактные строки серверов", draft.compactServers) { applySettings(draft.copy(compactServers = it)) }
                    SettingsToggle("Сортировать по задержке", draft.sortByLatency) { applySettings(draft.copy(sortByLatency = it)) }
                    SettingsToggle("Скрывать недоступные серверы", draft.hideUnavailable) { applySettings(draft.copy(hideUnavailable = it)) }
                    Text("Скрываются серверы с неудачной проверкой за последние 15 минут. Непроверенные и проверяемые остаются в списке. Перепроверь серверы при смене сети.", style = MaterialTheme.typography.bodySmall)
                    Text("Можно транслировать экран и делать скриншоты. Ссылка подписки и HWID скрыты в полях ввода.", style = MaterialTheme.typography.bodySmall)
                }
                "Подключение" -> {
                    if (locked) Text("Сетевые настройки доступны после отключения VPN и завершения проверок.")
                    OutlinedTextField(mtu, { mtu = it; number(it, "mtu") }, enabled = !locked, label = { Text("MTU · 1280–9000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SettingsToggle("IPv6 через VPN", draft.ipv6, !locked) { applySettings(draft.copy(ipv6 = it)) }
                    Text("При выключении IPv6 блокируется внутри туннеля.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.dns, { applySettings(draft.copy(dns = it)) }, enabled = !locked, label = { Text("DNS over HTTPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.bootstrapDns, { applySettings(draft.copy(bootstrapDns = it)) }, enabled = !locked, label = { Text("DNS для адресов серверов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Этот DNS используется напрямую для подключения к серверу. Если Cloudflare недоступен, можно указать другой DoH.", style = MaterialTheme.typography.bodySmall)
                }
                "Проверки" -> {
                    if (locked) Text("Отключи VPN и заверши проверки, чтобы менять эти параметры.")
                    SettingsToggle("Проверять активный туннель", draft.healthEnabled, !locked) { applySettings(draft.copy(healthEnabled = it)) }
                    OutlinedTextField(interval, { interval = it; number(it, "interval") }, enabled = !locked, label = { Text("Интервал · 30–600 секунд") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(timeout, { timeout = it; number(it, "timeout") }, enabled = !locked, label = { Text("Тайм-аут · 3–30 секунд") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.probeUrl, { applySettings(draft.copy(probeUrl = it)) }, enabled = !locked, label = { Text("HTTPS-адрес проверки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Задержка включает установление HTTPS-соединения. Отказ этого адреса не доказывает недоступность всех сайтов.", style = MaterialTheme.typography.bodySmall)
                    Text("Способ проверки", style = MaterialTheme.typography.titleMedium)
                    listOf("auto" to "HTTPS · несколько адресов", "https" to "HTTPS · свой адрес", "tcp" to "TCP · доступность порта").forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { applySettings(draft.copy(probeMode = value)) }) {
                            RadioButton(draft.probeMode == value, { applySettings(draft.copy(probeMode = value)) }, enabled = !locked)
                            Text(label, modifier = Modifier.padding(top = 13.dp))
                        }
                    }
                    Text("TCP не подтверждает работу VPN и не подходит для Hysteria/TUIC на UDP. HTTPS проверяет передачу трафика через сервер.", style = MaterialTheme.typography.bodySmall)
                }
                "Подписка и HWID" -> {
                    if (locked) Text("Заверши подключение и проверки, чтобы менять параметры подписки.")
                    SettingsToggle("Передавать HWID провайдеру", draft.hwidEnabled, !locked) { applySettings(draft.copy(hwidEnabled = it)) }
                    Text("Постоянный идентификатор установки, без IMEI и серийного номера. Меняй его только если провайдер выдал конкретное значение.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.hwid, { applySettings(draft.copy(hwid = it)) }, enabled = !locked, label = { Text("HWID · x-hwid") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { clipboard.setText(AnnotatedString(draft.hwid)) }) { Text("Скопировать HWID") }
                    SettingsToggle("Передавать модель и версию Android", draft.deviceInfo, !locked) { applySettings(draft.copy(deviceInfo = it)) }
                    OutlinedTextField(draft.userAgent, { applySettings(draft.copy(userAgent = it)) }, enabled = !locked, label = { Text("User-Agent подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Провайдер может выбирать формат подписки по User-Agent. HWID отправляется только исходному сайту подписки.", style = MaterialTheme.typography.bodySmall)
                }
                "О приложении" -> {
                    Text("Moth ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                    Text("sing-box 1.11.15 + Xray 25.3.6\nОткрытый исходный код · GPL-3.0-or-later\nhttps://github.com/Bu11t0N/Moth")
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
