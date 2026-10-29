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
    /** The Material behaviour's connection; while large titles are off (no limit) it also notes whether the page left its top. */
    val nestedScrollConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        private val inner get() = behavior.nestedScrollConnection
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) = inner.onPreScroll(available, source)
        override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (behavior.state.heightOffsetLimit > -0.5f) { if (consumed.y < 0f) wasCollapsed = true else if (available.y > 0f) wasCollapsed = false }
            return inner.onPostScroll(consumed, available, source)
        }
        override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity) = inner.onPreFling(available)
        override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) = inner.onPostFling(consumed, available)
    }
    /** Last known collapsed state; used when the limit changes while it is 0 (large titles off), where the offset says nothing. */
    internal var wasCollapsed = false

    /** Expanded (top of the page) or collapsed (the page is scrolled), animated; used when the page under the header changes. */
    suspend fun settleTo(collapsed: Boolean, animated: Boolean) {
        val state = behavior.state
        wasCollapsed = collapsed
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

/** How much taller the header is when expanded: [LargeTitleTuning.percent] of the screen height, kept within 56–260 dp, and the size of
 *  the expanded title for that height ([org.mothvpn.core.LargeTitleSize.textScale]); animated, so a new value (the Default button, a
 *  restored backup) grows or shrinks the header and its title smoothly instead of jumping. */
@Composable private fun largeTitleExtra(): Pair<Float, Float> {
    val target = org.mothvpn.core.LargeTitleSize.extraDp(LargeTitleTuning.percent, LocalConfiguration.current.screenHeightDp)
    val motion = animationsOn()
    fun <T> spec(): androidx.compose.animation.core.AnimationSpec<T> =
        if (motion) androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow) else androidx.compose.animation.core.snap()
    val value by androidx.compose.animation.core.animateFloatAsState(target, spec(), label = "largeTitleExtra")
    val scale by androidx.compose.animation.core.animateFloatAsState(org.mothvpn.core.LargeTitleSize.textScale(LargeTitleTuning.percent), spec(), label = "largeTitleText")
    return value to scale
}

/**
 * The header. Expanded (top of the page): a big title centred in the free space, the buttons ([actions]) in the row under it, at the
 * right; its size follows the height setting. Collapsing (the page scrolls up): the extra space shrinks and the same title shrinks and
 * moves into the start of the button row, where it ends at the compact size (headlineSmall). One title, morphing; nothing fades or swaps,
 * so accessibility and UI tests always see it once. A long title is ellipsized to fit the compact slot and scaled down to fit when expanded.
 * The space above and below the title is balanced (without buttons the empty button row is part of the space), and extra space under the
 * header (no divider line) separates it from the page content, so the title does not read as the first item of the list.
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
    val (extraDp, textScale) = largeTitleExtra()
    val extraPx = with(density) { extraDp.dp.toPx() } * (1f - off)
    val state = largeTitle.behavior.state
    // A new height (the Interface slider, Default, large titles on/off) changes how far the header can fold. A collapsed header (the page
    // is scrolled) stays collapsed at the new limit; the new height shows when the page is back at its top. Without this, a taller
    // limit would open the header part-way while the page is scrolled. [LargeTitle.wasCollapsed] keeps the state while there is no limit (off).
    SideEffect {
        val old = state.heightOffsetLimit
        if (old != -extraPx) {
            val collapsed = if (old < -0.5f && old != -Float.MAX_VALUE) state.heightOffset <= old + 0.5f else largeTitle.wasCollapsed
            state.heightOffsetLimit = -extraPx
            if (collapsed) state.heightOffset = -extraPx
        }
        if (state.heightOffsetLimit < -0.5f) largeTitle.wasCollapsed = state.heightOffset <= state.heightOffsetLimit + 0.5f
    }
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
        val top = 12.dp.toPx(); val bottom = 8.dp.toPx()
        val gap = if (row.width > 0) 8.dp.roundToPx() else 0
        // Measured at the big size, as wide as the compact slot allows once scaled down to the compact size.
        val slot = (width - row.width - gap).coerceAtLeast(1)
        val text = measurables[0].measure(Constraints(maxWidth = (slot / ratio).toInt().coerceAtLeast(1)))
        val side = 8.dp.roundToPx()
        val extraNow = (extraPx + state.heightOffset).coerceIn(0f, extraPx)
        val height = (top + extraNow + rowHeight + bottom).roundToInt() + 8.dp.roundToPx() // + the gap to the content
        // Expanded: centred in the free space (above the buttons, or the whole header when there are none), as big as the height setting
        // says but never wider than the screen or taller than that space. Collapsed: in the middle of the button row, compact.
        val band = if (row.width > 0) top + extraPx else top + extraPx + rowHeight + bottom
        val expandedScale = minOf(textScale, (width - 2 * side).coerceAtLeast(1) / text.width.coerceAtLeast(1).toFloat(),
            (band - 8.dp.toPx()).coerceAtLeast(1f) / text.height.coerceAtLeast(1)).coerceAtLeast(ratio)
        val scale = expandedScale + (ratio - expandedScale) * fraction
        val x = (width - text.width * expandedScale) / 2f * (1f - fraction)
        val centreY = band / 2f + (top + rowHeight / 2f - band / 2f) * fraction
        val y = centreY - text.height * scale / 2f
        layout(width, height) {
            text.placeWithLayer(x.roundToInt(), y.roundToInt()) { transformOrigin = TransformOrigin(0f, 0f); scaleX = scale; scaleY = scale }
            row.place(width - row.width, (top + extraNow + (rowHeight - row.height) / 2f).roundToInt())
        }
    }
}
