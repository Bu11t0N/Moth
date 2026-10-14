package org.mothvpn.core

import org.junit.Assert.*
import org.junit.Test
import org.mothvpn.core.UpdatePhase.Event

class UpdateFlowTest {
    @Test fun cancelDuringDownloadResets() {
        val downloading = UpdatePhase.Idle.on(Event.CHECK).on(Event.DOWNLOAD).on(Event.DOWNLOAD, 0.4f)
        assertEquals(UpdatePhase.Downloading(0.4f), downloading)
        assertTrue(downloading.busy)
        val after = downloading.on(Event.CANCELLED)
        assertSame(UpdatePhase.Idle, after)
        assertFalse(after.busy)
        assertEquals(UpdatePhase.Downloading(null), after.on(Event.DOWNLOAD)) // can start again
    }
    @Test fun dismissingInstallResets() {
        val ready = UpdatePhase.Downloading(1f).on(Event.READY)
        assertSame(UpdatePhase.Installing, ready)
        assertSame(UpdatePhase.Idle, ready.on(Event.DIALOG_DISMISSED))
        assertSame(UpdatePhase.Idle, ready.on(Event.INSTALL_ABORTED))
        assertSame(UpdatePhase.Idle, UpdatePhase.Checking.on(Event.FAILED))
    }
}
