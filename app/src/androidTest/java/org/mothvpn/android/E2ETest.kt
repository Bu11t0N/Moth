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
    private fun tab(name: String) { wait(By.text(name).clickable(true)).click(); Thread.sleep(800) }

    @Test fun importConnectSwitchKillSwitchDisconnect() {
        System.setProperty("http.keepAlive", "false")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val direct = tryFetch()
        log("direct path source: $direct")
        assertNotNull("echo server unreachable from the emulator", direct)
        assertFalse("direct path must not look tunneled", direct in sources.values)

        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // 1. Import the HTTPS subscription (the test CA is trusted in debug builds only).
        wait(By.text("Add subscription").clickable(true)).click()
        wait(By.clazz("android.widget.EditText")).text = "https://$host:8443/sub"
        wait(By.text("Add").clickable(true)).click()
        wait(By.text("E2E VLESS"), 60_000)
        log("subscription imported")

        // 2. Connect and check the traffic goes through the VLESS server.
        wait(By.desc("Connect VPN").enabled(true)).click()
        wait(By.text("VPN is on"), 60_000)
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
        wait(By.text("Connection").clickable(true)).click()
        val label = wait(By.text("Kill switch"))
        val toggle = label.parent.findObject(By.checkable(true)) ?: run { dump(); throw AssertionError("kill switch toggle not found") }
        if (!toggle.isChecked) toggle.click()
        Thread.sleep(800)
        assertTrue("kill switch should be on", wait(By.text("Kill switch")).parent.findObject(By.checkable(true)).isChecked)
        device.pressBack(); Thread.sleep(500); tab("Home")
        assertEquals("paused", fetch("/pause=25"))
        Thread.sleep(4000)
        repeat(3) {
            val seen = tryFetch(timeoutMs = 4000)
            log("while the server is down: $seen")
            assertNull("traffic leaked outside the tunnel while the server was down", seen)
            Thread.sleep(1000)
        }
        assertNotNull("VPN should stay on while the server is down", device.findObject(By.desc("Disconnect VPN")))
        awaitSource(sources.getValue("trojan"), 90_000)
        log("recovered after the server came back")

        // 5. Disconnect: traffic goes directly again.
        wait(By.desc("Disconnect VPN").enabled(true)).click()
        wait(By.desc("Connect VPN"), 30_000)
        val end = System.currentTimeMillis() + 30_000
        var after: String? = null
        while (System.currentTimeMillis() < end) { after = tryFetch(); if (after != null && after !in sources.values) break; Thread.sleep(1000) }
        assertTrue("after disconnect traffic should go directly (got $after)", after != null && after !in sources.values)
        log("disconnected")
    }
}
