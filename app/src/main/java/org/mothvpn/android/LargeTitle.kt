package org.mothvpn.android

import androidx.compose.animation.core.animate
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Telegram-style large title: at the top of a list the title is big and centred and the header is taller, so the title, its
 * buttons and the list start lower (easier to reach on a tall phone); scrolling the list up first shrinks the header to the compact title,
 * scrolling back to the top grows it again. A half-way header snaps to the nearest state when the finger lifts.
 * Attach [LargeTitle.nestedScrollConnection] (Modifier.nestedScroll) to a parent of both the header and the list.
 * The scroll / snap logic is Material 3's exit-until-collapsed top app bar behaviour; the header itself is ours (NewDesign look).
 */
@OptIn(ExperimentalMaterial3Api::class)
class LargeTitle internal constructor(internal val behavior: TopAppBarScrollBehavior) {
    val nestedScrollConnection get() = behavior.nestedScrollConnection

    /** Expanded (top of the page) or collapsed (the page is scrolled), animated; used when the page under the header changes. */
    suspend fun settleTo(collapsed: Boolean, animated: Boolean) {
        val state = behavior.state
        val target = if (collapsed) state.heightOffsetLimit else 0f
        if (target == -Float.MAX_VALUE || state.heightOffset == target) return
        if (!animated) { state.heightOffset = target; return }
        animate(state.heightOffset, target, animationSpec = androidx.compose.animation.core.tween(Motion.SHORT)) { v, _ -> state.heightOffset = v }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun rememberLargeTitle(): LargeTitle {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState(),
        snapAnimationSpec = if (animationsOn()) androidx.compose.animation.core.spring(stiffness = 600f) else null)
    return androidx.compose.runtime.remember(behavior) { LargeTitle(behavior) }
}

/**
 * How much taller the header is when expanded: about a fifth of the screen height (96–200 dp), so on a tall phone the big title
 * fills the empty top of the screen and the buttons and the list start lower, within thumb reach.
 */
@Composable private fun largeTitleExtra() = (LocalConfiguration.current.screenHeightDp * 0.2f).coerceIn(96f, 200f).dp

/**
 * The header. Expanded (top of the page): a big title centred in the extra space at the top, the buttons ([actions]) in the row
 * under it, at the right. Collapsing (the page scrolls up): the extra space shrinks, the big title fades out and the compact title
 * (headlineSmall) fades in at the start of the button row. Only one of the two titles exists at rest (expanded or collapsed), so
 * accessibility and UI tests see the title once. A title change crossfades.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LargeTitleHeader(largeTitle: LargeTitle, title: String, modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}) {
    val extraPx = with(LocalDensity.current) { largeTitleExtra().toPx() }
    val state = largeTitle.behavior.state
    SideEffect { if (state.heightOffsetLimit != -extraPx) state.heightOffsetLimit = -extraPx }
    val fraction = state.collapsedFraction
    val fade = androidx.compose.animation.core.tween<Float>(if (animationsOn()) 200 else 0)
    val big = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Medium)
    Column(modifier.fillMaxWidth()) {
        // The big title's area: its height is the current extra space; the title keeps its full size and stays centred in it (clipped).
        Box(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
            val full = extraPx.roundToInt()
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = full))
            val now = (extraPx + state.heightOffset).coerceIn(0f, extraPx).roundToInt()
            layout(placeable.width, now) { placeable.place(0, (now - placeable.height) / 2) }
        }, contentAlignment = Alignment.Center) {
            if (fraction < 1f) Crossfade(title, animationSpec = fade, label = "largeTitle") { t ->
                Text(t, style = big, maxLines = 1, textAlign = TextAlign.Center,
                    autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = big.fontSize),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        .graphicsLayer { alpha = (1f - fraction * 1.6f).coerceIn(0f, 1f); val k = 1f - 0.15f * fraction; scaleX = k; scaleY = k })
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (fraction > 0f) Crossfade(title, Modifier.weight(1f).graphicsLayer { alpha = ((fraction - 0.4f) / 0.6f).coerceIn(0f, 1f) }, animationSpec = fade, label = "compactTitle") { t ->
                Text(t, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Spacer(Modifier.weight(1f))
            actions()
        }
    }
}
