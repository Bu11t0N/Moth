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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mothvpn.core.ClientSettings
import java.io.File

/**
 * 1.0.4-beta.2 on the CI emulator (synthetic fixture subscription only): the Updates page never scrolls on short screens with large
 * fonts, the Home server sheet follows "Hide unavailable", keyword filters switch on and off, and the developer change history.
 * Screenshots go to the e2e cache artifact; only screen names and states are logged.
 */
@RunWith(AndroidJUnit4::class)
class Release104E2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val out = File(context.externalCacheDir, "ui104").apply { mkdirs() }
    private fun log(msg: String) = android.util.Log.i("MothE2E", msg)
    private fun shell(cmd: String) { instrumentation.uiAutomation.executeShellCommand(cmd).use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes() } }
    private fun wait(selector: BySelector, ms: Long = 30_000): UiObject2 =
        device.wait(Until.findObject(selector), ms) ?: run { runCatching { device.dumpWindowHierarchy(File(out, "window.xml")) }; throw AssertionError("not found: $selector") }
    private fun eventually(ms: Long, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (runCatching(check).getOrDefault(false)) return; Thread.sleep(200) }
        throw AssertionError("timed out: $what")
    }
    private fun shot(name: String) { runCatching { device.takeScreenshot(File(out, "$name.png")) } }
    private fun off() = TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !TunnelStatus.blocking.value
    private fun reset() {
        if (!off()) { context.startService(Intent(context, TunnelService::class.java).setAction("STOP")); eventually(30_000, "VPN off") { off() } }
        SubscriptionStore(context).clear(); Onboarding.markDone(context); SettingsStore(context).save(ClientSettings())
    }
    @Before fun before() { System.setProperty("http.keepAlive", "false"); reset() }
    @After fun after() { runCatching { device.pressHome() }; shell("wm size reset"); shell("settings put system font_scale 1.0"); reset() }

    private fun fixture(settings: ClientSettings = ClientSettings()) {
        SettingsStore(context).save(settings)
        eventually(30_000, "subscription downloaded") { SubscriptionRefresh.run(context, null, "https://10.0.2.2:8443/sub", null, SettingsStore(context).load(), "e2e"); true }
        val store = SubscriptionStore(context)
        val profile = store.load()!!.profiles.first { it.name == "E2E VLESS" }
        store.save(store.load()!!.copy(selected = profile.id, wifiSelected = profile.id, mobileSelected = profile.id))
    }
    private fun launch() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(1200)
    }
    private fun scrollTo(text: String) {
        repeat(8) { if (device.findObject(By.text(text)) == null) { device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.5f); Thread.sleep(400) } }
    }

    /** Updates page: no scrollable container at any size; the main button sits fully above the tab bar; the moth shrinks or is hidden. */
    @Test fun updatesPageNeverScrolls() {
        for ((size, font) in listOf("1080x1600" to 1.3f, "1080x1600" to 1.0f, "1080x1900" to 1.5f, "1080x2400" to 1.3f, "1080x2400" to 1.0f)) {
            shell("wm size $size"); shell("settings put system font_scale $font"); Thread.sleep(1500)
            launch()
            device.findObject(By.desc("Connect VPN"))?.visibleBounds?.let { log("home-$size-$font: moth button=$it") }
            shot("home-$size-$font")
            wait(By.text("Settings")).click(); Thread.sleep(900)
            scrollTo("Updates"); wait(By.text("Updates")).click()
            val button = wait(By.text("Check for updates")).visibleBounds; Thread.sleep(1200)
            val tag = "updates-$size-$font"
            shot(tag)
            assertNull("Updates page must not scroll ($tag)", device.findObject(By.scrollable(true)))
            val tabs = wait(By.text("Servers")).visibleBounds
            val installed = wait(By.text("Installed version")).visibleBounds
            val again = device.findObject(By.text("Check for updates"))!!.visibleBounds
            log("$tag: button=$again tabs=$tabs installed=$installed")
            assertEquals("main button moved ($tag)", button, again)
            assertTrue("main button cut off or under the tab bar ($tag)", again.bottom <= tabs.top && again.height() > 0)
            assertTrue("version row cut off ($tag)", installed.height() > 0 && installed.bottom < again.top)
            // The status line (when this size has room for it) sits fully below the large title and above the version card.
            // A failed automatic check (no route to GitHub on CI) shows an error notice over the title for a while: wait it out.
            wait(By.text("Updates"), 20_000)
            val title = device.findObjects(By.text("Updates")).maxByOrNull { it.visibleBounds.height() }!!.visibleBounds
            device.findObject(By.textStartsWith("You have the latest"))?.visibleBounds?.let { st ->
                log("$tag: status=$st title=$title")
                assertTrue("status line under the title or the card ($tag)", st.top >= title.bottom && st.bottom <= installed.top)
            }
            // While a check runs the main button itself is Cancel (there is no other Cancel on the page), in the same place.
            AppUpdater.testCheckDelayMs = 15_000
            try {
                device.findObject(By.text("Check for updates"))!!.click()
                val cancel = wait(By.text("Cancel"), 5_000).visibleBounds; Thread.sleep(1200)
                shot("$tag-checking")
                assertEquals("only the main button offers Cancel ($tag)", 1, device.findObjects(By.text("Cancel")).size)
                assertTrue("Cancel replaces the main button in place ($tag)", kotlin.math.abs(button.centerY() - cancel.centerY()) <= 2 && kotlin.math.abs(button.centerX() - cancel.centerX()) <= 2)
                assertNull("Updates page must not scroll while checking ($tag)", device.findObject(By.scrollable(true)))
                device.findObject(By.text("Checking for updates…"))?.visibleBounds?.let { st ->
                    log("$tag-checking: status=$st cancel=$cancel")
                    assertTrue("checking status above the card ($tag)", st.bottom <= installed.top)
                }
                device.findObject(By.text("Cancel"))!!.click()
                wait(By.text("Check for updates"), 5_000)
            } finally { AppUpdater.testCheckDelayMs = 0 }
            device.pressBack(); Thread.sleep(600)
        }
    }

    /** Home server sheet: with "Hide unavailable" on, a server whose last check failed is not listed, and the header says the filter is on. */
    @Test fun homeServerSheetHidesUnavailable() {
        fixture(ClientSettings(hideUnavailable = true))
        launch()
        val book = SubscriptionStore(context).loadBook()!!
        val trojan = book.profiles.first { it.name == "E2E Trojan" }
        val now = System.currentTimeMillis()
        Diagnostics.resultsMutable.value = Diagnostics.resultsMutable.value + listOf("wifi", "mobile").associate { "$it:${trojan.id}" to ProbeResult(error = "e2e", checkedAt = now, kind = org.mothvpn.core.ProbeErrorKind.TIMEOUT) }
        wait(By.descStartsWith("Choose server")).click()
        wait(By.descStartsWith("E2E VLESS")); Thread.sleep(800)
        shot("home-sheet-hide-unavailable")
        assertNotNull("filter label in the sheet header", device.findObject(By.text("Hide unavailable")))
        assertNull("unavailable server listed", device.findObject(By.descStartsWith("E2E Trojan")))
        device.pressBack(); Thread.sleep(700)
        // Off: everything is listed and there is no label.
        SettingsStore(context).save(SettingsStore(context).load().copy(hideUnavailable = false)); launch()
        Diagnostics.resultsMutable.value = Diagnostics.resultsMutable.value + listOf("wifi", "mobile").associate { "$it:${trojan.id}" to ProbeResult(error = "e2e", checkedAt = now, kind = org.mothvpn.core.ProbeErrorKind.TIMEOUT) }
        wait(By.descStartsWith("Choose server")).click()
        wait(By.descStartsWith("E2E Trojan")); Thread.sleep(500)
        assertNull("no label when the filter is off", device.findObject(By.text("Hide unavailable")))
        device.pressBack()
    }

    /** Keyword filter: a checkbox switches a keyword off (it stays in the list, the servers come back) and on again. */
    @Test fun filterKeywordSwitchesOnAndOff() {
        fixture()
        launch()
        wait(By.text("Servers")).click(); Thread.sleep(900)
        wait(By.text("Filter")).click()
        wait(By.clazz("android.widget.EditText")).text = "Trojan"
        wait(By.text("Add")).click(); Thread.sleep(800)
        fun rule() = SettingsStore(context).load().let { listOf(it.wifiFilter, it.mobileFilter).first { r -> r.keywords.isNotEmpty() } }
        eventually(5_000, "keyword saved") { rule().active == listOf("Trojan") }
        wait(By.text("Trojan")).click(); Thread.sleep(800)
        shot("filter-keyword-off")
        eventually(5_000, "keyword switched off") { rule().keywords == listOf("Trojan") && rule().active.isEmpty() }
        assertNotNull("keyword stays in the list", device.findObject(By.text("Trojan")))
        wait(By.text("Trojan")).click(); Thread.sleep(800)
        eventually(5_000, "keyword switched on") { rule().active == listOf("Trojan") }
        wait(By.desc("Remove Trojan")).click(); Thread.sleep(800)
        eventually(5_000, "keyword deleted") { SettingsStore(context).load().let { it.wifiFilter.keywords.isEmpty() && it.mobileFilter.keywords.isEmpty() } }
    }

    /** Developer change history: an update that brings back a removed server and renames another is listed; a tap shows the field. */
    @Test fun changeHistoryListsAddedAndChangedServers() {
        fixture()
        val store = SubscriptionStore(context)
        // Pretend the previous download had no "E2E Trojan" and called "E2E VLESS" differently, then update for real.
        store.update { b -> b.copy(sources = b.sources.map { s -> s.copy(profiles = s.profiles.filter { it.name != "E2E Trojan" }.map { if (it.name == "E2E VLESS") it.copy(name = "E2E VLESS old") else it }) }) }
        val id = store.loadBook()!!.sources.first().id
        SubscriptionRefresh.run(context, id, "https://10.0.2.2:8443/sub", null, SettingsStore(context).load(), "e2e")
        val entry = SubscriptionChangeLog.of(context, id).first()
        assertEquals(listOf("E2E Trojan"), entry.added.map { it.name })
        assertEquals(listOf("E2E VLESS"), entry.changed.map { it.after.name })
        // Off (default): no "Changes" in the subscription menu.
        launch()
        wait(By.text("Servers")).click(); Thread.sleep(900)
        wait(By.desc("Subscription actions")).click(); wait(By.text("Delete")); Thread.sleep(500)
        assertNull("Changes hidden while the developer option is off", device.findObject(By.text("Changes")))
        device.pressBack(); Thread.sleep(600)
        SettingsStore(context).save(SettingsStore(context).load().copy(changeHistory = true))
        launch()
        wait(By.text("Servers")).click(); Thread.sleep(900)
        wait(By.desc("Subscription actions")).click()
        wait(By.text("Changes")).click()
        val counts = wait(By.text("Added: 1 · Changed: 1")).visibleBounds; Thread.sleep(600)
        // The server list behind the sheet has the same names: take the rows below the card's header.
        fun inSheet(text: String) = device.findObjects(By.text(text)).firstOrNull { it.visibleBounds.top > counts.top } ?: throw AssertionError("not in the sheet: $text")
        inSheet("E2E Trojan")
        shot("changes-sheet")
        inSheet("E2E VLESS").click()
        wait(By.text("Name")); wait(By.text("E2E VLESS old  →  E2E VLESS")); Thread.sleep(400)
        shot("changes-detail")
        device.pressBack(); Thread.sleep(500); device.pressBack()
    }
}
