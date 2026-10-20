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

/** Keyed lazy-list rows: fade in/out and slide to new places (Modifier.animateItem); nothing with "Remove animations". */
@Composable fun Modifier.listItemMotion(scope: androidx.compose.foundation.lazy.LazyItemScope): Modifier {
    if (!animationsOn()) return this
    return with(scope) {
        this@listItemMotion.animateItem(fadeInSpec = tween(Motion.SHORT), fadeOutSpec = tween(180),
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
