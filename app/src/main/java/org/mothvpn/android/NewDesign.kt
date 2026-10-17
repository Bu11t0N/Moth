package org.mothvpn.android

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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

/** Monochrome Material 3 schemes (black, white and greys; no dynamic/wallpaper colour). Errors stay red so warnings remain readable. */
/** Selected server row in the new design: no check mark, a stronger fill plus an outline so it stands out in both themes. */
object NewDesignRow {
    @Composable fun selectedColor(): Color = MaterialTheme.colorScheme.primaryContainer
    @Composable fun selectedBorder(chosen: Boolean): androidx.compose.foundation.BorderStroke? =
        if (chosen) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) else null
}

object MonoTheme {
    val dark = darkColorScheme(
        primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF000000), primaryContainer = Color(0xFF3A3A3A), onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFBDBDBD), onSecondary = Color(0xFF000000), secondaryContainer = Color(0xFF2C2C2C), onSecondaryContainer = Color(0xFFF2F2F2),
        tertiary = Color(0xFFBDBDBD), onTertiary = Color(0xFF000000), tertiaryContainer = Color(0xFF2C2C2C), onTertiaryContainer = Color(0xFFF2F2F2),
        background = Color(0xFF0B0B0B), onBackground = Color(0xFFF2F2F2), surface = Color(0xFF0B0B0B), onSurface = Color(0xFFF2F2F2),
        surfaceVariant = Color(0xFF1E1E1E), onSurfaceVariant = Color(0xFFA6A6A6), surfaceTint = Color(0xFFF2F2F2),
        inverseSurface = Color(0xFFF2F2F2), inverseOnSurface = Color(0xFF111111), inversePrimary = Color(0xFF111111),
        outline = Color(0xFF6E6E6E), outlineVariant = Color(0xFF333333), scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF2A2A2A), surfaceDim = Color(0xFF0B0B0B), surfaceContainerLowest = Color(0xFF050505), surfaceContainerLow = Color(0xFF141414),
        surfaceContainer = Color(0xFF181818), surfaceContainerHigh = Color(0xFF1F1F1F), surfaceContainerHighest = Color(0xFF292929))
    val light = lightColorScheme(
        primary = Color(0xFF111111), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFDADADA), onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFF555555), onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color(0xFF111111),
        tertiary = Color(0xFF555555), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFE6E6E6), onTertiaryContainer = Color(0xFF111111),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF111111), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF5E5E5E), surfaceTint = Color(0xFF111111),
        inverseSurface = Color(0xFF111111), inverseOnSurface = Color(0xFFF2F2F2), inversePrimary = Color(0xFFF2F2F2),
        outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFD6D6D6), scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFE0E0E0), surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFECECEC), surfaceContainerHighest = Color(0xFFE6E6E6))
    /** Larger, rounder components than the defaults. */
    val shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp))
}

/** Green/yellow/red dot by latency band plus the number in grey; a grey dash when the check failed. */
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
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Text(if (latencyMs != null) "$latencyMs ms" else "—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}
private fun Color.luminanceLow() = (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f

/**
 * The connect button of the new design: a moth seen from above. Off = wings folded back along the body, connecting = wings flapping (loop),
 * on = wings spread. Monochrome: dark theme grey off / near-white on, light theme grey off / black on. One large circular touch target.
 */
@Composable fun MothButton(on: Boolean, flapping: Boolean, dark: Boolean, enabled: Boolean, onClick: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val open = remember { androidx.compose.animation.core.Animatable(if (on) 1f else 0f) }
    LaunchedEffect(flapping, on) {
        if (flapping) while (true) {
            open.animateTo(1f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            open.animateTo(0.18f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
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
    // Folded: the forewings swing back over the hindwings like a tent; spread: all four wings out to the sides.
    val foreTurn = (1f - open) * 70f; val hindTurn = (1f - open) * 42f
    val foreX = 0.5f + 0.5f * open; val hindX = 0.35f + 0.65f * open
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
        Spacer(Modifier.weight(1f))
        val source = remember(book, selected) { selected?.let { p -> book?.sourceOf(p.id) } ?: book?.sources?.firstOrNull() }
        if (saved != null && source != null) HomeSubscriptionCard(book, source, network)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        // The whole bar is one touch target: it opens the server sheet.
        if (selected != null) Surface(onClick = { sheet = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        Spacer(Modifier.height(8.dp))
    }
    if (sheet && saved != null) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val kind = network.kind
        val ordered = remember(saved, results, settings.serverSort, settings.favorites, kind) {
            Favorites.first(ServerOrder.sort(saved.profiles, settings.serverSort, { it.name }) { p -> results["$kind:${p.id}"]?.latencyMs }, settings.favorites) { it.id }
        }
        val canSelect = !busy && !probing && tunnel != TunnelState.STOPPING
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(tr(R.string.msg_servers_27), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ordered, key = { it.id }) { p ->
                    val chosen = p.id == selected?.id
                    Surface(onClick = { if (canSelect) { select(p.id, kind); sheet = false } }, enabled = canSelect, shape = MaterialTheme.shapes.medium,
                        color = if (chosen) NewDesignRow.selectedColor() else MaterialTheme.colorScheme.surfaceContainerLow, border = NewDesignRow.selectedBorder(chosen),
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

/** The protocol line of a server, as in the server list. */
@Composable fun serverSubtitle(p: Profile): String =
    org.mothvpn.core.ServerLabels.label(p, tr(R.string.server_balancer)) + if (org.mothvpn.core.ProxyProtocols.unencrypted(p)) " · " + tr(R.string.proto_unencrypted) else ""
