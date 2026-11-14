package com.multicore.vpn.tunnel

/**
 * Exclusive access to one engine's native library.
 *
 * The bundled engines keep a single tunnel pointer and one shared start-result
 * buffer in process globals, and their Stop() also clears the library's
 * "already started" guard while a start may still be running. Two calls that
 * overlap therefore corrupt each other: the failing one stores a nil tunnel,
 * the succeeding one dereferences it, and the Go runtime aborts the process
 * (SIGSEGV inside the engine's Start, then SIGABRT).
 *
 * Every native entry point of an engine is funnelled through one of these, so a
 * caller cannot open that window by forgetting a lock, and a stop issued during
 * a start waits for the start to return instead of racing it.
 */
class NativeGate {
    @PublishedApi
    internal val lock = Any()

    /** Inline so a caller can return from inside the critical section. */
    inline fun <T> call(block: () -> T): T = synchronized(lock, block)
}
