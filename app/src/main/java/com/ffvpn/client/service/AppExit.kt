package com.ffvpn.client.service

import android.app.NotificationManager
import android.content.Context
import com.ffvpn.client.core.AppState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Closes the app the way the exit button asks for: the tunnel is already torn
 * down by the service, this only ends the process so no engine child process,
 * listener thread or notification outlives it.
 *
 * [schedule] is the safety net. An engine whose stop blocks forever must not be
 * able to keep the app half-closed with the interface already released.
 */
object AppExit {
    private const val FORCE_AFTER_MS = 4_000L
    private val closing = AtomicBoolean(false)

    fun close(context: Context, reason: String) {
        if (!closing.compareAndSet(false, true)) return
        AppState.log("exit: $reason")
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
        exitProcess(0)
    }

    fun schedule(context: Context) {
        Thread({
            runCatching { Thread.sleep(FORCE_AFTER_MS) }
            close(context.applicationContext, "forced after ${FORCE_AFTER_MS}ms")
        }, "app-exit").apply { isDaemon = true }.start()
    }
}
