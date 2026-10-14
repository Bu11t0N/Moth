package org.mothvpn.core

/**
 * State of the in-app updater screen. Every way out of a download or install (cancel, dismissing the
 * permission dialog, closing the system install confirmation, failure) returns to [Idle] so the
 * "downloading" status disappears and the user can start again.
 */
sealed class UpdatePhase {
    object Idle : UpdatePhase()
    object Checking : UpdatePhase()
    data class Downloading(val progress: Float?) : UpdatePhase()
    /** The APK is ready; waiting for the install permission or the system confirmation. */
    object Installing : UpdatePhase()
    val busy get() = this is Checking || this is Downloading

    enum class Event { CHECK, DOWNLOAD, READY, CANCELLED, DIALOG_DISMISSED, INSTALL_ABORTED, FAILED, DONE }
    fun on(event: Event, progress: Float? = null): UpdatePhase = when (event) {
        Event.CHECK -> Checking
        Event.DOWNLOAD -> if (this is Downloading && progress == null) this else Downloading(progress)
        Event.READY -> Installing
        Event.CANCELLED, Event.DIALOG_DISMISSED, Event.INSTALL_ABORTED, Event.FAILED, Event.DONE -> Idle
    }
}
