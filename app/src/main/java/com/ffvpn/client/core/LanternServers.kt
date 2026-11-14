package com.ffvpn.client.core

import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** One entry of the list the radiance engine publishes for itself. */
data class RadianceServer(
    val tag: String,
    val address: String,
    val protocol: String,
    val country: String,
    val reachable: Boolean?
)

/** A server plus how long it took us to reach it. */
data class RadianceCandidate(
    val server: RadianceServer,
    val latencyMs: Int
)

/**
 * Prepares the list of available servers the way the reference client does:
 * ask the engine itself (so the list always comes from the same control plane
 * that will serve the traffic), cache it, then measure which entry answers
 * fastest so the core can dial the best one first.
 */
object LanternServers {
    private const val MARKER = "RADIANCE_SERVERS_JSON "
    private const val CACHE_FILE = "lantern_servers.json"
    private const val LIST_TIMEOUT_MS = 150_000L
    private const val TCP_TIMEOUT_MS = 5_000
    private const val CONCURRENCY = 8

    fun cacheFile(workDir: File): File = File(workDir, CACHE_FILE)

    /** Runs `--list-servers --check` once and returns the parsed list. */
    fun fetch(
        binary: File,
        workDir: File,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<RadianceServer> {
        val child = try {
            ProcessBuilder(
                binary.absolutePath,
                "--data-path", workDir.absolutePath,
                "--list-servers", "--check",
                "--log-level", "warn"
            )
                .directory(workDir)
                .redirectErrorStream(true)
                .also { CaBundle.applyTo(it) }
                .start()
        } catch (t: Throwable) {
            log("engine lantern list failed to start: ${t.message ?: t.javaClass.simpleName}")
            return emptyList()
        }
        val startedAt = SystemClock.elapsedRealtime()
        var payload: String? = null
        val reader = child.inputStream.bufferedReader()
        while (payload == null && !stop()) {
            val line = runCatching { reader.readLine() }.getOrNull()
            if (line == null) break
            val index = line.indexOf(MARKER)
            if (index >= 0) {
                payload = line.substring(index + MARKER.length)
            }
            if (SystemClock.elapsedRealtime() - startedAt > LIST_TIMEOUT_MS) break
        }
        EngineProc.stopOnce(child, log, "lantern list")
        val list = parse(payload)
        if (list.isEmpty()) {
            log("engine lantern produced no server list, using cache")
            return readCache(workDir)
        }
        writeCache(workDir, payload!!)
        log("engine lantern listed ${list.size} server(s)")
        return list
    }

    private fun parse(payload: String?): List<RadianceServer> {
        if (payload.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val tag = item.optString("name")
                if (tag.isBlank()) continue
                val reachable = if (item.isNull("reachable")) null else item.optBoolean("reachable")
                add(
                    RadianceServer(
                        tag = tag,
                        address = item.optString("addr"),
                        protocol = item.optString("protocol"),
                        country = item.optString("country_code").ifBlank { item.optString("country") },
                        reachable = reachable
                    )
                )
            }
        }
    }

    private fun writeCache(workDir: File, payload: String) {
        runCatching { cacheFile(workDir).writeText(payload) }
    }

    fun readCache(workDir: File): List<RadianceServer> =
        runCatching { parse(cacheFile(workDir).readText()) }.getOrDefault(emptyList())

    /** Times a plain TCP connect to each server address, in parallel. */
    fun probe(
        servers: List<RadianceServer>,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<RadianceCandidate> {
        if (servers.isEmpty()) return emptyList()
        val next = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(CONCURRENCY.coerceAtMost(servers.size))
        val results = try {
            servers.map { server ->
                pool.submit(Callable { RadianceCandidate(server, probeAddress(server.address, stop)) })
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
            "engine lantern probed ${ranked.size}/${servers.size} server(s): " +
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
     * Engine-verified servers first, then unreachable ones; inside each group
     * the lowest measured TCP latency wins, and ties break on the tag so the
     * order stays stable between attempts.
     */
    fun rank(candidates: List<RadianceCandidate>): List<RadianceCandidate> =
        candidates.sortedWith(
            compareBy(
                { if (it.server.reachable == false) 1 else 0 },
                { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs },
                { it.server.tag }
            )
        )

    /**
     * Radiance only accepts a device id from the host app on android, and the
     * config service rejects requests without one, so seed it into the engine's
     * own settings file before the first run.
     */
    fun ensureDeviceId(workDir: File, deviceId: String, log: (String) -> Unit) {
        val file = File(workDir, "settings.json")
        val json = runCatching {
            if (file.exists()) JSONObject(file.readText()) else JSONObject()
        }.getOrDefault(JSONObject())
        if (json.optString("device_id").isNotBlank()) return
        json.put("device_id", deviceId)
        runCatching { file.writeText(json.toString()) }
            .onFailure { log("engine lantern device id seed failed: ${it.message}") }
    }
}
