package com.ffvpn.client.core

import android.os.SystemClock
import android.util.Log
import com.ffvpn.client.Texts
import com.ffvpn.client.vpn.Logs
import java.util.concurrent.CopyOnWriteArrayList

object AppState {
    enum class Mode {
        VPN,
        PROXY
    }

    data class State(
        val mode: Mode = Mode.VPN,
        val coreId: String = "firefox",
        val shareLan: Boolean = false,
        val running: Boolean = false,
        val busy: Boolean = false,
        val connected: Boolean = false,
        val disconnecting: Boolean = false,
        val statusCode: Int = StatusText.READY,
        val statusDetail: String = "",
        val proxy: String = "",
        /** "192.168.1.5:1081" for LAN clients using SOCKS5; blank while sharing is off. */
        val lan: String = "",
        /** "192.168.1.5:8080" for LAN clients using HTTP; blank while sharing is off. */
        val lanHttp: String = "",
        /** SystemClock.elapsedRealtime() when the attempt started; 0 when idle. */
        val startedAt: Long = 0L,
        /** SystemClock.elapsedRealtime() when the tunnel came up; 0 until then. */
        val connectedAt: Long = 0L,
        /** How long the successful attempt took, in milliseconds; 0 until then. */
        val connectMillis: Long = 0L,
        /** SystemClock.elapsedRealtime() of the automatic retry, 0 while one is not queued. */
        val retryAt: Long = 0L,
        /** How many attempts have already failed, including the queued one. */
        val retryNumber: Int = 0,
        /** Likely cause of the last failure, worked out by the probes; blank until then. */
        val diagnosis: String = "",
        /** Exit the connected core dialled, when it reports one. */
        val exit: String = "",
        /** Round-trip of the chosen exit, 0 when the core does not report one. */
        val latencyMs: Int = 0,
        /** Free bandwidth left on the account, blank for the anonymous cores. */
        val quota: String = "",
        /** Address the tunnel hands to the outside world, blank until it is known. */
        val exitIp: String = "",
        /** Where that address lives, as the echo service names it. */
        val exitPlace: String = "",
        /** A newer release than the installed one, null until a check finds it. */
        val update: GithubUpdates.Release? = null
    )

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    var state: State = State()
        private set

    fun listen(listener: (State) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }

    fun update(block: (State) -> State) {
        val next: State
        synchronized(this) {
            next = block(state)
            state = next
        }
        listeners.forEach { runCatching { it(next) } }
    }

    /** Stamps the first success of the current attempt and how long it took. */
    fun stamped(state: State): State {
        if (state.connectedAt != 0L) return state
        val now = SystemClock.elapsedRealtime()
        return state.copy(
            connectedAt = now,
            connectMillis = if (state.startedAt > 0L) now - state.startedAt else 0L
        )
    }

    fun log(line: String) {
        Log.i(TAG, line)
        Logs.add(state.coreId.ifBlank { "app" }, line)
        certificateComplaint(line)?.let {
            Log.i(TAG, it)
            Logs.add("app", it)
        }
    }

    /**
     * Turns an engine's certificate error into a sentence that cannot be mistaken
     * for a blackout, because on this kind of link both look the same from the UI.
     * Once per run: the same line repeats for every exit.
     */
    private fun certificateComplaint(line: String): String? {
        if (certNoted || !CERTIFICATE.containsMatchIn(line)) return null
        certNoted = true
        return "note: the peer's certificate was not trusted, which is a trust-store " +
            "problem and not a network outage (bundle=${CaBundle.path()})"
    }

    private val CERTIFICATE = Regex(
        "x509|unknown authority|certificate has expired|self[- ]signed certificate|certificate verify failed",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    private var certNoted = false

    private const val TAG = "NiloofarVPN"
}

/**
 * Waits between attempts the way a censored line expects: a few quick retries
 * for a transient failure, then long pauses so nothing hammers the network.
 */
object RetryLadder {
    const val MAX_ATTEMPTS = 6

    fun delayMillis(failures: Int): Long = when {
        failures <= 3 -> 2_000L
        else -> (15_000L shl (failures - 4)).coerceAtMost(300_000L)
    }
}

object StatusText {
    const val READY = 0
    const val STOPPED = 2100
    const val REVOKED = 2101
    const val DENIED = 2102
    const val TIMEOUT = 2103
    const val TUNNEL_FAILED = 2104
    const val START_FAILED = 2105
    const val ERROR = 2106
    /** The core reported ready but no byte ever reached the internet. */
    const val NO_TRAFFIC = 2107

    fun isConnected(code: Int): Boolean = code == 1004 || code == 1017

    fun describe(code: Int, detail: String): String = when {
        code == READY -> Texts.ready
        code == 1002 -> Texts.connecting
        isConnected(code) -> Texts.connected
        code == 2004 -> Texts.disconnected
        code == 2005 -> Texts.failed
        code == STOPPED -> Texts.stopped
        code == REVOKED -> Texts.revoked
        code == DENIED -> Texts.denied
        code == TIMEOUT -> Texts.timeout
        code == TUNNEL_FAILED -> Texts.tunnelFailed
        code == START_FAILED -> Texts.startFailed(detail)
        code == NO_TRAFFIC ->
            if (detail.isBlank()) Texts.noTraffic else Texts.noTrafficDetail(detail)
        code >= 2000 -> Texts.error(detail.ifBlank { Texts.status(code) })
        // An engine's own English sentence shown inside Persian text.
        detail.isNotBlank() -> Texts.ui(detail)
        else -> Texts.status(code)
    }
}
