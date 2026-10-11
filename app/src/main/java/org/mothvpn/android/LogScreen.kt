package org.mothvpn.android

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mothvpn.core.LogText

private const val VISIBLE_LINES = 400

/** "For developers" page: entries for the log and the crash log. */
@Composable fun DeveloperSection(openLog: () -> Unit, openCrash: () -> Unit) {
    for ((title, open) in listOf(tr(R.string.dev_log) to openLog, tr(R.string.dev_crash_log) to openCrash)) {
        Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@androidx.annotation.RequiresApi(29)
private fun saveToDownloads(context: android.content.Context, name: String, text: String): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
    context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
}.onFailure { Logger.w("Log", "saving the log to Downloads failed", it) }.isSuccess

/** One log page: [crash] selects the crash log (no level selector) or the normal log (with level selector). */
@Composable fun LogPage(crash: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(Logger.level) }
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var version by remember { mutableStateOf(0) }
    fun full(): String = (if (crash) CrashLog.shareFile() else Logger.shareFile()).readText()
    val stamp = { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) }
    val fileName = { (if (crash) "moth-crash-" else "moth-log-") + stamp() + ".txt" }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(full().toByteArray()) } }.onFailure { Logger.w("Log", "saving the log to a document failed", it) }.isSuccess
            Logger.i("UI", "toast: log ${if (ok) "saved" else "save failed"}"); Toast.makeText(context, if (ok) tr(R.string.dev_saved_to_downloads) else tr(R.string.dev_save_failed), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(version) {
        lines = withContext(Dispatchers.IO) { LogText.lastLines(if (crash) CrashLog.read() else Logger.read(), VISIBLE_LINES) }
    }
    val state = rememberLazyListState()
    LaunchedEffect(lines) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!crash) {
            val levels = listOf(LogLevel.OFF to tr(R.string.dev_level_off), LogLevel.ERROR to "ERROR", LogLevel.WARN to "WARN", LogLevel.INFO to "INFO",
                LogLevel.DEBUG to "DEBUG", LogLevel.VERBOSE to "VERBOSE")
            var levelDialog by remember { mutableStateOf(false) }
            fun choose(value: LogLevel) { levelDialog = false; if (value != level) { level = value; Logger.level = value } }
            Card(Modifier.fillMaxWidth().clickable { levelDialog = true }) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.dev_log_level), style = MaterialTheme.typography.titleMedium)
                        Text(levels.first { it.first == level }.second, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
            if (levelDialog) AlertDialog(onDismissRequest = { levelDialog = false }, title = { Text(tr(R.string.dev_log_level)) },
                text = { Column {
                    levels.forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable { choose(value) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(level == value, { choose(value) })
                            Text(label)
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { levelDialog = false }) { Text(tr(R.string.msg_cancel_32)) } })
            Text(tr(R.string.dev_log_privacy_note), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val file = if (crash) CrashLog.shareFile() else Logger.shareFile()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
                Logger.i("Log", "sharing the ${if (crash) "crash log" else "log"}")
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Moth log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, tr(R.string.dev_share_log)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }) { Text(tr(R.string.dev_share_log)) }
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 29) {
                    val ok = saveToDownloads(context, fileName(), full())
                    Logger.i("UI", "toast: log ${if (ok) "saved" else "save failed"}"); Toast.makeText(context, if (ok) tr(R.string.dev_saved_to_downloads) else tr(R.string.dev_save_failed), Toast.LENGTH_SHORT).show()
                } else createDocument.launch(fileName())
            }) { Text(tr(R.string.dev_download_log)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (crash) CrashLog.clear() else Logger.clear(); version++ }) { Text(tr(R.string.dev_clear_log)) }
            TextButton(onClick = { version++ }) { Text(tr(R.string.dev_refresh)) }
        }
        if (lines.isEmpty()) Text(if (!crash && level == LogLevel.OFF) tr(R.string.dev_log_is_off) else tr(R.string.dev_log_empty))
        else {
            Text(tr(R.string.dev_showing_last_lines, VISIBLE_LINES), style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
                items(lines.size) { Text(lines[it], fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
            }
        }
    }
}
