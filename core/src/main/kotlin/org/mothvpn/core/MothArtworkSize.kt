package org.mothvpn.core

object MothArtworkSize {
    const val DRAWING_RATIO = 210f / 260f
    /** Antenna tip in the 100-unit drawing (stroke 2.2 sits on top of this). */
    const val ANTENNA_TIP = 17f
    const val ANTENNA_STROKE = 2.2f

    /** Canvas that draws the moth at the same scale as a Home button of [artwork] dp. */
    fun drawingSide(artwork: Float): Float = artwork * DRAWING_RATIO

    /**
     * Home-sized moth canvas, reduced to [room] when the slot is shorter, so the full drawing
     * (antennae included) stays visible. The square is never stretched.
     */
    fun fitDrawing(artwork: Float, room: Float): Float =
        minOf(drawingSide(artwork), room.coerceAtLeast(0f))

    /** Same 48–260 dp range as Home; leave room for controls at large text sizes. */
    fun side(width: Float, height: Float, fontScale: Float): Float =
        minOf(260f, (width - 40f).coerceAtLeast(48f), (height - 560f * fontScale.coerceAtLeast(1f)).coerceAtLeast(48f))
}
