package org.mothvpn.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mothvpn.core.ClientSettings
import java.io.File

/**
 * 0.7 "New design" (developer toggle) on the CI emulator: home off/connecting (moth flapping, 3 frames)/on, the server sheet,
 * the servers tab (group header, collapsed group, add button) and Settings, in the dark and the light theme. Also checks that the moth
 * button does not move when the status lines change. Screenshots go to the app's external cache (design/), pulled as a CI artifact.
 * Only screen names and states are logged, never server data.
 */
@RunWith(AndroidJUnit4::class)
class DesignScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val out = File(context.externalCacheDir, "design").apply { mkdirs() }
    private val hwid by lazy { runCatching { SettingsStore(context).load().hwid }.getOrDefault("e2e-hwid-0000") }

    private fun log(msg: String) = android.util.Log.i("MothE2E", msg)
    private fun wait(selector: BySelector, ms: Long = 30_000): UiObject2 =
        device.wait(Until.findObject(selector), ms) ?: run { runCatching { device.dumpWindowHierarchy(File(out, "window.xml")) }; throw AssertionError("not found: $selector") }
    private fun eventually(ms: Long, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (runCatching(check).getOrDefault(false)) return; Thread.sleep(200) }
        throw AssertionError("timed out: $what")
    }
    private fun shot(name: String) { device.waitForIdle(); assertTrue("screenshot $name", device.takeScreenshot(File(out, "$name.png"))); log("design screenshot: $name") }
    private fun off() = TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !TunnelStatus.blocking.value
    private fun stopVpn() {
        if (!off()) { context.startService(Intent(context, TunnelService::class.java).setAction("STOP")); eventually(30_000, "VPN off") { off() } }
    }
    private fun reset() { stopVpn(); SubscriptionStore(context).clear(); SettingsStore(context).save(ClientSettings(hwid = hwid)) }
    @Before fun before() { System.setProperty("http.keepAlive", "false"); reset() }
    @After fun after() { runCatching { device.pressHome() }; reset() }

    private fun tab(name: String) { wait(By.text(name)).click(); Thread.sleep(900) }

    private fun screens(theme: String, amoled: Boolean = false) {
        reset()
        SettingsStore(context).save(ClientSettings(hwid = hwid, theme = if (amoled) "dark" else theme, newDesign = true, amoled = amoled))
        eventually(30_000, "subscription downloaded") { SubscriptionRefresh.run(context, null, "https://10.0.2.2:8443/sub", null, SettingsStore(context).load(), "e2e"); true }
        val store = SubscriptionStore(context)
        val profile = store.load()!!.profiles.first { it.name == "E2E VLESS" }
        store.save(store.load()!!.copy(selected = profile.id, wifiSelected = profile.id, mobileSelected = profile.id))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(800)
        // Ping values for the dots: the group header's check button (the new design has no toolbar icons).
        tab("Servers")
        wait(By.desc("Add subscription"))
        wait(By.desc("Check servers").enabled(true), 30_000).click()
        eventually(5_000, "checks started") { Diagnostics.running.value }
        eventually(90_000, "checks finished") { !Diagnostics.running.value }
        Thread.sleep(800)
        shot("servers-$theme")
        wait(By.descStartsWith("Collapse:")).click(); Thread.sleep(700)
        shot("servers-collapsed-$theme")
        wait(By.descStartsWith("Expand:")).click(); Thread.sleep(500)
        // Menus are bottom sheets with full-width rows: the subscription menu and a server's long-press menu.
        wait(By.desc("Subscription actions")).click(); wait(By.text("Delete")); Thread.sleep(700)
        shot("group-menu-$theme"); device.pressBack(); Thread.sleep(700)
        wait(By.descStartsWith("E2E Trojan")).longClick(); wait(By.text("Share server")); Thread.sleep(700)
        shot("server-menu-$theme"); device.pressBack(); Thread.sleep(700)
        tab("Home")
        val offBounds = wait(By.desc("Connect VPN").enabled(true)).visibleBounds
        shot("home-off-$theme")
        // Connecting lasts about a second here: grab frames while STARTING, keep the last few (moth flapping) and save three of them.
        wait(By.desc("Connect VPN").enabled(true)).click()
        val clicked = System.currentTimeMillis()
        val frames = ArrayDeque<android.graphics.Bitmap>()
        eventually(10_000, "connecting started") { TunnelStatus.state.value != TunnelState.OFF }
        while (TunnelStatus.state.value == TunnelState.STARTING && System.currentTimeMillis() - clicked < 15_000) {
            val bmp = instrumentation.uiAutomation.takeScreenshot()
            if (TunnelStatus.state.value == TunnelState.STARTING && System.currentTimeMillis() - clicked > 250 && bmp != null) {
                frames.addLast(bmp); if (frames.size > 5) frames.removeFirst().recycle()
            } else bmp?.recycle()
        }
        fun save(f: android.graphics.Bitmap, name: String) { File(out, "$name.png").outputStream().use { f.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; log("design screenshot: $name") }
        frames.lastOrNull()?.let { save(it, "home-connecting-$theme") }
        val picks = if (frames.size >= 3) listOf(0, frames.size / 2, frames.size - 1) else frames.indices.toList()
        picks.forEachIndexed { i, idx -> save(frames.elementAt(idx), "flap-${i + 1}-$theme") }
        log("connecting frames taken while STARTING: ${frames.size}")
        frames.forEach { it.recycle() }
        wait(By.text("VPN is on"), 60_000)
        // The health line ("Checking internet access…", then the result) must not push the moth down.
        eventually(20_000, "health line") { device.findObject(By.textStartsWith("Checking internet")) != null || device.findObject(By.textContains("available")) != null }
        assertEquals("moth button moved when the status changed", offBounds, wait(By.desc("Disconnect VPN")).visibleBounds)
        Thread.sleep(2500)
        assertEquals("moth button moved when the status changed", offBounds, wait(By.desc("Disconnect VPN")).visibleBounds)
        shot("home-on-$theme")
        wait(By.descStartsWith("Choose server")).click()
        wait(By.text("Servers")); Thread.sleep(1200)
        shot("sheet-$theme")
        device.pressBack(); Thread.sleep(800)
        stopVpn()
        tab("Settings"); Thread.sleep(600)
        shot("settings-$theme")
    }

    @Test fun newDesignDark() = screens("dark")
    @Test fun newDesignLight() = screens("light")
    @Test fun newDesignAmoled() = screens("amoled", amoled = true)
}
