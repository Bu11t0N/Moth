package org.mothvpn.android

import android.app.Activity
import android.content.pm.ActivityInfo
import org.mothvpn.core.PhonePortrait

/** Locks phones to portrait; tablets follow the system rotation setting. The QR scanner stays portrait on every device. */
object ScreenOrientation {
    fun apply(activity: Activity) {
        if (activity is PortraitCaptureActivity) return
        val target = if (PhonePortrait.lock(activity.resources.configuration.smallestScreenWidthDp))
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_USER
        if (activity.requestedOrientation != target) activity.requestedOrientation = target
    }
}
