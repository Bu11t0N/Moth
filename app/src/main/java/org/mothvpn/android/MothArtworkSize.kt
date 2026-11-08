package org.mothvpn.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Identical artwork on Home and Updates, including large fonts and small windows. */
@Composable fun mothArtworkSize(): androidx.compose.ui.unit.Dp {
    val window = LocalConfiguration.current
    return org.mothvpn.core.MothArtworkSize.side(window.screenWidthDp.toFloat(), window.screenHeightDp.toFloat(), LocalDensity.current.fontScale).dp
}
