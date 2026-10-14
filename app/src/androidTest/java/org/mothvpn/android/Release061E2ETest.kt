package org.mothvpn.android

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.widget.TextView
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/**
 * 0.6.1 on the CI emulator against scripts/e2e/server.py (sing-box + Xray servers on the runner at 10.0.2.2):
 * - home-screen widget states (bound through a test AppWidgetHost and read from the applied RemoteViews),
 * - an app excluded by the split tunnel (org.mothvpn.e2ehelper, e2e-helper module) reaches the host only directly,
 * - UDP over XHTTP through the Xray bridge (UDP echo server),
 * - a CA installed as a user CA (by the workflow, adb root) is trusted for the subscription download and both cores.
 */
@RunWith(AndroidJUnit4::class)
class Release061E2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val host = "10.0.2.2"
    private val tunneled = setOf("127.0.0.11", "127.0.0.12", "127.0.0.13", "127.0.0.19", "127.0.0.20", "127.0.0.21")
    private val helper = "org.mothvpn.e2ehelper"

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
    private fun shell(command: String): String = runCatching {
        instrumentation.uiAutomation.executeShellCommand(command).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }
    }.getOrElse { "error: ${it.javaClass.simpleName}" }
    private fun launch() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        wait(By.text("Settings")); Thread.sleep(800)
    }
    private fun tab(name: String) { wait(By.text(name)).click(); Thread.sleep(800) }
    private val hwid by lazy { runCatching { SettingsStore(context).load().hwid }.getOrDefault("e2e-hwid-0000") }
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
    @Before fun before() { System.setProperty("http.keepAlive", "false"); reset() }
    @After fun after() { runCatching { device.pressHome() }; reset() }

    /** Network can be briefly unavailable right after the previous test's VPN stopped; retry the download. */
    private fun add(url: String) = eventually(30_000, "subscription $url downloaded") { SubscriptionRefresh.run(context, null, url, null, SettingsStore(context).load(), "e2e"); true }
    private fun use(url: String, name: String): Profile {
        SubscriptionStore(context).clear(); add(url)
        val store = SubscriptionStore(context)
        val profile = store.load()!!.profiles.first { it.name == name }
        store.save(store.load()!!.copy(selected = profile.id, wifiSelected = profile.id, mobileSelected = profile.id))
        return profile
    }
    private fun connect() {
        launch(); tab("Home")
        wait(By.desc("Connect VPN").enabled(true)).click()
        wait(By.text("VPN is on"), 60_000, "connect")
    }
    private fun disconnect() {
        launch(); tab("Home")
        wait(By.desc("Disconnect VPN").enabled(true)).click()
        eventually(30_000, "disconnected") { off() }
        Thread.sleep(1500)
    }

    // --- (a) widget ---------------------------------------------------------------------------------------------

    private class BoundWidget(val host: AppWidgetHost, val id: Int, val view: AppWidgetHostView)
    private fun bindWidget(): BoundWidget {
        log("grantbind: " + shell("cmd appwidget grantbind --package ${context.packageName} --user 0").trim().ifEmpty { "ok" })
        val manager = AppWidgetManager.getInstance(context)
        var bound: BoundWidget? = null
        instrumentation.runOnMainSync {
            val widgetHost = AppWidgetHost(context, 6101)
            widgetHost.startListening()
            val id = widgetHost.allocateAppWidgetId()
            check(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, MothWidget::class.java))) { "binding the widget was not allowed" }
            bound = BoundWidget(widgetHost, id, widgetHost.createView(context, id, manager.getAppWidgetInfo(id)))
        }
        return bound!!
    }
    private fun BoundWidget.text(viewId: Int): String? {
        var text: String? = null
        instrumentation.runOnMainSync { text = view.findViewById<TextView>(viewId)?.text?.toString() }
        return text
    }
    private fun BoundWidget.expect(what: String, ms: Long, states: Set<String>, button: String) {
        MothWidget.update(context)
        eventually(ms, "widget $what (state one of $states, button $button; now ${text(R.id.widget_state)} / ${text(R.id.widget_button)})") {
            text(R.id.widget_state) in states && text(R.id.widget_button) == button
        }
        log("widget $what: ${text(R.id.widget_server)} | ${text(R.id.widget_state)} | ${text(R.id.widget_button)}")
    }
    private fun airplane(on: Boolean) {
        log("airplane $on: " + shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}").trim().ifEmpty { "ok" })
    }

    @Test fun widgetShowsDisconnectedConnectedBlockedAndReconnecting() {
        val widget = bindWidget()
        try {
            val profile = use("https://$host:8443/sub", "E2E VLESS")
            SettingsStore(context).save(SettingsStore(context).load().copy(killSwitch = true))
            widget.expect("disconnected", 15_000, setOf(tr(R.string.tile_off)), tr(R.string.widget_connect))
            assertEquals(profile.name, widget.text(R.id.widget_server))

            connect()
            eventually(30_000, "traffic through VLESS") { fetch("/ip") == "127.0.0.11" }
            widget.expect("connected", 15_000, setOf(tr(R.string.tile_on)), tr(R.string.widget_disconnect))

            // Network loss with the kill switch on: the widget keeps "Disconnect" and shows the blocked/reconnecting state.
            airplane(true)
            try {
                widget.expect("network lost", 45_000, setOf(tr(R.string.tile_reconnecting), tr(R.string.tile_blocked)), tr(R.string.widget_disconnect))
            } finally { airplane(false) }
            widget.expect("reconnected", 120_000, setOf(tr(R.string.tile_on)), tr(R.string.widget_disconnect))
            eventually(60_000, "traffic after reconnect") { fetch("/ip") == "127.0.0.11" }

            // A server the core rejects: kill switch keeps traffic blocked and the widget says so.
            disconnect()
            widget.expect("disconnected again", 15_000, setOf(tr(R.string.tile_off)), tr(R.string.widget_connect))
            val broken = Profile("0123456789abcdef0123456789abcdef", "Broken server", "shadowsocks",
                "{\"type\":\"shadowsocks\",\"tag\":\"proxy\",\"server\":\"$host\",\"server_port\":20003,\"method\":\"no-such-cipher\",\"password\":\"x\"}")
            SubscriptionStore(context).saveBook(SubscriptionBook(listOf(SubscriptionSource(SubscriptionSource.LOCAL_ID, "My servers", SubscriptionRecord.LOCAL_URL, listOf(broken))), broken.id))
            SettingsStore(context).save(SettingsStore(context).load().copy(killSwitch = true, healthEnabled = false))
            launch(); tab("Home"); wait(By.desc("Connect VPN").enabled(true)).click()
            eventually(60_000, "blocked") { TunnelStatus.trafficBlocked(TunnelStatus.state.value, TunnelStatus.blocking.value) }
            widget.expect("blocked", 20_000, setOf(tr(R.string.tile_blocked), tr(R.string.tile_reconnecting)), tr(R.string.widget_disconnect))
            assertEquals("Broken server", widget.text(R.id.widget_server))
            assertNull("traffic leaked while blocked", fetch("/ip", 4000))
        } finally {
            airplane(false)
            instrumentation.runOnMainSync { runCatching { widget.host.deleteAppWidgetId(widget.id); widget.host.stopListening() } }
        }
        stopVpn()
        eventually(90_000, "direct network back after the widget test") { fetch("/ip", 4000)?.let { it !in tunneled } == true }
    }

    // --- (b) split tunnel -----------------------------------------------------------------------------------------

    /** Runs the helper's probe: "direct=<ip|ERR..>;bound=<ip|ERR..|NOVPN>;tunip=<ip|ERR..|NOTUN>". */
    private fun probeHelper(): Map<String, String> {
        val out = shell("am broadcast -f 32 --receiver-foreground -n $helper/.Probe --es host $host")
        val data = Regex("data=\"([^\"]*)\"").find(out)?.groupValues?.get(1) ?: throw AssertionError("helper gave no result: $out")
        log("helper: $data")
        return data.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun excludedAppStaysOutside(url: String, name: String, tunnelSource: String) {
        use(url, name)
        SettingsStore(context).save(SettingsStore(context).load().copy(splitMode = "bypass", splitApps = listOf(helper)))
        connect()
        eventually(30_000, "$name carries Moth's own traffic") { fetch("/ip") == tunnelSource }
        assertTrue("VPN interface up", java.net.NetworkInterface.getByName("tun0") != null)
        var result: Map<String, String> = emptyMap()
        // The receiver can be slow on the first start of the helper process; retry the direct part only.
        eventually(45_000, "excluded app reaches the host directly") { probeHelper().also { result = it }["direct"].let { it != null && !it.startsWith("ERR") } }
        assertFalse("excluded app went through the VPN: $result", result["direct"] in tunneled)
        val bound = result["bound"].orEmpty()
        assertTrue("socket bound to the VPN network must be rejected for an excluded app: $result", bound.startsWith("ERR") || bound == "NOVPN")
        if (bound == "NOVPN") log("helper cannot see the VPN network at all (counts as rejected)")
        val tunIp = result["tunip"].orEmpty()
        assertFalse("socket bound to the tun0 address reached the host through the VPN: $result", tunIp in tunneled)
        disconnect()
    }

    @Test fun excludedAppCannotUseTheVpnSingBox() {
        assertTrue("helper APK $helper must be installed (CI installs e2e-helper)", shell("pm list packages $helper").contains(helper))
        excludedAppStaysOutside("https://$host:8443/sub", "E2E VLESS", "127.0.0.11")
    }

    @Test fun excludedAppCannotUseTheVpnXray() {
        assertTrue("helper APK $helper must be installed (CI installs e2e-helper)", shell("pm list packages $helper").contains(helper))
        excludedAppStaysOutside("https://$host:8443/sub5", "E2E XHTTP", "127.0.0.19")
    }

    // --- (c) UDP over XHTTP -------------------------------------------------------------------------------------------

    private fun udpEcho(payload: String): String? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = 3000
            val data = payload.toByteArray()
            s.send(DatagramPacket(data, data.size, InetSocketAddress(host, 9999)))
            val buffer = ByteArray(2048); val packet = DatagramPacket(buffer, buffer.size)
            s.receive(packet); String(packet.data, 0, packet.length)
        }
    }.getOrNull()

    @Test fun udpOverXhttpThroughXrayBridge() {
        val profile = use("https://$host:8443/sub5", "E2E XHTTP")
        assertEquals("xray", profile.engine)
        connect()
        eventually(30_000, "TCP via XHTTP") { fetch("/ip") == "127.0.0.19" }
        // The echo replies "<source> <payload>": 127.0.0.19 is the Xray server's freedom outbound, so the datagram went over XHTTP.
        eventually(45_000, "UDP echo via XHTTP") { udpEcho("moth-udp-1") == "127.0.0.19 moth-udp-1" }
        repeat(5) { i -> eventually(15_000, "UDP echo $i") { udpEcho("moth-udp-x$i") == "127.0.0.19 moth-udp-x$i" } }
        log("UDP over XHTTP ok")
        disconnect()
    }

    // --- (d) user-installed CA ----------------------------------------------------------------------------------------

    @Test fun userInstalledCaTrustedForSubscriptionAndBothCores() {
        assertTrue("user CA visible in AndroidCAStore (the workflow installs scripts/e2e/user-ca.pem with adb root)", TrustedCertificates.userCount() > 0)
        // Subscription over HTTPS whose certificate only the user CA can verify (not the bundled debug test CA).
        add("https://$host:8444/sub")
        val names = SubscriptionStore(context).load()!!.profiles.map { it.name }
        assertEquals(listOf("UserCA Trojan", "UserCA XHTTP"), names)
        for ((name, source) in listOf("UserCA Trojan" to "127.0.0.21", "UserCA XHTTP" to "127.0.0.20")) {
            val store = SubscriptionStore(context)
            val p = store.load()!!.profiles.first { it.name == name }
            store.save(store.load()!!.copy(selected = p.id, wifiSelected = p.id, mobileSelected = p.id))
            connect()
            eventually(45_000, "$name via TLS with the user CA") { fetch("/ip") == source }
            log("user CA ok: $name (${p.engine})")
            disconnect()
        }
    }
}
