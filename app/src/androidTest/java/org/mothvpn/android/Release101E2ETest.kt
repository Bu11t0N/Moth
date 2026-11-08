package org.mothvpn.android

import android.content.Intent
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mothvpn.core.*

/** Synthetic CI fixtures only: cold offline startup/recovery, and strictly tunneled subscription downloads. */
@RunWith(AndroidJUnit4::class)
class Release101E2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val host = "10.0.2.2"
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText() }
    }
    private fun eventually(what: String, ms: Long = 60_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (runCatching(check).getOrDefault(false)) return; Thread.sleep(500) }
        throw AssertionError("timed out: $what; state=${TunnelStatus.state.value}")
    }
    private fun stop() {
        if (TunnelStatus.state.value != TunnelState.OFF) {
            context.startService(Intent(context, TunnelService::class.java).setAction("STOP"))
            eventually("VPN off") { TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) }
        }
    }
    @Before fun before() {
        shell("cmd connectivity airplane-mode disable"); stop()
        Onboarding.markDone(context); SubscriptionStore(context).clear()
        SettingsStore(context).save(ClientSettings(healthEnabled = false))
        eventually("physical network") { NetworkTracker.read(cm).available }
    }
    @After fun after() {
        shell("cmd connectivity airplane-mode disable"); stop()
        SubscriptionStore(context).clear(); SettingsStore(context).save(ClientSettings())
        device.pressHome()
    }
    private fun fixture(): String {
        eventually("fixture downloaded") { SubscriptionRefresh.run(context, null, "https://$host:8443/sub", null, ClientSettings(), "e2e"); true }
        val store = SubscriptionStore(context); val s = store.load()!!
        val id = s.profiles.first { it.name == "E2E VLESS" }.id
        store.save(s.copy(selected = id, wifiSelected = id, mobileSelected = id))
        return id
    }
    private fun launch() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Settings")), 20_000))
    }
    @Test fun coldOfflineStartAndAutomaticRecovery() {
        fixture()
        SettingsStore(context).save(ClientSettings(healthEnabled = false, autoSelectWifi = true, autoSelectMobile = true))
        shell("cmd connectivity airplane-mode enable")
        try {
            eventually("offline") { !NetworkTracker.read(cm).available }
            launch()
            val connect = device.wait(Until.findObject(By.desc("Connect VPN").enabled(true)), 20_000)
            assertNotNull("offline connect button", connect); connect!!.click()
            // Accept VPN permission on a fresh emulator, just as the normal UI does.
            device.wait(Until.findObject(By.res("android:id/button1")), 2000)?.click()
            eventually("VPN established while offline", 30_000) { TunnelStatus.state.value == TunnelState.RUNNING }
            assertTrue("offline startup must establish a real VPN", cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true })
            eventually("No network notification") {
                val n = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull { it.id == 1 }?.notification
                n?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == tr(R.string.offline_title)
            }
            assertFalse("offline start must not measure servers", TunnelStatus.autoSelecting.value)
        } finally { shell("cmd connectivity airplane-mode disable") }
        eventually("network recovery") { NetworkTracker.read(cm).available && TunnelStatus.offline.value == OfflineStatus.Kind.ONLINE }
        eventually("traffic recovered through the VPN") { SubscriptionDownload.fetchDetails("https://$host:8443/ip").body.trim() in setOf("127.0.0.11", "127.0.0.12", "127.0.0.13") }
        assertTrue(TunnelStatus.activeProfile.value.isNotBlank())
    }
    @Test fun startupDnsFailureKeepsVpnAndRetriesWithoutANetworkSwitch() {
        fixture()
        val store = SubscriptionStore(context)
        val working = store.load()!!.profiles.first { it.name == "E2E VLESS" }
        val wg = Subscriptions.parse(SubscriptionDownload.fetch("https://$host:8443/sub3")).profiles.first { it.protocol == "wireguard" }
        val unresolved = wg.copy(outbound = org.json.JSONObject(wg.outbound).apply { getJSONArray("peers").getJSONObject(0).put("address", "offline-start.invalid") }.toString())
        val source = SubscriptionSource("dns-fixture", "Fixture", SubscriptionRecord.LOCAL_URL, listOf(unresolved))
        store.saveBook(SubscriptionBook(listOf(source), unresolved.id))
        context.startForegroundService(Intent(context, TunnelService::class.java).setAction("START").putExtra("profile", unresolved.id))
        eventually("startup DNS failure keeps a VPN interface", 90_000) {
            TunnelStatus.state.value == TunnelState.RUNNING && TunnelStatus.offline.value != OfflineStatus.Kind.ONLINE &&
                cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
        }
        // Fix the synthetic endpoint without a network callback; the deferred-start timer must recover on its own.
        store.saveBook(SubscriptionBook(listOf(source.copy(profiles = listOf(working.copy(id = unresolved.id)))), unresolved.id))
        eventually("DNS-dependent startup retries", 90_000) { TunnelDownloads.ready() && TunnelStatus.offline.value == OfflineStatus.Kind.ONLINE }
        eventually("traffic works after deferred startup") { SubscriptionDownload.fetch("https://$host:8443/ip").trim() == "127.0.0.11" }
    }
    @Test fun subscriptionRefreshIsForcedThroughVpnDespiteDirectRouting() {
        val id = fixture()
        // A user bypass rule sends the fixture host directly; subscription refresh must still use the server.
        val sourceId = SubscriptionStore(context).loadBook()!!.sources.first().id
        val s = ClientSettings(healthEnabled = false, subVpnOnlySubs = listOf(sourceId), routingRules = listOf(RoutingRule(CustomRouting.CIDR, "$host/32", CustomRouting.DIRECT)))
        SettingsStore(context).save(s)
        context.startForegroundService(Intent(context, TunnelService::class.java).setAction("START").putExtra("profile", id))
        eventually("running VPN") { TunnelDownloads.ready() }
        assertEquals("127.0.0.11", SubscriptionDownload.fetchDetails("https://$host:8443/ip", s, sourceId).body.trim())
        assertNotEquals("new/unselected subscriptions keep normal routing", "127.0.0.11", SubscriptionDownload.fetchDetails("https://$host:8443/ip", s, "other-sub").body.trim())
        val via = TunnelDownloads.requireSession()
        fun credentials(hostname: String, port: Int, protocol: String = "SOCKS5", type: java.net.Authenticator.RequestorType = java.net.Authenticator.RequestorType.SERVER) = java.net.Authenticator.requestPasswordAuthentication(
            hostname, java.net.InetAddress.getByName("127.0.0.1"), port, protocol, "fixture", null, null, type)
        assertNotNull("Android SOCKS5 callback is scoped to the local proxy", credentials("localhost", via.port))
        assertNull("no proxy secret sent to the origin", credentials("127.0.0.1", via.port, "https"))
        assertNull("no proxy secret sent to HTTP authentication", credentials("127.0.0.1", via.port, "http", java.net.Authenticator.RequestorType.PROXY))
        assertNull("no proxy secret sent to another port", credentials("127.0.0.1", via.port + 1))
        assertNull("no proxy secret sent to another host", credentials("example.com", via.port))
        // A wrong per-session secret must be refused, never allowed to fall back directly.
        val wrong = via.copy(password = "wrong-synthetic-password")
        TunnelDownloads.session = wrong
        try {
            assertTrue("bad proxy auth must fail closed", runCatching { SubscriptionDownload.fetchDetails("https://$host:8443/ip", s, sourceId) }.isFailure)
        } finally { TunnelDownloads.session = via }
        SubscriptionRefresh.run(context, SubscriptionStore(context).loadBook()!!.sources.first().id, "https://$host:8443/sub", null, s, "e2e")
        stop()
        assertThrows(TunnelDownloads.VpnRequired::class.java) { SubscriptionDownload.fetchDetails("https://$host:8443/ip", s, sourceId) }
        assertTrue("unselected source can still refresh without VPN", SubscriptionDownload.fetchDetails("https://$host:8443/ip", s, "other-sub").body.isNotBlank())
        assertThrows(TunnelDownloads.VpnRequired::class.java) { TunnelDownloads.open(java.net.URL("https://$host:8443/ip"), via) }
    }
}
