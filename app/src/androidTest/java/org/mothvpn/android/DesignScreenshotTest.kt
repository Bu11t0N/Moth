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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mothvpn.core.ClientSettings
import java.io.File

/**
 * 0.7 "New design" (developer toggle) on the CI emulator: home off/connecting/on, the server sheet, the servers tab and
 * Settings, in the dark and the light theme. Screenshots go to the app's external cache (design/), pulled as a CI artifact.
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

    private fun screens(theme: String) {
        reset()
        SettingsStore(context).save(ClientSettings(hwid = hwid, theme = theme, newDesign = true))
        eventually(30_000, "subscription downloaded") { SubscriptionRefresh.run(context, null, "https://10.0.2.2:8443/sub", null, SettingsStore(context).load(), "e2e"); true }
        val store = SubscriptionStore(context)
        val profile = store.load()!!.profiles.first { it.name == "E2E VLESS" }
        store.save(store.load()!!.copy(selected = profile.id, wifiSelected = profile.id, mobileSelected = profile.id))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(800)
        // Ping values for the dots: check all servers from the Servers tab.
        tab("Servers")
        wait(By.desc("Check servers").enabled(true), 30_000).click()
        eventually(5_000, "checks started") { Diagnostics.running.value }
        eventually(90_000, "checks finished") { !Diagnostics.running.value }
        Thread.sleep(800)
        shot("servers-$theme")
        tab("Home")
        wait(By.desc("Connect VPN").enabled(true))
        shot("home-off-$theme")
        wait(By.desc("Connect VPN").enabled(true)).click()
        // The UI must already show the connecting state (rings) when the screenshot is taken.
        val shown = device.wait(Until.findObject(By.text("Connecting…")), 10_000) != null
        Thread.sleep(300) // let the rings grow a little
        val connecting = TunnelStatus.state.value == TunnelState.STARTING && shown
        shot("home-connecting-$theme")
        log("connecting screenshot taken while STARTING: $connecting")
        wait(By.text("VPN is on"), 60_000); Thread.sleep(2500)
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
}
