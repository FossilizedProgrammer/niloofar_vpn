package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.ffvpn.client.service.TunnelWatchdog
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File

/**
 * The Fluxcore engine: the Fluxcore CLI cross-built for Android, run as a child
 * process in zero-privilege proxy mode. It carries WireGuard/AmneziaWG in
 * gVisor's userspace netstack — no TUN, no root — and publishes a loopback
 * SOCKS5 and HTTP listener while it discovers the fleet and dials the fastest
 * gateway that answers. The SOCKS listener is bridged to the TUN by Tun2Socks,
 * exactly like the other executable cores.
 *
 * The engine keeps its identity, config and fleet cache under
 * `os.UserConfigDir()/fluxcore`, so it is started with HOME pointed at the app's
 * filesDir: Android sets no HOME, and without it the engine cannot write the
 * identity it enrols with.
 */
class FluxcoreCore : VpnCore {
    override val id = "fluxcore"
    override val proxyAddress get() = Prefs.socksAddress

    /** The engine resolves on the gateway side; the bridge answers DNS from its mapped cache. */
    override val vpnDnsServers: List<String> = listOf(MAPPED_DNS)

    /** The tunnel is up before the engine says "connected", so probes are warm. */
    override val trafficProbeTimeoutMs: Int = 10_000

    @Volatile
    private var context: Context? = null

    @Volatile
    private var listener: ((Int, String, String) -> Unit)? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopping = true

    @Volatile
    private var vpnStarted = false

    @Volatile
    private var connected = false

    /** Set once the engine prints its "connected" line: the handshake is done. */
    @Volatile
    private var tunnelUp = false

    @Volatile
    private var lastLine = ""

    private val lock = Any()

    override fun init(context: Context) {
        this.context = context.applicationContext
        workDir.mkdirs()
        AppState.log("engine $id binary=${binary.exists()}")
    }

    override fun setSocketProtector(protector: ((Int) -> Boolean)?) = Unit

    override fun setStatusListener(listener: ((Int, String, String) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(): String? {
        if (!binary.exists()) return Texts.engineMissing
        synchronized(lock) {
            workDir.mkdirs()
            stopVpn()
            stopping = false
            connected = false
            // Say the chosen gateway's name instead of "finding gateways": a
            // pinned selection is already a decision, not a search.
            val pinned = ServerChoice.pinnedFluxcore()
            if (pinned != null) {
                val label = Prefs.serverLabel("fluxcore").ifBlank { pinned }
                AppState.log("engine $id dialing selected $pinned ($label)")
                emit(1002, "dialing $label", "")
            } else {
                emit(1002, "connecting", "")
            }
            return dial()
        }
    }

    /** Starts the engine and returns null once its tunnel carries traffic. */
    private fun dial(): String? {
        destroyProcess()
        tunnelUp = false
        val startedAt = SystemClock.elapsedRealtime()
        val child = try {
            // The picker's choice is a country or country:city selector; "auto"
            // clears any selector the engine kept from an earlier run.
            val country = ServerChoice.pinnedFluxcore() ?: "auto"
            ProcessBuilder(
                listOf(
                    binary.absolutePath, "run",
                    "--listen", Prefs.socksAddress,
                    "--http-listen", Prefs.httpAddress,
                    "--country", country
                )
            )
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    // os.UserConfigDir() needs HOME; without it the engine fails
                    // before it can create or read its identity.
                    environment()["HOME"] = appContext.filesDir.absolutePath
                    CaBundle.applyTo(this)
                }
                .start()
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }
        process = child
        pumpLogs(child)
        while (!stopping) {
            if (tunnelUp) {
                AppState.log("engine $id connected, checking traffic")
                if (TunnelWatchdog.servesTraffic(Prefs.socksPort, trafficProbeTimeoutMs)) {
                    connected = true
                    AppState.log(
                        "engine $id tunnel up (${SystemClock.elapsedRealtime() - startedAt}ms)"
                    )
                    emit(1004, "ready", "")
                    watch()
                    return null
                }
                destroyProcess()
                return "the tunnel carried no traffic"
            }
            if (!child.isAlive) {
                val code = runCatching { child.exitValue() }.getOrNull()
                return "process exited ($code) ${lastLine.take(160)}".trim()
            }
            if (SystemClock.elapsedRealtime() - startedAt >= CONNECT_BUDGET_MS) {
                destroyProcess()
                return "no tunnel after ${CONNECT_BUDGET_MS / 1000}s"
            }
            try {
                Thread.sleep(200L)
            } catch (_: InterruptedException) {
                break
            }
        }
        destroyProcess()
        return null
    }

    /** When the process dies, bring the engine back; it owns gateway selection. */
    private fun watch() {
        val child = process ?: return
        Thread {
            runCatching { child.waitFor() }
            synchronized(lock) {
                if (stopping || process !== child) return@synchronized
                AppState.log("engine $id tunnel process exited, restarting")
                connected = false
                emit(1002, "reconnecting", "")
                val error = dial()
                if (!stopping && error != null) {
                    AppState.log("engine $id fallback failed: $error")
                    emit(2106, "reconnect failed", error)
                }
            }
        }.apply { isDaemon = true; name = "fluxcore-watch" }.start()
    }

    override fun disconnect() {
        stopping = true
        connected = false
        stopVpn()
        synchronized(lock) {
            destroyProcess()
        }
    }

    override fun startVpn(fd: Int): String? {
        val config = writeTunnelConfig()
        val started = try {
            Tun2Socks.TProxyStartService(config.absolutePath, fd)
        } catch (t: Throwable) {
            AppState.log("engine $id tunnel bridge: ${t.message ?: t.javaClass.simpleName}")
            false
        }
        if (!started) return Texts.tunnelFailed
        vpnStarted = true
        repeat(15) {
            Thread.sleep(100L)
            if (runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)) {
                AppState.log("engine $id tunnel bridge started")
                return null
            }
        }
        stopVpn()
        return Texts.tunnelFailed
    }

    override fun stopVpn() {
        if (!vpnStarted) return
        vpnStarted = false
        runCatching { Tun2Socks.TProxyStopService() }
            .onFailure { AppState.log("engine $id tunnel stop: ${it.message}") }
    }

    /**
     * The engine notices a dead handshake on its own, but a network change is
     * faster to recover from by starting a fresh attempt than by waiting for
     * its monitor, so the session is redialled here.
     */
    override fun onNetworkTransition() {
        if (!connected || stopping) return
        Thread({
            synchronized(lock) {
                if (!connected || stopping) return@synchronized
                AppState.log("engine $id network changed, redialling")
                connected = false
                emit(1002, "connecting", "")
                destroyProcess()
                val error = dial()
                if (!stopping && error != null) {
                    AppState.log("engine $id reconnect failed: $error")
                    emit(2106, "reconnect failed", error)
                }
            }
        }, "fluxcore-network").apply { isDaemon = true }.start()
    }

    override fun isRunning(): Boolean =
        process?.isAlive == true ||
            (vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false))

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    private fun pumpLogs(child: Process) {
        EngineProc.attach(
            child,
            Thread {
                runCatching {
                    // Not `use`d: EngineProc closes the pipe, once the child is
                    // dead and can no longer write into it.
                    val reader = child.inputStream.bufferedReader()
                    while (true) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        val text = line.trim()
                        lastLine = text
                        when {
                            text.equals(CONNECTED_MARKER, ignoreCase = true) -> {
                                tunnelUp = true
                                if (!connected) {
                                    connected = true
                                    emit(1004, "ready", "")
                                }
                            }
                            // The engine reconnects a lost tunnel itself; the UI
                            // has to say so instead of staying "connected".
                            text.startsWith("tunnel lost", ignoreCase = true) -> {
                                tunnelUp = false
                                connected = false
                                emit(1002, "reconnecting", "")
                            }
                        }
                        if (text.isNotBlank()) {
                            AppState.log("engine $id: ${text.take(200)}")
                        }
                    }
                }
            }.apply { isDaemon = true; name = "fluxcore-log"; start() }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "fluxcore")
        lastLine = ""
        tunnelUp = false
    }

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    private fun writeTunnelConfig(): File {
        val file = File(workDir, TUNNEL_FILE)
        runCatching {
            file.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 172.19.0.2
                  ipv6: 'fd00::2'
                  icmp: 'reply'
                socks5:
                  address: $LOOPBACK
                  port: ${Prefs.socksPort}
                  udp: 'udp'
                mapdns:
                  address: $MAPPED_DNS
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000
                misc:
                  task-stack-size: 32768
                  log-level: warn
                """.trimIndent()
            )
        }.onFailure { AppState.log("engine $id tunnel config: ${it.message}") }
        return file
    }

    private val appContext: Context
        get() = context ?: error("FluxcoreCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "fluxcore")

    private val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libfluxcore.so")

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TUNNEL_FILE = "tunnel.yml"
        const val CONNECTED_MARKER = "connected"
        const val CONNECT_BUDGET_MS = 120_000L
    }
}
