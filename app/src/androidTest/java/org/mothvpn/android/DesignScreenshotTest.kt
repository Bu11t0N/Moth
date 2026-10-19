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

    /** Back closes the keyboard first (when shown), then the sheet: press until the sheet title is gone, never past it. */
    private fun closeSheet(title: String) {
        repeat(3) { if (device.findObject(By.text(title)) != null) { device.pressBack(); Thread.sleep(700) } }
        assertTrue("sheet closed", device.findObject(By.text(title)) == null)
    }

    /** The home-screen widget as the launcher draws it: its RemoteViews inflated in a night / day configuration and rendered at 4×1 cells. */
    private fun widgetShot(name: String, night: Boolean) {
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        }
        val themed = context.createConfigurationContext(config)
        var bitmap: android.graphics.Bitmap? = null
        instrumentation.runOnMainSync {
            val d = themed.resources.displayMetrics.density
            val view = MothWidget.views(themed).apply(themed, android.widget.FrameLayout(themed))
            val w = (360 * d).toInt(); val h = (72 * d).toInt(); val pad = (12 * d).toInt()
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            bitmap = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                val c = android.graphics.Canvas(b); c.drawColor(if (night) 0xFF3A4048.toInt() else 0xFFB8C4D0.toInt()) // a plain "wallpaper"
                c.translate(pad.toFloat(), pad.toFloat()); view.draw(c)
            }
        }
        File(out, "$name.png").outputStream().use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        log("design screenshot: $name")
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
            shot("home-short-off-$tag")
            wait(By.desc("Connect VPN").enabled(true)).click()
            wait(By.text("VPN is on"), 60_000); Thread.sleep(2500)
            assertEquals("moth button moved between off and on", offBounds, check("on"))
            shot("home-short-on-$tag")
            wait(By.descStartsWith("Choose server")).click()
            val first = wait(By.descStartsWith("E2E Trojan")); Thread.sleep(1000)
            shot("sheet-short-$tag")
            val h = device.displayHeight
            device.swipe(device.displayWidth / 2, first.visibleBounds.top, device.displayWidth / 2, h - 10, 12)
            eventually(5_000, "sheet closed by one swipe") { device.findObject(By.descStartsWith("E2E Trojan")) == null }
            stopVpn()
        } finally { shell("wm size reset"); shell("settings put system font_scale 1.0"); Thread.sleep(1000) }
    }

    private fun prepare(theme: String, amoled: Boolean, announce: Boolean = false) {
        reset()
        context.getSharedPreferences("announce_dismissed", android.content.Context.MODE_PRIVATE).edit().clear().commit()
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
        shot("servers-$theme")
        wait(By.descStartsWith("Collapse:")).click(); Thread.sleep(700)
        shot("servers-collapsed-$theme")
        wait(By.descStartsWith("Expand:")).click(); Thread.sleep(500)
        // Menus are bottom sheets with full-width rows: the subscription menu and a server's long-press menu.
        wait(By.desc("Subscription actions")).click(); wait(By.text("Delete")); Thread.sleep(700)
        shot("group-menu-$theme"); device.pressBack(); Thread.sleep(700)
        wait(By.descStartsWith("E2E Trojan")).longClick(); wait(By.text("Share server")); Thread.sleep(700)
        shot("server-menu-$theme"); device.pressBack(); Thread.sleep(700)
        // Keyword filter: add "Trojan" (hide matching), the Trojan server disappears; then the keyword is removed again.
        wait(By.text("Filter")).click(); wait(By.text("Server filter: Wi-Fi")); Thread.sleep(500)
        wait(By.clazz("android.widget.EditText")).text = "Trojan"
        wait(By.text("Add").enabled(true)).click(); wait(By.desc("Remove Trojan")); Thread.sleep(500)
        shot("filter-sheet-$theme")
        closeSheet("Server filter: Wi-Fi")
        eventually(5_000, "filtered") { device.findObject(By.descStartsWith("E2E Trojan")) == null }
        shot("servers-filtered-$theme")
        wait(By.text("Filter: 1")).click(); wait(By.desc("Remove Trojan")).click(); Thread.sleep(300); closeSheet("Server filter: Wi-Fi")
        wait(By.descStartsWith("E2E Trojan"))
        tab("Home")
        // The provider's announcement: a banner above the subscription card; hidden after dismissal (it returns only for a new text).
        wait(By.desc("Hide announcement")); wait(By.textStartsWith("Maintenance on Sunday")); Thread.sleep(500)
        shot("home-announce-$theme")
        wait(By.desc("Hide announcement")).click()
        eventually(5_000, "announcement dismissed") { device.findObject(By.desc("Hide announcement")) == null }
        assertTrue("dismissal stored", AnnounceDismissals.dismissed(context, SubscriptionStore(context).loadBook()!!.sources.first().id, "Maintenance on Sunday 03:00–04:00. Servers in NL will reconnect once."))
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
        widgetShot("widget-on-$theme", theme != "light")
        wait(By.descStartsWith("Choose server")).click()
        wait(By.text("Servers")); Thread.sleep(1200)
        shot("sheet-$theme")
        device.pressBack(); Thread.sleep(800)
        stopVpn()
        eventually(10_000, "off") { off() }
        widgetShot("widget-off-$theme", theme != "light")
        // One subscription with provider links: the card opens the subscriptions sheet with the link buttons.
        tab("Home"); wait(By.descContains(": Show all subscriptions")).click(); wait(By.text("Subscriptions")); wait(By.desc("Support")); wait(By.textStartsWith("Maintenance on Sunday")); Thread.sleep(1000)
        shot("subscriptions-$theme"); device.pressBack(); Thread.sleep(800)
        tab("Settings"); Thread.sleep(600)
        shot("settings-$theme")
        if (theme == "dark") {
            // Ping method choice (Connection) and the developer list of subscription header names.
            fun scrollTo(text: String) {
                repeat(10) {
                    if (device.findObject(By.text(text)) != null) return
                    device.swipe(device.displayWidth / 2, device.displayHeight * 7 / 10, device.displayWidth / 2, device.displayHeight * 3 / 10, 25); Thread.sleep(400)
                }
            }
            wait(By.text("Connection")).click(); Thread.sleep(800)
            scrollTo("Full latency"); wait(By.text("Full latency")); Thread.sleep(500)
            shot("settings-ping-$theme"); device.pressBack(); Thread.sleep(800)
            scrollTo("For developers"); wait(By.text("For developers")).click(); Thread.sleep(800)
            wait(By.text("Subscription headers")).click(); wait(By.textStartsWith("HTTP headers:")); Thread.sleep(600)
            shot("dev-headers-$theme"); device.pressBack(); Thread.sleep(500); device.pressBack(); Thread.sleep(500)
        }
    }

    @Test fun newDesignDark() = screens("dark")
    @Test fun newDesignLight() = screens("light")
    @Test fun newDesignAmoled() = screens("amoled", amoled = true)
    /** Connection settings stay editable while connected; the change is saved at once and applied by one reconnect ~5 s later. */
    @Test fun settingsEditableWhileConnected() {
        prepare("dark", false)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(800)
        wait(By.desc("Connect VPN").enabled(true)).click()
        wait(By.text("VPN is on"), 60_000); Thread.sleep(1500)
        tab("Settings"); wait(By.text(tr(R.string.set_cat_routing))).click(); Thread.sleep(800)
        wait(By.textStartsWith("VPN is on: changes"))
        val row = wait(By.text(tr(R.string.direct_ru)))
        row.click()
        val changed = System.currentTimeMillis()
        eventually(5_000, "saved while connected") { SettingsStore(context).load().directRu }
        shot("settings-live-dark")
        var reconnectAt = 0L
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && reconnectAt == 0L) { if (TunnelStatus.state.value != TunnelState.RUNNING) reconnectAt = System.currentTimeMillis(); Thread.sleep(30) }
        log("settings live reconnect after ${reconnectAt - changed} ms")
        assertTrue("reconnected once after the change", reconnectAt > 0)
        assertTrue("reconnect waited for the 5 s debounce", reconnectAt - changed >= 4_500)
        eventually(60_000, "VPN back on") { TunnelStatus.state.value == TunnelState.RUNNING }
        stopVpn()
    }
    @Test fun newDesignShortScreen() = shortScreen("1x", 1.0f)
    @Test fun newDesignShortScreenLargeFont() = shortScreen("font130", 1.3f, "1080x1900")
}
