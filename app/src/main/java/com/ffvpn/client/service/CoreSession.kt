package com.ffvpn.client.service

import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.EngineHandoff
import com.ffvpn.client.core.VpnCore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * One connect attempt and its teardown. [stop] only schedules the teardown: an
 * engine dialling inside a native start does not return for its whole connect
 * timeout, so nothing on the UI path may wait for the returned future.
 *
 * The core is stopped once. A second stop would land after the next attempt has
 * already dialled, and would tear down that attempt's tunnel instead.
 */
internal class CoreSession(
    val core: VpnCore,
    private val workers: Executor
) {
    private var shutdown: CompletableFuture<Unit>? = null

    @Synchronized
    fun stop(): CompletableFuture<Unit> {
        shutdown?.let { return it }
        val completion = CompletableFuture<Unit>()
        shutdown = completion
        // Registered before the UI is released, so the next session cannot slip
        // in ahead of this engine's teardown.
        EngineHandoff.register(completion)
        workers.execute {
            try {
                core.disconnect()
                completion.complete(Unit)
            } catch (error: Throwable) {
                AppState.log("engine ${core.id} stop incomplete: ${error.message}")
                completion.completeExceptionally(error)
            }
        }
        return completion
    }
}