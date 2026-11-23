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

    /** Smallest moth the Updates page still draws; below this the page shows no moth at all. */
    const val MIN_PAGE_DRAWING = 72f

    /**
     * Updates page artwork for a page [width] dp wide: the largest Home button (260 dp), never wider than the page. Unlike [side] it
     * reserves no room for Home's controls; the page fits the drawing to its free height with [fitPageDrawing].
     */
    fun pageArtwork(width: Float): Float = minOf(260f, width.coerceAtLeast(0f))

    /** Updates page: Home-sized drawing shrunk to [room]; 0 (no moth) when that would be under [MIN_PAGE_DRAWING]. */
    fun fitPageDrawing(artwork: Float, room: Float): Float =
        fitDrawing(artwork, room).let { if (it < MIN_PAGE_DRAWING) 0f else it }

    /** Same 48–260 dp range as Home; leave room for controls at large text sizes. */
    fun side(width: Float, height: Float, fontScale: Float): Float =
        minOf(260f, (width - 40f).coerceAtLeast(48f), (height - 560f * fontScale.coerceAtLeast(1f)).coerceAtLeast(48f))
}
