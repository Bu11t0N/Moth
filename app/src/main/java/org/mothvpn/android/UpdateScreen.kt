package org.mothvpn.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.mothvpn.core.AppRelease
import java.io.File

@Composable fun UpdatePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        message = tr(if (context.packageManager.canRequestPackageInstalls()) R.string.update_permission_ready else R.string.update_permission_needed)
    }
    fun action(block: suspend () -> Unit) {
        job = scope.launch {
            busy = true; message = ""
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logger.w("Update", "update action failed", e); message = tr(R.string.update_failed) }
            finally { busy = false }
        }
    }
    fun check() = action {
        apk = null; release = null
        release = withContext(Dispatchers.IO) { AppUpdater.check() }
        Logger.i("Update", if (release == null) "no newer stable version" else "newer version ${release?.version} available")
        if (release == null) message = tr(R.string.update_latest)
    }
    // Checked automatically whenever the screen is opened.
    LaunchedEffect(Unit) { check() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(R.string.update_current, BuildConfig.VERSION_NAME))
            release?.let { available ->
                Text(tr(R.string.update_available, available.version))
                if (apk == null) Button(enabled = !busy, onClick = { action {
                    apk = withContext(Dispatchers.IO) { AppUpdater.download(context, available) }
                } }) { Text(tr(R.string.update_download)) }
                else Button(enabled = !busy, onClick = { action {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        } else {
                            val file = apk ?: error("APK unavailable")
                            withContext(Dispatchers.IO) { AppUpdater.verify(context, file, available.version) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                } }) { Text(tr(R.string.update_install)) }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { job?.cancel() }) { Text(tr(R.string.update_cancel)) }
            }
            if (message.isNotEmpty()) Text(message)
        }
        Button(enabled = !busy, onClick = { check() }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.update_check)) }
    }
}
