package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Relays that are usually good to medium speed. The bundled tunnel process
 * speaks SSTP in userspace and serves a local SOCKS5 proxy; before dialling,
 * the ranked relay list is scanned so the fastest reachable server goes first
 * and a dead one simply falls through to the next entry.
 */
class SstpCore : VpnCore {
    override val id = "sstp"
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
    private var candidates: List<SstpCandidate> = emptyList()

    private lateinit var prefs: SstpStore
    private val lock = Any()

    override fun init(context: Context) {
        this.context = context.applicationContext
        prefs = SstpStore(context.applicationContext)
        workDir.mkdirs()
        AppState.log(
            "engine $id binary=${binary.exists()} " +
                "relays=${SstpScanner.loadEndpoints(context.applicationContext).size}"
        )
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

            val endpoints = SstpScanner.loadEndpoints(appContext)
            val scanned = SstpScanner.scan(endpoints, stop = { stopping }) { AppState.log(it) }
            if (stopping) return null
            candidates = order(scanned)
            if (candidates.isEmpty()) {
                AppState.log("engine $id scan produced no candidates")
                return Texts.endpointNotFound
            }
            AppState.log("engine $id trying ${candidates.size} ranked relay(s)")
            return attemptFrom(0, TOTAL_BUDGET_MS)
        }
    }

    /** Known-good relay first, then the scan ranking (TLS-alive, then latency). */
    private fun order(scanned: List<SstpCandidate>): List<SstpCandidate> {
        val last = prefs.lastEndpoint()?.let { SstpEndpoint.parse(it) }
        if (last == null) return scanned
        val hit = scanned.firstOrNull { it.endpoint == last }
        if (hit == null) return scanned
        return listOf(hit) + scanned.filter { it.endpoint != last }
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
            AppState.log(
                "engine $id dialing ${candidate.endpoint} " +
                    "(${candidate.health.name.lowercase()}, ${candidate.latencyMs}ms) " +
                    "${index + 1}/${candidates.size}"
            )
            emit(1002, "dialing ${candidate.endpoint}", "")
            val wait = remaining.coerceAtMost(PER_SERVER_MS)
            val error = runCandidate(candidate, wait)
            if (stopping) return null
            if (error == null) {
                connected = true
                prefs.setLastEndpoint(candidate.endpoint.toString())
                AppState.log("engine $id tunnel up on ${candidate.endpoint}")
                emit(1004, "ready", "")
                watch(index)
                return null
            }
            lastError = error
            AppState.log("engine $id ${candidate.endpoint} failed: $error, next relay")
            index++
            // A relay that refuses at once is skipped only after a short breath:
            // a burst of fresh connections is what makes a whole list answer
            // with resets, and each relay deserves its own attempt.
            if (!stopping && index < candidates.size) pauseBetweenRelays()
        }
        return lastError ?: Texts.endpointNotFound
    }

    private fun pauseBetweenRelays() {
        runCatching { Thread.sleep(NEXT_RELAY_PAUSE_MS) }
    }

    /** Starts the tunnel process and returns null once its proxies are ready. */
    private fun runCandidate(candidate: SstpCandidate, timeoutMs: Long): String? {
        destroyProcess()
        serving = false
        val startedAt = SystemClock.elapsedRealtime()
        val child = try {
            ProcessBuilder(listOf(binary.absolutePath) + args(candidate.endpoint))
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

    /** When a live tunnel drops, fall through to the next ranked relay. */
    private fun watch(from: Int) {
        val child = process ?: return
        Thread {
            runCatching { child.waitFor() }
            synchronized(lock) {
                if (stopping || process !== child) return@synchronized
                AppState.log("engine $id tunnel process exited, moving to next relay")
                connected = false
                emit(1002, "reconnecting", "")
                val error = attemptFrom(from + 1, TOTAL_BUDGET_MS)
                if (!stopping && error != null) {
                    AppState.log("engine $id fallback failed: $error")
                    emit(2106, "reconnect failed", error)
                }
            }
        }.apply { isDaemon = true; name = "sstp-watch" }.start()
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
        }, "sstp-network").apply { isDaemon = true }.start()
    }

    override fun isRunning(): Boolean =
        process?.isAlive == true ||
            (vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false))

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    private fun args(endpoint: SstpEndpoint): List<String> = listOf(
        "-server", endpoint.ip,
        "-port", endpoint.port.toString(),
        "-user", PPP_USER,
        "-pass", PPP_PASS,
        "-socks", Prefs.socksAddress,
        "-http", "",
        "-dns", TUNNEL_DNS,
        "-mtu", MTU.toString()
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
                        AppState.log("engine $id: $lastLine")
                    }
                }
            }.apply { isDaemon = true; name = "sstp-log"; start() }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "sstp")
        lastLine = ""
        serving = false
    }

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    // The tunnel's local proxy is TCP-only, so DNS is answered from the
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
        get() = context ?: error("SstpCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "sstp")

    private val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libsstp.so")

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val PPP_USER = "vpn"
        const val PPP_PASS = "vpn"
        const val TUNNEL_DNS = "1.1.1.1,8.8.8.8"
        const val MTU = 1400
        const val TUNNEL_FILE = "tunnel.yml"
        const val SERVING_MARKER = "proxies ready"
        const val PER_SERVER_MS = 25_000L
        const val NEXT_RELAY_PAUSE_MS = 350L
        const val TOTAL_BUDGET_MS = 300_000L
    }
}

/** Remembers the relay that last carried traffic so the next dial starts there. */
private class SstpStore(context: Context) {
    private val prefs = context.getSharedPreferences("sstp", Context.MODE_PRIVATE)

    fun lastEndpoint(): String? = prefs.getString(KEY_LAST, null)

    fun setLastEndpoint(value: String) {
        prefs.edit().putString(KEY_LAST, value).apply()
    }

    private companion object {
        const val KEY_LAST = "last_endpoint"
    }
}
