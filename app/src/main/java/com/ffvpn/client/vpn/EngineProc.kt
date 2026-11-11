package com.ffvpn.client.vpn

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Shutdown for engines that run as child processes. The child's output pipe is
 * never closed while it can still write to it: the Go engine aborts on a broken
 * stdout, and a native abort cannot be caught in the app.
 */
object EngineProc {
    private const val TAG = "ffvpn"
    private val readers = java.util.concurrent.ConcurrentHashMap<Process, Thread>()

    /** Registers the thread that drains [child]'s output, so stopping can wait for EOF. */
    fun attach(child: Process, reader: Thread) {
        readers[child] = reader
    }

    /** Kills [child] and only then closes its streams. */
    fun shutdown(child: Process) {
        val reader = readers.remove(child)
        runCatching { child.destroy() }
        if (!wait(child, reader, SOFT_WAIT_MS)) {
            runCatching { child.destroyForcibly() }
            wait(child, reader, HARD_WAIT_MS)
        }
        if (runCatching { child.isAlive }.getOrDefault(true)) {
            Log.w(TAG, "engine survived the kill, leaving its pipe open")
            return
        }
        listOf(child.inputStream, child.errorStream, child.outputStream).forEach {
            runCatching { it.close() }
        }
    }

    /** Stops a short-lived command after draining its output to EOF. */
    fun stopOnce(child: Process) {
        val drainer = Thread {
            runCatching {
                val input = child.inputStream
                val buffer = ByteArray(4096)
                while (runCatching { input.read(buffer) }.getOrDefault(-1) >= 0) Unit
            }
        }.apply { isDaemon = true; name = "ffvpn-drain"; start() }
        attach(child, drainer)
        shutdown(child)
    }

    private fun wait(child: Process, reader: Thread?, timeoutMs: Long): Boolean {
        runCatching { child.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
        runCatching { reader?.join(READER_GRACE_MS) }
        return !runCatching { child.isAlive }.getOrDefault(true)
    }

    private const val SOFT_WAIT_MS = 3_000L
    private const val HARD_WAIT_MS = 2_000L
    private const val READER_GRACE_MS = 200L
}
