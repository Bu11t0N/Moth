package org.mothvpn.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mothvpn.core.TrafficHistory
import java.io.File
import java.time.LocalDate

/** Opens the statistics screen with sample data, taps today's bar and saves a screenshot (CI artifact). */
@RunWith(AndroidJUnit4::class)
class StatsScreenshotTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun statsScreen() {
        val today = LocalDate.now()
        val mb = 1024L * 1024
        val sample = listOf(0, 312, 95, 0, 540, 228, 410, 0, 0, 180, 66, 720, 305, 150)
        var h = TrafficHistory()
        sample.forEachIndexed { i, v -> if (v > 0) h = h.add(today.minusDays((sample.size - 1 - i).toLong()), v * mb / 9, v * mb) }
        TrafficRecorder.replaceForTest(context, h)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Settings")), 20_000)); Thread.sleep(800)
        device.findObject(By.text("Settings")).click(); Thread.sleep(800)
        device.wait(Until.findObject(By.text("Statistics")), 10_000).click()
        val chart = device.wait(Until.findObject(By.desc("Daily traffic")), 10_000)
        assertNotNull("chart not shown", chart)
        device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.25f)
        Thread.sleep(1200)
        // Re-find the chart after the scroll and retry the tap: a tap during the scroll settle was lost on CI.
        var detail: androidx.test.uiautomator.UiObject2? = null
        repeat(3) {
            if (detail != null) return@repeat
            val b = (device.findObject(By.desc("Daily traffic")) ?: chart).visibleBounds
            val left = b.left + (48 * context.resources.displayMetrics.density).toInt()
            device.click(b.right - (b.right - left) / 14, b.bottom - b.height() / 4)
            detail = device.wait(Until.findObject(By.textContains("↑")), 3_000)
            // The detail line sits above the chart and may have scrolled out of view.
            if (detail == null && device.findObject(By.text("Tap a bar for that day.")) == null) {
                device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.UP, 0.25f); Thread.sleep(600)
                detail = device.findObject(By.textContains("↑"))
            }
        }
        assertNotNull("tap detail not shown", detail)
        device.takeScreenshot(File(context.externalCacheDir, "stats.png"))
        device.pressBack(); Thread.sleep(500)
    }

    @Test fun settingsScreens() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Settings")), 20_000)); Thread.sleep(800)
        device.findObject(By.text("Settings")).click()
        assertNotNull(device.wait(Until.findObject(By.text("Routing and DNS")), 10_000)); Thread.sleep(1000)
        device.takeScreenshot(File(context.externalCacheDir, "settings.png"))
        var dev = device.findObject(By.text("For developers"))
        repeat(5) { if (dev == null) { device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.5f); Thread.sleep(400); dev = device.findObject(By.text("For developers")) } }
        assertNotNull("developer menu entry", dev); dev!!.click()
        assertNotNull(device.wait(Until.findObject(By.text("Crash log")), 10_000)); Thread.sleep(1000)
        device.takeScreenshot(File(context.externalCacheDir, "dev.png"))
        device.pressBack(); Thread.sleep(500)
    }
}
