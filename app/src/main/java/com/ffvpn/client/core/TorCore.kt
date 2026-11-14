package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.ffvpn.client.service.TunnelWatchdog
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File

/**
 * Tor, run as a child process the same way every other engine here runs: the
 * bundled `libtorrunner.so` loads `libtor.so` from its own directory and calls
 * `tor_main()`, so the tunnel is a real process with pipes that SIGTERM can
 * stop cleanly. Tor is the fallback of last resort — it is the slowest core and
 * the only one whose entry points are public, which is also why it needs the
 * obfs4 bridges of [TorBridges] on a network that blocks it outright.
 *
 * Bridges are the whole point of this core: without them the first thing Tor
 * does is connect to a directory relay whose address is in the public consensus,
 * and a censor that wants Tor down simply blocks those. With `UseBridges 1` the
 * first hop is an obfs4 listener that looks like TLS, and the pluggable
 * transport (`liblyrebird.so`) is spawned by Tor itself over the managed-proxy
 * protocol, so nothing here has to speak obfs4.
 */
class TorCore : VpnCore {
    override val id = "tor"
    override val proxyAddress get() = Prefs.socksAddress

    /** Tor resolves the names itself, over the circuit; the bridge answers DNS locally. */
    override val vpnDnsServers: List<String> = listOf(MAPPED_DNS)

    // Tor picks and opens a circuit when the first CONNECT arrives, so the very
    // first probe has to be patient — over a fresh bridge it can take far longer
    // than the handshake of any of the tunnel-UP-front cores here.
    override val trafficProbeTimeoutMs: Int = 20_000

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

    @Volatile
    private var bootstrapped = false

    @Volatile
    private var percent = 0

    @Volatile
    private var lastLine = ""

    /**
     * Bridges already handed to Tor in this session that carried nothing. A
     * retry on a filtered link has to reach different bridges; handing Tor the
     * same blocked lines again only repeats the same failure.
     */
    private val rejectedBridges = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Addresses of the bridges in the last written torrc, so they can be rejected. */
    @Volatile
    private var usedBridges: List<String> = emptyList()

    private val lock = Any()

    override fun init(context: Context) {
        this.context = context.applicationContext
        workDir.mkdirs()
        AppState.log(
            "engine $id binary=${binary.exists()} transport=${transport.exists()} " +
                "bridges=${TorBridges.all(context.applicationContext).size}"
        )
    }

    override fun setSocketProtector(protector: ((Int) -> Boolean)?) = Unit

    override fun setStatusListener(listener: ((Int, String, String) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(): String? {
        if (!binary.exists() || !transport.exists()) return Texts.engineMissing
        synchronized(lock) {
            workDir.mkdirs()
            stopVpn()
            stopping = false
            connected = false
            emit(1002, "connecting", "")

            if (Prefs.torUseBridges) {
                if (TorBridges.all(appContext).isEmpty()) {
                    emit(1002, "checking bridges", "")
                    AppState.log("engine $id bridge mode has no bridges yet, scanning now")
                    TorBridges.scan(appContext, stop = { stopping }) { AppState.log(it) }
                    if (stopping) return null
                }
                if (TorBridges.all(appContext).isEmpty()) {
                    return Texts.torNoBridges
                }
            }
            val error = dial()
            if (error != null && Prefs.torUseBridges && !stopping) {
                // The bridges this attempt used are not offered again in this
                // session, so the retry genuinely reaches the next ones.
                val used = usedBridges
                if (used.isNotEmpty()) {
                    rejectedBridges.addAll(used)
                    AppState.log(
                        "engine $id ${used.size} bridge(s) carried nothing, moving to the next ones"
                    )
                }
                if (TorBridges.torrcLines(appContext, rejectedBridges).isEmpty()) {
                    AppState.log("engine $id every known bridge was tried, starting the list over")
                    rejectedBridges.clear()
                }
                // The stored bridges may all be blocked now; a fresh list is the
                // only retry that can possibly change the outcome.
                AppState.log("engine $id failed with the stored bridges, refreshing the list")
                emit(1002, "checking bridges", "")
                TorBridges.scan(appContext, stop = { stopping }) { AppState.log(it) }
                if (!stopping && TorBridges.all(appContext).isNotEmpty()) {
                    if (TorBridges.torrcLines(appContext, rejectedBridges).isEmpty()) {
                        // The fresh list only names bridges already tried; with
                        // nothing left to skip there is no point skipping.
                        AppState.log("engine $id the fresh list repeats tried bridges, starting over")
                        rejectedBridges.clear()
                    }
                    val second = dial()
                    if (second == null) return null
                    return withBridgeHint(second)
                }
            }
            return if (error == null) null else withBridgeHint(error)
        }
    }

    /**
     * A core that cannot reach the network without bridges must say so: the
     * failure looks identical to a dead relay, and without this the user has no
     * reason to open Settings and turn the switch on.
     */
    private fun withBridgeHint(error: String): String =
        if (Prefs.torUseBridges) error else "$error — ${Texts.torEnableBridges}"

    /** Writes the torrc and runs one Tor process until it bootstraps or dies. */
    private fun dial(): String? {
        val startedAt = SystemClock.elapsedRealtime()
        dataDir.mkdirs()
        val config = writeTorrc()
        val child = try {
            ProcessBuilder(
                runner.absolutePath, "--hush", "-f", config.absolutePath
            ).directory(dataDir).apply {
                // Tor reports everything on stderr; without the merge the bootstrap
                // lines never reach the pump and the pipe eventually fills up.
                redirectErrorStream(true)
                environment()["HOME"] = appContext.filesDir.absolutePath
                CaBundle.applyTo(this)
            }.start()
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }
        process = child
        pumpLogs(child)
        bootstrapped = false
        percent = 0
        while (!stopping) {
            if (bootstrapped) {
                AppState.log("engine $id bootstrapped, checking traffic")
                if (TunnelWatchdog.servesTraffic(Prefs.socksPort, trafficProbeTimeoutMs)) {
                    connected = true
                    rejectedBridges.clear()
                    AppState.log("engine $id tunnel up (${SystemClock.elapsedRealtime() - startedAt}ms)")
                    emit(1004, "ready", "")
                    watch()
                    return null
                }
                destroyProcess()
                return "the circuit carried no traffic"
            }
            if (!child.isAlive) {
                val code = runCatching { child.exitValue() }.getOrNull()
                return "process exited ($code) ${lastLine.take(160)}".trim()
            }
            if (SystemClock.elapsedRealtime() - startedAt >= BOOTSTRAP_BUDGET_MS) {
                // Read the progress before teardown: shutdown clears it.
                val stalled = percent
                destroyProcess()
                return "bootstrap stopped at $stalled% after ${BOOTSTRAP_BUDGET_MS / 1000}s"
            }
            try {
                Thread.sleep(250L)
            } catch (_: InterruptedException) {
                break
            }
        }
        destroyProcess()
        return null
    }

    /** When a live tunnel dies, bring Tor back up; its circuits are not ours to pick. */
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
        }.apply { isDaemon = true; name = "tor-watch" }.start()
    }

    override fun disconnect() {
        stopping = true
        connected = false
        rejectedBridges.clear()
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
        }, "tor-network").apply { isDaemon = true }.start()
    }

    override fun isRunning(): Boolean =
        process?.isAlive == true ||
            (vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false))

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    /**
     * The torrc is rewritten on every dial because the bridge list and the
     * SOCKS port are both user-editable, and Tor only reads them at startup.
     */
    private fun writeTorrc(): File {
        val lines = mutableListOf(
            "DataDirectory ${dataDir.absolutePath}",
            // Tor must stay in the foreground: RunAsDaemon would fork and exit
            // the parent, which strands the child and loses the pipes.
            "RunAsDaemon 0",
            "SocksPort $LOOPBACK:${Prefs.socksPort}",
            "AvoidDiskWrites 1",
            "DormantCanceledByStartup 1",
            "DisableNetwork 0",
            "Log notice stderr",
            // Safety net: if the app dies without a clean disconnect, Tor stops
            // itself instead of leaving a circuit up with nobody to own it.
            "__OwningControllerProcess ${android.os.Process.myPid()}"
        )
        if (Prefs.torUseBridges) {
            val bridges = TorBridges.best(appContext, rejectedBridges)
            usedBridges = bridges.map { it.address }
            val obfs4 = bridges.any { it.usesObfs4 }
            lines += "UseBridges 1"
            if (obfs4) {
                lines += "ClientTransportPlugin obfs4 exec ${transport.absolutePath}"
            }
            bridges.forEach { lines += "Bridge ${it.line}" }
            // Bridge mode is all-or-nothing for the user: the torrc and the log
            // have to agree on what Tor was handed, or "my bridge is ignored"
            // cannot be told apart from a bridge that is simply blocked.
            AppState.log(
                "engine $id torrc: UseBridges 1 with ${bridges.size} bridge(s), " +
                    "obfs4 transport=$obfs4"
            )
        }
        val file = File(workDir, TORRC_FILE)
        runCatching { file.writeText(lines.joinToString("\n")) }
            .onFailure { AppState.log("engine $id torrc: ${it.message}") }
        return file
    }

    private fun pumpLogs(child: Process) {
        EngineProc.attach(
            child,
            Thread {
                runCatching {
                    // Not `use`d: EngineProc closes the pipe, and only once the
                    // child is provably gone.
                    val reader = child.inputStream.bufferedReader()
                    while (true) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        lastLine = line.trim()
                        BOOTSTRAP.find(lastLine)?.groupValues?.get(1)?.toIntOrNull()?.let {
                            if (it != percent) {
                                percent = it
                                emit(1002, "bootstrapping $it", "")
                            }
                        }
                        if (lastLine.contains("Bootstrapped 100", ignoreCase = true)) {
                            bootstrapped = true
                        }
                        if (lastLine.isNotBlank()) {
                            AppState.log("engine $id: ${lastLine.removePrefix("[notice] ").take(200)}")
                        }
                    }
                }
            }.apply { isDaemon = true; name = "tor-log"; start() }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "tor")
        lastLine = ""
        bootstrapped = false
        percent = 0
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
        get() = context ?: error("TorCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "tor")

    private val dataDir: File
        get() = File(workDir, "data")

    private val runner: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libtorrunner.so")

    private val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libtor.so")

    private val transport: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "liblyrebird.so")

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TORRC_FILE = "torrc"
        const val TUNNEL_FILE = "tunnel.yml"
        const val BOOTSTRAP_BUDGET_MS = 180_000L
        val BOOTSTRAP = Regex("Bootstrapped (\\d+)%")
    }
}
