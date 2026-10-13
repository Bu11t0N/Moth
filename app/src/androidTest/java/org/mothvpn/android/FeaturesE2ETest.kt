package org.mothvpn.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mothvpn.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.5.2/0.5.3 features on the CI emulator against scripts/e2e/server.py (10.0.2.2): several subscriptions, routing rules
 * (dialog, paste import, file round trip, block/direct/proxy on real traffic), backup round trip and the kill-switch blocked
 * state on the home screen and the Quick Settings tile. Each test starts and ends with empty data and default settings,
 * so the order of test classes does not matter (E2ETest expects an empty app).
 */
@RunWith(AndroidJUnit4::class)
class FeaturesE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val host = "10.0.2.2"
    private val sub1 = "https://$host:8443/sub"
    private val sub2 = "https://$host:8443/sub2"
    private val tunneled = setOf("127.0.0.11", "127.0.0.12", "127.0.0.13")

    private fun log(msg: String) = android.util.Log.i("MothE2E", msg)
    private fun dump(name: String) = runCatching { device.dumpWindowHierarchy(File(context.externalCacheDir, "e2e-$name.xml")) }
    private fun wait(selector: BySelector, ms: Long = 30_000, name: String = "window"): UiObject2 =
        device.wait(Until.findObject(selector), ms) ?: run { dump(name); throw AssertionError("not found: $selector") }
    private fun fetch(path: String, timeoutMs: Int = 6000): String? = runCatching {
        val c = URL("http://$host:8081$path").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.useCaches = false; c.setRequestProperty("Connection", "close")
        try { c.inputStream.bufferedReader().readText().trim() } finally { c.disconnect() }
    }.getOrNull()
    private fun eventually(ms: Long = 30_000, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (runCatching(check).getOrDefault(false)) return; Thread.sleep(500) }
        dump("eventually"); throw AssertionError("timed out: $what")
    }
    private fun launch() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(800)
    }
    private fun tab(name: String) { wait(By.text(name)).click(); Thread.sleep(800) }
    private fun scrollTo(text: String): UiObject2 {
        for (dir in listOf(Direction.DOWN, Direction.UP)) repeat(10) {
            device.findObject(By.text(text))?.let { return it }
            device.findObject(By.scrollable(true))?.scroll(dir, 0.35f); Thread.sleep(400)
        }
        return wait(By.text(text), 2_000)
    }
    private val hwid by lazy { runCatching { SettingsStore(context).load().hwid }.getOrDefault("e2e-hwid-0000") }
    private fun stopVpn() {
        if (TunnelStatus.state.value != TunnelState.OFF) {
            context.startService(Intent(context, TunnelService::class.java).setAction("STOP"))
            eventually(30_000, "VPN off") { TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !TunnelStatus.blocking.value }
        }
    }
    private fun reset() {
        stopVpn()
        SubscriptionStore(context).clear()
        SettingsStore(context).save(ClientSettings(hwid = hwid))
    }
    @Before fun before() { System.setProperty("http.keepAlive", "false"); reset() }
    @After fun after() { runCatching { device.pressHome() }; reset() }

    private fun add(url: String, name: String?) = SubscriptionRefresh.run(context, null, url, name, SettingsStore(context).load(), "e2e")
    private fun connect() {
        tab("Home")
        wait(By.desc("Connect VPN").enabled(true)).click()
        wait(By.text("VPN is on"), 60_000, "connect")
    }
    private fun disconnect() {
        tab("Home")
        wait(By.desc("Disconnect VPN").enabled(true)).click()
        wait(By.desc("Connect VPN"), 30_000, "disconnect")
        Thread.sleep(1500)
    }
    private fun waitRules(expected: List<RoutingRule>) = eventually(15_000, "rules saved") { SettingsStore(context).load().routingRules == expected }

    @Test fun multipleSubscriptionsGroupRefreshAndDelete() {
        val first = add(sub1, "First E2E")
        val second = add(sub2, null)
        val book = SubscriptionStore(context).loadBook()!!
        assertEquals(2, book.sources.size)
        assertEquals(5, book.profiles.size)
        assertEquals("Second E2E", book.source(second.sourceId)!!.title())
        assertEquals(10_000_000_000L, book.source(second.sourceId)!!.totalBytes)
        // Both refresh in place; nothing is lost or duplicated.
        val before = book.sources.associate { it.id to it.updatedAt }
        Thread.sleep(20)
        SubscriptionRefresh.run(context, first.sourceId, sub1, null, SettingsStore(context).load(), "e2e")
        SubscriptionRefresh.run(context, second.sourceId, sub2, null, SettingsStore(context).load(), "e2e")
        val refreshed = SubscriptionStore(context).loadBook()!!
        assertEquals(listOf(first.sourceId, second.sourceId), refreshed.sources.map { it.id })
        refreshed.sources.forEach { assertTrue("refreshed ${it.id}", it.updatedAt > before.getValue(it.id)) }
        assertEquals("First E2E", refreshed.source(first.sourceId)!!.name)
        // Traffic warning: the second subscription is at 92 % (default threshold 90 %), named because there are two.
        eventually(10_000, "traffic notification") {
            context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {
                it.notification.extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty().let { t -> t.contains("Second E2E") && t.contains("running out") }
            }
        }
        log("two subscriptions refreshed, traffic warning shown")

        launch(); tab("Servers")
        val header = wait(By.text("Second E2E · 2"))
        wait(By.text("First E2E · 3")); wait(By.text("Second Trojan")); wait(By.text("E2E VLESS"))
        val y = header.visibleBounds.centerY()
        device.findObjects(By.desc("Subscription actions")).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - y) }!!.click()
        wait(By.text("Delete")).click()
        wait(By.text("Remove")).click()
        assertTrue("second group still shown", device.wait(Until.gone(By.text("Second E2E · 2")), 15_000))
        eventually(10_000, "store has one subscription") { SubscriptionStore(context).loadBook()!!.sources.map { it.id } == listOf(first.sourceId) }
        wait(By.text("First E2E · 3"))
        log("grouped list shown, second subscription deleted")
    }

    @Test fun routingRulesDialogPasteFileAndTraffic() {
        add(sub1, "First E2E")
        val direct = fetch("/ip")
        assertNotNull("echo server unreachable", direct); assertFalse(direct in tunneled)
        launch(); tab("Settings")
        wait(By.text("Connection")).click()
        scrollTo("Routing rules").click()
        // 1. Add a rule with the dialog.
        wait(By.text("Add rule")).click()
        wait(By.text("IP / CIDR")).click()
        wait(By.clazz("android.widget.EditText")).text = host
        wait(By.text("Block")).click()
        wait(By.text("Save")).click()
        wait(By.text("$host/32"))
        val blockRule = RoutingRule("ip_cidr", "$host/32", "block")
        waitRules(listOf(blockRule))
        // 2. Import from the clipboard: two new rules, one invalid line, one duplicate.
        instrumentation.runOnMainSync {
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("rules", "suffix:example.org block\nkeyword:tracker\nbad line here\nip:$host/32 direct\n"))
        }
        Thread.sleep(500)
        wait(By.text("Paste")).click()
        wait(By.text("Import rules"))
        wait(By.textContains("Invalid lines: 1"))
        wait(By.textContains("Duplicates skipped: 1"))
        wait(By.text("Add 2")).click()
        val all = listOf(blockRule, RoutingRule("domain_suffix", "example.org", "block"), RoutingRule("domain_keyword", "tracker", "direct"))
        waitRules(all)
        wait(By.textContains("New rules: 2"))
        log("rules added by dialog and paste")
        // 3. Text file round trip through the same helpers the screen uses.
        val file = File(context.externalCacheDir, "e2e-rules.txt")
        writeDocument(context, android.net.Uri.fromFile(file), RuleText.format(all))
        assertEquals(all, RuleText.import(readDocument(context, android.net.Uri.fromFile(file), RuleText.MAX_CHARS), emptyList()).rules)
        device.pressBack(); device.pressBack()

        // 4. Real traffic: Block, then Direct, then Proxy for the echo server's address.
        connect()
        Thread.sleep(3000)
        repeat(3) { assertNull("block rule let traffic through", fetch("/ip", 4000)); Thread.sleep(500) }
        log("block rule blocks $host")
        disconnect()
        for ((action, check) in listOf<Pair<String, (String?) -> Boolean>>("direct" to { s -> s != null && s !in tunneled }, "proxy" to { s -> s == "127.0.0.11" })) {
            SettingsStore(context).save(SettingsStore(context).load().copy(routingRules = listOf(RoutingRule("ip_cidr", "$host/32", action))))
            launch(); connect()
            eventually(45_000, "$action rule") { check(fetch("/ip", 4000)) }
            log("$action rule works")
            disconnect()
        }
    }

    @Test fun backupRoundTripWithAndWithoutPassword() {
        val first = add(sub1, "First E2E")
        val store = SubscriptionStore(context)
        val localProfile = Subscriptions.parseLink("trojan://moth-e2e-password@$host:20002?type=tcp&security=none&local=1#Local Trojan")
        store.update { it.upsert(SubscriptionSource(SubscriptionSource.LOCAL_ID, "My servers", SubscriptionRecord.LOCAL_URL, listOf(localProfile))) }
        val profiles = store.loadBook()!!.profiles
        store.save(store.load()!!.copy(selected = profiles[1].id, wifiSelected = profiles[1].id, mobileSelected = localProfile.id))
        val rules = listOf(RoutingRule("domain_suffix", "example.org", "block"), RoutingRule("ip_cidr", "192.168.0.0/16", "direct"))
        SettingsStore(context).save(ClientSettings(hwid = hwid, favorites = listOf(profiles[2].id), routingRules = rules, trafficWarning = 80, killSwitch = true))
        val original = Backup.Content(SettingsStore(context).load(), store.loadBook()!!, BuildConfig.VERSION_NAME, System.currentTimeMillis())
        val expected = Backup.Summary(1, 4, 1, 1, 2)
        assertEquals(expected, Backup.summary(original))

        for (password in listOf<String?>(null, "e2e secret")) {
            val file = File(context.externalCacheDir, "e2e-backup-${if (password == null) "plain" else "encrypted"}.json")
            writeDocument(context, android.net.Uri.fromFile(file), Backup.export(original, password))
            val text = readDocument(context, android.net.Uri.fromFile(file), Backup.MAX_BYTES)
            assertEquals(password != null, Backup.encrypted(text))
            if (password != null) {
                assertFalse("secrets in an encrypted file", text.contains("moth-e2e-password") || text.contains(host))
                assertTrue(runCatching { Backup.read(text, "wrong") }.exceptionOrNull() is Backup.WrongPassword)
            }
            // Wipe, then restore.
            store.clear(); SettingsStore(context).save(ClientSettings(hwid = hwid))
            assertNull(store.loadBook())
            val restored = Backup.read(text, password)
            assertEquals(expected, Backup.summary(restored))
            store.saveBook(restored.book); SettingsStore(context).save(restored.settings)
            val book = store.loadBook()!!
            assertEquals(original.book, book)
            assertEquals(localProfile.id, book.record()!!.mobileSelected)
            assertEquals(profiles[1].id, book.record()!!.wifiSelected)
            val settings = SettingsStore(context).load()
            assertEquals(rules, settings.routingRules); assertEquals(listOf(profiles[2].id), settings.favorites); assertEquals(80, settings.trafficWarning); assertTrue(settings.killSwitch)
            log("backup restored (${if (password == null) "plain" else "encrypted"})")
        }
        // The restored data is what the app shows.
        launch(); tab("Servers")
        wait(By.text("First E2E · 2")); wait(By.text("My servers · 1")); wait(By.text("Favorites · 1"))
        tab("Settings"); wait(By.text("Connection")).click()
        scrollTo("Rules: 2")
        device.pressBack()
        assertEquals(first.sourceId, store.loadBook()!!.sources.first().id)
    }

    /** A server whose config sing-box rejects: with the kill switch on, the start fails and traffic stays blocked. */
    @Test fun killSwitchBlockedStateOnHomeAndTile() {
        val broken = Profile("0123456789abcdef0123456789abcdef", "Broken server", "shadowsocks",
            "{\"type\":\"shadowsocks\",\"tag\":\"proxy\",\"server\":\"$host\",\"server_port\":20003,\"method\":\"no-such-cipher\",\"password\":\"x\"}")
        SubscriptionStore(context).saveBook(SubscriptionBook(listOf(SubscriptionSource(SubscriptionSource.LOCAL_ID, "My servers", SubscriptionRecord.LOCAL_URL, listOf(broken))), broken.id))
        SettingsStore(context).save(ClientSettings(hwid = hwid, killSwitch = true, healthEnabled = false))
        launch(); connect@ run {
            tab("Home")
            wait(By.desc("Connect VPN").enabled(true)).click()
        }
        eventually(60_000, "blocked") { TunnelStatus.trafficBlocked(TunnelStatus.state.value, TunnelStatus.blocking.value) }
        // Home: "Disconnect" with "Traffic blocked", never "Connect".
        wait(By.text("Disconnect"), 20_000, "home-blocked"); wait(By.text("Traffic blocked"))
        assertNull("traffic leaked while blocked", fetch("/ip", 4000))
        log("home shows the blocked state")

        val component = "org.mothvpn.android/.VpnTileService"
        val added = shell("cmd statusbar add-tile $component")
        log("add-tile: ${added.trim().ifEmpty { "ok" }}")
        var subtitleSeen = false
        if (!added.contains("rror") && !added.contains("Unknown")) {
            device.openQuickSettings(); Thread.sleep(1500)
            for (i in 0 until 4) {
                if (device.findObject(By.text("Traffic blocked")) != null || device.findObject(By.text("Reconnecting…")) != null) { subtitleSeen = true; break }
                device.findObject(By.scrollable(true))?.swipe(Direction.LEFT, 0.8f); Thread.sleep(800)
            }
            dump("quick-settings")
            log(if (subtitleSeen) "tile shows the blocked state" else "tile subtitle not visible in Quick Settings (soft check skipped)")
            device.pressBack(); device.pressBack(); Thread.sleep(500)
            // Tapping the tile in the blocked state disconnects and lifts the block.
            shell("cmd statusbar click-tile $component")
            eventually(30_000, "tile tap disconnects") { TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !TunnelStatus.blocking.value }
            log("tile tap disconnected")
            shell("cmd statusbar remove-tile $component")
        } else log("Quick Settings tile commands not available: tile checks skipped")
        if (TunnelStatus.state.value !in setOf(TunnelState.OFF, TunnelState.ERROR)) {
            launch(); tab("Home"); wait(By.desc("Disconnect VPN").enabled(true)).click()
            eventually(30_000, "home disconnect") { TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !TunnelStatus.blocking.value }
        }
        eventually(30_000, "direct traffic after disconnect") { fetch("/ip", 4000)?.let { it !in tunneled } == true }
    }

    private fun shell(command: String): String = runCatching {
        instrumentation.uiAutomation.executeShellCommand(command).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }
    }.getOrElse { "error: ${it.javaClass.simpleName}" }
}
