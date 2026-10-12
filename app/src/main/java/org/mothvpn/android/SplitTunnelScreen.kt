package org.mothvpn.android

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mothvpn.core.ClientSettings

private class AppEntry(val packageName: String, val label: String, val system: Boolean)

/** Per-app split tunneling: mode dialog row, search and the list of installed apps that use the internet. */
@Composable fun SplitTunnelPage(settings: ClientSettings, enabled: Boolean, apply: (ClientSettings) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var modeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                // Only apps with a launcher icon are visible to Moth (<queries> MAIN/LAUNCHER in the manifest, no QUERY_ALL_PACKAGES).
                @Suppress("DEPRECATION")
                val launchable = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName }.toSet()
                val found = launchable.filter { it != context.packageName }.mapNotNull { name ->
                    @Suppress("DEPRECATION")
                    val p = runCatching { pm.getPackageInfo(name, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: return@mapNotNull null
                    val info = p.applicationInfo ?: return@mapNotNull null
                    if (p.requestedPermissions?.contains(android.Manifest.permission.INTERNET) != true) return@mapNotNull null
                    AppEntry(p.packageName, info.loadLabel(pm).toString(), info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                }
                // Apps chosen earlier that are no longer visible (no launcher icon) stay in the list so they can be unchecked.
                (found + settings.splitApps.filter { id -> found.none { it.packageName == id } }.map { AppEntry(it, it, false) })
                    .sortedWith(compareBy({ it.packageName !in settings.splitApps }, { it.label.lowercase() }))
            }.onFailure { Logger.w("Split", "listing apps failed", it) }.getOrDefault(emptyList())
        }
    }
    val modes = listOf("off" to tr(R.string.split_mode_off), "only" to tr(R.string.split_mode_only), "bypass" to tr(R.string.split_mode_bypass))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!enabled) Text(tr(R.string.msg_network_settings_are_available_after_disconne_88))
        DialogRow(tr(R.string.split_mode), modes.first { it.first == settings.splitMode }.second, enabled) { modeDialog = true }
        Text(tr(R.string.split_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(search, { search = it }, label = { Text(tr(R.string.split_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { showSystem = !showSystem }) {
            Checkbox(showSystem, { showSystem = it }); Text(tr(R.string.split_show_system))
        }
        val list = apps
        if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            val shown = list.filter { (showSystem || !it.system || it.packageName in settings.splitApps) && (search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true)) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(shown, key = { it.packageName }) { app ->
                    val checked = app.packageName in settings.splitApps
                    fun toggle() { apply(settings.copy(splitApps = if (checked) settings.splitApps - app.packageName else (settings.splitApps + app.packageName).distinct().sorted())) }
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { toggle() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon by produceState<ImageBitmap?>(null, app.packageName) {
                            value = withContext(Dispatchers.IO) { runCatching { pm.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                        }
                        Box(Modifier.size(40.dp)) { icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) } }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Checkbox(checked, { toggle() }, enabled = enabled)
                    }
                }
            }
        }
    }
    if (modeDialog) ChoiceDialog(tr(R.string.split_mode), modes, settings.splitMode, { modeDialog = false }) { apply(settings.copy(splitMode = it)) }
}

/** Settings row that opens a dialog (same look as the language and sort rows). */
@Composable fun DialogRow(title: String, value: String, enabled: Boolean = true, open: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = open)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, current: T, close: () -> Unit, choose: (T) -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Column {
        options.forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().clickable { close(); choose(value) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(current == value, { close(); choose(value) }); Text(label)
            }
        }
    } }, confirmButton = { TextButton(onClick = close) { Text(tr(R.string.msg_cancel_32)) } })
}
