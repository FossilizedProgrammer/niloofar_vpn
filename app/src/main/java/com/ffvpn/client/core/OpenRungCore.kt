package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File

/**
 * The OpenRung engine: a bundled sing-box driven by the shared connectcore
 * connection engine, run as a child process in zero-privilege proxy mode. It
 * publishes a signed relay directory, gets the list scanned and ranked first,
 * and then pins the fastest relay. When that relay does not build a tunnel, the
 * next one is dialled automatically. The local mixed SOCKS/HTTP inbound is
 * bridged to the TUN by Tun2Socks, exactly like the other executable cores.
 */
class OpenRungCore : VpnCore {
    override val id = "openrung"
    override val proxyAddress get() = Prefs.socksAddress

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
    private var serving = false

    @Volatile
    private var lastLine = ""

    @Volatile
    private var candidates: List<OpenRungCandidate> = emptyList()

    /** True while the user pinned one relay: it is redialled, never replaced. */
    @Volatile
    private var manual = false

    private lateinit var prefs: OpenRungStore
    private val lock = Any()

    override fun init(context: Context) {
        this.context = context.applicationContext
        prefs = OpenRungStore(context.applicationContext)
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
            emit(1002, "connecting", "")

            val pinned = ServerChoice.pinnedOpenRung(appContext)
            manual = pinned != null
            if (pinned != null) {
                candidates = listOf(OpenRungCandidate(pinned, 0))
                AppState.log("engine $id pinned to ${pinned.id} by the user")
                return attemptFrom(0, TOTAL_BUDGET_MS)
            }

            val listed = OpenRungServers.fetch(binary, workDir, stop = { stopping }) { AppState.log(it) }
            if (stopping) return null
            if (listed.isEmpty()) {
                AppState.log("engine $id has no relay list")
                return Texts.endpointNotFound
            }
            val probed = OpenRungServers.probe(listed, stop = { stopping }) { AppState.log(it) }
            if (stopping) return null
            candidates = order(OpenRungServers.rank(probed))
            AppState.log("engine $id trying ${candidates.size} ranked relay(s)")
            return attemptFrom(0, TOTAL_BUDGET_MS)
        }
    }

    /** The relay that last carried traffic goes first, then the ranking. */
    private fun order(ranked: List<OpenRungCandidate>): List<OpenRungCandidate> {
        val last = prefs.lastRelay()
        if (last == null) return ranked
        val hit = ranked.firstOrNull { it.relay.id == last } ?: return ranked
        return listOf(hit) + ranked.filter { it.relay.id != last }
    }

    private fun attemptFrom(from: Int, budgetMs: Long): String? {
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        var index = from
        var lastError: String? = null
        while (!stopping) {
            val candidate = candidates.getOrNull(index) ?: break
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                lastError = "candidate budget exhausted"
                break
            }
            val relay = candidate.relay
            val latencyNote = if (candidate.latencyMs > 0) " (${candidate.latencyMs}ms)" else ""
            AppState.log(
                "engine $id dialing ${relay.label} [${relay.protocol} ${relay.country}]$latencyNote " +
                    "${index + 1}/${candidates.size}"
            )
            emit(1002, "dialing ${relay.protocol} ${relay.country}", "")
            val wait = remaining.coerceAtMost(PER_SERVER_MS)
            val error = runCandidate(relay, wait)
            if (stopping) return null
            if (error == null) {
                connected = true
                prefs.setLastRelay(relay.id)
                AppState.log("engine $id tunnel up on ${relay.label}")
                emit(1004, "ready", "")
                watch(index)
                return null
            }
            lastError = error
            AppState.log("engine $id ${relay.label} failed: $error, next relay")
            index++
        }
        return lastError ?: Texts.endpointNotFound
    }

    /** Starts the engine and returns null once it reports its local proxy. */
    private fun runCandidate(relay: OpenRungRelay, timeoutMs: Long): String? {
        destroyProcess()
        serving = false
        val startedAt = SystemClock.elapsedRealtime()
        val child = try {
            ProcessBuilder(listOf(binary.absolutePath) + args(relay))
                .directory(workDir)
                .redirectErrorStream(true)
                .also { builder ->
                    CaBundle.applyTo(builder)
                    OpenRungServers.applyEnv(builder, workDir)
                    // Force the engine's loopback inbound onto the port Tun2Socks
                    // bridges to, instead of its own persisted/ephemeral port.
                    builder.environment()["OPENRUNG_PROXY_PORT"] = Prefs.socksPort.toString()
                }
                .start()
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }
        process = child
        pumpLogs(child)
        while (!stopping) {
            if (serving) return null
            if (!child.isAlive) {
                val code = runCatching { child.exitValue() }.getOrNull()
                return "process exited ($code) ${lastLine.take(140)}".trim()
            }
            if (SystemClock.elapsedRealtime() - startedAt >= timeoutMs) {
                destroyProcess()
                return "no tunnel after ${timeoutMs / 1000}s"
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

    /** When a live tunnel drops, fall through to the next ranked relay. */
    private fun watch(from: Int) {
        val child = process ?: return
        Thread {
            runCatching { child.waitFor() }
            synchronized(lock) {
                if (stopping || process !== child) return@synchronized
                AppState.log(
                    "engine $id tunnel process exited, " +
                        if (manual) "redialling the chosen relay" else "moving to next relay"
                )
                connected = false
                emit(1002, "reconnecting", "")
                val error = attemptFrom(if (manual) from else from + 1, TOTAL_BUDGET_MS)
                if (!stopping && error != null) {
                    AppState.log("engine $id fallback failed: $error")
                    emit(2106, "reconnect failed", error)
                }
            }
        }.apply { isDaemon = true; name = "openrung-watch" }.start()
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
        if (!started) {
            return Texts.tunnelFailed
        }
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
                val error = attemptFrom(0, TOTAL_BUDGET_MS)
                if (!stopping && error != null) {
                    AppState.log("engine $id reconnect failed: $error")
                    emit(2106, "reconnect failed", error)
                }
            }
        }, "openrung-network").apply { isDaemon = true }.start()
    }

    override fun isRunning(): Boolean =
        process?.isAlive == true ||
            (vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false))

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    private fun args(relay: OpenRungRelay): List<String> = listOf(
        "connect", "-headless",
        "-relay-id", relay.id
    )

    private fun pumpLogs(child: Process) {
        EngineProc.attach(
            child,
            Thread {
                runCatching {
                    // Not `use`d: the stream is closed by EngineProc, once the
                    // child is dead and can no longer write into it.
                    val reader = child.inputStream.bufferedReader()
                    while (true) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        lastLine = line.trim()
                        if (lastLine.contains(SERVING_MARKER, ignoreCase = true)) {
                            serving = true
                        }
                        AppState.log("engine $id: ${lastLine.take(200)}")
                    }
                }
            }.apply { isDaemon = true; name = "openrung-log"; start() }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "openrung")
        lastLine = ""
        serving = false
    }

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    // The engine's local proxy resolves DNS on the relay at CONNECT time, so the
    // bridge answers DNS from its mapped cache, like the other executable cores.
    override val vpnDnsServers: List<String> = listOf(MAPPED_DNS)

    private fun writeTunnelConfig(): File {
        val file = File(workDir, TUNNEL_FILE)
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
        return file
    }

    private val appContext: Context
        get() = context ?: error("OpenRungCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "openrung")

    private val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libopenrung.so")

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TUNNEL_FILE = "tunnel.yml"
        const val SERVING_MARKER = "status: connected"
        const val PER_SERVER_MS = 150_000L
        const val TOTAL_BUDGET_MS = 420_000L
    }
}

/** Remembers the relay that last carried traffic. */
private class OpenRungStore(context: Context) {
    private val prefs = context.getSharedPreferences("openrung", Context.MODE_PRIVATE)

    fun lastRelay(): String? = prefs.getString(KEY_LAST, null)

    fun setLastRelay(value: String) {
        prefs.edit().putString(KEY_LAST, value).apply()
    }

    private companion object {
        const val KEY_LAST = "last_relay"
    }
}