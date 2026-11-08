package org.mothvpn.core

object MothArtworkSize {
    const val DRAWING_RATIO = 210f / 260f
    /** Same 48–260 dp range as Home; leave room for controls at large text sizes. */
    fun side(width: Float, height: Float, fontScale: Float): Float =
        minOf(260f, (width - 40f).coerceAtLeast(48f), (height - 560f * fontScale.coerceAtLeast(1f)).coerceAtLeast(48f))
}
