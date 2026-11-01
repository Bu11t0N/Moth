package org.mothvpn.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * The first-run flow. Shown once: on the first launch with no saved subscription. The "done" mark is a plain flag of its own (not part
 * of the settings or backups); an existing install that already has subscriptions is marked done silently. The developer page can show
 * the flow again without touching the flag or any data.
 *
 * Steps: [TUTORIAL] short feature pages (Skip jumps to the permissions) → the permissions page (VPN, notifications on Android 13+,
 * battery optimization, keeping Moth alive in the background; every request is optional and a denial only shows a hint) → the
 * "add your first subscription" page (left out when a subscription already exists, e.g. when reopened from the developer page).
 */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val DONE = "done"
    /** The feature pages before the permissions page. */
    const val TUTORIAL = 4
    fun done(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DONE, false)
    fun markDone(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply() }
    /** Tests only: the next launch without subscriptions shows the first-run flow again. */
    fun reset(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DONE).apply() }
    /** Xiaomi, Redmi and POCO phones (MIUI / HyperOS): their own battery saver and the lock in Recents matter most there. */
    fun xiaomi(): Boolean = Build.MANUFACTURER.lowercase().let { it == "xiaomi" || it == "redmi" || it == "poco" }
}

private class TutorialPage(val icon: ImageVector?, val title: Int, val text: Int)
private val tutorial = listOf(
    TutorialPage(null, R.string.onboard_title, R.string.onboard_t_connect),
    TutorialPage(Icons.Outlined.Speed, R.string.onboard_t_servers_title, R.string.onboard_t_servers),
    TutorialPage(Icons.AutoMirrored.Outlined.CallSplit, R.string.split_title, R.string.onboard_t_split),
    TutorialPage(Icons.Outlined.SystemUpdate, R.string.update_title, R.string.onboard_t_updates))

/**
 * [add]: the link typed or pasted; [scan]: open the QR scanner; [skip]: close the flow without adding ([how] says from where, for the
 * log). [hasSubscription]: the subscription page is left out and the permissions page finishes the flow.
 */
@Composable fun OnboardingScreen(add: (String) -> Unit, scan: () -> Unit, skip: (how: String) -> Unit, hasSubscription: Boolean = false) {
    val last = if (hasSubscription) Onboarding.TUTORIAL else Onboarding.TUTORIAL + 1
    var step by rememberSaveable { mutableIntStateOf(0) }
    fun go(to: Int) { Logger.d("Onboarding", "step $step → $to"); step = to }
    BackHandler { if (step == 0) skip("back") else go(step - 1) }
    val anim = animationsOn()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(step, label = "onboarding", transitionSpec = {
            if (!anim) fadeIn(Motion.tweenOr(false)) togetherWith fadeOut(Motion.tweenOr(false))
            else { val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(Motion.tweenOr(true, Motion.MEDIUM)) { it * dir / 4 } + fadeIn(Motion.tweenOr(true, Motion.MEDIUM))) togetherWith
                    (slideOutHorizontally(Motion.tweenOr(true, Motion.MEDIUM)) { -it * dir / 4 } + fadeOut(Motion.tweenOr(true))) }
        }) { s ->
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
                when {
                    s < Onboarding.TUTORIAL -> TutorialStep(s, next = { go(s + 1) }, skipAll = { Logger.i("Onboarding", "tutorial skipped at page ${s + 1}"); go(Onboarding.TUTORIAL) })
                    s == Onboarding.TUTORIAL -> PermissionsStep(done = hasSubscription, next = { if (s == last) skip("done") else go(s + 1) })
                    else -> SubscriptionStep(add, scan) { skip("skip") }
                }
            }
        }
    }
}

/** A narrow centred column shared by the steps: content scrolls if the screen is small, the buttons stay at the bottom. */
@Composable private fun StepFrame(top: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}, buttons: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 480.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(48.dp)) { top() }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { buttons() }
    }
}

/** Page dots: the current page filled, the others faint. */
@Composable private fun PageDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "${current + 1} / $count" }) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == current) 10.dp else 8.dp).background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (i == current) 0.9f else 0.25f), CircleShape))
        }
    }
}

@Composable private fun TutorialStep(page: Int, next: () -> Unit, skipAll: () -> Unit) {
    val p = tutorial[page]
    StepFrame(top = { TextButton(onClick = skipAll, modifier = Modifier.align(Alignment.CenterEnd).pressScale()) { Text(tr(R.string.onboard_skip_tutorial)) } },
        buttons = {
            PageDots(page, Onboarding.TUTORIAL + 1)
            Spacer(Modifier.height(12.dp))
            NdButton(onClick = next, modifier = Modifier.fillMaxWidth().height(52.dp).pressScale()) { Text(tr(R.string.onboard_next)) }
        }) {
        if (p.icon == null) MothFlutter(size = 148.dp, tappable = true)
        else IconTile(p.icon, size = 96.dp)
        Text(tr(p.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(tr(p.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun openSettings(context: Context, vararg intents: Intent) {
    for (intent in intents) if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    Logger.w("Onboarding", "no settings screen for ${intents.firstOrNull()?.action}")
}

/**
 * The permissions Moth already asks for elsewhere, each optional: the VPN connection (VpnService.prepare, as on the first connect),
 * notifications on Android 13+ (as when the VPN starts), the battery optimization list (the same Android screen as Settings › Battery;
 * no new permission) and keeping Moth in the background (the lock in Recents and, on Xiaomi, App info › Battery saver). States are
 * read again whenever the user comes back from Android's screens.
 */
@Composable private fun PermissionsStep(done: Boolean, next: () -> Unit) {
    val context = LocalContext.current
    val resumed = resumeCount()
    var asked by remember { mutableIntStateOf(0) }
    var vpnDenied by rememberSaveable { mutableStateOf(false) }
    var notifyDenied by rememberSaveable { mutableStateOf(false) }
    val vpnOk = remember(resumed, asked) { runCatching { VpnService.prepare(context) == null }.getOrDefault(false) }
    val notifyNeeded = Build.VERSION.SDK_INT >= 33
    val notifyOk = remember(resumed, asked) { !notifyNeeded || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val batteryOk = remember(resumed, asked) { runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() == true }
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = r.resultCode == android.app.Activity.RESULT_OK
        Logger.i("Permission", "first run: VPN permission ${if (ok) "granted" else "denied (code ${r.resultCode})"}"); vpnDenied = !ok; asked++
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Logger.i("Permission", "first run: notification permission ${if (ok) "granted" else "denied"}"); notifyDenied = !ok; asked++
    }
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
    val xiaomi = Onboarding.xiaomi()
    StepFrame(buttons = {
        PageDots(Onboarding.TUTORIAL, Onboarding.TUTORIAL + 1)
        Spacer(Modifier.height(12.dp))
        NdButton(onClick = next, modifier = Modifier.fillMaxWidth().height(52.dp).pressScale()) { Text(tr(if (done) R.string.onboard_done else R.string.onboard_continue)) }
    }) {
        Text(tr(R.string.onboard_perm_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(tr(R.string.onboard_perm_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        PermissionCard(Icons.Outlined.VpnKey, tr(R.string.onboard_perm_vpn), tr(if (vpnDenied && !vpnOk) R.string.onboard_perm_vpn_denied else R.string.onboard_perm_vpn_text), vpnOk,
            tr(R.string.onboard_allow)) {
            val intent = runCatching { VpnService.prepare(context) }.getOrNull()
            if (intent == null) asked++ else runCatching { vpn.launch(intent) }.onFailure { Logger.w("Permission", "first run: VPN permission screen unavailable", it); vpnDenied = true }
        }
        if (notifyNeeded) PermissionCard(Icons.Outlined.Notifications, tr(R.string.onboard_perm_notify),
            tr(if (notifyDenied && !notifyOk) R.string.onboard_perm_notify_denied else R.string.onboard_perm_notify_text), notifyOk,
            tr(if (notifyDenied) R.string.onboard_open_settings else R.string.onboard_allow)) {
            if (notifyDenied) openSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), appInfo)
            else notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermissionCard(Icons.Outlined.BatteryStd, tr(R.string.battery_optimization), tr(R.string.onboard_perm_battery_text), batteryOk, tr(R.string.onboard_open_settings)) {
            openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appInfo)
        }
        PermissionCard(Icons.Outlined.Lock, tr(R.string.onboard_perm_keep), tr(if (xiaomi) R.string.onboard_perm_keep_xiaomi else R.string.onboard_perm_keep_text), null,
            tr(R.string.onboard_app_info)) { openSettings(context, appInfo) }
    }
}

/** One permission: icon, title, short text, and either "Allowed" (when [granted]) or the [action] button. [granted] null: no state to show. */
@Composable private fun PermissionCard(icon: ImageVector, title: String, text: String, granted: Boolean?, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = ndCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(icon)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (granted == true) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr(R.string.onboard_allowed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (granted != true) OutlinedButton(onClick = onClick, modifier = Modifier.pressScale()) { Text(action) }
        }
    }
}

/** The last step: add the first subscription (paste, scan or type a link), or "Not now". */
@Composable private fun SubscriptionStep(add: (String) -> Unit, scan: () -> Unit, skip: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var empty by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    StepFrame(buttons = {
        NdButton(onClick = { add(url.trim()) }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp).pressScale(enabled = url.isNotBlank())) { Text(tr(R.string.onboard_add)) }
        TextButton(onClick = skip, modifier = Modifier.pressScale()) { Text(tr(R.string.onboard_skip)) }
    }) {
        MothFlutter(size = 120.dp, tappable = true)
        Text(tr(R.string.onboard_sub_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(tr(R.string.onboard_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        OutlinedTextField(value = url, onValueChange = { url = it; empty = false }, label = { Text(tr(R.string.msg_subscription_link_30)) }, singleLine = true,
            isError = empty, supportingText = if (empty) ({ Text(tr(R.string.import_clipboard_empty)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { url = clip.getText()?.text?.trim().orEmpty(); empty = url.isEmpty() }, modifier = Modifier.weight(1f).pressScale()) { Text(tr(R.string.import_paste)) }
            OutlinedButton(onClick = scan, modifier = Modifier.weight(1f).pressScale()) { Text(tr(R.string.import_scan)) }
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
