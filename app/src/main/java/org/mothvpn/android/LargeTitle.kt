package org.mothvpn.android

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.TransformOrigin
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
 * Large titles on or off and the expanded header's extra height in percent of the screen height (Settings › Interface, 10–30 %, 20 % by
 * default). Set from the settings in memory (not after the file write) and, while the height slider is dragged, from the slider, so every
 * large title follows it at once. [percent] is fractional while dragging, so the height changes smoothly between whole steps.
 */
object LargeTitleTuning {
    var percent by androidx.compose.runtime.mutableFloatStateOf(org.mothvpn.core.LargeTitleSize.DEFAULT.toFloat())
    var enabled by androidx.compose.runtime.mutableStateOf(true)
}

/** How much taller the header is when expanded: [LargeTitleTuning.percent] of the screen height, kept within 56–260 dp; animated, so a
 *  new value (the Default button, a restored backup) grows or shrinks the header smoothly instead of jumping. */
@Composable private fun largeTitleExtra(): Float {
    val target = org.mothvpn.core.LargeTitleSize.extraDp(LargeTitleTuning.percent, LocalConfiguration.current.screenHeightDp)
    val motion = animationsOn()
    val value by androidx.compose.animation.core.animateFloatAsState(target,
        if (motion) androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow) else androidx.compose.animation.core.snap(), label = "largeTitleExtra")
    return value
}

/**
 * The header. Expanded (top of the page): a big title centred in the extra space at the top, the buttons ([actions]) in the row under
 * it, at the right. Collapsing (the page scrolls up): the extra space shrinks and the same title shrinks and moves into the start of the
 * button row, where it ends at the compact size (headlineSmall). One title, morphing; nothing fades or swaps, so accessibility and UI
 * tests always see it once. A long title is ellipsized to fit the compact slot and scaled down to fit the width when expanded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LargeTitleHeader(largeTitle: LargeTitle, title: String, modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}) {
    val density = LocalDensity.current
    // Large titles off (Settings › Interface): no extra space and the title stays at the compact size, like a plain top bar. The switch
    // animates between the two (the extra space folds away while the title shrinks into its compact place).
    val motion = animationsOn()
    val off by androidx.compose.animation.core.animateFloatAsState(if (LargeTitleTuning.enabled) 0f else 1f,
        if (motion) androidx.compose.animation.core.tween(Motion.SHORT) else androidx.compose.animation.core.snap(), label = "largeTitleOff")
    val extraPx = with(density) { largeTitleExtra().dp.toPx() } * (1f - off)
    val state = largeTitle.behavior.state
    SideEffect { if (state.heightOffsetLimit != -extraPx) state.heightOffsetLimit = -extraPx }
    val big = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Medium)
    val compact = MaterialTheme.typography.headlineSmall
    val ratio = with(density) { compact.fontSize.toPx() / big.fontSize.toPx() }
    Layout(content = {
        Text(title, style = big, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }, modifier = modifier.fillMaxWidth().clipToBounds()) { measurables, constraints ->
        val width = constraints.maxWidth
        val fraction = maxOf(state.collapsedFraction.coerceIn(0f, 1f), off)
        val row = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val rowHeight = maxOf(48.dp.roundToPx(), row.height)
        val gap = if (row.width > 0) 8.dp.roundToPx() else 0
        // Measured at the big size, as wide as the compact slot allows once scaled down to the compact size.
        val slot = (width - row.width - gap).coerceAtLeast(1)
        val text = measurables[0].measure(Constraints(maxWidth = (slot / ratio).toInt().coerceAtLeast(1)))
        val side = 8.dp.roundToPx()
        val expandedScale = minOf(1f, (width - 2 * side).coerceAtLeast(1) / text.width.coerceAtLeast(1).toFloat())
        val scale = expandedScale + (ratio - expandedScale) * fraction
        val extraNow = (extraPx + state.heightOffset).coerceIn(0f, extraPx)
        val height = (extraNow + rowHeight).roundToInt()
        // Centre: in the middle of the full extra space when expanded, in the middle of the button row when collapsed.
        val x = (width - text.width * expandedScale) / 2f * (1f - fraction)
        val centreY = extraPx / 2f + (rowHeight / 2f - extraPx / 2f) * fraction
        val y = centreY - text.height * scale / 2f
        layout(width, height) {
            text.placeWithLayer(x.roundToInt(), y.roundToInt()) { transformOrigin = TransformOrigin(0f, 0f); scaleX = scale; scaleY = scale }
            row.place(width - row.width, (extraNow + (rowHeight - row.height) / 2f).roundToInt())
        }
    }
}
