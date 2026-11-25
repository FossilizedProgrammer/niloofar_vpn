package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One entry from the official VeePN free subscription. */
data class VeepnServer(
    val ip: String,
    val port: Int,
    val proto: String,
    val flag: String,
    val name: String,
    val method: String
) {
    /** Exact selector the engine accepts on `connect` and the key the picker keeps. */
    val endpoint: String get() = "$ip:$port"

    val label: String get() = name.ifBlank { flag }.ifBlank { endpoint }
}

/** One server that passed the engine's real traffic test. */
data class VeepnTest(val server: VeepnServer, val latencyMs: Int)

/**
 * Reads the server list from the engine itself: `veepn servers --json` fetches
 * the free subscription (the same one the Android app uses) and prints every
 * parsed server, and `veepn test --json` sends a real request through each one
 * and reports which actually carry traffic. The list is cached so the picker
 * opens instantly.
 */
object VeepnServers {
    private const val SERVERS_FILE = "servers.json"
    private const val LIST_TIMEOUT_MS = 120_000L
    private const val TEST_TIMEOUT_MS = 600_000L

    fun workDir(context: Context): File =
        File(context.applicationContext.filesDir, "veepn")

    fun binary(context: Context): File =
        File(context.applicationContext.applicationInfo.nativeLibraryDir, "libveepn.so")

    fun cacheFile(workDir: File): File = File(workDir, SERVERS_FILE)

    /** Runs `servers --json` once and caches its output. */
    fun fetch(
        binary: File,
        workDir: File,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<VeepnServer> {
        val output = run(
            binary,
            workDir,
            listOf("servers", "--json", "--refresh"),
            LIST_TIMEOUT_MS,
            stop,
            log
        ) ?: return readCache(workDir)
        val array = runCatching { JSONArray(output) }.getOrNull()
        if (array == null) {
            log("engine veepn produced no server list, using cache")
            return readCache(workDir)
        }
        val list = parse(array)
        if (list.isEmpty()) {
            log("engine veepn produced no server list, using cache")
            return readCache(workDir)
        }
        runCatching { cacheFile(workDir).writeText(array.toString()) }
        log("engine veepn listed ${list.size} server(s)")
        return list
    }

    /**
     * Runs `test --json`: every server is used for a real request through its
     * own tunnel, and only the ones that answered are returned, fastest first.
     */
    fun test(
        binary: File,
        workDir: File,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<VeepnTest> {
        val output = run(
            binary,
            workDir,
            listOf("test", "--json"),
            TEST_TIMEOUT_MS,
            stop,
            log
        ) ?: return emptyList()
        val root = runCatching { JSONObject(output) }.getOrNull() ?: return emptyList()
        val rows = root.optJSONArray("results") ?: return emptyList()
        val results = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (!row.optBoolean("ok")) continue
                val ip = row.optString("ip")
                val port = row.optInt("port", 0)
                if (ip.isBlank() || port <= 0) continue
                add(
                    VeepnTest(
                        VeepnServer(
                            ip = ip,
                            port = port,
                            proto = row.optString("proto"),
                            flag = row.optString("flag"),
                            name = row.optString("name"),
                            method = ""
                        ),
                        latencyMs = row.optLong("latency_ms", -1L).toInt()
                    )
                )
            }
        }
        log("engine veepn tested ${root.optInt("total", 0)} server(s), ${results.size} usable")
        return results
    }

    fun readCache(workDir: File): List<VeepnServer> {
        val raw = runCatching { cacheFile(workDir).readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return parse(array)
    }

    /** Runs one short engine command and returns its JSON document. */
    private fun run(
        binary: File,
        workDir: File,
        args: List<String>,
        timeoutMs: Long,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): String? {
        workDir.mkdirs()
        val child = try {
            ProcessBuilder(listOf(binary.absolutePath) + args)
                .directory(workDir)
                .redirectErrorStream(false)
                .apply {
                    environment()["VEEPN_HOME"] = workDir.absolutePath
                    CaBundle.applyTo(this)
                }
                .start()
        } catch (t: Throwable) {
            log("engine veepn ${args.first()} failed to start: ${t.message ?: t.javaClass.simpleName}")
            return null
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        val outReader = Thread {
            runCatching {
                val reader = child.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(stdout) { stdout.appendLine(line) }
                }
            }
        }.apply { isDaemon = true; name = "veepn-${args.first()}-out"; start() }
        val errReader = Thread {
            runCatching {
                val reader = child.errorStream.bufferedReader()
                while (!stop() && SystemClock.elapsedRealtime() - startedAt <= timeoutMs) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        synchronized(stderr) { stderr.appendLine(line) }
                        log("engine veepn: ${line.trim().take(160)}")
                    }
                }
            }
        }.apply { isDaemon = true; name = "veepn-${args.first()}-err"; start() }
        EngineProc.attach(child, outReader)
        while (child.isAlive && !stop() && SystemClock.elapsedRealtime() - startedAt <= timeoutMs) {
            runCatching { child.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        EngineProc.shutdown(child, log, "veepn ${args.first()}")
        runCatching { outReader.join(500) }
        runCatching { errReader.join(500) }
        val text = synchronized(stdout) { stdout.toString() }
        return text.ifBlank { null }
    }

    private fun parse(array: JSONArray): List<VeepnServer> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val ip = item.optString("ip")
            val port = item.optInt("port", 0)
            if (ip.isBlank() || port <= 0) continue
            add(
                VeepnServer(
                    ip = ip,
                    port = port,
                    proto = item.optString("proto"),
                    flag = item.optString("flag"),
                    name = item.optString("name"),
                    method = item.optString("method")
                )
            )
        }
    }.distinctBy { it.endpoint }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.flag.lowercase() }, { it.endpoint }))
}
