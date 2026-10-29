package org.mothvpn.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end test on the CI emulator against scripts/e2e/server.py on the runner host (10.0.2.2):
 * import an HTTPS subscription, connect, check traffic goes through the server, switch servers while connected,
 * kill switch keeps traffic blocked while the server is down, disconnect.
 * Each test server sends traffic to the echo endpoint from its own source address, so /ip tells which path was used.
 */
@RunWith(AndroidJUnit4::class)
class E2ETest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val host = "10.0.2.2"
    private val sources = mapOf("vless" to "127.0.0.11", "trojan" to "127.0.0.12", "ss" to "127.0.0.13")

    private fun log(msg: String) = android.util.Log.i("MothE2E", msg)
    private fun dump() = runCatching { device.dumpWindowHierarchy(java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir, "e2e-window.xml")) }
    private fun wait(selector: BySelector, ms: Long = 30_000): UiObject2 =
        device.wait(Until.findObject(selector), ms) ?: run { dump(); throw AssertionError("not found: $selector") }
    private fun fetch(path: String, timeoutMs: Int = 6000): String {
        val c = URL("http://$host:8081$path").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.useCaches = false; c.setRequestProperty("Connection", "close")
        try { return c.inputStream.bufferedReader().readText().trim() } finally { c.disconnect() }
    }
    private fun tryFetch(path: String = "/ip", timeoutMs: Int = 6000): String? = runCatching { fetch(path, timeoutMs) }.getOrNull()
    private fun awaitSource(expected: String, ms: Long = 60_000) {
        val end = System.currentTimeMillis() + ms
        var last: String? = null
        while (System.currentTimeMillis() < end) { last = tryFetch(); if (last == expected) return; Thread.sleep(1500) }
        dump(); throw AssertionError("traffic did not arrive via $expected (last=$last)")
    }
    private fun tab(name: String) { wait(By.text(name)).click(); Thread.sleep(800) }

    private fun scrollTo(text: String): UiObject2 {
        for (dir in listOf(androidx.test.uiautomator.Direction.DOWN, androidx.test.uiautomator.Direction.UP)) repeat(14) {
            device.findObject(By.text(text))?.let { return it }
            device.findObject(By.scrollable(true))?.scroll(dir, 0.35f)
            Thread.sleep(500)
        }
        return wait(By.text(text), 2_000)
    }

    /** The switch on the row of [text]. Compose recomposition can invalidate found nodes, so stale lookups are re-found. */
    private fun toggleNear(text: String): UiObject2? {
        repeat(3) { attempt ->
            try {
                val label = scrollTo(text)
                val y = label.visibleBounds.centerY()
                return device.findObjects(By.checkable(true)).firstOrNull { it.visibleBounds.top <= y && it.visibleBounds.bottom >= y }
                    ?: label.parent?.findObject(By.checkable(true))
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                log("toggleNear($text): stale node, re-finding (attempt ${attempt + 1})")
                device.waitForIdle()
            }
        }
        return null
    }

    /** Turns the switch on, re-finding it if the node went stale. */
    private fun ensureChecked(text: String) {
        repeat(3) {
            try {
                val t = toggleNear(text) ?: run { dump(); throw AssertionError("$text toggle not found") }
                if (!t.isChecked) t.click()
                return
            } catch (e: androidx.test.uiautomator.StaleObjectException) { device.waitForIdle() }
        }
    }

    @Test fun importConnectSwitchKillSwitchDisconnect() {
        System.setProperty("http.keepAlive", "false")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val direct = tryFetch()
        log("direct path source: $direct")
        assertNotNull("echo server unreachable from the emulator", direct)
        assertFalse("direct path must not look tunneled", direct in sources.values)

        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // 1. Import the HTTPS subscription (the test CA is trusted in debug builds only).
        wait(By.text("Add subscription")).click()
        wait(By.clazz("android.widget.EditText")).text = "https://$host:8443/sub"
        wait(By.text("Add")).click()
        wait(By.text("E2E VLESS"), 60_000)
        log("subscription imported")

        // 2. Connect and check the traffic goes through the VLESS server.
        val firstNotification = VpnChecks.FirstRunningNotification(context)
        wait(By.desc("Connect VPN").enabled(true)).click()
        wait(By.text("VPN is on"), 60_000)
        // The very first connected notification already has the default speed and total lines (not just the server name; uptime is off by default).
        val lines = firstNotification.first().lines()
        log("first connected notification lines: ${lines.map { it.substringBefore(':') }}")
        assertTrue("first connected notification lacks speed/total: ${lines.map { it.substringBefore(':') }}",
            lines.size == 2 && lines[0].startsWith("Speed:") && lines[1].startsWith("Total:"))
        awaitSource(sources.getValue("vless"))
        log("connected via VLESS")

        // 3. Switch to Trojan while connected.
        tab("Servers")
        wait(By.text("E2E Trojan")).click()
        tab("Home")
        wait(By.text("VPN is on"), 60_000)
        awaitSource(sources.getValue("trojan"))
        log("switched to Trojan while connected")

        // 4. Kill switch on (live, while connected); stop the server: nothing may leave outside the tunnel.
        tab("Settings")
        wait(By.text("Connection")).click()
        ensureChecked("Kill switch")
        Thread.sleep(800)
        val ksEnd = System.currentTimeMillis() + 5_000
        var ksOn = false
        while (!ksOn && System.currentTimeMillis() < ksEnd) {
            ksOn = try { toggleNear("Kill switch")?.isChecked == true } catch (e: androidx.test.uiautomator.StaleObjectException) { false }
            if (!ksOn) device.waitForIdle()
        }
        assertTrue("kill switch should be on", ksOn)
        device.pressBack(); Thread.sleep(500); tab("Home")
        assertEquals("paused", fetch("/pause=25"))
        Thread.sleep(4000)
        repeat(3) {
            val seen = tryFetch(timeoutMs = 4000)
            log("while the server is down: $seen")
            assertNull("traffic leaked outside the tunnel while the server was down", seen)
            Thread.sleep(1000)
        }
        // The tunnel must stay up (the kill switch may show the blocked state instead of "VPN is on").
        val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION") val vpnUp = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
        if (!vpnUp) dump()
        assertTrue("VPN tunnel should stay up while the server is down", vpnUp)
        log("tunnel stays up; button: " + (device.findObject(By.desc("Disconnect VPN"))?.let { "Disconnect" } ?: "Connect (blocked state)"))
        awaitSource(sources.getValue("trojan"), 90_000)
        log("recovered after the server came back")

        // 5. Disconnect: traffic goes directly again.
        wait(By.desc("Disconnect VPN").enabled(true)).click()
        wait(By.desc("Connect VPN"), 30_000)
        VpnChecks.awaitVpnGone(cm, ::log) { dump() }
        VpnChecks.awaitNotificationGone(InstrumentationRegistry.getInstrumentation().targetContext, ::log)
        val end = System.currentTimeMillis() + 30_000
        var after: String? = null
        while (System.currentTimeMillis() < end) { after = tryFetch(); if (after != null && after !in sources.values) break; Thread.sleep(1000) }
        assertTrue("after disconnect traffic should go directly (got $after)", after != null && after !in sources.values)
        log("disconnected")
    }
}
