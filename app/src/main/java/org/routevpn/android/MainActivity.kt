package org.routevpn.android

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.routevpn.core.Subscriptions
import org.routevpn.core.TunnelConfig
import org.routevpn.core.ClientSettings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler

class MainActivity : ComponentActivity() {
    private lateinit var store: SubscriptionStore
    private var saved by mutableStateOf<SavedSubscription?>(null)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var settings by mutableStateOf(ClientSettings())
    private var settingsOpen by mutableStateOf(false)
    private var pendingAction: Intent? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pendingAction; pendingAction = null
        if (result.resultCode == RESULT_OK && action != null) launchService(action) else message = "Разрешение на VPN не выдано"
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = SubscriptionStore(this)
        busy = true
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.load() } }.onSuccess { saved = it }
                .onFailure { message = "Не удалось прочитать сохранённую подписку. Добавь её снова." }
            runCatching { withContext(Dispatchers.IO) { SettingsStore(this@MainActivity).load() } }.onSuccess { settings = it }
                .onFailure { message = "Не удалось прочитать настройки" }
            busy = false
        }
        setContent {
            BackHandler(enabled = settingsOpen) { settingsOpen = false }
            val tunnel by TunnelStatus.state.collectAsState()
            val results by Diagnostics.results.collectAsState()
            val health by Diagnostics.health.collectAsState()
            val probing by Diagnostics.running.collectAsState()
            val speed by Diagnostics.speed.collectAsState()
            var dialog by remember { mutableStateOf(false) }
            var url by remember { mutableStateOf("") }
            val locked = busy || probing || tunnel in setOf(TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING)
            val dark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF83DCC6), background = Color(0xFF101A23), surface = Color(0xFF1C2A35)) else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (settingsOpen) {
                        SettingsScreen(settings, locked, { next ->
                        busy = true
                        lifecycleScope.launch {
                            try { withContext(Dispatchers.IO) { SettingsStore(this@MainActivity).save(next) }; settings = next; settingsOpen = false; message = "Настройки сохранены" }
                            catch (_: Exception) { message = "Не удалось сохранить настройки" }
                            finally { busy = false }
                        }
                        }, { settingsOpen = false })
                    } else {
                    Column(Modifier.fillMaxSize().padding(24.dp).statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("MOTH / VPN", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { settingsOpen = true }) { Text("Настройки") }
                        }
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(when(tunnel) { TunnelState.OFF -> "VPN отключён"; TunnelState.STARTING -> "Запускаем VPN…"; TunnelState.RUNNING -> "VPN запущен"; TunnelState.STOPPING -> "Отключаем…"; TunnelState.ERROR -> "Не удалось запустить VPN" }, style = MaterialTheme.typography.titleLarge)
                                Text(if (tunnel == TunnelState.RUNNING) health.message + (health.latencyMs?.let { " · $it мс" } ?: "") else "Выбери сервер и подключись", style = MaterialTheme.typography.bodyMedium)
                                if (tunnel == TunnelState.RUNNING) TextButton(enabled = !health.checking, onClick = { startService(Intent(this@MainActivity, TunnelService::class.java).setAction("HEALTH")) }) { Text(if (health.checking) "Проверяем…" else "Проверить туннель") }
                                Button(onClick = { if (tunnel == TunnelState.RUNNING) stop() else connect() }, enabled = !busy && (!probing || tunnel == TunnelState.RUNNING) && tunnel !in setOf(TunnelState.STARTING, TunnelState.STOPPING) && saved?.profiles?.isNotEmpty() == true, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                    Text(if (tunnel == TunnelState.RUNNING) "Отключить" else "Подключиться")
                                }
                            }
                        }
                        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        if (tunnel == TunnelState.ERROR) Text("Проверь выбранный сервер и поддержку его настроек. Можно выбрать другой сервер.", color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !locked, onClick = { url = ""; dialog = true }) { Text(if (saved == null) "Добавить подписку" else "Заменить подписку") }
                            if (saved != null) TextButton(enabled = !locked, onClick = { saved?.let { import(it.url) } }) { Text("Обновить") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Серверы · ${saved?.profiles?.size ?: 0}", style = MaterialTheme.typography.titleMedium)
                            if (saved != null) TextButton(enabled = !locked, onClick = { removeSubscription() }) { Text("Удалить") }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (saved != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(enabled = !busy && tunnel !in setOf(TunnelState.STARTING, TunnelState.STOPPING), onClick = {
                                if (probing) startService(Intent(this@MainActivity, TunnelService::class.java).setAction("CANCEL_PROBES")) else diagnose(null, false)
                            }) { Text(if (probing) "Отменить" else "Проверить все") }
                            TextButton(enabled = !busy && !probing && tunnel !in setOf(TunnelState.STARTING, TunnelState.STOPPING), onClick = { diagnose(saved?.selected, true) }) { Text("Скорость · 1 МиБ") }
                        }
                        if (speed.isNotEmpty()) Text(speed, style = MaterialTheme.typography.bodySmall)
                        if (probing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(saved?.profiles.orEmpty().let { list -> if (settings.sortByLatency) list.sortedBy { results[it.id]?.latencyMs ?: Long.MAX_VALUE } else list }, key = { it.id }) { profile ->
                                val selected = profile.id == saved?.selected
                                Row(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                                    .clickable(enabled = !locked) { select(profile.id) }.padding(12.dp)) {
                                    RadioButton(selected = selected, onClick = { select(profile.id) }, enabled = !locked)
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(profile.name, style = MaterialTheme.typography.titleMedium); Text(profile.protocol.uppercase(), style = MaterialTheme.typography.labelSmall)
                                        results[profile.id]?.let { r -> Text(if (r.checking) "Проверяем…" else r.latencyMs?.let { "$it мс · HTTPS" } ?: r.error.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                                        results[profile.id]?.takeIf { !it.checking }?.let { r -> Text("Проверено " + android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(java.util.Date(r.checkedAt)), style = MaterialTheme.typography.labelSmall) }
                                        TextButton(enabled = !busy && !probing && tunnel !in setOf(TunnelState.STARTING, TunnelState.STOPPING), onClick = { diagnose(profile.id, false) }) { Text("Проверить") }
                                    }
                                }
                            }
                        }
                        Text("Без автоматического выбора и переподключения", style = MaterialTheme.typography.labelSmall)
                    }
                    }
                    if (dialog) AlertDialog(onDismissRequest = { if (!busy) dialog = false }, title = { Text("Добавить подписку") }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Вставь HTTPS-ссылку от своего VPN-провайдера. Она хранится только на устройстве.")
                            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Ссылка подписки") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        }
                    }, confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { dialog = false; import(url); url = "" }) { Text("Добавить") } }, dismissButton = { TextButton(onClick = { dialog = false; url = "" }) { Text("Отмена") } })
                }
            }
        }
    }
    private fun import(url: String) {
        if (busy) return
        busy = true; message = "Загружаем серверы…"
        lifecycleScope.launch {
            try {
                val previous = saved
                val result = withContext(Dispatchers.IO) {
                    val parsed = Subscriptions.parse(SubscriptionDownload.fetch(url, settings))
                    val selected = previous?.selected?.takeIf { id -> parsed.profiles.any { it.id == id } } ?: parsed.profiles.first().id
                    val next = SavedSubscription(url.trim(), parsed.profiles, selected)
                    store.save(next)
                    next to parsed.rejected
                }
                saved = result.first
                message = "Добавлено серверов: ${result.first.profiles.size}" + if (result.second > 0) ". Пропущено неподдерживаемых: ${result.second}" else ""
            } catch (e: Exception) {
                message = when(e) {
                    is java.net.SocketTimeoutException -> "Сервис подписки не ответил вовремя"
                    is java.net.UnknownHostException -> "Не удалось найти сервис подписки. Проверь интернет."
                    is javax.net.ssl.SSLException -> "Не удалось проверить защищённое соединение с сервисом подписки"
                    is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { !it.contains("://") } ?: "Не удалось добавить подписку"
                    else -> "Не удалось загрузить подписку. Проверь ссылку и интернет."
                }
            } finally { busy = false }
        }
    }
    private fun select(id: String) {
        if (busy || Diagnostics.running.value || TunnelStatus.state.value in setOf(TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING)) return
        val next = saved?.copy(selected = id) ?: return
        busy = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { store.save(next) }; saved = next }
            catch (_: Exception) { message = "Не удалось сохранить выбранный сервер" }
            finally { busy = false }
        }
    }
    private fun removeSubscription() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { store.clear() }; saved = null; message = "Подписка удалена" }
            catch (_: Exception) { message = "Не удалось удалить подписку" }
            finally { busy = false }
        }
    }
    private fun connect() {
        val subscription = saved ?: return
        val profile = subscription.profiles.firstOrNull { it.id == subscription.selected } ?: return
        authorize(Intent(this, TunnelService::class.java).setAction("START").putExtra("profile", profile.id))
    }
    private fun diagnose(id: String?, speed: Boolean) {
        authorize(Intent(this, TunnelService::class.java).setAction(if (speed) "SPEED" else "PROBE").apply { if (id != null) putExtra("profile", id) })
    }
    private fun authorize(action: Intent) {
        val permission = VpnService.prepare(this)
        if (permission != null) { pendingAction = action; vpnPermission.launch(permission) } else launchService(action)
    }
    private fun launchService(action: Intent) {
        if (action.action == "START") TunnelStatus.mutable.value = TunnelState.STARTING
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        try { startForegroundService(action) }
        catch (_: Exception) { if (action.action == "START") TunnelStatus.mutable.value = TunnelState.ERROR; message = "Не удалось запустить проверку или VPN" }
    }
    private fun stop() { startService(Intent(this, TunnelService::class.java).setAction("STOP")) }
}

