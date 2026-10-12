package org.mothvpn.core

/** Stable outcome codes of a failed or unusual server check; the UI maps them to icons and localized texts. */
enum class ProbeErrorKind {
    TIMEOUT, DNS_TIMEOUT, DNS_UNRESOLVED, PORT_CLOSED, TLS, FAILED, CANCELLED, UDP_PROTOCOL;

    companion object {
        fun of(e: Throwable): ProbeErrorKind = when (e) {
            is java.net.SocketTimeoutException -> TIMEOUT
            is java.util.concurrent.TimeoutException -> DNS_TIMEOUT
            is java.net.UnknownHostException -> DNS_UNRESOLVED
            is java.net.ConnectException -> PORT_CLOSED
            is javax.net.ssl.SSLException -> TLS
            else -> FAILED
        }
    }
}
