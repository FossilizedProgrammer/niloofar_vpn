package com.ffvpn.client.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * Handover between two sessions. An engine cannot be cancelled once it is
 * dialling, so a stopped one keeps the local proxy port until its own start
 * returns. Psiphon and Sun and Lion are separate libraries that share that port,
 * and the TUN bridge is shared by every core, so a new session waits for the
 * previous engine to let go instead of dialling on top of it.
 *
 * Process-wide on purpose: the next session may belong to the other service.
 */
object EngineHandoff {
    private val inFlight = AtomicReference<List<CompletableFuture<Unit>>>(emptyList())

    /** Called synchronously as a session is let go, before the UI is released. */
    fun register(shutdown: CompletableFuture<Unit>) {
        // Added, not swapped in: both services can be tearing down at once, and
        // dropping the older one would let the next session dial on top of it.
        inFlight.updateAndGet { pending -> pending.filterNot { it.isDone } + shutdown }
    }

    /** True while an engine is still shutting down. */
    fun pending(): Boolean = inFlight.get().any { !it.isDone }

    /** Blocks the calling worker until no engine is still shutting down. */
    fun await() {
        while (true) {
            val shuttingDown = inFlight.get().filterNot { it.isDone }
            if (shuttingDown.isEmpty()) return
            // A stop that threw has still released what it could. Waiting on it
            // forever would strand every later attempt.
            shuttingDown.forEach { runCatching { it.join() } }
        }
    }
}