package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import java.util.Collections
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** An edge address that answered TLS and HTTP often enough to be trusted. */
data class EdgeHit(
    val ip: String,
    val sni: String,
    /** The `Host:` that answered under that SNI; a fronted pair needs both names. */
    val host: String,
    val latencyMs: Int,
    val successes: Int,
    val tries: Int,
    val score: Int
)

/** What the scout phase found: the SNI values and the HTTP host values this network answers. */
data class EdgeScout(val snis: List<String>, val hosts: List<String>)

/**
 * How hard to look. The reference desktop client caps a deep sweep at 20 000
 * addresses; this phone probes over a 32-bit socket stack, so the caps are its
 * own and every mode also carries a wall-clock budget instead — a scan that
 * never ends is worse than one that stops early with what it found.
 */
enum class EdgeMode(
    val samplesPerCidr: Int,
    val maxIps: Int,
    val tries: Int,
    val minOk: Int,
    val budgetMs: Long
) {
    QUICK(8, 120, 2, 1, 150_000L),
    BALANCED(32, 600, 3, 2, 300_000L),
    DEEP(0, 1_200, 5, 3, 600_000L);

    companion object {
        fun of(name: String?): EdgeMode = values().firstOrNull { it.name == name } ?: BALANCED
    }
}

/**
 * The scanner behind the IP scanner screen: expand a list of addresses, keep the
 * SNIs that answer, then probe each address several times and rank what stays
 * reliable. Shares the core's own prober, so an address this screen calls usable
 * is one the Sun and Lion engine will see the same way.
 */
object EdgeScanner {
    private const val PROBE_TIMEOUT_MS = 5_000
    private const val RETRY_GAP_MS = 120L
    private const val SCOUT_IPS = 6

    /** Addresses from a list of IPs, CIDRs and ranges, in the order they read. */
    fun expand(lines: List<String>, mode: EdgeMode): List<String> {
        val result = LinkedHashSet<String>()
        for (line in lines) {
            val text = line.substringBefore('#').trim()
            if (text.isEmpty()) continue
            for (token in text.split("[\\s,;]+".toRegex())) {
                if (token.isEmpty()) continue
                appendToken(token, mode, result)
                if (result.size >= mode.maxIps) return result.toList()
            }
        }
        return result.toList()
    }

    private fun appendToken(token: String, mode: EdgeMode, into: MutableSet<String>) {
        val random = Random(System.nanoTime())
        if (token.contains('/')) {
            val (base, size) = parseCidr(token) ?: return
            val count = size.toInt()
            if (count <= 2) {
                add(into, ipToString(base))
                if (count == 2) add(into, ipToString(base + 1))
                return
            }
            if (mode.samplesPerCidr == 0) {
                // A /8 holds sixteen million addresses; the cap has to be
                // checked while enumerating, not after the list is built.
                var offset = 1
                while (offset < count - 1 && into.size < mode.maxIps) {
                    add(into, ipToString(base + offset))
                    offset++
                }
            } else {
                repeat(mode.samplesPerCidr) { add(into, ipToString(base + 1 + random.nextInt(count - 2))) }
            }
            return
        }
        val dash = token.indexOf('-')
        if (dash > 0) {
            val from = ipToLong(token.substring(0, dash)) ?: return
            val to = ipToLong(token.substring(dash + 1)) ?: return
            if (to < from) return
            val count = (to - from + 1).toInt().coerceAtMost(mode.maxIps)
            if (mode.samplesPerCidr == 0) {
                var step = 0
                while (step < count && into.size < mode.maxIps) {
                    add(into, ipToString(from + step))
                    step++
                }
            } else {
                repeat(minOf(mode.samplesPerCidr, count)) { add(into, ipToString(from + random.nextInt(count))) }
            }
            return
        }
        add(into, token)
    }

    private fun add(into: MutableSet<String>, ip: String) {
        if (isPublic(ip)) into.add(ip)
    }

    /** Loopback, private and multicast ranges never front anything useful. */
    fun isPublic(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it < 0 || it > 255 }) return false
        val first = octets[0]
        return when {
            first == 0 || first >= 224 -> false
            first == 10 || first == 127 -> false
            first == 169 && octets[1] == 254 -> false
            first == 172 && octets[1] in 16..31 -> false
            first == 192 && octets[1] == 168 -> false
            else -> true
        }
    }

    /**
     * Which SNI values and which HTTP `Host:` values this network still lets
     * through. The hosts are found first, with one known SNI, because a fronted
     * request needs both names to answer: an SNI that works behind a blocked host
     * is not a route. Every SNI is then proven behind the first host that
     * survived: one host is what the dial actually uses, and proving each SNI
     * against the whole list would multiply the scan's slowest phase.
     */
    fun scout(
        ips: List<String>,
        snis: List<String>,
        hosts: List<String>,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): EdgeScout {
        val targets = ips.take(SCOUT_IPS)
        if (targets.isEmpty() || snis.isEmpty()) return EdgeScout(emptyList(), emptyList())
        val firstSni = snis.first()

        val workingHosts = Collections.synchronizedList(mutableListOf<String>())
        val hostPool = Executors.newFixedThreadPool(minOf(hosts.size, 6).coerceAtLeast(1))
        try {
            hosts.map { host ->
                hostPool.submit {
                    if (stop()) return@submit
                    if (targets.any { ip ->
                            SunAndLionScanner.probe(ip, firstSni, host, PROBE_TIMEOUT_MS) != null
                        }
                    ) {
                        workingHosts.add(host)
                    }
                }
            }.forEach { runCatching { it.get() } }
        } finally {
            hostPool.shutdownNow()
        }
        val liveHosts = hosts.filter { it in workingHosts }
        val behind = liveHosts.firstOrNull() ?: firstSni

        val working = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(minOf(snis.size, 6))
        try {
            snis.map { sni ->
                pool.submit {
                    if (stop()) return@submit
                    if (targets.any { ip ->
                            SunAndLionScanner.probe(ip, sni, behind, PROBE_TIMEOUT_MS) != null
                        }
                    ) {
                        working.add(sni)
                    }
                }
            }.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }
        val ordered = snis.filter { it in working }
        log(
            "tools edge scout: ${ordered.size}/${snis.size} SNI value(s) answered, " +
                "${liveHosts.size}/${hosts.size} host(s) behind $behind"
        )
        return EdgeScout(ordered, liveHosts)
    }

    /**
     * Every address, probed until it is either trusted or dropped. Progress is
     * reported as each one finishes so the screen can show a live count. One host
     * fronts the whole run — the first that the scout saw answer — because that is
     * the single `Host:` value the engine's dial override can carry.
     */
    fun scan(
        ips: List<String>,
        snis: List<String>,
        hosts: List<String>,
        concurrency: Int,
        mode: EdgeMode,
        stop: () -> Boolean,
        onProgress: (done: Int, usable: Int) -> Unit
    ): List<EdgeHit> {
        if (ips.isEmpty() || snis.isEmpty()) return emptyList()
        val host = hosts.firstOrNull().orEmpty()
        val deadline = SystemClock.elapsedRealtime() + mode.budgetMs
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val hits = Collections.synchronizedList(mutableListOf<EdgeHit>())
        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 15))
        try {
            val tasks = (0 until ips.size).map {
                pool.submit {
                    while (true) {
                        if (stop() || SystemClock.elapsedRealtime() >= deadline) return@submit
                        val index = next.getAndIncrement()
                        if (index >= ips.size) return@submit
                        runCatching { check(ips[index], snis, host, mode, stop, deadline) }
                            .getOrNull()?.let { hit -> hits += hit }
                        val count = done.incrementAndGet()
                        onProgress(count, hits.size)
                    }
                }
            }
            tasks.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }
        val ranked = hits.sortedByDescending { it.score }
        AppState.log(
            "tools edge scan: ${ranked.size}/${ips.size} usable " +
                "(${if (SystemClock.elapsedRealtime() >= deadline) "budget over" else "list done"})"
        )
        return ranked
    }

    /** First SNI that gets an HTTP answer decides the pair; the rest proves it repeats. */
    private fun check(
        ip: String,
        snis: List<String>,
        host: String,
        mode: EdgeMode,
        stop: () -> Boolean,
        deadline: Long
    ): EdgeHit? {
        var latency: Int? = null
        var usedSni: String? = null
        for (sni in snis) {
            if (stop() || SystemClock.elapsedRealtime() >= deadline) return null
            val ms = SunAndLionScanner.probe(ip, sni, host.ifEmpty { sni }, PROBE_TIMEOUT_MS) ?: continue
            latency = ms
            usedSni = sni
            break
        }
        val sni = usedSni ?: return null
        val usedHost = host.ifEmpty { sni }
        var ok = 1
        var totalMs = latency ?: 0
        repeat(mode.tries - 1) {
            if (stop() || SystemClock.elapsedRealtime() >= deadline) return@repeat
            Thread.sleep(RETRY_GAP_MS)
            val ms = SunAndLionScanner.probe(ip, sni, usedHost, PROBE_TIMEOUT_MS) ?: return@repeat
            ok++
            totalMs += ms
        }
        if (ok < mode.minOk) return null
        val avg = totalMs / ok
        return EdgeHit(ip, sni, usedHost, avg, ok, mode.tries, score(avg, ok, mode.tries))
    }

    private fun score(latencyMs: Int, successes: Int, tries: Int): Int {
        val latencyPoints = (60 - minOf(60, latencyMs / 25)).coerceAtLeast(0)
        return latencyPoints + successes * 30 / tries
    }

    private fun parseCidr(token: String): Pair<Long, Long>? {
        val parts = token.split('/')
        if (parts.size != 2) return null
        val bits = parts[1].toIntOrNull() ?: return null
        if (bits !in 8..31) return null
        val address = ipToLong(parts[0]) ?: return null
        val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (address and mask) to (1L shl (32 - bits))
    }

    private fun ipToLong(value: String): Long? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    private fun ipToString(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}." +
            "${(value shr 8) and 0xFF}.${value and 0xFF}"
}

/**
 * What the scanner screen had typed in last time. Retyping a CIDR list with a
 * phone keyboard is not something a user should do twice.
 */
class EdgeInputs(context: Context) {
    private val prefs = context.getSharedPreferences("edge_scanner", Context.MODE_PRIVATE)

    var ranges: String
        get() = prefs.getString(KEY_RANGES, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_RANGES, value).apply()

    var snis: String
        get() = prefs.getString(KEY_SNIS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SNIS, value).apply()

    var hosts: String
        get() = prefs.getString(KEY_HOSTS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HOSTS, value).apply()

    var presetId: String
        get() = prefs.getString(KEY_PRESET, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    var mode: EdgeMode
        get() = EdgeMode.of(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var concurrency: Int
        get() = prefs.getInt(KEY_CONCURRENCY, DEFAULT_CONCURRENCY)
        set(value) = prefs.edit().putInt(KEY_CONCURRENCY, value.coerceIn(1, 15)).apply()

    private companion object {
        const val KEY_RANGES = "ranges"
        const val KEY_SNIS = "snis"
        const val KEY_HOSTS = "hosts"
        const val KEY_PRESET = "preset"
        const val KEY_MODE = "mode"
        const val KEY_CONCURRENCY = "concurrency"
        const val DEFAULT_CONCURRENCY = 8
    }
}
