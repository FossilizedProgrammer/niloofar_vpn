package com.multicore.vpn.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The invariant behind the engine crash: a native library keeps one tunnel and
 * one start-result buffer in process globals, so no two calls may be in flight
 * and a stop may never run underneath a start.
 */
class NativeGateTest {
    @Test
    fun `native calls never overlap`() {
        val gate = NativeGate()
        val inFlight = AtomicInteger()
        val overlap = AtomicBoolean()
        val threads = (0 until 8).map {
            Thread {
                repeat(50) {
                    gate.call {
                        if (inFlight.incrementAndGet() != 1) overlap.set(true)
                        Thread.sleep(1)
                        inFlight.decrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertFalse("two native calls ran at the same time", overlap.get())
        assertEquals("every call must leave the gate", 0, inFlight.get())
    }

    @Test
    fun `a stop waits for a start that is still running`() {
        val gate = NativeGate()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopped = AtomicBoolean()
        val start = Thread {
            gate.call {
                startEntered.countDown()
                releaseStart.await(10, TimeUnit.SECONDS)
            }
        }
        start.start()
        assertTrue("start did not enter the gate", startEntered.await(5, TimeUnit.SECONDS))
        val stop = Thread { gate.call { stopped.set(true) } }
        stop.start()
        Thread.sleep(100)
        assertFalse("a stop reached the library while a start was running", stopped.get())
        releaseStart.countDown()
        stop.join(5_000)
        start.join(5_000)
        assertTrue("the stop never ran after the start returned", stopped.get())
    }

    @Test
    fun `a throwing call releases the gate`() {
        val gate = NativeGate()
        runCatching { gate.call<Unit> { throw IllegalStateException("engine exploded") } }
        assertEquals("the gate stayed locked after an error", 42, gate.call { 42 })
    }
}
