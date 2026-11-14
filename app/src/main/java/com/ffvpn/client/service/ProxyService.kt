package com.ffvpn.client.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ffvpn.client.Notifications
import com.ffvpn.client.Texts
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.CoreRegistry
import com.ffvpn.client.core.Diagnostics
import com.ffvpn.client.core.EngineHandoff
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.Reachability
import com.ffvpn.client.core.RetryLadder
import com.ffvpn.client.core.StatusText
import com.ffvpn.client.core.VpnCore
import com.ffvpn.client.vpn.ExitProbe
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ProxyService : Service() {
    private val workers = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private var session: CoreSession? = null
    private var active = false
    private var destroyed = false
    private val notified = AtomicBoolean(false)
    /** Bumped by every attempt and every stop, so a paused retry can be cancelled. */
    private val generation = AtomicLong(0)
    /** One reachability check per attempt, however often the core repeats "ready". */
    private val verified = AtomicBoolean(false)
    private val verifying = AtomicBoolean(false)
    private var attempt = 0

    @Volatile
    private var core: VpnCore? = null

    @Volatile
    private var stopping = false
    /**
     * True once this session was ended on purpose. Mirrors the VPN service: the
     * ladder may only rebuild a tunnel the user never asked to lose, and it checks
     * this again when the wait ends, because `stopping` can be read just before a
     * teardown lands and then the proxy comes back after the user turned it off.
     */
    private val wantsClosed = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (active) {
                    teardown(StatusText.STOPPED, "", "proxy stopped by user", intent.getBooleanExtra(EXTRA_EXIT, false))
                } else {
                    AppState.update { it.copy(disconnecting = false, busy = false) }
                    stopSelf(startId)
                }
            }
            ACTION_START -> startProxy(intent.getStringExtra(EXTRA_CORE), first = true)
        }
        return START_NOT_STICKY
    }

    private fun startProxy(coreId: String?, first: Boolean) {
        val state = AppState.state
        if (destroyed || state.disconnecting ||
            (first && (state.running || state.busy || state.connected))) {
            if (!active) stopSelf()
            return
        }
        if (first) {
            attempt = 0
            wantsClosed.set(false)
        }
        active = true
        verified.set(false)
        verifying.set(false)
        startForeground(
            Notifications.NOTIFICATION_ID,
            Notifications.build(this, Texts.appName, Texts.notificationConnecting)
        )
        val selected = CoreRegistry.get(coreId)
        core = selected
        stopping = false
        notified.set(false)
        val gen = generation.incrementAndGet()
        val activeSession = CoreSession(selected, workers)
        session = activeSession
        AppState.update {
            it.copy(
                mode = AppState.Mode.PROXY,
                coreId = selected.id,
                shareLan = Prefs.shareLan,
                running = true,
                busy = true,
                connected = false,
                statusCode = 1002,
                statusDetail = "",
                proxy = "",
                lan = "",
                lanHttp = "",
                startedAt = SystemClock.elapsedRealtime(),
                connectedAt = 0L,
                connectMillis = 0L,
                retryAt = 0L,
                retryNumber = 0,
                diagnosis = "",
                exit = "",
                latencyMs = 0,
                quota = "",
                exitIp = "",
                exitPlace = ""
            )
        }
        selected.setSocketProtector(null)
        selected.setStatusListener { code, message, data ->
            main.post {
                if (stopping || stale(gen) || AppState.state.disconnecting) return@post
                AppState.log("status $code $message $data")
                val connected = StatusText.isConnected(code)
                // "Ready" from the core only means its local SOCKS port answers.
                // Whether the exit carries anything is checked before this app
                // tells the user it is connected; see verifyThenMark.
                if (connected) {
                    verifyThenMark(selected, gen)
                    return@post
                }
                AppState.update { current ->
                    val stillUp = current.connected && code < ERROR_CODE
                    val next = current.copy(
                        statusCode = when {
                            connected -> 1004
                            stillUp -> current.statusCode
                            else -> code
                        },
                        statusDetail = if (connected || stillUp) current.statusDetail else message,
                        connected = current.connected || connected,
                        busy = if (connected) false else current.busy,
                        proxy = if (connected) Prefs.socksAddress else current.proxy
                    )
                    if (connected) AppState.stamped(next) else next
                }
                if (connected) markRunning(selected, gen)
            }
        }
        workers.execute {
            if (stale(gen)) return@execute
            // The previous engine keeps the local proxy port until its own dial
            // returns; binding on top of it would fail or steal the wrong tunnel.
            if (EngineHandoff.pending()) {
                main.post {
                    if (!stale(gen)) {
                        AppState.update { it.copy(statusDetail = Texts.waitingForEngine) }
                    }
                }
                EngineHandoff.await()
                main.post {
                    if (!stale(gen)) AppState.update { it.copy(statusDetail = "") }
                }
            }
            if (stale(gen)) return@execute
            val error = try {
                selected.connect()
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            main.post {
                if (stopping || stale(gen) || AppState.state.disconnecting) return@post
                if (!error.isNullOrBlank()) {
                    AppState.log("connect returned: $error")
                    if (AppState.state.connected) {
                        AppState.update { it.copy(statusCode = StatusText.ERROR, statusDetail = error) }
                    } else {
                        fail(error, gen)
                    }
                }
            }
        }
    }

    private fun stale(gen: Long) = generation.get() != gen

    /**
     * Holds the announcement back until YouTube or Facebook has actually opened
     * through the tunnel. An exit that is blocked at the first real request is a
     * failed attempt, not a green ring, so it goes down the retry ladder and the
     * next exit or core gets a turn.
     */
    private fun verifyThenMark(selected: VpnCore, gen: Long) {
        if (!verified.compareAndSet(false, true)) return
        if (!verifying.compareAndSet(false, true)) return
        AppState.update {
            it.copy(busy = true, statusCode = 1002, statusDetail = Texts.verifyingTunnel)
        }
        workers.execute {
            val reach = Reachability.verify(selected.proxyAddress) { stopping || stale(gen) }
            verifying.set(false)
            main.post {
                if (stopping || stale(gen) || AppState.state.disconnecting) return@post
                if (!reach.ok) {
                    AppState.log("proxy is up but carries nothing: ${reach.detail}")
                    verified.set(false)
                    fail(reach.detail, gen, StatusText.NO_TRAFFIC)
                    return@post
                }
                AppState.update { current ->
                    AppState.stamped(
                        current.copy(
                            running = true,
                            busy = false,
                            connected = true,
                            statusCode = 1004,
                            statusDetail = "",
                            proxy = Prefs.socksAddress
                        )
                    )
                }
                markRunning(selected, gen)
            }
        }
    }

    private fun fail(detail: String, gen: Long, code: Int = StatusText.ERROR) {
        if (Looper.myLooper() != main.looper) {
            main.post { fail(detail, gen) }
            return
        }
        if (stale(gen) || stopping || AppState.state.disconnecting) return
        val selected = core
        val blocked = selected?.blockedOnUser == true
        if (selected == null || wantsClosed.get() || attempt >= RetryLadder.MAX_ATTEMPTS ||
            !Prefs.autoRetry || blocked) {
            teardown(code, detail)
            val stoppedGeneration = generation.get()
            if (!blocked) workers.execute {
                val cause = runCatching { Diagnostics.explain() }.getOrNull()
                main.post {
                    if (!stale(stoppedGeneration) && !AppState.state.running && cause != null) {
                        AppState.update { it.copy(diagnosis = cause) }
                    }
                }
            }
            return
        }
        val failures = attempt + 1
        attempt = failures
        val delay = RetryLadder.delayMillis(failures)
        AppState.log("attempt $failures failed ($detail), retrying in ${delay}ms")
        val retryGeneration = generation.incrementAndGet()
        stopping = true
        ExitProbe.cancel()
        TunnelWatchdog.stop()
        LanShare.stop()
        ProxyFront.stop()
        AppState.update {
            it.copy(
                running = true,
                busy = true,
                connected = false,
                disconnecting = true,
                statusCode = 1002,
                statusDetail = "",
                proxy = "",
                startedAt = SystemClock.elapsedRealtime(),
                connectedAt = 0L,
                connectMillis = 0L,
                retryAt = SystemClock.elapsedRealtime() + delay,
                retryNumber = failures,
                diagnosis = "",
                exitIp = "",
                exitPlace = ""
            )
        }
        stopCore {
            AppState.update { it.copy(disconnecting = false) }
            main.postDelayed({
                if (!stale(retryGeneration) && !wantsClosed.get() && !destroyed) {
                    AppState.log("automatic retry $failures for core ${selected.id}")
                    startProxy(selected.id, first = false)
                }
            }, (AppState.state.retryAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        }
    }

    private fun markRunning(selected: VpnCore, gen: Long) {
        val coreProxy = selected.proxyAddress
        ProxyFront.start(coreProxy)
        AppState.update {
            AppState.stamped(
                it.copy(
                    running = true,
                    busy = false,
                    connected = true,
                    statusCode = 1004,
                    statusDetail = "",
                    proxy = Prefs.socksAddress
                )
            )
        }
        if (notified.compareAndSet(false, true)) {
            AppState.log("SOCKS5 listening on ${Prefs.socksAddress}, HTTP on ${Prefs.httpAddress}")
            ExitProbe.start()
            getSystemService(NotificationManager::class.java).notify(
                Notifications.NOTIFICATION_ID,
                Notifications.build(this, Texts.appName, Texts.notificationProxy)
            )
            ConnectSound.ping(this)
        }
        if (Prefs.shareLan) {
            LanShare.start(coreProxy)
        }
        TunnelWatchdog.start(Prefs.socksPort, selected.trafficProbeTimeoutMs) {
            fail("no traffic through the tunnel", gen)
        }
    }

    /**
     * Lets the session go now and stops the engine on its own worker. An engine
     * dialling inside a native start does not return for its whole connect
     * timeout, so waiting for it here would hold the app on "disconnecting".
     */
    private fun stopCore(onStopped: () -> Unit) {
        val activeSession = session
        session = null
        core = null
        activeSession?.stop()?.whenComplete { _, error ->
            if (error != null) AppState.log("proxy stop incomplete: ${error.message}")
        }
        onStopped()
    }

    private fun teardown(code: Int, detail: String, logLine: String? = null, exit: Boolean = false) {
        wantsClosed.set(true)
        generation.incrementAndGet()
        stopping = true
        ExitProbe.cancel()
        TunnelWatchdog.stop()
        LanShare.stop()
        ProxyFront.stop()
        AppState.update {
            it.copy(
                running = false,
                busy = true,
                connected = false,
                disconnecting = true,
                statusCode = code,
                statusDetail = detail,
                startedAt = 0L,
                connectedAt = 0L,
                connectMillis = 0L,
                retryAt = 0L,
                retryNumber = 0,
                diagnosis = "",
                exit = "",
                latencyMs = 0,
                quota = "",
                exitIp = "",
                exitPlace = ""
            )
        }
        if (logLine != null) AppState.log(logLine)
        if (exit) AppExit.schedule(this)
        stopCore {
            active = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!destroyed) stopSelf()
            AppState.update { it.copy(busy = false, disconnecting = false) }
            if (exit) AppExit.close(this, "engine stopped")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wantsClosed.set(true)
        stopping = true
        generation.incrementAndGet()
        ExitProbe.cancel()
        LanShare.stop()
        ProxyFront.stop()
        TunnelWatchdog.stop()
        runCatching { core?.setStatusListener(null) }
        // Never join the engine on the main thread: a start stuck inside it makes
        // disconnect block for the engine's whole connect timeout, which here
        // would be an ANR rather than a slow stop.
        val activeSession = session
        session = null
        core = null
        activeSession?.stop()
    }

    companion object {
        private const val ACTION_START = "com.ffvpn.client.proxy.START"
        private const val ACTION_STOP = "com.ffvpn.client.proxy.STOP"
        private const val EXTRA_CORE = "core"
        private const val EXTRA_EXIT = "exit"
        private const val ERROR_CODE = 2000

        fun start(context: Context, coreId: String) {
            val state = AppState.state
            if (state.disconnecting || state.running || state.busy || state.connected) return
            val intent = Intent(context, ProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CORE, coreId)
            context.startForegroundService(intent)
        }

        /** [exit] also closes the whole app once the engine has stopped. */
        fun stop(context: Context, exit: Boolean = false) {
            val state = AppState.state
            if (state.disconnecting && !exit) return
            if (state.running || state.busy || state.connected) {
                AppState.update { it.copy(disconnecting = true, busy = true) }
            }
            context.startService(
                Intent(context, ProxyService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_EXIT, exit)
            )
        }
    }
}
