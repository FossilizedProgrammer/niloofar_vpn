package com.ffvpn.client.core

import android.os.Looper

/**
 * Last line of defence against a lost connection taking the app down.
 *
 * Every engine runs its own workers, and a socket that dies mid-read can surface
 * as any exception type the platform chooses (SocketException, EOFException,
 * NullPointerException from a half-parsed reply, OutOfMemoryError from a huge
 * Content-Length). Each site that could plausibly fail already catches, but an
 * uncaught throwable on *any* thread kills the whole process on Android, so a
 * missed path would disconnect the user with no explanation. Background threads
 * therefore report and survive; only the main looper is still allowed to crash,
 * because a dead UI thread cannot be trusted to draw anything sensible.
 */
object CrashGuard {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            report(thread.name, error)
            val onMainThread = runCatching { thread === Looper.getMainLooper().thread }.getOrDefault(false)
            if (onMainThread) previous?.uncaughtException(thread, error) else {
                AppState.log("worker ${thread.name} survived ${error.javaClass.simpleName}")
            }
        }
    }

    /** Logs a throwable with the frames that matter, without trusting any of it to render. */
    fun report(where: String, error: Throwable) {
        runCatching {
            AppState.log("uncaught in $where: ${error.javaClass.name}: ${error.message}")
            val frames = error.stackTrace ?: emptyArray()
            frames.take(8).forEach { AppState.log("    at $it") }
            val cause = error.cause
            if (cause != null) AppState.log("    caused by ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }
}
