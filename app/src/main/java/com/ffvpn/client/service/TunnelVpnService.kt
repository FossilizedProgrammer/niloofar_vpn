package com.ffvpn.client.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TunnelVpnService : VpnService() {
    private val workers = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private var session: CoreSession? = null
    private var destroyed = false
    @Volatile
    private var core: VpnCore? = null
    @Volatile
    private var tun: ParcelFileDescriptor? = null
    /** Duplicate handed to the core; kept so the service can always release it. */
    @Volatile
    private var tunCore: ParcelFileDescriptor? = null
    @Volatile
    private var stopping = false
    @Volatile
    private var connectLatch: CountDownLatch? = null
    /**
     * Bumped by every connect attempt and every teardown. A worker only commits
     * state or keeps a tunnel while its own generation is still current, which
     * stops a cancelled attempt from resurrecting the session afterwards.
     */
    private val generation = AtomicLong(0)
    /** Failed attempts of the current session, so the retry ladder can back off. */
    private var attempt = 0
    /**
     * True once this session was ended on purpose - by the user, by a revoked
     * permission or by the last failed attempt. The ladder may only ever rebuild
     * a tunnel the user never asked to lose, so a queued retry checks this flag
     * again at the moment it would connect: `stopping` alone can be read a few
     * microseconds before the teardown lands, and then the app reconnects on its
     * own right after the user pressed the ring.
     */
    private val wantsClosed = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val exit = intent.getBooleanExtra(EXTRA_EXIT, false)
                shutdown(StatusText.STOPPED, "VPN stopped by user", exit)
            }
            ACTION_START -> startTunnel(intent.getStringExtra(EXTRA_CORE), first = true)
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(coreId: String?, first: Boolean) {
        if (destroyed || AppState.state.disconnecting ||
            (first && (AppState.state.running || AppState.state.busy || AppState.state.connected))) return
        if (first) {
            attempt = 0
            wantsClosed.set(false)
        }
        startForeground(
            Notifications.NOTIFICATION_ID,
            Notifications.build(this, Texts.appName, Texts.notificationConnecting)
        )
        val selected = CoreRegistry.get(coreId)
        core = selected
        val gen = generation.incrementAndGet()
        stopping = false
        val activeSession = CoreSession(selected, workers)
        session = activeSession
        val ready = CountDownLatch(1)
        connectLatch = ready
        val error = AtomicReference<String?>(null)
        selected.setSocketProtector { fd -> protect(fd) }
        AppState.update {
            it.copy(
                mode = AppState.Mode.VPN,
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
        selected.setStatusListener { code, message, data ->
            main.post {
                if (stopping || stale(gen)) return@post
                AppState.log("status $code $message $data")
                val readyFromCore = StatusText.isConnected(code)
                AppState.update { state ->
                    // A core's "ready" only means its local SOCKS port answers.
                    // On a filtered link the exit it chose can still be blocked,
                    // so the ring waits for the reachability check below instead
                    // of calling this connected now.
                    val stillUp = state.connected && code < ERROR_CODE
                    state.copy(
                        statusCode = when {
                            readyFromCore -> if (state.connected) 1004 else 1002
                            stillUp -> state.statusCode
                            else -> code
                        },
                        statusDetail = when {
                            readyFromCore ->
                                if (state.connected) state.statusDetail else Texts.verifyingTunnel
                            stillUp -> state.statusDetail
                            else -> message
                        }
                    )
                }
                if (readyFromCore) {
                    ready.countDown()
                } else if (code >= ERROR_CODE) {
                    error.compareAndSet(null, message.ifBlank { "status $code" })
                    ready.countDown()
                }
            }
        }

        workers.execute {
            if (stale(gen)) return@execute
            // The previous engine keeps the local proxy port until its own dial
            // returns, and the TUN bridge is shared. Dialling before it lets go
            // would either fail to bind or tear down the wrong tunnel.
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
            val result = try {
                selected.connect()
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            if (!result.isNullOrBlank()) {
                error.compareAndSet(null, result)
                ready.countDown()
            }
        }

        workers.execute {
            if (!ready.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                if (!stale(gen)) fail(StatusText.TIMEOUT, "connect timed out")
                return@execute
            }
            if (stale(gen)) return@execute
            val connectError = error.get()
            if (connectError != null) {
                fail(StatusText.ERROR, connectError)
                return@execute
            }

            val builder = Builder()
                .setSession(Texts.appName)
                .addAddress("172.19.0.2", 24)
                .addAddress("fd00::2", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setMtu(1500)
                .setBlocking(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { AppState.log("disallow self failed: ${it.message}") }
            selected.vpnDnsServers.forEach { dns ->
                runCatching { builder.addDnsServer(dns) }
            }
            val descriptor = try {
                builder.establish()
            } catch (t: Throwable) {
                AppState.log("establish failed: ${t.message}")
                null
            }
            if (descriptor == null) {
                if (!stale(gen)) fail(StatusText.TUNNEL_FAILED, "establish failed")
                return@execute
            }
            tun = descriptor
            if (stale(gen)) {
                abandon(descriptor)
                return@execute
            }

            val tunCore = try {
                ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            } catch (t: Throwable) {
                AppState.log("interface dup failed: ${t.message}")
                null
            }
            if (tunCore == null) {
                if (!stale(gen)) fail(StatusText.TUNNEL_FAILED, "interface unavailable")
                return@execute
            }
            runCatching {
                Os.fcntlInt(tunCore.fileDescriptor, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            }
            this.tunCore = tunCore

            val vpnError = try {
                selected.startVpn(tunCore.fd)
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            if (!vpnError.isNullOrBlank()) {
                closeTunnel()
                fail(StatusText.START_FAILED, vpnError)
                return@execute
            }
            // The core reads and writes the duplicated fd from here on; it is
            // released in closeTunnel() once the core has stopped.
            if (stale(gen)) {
                AppState.log("session cancelled while the interface was starting")
                // The teardown may have run before the bridge was up, so its stop
                // found nothing to stop. This session owns the bridge now.
                selected.stopVpn()
                return@execute
            }

            // The core says the interface is up, which is not the same as traffic
            // reaching the internet: on a filtered link the exit it chose can be
            // blocked while the local SOCKS handshake still succeeds. Ask for the
            // sites people actually notice before calling this connected.
            main.post {
                if (!stopping && !stale(gen)) {
                    AppState.update { it.copy(statusCode = 1002, statusDetail = Texts.verifyingTunnel) }
                }
            }
            val reach = Reachability.verify(selected.proxyAddress) { stopping || stale(gen) }
            if (stopping || stale(gen)) {
                selected.stopVpn()
                return@execute
            }
            if (!reach.ok) {
                AppState.log("tunnel is up but carries nothing: ${reach.detail}")
                closeTunnel()
                fail(StatusText.NO_TRAFFIC, reach.detail, gen)
                return@execute
            }

            main.post {
                if (stopping || stale(gen)) return@post
                val coreProxy = selected.proxyAddress
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
                AppState.log("VPN up, core=${selected.id}, socks=$coreProxy")
                ExitProbe.start()
                if (Prefs.shareLan) {
                    ProxyFront.start(coreProxy)
                    LanShare.start(coreProxy)
                }
                getSystemService(NotificationManager::class.java).notify(
                    Notifications.NOTIFICATION_ID,
                    Notifications.build(this, Texts.appName, Texts.notificationVpn)
                )
                ConnectSound.ping(this)
                TunnelWatchdog.start(
                    Prefs.socksPort,
                    selected.trafficProbeTimeoutMs
                ) { fail(StatusText.NO_TRAFFIC, "no traffic through the tunnel", gen) }
            }
        }
    }

    private fun stale(gen: Long) = generation.get() != gen

    /** Closes a tunnel whose session was cancelled before it could commit. */
    private fun abandon(descriptor: ParcelFileDescriptor) {
        if (tun === descriptor) tun = null
        runCatching { descriptor.close() }
    }

    private fun closeTunnel() {
        val core = tunCore
        tunCore = null
        // A core may have closed the descriptor it was handed, and then the
        // number can be reused by an unrelated file. Only close it while it
        // still points at the tunnel.
        if (core != null && isStillTun(core.fd)) {
            runCatching { core.close() }
        }
        runCatching { tun?.close() }
        tun = null
    }

    private fun isStillTun(fd: Int): Boolean =
        runCatching { Os.readlink("/proc/self/fd/$fd") }.getOrNull() == TUN_LINK

    private fun stopCoreAndReleaseTunnel(onStopped: () -> Unit) {
        closeTunnel()
        val activeSession = session
        session = null
        core = null
        // An engine dialling inside a native start does not return for its whole
        // connect timeout, and its stop waits behind that start. The interface is
        // already closed above, so the session is let go now and the engine
        // finishes on its own worker instead of holding the ring on "disconnecting".
        activeSession?.stop()?.whenComplete { _, error ->
            if (error != null) AppState.log("engine stop incomplete: ${error.message}")
        }
        onStopped()
    }

    private fun teardown(code: Int, detail: String, logLine: String?, exit: Boolean = false) {
        wantsClosed.set(true)
        generation.incrementAndGet()
        stopping = true
        ExitProbe.cancel()
        connectLatch?.countDown()
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
        stopCoreAndReleaseTunnel {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!destroyed) stopSelf()
            AppState.update { it.copy(busy = false, disconnecting = false) }
            if (exit) AppExit.close(this, "engine stopped")
        }
    }

    private fun fail(code: Int, detail: String, gen: Long = generation.get()) {
        if (Looper.myLooper() != main.looper) {
            main.post { fail(code, detail, gen) }
            return
        }
        if (stale(gen) || stopping) return
        val selected = core
        // A timeout already means the core had its full patience window; the
        // ladder is for attempts that gave up on their own.
        val blocked = selected != null && selected.blockedOnUser
        val retryable = selected != null && !stopping && !wantsClosed.get() &&
            attempt < RetryLadder.MAX_ATTEMPTS &&
            code != StatusText.TIMEOUT && Prefs.autoRetry && !blocked
        if (retryable) {
            retry(code, detail, selected!!)
            return
        }
        teardown(code, detail, "vpn failed: $detail")
        // Only after the interface is gone do the probes see the real network.
        // They have nothing to say about a core waiting for the user to sign in.
        if (!blocked) workers.execute { explainFailure() }
    }

    /** Releases the failed session and queues the next attempt further down the ladder. */
    private fun retry(code: Int, detail: String, selected: VpnCore) {
        val failures = attempt + 1
        attempt = failures
        val delay = RetryLadder.delayMillis(failures)
        AppState.log("attempt $failures failed (${detail.ifBlank { "code $code" }}), retrying in ${delay}ms")
        val gen = generation.incrementAndGet()
        stopping = true
        connectLatch?.countDown()
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
        stopCoreAndReleaseTunnel {
            AppState.update { it.copy(disconnecting = false) }
            main.postDelayed({
                if (!stale(gen) && !wantsClosed.get() && !destroyed) {
                    AppState.log("automatic retry $failures for core ${selected.id}")
                    startTunnel(selected.id, first = false)
                }
            }, (AppState.state.retryAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        }
    }

    /** Runs the probes once and publishes what they think is broken. */
    private fun explainFailure() {
        val cause = runCatching { Diagnostics.explain() }.getOrNull() ?: return
        AppState.update { it.copy(diagnosis = cause) }
    }

    private fun shutdown(code: Int, logLine: String, exit: Boolean = false) =
        teardown(code, "", logLine, exit)

    override fun onRevoke() {
        AppState.log("VPN permission revoked")
        teardown(StatusText.REVOKED, "", "VPN permission revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        if (session != null) teardown(StatusText.STOPPED, "", null)
        super.onDestroy()
    }

    companion object {
        private const val TUN_LINK = "/dev/tun"
        private const val ACTION_START = "com.ffvpn.client.tunnel.START"
        private const val ACTION_STOP = "com.ffvpn.client.tunnel.STOP"
        private const val EXTRA_CORE = "core"
        private const val EXTRA_EXIT = "exit"
        private const val CONNECT_TIMEOUT_SECONDS = 900L
        private const val ERROR_CODE = 2000

        fun start(context: Context, coreId: String) {
            val state = AppState.state
            if (state.disconnecting || state.running || state.busy || state.connected) return
            val intent = Intent(context, TunnelVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CORE, coreId)
            context.startForegroundService(intent)
        }

        /** [exit] also closes the whole app once the engine has stopped. */
        fun stop(context: Context, exit: Boolean = false) {
            if (AppState.state.disconnecting && !exit) return
            AppState.update { it.copy(disconnecting = true, busy = true) }
            context.startService(
                Intent(context, TunnelVpnService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_EXIT, exit)
            )
        }
    }
}
