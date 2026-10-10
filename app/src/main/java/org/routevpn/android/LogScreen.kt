package org.routevpn.android

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
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
import org.routevpn.core.LogText

private const val VISIBLE_LINES = 400

/** "For developers" page: a single entry that opens the log page. */
@Composable fun DeveloperSection(openLog: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = openLog)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tr(R.string.dev_log), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Level selector, Log / Crash log tabs, Share / Clear / Refresh and a lazily rendered tail of the selected log. */
@Composable fun LogPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(Logger.level) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(version, tab) {
        lines = withContext(Dispatchers.IO) { LogText.lastLines(if (tab == 0) Logger.read() else CrashLog.read(), VISIBLE_LINES) }
    }
    val state = rememberLazyListState()
    LaunchedEffect(lines) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(R.string.dev_log_level), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(LogLevel.OFF to tr(R.string.dev_level_off), LogLevel.ERROR to "ERROR", LogLevel.WARN to "WARN", LogLevel.INFO to "INFO",
                LogLevel.DEBUG to "DEBUG", LogLevel.VERBOSE to "VERBOSE").forEach { (value, label) ->
                FilterChip(selected = level == value, onClick = { level = value; Logger.level = value }, label = { Text(label) })
            }
        }
        Text(tr(R.string.dev_log_privacy_note), style = MaterialTheme.typography.bodySmall)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr(R.string.dev_log)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr(R.string.dev_crash_log)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val file = if (tab == 0) Logger.shareFile() else CrashLog.shareFile()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Moth log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, tr(R.string.dev_share_log)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }) { Text(tr(R.string.dev_share_log)) }
            OutlinedButton(onClick = { if (tab == 0) Logger.clear() else CrashLog.clear(); version++ }) { Text(tr(R.string.dev_clear_log)) }
            TextButton(onClick = { version++ }) { Text(tr(R.string.dev_refresh)) }
        }
        if (tab == 0 && level == LogLevel.OFF) Text(tr(R.string.dev_log_is_off), style = MaterialTheme.typography.bodySmall)
        if (lines.isEmpty()) Text(tr(R.string.dev_log_empty))
        else {
            Text(tr(R.string.dev_showing_last_lines, VISIBLE_LINES), style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
                items(lines.size) { Text(lines[it], fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
            }
        }
    }
}
