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

class MainActivity : ComponentActivity() {
    private lateinit var store: SubscriptionStore
    private var saved by mutableStateOf<SavedSubscription?>(null)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var pendingConfig: String? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val config = pendingConfig; pendingConfig = null
        if (result.resultCode == RESULT_OK && config != null) start(config) else message = "Разрешение на VPN не выдано"
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
            busy = false
        }
        setContent {
            val tunnel by TunnelStatus.state.collectAsState()
            var dialog by remember { mutableStateOf(false) }
            var url by remember { mutableStateOf("") }
            val locked = busy || tunnel in setOf(TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING)
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF83DCC6), background = Color(0xFF101A23), surface = Color(0xFF1C2A35))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(24.dp).statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("MOTH / VPN", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("Твоё подключение", style = MaterialTheme.typography.headlineLarge)
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(when(tunnel) { TunnelState.OFF -> "VPN отключён"; TunnelState.STARTING -> "Запускаем VPN…"; TunnelState.RUNNING -> "VPN запущен"; TunnelState.STOPPING -> "Отключаем…"; TunnelState.ERROR -> "Не удалось запустить VPN" }, style = MaterialTheme.typography.titleLarge)
                                Text(if (tunnel == TunnelState.RUNNING) "Туннель активен. Проверь доступ к интернету." else "Выбери сервер и подключись", style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { if (tunnel == TunnelState.RUNNING) stop() else connect() }, enabled = !busy && tunnel !in setOf(TunnelState.STARTING, TunnelState.STOPPING) && saved?.profiles?.isNotEmpty() == true, modifier = Modifier.fillMaxWidth().height(56.dp)) {
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
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(saved?.profiles.orEmpty(), key = { it.id }) { profile ->
                                val selected = profile.id == saved?.selected
                                Row(Modifier.fillMaxWidth().background(if (selected) Color(0xFF254B48) else MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                                    .clickable(enabled = !locked) { select(profile.id) }.padding(12.dp)) {
                                    RadioButton(selected = selected, onClick = { select(profile.id) }, enabled = !locked)
                                    Column(Modifier.padding(start = 8.dp)) { Text(profile.name, style = MaterialTheme.typography.titleMedium); Text(profile.protocol.uppercase(), style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                        Text("Без автоматического выбора и переподключения", style = MaterialTheme.typography.labelSmall)
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
                    val parsed = Subscriptions.parse(SubscriptionDownload.fetch(url))
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
        if (busy || TunnelStatus.state.value in setOf(TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING)) return
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
        val config = TunnelConfig.create(profile)
        val permission = VpnService.prepare(this)
        if (permission != null) { pendingConfig = config; vpnPermission.launch(permission) } else start(config)
    }
    private fun start(config: String) {
        TunnelStatus.mutable.value = TunnelState.STARTING
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        try { startForegroundService(Intent(this, TunnelService::class.java).setAction("START").putExtra("config", config)) }
        catch (_: Exception) { TunnelStatus.mutable.value = TunnelState.ERROR }
    }
    private fun stop() { startService(Intent(this, TunnelService::class.java).setAction("STOP")) }
}

