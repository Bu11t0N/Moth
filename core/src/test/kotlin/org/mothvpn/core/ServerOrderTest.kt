package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class ServerOrderTest {
    private val names = listOf("b", "Server 10", "server 2", "A", "a1")
    @Test fun nameOrderIsCaseInsensitiveAndNatural() {
        assertEquals(listOf("A", "a1", "b", "server 2", "Server 10"), ServerOrder.sort(names, ServerOrder.NAME, { it }, { null }))
    }
    @Test fun latencyOrderPutsUnmeasuredLastAndKeepsNameOrderInTies() {
        val ping = mapOf("b" to 50L, "A" to 50L, "server 2" to 10L)
        assertEquals(listOf("server 2", "A", "b", "a1", "Server 10"), ServerOrder.sort(names, ServerOrder.LATENCY, { it }, { ping[it] }))
    }
    @Test fun settingPersistsAndDefaultsToName() {
        assertEquals(ServerOrder.NAME, ClientSettings().serverSort)
        assertEquals(ServerOrder.LATENCY, ClientSettings.from(ClientSettings(serverSort = "latency").json()).serverSort)
        assertFails { ClientSettings(serverSort = "x").validate() }
    }
    @Test fun oldSortByLatencyFlagMigrates() {
        fun old(flag: Boolean) = ClientSettings.from(ClientSettings().json().apply { remove("serverSort"); put("sortByLatency", flag) })
        assertEquals(ServerOrder.LATENCY, old(true).serverSort)
        assertEquals(ServerOrder.NAME, old(false).serverSort)
    }
}
