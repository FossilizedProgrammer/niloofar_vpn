package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.ffvpn.client.service.TunnelWatchdog
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File

/**
 * The Geph engine: the `geph5-proxy` client of the Geph5 tree cross-built for
 * Android, run as a child process in zero-privilege proxy mode. It registers a
 * free account on the first connect (one proof-of-work puzzle), picks an exit
 * by country and serves a loopback SOCKS5 + HTTP proxy. That listener is
 * bridged to the TUN by Tun2Socks, exactly like the other executable cores.
 *
 * The engine's account file and SQLite cache are passed explicitly: the `dirs`
 * crate finds no config or cache directory on Android, so its defaults would
 * fail. It is started with HOME pointed at the app's filesDir for the same
 * reason.
 */
class GephCore : VpnCore {
    override val id = "geph"
    override val proxyAddress get() = Prefs.socksAddress

    /** The engine resolves on its own side; the bridge answers DNS from its mapped cache. */
    override val vpnDnsServers: List<String> = listOf(MAPPED_DNS)

    /** The engine prints its "connected" line after the exit is authenticated. */
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

    /** Set once the engine prints its "connected" line: the exit is up. */
    @Volatile
    private var tunnelUp = false

    @Volatile
    private var lastLine = ""

    private val lock = Any()

    override fun init(context: Context) {
        this.context = context.applicationContext
        workDir.mkdirs()
        AppState.log("engine $id binary=${binary.exists()} account=${accountFile.isFile}")
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
            val pinned = ServerChoice.pinnedGeph()
            if (pinned != null) {
                val label = Prefs.serverLabel("geph").ifBlank { pinned }
                AppState.log("engine $id dialing selected $pinned ($label)")
                emit(1002, "dialing $label", "")
            } else {
                emit(1002, "connecting", "")
            }
            return signUpIfNeeded() ?: dial()
        }
    }

    /**
     * A free Geph account is created in place on the first connect: `signup`
     * solves the broker's proof-of-work puzzle and writes the code to the
     * account file that `run` then reads. It never prompts, because the file is
     * always given explicitly.
     */
    private fun signUpIfNeeded(): String? {
        if (accountFile.isFile && runCatching { accountFile.readText() }.getOrDefault("").isNotBlank()) {
            return null
        }
        AppState.log("engine $id has no account yet, signing up")
        emit(1002, "signing up", "")
        val code = GephServers.signup(
            binary = binary,
            workDir = workDir,
            timeoutMs = SIGNUP_TIMEOUT_MS,
            stop = { stopping },
            log = { AppState.log("engine $id: $it") }
        )
        if (stopping) return null
        if (code == null) {
            return "could not register a Geph account ${lastLine.take(160)}".trim()
        }
        AppState.log("engine $id account registered")
        return null
    }

    /** Starts the engine and returns null once its tunnel carries traffic. */
    private fun dial(): String? {
        destroyProcess()
        tunnelUp = false
        val startedAt = SystemClock.elapsedRealtime()
        val command = buildList {
            add(binary.absolutePath)
            add("--account-file")
            add(accountFile.absolutePath)
            add("run")
            // The picker pins one exit country; without it the broker answers
            // with whatever free exit is nearest.
            add("--country")
            add(ServerChoice.pinnedGeph() ?: "auto")
            add("--socks")
            add(Prefs.socksPort.toString())
            add("--http")
            add(Prefs.httpPort.toString())
            add("--cache")
            add(GephServers.cachePath(workDir).absolutePath)
        }
        val child = try {
            ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = workDir.absolutePath
                    // The engine's default level is DEBUG: a line per dial stage.
                    environment()["RUST_LOG"] = "warn"
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

    /** When the process dies, bring the engine back; it owns exit selection. */
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
        }.apply { isDaemon = true; name = "geph-watch" }.start()
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
     * The engine recovers its own sessions, but a network change is faster to
     * recover from by starting a fresh attempt, so the session is redialled.
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
        }, "geph-network").apply { isDaemon = true }.start()
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
                            CONNECTED_LINE.containsMatchIn(text) -> {
                                // dial() owns the "ready" announcement, once
                                // its own traffic probe has passed.
                                tunnelUp = true
                            }
                            text.startsWith("interrupted", ignoreCase = true) -> {
                                tunnelUp = false
                                connected = false
                            }
                        }
                        if (text.isNotBlank()) {
                            AppState.log("engine $id: ${text.take(200)}")
                        }
                    }
                }
            }.apply { isDaemon = true; name = "geph-log"; start() }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "geph")
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
        get() = context ?: error("GephCore.init() was not called")

    private val workDir: File
        get() = GephServers.workDir(appContext)

    private val binary: File
        get() = GephServers.binary(appContext)

    private val accountFile: File
        get() = GephServers.accountFile(workDir)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TUNNEL_FILE = "tunnel.yml"

        /** `connected    : NL via Amsterdam (Netherlands / sosistab3)` */
        val CONNECTED_LINE = Regex("^connected\\s*:")

        /** Creating the account costs one proof-of-work puzzle on an unknown phone. */
        const val SIGNUP_TIMEOUT_MS = 300_000L
        const val CONNECT_BUDGET_MS = 150_000L
    }
}
