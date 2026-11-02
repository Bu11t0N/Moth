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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import org.mothvpn.core.ClientSettings

/**
 * The first-run flow. Shown once: on the first launch with no saved subscription. The "done" mark is a plain flag of its own (not part
 * of the settings or backups); an existing install that already has subscriptions is marked done silently. The developer page can show
 * the flow again without touching the flag or any data.
 *
 * Steps: [TUTORIAL] short feature pages in a pager (swipe left/right or Next; Skip jumps to the permissions; the auto-select page has
 * the real auto-select switch, the split tunneling page opens the real split tunneling page over the tour) → the permissions page (VPN, notifications on Android 13+,
 * battery optimization, keeping Moth alive in the background; every request is optional and a denial only shows a hint) → the
 * "add your first subscription" page (left out when a subscription already exists, e.g. when reopened from the developer page).
 */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val DONE = "done"
    /** The feature pages before the permissions page. */
    const val TUTORIAL = 3
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
    TutorialPage(Icons.AutoMirrored.Outlined.CallSplit, R.string.split_title, R.string.onboard_t_split))
private const val PAGE_AUTO = 1
private const val PAGE_SPLIT = 2

/**
 * [add]: the link typed or pasted; [scan]: open the QR scanner; [skip]: close the flow without adding ([how] says from where, for the
 * log). [hasSubscription]: the subscription page is left out and the permissions page finishes the flow. [settings]/[save]: the app's
 * settings (the auto-select switch and the split tunneling page change the same values as Settings, saved at once).
 */
@Composable fun OnboardingScreen(add: (String) -> Unit, scan: () -> Unit, skip: (how: String) -> Unit, hasSubscription: Boolean = false,
    settings: ClientSettings = ClientSettings(), save: (ClientSettings) -> Unit = {}) {
    // Stage: 0 the tour (pager), 1 permissions, 2 subscription.
    val last = if (hasSubscription) 1 else 2
    var stage by rememberSaveable { mutableIntStateOf(0) }
    var splitOpen by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState { Onboarding.TUTORIAL }
    val scope = rememberCoroutineScope()
    val anim = animationsOn()
    fun go(to: Int) { Logger.d("Onboarding", "stage $stage → $to"); stage = to }
    fun page(to: Int) { scope.launch { if (anim) pager.animateScrollToPage(to) else pager.scrollToPage(to) } }
    BackHandler(enabled = !splitOpen) { when { stage > 0 -> go(stage - 1); pager.currentPage > 0 -> page(pager.currentPage - 1); else -> skip("back") } }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { Logger.d("Onboarding", "tour page ${it + 1}") } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(stage, label = "onboarding", transitionSpec = {
            if (!anim) fadeIn(Motion.tweenOr(false)) togetherWith fadeOut(Motion.tweenOr(false))
            else { val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(Motion.tweenOr(true, Motion.MEDIUM)) { it * dir / 4 } + fadeIn(Motion.tweenOr(true, Motion.MEDIUM))) togetherWith
                    (slideOutHorizontally(Motion.tweenOr(true, Motion.MEDIUM)) { -it * dir / 4 } + fadeOut(Motion.tweenOr(true))) }
        }) { s ->
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
                when (s) {
                    0 -> TutorialStep(pager, settings, save,
                        next = { if (pager.currentPage < Onboarding.TUTORIAL - 1) page(pager.currentPage + 1) else go(1) },
                        skipAll = { Logger.i("Onboarding", "tutorial skipped at page ${pager.currentPage + 1}"); go(1) },
                        openSplit = { Logger.i("Onboarding", "split tunneling opened from the tour"); splitOpen = true })
                    1 -> PermissionsStep(done = hasSubscription, next = { if (last == 1) skip("done") else go(2) })
                    else -> SubscriptionStep(add, scan) { skip("skip") }
                }
            }
        }
        // The real split tunneling page over the tour; Back (or the arrow) returns to the split tunneling tour page.
        AnimatedVisibility(splitOpen, enter = fadeIn(Motion.tweenOr(anim)) + slideInHorizontally(Motion.tweenOr(anim)) { if (anim) it / 8 else 0 },
            exit = fadeOut(Motion.tweenOr(anim)) + slideOutHorizontally(Motion.tweenOr(anim)) { if (anim) it / 8 else 0 }) {
            BackHandler { splitOpen = false }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { splitOpen = false }, modifier = Modifier.pressScale()) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr(R.string.onboard_back)) }
                        Text(tr(R.string.split_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    }
                    val tunnel by TunnelStatus.state.collectAsState()
                    SplitTunnelPage(settings, tunnel !in setOf(TunnelState.STARTING, TunnelState.STOPPING), save, Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp))
                }
            }
        }
    }
}

/** A narrow centred column shared by the steps: content scrolls if the screen is small, the buttons stay at the bottom. */
@Composable private fun StepFrame(top: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}, buttons: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 480.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(48.dp)) { top() }
        StepBody(Modifier.weight(1f), content)
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { buttons() }
    }
}
@Composable private fun StepBody(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
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

/** The feature tour: the pages swipe left/right (the main way through); Skip at the top, page dots and Next at the bottom stay put. */
@Composable private fun TutorialStep(pager: androidx.compose.foundation.pager.PagerState, settings: ClientSettings, save: (ClientSettings) -> Unit,
    next: () -> Unit, skipAll: () -> Unit, openSplit: () -> Unit) {
    Column(Modifier.widthIn(max = 480.dp).fillMaxSize().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 24.dp)) {
            TextButton(onClick = skipAll, modifier = Modifier.align(Alignment.CenterEnd).pressScale()) { Text(tr(R.string.onboard_skip_tutorial)) }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { it }) { i ->
            val p = tutorial[i]
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StepBody(Modifier.weight(1f)) {
                    if (p.icon == null) MothFlutter(size = 148.dp, tappable = true)
                    else IconTile(p.icon, size = 96.dp)
                    Text(tr(p.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(tr(p.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                when (i) {
                    // One switch for auto-select on both Wi-Fi and mobile data (Settings › Connection keeps the two separate).
                    PAGE_AUTO -> {
                        val on = settings.autoSelectWifi || settings.autoSelectMobile
                        val toggle = { v: Boolean -> Logger.i("Onboarding", "auto-select ${if (v) "on" else "off"} from the tour"); save(settings.copy(autoSelectWifi = v, autoSelectMobile = v)) }
                        Card(Modifier.fillMaxWidth().padding(top = 12.dp).pressScale(), colors = ndCardColors()) {
                            Row(Modifier.fillMaxWidth().clickable { toggle(!on) }.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(tr(R.string.onboard_auto_switch), style = MaterialTheme.typography.titleMedium)
                                    Text(tr(R.string.onboard_auto_switch_sum), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                MothSwitch(checked = on, onCheckedChange = { toggle(it) })
                            }
                        }
                    }
                    PAGE_SPLIT -> {
                        val count = settings.splitApps.size
                        OutlinedButton(onClick = openSplit, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp).pressScale()) {
                            Text(if (count == 0) tr(R.string.onboard_split_open) else tr(R.string.onboard_split_open_count, count))
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PageDots(pager.currentPage, Onboarding.TUTORIAL + 1)
            Spacer(Modifier.height(12.dp))
            NdButton(onClick = next, modifier = Modifier.fillMaxWidth().height(52.dp).pressScale()) { Text(tr(R.string.onboard_next)) }
        }
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
