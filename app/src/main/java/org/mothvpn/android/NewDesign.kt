package org.mothvpn.android

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mothvpn.core.ClientSettings
import org.mothvpn.core.Favorites
import org.mothvpn.core.LatencyBand
import org.mothvpn.core.ProbeErrorKind
import org.mothvpn.core.Profile
import org.mothvpn.core.ServerOrder
import org.mothvpn.core.VersionLabel

/** True while the experimental design (Settings → For developers → New design) is on. The classic UI reads false. */
val LocalNewDesign = staticCompositionLocalOf { false }

/** The new design's tonal levels (see [MonoTheme]). */
object NdTone {
    @Composable fun background() = MaterialTheme.colorScheme.background
    @Composable fun sheet() = MaterialTheme.colorScheme.surfaceContainerLow
    @Composable fun card() = MaterialTheme.colorScheme.surfaceContainer
    @Composable fun raised() = MaterialTheme.colorScheme.surfaceContainerHigh
    @Composable fun highlight() = MaterialTheme.colorScheme.primaryContainer
}

/** Card colours: the new design's L1 card tone; the classic design keeps the Material default. */
@Composable fun ndCardColors() = if (LocalNewDesign.current) CardDefaults.cardColors(containerColor = NdTone.card()) else CardDefaults.cardColors()

/** Selected server row in the new design: no check mark, a stronger fill plus an outline so it stands out in both themes. */
object NewDesignRow {
    @Composable fun selectedColor(): Color = MaterialTheme.colorScheme.primaryContainer
    @Composable fun selectedBorder(chosen: Boolean): androidx.compose.foundation.BorderStroke? =
        if (chosen) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) else null
}

/**
 * Monochrome Material 3 schemes (black, white and greys; no dynamic/wallpaper colour). Errors stay red so warnings remain readable.
 * Tonal levels, each a visible step from the one below (dark: near-black up to greys; light: the inverse; AMOLED: black background, cards above it):
 * L0 background → sheets (surfaceContainerLow) → L1 cards (surfaceContainer: groups, settings, subscription) → L2 rows inside cards and sheets
 * (surfaceContainerHigh) → L3 highlighted / selected (primaryContainer). [NdTone] names them for the screens.
 */
object MonoTheme {
    val dark = darkColorScheme(
        primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF000000), primaryContainer = Color(0xFF3D3D3D), onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFBDBDBD), onSecondary = Color(0xFF000000), secondaryContainer = Color(0xFF2C2C2C), onSecondaryContainer = Color(0xFFF2F2F2),
        tertiary = Color(0xFFBDBDBD), onTertiary = Color(0xFF000000), tertiaryContainer = Color(0xFF2C2C2C), onTertiaryContainer = Color(0xFFF2F2F2),
        background = Color(0xFF0A0A0A), onBackground = Color(0xFFF2F2F2), surface = Color(0xFF0A0A0A), onSurface = Color(0xFFF2F2F2),
        surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFFA6A6A6), surfaceTint = Color(0xFFF2F2F2),
        inverseSurface = Color(0xFFF2F2F2), inverseOnSurface = Color(0xFF111111), inversePrimary = Color(0xFF111111),
        outline = Color(0xFF6E6E6E), outlineVariant = Color(0xFF333333), scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF363636), surfaceDim = Color(0xFF0A0A0A), surfaceContainerLowest = Color(0xFF050505), surfaceContainerLow = Color(0xFF141414),
        surfaceContainer = Color(0xFF1C1C1C), surfaceContainerHigh = Color(0xFF262626), surfaceContainerHighest = Color(0xFF313131))
    val light = lightColorScheme(
        primary = Color(0xFF111111), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFCFCFCF), onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFF555555), onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color(0xFF111111),
        tertiary = Color(0xFF555555), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFE6E6E6), onTertiaryContainer = Color(0xFF111111),
        background = Color(0xFFFBFBFB), onBackground = Color(0xFF111111), surface = Color(0xFFFBFBFB), onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF5E5E5E), surfaceTint = Color(0xFF111111),
        inverseSurface = Color(0xFF111111), inverseOnSurface = Color(0xFFF2F2F2), inversePrimary = Color(0xFFF2F2F2),
        outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFD6D6D6), scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDADADA), surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3F3),
        surfaceContainer = Color(0xFFEDEDED), surfaceContainerHigh = Color(0xFFE3E3E3), surfaceContainerHighest = Color(0xFFD8D8D8))
    /** Dark theme with "Pure black (AMOLED)": #000000 background and surfaces; cards and sheets stay a little lighter so they remain visible. */
    val amoled = dark.copy(background = Color(0xFF000000), surface = Color(0xFF000000), surfaceDim = Color(0xFF000000),
        surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF0F0F0F), surfaceContainer = Color(0xFF161616),
        surfaceContainerHigh = Color(0xFF1F1F1F), surfaceContainerHighest = Color(0xFF2A2A2A), surfaceVariant = Color(0xFF161616), surfaceBright = Color(0xFF2A2A2A),
        primaryContainer = Color(0xFF363636))
    /** Colours for the home hero card (the server bar): the inverse tone, with background = the card fill so ping colours and greys stay readable. */
    fun hero(darkTheme: Boolean) = if (darkTheme) light.copy(background = Color(0xFFF0F0F0), surface = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF4A4A4A))
        else this.dark.copy(background = Color(0xFF161616), surface = Color(0xFF161616), onSurfaceVariant = Color(0xFFB5B5B5))
    /** Larger, rounder components than the defaults. */
    val shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp))
}

/** Green/yellow/red dot by latency band plus the number in grey; a failed check shows only a grey dash (no dot). */
@Composable fun PingDot(latencyMs: Long?, failed: Boolean, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminanceLow()
    val color = when {
        latencyMs == null -> if (dark) Color(0xFFFF6B6B) else Color(0xFFC62828)
        else -> when (LatencyBand.of(latencyMs)) {
            LatencyBand.GOOD -> if (dark) Color(0xFF4CD07D) else Color(0xFF1E9E4A)
            LatencyBand.MEDIUM -> if (dark) Color(0xFFFFC247) else Color(0xFFD99A00)
            LatencyBand.POOR -> if (dark) Color(0xFFFF6B6B) else Color(0xFFD32F2F)
        }
    }
    if (latencyMs == null && !failed) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // The coloured dot only goes with a real ping number; a failed check is just a grey dash.
        if (latencyMs != null) Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Text(if (latencyMs != null) "$latencyMs ms" else "—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(start = if (latencyMs != null) 6.dp else 0.dp))
    }
}
private fun Color.luminanceLow() = (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f

/**
 * The connect button of the new design: a moth seen from above. Off = resting delta pose (wings partly open, swept back), connecting = flapping between rest and spread (loop),
 * on = wings spread. Monochrome: dark theme grey off / near-white on, light theme grey off / black on. One large circular touch target.
 */
@Composable fun MothButton(on: Boolean, flapping: Boolean, dark: Boolean, enabled: Boolean, onClick: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val open = remember { androidx.compose.animation.core.Animatable(if (on) 1f else 0f) }
    LaunchedEffect(flapping, on) {
        if (flapping) while (true) {
            open.animateTo(1f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            open.animateTo(0f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        } else open.animateTo(if (on) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 220f))
    }
    val lit = on || flapping
    val color by androidx.compose.animation.animateColorAsState(when {
        lit && dark -> Color(0xFFF2F2F2); lit -> Color(0xFF111111); dark -> Color(0xFF8A8A8A); else -> Color(0xFF9E9E9E)
    }, tween(400), label = "moth colour")
    val vein = MaterialTheme.colorScheme.background
    Box(modifier.size(260.dp).clip(CircleShape)
        .clickable(enabled = enabled, onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(210.dp).graphicsLayer { alpha = if (enabled || flapping) 1f else 0.55f }) { drawMoth(open.value, color, vein) }
    }
}

/** Moth in a 100×100 box (scaled to the canvas): four wings pivoting at the thorax, then the body, head and antennae on top. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoth(open: Float, color: Color, vein: Color) {
    val k = size.minDimension / 100f
    fun P(x: Float, y: Float) = Offset(x * k, y * k)
    val fore = androidx.compose.ui.graphics.Path().apply {
        moveTo(52f * k, 37f * k); cubicTo(62f * k, 27f * k, 80f * k, 21f * k, 96f * k, 23f * k)
        cubicTo(97f * k, 32f * k, 91f * k, 42f * k, 82f * k, 48f * k); cubicTo(72f * k, 53f * k, 62f * k, 53f * k, 53f * k, 49f * k); close()
    }
    val hind = androidx.compose.ui.graphics.Path().apply {
        moveTo(52f * k, 48f * k); cubicTo(64f * k, 50f * k, 78f * k, 54f * k, 82f * k, 63f * k)
        cubicTo(84f * k, 72f * k, 74f * k, 80f * k, 64f * k, 76f * k); cubicTo(58f * k, 72f * k, 54f * k, 63f * k, 51f * k, 55f * k); close()
    }
    val veinW = 1.3f * k; val veinC = vein.copy(alpha = 0.28f)
    // Resting (open = 0): a delta pose, forewings swept back and slightly lowered over the hindwings, both pairs visible; spread (1): all wings out to the sides.
    val foreTurn = (1f - open) * 32f; val hindTurn = (1f - open) * 12f
    val foreX = 0.8f + 0.2f * open; val hindX = 0.85f + 0.15f * open
    val pivot = P(51f, 44f)
    for (side in listOf(1f, -1f)) withTransform({ scale(side, 1f, Offset(size.width / 2, size.height / 2)) }) {
        withTransform({ rotate(hindTurn, pivot); scale(hindX, 1f, pivot) }) {
            drawPath(hind, color); drawLine(veinC, P(54f, 53f), P(72f, 68f), veinW, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        withTransform({ rotate(foreTurn, pivot); scale(foreX, 1f, pivot) }) {
            drawPath(fore, color); drawLine(veinC, P(55f, 42f), P(88f, 28f), veinW, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
    // Body: a thin outline in the background colour keeps it visible over the folded wings.
    val edge = Stroke(1.6f * k); val edgeC = vein.copy(alpha = 0.5f)
    drawOval(color, P(45.4f, 44f), androidx.compose.ui.geometry.Size(9.2f * k, 28f * k)); drawOval(edgeC, P(45.4f, 44f), androidx.compose.ui.geometry.Size(9.2f * k, 28f * k), style = edge)
    drawCircle(color, 6f * k, P(50f, 41f)); drawCircle(edgeC, 6f * k, P(50f, 41f), style = edge)
    drawCircle(color, 3.6f * k, P(50f, 33f))
    val antenna = Stroke(2.2f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    for (side in listOf(1f, -1f)) {
        val path = androidx.compose.ui.graphics.Path().apply { moveTo((50f + side * 1.5f) * k, 30.5f * k); quadraticTo((50f + side * 5f) * k, 21f * k, (50f + side * 15f) * k, 17f * k) }
        drawPath(path, color, style = antenna)
    }
}

/** Probe result of [p] on [kind] for display: (latency, failed), ignoring cancelled checks and checks on another network. */
private fun ping(results: Map<String, ProbeResult>, kind: String, p: Profile, network: AccessNetwork): Pair<Long?, Boolean> {
    val r = results["$kind:${p.id}"]?.takeUnless { it.cancelled || it.kind == ProbeErrorKind.CANCELLED || it.checking } ?: return null to false
    if (r.networkHandle != network.handle) return null to false
    return r.latencyMs to (r.latencyMs == null && r.error != null)
}

/** Home screen of the new design: same content and actions as [HomeScreen], moth connect button and a server bar with a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NewHomeScreen(saved: SavedSubscription?, book: org.mothvpn.core.SubscriptionBook?, settings: ClientSettings, network: AccessNetwork, busy: Boolean, message: String,
    add: () -> Unit, connect: () -> Unit, select: (String, String) -> Unit, updateAvailable: Boolean = false, openUpdates: () -> Unit = {}) {
    val tunnel by TunnelStatus.state.collectAsState()
    val health by Diagnostics.health.collectAsState()
    val probing by Diagnostics.running.collectAsState()
    val active by TunnelStatus.activeProfile.collectAsState()
    val results by Diagnostics.results.collectAsState()
    val selected = remember(saved, network.kind, active, tunnel) {
        saved?.profiles?.firstOrNull { it.id == if (tunnel in setOf(TunnelState.RUNNING, TunnelState.BLOCKED) && active.isNotEmpty()) active else saved.selection(network.kind) }
    }
    val blockingTun by TunnelStatus.blocking.collectAsState()
    val stats by TunnelStatus.stats.collectAsState()
    val offline by TunnelStatus.offline.collectAsState()
    val blocked = TunnelStatus.trafficBlocked(tunnel, blockingTun, offline)
    val running = tunnel in setOf(TunnelState.RUNNING, TunnelState.BLOCKED) || blocked
    val transition = tunnel in setOf(TunnelState.STARTING, TunnelState.STOPPING) && !blocked
    val on = running && !blocked
    val dark = MaterialTheme.colorScheme.background.luminanceLow()
    var sheet by remember { mutableStateOf(false) }
    val enabled = if (blocked) true else !busy && (!probing || running) && !transition && selected != null
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Fixed-height header: the update badge sits in its own reserved slot, so the title never moves.
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp)) {
            val name = tr(R.string.app_name)
            val about = VersionLabel.about(name, BuildConfig.VERSION_NAME)
            Row(Modifier.align(Alignment.CenterStart).padding(end = 56.dp), verticalAlignment = Alignment.Bottom) {
                Text(name, style = MaterialTheme.typography.headlineLarge, maxLines = 1)
                Text(about.removePrefix(name).trim(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            }
            if (updateAvailable) IconButton(onClick = openUpdates, modifier = Modifier.align(Alignment.CenterEnd).size(48.dp).semantics { contentDescription = tr(R.string.update_available_arrow) }) {
                BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.onSurface) }) { MothIcon("update", Modifier.size(26.dp)) }
            }
        }
        Spacer(Modifier.weight(1f))
        MothButton(on = on, flapping = transition, dark = dark, enabled = enabled, onClick = connect,
            description = if (running) tr(R.string.msg_disconnect_vpn_8) else tr(R.string.msg_connect_vpn_9))
        // Reserved space for the status lines (title + one detail line): nothing above or below moves when they change.
        Column(Modifier.fillMaxWidth().height(88.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (blocked) {
                Text(tr(R.string.home_disconnect), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                Text(tr(R.string.msg_blocked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else Text(when (tunnel) { TunnelState.OFF -> tr(R.string.msg_connect_10); TunnelState.RUNNING -> if (TunnelStatus.waitingForNetwork(tunnel, offline)) tr(R.string.tile_reconnecting) else tr(R.string.msg_vpn_is_on_11); TunnelState.STARTING -> tr(R.string.msg_connecting_12); TunnelState.STOPPING -> tr(R.string.msg_disconnecting_13); TunnelState.ERROR -> tr(R.string.msg_could_not_connect_14); TunnelState.BLOCKED -> tr(R.string.msg_blocked) }, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            if (tunnel == TunnelState.RUNNING && !blocked) Text(if (health.checking) tr(R.string.msg_checking_internet_access_15) else localizedError(health.message), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (saved == null) Button(enabled = !busy, onClick = add, modifier = Modifier.heightIn(min = 56.dp)) { Text(tr(R.string.msg_add_subscription_16)) }
        // Session tiles (speed, traffic, time) while connected. The space is always reserved, so the moth never moves when they appear.
        else SessionTiles(stats.takeIf { on })
        Spacer(Modifier.weight(1f))
        val source = remember(book, selected) { selected?.let { p -> book?.sourceOf(p.id) } ?: book?.sources?.firstOrNull() }
        if (saved != null && source != null) NewSubscriptionCard(book, source, network)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val filterHidesAll by TunnelStatus.filterHidesAll.collectAsState()
        if (filterHidesAll && settings.autoSelect && settings.serverFilter.isNotEmpty()) Text(tr(R.string.nd_filter_hides_all), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        // The whole bar is one touch target: it opens the server sheet.
        // Hero card: the inverse tone (light fill in the dark theme, dark fill in the light theme) with its own readable colours.
        if (selected != null) MaterialTheme(colorScheme = MonoTheme.hero(dark)) {
            Surface(onClick = { sheet = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { contentDescription = tr(R.string.nd_choose_server) + ": " + selected.name }) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selected.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(serverSubtitle(selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val (ms, failed) = ping(results, network.kind, selected, network)
                    PingDot(ms, failed, Modifier.padding(start = 12.dp))
                    Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(28.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (sheet && saved != null) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val kind = network.kind
        val ordered = remember(saved, results, settings.serverSort, settings.favorites, settings.serverFilter, settings.serverFilterMode, kind) {
            Favorites.first(ServerOrder.sort(saved.profiles.filter { settings.filterShows(it) }, settings.serverSort, { it.name }) { p -> results["$kind:${p.id}"]?.latencyMs }, settings.favorites) { it.id }
        }
        val canSelect = !busy && !probing && tunnel != TunnelState.STOPPING
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = state, containerColor = NdTone.sheet()) {
            Text(tr(R.string.msg_servers_27), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ordered, key = { it.id }) { p ->
                    val chosen = p.id == selected?.id
                    Surface(onClick = { if (canSelect) { select(p.id, kind); sheet = false } }, enabled = canSelect, shape = MaterialTheme.shapes.medium,
                        color = if (chosen) NewDesignRow.selectedColor() else NdTone.raised(), border = NewDesignRow.selectedBorder(chosen),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { contentDescription = p.name + if (chosen) tr(R.string.msg_selected_61) else "" }) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(serverSubtitle(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val (ms, failed) = ping(results, kind, p, network)
                            PingDot(ms, failed, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Subscription card on the new home screen. The whole card is one touch target: with several subscriptions it opens the list of all of them,
 * otherwise it shows the subscription link (local "My servers" have no link, so the card is not clickable then).
 */
@Composable fun NewSubscriptionCard(book: org.mothvpn.core.SubscriptionBook?, source: org.mothvpn.core.SubscriptionSource, network: AccessNetwork) {
    val all = book?.sources.orEmpty()
    val host = remember(source.url) { runCatching { java.net.URI(source.url).host }.getOrNull().orEmpty() }
    var showAll by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val clickable = all.size > 1 || !source.local
    val title = source.name.ifBlank { source.providerName }.ifBlank { if (source.local) tr(R.string.import_local_name) else host }
    // One subscription with provider links opens the sheet too, so its link buttons are reachable (the sheet also copies the link).
    Surface(onClick = { if (all.size > 1 || !source.links.isEmpty) showAll = true else showLink = true }, enabled = clickable, shape = MaterialTheme.shapes.large, color = NdTone.card(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { contentDescription = title + ": " + tr(if (all.size > 1 || !source.links.isEmpty) R.string.sub_all_show else R.string.sub_link_show) }) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(if (all.size > 1) tr(R.string.sub_count, all.size) else null, tr(R.string.sub_servers_count, source.profiles.size),
                    if (!network.available) tr(R.string.msg_no_network_20) else if (network.kind == "mobile") tr(R.string.msg_mobile_data_21) else "Wi-Fi").joinToString(" · ")
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    SubscriptionQuotaLine(org.mothvpn.core.SubscriptionRecord(source.url, source.profiles, "", usedBytes = source.usedBytes, totalBytes = source.totalBytes, expires = source.expires))
                }
            }
            if (clickable) Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
    }
    if (showAll) SubscriptionsSheet(all, { showAll = false }) { url -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(url)) }
    // The link is the user's own; it is shown in full here and is never written to the log.
    if (showLink) AlertDialog(onDismissRequest = { showLink = false }, title = { Text(tr(R.string.sub_link_title)) },
        text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(source.url) } },
        confirmButton = { TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(source.url)); showLink = false }) { Text(tr(R.string.sub_link_copy)) } },
        dismissButton = { TextButton(onClick = { showLink = false }) { Text(tr(R.string.sub_link_close)) } })
}

/** All subscriptions as cards in a bottom sheet (as the server sheet): servers, traffic, end date, last update (not for local servers) and a copy-link button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SubscriptionsSheet(sources: List<org.mothvpn.core.SubscriptionSource>, close: () -> Unit, copy: (String) -> Unit) {
    val dates = java.text.DateFormat.getDateInstance(java.text.DateFormat.DEFAULT, AppLanguage.context().resources.configuration.locales[0])
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = NdTone.sheet()) {
        Text(tr(R.string.sub_all_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sources, key = { it.id }) { s ->
                Surface(shape = MaterialTheme.shapes.medium, color = NdTone.raised(), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Column {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = if (s.links.isEmpty) 12.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.title().ifBlank { tr(R.string.import_local_name) }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val grey = MaterialTheme.colorScheme.onSurfaceVariant
                            Text(tr(R.string.sub_servers_count, s.profiles.size), style = MaterialTheme.typography.bodySmall, color = grey)
                            if (s.totalBytes > 0) Text(tr(R.string.msg_traffic_1f_1f_gib_18, s.usedBytes / 1073741824.0, s.totalBytes / 1073741824.0), style = MaterialTheme.typography.bodySmall, color = grey)
                            if (s.expires in 1..253402300799L) Text(tr(R.string.msg_subscription_expires_on_19) + dates.format(java.util.Date(s.expires * 1000)), style = MaterialTheme.typography.bodySmall, color = grey)
                            // Local servers ("My servers") are never downloaded, so they have no update date.
                            if (!s.local && s.updatedAt > 0) Text(tr(R.string.sub_updated_on, dates.format(java.util.Date(s.updatedAt))), style = MaterialTheme.typography.bodySmall, color = grey)
                        }
                        if (s.totalBytes > 0) UsageRing(s.usedBytes.toDouble() / s.totalBytes, Modifier.padding(start = 8.dp))
                        if (!s.local) IconButton(onClick = { copy(s.url) }, modifier = Modifier.size(48.dp).semantics { contentDescription = tr(R.string.sub_link_copy_link) }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                    }
                    if (!s.links.isEmpty) ProviderLinkButtons(s.links, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Leading icon in a rounded-square tile (40 dp, 12 dp corners): a grey tile (darker grey in the dark theme, lighter in the light theme, still
 * visible on AMOLED black) with a monochrome icon. Used for Settings rows; meant for future provider buttons too (bot, support, news).
 * Server lists and menus keep plain icons.
 */
@Composable fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp,
    label: String? = null, onClick: (() -> Unit)? = null) {
    val dark = MaterialTheme.colorScheme.background.luminanceLow()
    // A translucent layer of the content colour: always a step lighter (dark) or darker (light) than the card it sits on.
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.13f else 0.08f)
    val content: @Composable () -> Unit = {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size * 0.55f)) }
    }
    // As a button (provider links) the tile keeps a 48 dp touch target around the 40 dp square.
    if (onClick != null) Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = color,
        modifier = modifier.size(size).semantics { if (label != null) contentDescription = label }, content = content)
    else Surface(shape = RoundedCornerShape(12.dp), color = color, modifier = modifier.size(size), content = content)
}

/** Menu of the new design: a bottom sheet with a title and full-width rows ([NdSheetItem]), consistent with the server sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NdActionSheet(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NdTone.sheet()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
            content()
        }
    }
}

/** One menu row: the whole row, edge to edge, is the touch target (at least 56 dp high). */
@Composable fun NdSheetItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick, role = androidx.compose.ui.semantics.Role.Button).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Monochrome leading icon, as in the rest of the new design.
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.padding(end = 16.dp).size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

/** The protocol line of a server, as in the server list. */
@Composable fun serverSubtitle(p: Profile): String =
    org.mothvpn.core.ServerLabels.label(p, tr(R.string.server_balancer)) + if (org.mothvpn.core.ProxyProtocols.unencrypted(p)) " · " + tr(R.string.proto_unencrypted) else ""

/**
 * New design: keyword server filter. Keywords are added one by one (text field + Add) and listed as rows with a delete button;
 * the mode hides matching servers or shows only them. Saved in settings and matched against server names when the list is shown,
 * so it survives subscription updates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ServerFilterSheet(settings: ClientSettings, save: (ClientSettings) -> Unit, close: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val keywords = settings.serverFilter
    fun add() { val next = org.mothvpn.core.ServerFilter.add(keywords, text); if (next != keywords) save(settings.copy(serverFilter = next)); text = "" }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NdTone.sheet()) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.nd_filter_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(org.mothvpn.core.ServerFilter.EXCLUDE to tr(R.string.nd_filter_exclude), org.mothvpn.core.ServerFilter.ONLY to tr(R.string.nd_filter_only)).forEachIndexed { i, (mode, label) ->
                    SegmentedButton(selected = settings.serverFilterMode == mode, onClick = { save(settings.copy(serverFilterMode = mode)) }, shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {},
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primaryContainer, activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = Color.Transparent, activeBorderColor = MaterialTheme.colorScheme.outline, inactiveBorderColor = MaterialTheme.colorScheme.outline),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(org.mothvpn.core.ServerFilter.MAX_LENGTH) }, singleLine = true, placeholder = { Text(tr(R.string.nd_filter_keyword)) },
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }))
                Button(onClick = { add() }, enabled = org.mothvpn.core.ServerFilter.clean(text) != null && keywords.size < org.mothvpn.core.ServerFilter.MAX_KEYWORDS,
                    modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) { Text(tr(R.string.nd_filter_add)) }
            }
            if (keywords.isEmpty()) Text(tr(R.string.nd_filter_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(keywords, key = { it }) { k ->
                    Surface(shape = MaterialTheme.shapes.medium, color = NdTone.raised(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(k, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { save(settings.copy(serverFilter = keywords - k)) }, modifier = Modifier.size(48.dp).semantics { contentDescription = tr(R.string.nd_filter_remove, k) }) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            Text(tr(R.string.nd_filter_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** True when the new design's keyword filter shows this server (the classic design ignores the filter). */
fun ClientSettings.filterShows(p: Profile) = !newDesign || org.mothvpn.core.ServerFilter.shows(p.name, serverFilter, serverFilterMode)

/**
 * Provider link buttons (support, web page, news) as [IconTile]s with a short caption. Telegram links get a paper-plane icon.
 * The URL is opened by the system; it is never written to the log.
 */
@Composable fun ProviderLinkButtons(links: org.mothvpn.core.ProviderLinks, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val items = listOfNotNull(
        links.support.takeIf { it.isNotEmpty() }?.let { Triple(it, tr(R.string.nd_link_support), if (org.mothvpn.core.ProviderLinks.telegram(it)) androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Send else androidx.compose.material.icons.Icons.Rounded.SupportAgent) },
        links.announce.takeIf { it.isNotEmpty() }?.let { Triple(it, tr(R.string.nd_link_news), if (org.mothvpn.core.ProviderLinks.telegram(it)) androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Send else androidx.compose.material.icons.Icons.Rounded.Campaign) },
        links.web.takeIf { it.isNotEmpty() }?.let { Triple(it, tr(R.string.nd_link_web), androidx.compose.material.icons.Icons.Rounded.Language) })
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEach { (url, label, icon) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 56.dp)) {
                IconTile(icon, label = label, onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Logger.w("Links", "no app can open the provider link") }
                })
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * Two-option switch of the new design: a tonal track with a filled pill behind the active option (not an underline).
 * [position] is 0..1 (the pager position, fractions while swiping), so the pill slides with the finger.
 */
@Composable fun NdPillSwitch(labels: List<String>, position: Float, select: (Int) -> Unit) {
    val pos = position.coerceIn(0f, (labels.size - 1).toFloat())
    Surface(shape = CircleShape, color = NdTone.raised(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val w = maxWidth / labels.size
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.offset(x = w * pos).width(w).fillMaxHeight()) {}
            Row(Modifier.fillMaxSize()) {
                labels.forEachIndexed { i, label ->
                    val near = 1f - kotlin.math.abs(pos - i).coerceIn(0f, 1f)
                    val color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onPrimary, near)
                    Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .selectable(selected = near > 0.5f, role = androidx.compose.ui.semantics.Role.Tab) { select(i) }, contentAlignment = Alignment.Center) {
                        Text(label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Three tonal tiles with a big number and a small label: current speed, traffic of this session, connection time. Empty (but sized) when [stats] is null. */
@Composable fun SessionTiles(stats: org.mothvpn.core.TrafficFormat.Stats?) {
    Box(Modifier.fillMaxWidth().height(72.dp)) {
        if (stats != null) {
            val units = tr(R.string.notif_units).split("|"); val locale = AppLanguage.context().resources.configuration.locales[0]
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(org.mothvpn.core.TrafficFormat.bytes(stats.txRate + stats.rxRate, units, locale) + tr(R.string.nd_per_second) to tr(R.string.nd_stat_speed),
                    org.mothvpn.core.TrafficFormat.bytes(stats.txTotal + stats.rxTotal, units, locale) to tr(R.string.nd_stat_traffic),
                    org.mothvpn.core.TrafficFormat.duration(stats.seconds) to tr(R.string.nd_stat_time)).forEach { (value, label) ->
                    Surface(shape = MaterialTheme.shapes.medium, color = NdTone.card(), modifier = Modifier.weight(1f).fillMaxHeight().semantics(mergeDescendants = true) {}) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Traffic used as a ring with the percent inside (Compose M3 1.4.0 has no wavy circular indicator, so it is drawn here):
 * a tonal track and a gently wavy arc for the used share, monochrome; above 90 % the arc turns to the error colour.
 */
@Composable fun UsageRing(fraction: Double, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val f = fraction.coerceIn(0.0, 1.0).toFloat()
    val percent = kotlin.math.round(fraction.coerceAtLeast(0.0) * 100).toInt()
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val arc = if (f >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val label = tr(R.string.nd_usage_ring, percent)
    Box(modifier.size(size).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx(); val wave = 1.2.dp.toPx()
            val r = this.size.minDimension / 2 - stroke / 2 - wave
            val c = center
            drawCircle(track, r, c, style = Stroke(stroke))
            if (f > 0f) {
                val path = androidx.compose.ui.graphics.Path()
                val steps = (180 * f).toInt().coerceAtLeast(2)
                for (i in 0..steps) {
                    val t = f * i / steps
                    val a = (-90f + 360f * t) * (Math.PI / 180f).toFloat()
                    val rr = r + wave * kotlin.math.sin(t * 2f * Math.PI.toFloat() * 12f)
                    val p = Offset(c.x + rr * kotlin.math.cos(a), c.y + rr * kotlin.math.sin(a))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, arc, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
        Text("$percent%", style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
