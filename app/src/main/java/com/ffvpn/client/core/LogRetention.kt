package com.ffvpn.client.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The engines write their own log files and never look back, so the app does the
 * tidying: anything older than the retention window is dropped on every start,
 * before any engine has a log open. A log whose lines carry timestamps is cut
 * line by line; one that does not is judged by its own modification time, and a
 * file with nothing stale in it is left untouched.
 */
object LogRetention {
    private const val MAX_AGE_MS = 48L * 60L * 60L * 1000L

    private const val PSIPHON_STAMP = "\"timestamp\":\""
    private const val LANTERN_STAMP = "time=\""

    fun sweep(filesDir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        var pruned = 0
        filesDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".log") || it.name == "notices") }
            .forEach { file ->
                runCatching { prune(file, cutoff) }
                    .onSuccess { if (it) pruned++ }
                    .onFailure { AppState.log("log sweep ${file.name}: ${it.message}") }
            }
        if (pruned > 0) AppState.log("log sweep: $pruned engine log files pruned")
    }

    private fun prune(file: File, cutoff: Long): Boolean {
        val kept = ArrayList<CharSequence>()
        var stamped = 0
        var removed = 0
        file.forEachLine { line ->
            val at = timestampOf(line)
            when {
                at == null -> kept.add(line)
                at >= cutoff -> {
                    stamped++
                    kept.add(line)
                }
                else -> {
                    stamped++
                    removed++
                }
            }
        }
        if (stamped == 0) {
            // Nothing here carries a timestamp the app knows: the file as a whole
            // is the unit of retention.
            return if (file.lastModified() < cutoff) file.delete() else false
        }
        if (removed == 0) return false
        if (kept.isEmpty() || kept.all { it.isBlank() }) return file.delete()
        file.writeText(kept.joinToString("\n") + "\n")
        return true
    }

    /** Wall-clock milliseconds of a log line, or null when it carries none. */
    private fun timestampOf(line: String): Long? {
        val psiphon = line.indexOf(PSIPHON_STAMP)
        if (psiphon >= 0) {
            val start = psiphon + PSIPHON_STAMP.length
            val end = line.indexOf('"', start)
            if (end > start) {
                val raw = line.substring(start, end)
                (parse(ISO, raw) ?: parse(ISO_SHORT, raw))?.let { return it }
            }
        }
        if (line.startsWith(LANTERN_STAMP)) {
            val end = line.indexOf('"', LANTERN_STAMP.length)
            if (end > LANTERN_STAMP.length) {
                parse(PLAIN, line.substring(LANTERN_STAMP.length, end))?.let { return it }
            }
        }
        return null
    }

    private val ISO = format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private val ISO_SHORT = format("yyyy-MM-dd'T'HH:mm:ss'Z'")
    private val PLAIN = format("yyyy-MM-dd HH:mm:ss.SSS 'UTC'")

    private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parse(format: SimpleDateFormat, raw: String): Long? =
        runCatching { synchronized(format) { format.parse(raw)?.time } }.getOrNull()
}
