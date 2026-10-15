package org.mothvpn.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.mothvpn.core.Profile
import org.mothvpn.core.ServerEditor

/** Developer option: full-screen editor for a server's stored outbound JSON. [save] receives the validated, compact JSON. */
@Composable fun ServerJsonEditor(profile: Profile, cancel: () -> Unit, save: (String) -> Unit) {
    var text by remember(profile.id) { mutableStateOf(ServerEditor.pretty(profile)) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.edit_json_title), style = MaterialTheme.typography.titleLarge)
                Text(profile.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr(R.string.edit_json_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), isError = error != null)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = cancel) { Text(tr(R.string.msg_cancel_32)) }
                    Button(onClick = {
                        when (val r = ServerEditor.validate(text, profile.engine)) {
                            is ServerEditor.Result.Ok -> save(r.outbound)
                            is ServerEditor.Result.Invalid -> error = tr(when (r.error) {
                                ServerEditor.Error.EMPTY -> R.string.edit_json_err_empty
                                ServerEditor.Error.TOO_LARGE -> R.string.edit_json_err_large
                                ServerEditor.Error.NOT_OBJECT -> R.string.edit_json_err_object
                                ServerEditor.Error.NO_TYPE -> R.string.edit_json_err_type
                                ServerEditor.Error.NO_PROTOCOL -> R.string.edit_json_err_protocol
                            })
                        }
                    }) { Text(tr(R.string.edit_json_save)) }
                }
            }
        }
    }
}
