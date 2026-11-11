package com.ffvpn.client.vpn

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What the engine asks for on stdin during a sign-in. */
enum class LoginPrompt { Password, Totp }

/**
 * Drives the engine's own Firefox Account sign-in: `--email` with the password
 * in `FFVPN_PASSWORD`, then `--session-token-file` so the token it obtains is
 * cached where [Store.hasSession] looks. The engine clears Mozilla's edge
 * challenge itself, so nothing here has to answer a CAPTCHA.
 *
 * [signIn] blocks on a worker thread; [answer] and [cancel] are called from the
 * UI thread while it runs.
 */
class FxaLogin(private val binary: File, private val workDir: File) {
    @Volatile
    private var child: Process? = null

    @Volatile
    private var cancelled = false

    private val answers = LinkedBlockingQueue<String>()

    fun answer(value: String) {
        answers.offer(value.trim())
    }

    fun cancel() {
        cancelled = true
        val process = child ?: return
        child = null
        EngineProc.stopOnce(process)
    }

    /** Blocking. Returns null once a token is cached, otherwise what went wrong. */
    fun signIn(
        email: String,
        password: String,
        unblockCode: String,
        onPrompt: (LoginPrompt) -> Unit,
        onStatus: (String) -> Unit
    ): String? {
        val session = File(workDir, SESSION)
        val args = mutableListOf(
            binary.absolutePath,
            "--email", email,
            "--session-token-file", session.absolutePath,
            "--usage"
        )
        if (unblockCode.isNotBlank()) args += listOf("--unblock-code", unblockCode)

        val builder = ProcessBuilder(args).directory(workDir).redirectErrorStream(true)
        CaBundle.applyTo(builder)
        builder.environment()["FFVPN_PASSWORD"] = password

        val process = try {
            builder.start()
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }
        child = process

        val startedAt = SystemClock.elapsedRealtime()
        val pending = StringBuilder()
        val chunk = ByteArray(512)
        var last = ""
        var problem: String? = null

        try {
            val input = process.inputStream
            while (!cancelled) {
                if (SystemClock.elapsedRealtime() - startedAt > SIGN_IN_TIMEOUT_MS) {
                    problem = "Sign-in timed out"
                    break
                }
                if (input.available() == 0) {
                    if (!process.isAlive) break
                    Thread.sleep(POLL_MS)
                    continue
                }
                val read = input.read(chunk)
                if (read < 0) break
                pending.append(String(chunk, 0, read))

                last = takeLines(pending, last, onStatus)
                val prompt = takePrompt(pending) ?: continue
                val code: String?
                if (prompt == LoginPrompt.Password && password.isNotBlank()) {
                    // The password is already known; only ask for what we lack.
                    code = password
                } else {
                    onPrompt(prompt)
                    code = await()
                }
                if (code == null) {
                    problem = "Sign-in was cancelled"
                    break
                }
                runCatching {
                    process.outputStream.write((code + "\n").toByteArray())
                    process.outputStream.flush()
                }
            }
        } catch (t: Throwable) {
            problem = t.message ?: t.javaClass.simpleName
        } finally {
            child = null
            EngineProc.stopOnce(process)
        }

        val token = runCatching { session.readText().trim() }.getOrDefault("")
        if (token.length >= TOKEN_LENGTH) return null
        if (problem != null) return problem
        Log.w(TAG, "sign-in ended without a token: $last")
        return last.take(200).ifBlank { "Sign-in failed" }
    }

    /** Waits for an answer from the UI, giving up when the user leaves. */
    private fun await(): String? {
        val deadline = SystemClock.elapsedRealtime() + ANSWER_TIMEOUT_MS
        while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
            answers.poll(POLL_MS, TimeUnit.MILLISECONDS)?.let { return it }
        }
        return null
    }

    /** Emits every finished line to the UI and keeps the newest as the status. */
    private fun takeLines(
        pending: StringBuilder,
        previous: String,
        onStatus: (String) -> Unit
    ): String {
        var last = previous
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) return last
            val line = pending.substring(0, end).trim()
            pending.delete(0, end + 1)
            if (line.isEmpty()) continue
            last = line.replace(STAMP, "")
            onStatus(last)
        }
    }

    /** Consumes a stdin prompt; the engine prints them without a newline. */
    private fun takePrompt(pending: StringBuilder): LoginPrompt? {
        val text = pending.trimEnd()
        val found = PROMPTS.firstOrNull { (_, marker) -> text.endsWith(marker) } ?: return null
        pending.setLength(0)
        return found.first
    }

    private companion object {
        const val TAG = "ffvpn"
        const val SESSION = "session.txt"
        const val TOKEN_LENGTH = 64
        const val SIGN_IN_TIMEOUT_MS = 600_000L
        const val ANSWER_TIMEOUT_MS = 300_000L
        const val POLL_MS = 200L
        val STAMP = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}\s*""")
        val PROMPTS = listOf(
            LoginPrompt.Totp to "Two-factor code (TOTP):",
            LoginPrompt.Password to "Firefox Account password:"
        )
    }
}
