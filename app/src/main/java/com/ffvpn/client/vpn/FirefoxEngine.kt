package com.ffvpn.client.vpn

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ffvpn.client.R
import java.io.File

/**
 * The ffvpn command line, bundled as libffvpn.so and exec'd from
 * nativeLibraryDir. [connect] returns once an exit is carrying traffic through
 * the engine's local SOCKS5 proxy; the caller then opens the TUN and hands it
 * to the tun2socks bridge.
 */
class FirefoxEngine(private val context: Context) {
    private val workDir: File = Store.workDir(context)
    private val binary: File = Store.binary(context)

    @Volatile
    private var child: Process? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var listening = false

    @Volatile
    private var lastLine = ""

    /** What the engine dialled last; the UI shows its country and latency. */
    @Volatile
    var selected: Candidate? = null
        private set

    /**
     * Blocking. Returns null on success, otherwise a human-readable error.
     * [share] opens the listeners on the LAN so other devices can use them.
     */
    fun connect(share: Boolean, onStep: (Step, String?) -> Unit): String? {
        stopping = false
        if (!binary.exists()) return context.getString(R.string.error_engine_missing)
        if (!Store.hasSession(context)) return context.getString(R.string.error_sign_in)

        onStep(Step.ReadingList, null)
        var servers = ServerList.fetch(binary, Store.cacheFile(context)) { stopping }
        if (stopping) return null
        if (servers.isEmpty()) servers = ServerList.readCache(Store.cacheFile(context))
        if (servers.isEmpty()) return context.getString(R.string.error_no_exits)

        // A chosen country narrows the list; if its exits vanished the whole
        // list is still better than no tunnel at all.
        val wanted = Store.country(context)
        if (wanted.isNotBlank()) {
            val inCountry = servers.filter { it.country.equals(wanted, ignoreCase = true) }
            if (inCountry.isNotEmpty()) servers = inCountry
        }

        onStep(Step.Probing, servers.size.toString())
        val ranked = ServerList
            .rank(ServerList.probe(servers) { stopping }, Store.lastServer(context))
            .take(MAX_CANDIDATES)
        if (ranked.isEmpty()) return context.getString(R.string.error_no_exits)

        var lastError: String? = null
        for (candidate in ranked) {
            if (stopping) return null
            onStep(Step.Dialing, candidate.server.label)
            val error = runCandidate(candidate, share)
            if (stopping) return null
            if (error == null) {
                selected = candidate
                Store.setLastServer(context, candidate.server.address)
                Logs.add("vpn", "using ${candidate.server.label} (${candidate.latencyMs} ms)")
                return null
            }
            lastError = error
            Log.w(TAG, "${candidate.server.address} failed: $error")
            Logs.add("vpn", "${candidate.server.address} failed: $error")
        }
        return lastError ?: context.getString(R.string.error_no_exits)
    }

    /** Starts the engine for one exit and proves traffic through it. */
    private fun runCandidate(candidate: Candidate, share: Boolean): String? {
        destroyChild()
        listening = false
        lastLine = ""

        val host = if (share) "0.0.0.0" else "127.0.0.1"
        val args = mutableListOf(
            binary.absolutePath,
            "--session-token-file", Store.sessionFile(context).absolutePath,
            "--server", candidate.server.address,
            "--socks", "$host:$SOCKS_PORT"
        )
        // A second listener speaks HTTP CONNECT, which is what most devices and
        // apps accept as a proxy without extra software.
        if (share) args += listOf("--http", "$host:$HTTP_PORT")

        val builder = ProcessBuilder(args)
            .directory(workDir)
            .redirectErrorStream(true)
        CaBundle.applyTo(builder)

        val process = try {
            builder.start()
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }
        child = process
        pump(process)

        val startedAt = SystemClock.elapsedRealtime()
        while (!stopping) {
            if (listening) {
                if (Traffic.serves(SOCKS_PORT, TRAFFIC_TIMEOUT_MS)) return null
                destroyChild()
                return context.getString(R.string.error_no_traffic)
            }
            if (!process.isAlive) {
                val code = runCatching { process.exitValue() }.getOrNull()
                return "engine exited ($code) ${lastLine.take(140)}".trim()
            }
            if (SystemClock.elapsedRealtime() - startedAt >= PER_SERVER_MS) {
                destroyChild()
                return context.getString(R.string.error_no_proxy, PER_SERVER_MS / 1000)
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        destroyChild()
        return null
    }

    private fun pump(process: Process) {
        val thread = Thread {
            runCatching {
                val reader = process.inputStream.bufferedReader()
                while (true) {
                    val line = runCatching { reader.readLine() }.getOrNull() ?: break
                    lastLine = line.trim()
                    if (listeningLine.containsMatchIn(lastLine)) listening = true
                    if (quotaLine.containsMatchIn(lastLine)) {
                        Store.setQuota(context, lastLine.substringAfter("Bandwidth:").trim())
                    }
                    if (lastLine.isNotBlank()) {
                        Log.d(TAG, "engine: ${lastLine.take(200)}")
                        Logs.add("engine", lastLine)
                    }
                }
            }
        }.apply { isDaemon = true; name = "ffvpn-log"; start() }
        EngineProc.attach(process, thread)
    }

    /** True while the engine process still runs; used to notice a dead tunnel. */
    fun isAlive(): Boolean = child?.isAlive == true

    fun stop() {
        stopping = true
        destroyChild()
    }

    private fun destroyChild() {
        val process = child ?: return
        child = null
        EngineProc.shutdown(process)
        lastLine = ""
        listening = false
    }

    private companion object {
        const val TAG = "ffvpn"
        const val SOCKS_PORT = 1080
        const val HTTP_PORT = 1081
        const val MAX_CANDIDATES = 8
        // The engine dials an exit for up to 30 s and its account calls can time
        // out twice before that, so a short window drops exits that still work.
        const val PER_SERVER_MS = 60_000L
        const val TRAFFIC_TIMEOUT_MS = 30_000
        const val POLL_MS = 200L
        val listeningLine = Regex("""SOCKS5 proxy listening on""")
        val quotaLine = Regex("""Bandwidth: .*""")
    }
}
