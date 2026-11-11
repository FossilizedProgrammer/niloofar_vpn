package com.ffvpn.client.vpn

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Asks the engine's own SOCKS port for a connection to a fixed address. The
 * engine can report itself listening while the exit behind it is dead, so a
 * tunnel is only called up once this succeeds.
 */
object Traffic {
    private const val HOST = "127.0.0.1"

    fun serves(port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, port), HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(byteArrayOf(5, 1, 0))
            if (input.read() != 5 || input.read() != 0) return false

            output.write(CONNECT)
            socket.soTimeout = timeoutMs
            val reply = ByteArray(10)
            var read = 0
            while (read < reply.size) {
                val n = input.read(reply, read, reply.size - read)
                if (n < 0) return false
                read += n
            }
            if (reply[1].toInt() != 0) return false

            // A live exit keeps the connection open; a dead one drops it at once.
            socket.soTimeout = ALIVE_CHECK_MS
            try {
                input.read()
                false
            } catch (_: SocketTimeoutException) {
                true
            }
        }
    }.getOrDefault(false)

    /** SOCKS5 CONNECT to 1.1.1.1:443. */
    private val CONNECT = byteArrayOf(
        5, 1, 0, 1, 1, 1, 1, 1, 1, 0xBB.toByte()
    )

    private const val HANDSHAKE_TIMEOUT_MS = 2_000
    private const val ALIVE_CHECK_MS = 1_500
}
