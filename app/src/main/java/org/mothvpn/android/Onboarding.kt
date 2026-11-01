package org.mothvpn.android

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The first-run screen ("add your first subscription"). Shown once: on the first launch with no saved subscription. The "done" mark is
 * a plain flag of its own (not part of the settings or backups); an existing install that already has subscriptions is marked done
 * silently. The developer page can show the screen again without touching the flag or any data.
 */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val DONE = "done"
    fun done(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DONE, false)
    fun markDone(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply() }
    /** Tests only: the next launch without subscriptions shows the first-run screen again. */
    fun reset(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DONE).apply() }
}

/** [add]: the link typed or pasted; [scan]: open the QR scanner; [skip]: close without adding. */
@Composable fun OnboardingScreen(add: (String) -> Unit, scan: () -> Unit, skip: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var empty by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    BackHandler { skip() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MothFlutter(size = 148.dp, tappable = true)
                Text(tr(R.string.onboard_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(tr(R.string.onboard_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(value = url, onValueChange = { url = it; empty = false }, label = { Text(tr(R.string.msg_subscription_link_30)) }, singleLine = true,
                    isError = empty, supportingText = if (empty) ({ Text(tr(R.string.import_clipboard_empty)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { url = clip.getText()?.text?.trim().orEmpty(); empty = url.isEmpty() }, modifier = Modifier.weight(1f).pressScale()) { Text(tr(R.string.import_paste)) }
                    OutlinedButton(onClick = scan, modifier = Modifier.weight(1f).pressScale()) { Text(tr(R.string.import_scan)) }
                }
                NdButton(onClick = { add(url.trim()) }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp).pressScale(enabled = url.isNotBlank())) { Text(tr(R.string.onboard_add)) }
                TextButton(onClick = skip, modifier = Modifier.pressScale()) { Text(tr(R.string.onboard_skip)) }
            }
        }
    }
}

/** The "update available" mark: a small dot with a ring in the colour behind it ([ring]), placed at the top-right corner of an icon. */
@Composable fun UpdateDot(modifier: Modifier = Modifier, ring: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface) {
    Box(modifier.size(9.dp)
        .background(ring, CircleShape).padding(1.5.dp)
        .background(MaterialTheme.colorScheme.onSurface, CircleShape)
        .semantics { contentDescription = tr(R.string.update_available_dot) })
}
