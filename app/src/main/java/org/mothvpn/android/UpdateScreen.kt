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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
    // One quiet status under the version in its card: what is happening now or the result of the last check ("Update available" when
    // one was found; its version is on the right); errors are shown by the top notice.
    val status = when {
        phase is org.mothvpn.core.UpdatePhase.Downloading -> tr(R.string.update_downloading) + (progress?.let { " ${(it * 100).toInt().coerceIn(0, 100)} %" } ?: "")
        busy && release == null -> tr(R.string.update_checking)
        // A found update waiting for Install: its version is on the right, under the installed one.
        !busy && release != null -> tr(R.string.update_available_dot)
        message.isNotEmpty() -> message
        else -> ""
    }
    // The page never scrolls. The bottom block (the version card with the status under its label, the channel row and the main
    // button) keeps its natural height; the moth takes the rest of the height above it, up to the largest Home size. On a short area
    // it shrinks and, below MothArtworkSize.MIN_PAGE_DRAWING, is not shown. The main button itself is the Cancel while a check or
    // download runs, so nothing else sits between the moth and the cards.
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Not Home's formula (mothArtworkSize): that one keeps 560 dp × font scale free for Home's status, cards, server bar and
            // tabs, which this page does not have, so with a larger font it left the moth small above empty space. Here the moth fills
            // the free height, up to the largest Home moth and the page width.
            val artwork = org.mothvpn.core.MothArtworkSize.pageArtwork(maxWidth.value)
            val side = org.mothvpn.core.MothArtworkSize.fitPageDrawing(artwork, (maxHeight - 16.dp).value).dp
            LaunchedEffect(side, maxHeight) { Logger.i("Update", "moth ${side.value.toInt()} dp in a ${maxHeight.value.toInt()} dp area") }
            // The connected moth, slowly beating its wings (faster while checking or downloading); a tap makes it flap. A picture, not a button.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (side > 0.dp) MothFlutter(Modifier.requiredSize(side), size = side, busy = busy, exact = true)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // One version row, no extra row or window: the installed version on the right and, when an update is found, the new
            // version under it (so it is clear what updates to what); the status in small grey text under the label (it may wrap;
            // the card grows and the moth gives way smoothly).
            val available = release
            Card(Modifier.fillMaxWidth(), colors = ndCardColors()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (animationsOn()) Modifier.animateContentSize() else Modifier)) {
                    VersionRow(tr(R.string.update_installed_row), BuildConfig.VERSION_NAME, available?.version, tr(R.string.update_available_row), status)
                }
            }
            DialogRow(tr(R.string.update_channel), channels.first { it.first == channel }.second, enabled = !busy) { channelDialog = true }
            // One main action at the very bottom: Cancel while a check or download runs (it replaces the button just tapped), otherwise
            // install a found update or check for one.
            if (busy) {
                NdButton(onClick = { job?.cancel(); job = null; message = ""; on(org.mothvpn.core.UpdatePhase.Event.CANCELLED) }, modifier = Modifier.pressScale().then(Modifier.fillMaxWidth().heightIn(min = 52.dp))) { Text(tr(R.string.update_cancel)) }
            } else if (available != null) {
                NdButton(enabled = !busy, onClick = { action { proceed(available) } }, modifier = Modifier.pressScale(enabled = !busy).then(Modifier.fillMaxWidth().heightIn(min = 52.dp))) { Text(tr(R.string.update_install)) }
            } else {
                NdButton(enabled = !busy, onClick = { check() }, modifier = Modifier.pressScale(enabled = !busy).then(Modifier.fillMaxWidth().heightIn(min = 52.dp))) { Text(tr(R.string.update_check)) }
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
@Composable private fun VersionRow(label: String, value: String, newer: String?, newerLabel: String, status: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            // The status, like the value under a settings row's title (Update channel / Beta), in grey; read out when it changes.
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            // A found update: its version right under the installed one, bare (no arrow, so it stays narrow), in the accent colour.
            androidx.compose.animation.AnimatedVisibility(newer != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                val shown = remember { mutableStateOf(newer.orEmpty()) }
                if (newer != null) shown.value = newer
                Text(shown.value, style = MaterialTheme.typography.titleSmall, maxLines = 1, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = "$newerLabel ${shown.value}" })
            }
        }
    }
}
