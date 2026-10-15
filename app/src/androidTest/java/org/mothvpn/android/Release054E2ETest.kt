package org.mothvpn.android

import android.content.Intent
import android.net.Uri
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
import org.mothvpn.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.5.4 on the CI emulator against scripts/e2e/server.py: SOCKS5 and HTTP proxies with username/password and WireGuard
 * carry real traffic (the echo server sees each server's source address), mux + TLS fragmentation keep VLESS working,
 * and a moth:// deep link adds a subscription only after the confirmation dialog.
 */
@RunWith(AndroidJUnit4::class)
class Release054E2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val host = "10.0.2.2"
    private val sub1 = "https://$host:8443/sub"
    private val sub3 = "https://$host:8443/sub3"

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

    /** Network can be briefly unavailable right after the previous test's VPN stopped; retry the download. */
    private fun add(url: String) = eventually(30_000, "subscription downloaded") { SubscriptionRefresh.run(context, null, url, null, SettingsStore(context).load(), "e2e"); true }
    private fun select(id: String) {
        val store = SubscriptionStore(context)
        store.save(store.load()!!.copy(selected = id, wifiSelected = id, mobileSelected = id))
    }
    private fun connect() {
        launch(); tab("Home")
        wait(By.desc("Connect VPN").enabled(true)).click()
        wait(By.text("VPN is on"), 60_000, "connect")
    }
    private fun disconnect() {
        tab("Home")
        wait(By.desc("Disconnect VPN").enabled(true)).click()
        wait(By.desc("Connect VPN"), 30_000, "disconnect")
        VpnChecks.awaitVpnGone(context.getSystemService(android.net.ConnectivityManager::class.java), ::log)
        Thread.sleep(1500)
    }
    private fun trafficThrough(name: String, source: String) {
        val profile = SubscriptionStore(context).load()!!.profiles.first { it.name == name }
        select(profile.id)
        connect()
        eventually(30_000, "$name traffic via $source") { fetch("/ip") == source }
        log("traffic ok: ${profile.protocol}")
        disconnect()
    }

    @Test fun socksHttpAndWireguardCarryTraffic() {
        add(sub3)
        val profiles = SubscriptionStore(context).load()!!.profiles
        assertEquals(3, profiles.size)
        assertTrue(ProxyProtocols.unencrypted(profiles.first { it.name == "E2E SOCKS5" }))
        trafficThrough("E2E SOCKS5", "127.0.0.14")
        trafficThrough("E2E HTTP", "127.0.0.15")
        trafficThrough("E2E WireGuard", "127.0.0.16")
    }

    @Test fun anytlsFromSingBoxSubscriptionWithNativeFragmentation() {
        add("https://$host:8443/sub4")
        assertEquals(listOf("anytls"), SubscriptionStore(context).load()!!.profiles.map { it.protocol })
        trafficThrough("E2E AnyTLS", "127.0.0.18")
        SettingsStore(context).save(SettingsStore(context).load().copy(fragment = "tlshello_small"))
        trafficThrough("E2E AnyTLS", "127.0.0.18")
    }

    @Test fun muxAndFragmentKeepVlessWorking() {
        SettingsStore(context).save(ClientSettings(hwid = hwid, mux = "smux", fragment = "tlshello", noise = true))
        add(sub1)
        trafficThrough("E2E VLESS", "127.0.0.11")
    }

    @Test fun deepLinkAddsSubscriptionAfterConfirmation() {
        val link = "moth://import-sub?url=" + Uri.encode(sub3) + "&name=" + Uri.encode("Deep E2E")
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        wait(By.text("Add subscription?"))
        wait(By.textContains(host))
        assertNull("nothing imported before confirmation", SubscriptionStore(context).loadBook())
        wait(By.text("Add")).click()
        eventually(30_000, "subscription added") { SubscriptionStore(context).loadBook()?.sources?.singleOrNull()?.let { it.name == "Deep E2E" && it.profiles.size == 3 } == true }
        log("deep link subscription added")
        // Server links open the same dialog and go to "My servers".
        device.pressHome()
        val vless = "vless://11111111-2222-4333-8444-555555555555@$host:20001?type=tcp&security=none&encryption=none#Deep%20VLESS"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vless)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        wait(By.text("Add servers?"))
        wait(By.text("Add")).click()
        eventually(30_000, "server link added") { SubscriptionStore(context).loadBook()?.source(SubscriptionSource.LOCAL_ID)?.profiles?.any { it.name == "Deep VLESS" } == true }
        log("deep link server added")
    }
}
