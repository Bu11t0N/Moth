package org.mothvpn.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
    // Errors go to the app-wide top notice (full text, never cut under the moth); the line under the moth keeps only short statuses.
    val notices = LocalNotice.current
    fun fail(text: String) { message = ""; notices.show(text, NoticeState.ERROR_MS) }
    var warning by remember { mutableStateOf<File?>(null) }
    var waiting by remember { mutableStateOf<Pair<AppRelease, File>?>(null) }
    fun install(file: File) {
        Logger.i("Update", "install requested: ${file.length()} bytes")
        try { AppUpdater.install(context, file) } catch (e: Exception) { Logger.w("Update", "install failed", e); fail(tr(R.string.update_failed)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = context.packageManager.canRequestPackageInstalls()
        Logger.i("Update", "install-unknown-apps permission ${if (granted) "granted" else "not granted"}")
        val pending = waiting; waiting = null
        if (granted && pending != null) { message = tr(R.string.update_permission_ready); install(pending.second) }
        else { fail(tr(R.string.update_permission_needed)); on(org.mothvpn.core.UpdatePhase.Event.DIALOG_DISMISSED) }
    }
    fun action(block: suspend () -> Unit) {
        job = scope.launch {
            on(org.mothvpn.core.UpdatePhase.Event.CHECK); message = ""
            try { block() }
            catch (e: CancellationException) { Logger.i("Update", "update action cancelled"); message = ""; on(org.mothvpn.core.UpdatePhase.Event.CANCELLED); throw e }
            catch (e: Exception) { Logger.w("Update", "update action failed", e); fail(tr(R.string.update_failed)); on(org.mothvpn.core.UpdatePhase.Event.FAILED) }
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
    val channels = listOf(UpdateChannel.RELEASE to tr(R.string.update_channel_release), UpdateChannel.BETA to tr(R.string.update_channel_beta))
    var channelDialog by remember { mutableStateOf(false) }
    // One quiet status line under the moth: what is happening now or the result of the last check. A found update is not repeated
    // there (its version is in the card below); errors are shown by the top notice.
    val status = when {
        phase is org.mothvpn.core.UpdatePhase.Downloading -> tr(R.string.update_downloading) + (progress?.let { " ${(it * 100).toInt().coerceIn(0, 100)} %" } ?: "")
        busy && release == null -> tr(R.string.update_checking)
        message.isNotEmpty() -> message
        else -> ""
    }
    // The page never scrolls and the moth has one size in every state (idle, checking, update found, downloading): its area is sized
    // once from the page height minus the room the bottom block needs in its tallest state (version card with two rows, channel row,
    // "check again" and the main button), so the moth neither shrinks nor moves when that block grows. Low under the moth, just above the version card: the
    // status line and, while checking or downloading, a small Cancel. The bottom block is pinned to the bottom; the main button never moves.
    val motion = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntSize>(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val type = MaterialTheme.typography
    // Text heights follow the font size; the rest is fixed dp (see the layout below).
    val sp = { u: androidx.compose.ui.unit.TextUnit -> with(density) { u.toDp() } }
    val statusHeight = 12.dp + sp(type.bodyLarge.lineHeight) * 2
    val below = statusHeight + 44.dp // status (two lines) + Cancel under the moth
    val cardRow = maxOf(52.dp, 20.dp + sp(type.titleMedium.lineHeight))
    val reserve = 8.dp + (8.dp + cardRow * 2 + 1.dp) + 12.dp + (32.dp + sp(type.bodyLarge.lineHeight) + sp(type.bodySmall.lineHeight)) +
        12.dp + 48.dp + 12.dp + 52.dp + 8.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val top = (maxHeight - reserve).coerceAtLeast(0.dp)
    // The moth fills the room above the status block (larger than before); one size in every state, never squashed (requiredSize: a
    // tighter parent clips it a little instead of shrinking or flattening it).
    val side = minOf(maxWidth * 0.9f, top - below - 8.dp, 380.dp)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.weight(1f, fill = false).height(top).fillMaxWidth().clipToBounds()) {
            // The connected moth, slowly beating its wings (faster while checking or downloading); a tap makes it flap. A picture, not a button.
            Box(Modifier.fillMaxSize().padding(bottom = below), contentAlignment = Alignment.Center) {
                if (side >= 48.dp) MothFlutter(Modifier.requiredSize(side), size = side, busy = busy)
            }
            // The status line and Cancel sit low, at the bottom of the moth area (just above the version card), not right under the wings.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Fixed height (two lines), so a status appearing or changing never moves the moth; statuses are short, errors use the top notice.
                Box(Modifier.fillMaxWidth().height(statusHeight).padding(start = 16.dp, end = 16.dp, top = 12.dp), contentAlignment = Alignment.TopCenter) {
                    Text(status, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).semantics { liveRegion = LiveRegionMode.Polite })
                }
                // Cancel keeps its place (invisible when idle), so the moth and the status never jump.
                Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(busy, enter = fadeIn(), exit = fadeOut()) {
                        TextButton(onClick = { job?.cancel(); job = null; message = ""; on(org.mothvpn.core.UpdatePhase.Event.CANCELLED) }, modifier = Modifier.pressScale()) {
                            Text(tr(R.string.update_cancel))
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(motion), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth(), colors = ndCardColors()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    VersionRow(tr(R.string.update_installed_row), BuildConfig.VERSION_NAME)
                    AnimatedVisibility(release != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            VersionRow(tr(R.string.update_available_row), release?.version.orEmpty())
                        }
                    }
                }
            }
            DialogRow(tr(R.string.update_channel), channels.first { it.first == channel }.second, enabled = !busy) { channelDialog = true }
            // One main action at the very bottom: install a found update, otherwise check again ("check again" goes above it when an update is found).
            val available = release
            if (available != null) {
                TextButton(enabled = !busy, onClick = { check() }, modifier = Modifier.pressScale(enabled = !busy).fillMaxWidth()) { Text(tr(R.string.update_check)) }
                NdButton(enabled = !busy, onClick = { action { proceed(available) } }, modifier = Modifier.pressScale(enabled = !busy).then(Modifier.fillMaxWidth().heightIn(min = 52.dp))) { Text(tr(R.string.update_install)) }
            } else {
                NdButton(enabled = !busy, onClick = { check() }, modifier = Modifier.pressScale(enabled = !busy).then(Modifier.fillMaxWidth().heightIn(min = 52.dp))) { Text(tr(R.string.update_check)) }
            }
        }
    }
    }
    if (channelDialog) ChoiceDialog(tr(R.string.update_channel), channels, channel, { channelDialog = false }) { value ->
        if (channel != value) { Logger.i("Settings", "update channel changed: ${channel.id} -> ${value.id}"); selectChannel(value.id) }
    }
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
}

/** A row of the version card: the label on the left, the version on the right (same look as the Data pages' rows). */
@Composable private fun VersionRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}
