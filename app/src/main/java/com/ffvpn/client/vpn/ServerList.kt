package com.ffvpn.client.vpn

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One free-tier exit from the proxy's own server list. */
data class Server(
    val country: String,
    val countryName: String,
    val city: String,
    val address: String
) {
    val label: String get() = if (city.isBlank()) countryName else "$countryName / $city"
}

/**
 * What a probe learned about one edge. The engine speaks TLS to the edge, so a
 * bare TCP connect proves too little on a filtered network.
 */
enum class Health { Alive, TcpOnly, Dead }

data class Candidate(
    val server: Server,
    val health: Health,
    val latencyMs: Int
)

/**
 * Prepares what the engine connects with: the free exit list is re-read from
 * Mozilla's control plane before every connect (through the engine itself, so
 * the addresses are exactly the ones it can dial), every edge is probed, and
 * only survivors are dialled.
 */
object ServerList {
    private const val TAG = "ffvpn"
    private const val MARKER = "FFVPN_SERVER_JSON "
    private const val LIST_TIMEOUT_MS = 60_000L
    private const val LOGIN_TIMEOUT_MS = 90_000L
    private const val TCP_TIMEOUT_MS = 5_000
    private const val TLS_TIMEOUT_MS = 10_000
    private const val PROBE_WAIT_MS = 18_000L
    private const val CONCURRENCY = 8

    fun hasSession(workDir: File): Boolean =
        runCatching { File(workDir, "session.txt").readText().trim().length >= 64 }.getOrDefault(false)

    /** Runs `--list-countries --free-only --json` and returns the exits it lists. */
    fun fetch(binary: File, cache: File, stop: () -> Boolean): List<Server> {
        val child = start(
            binary, cache.parentFile ?: File("/"), listOf("--list-countries", "--free-only", "--json"), CaBundle.env()
        ) ?: return readCache(cache)

        val startedAt = SystemClock.elapsedRealtime()
        val seen = LinkedHashMap<String, Server>()
        val reader = child.inputStream.bufferedReader()
        while (!stop()) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            val index = line.indexOf(MARKER)
            if (index >= 0) {
                parse(line.substring(index + MARKER.length))?.let { seen.putIfAbsent(it.address, it) }
            }
            if (SystemClock.elapsedRealtime() - startedAt > LIST_TIMEOUT_MS) break
        }
        EngineProc.stopOnce(child)
        val exits = seen.values.toList()
        if (exits.isEmpty()) return readCache(cache)
        writeCache(cache, exits)
        Log.d(TAG, "listed ${exits.size} free exit(s)")
        Logs.add("vpn", "listed ${exits.size} free exit(s)")
        return exits
    }

    private fun parse(payload: String): Server? {
        val item = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val address = item.optString("addr")
        if (!address.contains(':')) return null
        return Server(
            country = item.optString("cc"),
            countryName = item.optString("country"),
            city = item.optString("city"),
            address = address
        )
    }

    private fun writeCache(cache: File, servers: List<Server>) {
        val payload = JSONArray()
        servers.forEach {
            payload.put(
                JSONObject()
                    .put("cc", it.country)
                    .put("country", it.countryName)
                    .put("city", it.city)
                    .put("addr", it.address)
            )
        }
        runCatching { cache.writeText(payload.toString()) }
    }

    fun readCache(cache: File): List<Server> =
        runCatching { cache.readText() }.getOrDefault("").let { text ->
            runCatching { JSONArray(text) }.getOrNull()?.let { array ->
                buildList {
                    for (i in 0 until array.length()) {
                        parse(array.optJSONObject(i)?.toString() ?: continue)?.let { add(it) }
                    }
                }
            } ?: emptyList()
        }

    /** Handshakes TLS to every exit in parallel, the way the engine dials it. */
    fun probe(servers: List<Server>, stop: () -> Boolean): List<Candidate> {
        if (servers.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(CONCURRENCY.coerceAtMost(servers.size))
        return try {
            val futures = servers.map { server -> pool.submit(Callable { probeServer(server, stop) }) }
            servers.mapIndexed { index, server ->
                val future = futures[index]
                if (stop()) {
                    future.cancel(true)
                    null
                } else {
                    runCatching { future.get(PROBE_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
                        ?: Candidate(server, Health.Dead, -1)
                }
            }.filterNotNull()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probeServer(server: Server, stop: () -> Boolean): Candidate {
        val host = server.address.substringBeforeLast(':').removeSurrounding("[", "]")
        val port = server.address.substringAfterLast(':').toIntOrNull()
            ?: return Candidate(server, Health.Dead, -1)
        if (host.isBlank() || stop()) return Candidate(server, Health.Dead, -1)

        val startedAt = SystemClock.elapsedRealtime()
        var plain: Socket? = null
        var ssl: javax.net.ssl.SSLSocket? = null
        var latency = -1
        var tcpOk = false
        return try {
            plain = Socket()
            plain.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            tcpOk = true
            latency = (SystemClock.elapsedRealtime() - startedAt).toInt()
            plain.soTimeout = TLS_TIMEOUT_MS
            ssl = CaBundle.socketFactory().createSocket(plain, host, port, true) as javax.net.ssl.SSLSocket
            ssl.startHandshake()
            Candidate(server, Health.Alive, latency)
        } catch (_: Throwable) {
            Candidate(server, if (tcpOk) Health.TcpOnly else Health.Dead, latency)
        } finally {
            runCatching { ssl?.close() }
            runCatching { plain?.close() }
        }
    }

    /** TLS-capable exits first, then by latency; the last good address wins a tie. */
    fun rank(candidates: List<Candidate>, lastAddress: String?): List<Candidate> =
        candidates.sortedWith(
            compareBy(
                { it.health.ordinal },
                { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs },
                { if (it.server.address == lastAddress) 0 else 1 }
            )
        )

    /**
     * Validates a token lifted from the in-app browser, lets the engine cache
     * it, and reads the account's quota. Same output loop as [signIn].
     */
    fun verifyToken(
        binary: File,
        workDir: File,
        token: String,
        onLine: (String) -> Unit
    ): String? {
        val child = start(
            binary,
            workDir,
            listOf(
                "--session-token", token,
                "--session-token-file", File(workDir, "session.txt").absolutePath,
                "--usage"
            ),
            CaBundle.env()
        ) ?: return "Sign-in could not start"

        val startedAt = SystemClock.elapsedRealtime()
        var lastMeaning = ""
        val reader = child.inputStream.bufferedReader()
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            if (line.isNotBlank()) {
                lastMeaning = line
                onLine(line)
            }
            if (SystemClock.elapsedRealtime() - startedAt > LOGIN_TIMEOUT_MS) {
                EngineProc.stopOnce(child)
                return "Sign-in timed out"
            }
        }
        EngineProc.stopOnce(child)
        val code = runCatching { child.exitValue() }.getOrNull()
        if (hasSession(workDir) && code == 0) {
            Logs.add("sign-in", "session accepted")
            return null
        }
        Logs.add("sign-in", "session rejected: ${lastMeaning.take(160)}")
        return lastMeaning.take(160).ifBlank { "Sign-in failed" }
    }

    private fun start(
        binary: File,
        workDir: File,
        args: List<String>,
        env: Map<String, String>?
    ): Process? = try {
        ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(true)
            .also { builder -> env?.forEach { (key, value) -> builder.environment()[key] = value } }
            .start()
    } catch (t: Throwable) {
        Log.w(TAG, "engine start failed: ${t.message}")
        null
    }
}
