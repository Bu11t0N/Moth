package org.mothvpn.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.mothvpn.core.AppRelease
import org.mothvpn.core.UpdateChannel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun UpdatePage(storedChannel: String, selectChannel: (String) -> Unit, modifier: Modifier = Modifier) {
    val channel = UpdateChannel.effective(storedChannel, BuildConfig.VERSION_NAME)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var phase by remember { mutableStateOf<org.mothvpn.core.UpdatePhase>(org.mothvpn.core.UpdatePhase.Idle) }
    val busy = phase.busy
    val progress = (phase as? org.mothvpn.core.UpdatePhase.Downloading)?.progress
    fun on(event: org.mothvpn.core.UpdatePhase.Event, value: Float? = null) { phase = phase.on(event, value) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var warning by remember { mutableStateOf<File?>(null) }
    var waiting by remember { mutableStateOf<Pair<AppRelease, File>?>(null) }
    fun install(file: File) {
        Logger.i("Update", "install requested: ${file.length()} bytes")
        try { AppUpdater.install(context, file) } catch (e: Exception) { Logger.w("Update", "install failed", e); message = tr(R.string.update_failed) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = context.packageManager.canRequestPackageInstalls()
        Logger.i("Update", "install-unknown-apps permission ${if (granted) "granted" else "not granted"}")
        val pending = waiting; waiting = null
        if (granted && pending != null) { message = tr(R.string.update_permission_ready); install(pending.second) }
        else { message = tr(R.string.update_permission_needed); on(org.mothvpn.core.UpdatePhase.Event.DIALOG_DISMISSED) }
    }
    fun action(block: suspend () -> Unit) {
        job = scope.launch {
            on(org.mothvpn.core.UpdatePhase.Event.CHECK); message = ""
            try { block() }
            catch (e: CancellationException) { Logger.i("Update", "update action cancelled"); message = ""; on(org.mothvpn.core.UpdatePhase.Event.CANCELLED); throw e }
            catch (e: Exception) { Logger.w("Update", "update action failed", e); message = tr(R.string.update_failed); on(org.mothvpn.core.UpdatePhase.Event.FAILED) }
            finally { if (phase.busy) on(org.mothvpn.core.UpdatePhase.Event.DONE) }
        }
    }
    /** Reuses the verified saved download or downloads it, then installs (asking for the permission first when needed). */
    suspend fun proceed(available: AppRelease) {
        val file = AppUpdater.saved(context, available) ?: run {
            message = tr(R.string.update_downloading); on(org.mothvpn.core.UpdatePhase.Event.DOWNLOAD)
            withContext(Dispatchers.IO) { AppUpdater.download(context, available) { p -> scope.launch(Dispatchers.Main) { if (phase is org.mothvpn.core.UpdatePhase.Downloading) on(org.mothvpn.core.UpdatePhase.Event.DOWNLOAD, p) } } }
        }
        message = ""; on(org.mothvpn.core.UpdatePhase.Event.READY)
        if (!context.packageManager.canRequestPackageInstalls()) {
            Logger.i("Update", "install-unknown-apps permission missing, showing the explanation")
            waiting = available to file; warning = file
        } else install(file)
    }
    fun check() = action {
        release = null
        withContext(Dispatchers.IO) { AppUpdater.cleanup(context) }
        release = withContext(Dispatchers.IO) { AppUpdater.check(channel) }
        val found = release
        if (found == null) { message = tr(if (channel == UpdateChannel.BETA) R.string.update_latest_any else R.string.update_latest); withContext(Dispatchers.IO) { AppUpdater.cleanup(context) } }
        else proceed(found)
    }
    // Checked automatically when the screen is opened and whenever the channel changes; a found update is downloaded and installed without more taps.
    LaunchedEffect(channel) { check() }
    // The user closed the system install confirmation: back to idle so "Install" can be tapped again.
    LaunchedEffect(Unit) { val seen = AppUpdater.installAborted.value; AppUpdater.installAborted.collect { if (it > seen) { message = ""; on(org.mothvpn.core.UpdatePhase.Event.INSTALL_ABORTED) } } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(R.string.update_current, BuildConfig.VERSION_NAME))
            release?.let { Text(tr(R.string.update_available, it.version)) }
            if (busy) {
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { job?.cancel(); job = null; message = ""; on(org.mothvpn.core.UpdatePhase.Event.CANCELLED) }, modifier = Modifier.pressScale()) { Text(tr(R.string.update_cancel)) }
            }
            if (message.isNotEmpty()) Text(message)
        }
        release?.let { available ->
            NdButton(enabled = !busy, onClick = { action { proceed(available) } }, modifier = Modifier.pressScale(enabled = !busy).then(Modifier.fillMaxWidth())) { Text(tr(R.string.update_install)) }
        }
        NdButton(enabled = !busy, onClick = { check() }, modifier = Modifier.pressScale(enabled = !busy).then(Modifier.fillMaxWidth())) { Text(tr(R.string.update_check)) }
        warning?.let {
            AlertDialog(onDismissRequest = { warning = null; waiting = null; message = ""; on(org.mothvpn.core.UpdatePhase.Event.DIALOG_DISMISSED) }, title = { Text(tr(R.string.update_unknown_title)) },
                text = { Text(tr(R.string.update_unknown_body)) },
                confirmButton = { TextButton(onClick = {
                    warning = null
                    Logger.i("Update", "opening the unknown-sources settings for the app")
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.pressScale()) { Text(tr(R.string.update_unknown_ok)) } },
                dismissButton = { TextButton(onClick = { warning = null; waiting = null; message = ""; on(org.mothvpn.core.UpdatePhase.Event.DIALOG_DISMISSED) }, modifier = Modifier.pressScale()) { Text(tr(R.string.update_cancel)) } })
        }
        val channels = listOf(UpdateChannel.RELEASE to tr(R.string.update_channel_release), UpdateChannel.BETA to tr(R.string.update_channel_beta))
        var channelDialog by remember { mutableStateOf(false) }
        Card(Modifier.pressScale(enabled = !busy).fillMaxWidth().clickable(enabled = !busy) { channelDialog = true }, colors = ndCardColors()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.update_channel), style = MaterialTheme.typography.titleMedium)
                    Text(channels.first { it.first == channel }.second, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
            }
        }
        if (channelDialog) {
            val pick = { value: UpdateChannel ->
                channelDialog = false
                if (channel != value) { Logger.i("Settings", "update channel changed: ${channel.id} -> ${value.id}"); selectChannel(value.id) }
            }
            AlertDialog(onDismissRequest = { channelDialog = false }, title = { Text(tr(R.string.update_channel)) },
                text = { Column {
                    channels.forEach { (value, label) ->
                        Row(Modifier.pressScale().fillMaxWidth().clickable { pick(value) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(channel == value, { pick(value) })
                            Text(label)
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { channelDialog = false }, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
        }
        if (channel == UpdateChannel.BETA) Text(tr(R.string.update_channel_beta_note), style = MaterialTheme.typography.bodySmall)
    }
}
