package org.mothvpn.core

/**
 * The soft light behind the home moth ("a moth flying to the light"): dim and cool when off, warm and pulsing with the wing beat
 * while connecting or checking servers, bright and steady when connected. Only the levels live here; colours are in the app.
 */
object HomeGlow {
    enum class Level { OFF, BEAT, ON }

    /** [beat]: the moth flaps (connecting/disconnecting, or a server check with the VPN off or on). */
    fun level(on: Boolean, beat: Boolean): Level = when { beat -> Level.BEAT; on -> Level.ON; else -> Level.OFF }

    /** Centre opacity at rest and the extra opacity added at full wing spread (0 = steady). */
    data class Intensity(val base: Float, val pulse: Float)
    fun intensity(level: Level, dark: Boolean, animations: Boolean): Intensity {
        val i = when (level) {
            Level.OFF -> Intensity(if (dark) 0.10f else 0.08f, 0f)
            Level.BEAT -> Intensity(if (dark) 0.14f else 0.12f, if (dark) 0.12f else 0.10f)
            Level.ON -> Intensity(if (dark) 0.30f else 0.26f, 0f)
        }
        // "Remove animations": no pulsing; a beat shows its mid level as a static glow.
        return if (animations) i else Intensity(i.base + i.pulse / 2f, 0f)
    }
    /** Opacity at the centre for wing spread [open] (0..1). */
    fun alpha(i: Intensity, open: Float): Float = (i.base + i.pulse * open.coerceIn(0f, 1f)).coerceIn(0f, 0.4f)

    /** Glow radius relative to the moth button size: it fades out around the moth, before the status text below it. */
    const val RADIUS = 0.7f
}
