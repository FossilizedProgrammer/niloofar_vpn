package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.ffvpn.client.service.TunnelWatchdog
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File

/**
 * The Opera engine: opera-proxy-cli cross-built for Android, run as a child
 * process in zero-privilege proxy mode. The CLI registers an anonymous
 * SurfEasy device, ranks the published endpoint feed, masks the SNI, opens the
 * tunnel and serves a loopback HTTP + SOCKS5 proxy, keeping the connection and
 * failing over between plans while it runs. Its SOCKS listener is bridged to
 * the TUN by Tun2Socks, exactly like the other executable cores.
 *
 * The CLI keeps its endpoint cache, SNI learning and saved session in one state
 * file, passed explicitly with `-state`: the default is a path under
 * XDG_STATE_HOME or the working directory, neither of which is reliable here.
 * Both listener addresses are passed explicitly too, because the CLI otherwise
 * yields to a free random port and the bridge needs exactly the SOCKS port.
 */
class OperaCore : VpnCore {
    override val id = "opera"
    override val proxyAddress get() = Prefs.socksAddress

    /** The engine resolves on its own side; the bridge answers DNS from its mapped cache. */
    override val vpnDnsServers: List<String> = listOf(MAPPED_DNS)

    /** The CLI prints "ready" only after a real request passed through the proxy. */
    override val trafficProbeTimeoutMs: Int = 10_000

    /** The free SurfEasy exits answer YouTube with 403, so ipify stands in. */
    override val probeHost: String = "api.ipify.org"

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

    /** Set once the CLI prints its "ready" line: the local proxy carries traffic. */
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
            val pinned = ServerChoice.pinnedOpera()
            if (pinned != null) {
                val label = Prefs.serverLabel("opera").ifBlank { pinned }
                AppState.log("engine $id dialing selected region $pinned ($label)")
                emit(1002, "dialing $label", "")
            } else {
                emit(1002, "connecting", "")
            }
            return dial()
        }
    }

    /** Starts the CLI and returns null once its proxy carries traffic. */
    private fun dial(): String? {
        destroyProcess()
        tunnelUp = false
        val startedAt = SystemClock.elapsedRealtime()
        val command = buildList {
            add(binary.absolutePath)
            add("-country")
            // The picker pins one region; without it the CLI walks its own
            // fallback sequence (EU, then AM) and remembers what worked.
            add(ServerChoice.pinnedOpera() ?: "auto")
            add("-http-bind")
            add(Prefs.httpAddress)
            add("-socks-bind")
            add(Prefs.socksAddress)
            add("-state")
            add(OperaServers.stateFile(workDir).absolutePath)
        }
        val child = try {
            ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = workDir.absolutePath
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
                if (TunnelWatchdog.servesTraffic(Prefs.socksPort, trafficProbeTimeoutMs, probeHost)) {
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

    /**
     * The CLI recovers a lost data plane itself, inside the same process, so
     * only a dead process is restarted here — that is what its exhausted
     * fallback cycles look like from outside.
     */
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
        }.apply { isDaemon = true; name = "opera-watch" }.start()
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
     * A network change breaks the CLI's tunnel, which it notices through its
     * own probe only after a few seconds of failures; a fresh attempt is
     * faster, so the session is redialled here.
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
        }, "opera-network").apply { isDaemon = true }.start()
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
                        // The CLI prefixes every line with its logger's date and
                        // "MAIN    :", so the marker is never at the start.
                        if (READY_MARKERS.any { text.contains(it, ignoreCase = true) }) {
                            // dial() owns the "ready" announcement, once its
                            // own traffic probe has passed.
                            tunnelUp = true
                        }
                        if (text.isNotBlank()) {
                            AppState.log("engine $id: ${text.take(200)}")
                        }
                    }
                }
            }.apply { isDaemon = true; name = "opera-log"; start() }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "opera")
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
        get() = context ?: error("OperaCore.init() was not called")

    private val workDir: File
        get() = OperaServers.workDir(appContext)

    private val binary: File
        get() = OperaServers.binary(appContext)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TUNNEL_FILE = "tunnel.yml"

        /**
         * The CLI says the proxy is serving only after its own HTTP probe came
         * back through it: on a fresh bootstrap, and again when the saved
         * session is re-opened with no API call at all.
         */
        val READY_MARKERS = listOf(
            "Built-in Opera proxy ready on",
            "Saved session is alive on"
        )

        // A first run registers the device, fetches the endpoint feed and walks
        // through several plans before one of them answers a probe.
        const val CONNECT_BUDGET_MS = 240_000L
    }
}
