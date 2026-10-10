package org.routevpn.android

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "For developers" settings page: log level selection and the entry point to the log. */
@Composable fun DeveloperSection(openLog: () -> Unit) {
    var level by remember { mutableStateOf(Logger.level) }
    Text(tr(R.string.dev_log_level), style = MaterialTheme.typography.titleMedium)
    listOf(LogLevel.OFF to tr(R.string.dev_level_off), LogLevel.ERROR to "ERROR", LogLevel.WARN to "WARN", LogLevel.INFO to "INFO",
        LogLevel.DEBUG to "DEBUG", LogLevel.VERBOSE to "VERBOSE").forEach { (value, label) ->
        Row(Modifier.fillMaxWidth().clickable { level = value; Logger.level = value }) {
            RadioButton(level == value, { level = value; Logger.level = value })
            Text(label, modifier = Modifier.padding(top = 13.dp))
        }
    }
    Text(tr(R.string.dev_log_privacy_note), style = MaterialTheme.typography.bodySmall)
    Card(Modifier.fillMaxWidth().clickable(onClick = openLog)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tr(R.string.dev_log), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable fun LogViewer() {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(version) { text = withContext(Dispatchers.IO) { Logger.read().takeLast(100_000) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", Logger.shareFile())
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Moth log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, tr(R.string.dev_share_log)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }) { Text(tr(R.string.dev_share_log)) }
        OutlinedButton(onClick = { Logger.clear(); version++ }) { Text(tr(R.string.dev_clear_log)) }
        TextButton(onClick = { version++ }) { Text(tr(R.string.dev_refresh)) }
    }
    if (Logger.level == LogLevel.OFF) Text(tr(R.string.dev_log_is_off), style = MaterialTheme.typography.bodySmall)
    if (text.isEmpty()) Text(tr(R.string.dev_log_empty))
    else Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
}
