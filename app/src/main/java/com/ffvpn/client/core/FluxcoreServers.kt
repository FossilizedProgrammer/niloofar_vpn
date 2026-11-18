package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import org.json.JSONArray

/** One selectable Fluxcore gateway: a country, or a country and its city. */
data class FluxcoreGateway(
    /** Selector the engine dials: `US` or `US:Frankfurt`. */
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val nodes: Int,
    /** Engine-measured round-trip in ms; -1 when it could not be measured. */
    val latencyMs: Int,
    /** Reported gateway load as a percentage, 0..100. */
    val loadPct: Int
)

/**
 * Reads the gateway list from the engine itself: `fluxcore list --locations
 * --json` discovers the fleet, measures every gateway and reports each row with
 * its load and latency. The list is cached so the picker opens instantly, and it
 * is ordered by load — least loaded first, which is the order the client itself
 * prefers once latency is comparable.
 */
object FluxcoreServers {
    private const val CACHE_FILE = "fluxcore_locations.json"
    private const val LIST_TIMEOUT_MS = 180_000L

    fun workDir(context: Context): File =
        File(context.applicationContext.filesDir, "fluxcore")

    fun binary(context: Context): File =
        File(context.applicationContext.applicationInfo.nativeLibraryDir, "libfluxcore.so")

    fun cacheFile(workDir: File): File = File(workDir, CACHE_FILE)

    /**
     * Runs the list command once. The engine keeps its fleet cache under
     * `os.UserConfigDir()`, so HOME has to point at the app's files dir exactly
     * as it does for the running engine.
     */
    fun fetch(
        binary: File,
        workDir: File,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<FluxcoreGateway> {
        workDir.mkdirs()
        val child = try {
            ProcessBuilder(binary.absolutePath, "list", "--locations", "--json")
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = workDir.parentFile?.absolutePath.orEmpty()
                    CaBundle.applyTo(this)
                }
                .start()
        } catch (t: Throwable) {
            log("engine fluxcore list failed to start: ${t.message ?: t.javaClass.simpleName}")
            return readCache(workDir)
        }
        val output = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        val reader = child.inputStream.bufferedReader()
        while (!stop() && SystemClock.elapsedRealtime() - startedAt <= LIST_TIMEOUT_MS) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            output.appendLine(line)
        }
        EngineProc.stopOnce(child, log, "fluxcore list")
        val payload = extractJson(output.toString())
        val list = parse(payload)
        if (list.isEmpty()) {
            log("engine fluxcore produced no gateway list, using cache")
            return readCache(workDir)
        }
        runCatching { cacheFile(workDir).writeText(payload!!) }
        log("engine fluxcore listed ${list.size} gateway(s), least loaded first")
        return list
    }

    fun readCache(workDir: File): List<FluxcoreGateway> =
        runCatching { parse(cacheFile(workDir).readText()) }.getOrDefault(emptyList())

    /** The command's own JSON document, ignoring any diagnostics printed around it. */
    private fun extractJson(output: String): String? {
        val start = output.indexOf('[')
        val end = output.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return output.substring(start, end + 1)
    }

    private fun parse(payload: String?): List<FluxcoreGateway> {
        if (payload.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return emptyList()
        val rows = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    FluxcoreGateway(
                        id = id,
                        code = item.optString("code"),
                        name = item.optString("name").ifBlank { item.optString("code") },
                        city = item.optString("city"),
                        nodes = item.optInt("nodes", 0),
                        latencyMs = if (item.isNull("latencyMs")) -1 else item.optLong("latencyMs", -1L).toInt(),
                        loadPct = item.optInt("loadPct", 0).coerceIn(0, 100)
                    )
                )
            }
        }
        return rows.sortedWith(
            compareBy(
                { it.loadPct },
                { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs },
                { it.name.lowercase() },
                { it.city.lowercase() }
            )
        )
    }
}
