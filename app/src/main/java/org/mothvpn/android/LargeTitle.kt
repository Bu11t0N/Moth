package org.mothvpn.android

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Telegram-style large title: at the top of a list the title is large and the header is taller, so the title, its buttons and
 * the list start lower (easier to reach on a tall phone); scrolling the list up first shrinks the header to the compact title,
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

/** How much taller the header is when expanded: about an eighth of the screen height. */
@Composable private fun largeTitleExtra() = (LocalConfiguration.current.screenHeightDp * 0.12f).coerceIn(48f, 120f).dp

/**
 * The header row: [content] is the title (it gets the interpolated text style, headlineLarge expanded → headlineSmall collapsed)
 * and any buttons; the row sits at the bottom of the header, so the buttons move down with the title when it is large.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LargeTitleHeader(largeTitle: LargeTitle, modifier: Modifier = Modifier,
    content: @Composable RowScope.(TextStyle) -> Unit) {
    val extraPx = with(LocalDensity.current) { largeTitleExtra().toPx() }
    val state = largeTitle.behavior.state
    SideEffect { if (state.heightOffsetLimit != -extraPx) state.heightOffsetLimit = -extraPx }
    val fraction = state.collapsedFraction
    val style = lerp(MaterialTheme.typography.headlineLarge, MaterialTheme.typography.headlineSmall, fraction)
    Row(modifier.fillMaxWidth().layout { measurable, constraints ->
        // The row keeps its natural height; the extra space above it shrinks as the header collapses.
        val placeable = measurable.measure(constraints)
        val extra = (extraPx + state.heightOffset).coerceIn(0f, extraPx).roundToInt()
        layout(placeable.width, placeable.height + extra) { placeable.place(0, extra) }
    }, verticalAlignment = Alignment.CenterVertically) {
        content(style)
    }
}
