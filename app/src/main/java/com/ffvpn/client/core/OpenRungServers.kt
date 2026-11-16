package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray

/** One relay of the signed directory the OpenRung broker publishes. */
data class OpenRungRelay(
    val id: String,
    val label: String,
    val address: String,
    val protocol: String,
    val country: String,
    val city: String,
    val reachable: Boolean?
)

/** A relay plus how long it took us to reach it. */
data class OpenRungCandidate(
    val relay: OpenRungRelay,
    val latencyMs: Int
)

/**
 * Prepares the OpenRung relay list the way the reference client does: ask the
 * engine itself (so the list comes from the same signed control plane that will
 * serve the traffic), cache it, then measure which relay answers fastest so the
 * core can dial the best one first. The engine's `relays` subcommand prints the
 * usable directory in its own ranked order, each with a TCP probe latency.
 */
object OpenRungServers {
    private const val MARKER = "OPENRUNG_RELAYS_JSON "
    private const val CACHE_FILE = "openrung_relays.json"
    private const val LIST_TIMEOUT_MS = 150_000L
    private const val TCP_TIMEOUT_MS = 5_000
    private const val CONCURRENCY = 8

    fun workDir(context: Context): File = File(context.applicationContext.filesDir, "openrung")

    fun binary(context: Context): File =
        File(context.applicationContext.applicationInfo.nativeLibraryDir, "libopenrung.so")

    fun cacheFile(workDir: File): File = File(workDir, CACHE_FILE)

    /**
     * The engine keeps its installation key and proxy state under the user config
     * dir, and writes its sing-box config to a temp file. On Android neither the
     * home nor /tmp is writable, so point all three at the core's own work dir.
     */
    fun applyEnv(builder: ProcessBuilder, workDir: File) {
        val env = builder.environment()
        env["XDG_CONFIG_HOME"] = workDir.absolutePath
        env["HOME"] = workDir.absolutePath
        env["TMPDIR"] = workDir.absolutePath
    }

    /** Runs the `relays` subcommand once and returns the parsed directory. */
    fun fetch(
        binary: File,
        workDir: File,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<OpenRungRelay> {
        workDir.mkdirs()
        val child = try {
            ProcessBuilder(binary.absolutePath, "relays")
                .directory(workDir)
                .redirectErrorStream(true)
                .also { CaBundle.applyTo(it); applyEnv(it, workDir) }
                .start()
        } catch (t: Throwable) {
            log("engine openrung list failed to start: ${t.message ?: t.javaClass.simpleName}")
            return readCache(workDir)
        }
        val startedAt = SystemClock.elapsedRealtime()
        var payload: String? = null
        val reader = child.inputStream.bufferedReader()
        while (payload == null && !stop()) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            val index = line.indexOf(MARKER)
            if (index >= 0) {
                payload = line.substring(index + MARKER.length)
            }
            if (SystemClock.elapsedRealtime() - startedAt > LIST_TIMEOUT_MS) break
        }
        EngineProc.stopOnce(child, log, "openrung list")
        val list = parse(payload)
        if (list.isEmpty()) {
            log("engine openrung produced no relay list, using cache")
            return readCache(workDir)
        }
        writeCache(workDir, payload!!)
        log("engine openrung listed ${list.size} relay(s)")
        return list
    }

    private fun parse(payload: String?): List<OpenRungRelay> {
        if (payload.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val relay = item.optJSONObject("Relay") ?: continue
                val id = relay.optString("id")
                if (id.isBlank()) continue
                val host = relay.optString("public_host")
                val port = relay.optInt("public_port")
                // ProbeMS is null for relays past the engine's probed head; a
                // number means the engine's own TCP probe reached it.
                val probed = if (item.isNull("ProbeMS")) null else item.optLong("ProbeMS", -1L)
                add(
                    OpenRungRelay(
                        id = id,
                        label = relay.optString("label"),
                        address = if (host.isBlank()) "" else "$host:$port",
                        protocol = relay.optString("protocol"),
                        country = relay.optString("country_code").ifBlank { relay.optString("country") },
                        city = relay.optString("city"),
                        reachable = probed?.let { it >= 0 }
                    )
                )
            }
        }
    }

    private fun writeCache(workDir: File, payload: String) {
        runCatching { cacheFile(workDir).writeText(payload) }
    }

    fun readCache(workDir: File): List<OpenRungRelay> =
        runCatching { parse(cacheFile(workDir).readText()) }.getOrDefault(emptyList())

    /** Times a plain TCP connect to each relay address, in parallel. */
    fun probe(
        relays: List<OpenRungRelay>,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<OpenRungCandidate> {
        if (relays.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(CONCURRENCY.coerceAtMost(relays.size))
        val results = try {
            relays.map { relay ->
                pool.submit(Callable { OpenRungCandidate(relay, probeAddress(relay.address, stop)) })
            }.map { future ->
                if (stop()) {
                    future.cancel(true)
                    null
                } else {
                    runCatching { future.get() }.getOrNull()
                }
            }
        } finally {
            pool.shutdownNow()
        }
        val ranked = results.filterNotNull()
        log(
            "engine openrung probed ${ranked.size}/${relays.size} relay(s): " +
                ranked.count { it.latencyMs >= 0 } + " reachable"
        )
        return ranked
    }

    private fun probeAddress(address: String, stop: () -> Boolean): Int {
        val host = address.substringBeforeLast(':').removeSurrounding("[", "]")
        val port = address.substringAfterLast(':').toIntOrNull() ?: return -1
        if (host.isBlank() || stop()) return -1
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
                (SystemClock.elapsedRealtime() - startedAt).toInt()
            }
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Engine-verified relays first, then unreachable ones; inside each group the
     * lowest measured TCP latency wins, and ties break on the id so the order
     * stays stable between attempts.
     */
    fun rank(candidates: List<OpenRungCandidate>): List<OpenRungCandidate> =
        candidates.sortedWith(
            compareBy(
                { if (it.relay.reachable == false) 1 else 0 },
                { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs },
                { it.relay.id }
            )
        )
}