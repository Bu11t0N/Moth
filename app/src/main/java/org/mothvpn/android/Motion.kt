package org.mothvpn.android

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Shared motion for the UI: short (about 200–300 ms) standard easing and springs. Everything checks the system
 * "Remove animations" setting (animator duration scale 0) and then changes at once.
 */
object Motion {
    const val SHORT = 220
    const val MEDIUM = 280
    fun enabled(context: android.content.Context): Boolean =
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    fun <T> tweenOr(on: Boolean, ms: Int = SHORT): FiniteAnimationSpec<T> = if (on) tween(ms, easing = androidx.compose.animation.core.FastOutSlowInEasing) else snap()
    fun <T> springOr(on: Boolean, stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        if (on) spring(dampingRatio = 0.85f, stiffness = stiffness) else snap()
}

/** True unless the system "Remove animations" setting is on (read once per composition site). */
@Composable fun animationsOn(): Boolean { val context = LocalContext.current; return remember { Motion.enabled(context) } }

/**
 * The shared press feedback for everything clickable (cards, rows, buttons, icon buttons, chips, dialog buttons): a short spring scale
 * of the whole element while it is held. It only observes the touch (initial pass, nothing is consumed) and scales in the graphics
 * layer, so ripple, clicks, touch targets and accessibility of the element are unchanged. The shrink is at most 1 - [scale] (0.96 for
 * small buttons) and at most about 10 dp in total, so wide rows and cards move less. No effect when [enabled] is false or with
 * animations off.
 */
fun Modifier.pressScale(scale: Float = 0.96f, enabled: Boolean = true): Modifier = composed {
    val on = animationsOn()
    if (!on || !enabled) return@composed this
    var pressed by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                try {
                    // Released on lift, or as soon as the finger moves past the touch slop (scrolling a list does not keep it pressed).
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                } finally { pressed = false }
            }
        }
        .graphicsLayer {
            val longest = maxOf(size.width, size.height)
            val shrink = if (longest <= 0f) 0f else minOf(1f - scale, 10.dp.toPx() / longest)
            val s = 1f - p * shrink
            scaleX = s; scaleY = s
        }
}

/** The shared-transition scope of the Settings pages and the page scope of the page being composed (null outside Settings). */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
val LocalSharedScope = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
val LocalPageScope = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/**
 * Container transform: the element with [key] on the page that is leaving and the one on the page that is coming share their bounds,
 * so a settings card grows into its page and the page shrinks back into the card. Off when [key] is null, outside Settings, or with
 * "Remove animations".
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable fun Modifier.containerTransform(key: String?, shape: androidx.compose.ui.graphics.Shape = androidx.compose.material3.MaterialTheme.shapes.medium): Modifier {
    val shared = LocalSharedScope.current; val page = LocalPageScope.current
    if (key == null || shared == null || page == null || !animationsOn()) return this
    return with(shared) {
        this@containerTransform.sharedBounds(rememberSharedContentState("container:$key"), animatedVisibilityScope = page,
            enter = androidx.compose.animation.fadeIn(tween(Motion.SHORT, delayMillis = 60)), exit = androidx.compose.animation.fadeOut(tween(120)),
            boundsTransform = { _, _ -> tween(Motion.MEDIUM, easing = androidx.compose.animation.core.FastOutSlowInEasing) },
            resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape))
    }
}

/**
 * Keyed lazy-list rows: fade in/out and slide to new places (Modifier.animateItem); nothing with "Remove animations".
 * [fadeIn] false: the row brings its own entrance (see [unfold]).
 */
@Composable fun Modifier.listItemMotion(scope: androidx.compose.foundation.lazy.LazyItemScope, fadeIn: Boolean = true): Modifier {
    if (!animationsOn()) return this
    return with(scope) {
        this@listItemMotion.animateItem(fadeInSpec = if (fadeIn) tween(Motion.SHORT) else null, fadeOutSpec = tween(180),
            placementSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold))
    }
}

/**
 * A bottom navigation icon: when its tab becomes selected it gives a small spring bounce and crossfades from the outlined to the filled
 * icon (the M3 indicator pill animates on its own). Instant with "Remove animations"; no bounce on first composition.
 */
@Composable fun NavIcon(selected: Boolean, outlined: androidx.compose.ui.graphics.vector.ImageVector, filled: androidx.compose.ui.graphics.vector.ImageVector) {
    val on = animationsOn()
    val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
    var previous by remember { mutableStateOf(selected) }
    androidx.compose.runtime.LaunchedEffect(selected) {
        val becameSelected = selected && !previous
        previous = selected
        if (on && becameSelected) {
            bounce.snapTo(0.8f)
            bounce.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        } else bounce.snapTo(1f)
    }
    androidx.compose.animation.Crossfade(selected, animationSpec = Motion.tweenOr(on, 180), label = "nav icon") { s ->
        androidx.compose.material3.Icon(if (s) filled else outlined, contentDescription = null,
            modifier = Modifier.graphicsLayer { scaleX = bounce.value; scaleY = bounce.value })
    }
}

/** A server group that the user just opened ([opening]) or is closing, at [at] (uptime ms). */
data class Unfold(val opening: Boolean, val at: Long = android.os.SystemClock.uptimeMillis())

/**
 * Staggered group rows (GroupUnfold timing): opening, each row slides down 8 dp from under the header, fades in and grows from 0.95;
 * closing reverses it faster, bottom row first. Rows composed after their slot has passed (scrolling later) show at once. Only a
 * graphics layer per visible row, so long lists stay smooth. Nothing with "Remove animations".
 */
fun Modifier.unfold(event: Unfold?, index: Int, count: Int): Modifier = composed {
    val on = animationsOn()
    if (event == null || !on) return@composed this
    val t = org.mothvpn.core.GroupUnfold.timing(index, count, event.opening)
    val p = remember(event) {
        val elapsed = android.os.SystemClock.uptimeMillis() - event.at
        androidx.compose.animation.core.Animatable(if (event.opening) (if (elapsed >= t.delayMs + t.durationMs) 1f else 0f) else 1f)
    }
    androidx.compose.runtime.LaunchedEffect(event) {
        val wait = t.delayMs - (android.os.SystemClock.uptimeMillis() - event.at)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        if (event.opening) p.animateTo(1f, tween(t.durationMs, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        else p.animateTo(0f, tween(t.durationMs, easing = androidx.compose.animation.core.FastOutLinearInEasing))
    }
    graphicsLayer {
        val v = p.value
        alpha = v; translationY = -(1f - v) * 8.dp.toPx()
        val sc = 0.95f + 0.05f * v; scaleX = sc; scaleY = sc
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
    }
}

/**
 * Buttons that come out of the element at the start of their row (the subscription card's avatar): while [shown] they slide out from
 * the row start to their place with a fade and a scale from 0.6, [index] staggered by 40 ms; when hidden they slide back into it.
 * Instant with "Remove animations".
 */
fun Modifier.emergeFromStart(shown: Boolean, index: Int, startDelayMs: Int = 120): Modifier = composed {
    val on = animationsOn()
    if (!on) return@composed this
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    var x by remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(shown) {
        if (shown) { kotlinx.coroutines.delay((startDelayMs + index * 40).toLong()); p.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }
        else p.animateTo(0f, tween(160, delayMillis = (2 - index).coerceAtLeast(0) * 25, easing = androidx.compose.animation.core.FastOutLinearInEasing))
    }
    this.then(Modifier.onPlaced { x = it.positionInParent().x }).graphicsLayer {
        val v = p.value
        translationX = -x * (1f - v); alpha = v.coerceIn(0f, 1f)
        val sc = 0.6f + 0.4f * v; scaleX = sc; scaleY = sc
    }
}

/**
 * The app's switch (WhatsApp style, colour-blind friendly): the thumb shows a check when on and a minus when off (16 dp icons), so
 * the state never depends on colour alone. Icon colours are the higher-contrast black or white on the thumb, in every theme.
 */
@Composable fun MothSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val defaults = androidx.compose.material3.SwitchDefaults.colors()
    val checkedThumb = scheme.onPrimary; val uncheckedThumb = scheme.outline
    val colors = androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = checkedThumb, checkedIconColor = contrastOn(checkedThumb),
        uncheckedThumbColor = uncheckedThumb, uncheckedIconColor = contrastOn(uncheckedThumb),
        disabledCheckedIconColor = defaults.disabledCheckedIconColor, disabledUncheckedIconColor = defaults.disabledUncheckedIconColor)
    androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, colors = colors,
        thumbContent = {
            androidx.compose.material3.Icon(if (checked) androidx.compose.material.icons.Icons.Rounded.Check else androidx.compose.material.icons.Icons.Rounded.Remove,
                contentDescription = null, modifier = Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize))
        })
}

/** Black or white, whichever contrasts more with [background] (WCAG relative luminance). */
fun contrastOn(background: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val l = background.luminance()
    return if ((l + 0.05f) / 0.05f >= 1.05f / (l + 0.05f)) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
}
