package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * One bridge line, exactly as it has to appear in the torrc: either
 * `obfs4 <ip>:<port> <fingerprint> cert=<base64> iat-mode=<n>` or a plain
 * `<ip>:<port> <fingerprint>`.
 *
 * The whole line is kept verbatim because Tor needs the `cert=` value and the
 * fingerprint, not just the address — dropping any part of it makes the bridge
 * unusable, so the line is parsed only to find what to probe.
 */
data class TorBridge(val line: String, val address: String, val latencyMs: Int) {
    val host: String get() = address.substringBeforeLast(':').removeSurrounding("[", "]")
    val port: Int get() = address.substringAfterLast(':').toIntOrNull() ?: 0

    /** Only an obfs4 bridge needs `ClientTransportPlugin` in the torrc. */
    val usesObfs4: Boolean get() = line.startsWith("obfs4 ")

    companion object {
        private val PREFIX = Regex("^Bridge\\s+")
        // iat-mode is optional and extra arguments are tolerated: a bridge that
        // omits it still works, and rejecting it would silently fall back to the
        // scanned list instead of the line the user pasted.
        private val OBFS4 = Regex(
            "^obfs4\\s+(\\S+)\\s+([0-9A-Fa-f]{40})\\s+cert=\\S+(?:\\s+.*)?$"
        )
        private val PLAIN = Regex("^(\\S+)\\s+([0-9A-Fa-f]{40})$")

        /** Null for anything that is not a bridge line, including comments. */
        fun parse(raw: String): TorBridge? {
            // Lists copied from the Tor site sometimes keep the "Bridge " marker;
            // torrc wants the line without it.
            val text = raw.trim().replaceFirst(PREFIX, "").trim()
            if (text.isEmpty()) return null
            val match = OBFS4.matchEntire(text) ?: PLAIN.matchEntire(text) ?: return null
            val address = match.groupValues[1]
            val port = address.substringAfterLast(':').toIntOrNull()
                ?: return null
            if (port !in 1..65535) return null
            return TorBridge(text, address, -1)
        }
    }
}

/**
 * The bridge list, gathered the way the reference scanner does it: pull the
 * community's tested-obfs4 feed, fall back to the list bundled in the APK, then
 * re-probe every candidate from this device before any of it is trusted.
 *
 * A TCP connect is all this can prove from Kotlin — the obfs4 handshake itself
 * needs the NaCl primitives that only the transport binary has — so the result
 * is "answers on this port", which is exactly what filters: a bridge that is
 * blocked shows up as dead, and one that is reachable but misconfigured still
 * gets a fair chance at handshake inside Tor.
 */
object TorBridges {
    private const val FEED =
        "https://raw.githubusercontent.com/center2055/OnionHop-Bridges-Collector/main/bridge/obfs4_tested.txt"
    private const val ASSET = "tor_obfs4_bridges.txt"
    private const val TCP_TIMEOUT_MS = 5_000
    private const val CONCURRENCY = 20
    private const val SCAN_BUDGET_MS = 90_000L

    /** Tor opens circuits over a handful of bridges; more only slows it down. */
    private const val MAX_IN_TORRC = 12
    private const val FEED_TIMEOUT_MS = 15_000

    fun load(context: Context): List<TorBridge> =
        runCatching {
            storeFile(context).readLines().mapNotNull(TorBridge::parse)
        }.getOrDefault(emptyList())

    /**
     * The lines the user typed in themselves. They live in a separate file
     * because a scan replaces the scanned list wholesale — it must not be able
     * to delete a bridge somebody got from a friend or from bridges.torproject.org.
     */
    fun custom(context: Context): List<TorBridge> =
        runCatching {
            customFile(context).readLines().mapNotNull(TorBridge::parse)
        }.getOrDefault(emptyList())

    /** Both sources, the user's own bridges first: they were chosen, not guessed. */
    fun all(context: Context): List<TorBridge> =
        (custom(context) + load(context)).distinctBy { it.address }

    /** Stores every valid bridge line from [raw]; returns how many were kept and dropped. */
    fun saveCustom(context: Context, raw: String): CustomResult {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val parsed = lines.mapNotNull(TorBridge::parse).distinctBy { it.address }
        val dropped = lines.size - parsed.size
        if (parsed.isEmpty() && lines.isNotEmpty()) {
            // Nothing usable was typed: keeping the stored list is better than
            // wiping a working set of bridges because of a typo or a pasted
            // transport this app does not speak.
            AppState.log("engine tor custom bridges: $dropped line(s) rejected, the stored list is kept")
            return CustomResult(0, dropped)
        }
        runCatching {
            workDir(context).mkdirs()
            customFile(context).writeText(parsed.joinToString("\n") { it.line })
        }.onFailure { AppState.log("engine tor custom bridges: ${it.message}") }
        AppState.log(
            "engine tor ${parsed.size} custom bridge(s) stored${if (dropped > 0) ", $dropped rejected" else ""}"
        )
        return CustomResult(parsed.size, dropped)
    }

    /** What [saveCustom] answered, so the card can say it in the user's language. */
    data class CustomResult(val accepted: Int, val rejected: Int)

    fun lastScannedAt(context: Context): Long =
        runCatching { storeFile(context).lastModified() }.getOrDefault(0L)

    /**
     * The bridges Tor will be handed, best-measured first, minus any that is
     * already known bad. [exclude] is how a retry reaches different bridges
     * instead of the same ones that just carried nothing.
     */
    fun best(context: Context, exclude: Set<String> = emptySet()): List<TorBridge> =
        all(context).filterNot { it.address in exclude }.take(MAX_IN_TORRC)

    /** Lines for the torrc, best-measured first. */
    fun torrcLines(context: Context, exclude: Set<String> = emptySet()): List<String> =
        best(context, exclude).map { it.line }

    /**
     * Downloads the feed, probes every bridge it names and keeps the ones that
     * answer. Falls back to the bundled list when the feed itself is blocked —
     * which is likely on the very networks that need bridges.
     */
    fun scan(
        context: Context,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): ScanResult {
        val fetched = fetchFeed(log)
        val candidates = (fetched + bundled(context)).distinctBy { it.address }
        if (candidates.isEmpty()) {
            log("engine tor no bridge list to scan")
            return ScanResult(emptyList(), 0)
        }
        val tested = probe(candidates, stop, log)
        if (tested.isEmpty()) {
            // Nothing answered from here, but Tor may still get through one of
            // them, so the best-ranked few are kept rather than throwing away.
            log("engine tor every bridge failed the reachability probe, keeping the list for tor to try")
            val fallback = rankedOf(candidates).take(MAX_IN_TORRC)
            store(context, fallback)
            return ScanResult(emptyList(), candidates.size)
        }
        // Only the bridges Tor can actually be handed are worth keeping: a
        // longer file would just take longer to re-probe next time.
        store(context, tested.take(MAX_IN_TORRC))
        return ScanResult(tested, candidates.size)
    }

    /** How many bridges a scan looked at, and how many of them answered. */
    data class ScanResult(val reachable: List<TorBridge>, val total: Int)

    /** Re-probes the stored list without downloading anything. */
    fun retest(context: Context, stop: () -> Boolean, log: (String) -> Unit): ScanResult {
        val stored = load(context)
        if (stored.isEmpty()) return scan(context, stop, log)
        val tested = probe(stored, stop, log)
        if (tested.isEmpty()) return ScanResult(emptyList(), stored.size)
        store(context, tested)
        return ScanResult(tested, stored.size)
    }

    private fun rankedOf(bridges: List<TorBridge>): List<TorBridge> =
        bridges.sortedBy { it.address }

    private fun ranked(bridges: List<TorBridge>): List<TorBridge> =
        bridges.sortedWith(compareBy({ it.latencyMs }, { it.address }))

    private fun probe(
        bridges: List<TorBridge>,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<TorBridge> {
        val deadline = SystemClock.elapsedRealtime() + SCAN_BUDGET_MS
        val next = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<TorBridge>())
        val workers = Executors.newFixedThreadPool(CONCURRENCY.coerceAtMost(bridges.size))
        try {
            val tasks = (0 until bridges.size).map {
                workers.submit {
                    while (true) {
                        if (stop() || SystemClock.elapsedRealtime() >= deadline) return@submit
                        val index = next.getAndIncrement()
                        if (index >= bridges.size) return@submit
                        probeOne(bridges[index])?.let { bridge -> results += bridge }
                    }
                }
            }
            tasks.forEach { runCatching { it.get() } }
        } finally {
            workers.shutdownNow()
        }
        log(
            "engine tor probed ${bridges.size} bridge(s): ${results.size} answer, " +
                "${bridges.size - results.size} blocked or silent"
        )
        return ranked(results)
    }

    private fun probeOne(bridge: TorBridge): TorBridge? {
        val started = SystemClock.elapsedRealtime()
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(bridge.host, bridge.port), TCP_TIMEOUT_MS)
            TorBridge(bridge.line, bridge.address, (SystemClock.elapsedRealtime() - started).toInt())
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun fetchFeed(log: (String) -> Unit): List<TorBridge> {
        val body = runCatching {
            val connection = URL(FEED).openConnection() as HttpsURLConnection
            CaBundle.applyTo(connection)
            connection.connectTimeout = FEED_TIMEOUT_MS
            connection.readTimeout = FEED_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.inputStream.bufferedReader().use { it.readText() }
        }.onFailure {
            log("engine tor bridge feed unreachable: ${it.javaClass.simpleName}")
        }.getOrNull() ?: return emptyList()
        val parsed = body.lineSequence().mapNotNull(TorBridge::parse).toList()
        log("engine tor bridge feed returned ${parsed.size} obfs4 bridge(s)")
        return parsed
    }

    private fun bundled(context: Context): List<TorBridge> = runCatching {
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.mapNotNull(TorBridge::parse).toList()
        }
    }.getOrDefault(emptyList())

    /** A scan writes the whole file, so the bridges it did not keep are gone. */
    private fun store(context: Context, bridges: List<TorBridge>) {
        runCatching {
            workDir(context).mkdirs()
            val file = storeFile(context)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(bridges.joinToString("\n") { it.line })
            if (!temp.renameTo(file)) file.writeText(bridges.joinToString("\n") { it.line })
            runCatching { temp.delete() }
        }.onFailure { AppState.log("engine tor bridge store: ${it.message}") }
    }

    private fun workDir(context: Context): File = File(context.filesDir, "tor")

    private fun storeFile(context: Context): File = File(workDir(context), "bridges.txt")

    private fun customFile(context: Context): File = File(workDir(context), "custom_bridges.txt")
}
