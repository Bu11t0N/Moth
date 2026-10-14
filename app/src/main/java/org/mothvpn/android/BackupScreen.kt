package org.mothvpn.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mothvpn.core.Backup
import org.mothvpn.core.ClientSettings
import org.mothvpn.core.SubscriptionBook

/** Reads a document picked with SAF as text, at most [max] characters (a larger file is rejected, not cut). */
fun readDocument(context: android.content.Context, uri: android.net.Uri, max: Int): String {
    val input = context.contentResolver.openInputStream(uri) ?: error("open failed")
    return input.use { s ->
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
        while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); require(out.size() <= max) { tr(R.string.backup_too_large) } }
        String(out.toByteArray(), Charsets.UTF_8)
    }
}
fun writeDocument(context: android.content.Context, uri: android.net.Uri, text: String) {
    (context.contentResolver.openOutputStream(uri, "wt") ?: error("open failed")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
}

/**
 * Settings → Backup. Export: everything in one JSON file (optionally password-encrypted). Import: validate, show what is inside,
 * then replace the current data. The file contents (links, keys, rules) are never logged.
 */
@Composable fun BackupPage(settings: ClientSettings, locked: Boolean, restore: (Backup.Content) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf<String?>(null) }   // an encrypted file waiting for its password
    var pending by remember { mutableStateOf<Backup.Content?>(null) } // a valid backup waiting for confirmation
    fun fail(e: Throwable) {
        Logger.w("Backup", "failed: ${e.javaClass.simpleName}") // no message: it could quote file contents
        status = when (e) {
            is Backup.WrongPassword -> tr(R.string.backup_wrong_password)
            is IllegalArgumentException, is org.json.JSONException -> e.message?.takeIf { e is IllegalArgumentException && !it.contains("://") }?.let(::localizedError) ?: tr(R.string.backup_invalid)
            else -> tr(R.string.backup_failed)
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val password = exportPassword; exportPassword = ""
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            try {
                val book = withContext(Dispatchers.IO) { SubscriptionStore(context).loadBook() } ?: SubscriptionBook(emptyList())
                val content = Backup.Content(settings, book, BuildConfig.VERSION_NAME, System.currentTimeMillis())
                val text = withContext(Dispatchers.Default) { Backup.export(content, password.ifEmpty { null }) }
                withContext(Dispatchers.IO) { writeDocument(context, uri, text) }
                val s = Backup.summary(content)
                Logger.i("Backup", "exported (encrypted=${password.isNotEmpty()}, subscriptions=${s.subscriptions}, servers=${s.servers}, rules=${s.rules})")
                status = tr(R.string.backup_exported)
            } catch (e: Exception) { fail(e) } finally { working = false }
        }
    }
    fun open(text: String, password: String?) {
        working = true
        scope.launch {
            try { pending = withContext(Dispatchers.Default) { Backup.read(text, password) }; pendingText = null; status = "" }
            catch (e: Backup.PasswordRequired) { pendingText = text }
            catch (e: Exception) { fail(e) } finally { working = false }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching { withContext(Dispatchers.IO) { readDocument(context, uri, Backup.MAX_BYTES) } }.onFailure { fail(it) }.getOrNull() ?: return@launch
            if (Backup.encrypted(text)) pendingText = text else open(text, null)
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(R.string.backup_hint), style = MaterialTheme.typography.bodyMedium)
        Button(enabled = !working, onClick = { exportPassword = ""; exportDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.backup_export)) }
        if (locked) Text(tr(R.string.backup_locked), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !working && !locked, onClick = { status = ""; pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.backup_import)) }
        Text(tr(R.string.backup_import_hint), style = MaterialTheme.typography.bodySmall)
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }
    if (exportDialog) {
        var password by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        val ok = password == repeat && (password.isEmpty() || password.length >= 6)
        AlertDialog(onDismissRequest = { exportDialog = false }, title = { Text(tr(R.string.backup_export)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.backup_secret_warning), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(password, { password = it }, label = { Text(tr(R.string.backup_password_optional)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (password.isNotEmpty()) OutlinedTextField(repeat, { repeat = it }, label = { Text(tr(R.string.backup_password_repeat)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), isError = !ok, modifier = Modifier.fillMaxWidth())
                Text(tr(if (password.isEmpty()) R.string.backup_plain_note else R.string.backup_password_note), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = ok, onClick = {
            exportDialog = false; exportPassword = password
            create.launch("moth-backup-${java.time.LocalDate.now()}.json")
        }) { Text(tr(R.string.backup_save_file)) } }, dismissButton = { TextButton(onClick = { exportDialog = false }) { Text(tr(R.string.msg_cancel_32)) } })
    }
    pendingText?.let { text ->
        var password by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pendingText = null }, title = { Text(tr(R.string.backup_password_title)) }, text = {
            OutlinedTextField(password, { password = it }, label = { Text(tr(R.string.backup_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        }, confirmButton = { TextButton(enabled = password.isNotEmpty() && !working, onClick = { open(text, password) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pendingText = null }) { Text(tr(R.string.msg_cancel_32)) } })
    }
    pending?.let { c ->
        val s = Backup.summary(c)
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(tr(R.string.backup_confirm_title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(R.string.backup_summary, s.subscriptions, s.servers, s.localServers, s.favorites, s.rules))
                if (c.appVersion.isNotBlank()) Text(tr(R.string.backup_from_version, c.appVersion), style = MaterialTheme.typography.bodySmall)
                Text(tr(R.string.backup_replace_warning), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = !locked, onClick = { pending = null; restore(c); status = tr(R.string.backup_restored) }) { Text(tr(R.string.backup_replace)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tr(R.string.msg_cancel_32)) } })
    }
}
