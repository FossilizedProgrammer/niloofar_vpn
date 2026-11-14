package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class SstpEndpoint(val ip: String, val port: Int) {
    override fun toString(): String = "$ip:$port"

    companion object {
        fun parse(line: String): SstpEndpoint? {
            val parts = line.trim().split(":")
            if (parts.size != 2) return null
            val port = parts[1].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val ip = parts[0]
            if (ip.count { it == '.' } != 3) return null
            return SstpEndpoint(ip, port)
        }
    }
}

enum class SstpHealth { Alive, TcpOnly, Dead }

data class SstpCandidate(
    val endpoint: SstpEndpoint,
    val health: SstpHealth,
    val latencyMs: Int
)

/**
 * The relay list the SSTP core dials, when the VPN Gate screen has installed one.
 *
 * The override is a plain `ip:port` file in exactly the format of the packaged
 * asset, so the scanner reads either source with the same parser. Deleting the
 * file is what "back to the built-in list" means.
 */
object SstpRelays {
    private const val PATH = "sstp/vpngate_servers.txt"

    fun overrideFile(context: Context): File = File(context.applicationContext.filesDir, PATH)

    fun isOverridden(context: Context): Boolean =
        runCatching { overrideFile(context).canRead() && overrideFile(context).length() > 0 }
            .getOrDefault(false)

    /** Installed relays, in file order; empty while the core uses the packaged list. */
    fun installed(context: Context): List<SstpEndpoint> {
        val file = overrideFile(context)
        return runCatching {
            if (!file.canRead()) emptyList() else file.bufferedReader().useLines { lines ->
                lines.mapNotNull(SstpEndpoint::parse).toList()
            }
        }.getOrDefault(emptyList())
    }

    /** Relays the installed file names, zero when the core still uses the packaged list. */
    fun installedCount(context: Context): Int = installed(context).size

    /** Installed relays, in the order given — the core walks the file top down. */
    fun apply(context: Context, endpoints: List<SstpEndpoint>): Boolean {
        if (endpoints.isEmpty()) return false
        val target = overrideFile(context)
        return runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.bufferedWriter().use { writer ->
                endpoints.distinct().forEach { writer.append(it.toString()).append('\n') }
            }
            target.delete()
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
            runCatching { temporary.delete() }
            true
        }.onSuccess {
            AppState.log("sstp relays installed ${endpoints.distinct().size} from the VPN Gate screen")
        }.onFailure {
            AppState.log("sstp relay install failed: ${it.message}")
        }.getOrDefault(false)
    }

    fun clear(context: Context): Boolean {
        val removed = runCatching { overrideFile(context).delete() }.getOrDefault(false)
        if (removed) AppState.log("sstp relays override removed, the built-in list is used again")
        return removed
    }
}

/**
 * Ranks the relay list before connecting, the way the reference
 * client's health scan does: a timed TCP connect, then a bare TLS ClientHello
 * to learn whether that port really speaks TLS. Latency is the TCP time only,
 * so the ranking is "answers TLS first, fastest first inside each group".
 */
object SstpScanner {
    private const val TCP_TIMEOUT_MS = 5_000
    private const val FIRST_BYTE_TIMEOUT_MS = 4_000

    /**
     * A wide burst from one address is exactly what a relay or a middlebox
     * answers with a reset, so the list is walked slowly enough for each host to
     * be judged on its own answer rather than on the queue it arrived in.
     */
    private const val CONCURRENCY = 6
    private const val SCAN_BUDGET_MS = 90_000L

    /** One fast refusal is not proof a relay is gone; the second look decides. */
    private const val RETRY_PAUSE_MS = 400L

    /** Minimal TLS 1.2 ClientHello with no SNI and no cipher suites. */
    private val CLIENT_HELLO = intArrayOf(
        0x16, 0x03, 0x01, 0x00, 0x37, 0x01, 0x00, 0x00, 0x00, 0x03, 0x03,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
        0x00, 0xff, 0x01, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x04, 0x00,
        0x02, 0x00, 0x00
    ).map { it.toByte() }.toByteArray()

    @Volatile
    private var cachedEndpoints: List<SstpEndpoint>? = null

    @Volatile
    private var cachedFrom: Long = -1L

    /**
     * Relays for this dial: the list installed from the VPN Gate screen when
     * there is one, the packaged asset otherwise. The cache is keyed on the
     * override's timestamp because a freshly installed list must take effect on
     * the very next connect, not only after a restart.
     */
    fun loadEndpoints(context: Context): List<SstpEndpoint> {
        val override = SstpRelays.overrideFile(context)
        val stamp = runCatching {
            if (override.canRead()) override.lastModified() else 0L
        }.getOrDefault(0L)
        cachedEndpoints?.let { if (cachedFrom == stamp) return it }
        val loaded = reader(context).buffered().useLines { lines ->
            lines.mapNotNull(SstpEndpoint::parse).distinct().toList()
        }
        cachedEndpoints = loaded
        cachedFrom = stamp
        if (stamp != 0L) {
            AppState.log("engine sstp using ${loaded.size} relay(s) from the VPN Gate screen")
        }
        return loaded
    }

    /**
     * The installed file if it has anything in it, the packaged list otherwise.
     * Callers close whichever source was opened.
     */
    fun reader(context: Context): Reader {
        val override = SstpRelays.overrideFile(context)
        val fromFile = runCatching {
            if (override.canRead() && override.length() > 0) FileReader(override) else null
        }.getOrNull()
        if (fromFile != null) return fromFile
        return runCatching { context.applicationContext.assets.open("sstp_servers.txt") }
            .getOrNull()?.reader() ?: StringReader("")
    }

    fun scan(
        endpoints: List<SstpEndpoint>,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<SstpCandidate> {
        if (endpoints.isEmpty()) return emptyList()
        val deadline = SystemClock.elapsedRealtime() + SCAN_BUDGET_MS
        val next = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<SstpCandidate>())
        val workers = Executors.newFixedThreadPool(CONCURRENCY.coerceAtMost(endpoints.size))
        try {
            val tasks = (0 until endpoints.size).map {
                workers.submit {
                    while (true) {
                        if (stop() || SystemClock.elapsedRealtime() >= deadline) return@submit
                        val index = next.getAndIncrement()
                        if (index >= endpoints.size) return@submit
                        results += probe(endpoints[index])
                    }
                }
            }
            tasks.forEach { runCatching { it.get() } }
        } finally {
            workers.shutdownNow()
        }
        val alive = results.count { it.health == SstpHealth.Alive }
        val tcpOnly = results.count { it.health == SstpHealth.TcpOnly }
        log(
            "engine sstp scanned ${results.size}/${endpoints.size} relay(s): " +
                "$alive TLS-alive, $tcpOnly TCP-only"
        )
        return ranked(results)
    }

    fun ranked(candidates: List<SstpCandidate>): List<SstpCandidate> =
        candidates.sortedWith(
            compareBy({ it.health.ordinal }, { it.latencyMs }, { it.endpoint.toString() })
        )

    /**
     * One relay, as the VPN Gate screen tests it when the user taps a row. A
     * refused connect is looked at a second time after a short pause before the
     * relay is written off, because a reset from the path and a closed port look
     * identical from here and only one of them means the server is really gone.
     */
    fun probe(endpoint: SstpEndpoint): SstpCandidate {
        val started = SystemClock.elapsedRealtime()
        tcpProbe(endpoint, started)?.let { return it }
        runCatching { Thread.sleep(RETRY_PAUSE_MS) }
        return tcpProbe(endpoint, started)
            ?: SstpCandidate(endpoint, SstpHealth.Dead, (SystemClock.elapsedRealtime() - started).toInt())
    }

    /** Null when the TCP connect itself failed, so nothing was listening there. */
    private fun tcpProbe(endpoint: SstpEndpoint, started: Long): SstpCandidate? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(endpoint.ip, endpoint.port), TCP_TIMEOUT_MS)
            val latency = (SystemClock.elapsedRealtime() - started).toInt()
            socket.soTimeout = FIRST_BYTE_TIMEOUT_MS
            socket.getOutputStream().apply { write(CLIENT_HELLO); flush() }
            try {
                when (socket.getInputStream().read()) {
                    -1 -> SstpCandidate(endpoint, SstpHealth.TcpOnly, latency)
                    else -> SstpCandidate(endpoint, SstpHealth.Alive, latency)
                }
            } catch (e: SocketTimeoutException) {
                SstpCandidate(endpoint, SstpHealth.TcpOnly, latency)
            } catch (e: IOException) {
                // A reset after the ClientHello still proves something is listening there.
                val message = e.message.orEmpty()
                val active = message.contains("reset", true) || message.contains("connection", true)
                SstpCandidate(
                    endpoint,
                    if (active) SstpHealth.Alive else SstpHealth.Dead,
                    latency
                )
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }
}
