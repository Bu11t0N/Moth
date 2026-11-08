package org.mothvpn.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Identical artwork on Home and Updates, including large fonts and small windows. */
@Composable fun mothArtworkSize(): androidx.compose.ui.unit.Dp {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) { org.mothvpn.core.MothArtworkSize.side(window.width.toDp().value, window.height.toDp().value, fontScale).dp }
}
