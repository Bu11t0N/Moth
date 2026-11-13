package org.mothvpn.android

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mothvpn.core.ClientSettings

private class AppEntry(val packageName: String, val label: String, val system: Boolean, val unlisted: Boolean = false)

/**
 * The apps split tunneling offers: by default only apps with an icon on the home screen / in the app drawer (<queries> MAIN/LAUNCHER in
 * the manifest); with [all] ("Elevated privileges", QUERY_ALL_PACKAGES) every installed package, also services without an icon.
 * Of those only the ones that can use the network (INTERNET permission). Moth itself is left out.
 * System apps are marked (shown behind "Show system apps"). Call off the main thread.
 */
private fun listedApps(context: android.content.Context, all: Boolean): List<AppEntry> {
    val pm = context.packageManager
    return listedPackages(context, all).map { (p, info) ->
        AppEntry(p.packageName, info.loadLabel(pm).toString(), info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
    }
}
private fun listedPackages(context: android.content.Context, all: Boolean): List<Pair<android.content.pm.PackageInfo, ApplicationInfo>> {
    val pm = context.packageManager
    if (all) {
        @Suppress("DEPRECATION")
        val installed = runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList())
        return installed.filter { it.packageName != context.packageName }.mapNotNull { p ->
            val info = p.applicationInfo ?: return@mapNotNull null
            if (p.requestedPermissions?.contains(android.Manifest.permission.INTERNET) != true) return@mapNotNull null
            p to info
        }
    }
    @Suppress("DEPRECATION")
    val launchable = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }.toSet()
    return launchable.filter { it != context.packageName }.mapNotNull { name ->
        @Suppress("DEPRECATION")
        val p = runCatching { pm.getPackageInfo(name, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: return@mapNotNull null
        val info = p.applicationInfo ?: return@mapNotNull null
        if (p.requestedPermissions?.contains(android.Manifest.permission.INTERNET) != true) return@mapNotNull null
        p to info
    }
}

/** Package names split tunneling lists ([listedApps], without loading labels); the Settings summary does not count selected apps outside it. */
fun splitListedPackages(context: android.content.Context, all: Boolean = false): Set<String> = listedPackages(context, all).map { it.first.packageName }.toSet()

/** Per-app split tunneling: mode dialog row, search and the list of home-screen apps that use the internet. */
@Composable fun SplitTunnelPage(saved: ClientSettings, enabledOff: Boolean, applyNow: (ClientSettings) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Editable while connected (both designs): every tap is saved at once; MainActivity reconnects once, 5 s after the last change.
    val enabled = enabledOff
    val settings = saved
    val apply: (ClientSettings) -> Unit = applyNow
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    LaunchedEffect(settings.splitAllApps) {
        apps = null
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val found = listedApps(context, settings.splitAllApps)
                // Apps chosen earlier that are no longer listed (uninstalled, or without a home-screen icon) stay in the list so they can be unchecked.
                (found + settings.splitApps.filter { id -> found.none { it.packageName == id } }.map { AppEntry(it, it, false, unlisted = true) })
                    .sortedBy { it.label.lowercase() }
            }.onFailure { Logger.w("Split", "listing apps failed", it) }.getOrDefault(emptyList())
        }
    }
    // Each mode card has its icon: a crossed-out shield for "selected apps bypass the VPN", a locked VPN mark for "only selected apps use the VPN".
    val modes = listOf("bypass" to tr(R.string.split_mode_bypass), "only" to tr(R.string.split_mode_only))
    val modeIcons = mapOf("bypass" to Icons.Outlined.RemoveModerator, "only" to Icons.Outlined.VpnLock)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The mode is chosen right on the page: two stacked cards, bypass (the default) first; the active one is shown only by its fill
        // and outline (the selected-server style), no check mark or radio button.
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = modes.firstOrNull { it.first == settings.splitMode }?.first ?: modes.first().first
            modes.forEach { (value, label) ->
                val chosen = current == value
                Card(onClick = { if (!chosen) apply(settings.copy(splitMode = value)) }, enabled = enabled,
                    modifier = Modifier.pressScale(enabled = enabled).fillMaxWidth().semantics { selected = chosen; role = androidx.compose.ui.semantics.Role.RadioButton },
                    colors = CardDefaults.cardColors(containerColor = if (chosen) NewDesignRow.selectedColor() else NdTone.card(),
                        disabledContainerColor = if (chosen) NewDesignRow.selectedColor() else NdTone.card()),
                    border = NewDesignRow.selectedBorder(chosen)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        modeIcons[value]?.let { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp),
                            tint = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        // The search field and the "Show system apps" and "Elevated privileges" rows step aside once the app list is scrolled down (more room for the apps) and
        // come back only at its very top. The per-app selection checkboxes in the list never hide.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // The two option rows show only while the list is at its very top (first item, offset 0); scrolled down at all → hidden.
        val hideOptions by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
        // The search field hides together with the two rows (same animation and timing) and comes back with them at the very top.
        // Fully hidden, AnimatedVisibility leaves no child in the column, so no extra gap stays above the list.
        androidx.compose.animation.AnimatedVisibility(!hideOptions, enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text(tr(R.string.split_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.pressScale().fillMaxWidth().clickable { showSystem = !showSystem }) {
                    Checkbox(showSystem, { showSystem = it }); Text(tr(R.string.split_show_system))
                }
                // "Elevated privileges": list every installed app (QUERY_ALL_PACKAGES), also those without a launcher icon.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.pressScale(enabled = enabled).fillMaxWidth().clickable(enabled = enabled) { apply(settings.copy(splitAllApps = !settings.splitAllApps)) }) {
                    Checkbox(settings.splitAllApps, { apply(settings.copy(splitAllApps = it)) }, enabled = enabled)
                    TitleWithInfo(tr(R.string.split_all_apps), tr(R.string.split_all_apps_info), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        // Selected apps on top. The order is a snapshot so rows don't jump under the finger: it is refreshed when the page opens, when the
        // search or the system-apps box changes.
        var onTop by remember { mutableStateOf(settings.splitApps.toSet()) }
        LaunchedEffect(search, showSystem) { onTop = settings.splitApps.toSet() }
        val list = apps?.let { all -> remember(all, onTop) { all.sortedBy { it.packageName !in onTop } } }
        if (list == null) {
            // The same loading moth as Home, the changelog and Updates, sized to the free space under the mode cards.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val moth = mothArtworkSize()
                val side = minOf(moth, maxWidth, maxHeight)
                val free = (maxHeight - side).coerceAtLeast(0.dp)
                Box(Modifier.padding(top = free * 0.35f).size(side), contentAlignment = Alignment.Center) {
                    MothLoading(size = side * org.mothvpn.core.MothArtworkSize.DRAWING_RATIO, exact = true)
                }
            }
        } else {
            val shown = list.filter { (showSystem || !it.system || it.packageName in settings.splitApps) && (search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true)) }
            // The selection checkbox is always shown, bound to the saved selection (settings.splitApps) and keyed by package name, so a row
            // keeps its checkmark while the list scrolls. (1.0.0-beta.3..5 wrongly hid these instead of the two option rows above.)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                items(shown, key = { it.packageName }) { app ->
                    val checked = app.packageName in settings.splitApps
                    fun toggle() { apply(settings.copy(splitApps = if (checked) settings.splitApps - app.packageName else (settings.splitApps + app.packageName).distinct().sorted())) }
                    Row(Modifier.pressScale(enabled = enabled).fillMaxWidth().clickable(enabled = enabled) { toggle() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon by produceState<ImageBitmap?>(null, app.packageName) {
                            value = withContext(Dispatchers.IO) { runCatching { pm.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                        }
                        Box(Modifier.size(40.dp)) { icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) } }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (app.unlisted) tr(R.string.split_unlisted) else app.packageName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Checkbox(checked, { toggle() }, enabled = enabled)
                    }
                }
            }
        }
    }
}

/** Settings row that opens a dialog (same look as the language and sort rows). */
@Composable fun DialogRow(title: String, value: String, enabled: Boolean = true, info: String? = null, open: () -> Unit) {
    Card(Modifier.pressScale(enabled = enabled).fillMaxWidth().clickable(enabled = enabled, onClick = open), colors = ndCardColors()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The small ⓘ right after the title (instead of a full icon button before the arrow).
                TitleWithInfo(title, info)
                if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, current: T, close: () -> Unit, choose: (T) -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Column {
        options.forEach { (value, label) ->
            Row(Modifier.pressScale().fillMaxWidth().clickable { close(); choose(value) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(current == value, { close(); choose(value) }); Text(label)
            }
        }
    } }, confirmButton = { TextButton(onClick = close, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
}
