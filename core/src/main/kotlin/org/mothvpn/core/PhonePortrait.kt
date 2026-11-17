package org.mothvpn.core

/** Phones stay in portrait; tablets (sw ≥ 600 dp) may rotate. Uses smallest width, so a phone in landscape still counts as a phone. */
object PhonePortrait {
    const val TABLET_SW_DP = 600
    fun lock(smallestScreenWidthDp: Int): Boolean = smallestScreenWidthDp < TABLET_SW_DP
}
