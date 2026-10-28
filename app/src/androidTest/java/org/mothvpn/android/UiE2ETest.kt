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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mothvpn.core.ClientSettings
import java.io.File

/**
 * Functional UI checks on the CI emulator, without screenshots: the Servers tab (checks, ⋮ menu, wrong-network notice, collapse,
 * menus, keyword filter), Home (no banner, the moth does not move when the status changes, server sheet, subscriptions sheet),
 * the widget views, Settings (ping method, developer menu, device-info dialog incl. a short screen with a large font),
 * live settings while connected, short-screen layout by bounds, and the statistics screen.
 * Only screen names and states are logged, never server data.
 */
@RunWith(AndroidJUnit4::class)
class UiE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val out = File(context.externalCacheDir, "ui").apply { mkdirs() }
    private val hwid by lazy { runCatching { SettingsStore(context).load().hwid }.getOrDefault("e2e-hwid-0000") }

    private fun log(msg: String) = android.util.Log.i("MothE2E", msg)
    /** Click that survives a recomposition between finding the node and clicking it. */
    private fun tap(selector: BySelector) {
        // Tap by coordinates: the node can be replaced between finding it and clicking it (the header recomposes while results arrive).
        repeat(5) { attempt ->
            try { val b = wait(selector).visibleBounds; device.click(b.centerX(), b.centerY()); return }
            catch (e: androidx.test.uiautomator.StaleObjectException) { if (attempt == 4) throw e; Thread.sleep(300) }
        }
    }
    private fun wait(selector: BySelector, ms: Long = 30_000): UiObject2 =
        device.wait(Until.findObject(selector), ms) ?: run { runCatching { device.dumpWindowHierarchy(File(out, "window.xml")) }; throw AssertionError("not found: $selector") }
    private fun eventually(ms: Long, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (runCatching(check).getOrDefault(false)) return; Thread.sleep(200) }
        throw AssertionError("timed out: $what")
    }
    private fun off() = TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !TunnelStatus.blocking.value
    private fun stopVpn() {
        if (!off()) { context.startService(Intent(context, TunnelService::class.java).setAction("STOP")); eventually(30_000, "VPN off") { off() } }
    }
    private fun reset() { stopVpn(); SubscriptionStore(context).clear(); SettingsStore(context).save(ClientSettings(hwid = hwid)) }
    @Before fun before() { System.setProperty("http.keepAlive", "false"); reset() }
    @After fun after() { runCatching { device.pressHome() }; reset() }

    private fun tab(name: String) { wait(By.text(name)).click(); Thread.sleep(900) }

    /** Back closes the keyboard first (when shown), then the sheet: press until the sheet title is gone, never past it. */
    private fun closeSheet(title: String) {
        repeat(3) { if (device.findObject(By.text(title)) != null) { device.pressBack(); Thread.sleep(700) } }
        assertTrue("sheet closed", device.findObject(By.text(title)) == null)
    }

    /** The home-screen widget's RemoteViews inflate and lay out in a night / day configuration at 4×1 cells. */
    private fun widgetInflates(night: Boolean) {
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        }
        val themed = context.createConfigurationContext(config)
        var size = 0
        instrumentation.runOnMainSync {
            val d = themed.resources.displayMetrics.density
            val view = MothWidget.views(themed).apply(themed, android.widget.FrameLayout(themed))
            val w = (360 * d).toInt(); val h = (72 * d).toInt()
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h); size = view.width * view.height
        }
        assertTrue("widget laid out (night=$night)", size > 0)
    }

    private fun shell(cmd: String) { instrumentation.uiAutomation.executeShellCommand(cmd).use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes() } }

    /**
     * A short phone (1080×1600 px at the emulator density, about 610 dp tall; 1080×1900 with the 1.3 font): on Home (off and on) the moth,
     * the subscription card (with its "valid until" line) and the server bar must not overlap, and the moth must not move between off and on.
     * Then the server sheet: it opens at a usable height and one swipe down closes it.
     */
    private fun shortScreen(tag: String, fontScale: Float, size: String = "1080x1600") {
        try {
            shell("wm size $size"); shell("settings put system font_scale $fontScale"); Thread.sleep(1500)
            prepare("dark", false)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            wait(By.text("Settings")); Thread.sleep(1200)
            fun check(state: String): android.graphics.Rect {
                val moth = wait(By.desc(if (state == "off") "Connect VPN" else "Disconnect VPN")).visibleBounds
                val card = wait(By.descContains(": Show all subscriptions")).visibleBounds
                val bar = wait(By.descStartsWith("Choose server")).visibleBounds
                log("short $tag $state: moth=$moth card=$card bar=$bar")
                assertTrue("moth overlaps the subscription card ($state)", moth.bottom <= card.top)
                assertTrue("subscription card overlaps the server bar ($state)", card.bottom <= bar.top)
                val dp = context.resources.displayMetrics.density
                assertTrue("server bar cut off by the screen edge ($state): $bar", bar.height() >= (64 * dp).toInt())
                assertTrue("subscription card squeezed ($state)", card.height() >= (64 * context.resources.displayMetrics.density).toInt())
                val until = wait(By.textContains("Expires on")).visibleBounds
                assertTrue("valid-until line covered ($state)", until.bottom <= bar.top && until.height() > 0)
                return moth
            }
            val offBounds = check("off")
            wait(By.desc("Connect VPN").enabled(true)).click()
            wait(By.text("VPN is on"), 60_000); Thread.sleep(2500)
            assertEquals("moth button moved between off and on", offBounds, check("on"))
            // The session panel (speed / traffic / time in one surface) sits between the moth and the subscription card.
            val speed = wait(By.textContains("Speed")).visibleBounds; val time = wait(By.textContains("Connected")).visibleBounds
            val card = wait(By.descContains(": Show all subscriptions")).visibleBounds
            log("short $tag panel: speed=$speed time=$time")
            assertTrue("session panel overlaps the moth or the card", speed.top >= offBounds.bottom && speed.bottom <= card.top && time.bottom <= card.top)
            assertTrue("session panel columns out of order", speed.right <= time.left)
            wait(By.descStartsWith("Choose server")).click()
            val first = wait(By.descStartsWith("E2E Trojan")); Thread.sleep(1000)
            val h = device.displayHeight
            device.swipe(device.displayWidth / 2, first.visibleBounds.top, device.displayWidth / 2, h - 10, 12)
            eventually(5_000, "sheet closed by one swipe") { device.findObject(By.descStartsWith("E2E Trojan")) == null }
            stopVpn()
        } finally { shell("wm size reset"); shell("settings put system font_scale 1.0"); Thread.sleep(1000) }
    }

    private fun prepare(theme: String, amoled: Boolean, announce: Boolean = false) {
        reset()
        SettingsStore(context).save(ClientSettings(hwid = hwid, theme = if (amoled) "dark" else theme, amoled = amoled))
        eventually(30_000, "subscription downloaded") { SubscriptionRefresh.run(context, null, "https://10.0.2.2:8443/sub", null, SettingsStore(context).load(), "e2e"); true }
        val store = SubscriptionStore(context)
        val profile = store.load()!!.profiles.first { it.name == "E2E VLESS" }
        store.save(store.load()!!.copy(selected = profile.id, wifiSelected = profile.id, mobileSelected = profile.id))
        // Provider links as a Remnawave/Marzban panel sends them (support-url, announce-url, profile-web-page-url), for the subscriptions sheet.
        // Plus a logo (cached as if downloaded) and a traffic limit, for the avatar and the usage ring.
        val book = store.update { b -> b.copy(sources = b.sources.map { it.copy(usedBytes = 37L shl 30, totalBytes = 100L shl 30,
            expires = System.currentTimeMillis() / 1000 + 40L * 86400, links = org.mothvpn.core.ProviderLinks("https://t.me/moth_e2e_support_bot", "https://sub.example.com/e2e", "https://t.me/moth_e2e_news", "https://sub.example.com/logo.png",
                announceText = if (announce) "Maintenance on Sunday 03:00–04:00. Servers in NL will reconnect once." else "")) }) }
        val logo = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val c = android.graphics.Canvas(this); c.drawColor(0xFF2D6CDF.toInt())
            c.drawCircle(48f, 48f, 26f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        }
        ProviderLogos.file(context, book.sources.first().id).outputStream().use { logo.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screens(theme: String, amoled: Boolean = false) {
        prepare(theme, amoled, announce = true)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(800)
        // Ping values for the dots: the group header's check button (the new design has no toolbar icons).
        tab("Servers")
        wait(By.desc("Add subscription"))
        wait(By.desc("Check servers").enabled(true), 30_000).click()
        eventually(5_000, "checks started") { Diagnostics.running.value }
        eventually(90_000, "checks finished") { !Diagnostics.running.value }
        Thread.sleep(800)
        // Top bar: update all subscriptions and check all servers on the page are direct buttons (no ⋮ menu any more).
        wait(By.desc("Check all servers")); wait(By.desc("Update subscription"))
        assertTrue("no overflow menu", device.findObject(By.desc("More server actions")) == null)
        // The other network's page: a check shows a short in-app notice at the top (no inline hint); it goes away by itself or on a tap.
        val current = NetworkTracker.read(context.getSystemService(android.net.ConnectivityManager::class.java)).kind
        val other = if (current == "mobile") "Wi-Fi" else "Mobile data"
        assertTrue("no inline network hint", device.findObject(By.textStartsWith("Checks run only")) == null)
        tap(By.text(other)); Thread.sleep(900)
        tap(By.desc("Check servers").enabled(true))
        wait(By.descStartsWith("To check these servers"), 3_000)
        // The notice is hosted above the tabs: switching to Home keeps it on screen, then it hides by itself after 2 s.
        tap(By.text("Home"))
        assertNotNull("notice kept across the tab switch", device.wait(Until.findObject(By.descStartsWith("To check these servers")), 400))
        eventually(4_000, "notice hides after 2 s") { device.findObject(By.descStartsWith("To check these servers")) == null }
        tap(By.text("Servers")); Thread.sleep(900)
        tap(By.text(other)); Thread.sleep(900)
        tap(By.desc("Check servers").enabled(true))
        tap(By.descStartsWith("To check these servers"))
        eventually(1_500, "notice hides on tap") { device.findObject(By.descStartsWith("To check these servers")) == null }
        assertTrue("no checks started on the other network", !Diagnostics.running.value)
        wait(By.text(if (current == "mobile") "Mobile data" else "Wi-Fi")).click(); Thread.sleep(900)
        tap(By.descStartsWith("Collapse:")); Thread.sleep(700)
        tap(By.descStartsWith("Expand:")); Thread.sleep(500)
        // Menus are bottom sheets with full-width rows: the subscription menu and a server's long-press menu.
        wait(By.desc("Subscription actions")).click(); wait(By.text("Delete")); Thread.sleep(700)
        device.pressBack(); Thread.sleep(700)
        wait(By.descStartsWith("E2E Trojan")).longClick(); wait(By.text("Share server")); Thread.sleep(700)
        device.pressBack(); Thread.sleep(700)
        // Keyword filter: add "Trojan" (hide matching), the Trojan server disappears; then the keyword is removed again.
        wait(By.text("Filter")).click(); wait(By.text("Server filter: Wi-Fi")); Thread.sleep(500)
        wait(By.clazz("android.widget.EditText")).text = "Trojan"
        wait(By.text("Add").enabled(true)).click(); wait(By.desc("Remove Trojan")); Thread.sleep(500)
        closeSheet("Server filter: Wi-Fi")
        eventually(5_000, "filtered") { device.findObject(By.descStartsWith("E2E Trojan")) == null }
        wait(By.text("Filter: 1")).click(); wait(By.desc("Remove Trojan")).click(); Thread.sleep(300); closeSheet("Server filter: Wi-Fi")
        wait(By.descStartsWith("E2E Trojan"))
        tab("Home")
        // The announcement is shown only in the subscriptions sheet (no banner on Home since beta 8).
        assertTrue("no announcement banner on Home", device.findObject(By.textStartsWith("Maintenance on Sunday")) == null)
        val offBounds = wait(By.desc("Connect VPN").enabled(true)).visibleBounds
        wait(By.desc("Connect VPN").enabled(true)).click()
        eventually(10_000, "connecting started") { TunnelStatus.state.value != TunnelState.OFF }
        wait(By.text("VPN is on"), 60_000)
        // The health line ("Checking internet access…", then the result) must not push the moth down.
        eventually(20_000, "health line") { device.findObject(By.textStartsWith("Checking internet")) != null || device.findObject(By.textContains("available")) != null }
        assertEquals("moth button moved when the status changed", offBounds, wait(By.desc("Disconnect VPN")).visibleBounds)
        Thread.sleep(2500)
        assertEquals("moth button moved when the status changed", offBounds, wait(By.desc("Disconnect VPN")).visibleBounds)
        widgetInflates(night = true); widgetInflates(night = false)
        wait(By.descStartsWith("Choose server")).click()
        wait(By.text("Servers")); Thread.sleep(1200)
        device.pressBack(); Thread.sleep(800)
        stopVpn()
        eventually(10_000, "off") { off() }
        // One subscription with provider links: the card opens the subscriptions sheet with the link buttons.
        tab("Home"); wait(By.descContains(": Show all subscriptions")).click(); wait(By.text("Subscriptions")); wait(By.desc("Support")); wait(By.textStartsWith("Maintenance on Sunday")); Thread.sleep(1000)
        device.pressBack(); Thread.sleep(800)
        tab("Settings"); Thread.sleep(600)
        if (theme == "dark") {
            // Ping method choice (Checks) and the developer list of subscription header names (About › For developers).
            fun scrollTo(text: String) {
                repeat(10) {
                    if (device.findObject(By.text(text)) != null) return
                    device.swipe(device.displayWidth / 2, device.displayHeight * 7 / 10, device.displayWidth / 2, device.displayHeight * 3 / 10, 25); Thread.sleep(400)
                }
            }
            wait(By.text("Checks")).click(); Thread.sleep(800)
            scrollTo("Full latency"); wait(By.text("Full latency")); Thread.sleep(500)
            device.pressBack(); Thread.sleep(800)
            scrollTo("About"); wait(By.text("About")).click(); Thread.sleep(800)
            scrollTo("For developers"); wait(By.text("For developers")).click(); Thread.sleep(800)
            wait(By.text("Subscription headers")).click(); wait(By.textStartsWith("HTTP headers:")); Thread.sleep(600)
            device.pressBack(); Thread.sleep(500); device.pressBack(); Thread.sleep(500); device.pressBack(); Thread.sleep(500)
            // Device info: a scrollable dialog whose fields hold the values that are sent (here the defaults) as real text.
            fun openDeviceInfo() {
                wait(By.text("Subscription")).click(); Thread.sleep(800)
                scrollTo("Device info"); wait(By.text("Device info")).click()
                wait(By.clazz("android.widget.EditText").text("Android")); Thread.sleep(600)
            }
            openDeviceInfo()
            device.pressBack(); Thread.sleep(500); device.pressBack(); Thread.sleep(500)
            try {
                // Short screen and a large font: the dialog scrolls so "Reset to defaults" stays reachable.
                shell("wm size 1080x1600"); shell("settings put system font_scale 1.5"); Thread.sleep(1500)
                tab("Settings"); Thread.sleep(800)
                if (device.findObject(By.text("Subscription")) == null) { device.pressBack(); Thread.sleep(800) }
                openDeviceInfo()
                repeat(6) { if (device.findObject(By.text("Reset to defaults")) == null) { device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.6f); Thread.sleep(400) } }
                wait(By.text("Reset to defaults")); Thread.sleep(400)
                device.pressBack(); Thread.sleep(500); device.pressBack(); Thread.sleep(500)
            } finally { shell("wm size reset"); shell("settings put system font_scale 1.0"); Thread.sleep(1000) }
        }
    }

    @Test fun mainScreensWork() = screens("dark")
    /** Connection settings stay editable while connected; the change is saved at once and applied by one reconnect ~5 s later. */
    @Test fun settingsEditableWhileConnected() {
        prepare("dark", false)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(800)
        wait(By.desc("Connect VPN").enabled(true)).click()
        wait(By.text("VPN is on"), 60_000); Thread.sleep(1500)
        tab("Settings"); wait(By.text(tr(R.string.set_cat_routing))).click(); Thread.sleep(800)
        wait(By.text(tr(R.string.direct_ru)))
        // No user-facing mention of reconnecting to apply settings (the behaviour is unchanged).
        assertTrue("no reconnect hint", device.findObject(By.textContains("reconnect")) == null)
        val row = wait(By.text(tr(R.string.direct_ru)))
        row.click()
        val changed = System.currentTimeMillis()
        eventually(5_000, "saved while connected") { SettingsStore(context).load().directRu }
        var reconnectAt = 0L
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && reconnectAt == 0L) { if (TunnelStatus.state.value != TunnelState.RUNNING) reconnectAt = System.currentTimeMillis(); Thread.sleep(30) }
        log("settings live reconnect after ${reconnectAt - changed} ms")
        assertTrue("reconnected once after the change", reconnectAt > 0)
        assertTrue("reconnect waited for the 5 s debounce", reconnectAt - changed >= 4_500)
        eventually(60_000, "VPN back on") { TunnelStatus.state.value == TunnelState.RUNNING }
        stopVpn()
    }
    @Test fun shortScreenLayout() = shortScreen("1x", 1.0f)
    @Test fun shortScreenLayoutLargeFont() = shortScreen("font130", 1.3f, "1080x1900")

    /** Statistics with sample data: the chart, the Day / Week / Month totals and the detail of a tapped bar. */
    @Test fun statsScreen() {
        val today = java.time.LocalDate.now()
        val mb = 1024L * 1024
        val sample = listOf(0, 312, 95, 0, 540, 228, 410, 0, 0, 180, 66, 720, 305, 150)
        var h = org.mothvpn.core.TrafficHistory()
        sample.forEachIndexed { i, v -> if (v > 0) h = h.add(today.minusDays((sample.size - 1 - i).toLong()), v * mb / 9, v * mb) }
        TrafficRecorder.replaceForTest(context, h)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Settings")), 20_000)); Thread.sleep(800)
        device.findObject(By.text("Settings")).click(); Thread.sleep(800)
        device.wait(Until.findObject(By.text("Data")), 10_000).click()
        device.wait(Until.findObject(By.text("Statistics")), 10_000).click()
        val chart = device.wait(Until.findObject(By.desc("Daily traffic")), 10_000)
        assertNotNull("chart not shown", chart)
        // The three totals at the top are labelled Day / Week / Month.
        listOf("Day", "Week", "Month").forEach { assertNotNull("total $it missing", device.findObject(By.text(it))) }
        assertNull("old Today label", device.findObject(By.text("Today")))
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
        device.pressBack(); Thread.sleep(500)
    }

    @Test fun settingsAndDeveloperMenu() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Settings")), 20_000)); Thread.sleep(800)
        device.findObject(By.text("Settings")).click()
        assertNotNull(device.wait(Until.findObject(By.text("Routing")), 10_000)); Thread.sleep(1000)
        var about = device.findObject(By.text("About"))
        repeat(5) { if (about == null) { device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.5f); Thread.sleep(400); about = device.findObject(By.text("About")) } }
        assertNotNull("about entry", about); about!!.click(); Thread.sleep(800)
        var dev = device.findObject(By.text("For developers"))
        repeat(5) { if (dev == null) { device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.5f); Thread.sleep(400); dev = device.findObject(By.text("For developers")) } }
        assertNotNull("developer menu entry", dev); dev!!.click()
        assertNotNull(device.wait(Until.findObject(By.text("Crash log")), 10_000)); Thread.sleep(1000)
        device.pressBack(); Thread.sleep(500)
    }
}
