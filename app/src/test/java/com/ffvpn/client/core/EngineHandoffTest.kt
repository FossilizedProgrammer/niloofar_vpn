package com.ffvpn.client.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

/**
 * Switching cores is only safe once the previous engine has let go of the local
 * proxy port, and an engine cannot be cancelled while it is dialling. These cover
 * the wait that enforces that, including the two cases that would otherwise let a
 * new session dial on top of a live tunnel: a second service tearing down at the
 * same time, and a stop that threw.
 */
class EngineHandoffTest {
    /** A daemon so a regression fails on the join timeout instead of hanging the suite. */
    private fun waiting(): Thread = Thread { EngineHandoff.await() }.apply {
        isDaemon = true
        start()
    }

    private fun finishes(thread: Thread) {
        thread.join(2_000)
        assertFalse("await did not return after the engine let go", thread.isAlive)
    }

    private fun blocked(thread: Thread) {
        thread.join(200)
        assertTrue("await returned before the engine let go", thread.isAlive)
    }

    @Test
    fun idleHandoffDoesNotBlock() {
        assertFalse(EngineHandoff.pending())
        finishes(waiting())
    }

    @Test
    fun waitsForTheEngineToLetGo() {
        val shutdown = CompletableFuture<Unit>()
        EngineHandoff.register(shutdown)
        assertTrue(EngineHandoff.pending())
        val thread = waiting()
        blocked(thread)
        shutdown.complete(Unit)
        finishes(thread)
        assertFalse(EngineHandoff.pending())
    }

    @Test
    fun waitsForEveryOverlappingShutdown() {
        val first = CompletableFuture<Unit>()
        val second = CompletableFuture<Unit>()
        EngineHandoff.register(first)
        EngineHandoff.register(second)
        val thread = waiting()
        // The newer teardown finishing says nothing about the older engine, which
        // may still hold the port the next session is about to bind.
        second.complete(Unit)
        blocked(thread)
        assertTrue(EngineHandoff.pending())
        first.complete(Unit)
        finishes(thread)
        assertFalse(EngineHandoff.pending())
    }

    @Test
    fun failedStopDoesNotStrandTheNextSession() {
        val shutdown = CompletableFuture<Unit>()
        EngineHandoff.register(shutdown)
        shutdown.completeExceptionally(RuntimeException("engine stop failed"))
        assertFalse(EngineHandoff.pending())
        finishes(waiting())
    }
}