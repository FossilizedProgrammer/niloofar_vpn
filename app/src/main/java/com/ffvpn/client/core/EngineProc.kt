package com.ffvpn.client.core

import java.util.concurrent.TimeUnit

/**
 * Teardown for the engines that run as child processes.
 *
 * The rule this enforces is that the child's output pipe is never closed while
 * the child can still write to it. The bundled engines abort on a lost
 * stdout/stderr, and a native abort cannot be caught by any try/catch in the
 * app. Keeping the reader attached until the process is provably gone removes
 * the whole class, and it also lets a child that is blocked writing finish
 * dying on SIGTERM instead of needing SIGKILL.
 */
object EngineProc {
    private val readers = java.util.concurrent.ConcurrentHashMap<Process, Thread>()

    /** Registers the thread that drains [child]'s output, so stopping it can wait for EOF. */
    fun attach(child: Process, reader: Thread) {
        readers[child] = reader
    }

    /** Kills [child] and closes its streams only once it is dead. */
    fun shutdown(child: Process, log: (String) -> Unit, tag: String) {
        val reader = readers.remove(child)
        runCatching { child.destroy() }
            .onFailure { log("$tag destroy: ${it.message ?: it.javaClass.simpleName}") }
        if (!wait(child, reader, SOFT_WAIT_MS)) {
            log("$tag still running after ${SOFT_WAIT_MS}ms, forcing")
            runCatching { child.destroyForcibly() }
                .onFailure { log("$tag destroyForcibly: ${it.message}") }
            wait(child, reader, HARD_WAIT_MS)
        }
        if (alive(child)) {
            log("$tag survived the kill, waiting for exit")
            var interrupted = false
            try {
                while (true) {
                    try {
                        child.waitFor()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        closeStreams(child)
    }

    /**
     * Waits for the process, not for the reader. Android interrupts a blocked
     * read the moment a process is destroyed, so a finished log pump proves
     * nothing about the child — and closing the pipe of a live one is exactly
     * what aborts a Rust engine on its way out.
     */
    private fun wait(child: Process, reader: Thread?, timeoutMs: Long): Boolean {
        runCatching { child.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
        runCatching { reader?.join(READER_GRACE_MS) }
        return !alive(child)
    }

    private fun alive(child: Process): Boolean =
        runCatching { child.isAlive }.getOrDefault(true)

    /**
     * Stops a short-lived engine command. The pipe is drained to EOF before it is
     * closed: a Go or Rust engine that is still printing when the read end goes
     * away dies in native code, and the app would lose the result line too.
     */
    fun stopOnce(child: Process, log: (String) -> Unit, tag: String) {
        val drainer = Thread {
            runCatching {
                val input = child.inputStream
                val buffer = ByteArray(4096)
                while (runCatching { input.read(buffer) }.getOrDefault(-1) >= 0) Unit
            }
        }.apply { isDaemon = true; name = "$tag-drain"; start() }
        attach(child, drainer)
        shutdown(child, log, tag)
    }

    private fun closeStreams(child: Process) {
        listOf(child.inputStream, child.errorStream, child.outputStream).forEach { stream ->
            runCatching { stream.close() }
        }
    }

    private const val SOFT_WAIT_MS = 3_000L
    private const val HARD_WAIT_MS = 2_000L
    private const val READER_GRACE_MS = 200L
}
