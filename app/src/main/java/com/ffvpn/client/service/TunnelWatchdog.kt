package com.ffvpn.client.service

import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.Prefs
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Watches the tunnel from the outside by asking the core's own SOCKS port for a
 * connection. A core can report itself ready while nothing reaches the internet
 * through it, and the ring should not say "connected" in that case.
 */
object TunnelWatchdog {
    @Volatile
    private var worker: Thread? = null

    /** Ask the core's SOCKS port for a real connection right now. */
    fun servesTraffic(
        port: Int = Prefs.socksPort,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Boolean = probe(port, timeoutMs)

    /** Probes are idempotent, so starting again simply replaces the previous run. */
    fun start(port: Int, timeoutMs: Int, onDead: () -> Unit) {
        stop()
        val thread = Thread {
            var strikes = 0
            var delay = FIRST_DELAY_MS
            while (!Thread.currentThread().isInterrupted) {
                if (!sleep(delay)) return@Thread
                if (probe(port, timeoutMs)) {
                    if (strikes > 0) AppState.log("watchdog: traffic is flowing again")
                    strikes = 0
                    delay = INTERVAL_MS
                    continue
                }
                strikes += 1
                AppState.log("watchdog: nothing passed through the tunnel ($strikes/$STRIKES)")
                if (strikes >= STRIKES) {
                    onDead()
                    return@Thread
                }
                delay = QUICK_DELAY_MS
            }
        }
        worker = thread
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (t: InterruptedException) {
        false
    }

    /**
     * A SOCKS5 CONNECT for the same site the connect check asked for, by name.
     * Only the reply is given [timeoutMs], because that is where a core that
     * still has to choose and open an exit spends its time; the loopback
     * handshake with a process that is already listening is always instant.
     * The site is named rather than addressed by IP so this cannot fail merely
     * because one address is blocked at an exit that carries everything else.
     */
    private fun probe(port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(Prefs.LOOPBACK, port), HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(REQUEST)
            val greeting = ByteArray(2)
            if (!readFully(input, greeting)) return false
            if (greeting[0] != 5.toByte() || greeting[1] != 0.toByte()) return false
            socket.soTimeout = timeoutMs
            output.write(connectRequest(HOST, PORT))
            val reply = ByteArray(4)
            if (!readFully(input, reply)) return false
            if (reply[0] != 5.toByte() || reply[1] != 0.toByte()) return false
            // Skip the bound address the reply carries before the stream starts.
            val skipped = when (reply[3].toInt() and 0xFF) {
                1 -> 4 + 2
                3 -> {
                    val length = input.read()
                    if (length < 0) return false
                    length + 2
                }
                4 -> 16 + 2
                else -> return false
            }
            if (!readFully(input, ByteArray(skipped))) return false
            quiet(socket, input)
        }
    }.getOrDefault(false)

    private fun connectRequest(host: String, port: Int): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(7 + name.size)
        request[0] = 5
        request[1] = 1
        request[2] = 0
        request[3] = 3
        request[4] = name.size.toByte()
        name.copyInto(request, 5)
        request[5 + name.size] = (port shr 8).toByte()
        request[6 + name.size] = port.toByte()
        return request
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    /**
     * A proxy can answer "connected" a few milliseconds before it learns that
     * nothing is there, so the reply alone proves nothing. A live path stays
     * open and quiet; a dead one closes the socket at once.
     */
    private fun quiet(socket: Socket, input: InputStream): Boolean {
        socket.soTimeout = ALIVE_CHECK_MS
        return try {
            input.read()
            false
        } catch (t: SocketTimeoutException) {
            true
        } catch (t: Exception) {
            false
        }
    }

    private val REQUEST = byteArrayOf(5, 1, 0)

    /** Same site the connect check asks for, so the two never disagree. */
    private const val HOST = "www.youtube.com"
    private const val PORT = 443

    private const val DEFAULT_TIMEOUT_MS = 6_000
    private const val HANDSHAKE_TIMEOUT_MS = 2_000
    private const val ALIVE_CHECK_MS = 1_500
    private const val FIRST_DELAY_MS = 8_000L
    private const val QUICK_DELAY_MS = 8_000L
    private const val INTERVAL_MS = 60_000L
    private const val STRIKES = 3
}
