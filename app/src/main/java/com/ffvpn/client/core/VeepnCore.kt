package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File

/**
 * The VeePN engine: the VeePN CLI cross-built for Android, run as a child
 * process in zero-privilege proxy mode. It fetches the official app's free
 * subscription, parses the ss/vmess/vless/trojan links, pings the servers,
 * brings up one of them with sing-box behind a loopback SOCKS5 + HTTP proxy and
 * keeps failing over while it runs. The SOCKS listener is bridged to the TUN by
 * Tun2Socks, exactly like the other executable cores.
 *
 * The engine keeps its config, subscription cache and state file under
 * `$VEEPN_HOME`, so it is started with that pointed at the app's filesDir: the
 * CLI would otherwise fall back to a HOME Android never sets.
 */
class VeepnCore : VpnCore {
    override val id = "veepn"
    override val proxyAddress get() = Prefs.socksAddress

    /** sing-box resolves on its own side; the bridge answers DNS from its mapped cache. */
    override val vpnDnsServers: List<String> = listOf(MAPPED_DNS)

    /** The CLI only prints "connected" after real traffic passed, so probes are warm. */
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

    /** Set once the engine prints its "connected to" line: the tunnel is up. */
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
            val pinned = ServerChoice.pinnedVeepn()
            if (pinned != null) {
                val label = Prefs.serverLabel("veepn").ifBlank { pinned }
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
        val command = buildList {
            add(binary.absolutePath)
            add("connect")
            // The picker pins one server by its endpoint; without it the engine
            // pings every server and starts with the fastest that answers.
            ServerChoice.pinnedVeepn()?.let { add(it) }
            add("--socks")
            add(Prefs.socksPort.toString())
            add("--http")
            add(Prefs.httpPort.toString())
        }
        val child = try {
            ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["VEEPN_HOME"] = workDir.absolutePath
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
                // The CLI prints its marker only after a real request passed
                // through the SOCKS port, so the tunnel is already proven. A
                // second, immediate probe here would ask YouTube before a free
                // exit has settled and kill a working process when that one
                // request times out; the app's reachability check runs after
                // connect() returns and allows the exit time to warm up.
                connected = true
                AppState.log(
                    "engine $id tunnel up (${SystemClock.elapsedRealtime() - startedAt}ms)"
                )
                emit(1004, "ready", "")
                watch()
                return null
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

    /** When the process dies, bring the engine back; it owns server selection. */
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
        }.apply { isDaemon = true; name = "veepn-watch" }.start()
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
     * The engine notices a dead tunnel on its own, but a network change is
     * faster to recover from by starting a fresh attempt than by waiting for
     * its watchdog, so the session is redialled here.
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
        }, "veepn-network").apply { isDaemon = true }.start()
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
                            text.startsWith(CONNECTED_MARKER, ignoreCase = true) ||
                                text.startsWith(RECONNECTED_MARKER, ignoreCase = true) -> {
                                // Only record the marker: dial() owns the
                                // "ready" announcement, once the process is
                                // known to be alive and selected.
                                tunnelUp = true
                            }
                            text.startsWith("stopping", ignoreCase = true) -> {
                                tunnelUp = false
                                connected = false
                            }
                        }
                        if (text.isNotBlank()) {
                            AppState.log("engine $id: ${text.take(200)}")
                        }
                    }
                }
            }.apply { isDaemon = true; name = "veepn-log"; start() }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "veepn")
        lastLine = ""
        tunnelUp = false
        connected = false
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
        get() = context ?: error("VeepnCore.init() was not called")

    private val workDir: File
        get() = VeepnServers.workDir(appContext)

    private val binary: File
        get() = VeepnServers.binary(appContext)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TUNNEL_FILE = "tunnel.yml"
        const val CONNECTED_MARKER = "connected to"
        const val RECONNECTED_MARKER = "reconnected to"
        // A first run also downloads the subscription and pings every server.
        const val CONNECT_BUDGET_MS = 180_000L
    }
}
