package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One exit country the Geph broker offers. */
data class GephCountry(
    val code: String,
    val name: String,
    val exits: Int,
    val cities: String,
    val free: Boolean
) {
    /** What the picker shows; [code] is what the engine dials. */
    val label: String get() = if (name.isBlank()) code else "$name · $code"

    val detail: String
        get() = buildString {
            append(if (exits == 1) "1 exit" else "$exits exits")
            if (cities.isNotBlank()) append(" · $cities")
            if (!free) append(" · plus")
        }
}

/**
 * Reads the exit countries from the engine itself: `geph5-proxy countries
 * --level free` asks the broker which free-tier exits exist and prints them as a
 * table. The table is cached so the picker opens instantly, and the account file
 * and SQLite cache are passed explicitly because the engine's `dirs` lookups
 * find neither on Android.
 */
object GephServers {
    private const val COUNTRIES_FILE = "countries.json"
    private const val ACCOUNT_FILE = "account.txt"
    private const val LIST_TIMEOUT_MS = 120_000L

    fun workDir(context: Context): File =
        File(context.applicationContext.filesDir, "geph")

    fun binary(context: Context): File =
        File(context.applicationContext.applicationInfo.nativeLibraryDir, "libgeph.so")

    /** The free account code, created by `signup` on the first connect. */
    fun accountFile(workDir: File): File = File(workDir, ACCOUNT_FILE)

    /** SQLite cache the engine keeps its broker/exit routing in. */
    fun cachePath(workDir: File): File = File(workDir, "db")

    fun cacheFile(workDir: File): File = File(workDir, COUNTRIES_FILE)

    /** Runs `countries` once and caches what it printed. */
    fun fetch(
        binary: File,
        workDir: File,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<GephCountry> {
        val output = run(
            binary,
            workDir,
            listOf("--account-file", accountFile(workDir).absolutePath, "countries", "--level", "free"),
            LIST_TIMEOUT_MS,
            stop,
            log
        ) ?: return readCache(workDir)
        val list = parse(output)
        if (list.isEmpty()) {
            log("engine geph produced no country list, using cache")
            return readCache(workDir)
        }
        runCatching {
            val array = JSONArray()
            list.forEach { country ->
                array.put(
                    JSONObject()
                        .put("code", country.code)
                        .put("name", country.name)
                        .put("exits", country.exits)
                        .put("cities", country.cities)
                        .put("free", country.free)
                )
            }
            cacheFile(workDir).writeText(array.toString())
        }
        log("engine geph listed ${list.size} exit country(ies)")
        return list
    }

    fun readCache(workDir: File): List<GephCountry> {
        val raw = runCatching { cacheFile(workDir).readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val code = item.optString("code")
                if (code.isBlank()) continue
                add(
                    GephCountry(
                        code = code,
                        name = item.optString("name"),
                        exits = item.optInt("exits"),
                        cities = item.optString("cities"),
                        free = item.optBoolean("free", true)
                    )
                )
            }
        }
    }

    /**
     * Runs `signup` once and returns the account code it registered. A free
     * account costs one proof-of-work puzzle (about half a minute on a desktop,
     * longer on a phone), so the caller allows minutes for it.
     */
    fun signup(
        binary: File,
        workDir: File,
        timeoutMs: Long,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): String? {
        val account = accountFile(workDir)
        workDir.mkdirs()
        run(
            binary,
            workDir,
            listOf("--account-file", account.absolutePath, "signup"),
            timeoutMs,
            stop,
            log
        )
        val saved = runCatching { account.readText().trim() }.getOrNull()
        return saved?.ifBlank { null }
    }

    /** Runs one short engine command and returns everything it printed. */
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
                .redirectErrorStream(true)
                .apply {
                    // The engine would otherwise fall back to a HOME Android
                    // never sets, and its default RUST_LOG level is DEBUG: a
                    // line per dial stage per second.
                    environment()["HOME"] = workDir.absolutePath
                    environment()["RUST_LOG"] = "warn"
                    CaBundle.applyTo(this)
                }
                .start()
        } catch (t: Throwable) {
            log("engine geph ${args.last()} failed to start: ${t.message ?: t.javaClass.simpleName}")
            return null
        }
        val output = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        val reader = Thread {
            runCatching {
                val reader = child.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    // `signup` redraws its progress on one line with \r; only the
                    // numbered steps are worth logging.
                    if (line.isNotBlank()) {
                        synchronized(output) { output.appendLine(line) }
                        if (!line.contains("solving...")) log("engine geph: ${line.trim().take(160)}")
                    }
                }
            }
        }.apply { isDaemon = true; name = "geph-${args.last()}-out"; start() }
        EngineProc.attach(child, reader)
        while (child.isAlive && !stop() && SystemClock.elapsedRealtime() - startedAt <= timeoutMs) {
            runCatching { child.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        EngineProc.shutdown(child, log, "geph ${args.last()}")
        runCatching { reader.join(500) }
        return synchronized(output) { output.toString() }.ifBlank { null }
    }

    /** The engine's `countries` table: CC, name, exits, level, cities. */
    private fun parse(text: String): List<GephCountry> = buildList {
        text.lineSequence().forEach { line ->
            val match = COUNTRY_LINE.matchEntire(line.trimEnd()) ?: return@forEach
            val (code, name, exits, level, cities) = match.destructured
            add(
                GephCountry(
                    code = code,
                    name = name.trim(),
                    exits = exits.toIntOrNull() ?: 0,
                    cities = cities.trim(),
                    free = level == "free"
                )
            )
        }
    }.distinctBy { it.code }
        .sortedBy { it.name.lowercase() }

    private val COUNTRY_LINE =
        Regex("^([A-Z]{2})\\s+(.+?)\\s{2,}(\\d+)\\s+(free|plus)\\s*(.*)$")
}

