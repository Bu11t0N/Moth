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
import org.mothvpn.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.7.1 on the CI emulator: failover while connected keeps the tunnel up. Two Shadowsocks servers run in their own sing-box
 * processes on the runner (scripts/e2e/server.py, /fo/stop=a|b), auto-select is on and the kill switch is off. The test stops
 * the server in use: until the switch the VPN network must stay up with the dead server (no "VPN off" gap and no direct
 * traffic), then traffic must arrive through the other server.
 */
@RunWith(AndroidJUnit4::class)
class FailoverE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val host = "10.0.2.2"
    private val tunneled = setOf("127.0.0.11", "127.0.0.12", "127.0.0.13", "127.0.0.19", "127.0.0.20", "127.0.0.21", "127.0.0.22", "127.0.0.23")
    private val servers = mapOf("a" to Triple("fa0000000000000000000000000000aa", 20021, "127.0.0.22"), "b" to Triple("fb0000000000000000000000000000bb", 20022, "127.0.0.23"))

    private fun log(msg: String) = android.util.Log.i("MothE2E", msg)
    private fun dump(name: String) = runCatching { device.dumpWindowHierarchy(File(context.externalCacheDir, "e2e-$name.xml")) }
    private fun wait(selector: BySelector, ms: Long = 30_000, name: String = "window"): UiObject2 =
        device.wait(Until.findObject(selector), ms) ?: run { dump(name); throw AssertionError("not found: $selector") }
    private fun fetch(path: String, timeoutMs: Int = 4000): String? = runCatching {
        val c = URL("http://$host:8081$path").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.useCaches = false; c.setRequestProperty("Connection", "close")
        try { c.inputStream.bufferedReader().readText().trim() } finally { c.disconnect() }
    }.getOrNull()
    private fun eventually(ms: Long = 30_000, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (runCatching(check).getOrDefault(false)) return; Thread.sleep(500) }
        dump("failover"); throw AssertionError("timed out: $what")
    }
    private val hwid by lazy { runCatching { SettingsStore(context).load().hwid }.getOrDefault("e2e-hwid-0000") }
    private val cm by lazy { context.getSystemService(android.net.ConnectivityManager::class.java) }
    private fun off() = TunnelStatus.state.value in setOf(TunnelState.OFF, TunnelState.ERROR) && !TunnelStatus.blocking.value
    private fun stopVpn() {
        if (TunnelStatus.state.value != TunnelState.OFF || TunnelStatus.blocking.value) {
            context.startService(Intent(context, TunnelService::class.java).setAction("STOP"))
            eventually(30_000, "VPN off") { off() }
        }
    }
    private fun reset() {
        stopVpn()
        SubscriptionStore(context).clear()
        SettingsStore(context).save(ClientSettings(hwid = hwid))
    }
    /** Both failover servers running again (the request goes directly or through a live server). */
    private fun startServers() = eventually(60_000, "failover servers started") { servers.keys.all { fetch("/fo/start=$it") == "ok" } }
    @Before fun before() { System.setProperty("http.keepAlive", "false"); reset() }
    @After fun after() { runCatching { device.pressHome() }; reset(); startServers() }

    @Test fun deadServerKeepsTheTunnelUpUntilALiveOneIsFound() {
        eventually(60_000, "direct network") { off() && fetch("/ip")?.let { it !in tunneled } == true }
        startServers()
        val profiles = servers.map { (name, s) ->
            Profile(s.first, "Failover ${name.uppercase()}", "shadowsocks",
                "{\"type\":\"shadowsocks\",\"tag\":\"proxy\",\"server\":\"$host\",\"server_port\":${s.second},\"method\":\"aes-128-gcm\",\"password\":\"moth-e2e-password\"}")
        }
        SubscriptionStore(context).saveBook(SubscriptionBook(listOf(SubscriptionSource(SubscriptionSource.LOCAL_ID, "My servers", SubscriptionRecord.LOCAL_URL, profiles)), profiles[0].id))
        // Health checks through the tunnel go to the test host (HTTPS with the test CA); TCP probes; the shortest check interval.
        SettingsStore(context).save(ClientSettings(hwid = hwid, autoSelect = true, killSwitch = false, probeMode = ProbePolicy.TCP,
            probeUrl = "https://$host:8443/sub", healthIntervalSeconds = 30))

        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(800)
        wait(By.desc("Connect VPN").enabled(true)).click()
        wait(By.text("VPN is on"), 60_000, "connect")
        val active = TunnelStatus.activeProfile.value
        val dead = servers.entries.first { it.value.first == active }
        val live = servers.entries.first { it.key != dead.key }
        eventually(30_000, "traffic through the selected server") { fetch("/ip") == dead.value.third }
        log("connected via failover server ${dead.key}; stopping it")
        assertEquals("ok", fetch("/fo/stop=${dead.key}"))
        Thread.sleep(2000)

        // Failover needs 3 failed health checks (30 s apart), then a probe of the other server, then the switch.
        val end = System.currentTimeMillis() + 240_000
        var switched = false; var samples = 0
        while (System.currentTimeMillis() < end) {
            val onDead = TunnelStatus.activeProfile.value == dead.value.first
            if (onDead) {
                samples++
                // Re-read after the check: the switch itself (STARTING, then a new tunnel) may begin between the two reads.
                if (!VpnChecks.vpnUp(cm) && TunnelStatus.activeProfile.value == dead.value.first) { dump("failover"); fail("the VPN network went down while staying on the dead server (state=${TunnelStatus.state.value})") }
                val state = TunnelStatus.state.value
                // RUNNING the whole time; STARTING only for the switch itself (never OFF or ERROR).
                assertTrue("state while on the dead server: $state", state == TunnelState.RUNNING || state == TunnelState.STARTING)
            }
            val seen = fetch("/ip", 3000)
            // Direct traffic is a leak while the tunnel stays on the dead server. Only the fast switch itself (kill switch off) has a
            // short gap: the active server is cleared, the old tunnel closes and the new one comes up.
            if (seen != null && seen !in tunneled && onDead && TunnelStatus.activeProfile.value == dead.value.first)
                fail("traffic left outside the tunnel while staying on the dead server ($seen)")
            if (seen != null && seen !in tunneled) log("direct answer during the switch")
            if (seen == live.value.third) { switched = true; break }
            Thread.sleep(500)
        }
        log("samples on the dead server with the tunnel up: $samples; switched=$switched")
        assertTrue("failover did not reach the live server", switched)
        assertTrue("the dead server should have been kept for a while (samples=$samples)", samples > 10)
        assertEquals(live.value.first, TunnelStatus.activeProfile.value)
        assertTrue(VpnChecks.vpnUp(cm))
    }
}
