package org.mothvpn.core

import kotlin.test.*

class LogTextTest {
    @Test fun lastLines() {
        assertEquals(listOf("b", "c"), LogText.lastLines("a\nb\nc\n\n", 2))
        assertEquals(listOf("a"), LogText.lastLines("a", 30))
        assertEquals(emptyList(), LogText.lastLines("", 5))
    }
    @Test fun truncate() {
        assertEquals("abc", LogText.truncate("abc", 3))
        assertTrue(LogText.truncate("abcdef", 3).startsWith("abc\n"))
    }
    @Test fun printable() {
        assertEquals("a.b\nc", LogText.printable(byteArrayOf(97, 0, 98, 10, 99)))
    }
    @Test fun unprocessed() {
        assertEquals(listOf(5L, 9L), LogText.unprocessed(listOf(9L, 3L, 5L, 2L), 3) { it })
    }
}
