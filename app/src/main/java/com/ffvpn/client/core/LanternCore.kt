package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Relays that are usually good to medium speed. The bundled tunnel process is
 * Lantern's radiance engine: it publishes its own server list, gets the list
 * scanned and ranked first, and then pins the fastest entry. When that entry
 * does not build a tunnel, the next one is dialled automatically.
 */
class LanternCore : VpnCore {
    override val id = "lantern"
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
    private var candidates: List<RadianceCandidate> = emptyList()

    private lateinit var prefs: LanternStore
    private val lock = Any()

    override fun init(context: Context) {
        this.context = context.applicationContext
        prefs = LanternStore(context.applicationContext)
        workDir.mkdirs()
        LanternServers.ensureDeviceId(workDir, prefs.deviceId()) { AppState.log(it) }
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
            LanternServers.ensureDeviceId(workDir, prefs.deviceId()) { AppState.log(it) }
            stopVpn()
            stopping = false
            connected = false
            emit(1002, "connecting", "")

            val listed = LanternServers.fetch(binary, workDir, stop = { stopping }) { AppState.log(it) }
            if (stopping) return null
            if (listed.isEmpty()) {
                AppState.log("engine $id has no server list")
                return Texts.endpointNotFound
            }
            val probed = LanternServers.probe(listed, stop = { stopping }) { AppState.log(it) }
            if (stopping) return null
            candidates = order(LanternServers.rank(probed))
            AppState.log("engine $id trying ${candidates.size} ranked server(s)")
            return attemptFrom(0, TOTAL_BUDGET_MS)
        }
    }

    /** The server that last carried traffic goes first, then the ranking. */
    private fun order(ranked: List<RadianceCandidate>): List<RadianceCandidate> {
        val last = prefs.lastServer()
        if (last == null) return ranked
        val hit = ranked.firstOrNull { it.server.tag == last } ?: return ranked
        return listOf(hit) + ranked.filter { it.server.tag != last }
    }

    private fun attemptFrom(from: Int, budgetMs: Long): String? {
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        var index = from
        var lastError: String? = null
        while (!stopping) {
            val candidate = candidates.getOrNull(index)
                ?: break
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                lastError = "candidate budget exhausted"
                break
            }
            val server = candidate.server
            AppState.log(
                "engine $id dialing ${server.tag} [${server.protocol} ${server.country}] " +
                    "(${candidate.latencyMs}ms) ${index + 1}/${candidates.size}"
            )
            emit(1002, "dialing ${server.protocol} ${server.country}", "")
            val wait = remaining.coerceAtMost(PER_SERVER_MS)
            val error = runCandidate(server, wait)
            if (stopping) return null
            if (error == null) {
                connected = true
                prefs.setLastServer(server.tag)
                AppState.log("engine $id tunnel up on ${server.tag}")
                emit(1004, "ready", "")
                watch(index)
                return null
            }
            lastError = error
            AppState.log("engine $id ${server.tag} failed: $error, next server")
            index++
        }
        return lastError ?: Texts.endpointNotFound
    }

    /** Starts the engine and returns null once it reports its local proxy. */
    private fun runCandidate(server: RadianceServer, timeoutMs: Long): String? {
        destroyProcess()
        serving = false
        val startedAt = SystemClock.elapsedRealtime()
        val child = try {
            ProcessBuilder(listOf(binary.absolutePath) + args(server))
                .directory(workDir)
                .redirectErrorStream(true)
                .also { CaBundle.applyTo(it) }
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

    /** When a live tunnel drops, fall through to the next ranked server. */
    private fun watch(from: Int) {
        val child = process ?: return
        Thread {
            runCatching { child.waitFor() }
            synchronized(lock) {
                if (stopping || process !== child) return@synchronized
                AppState.log("engine $id tunnel process exited, moving to next server")
                connected = false
                emit(1002, "reconnecting", "")
                val error = attemptFrom(from + 1, TOTAL_BUDGET_MS)
                if (!stopping && error != null) {
                    AppState.log("engine $id fallback failed: $error")
                    emit(2106, "reconnect failed", error)
                }
            }
        }.apply { isDaemon = true; name = "lantern-watch" }.start()
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
        }, "lantern-network").apply { isDaemon = true }.start()
    }

    override fun isRunning(): Boolean =
        process?.isAlive == true ||
            (vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false))

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    private fun args(server: RadianceServer): List<String> = listOf(
        "--data-path", workDir.absolutePath,
        "--socks-addr", Prefs.socksAddress,
        "--server", server.tag,
        "--log-level", "info"
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
            }.apply { isDaemon = true; name = "lantern-log"; start() }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "lantern")
        lastLine = ""
        serving = false
    }

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    // The engine's local proxy is TCP-only, so DNS is answered from the
    // bridge's mapped cache and resolved by the relay on CONNECT.
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
        get() = context ?: error("LanternCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "lantern")

    private val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "liblantern.so")

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TUNNEL_FILE = "tunnel.yml"
        const val SERVING_MARKER = "RADIANCE_READY"
        const val PER_SERVER_MS = 150_000L
        const val TOTAL_BUDGET_MS = 420_000L
    }
}

/** Remembers the engine's device id and the server that last carried traffic. */
private class LanternStore(context: Context) {
    private val prefs = context.getSharedPreferences("lantern", Context.MODE_PRIVATE)

    fun deviceId(): String {
        val stored = prefs.getString(KEY_DEVICE, null)
        if (stored != null) return stored
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE, created).apply()
        return created
    }

    fun lastServer(): String? = prefs.getString(KEY_LAST, null)

    fun setLastServer(value: String) {
        prefs.edit().putString(KEY_LAST, value).apply()
    }

    private companion object {
        const val KEY_DEVICE = "device_id"
        const val KEY_LAST = "last_server"
    }
}
