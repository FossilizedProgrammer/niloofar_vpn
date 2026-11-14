package com.ffvpn.client.service

import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.Prefs
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The app's own listeners, always on the ports the user picked: an HTTP proxy
 * in front of the core's SOCKS5, plus a plain SOCKS relay for a core that
 * cannot bind the chosen port itself. The HTTP side also binds every
 * interface when LAN sharing is on, so the shared address serves both
 * protocols.
 */
object ProxyFront {
    private val workers = Executors.newCachedThreadPool()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val listeners = CopyOnWriteArrayList<ServerSocket>()

    @Volatile
    private var running = false

    fun start(coreProxy: String) {
        val core = parseAddress(coreProxy) ?: return
        synchronized(this) {
            if (running) return
            running = true
        }
        val bind = if (Prefs.shareLan) "0.0.0.0" else Prefs.LOOPBACK
        if (core.port != Prefs.socksPort) {
            // Local apps only; LAN clients reach SOCKS5 through LanShare, which
            // connects to the engine directly and owns the 0.0.0.0 socket.
            listen("socks", Prefs.LOOPBACK, Prefs.socksPort) { client -> relay(client, core) }
        }
        listen("http", bind, Prefs.httpPort) { client -> serveHttp(client, core) }
    }

    fun stop() {
        synchronized(this) {
            if (!running && listeners.isEmpty()) return
            running = false
            listeners.forEach { runCatching { it.close() } }
            listeners.clear()
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        AppState.log("proxy front stopped")
    }

    private fun listen(kind: String, bind: String, port: Int, handler: (Socket) -> Unit) {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bind, port))
            }
        } catch (t: Throwable) {
            AppState.log("proxy front $kind port $port unavailable: ${t.message}")
            return
        }
        listeners += server
        workers.execute {
            while (running) {
                val client = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                clients += client
                workers.execute {
                    runCatching { handler(client) }
                    clients -= client
                    runCatching { client.close() }
                }
            }
        }
        AppState.log("proxy front $kind listening on $bind:$port")
    }

    /** Bytes straight through to the core's SOCKS5, no protocol parsing. */
    private fun relay(client: Socket, target: InetSocketAddress) {
        client.tcpNoDelay = true
        Socket().use { upstream ->
            upstream.tcpNoDelay = true
            upstream.connect(target, CONNECT_TIMEOUT_MS)
            val toCore = pump(client.getInputStream(), upstream.getOutputStream(), upstream)
            val toClient = pump(upstream.getInputStream(), client.getOutputStream(), client)
            toCore.join()
            toClient.join()
        }
    }

    private fun serveHttp(client: Socket, socks: InetSocketAddress) {
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 3) return
        val headers = readHeaders(input)
        val connect = parts[0].equals("CONNECT", ignoreCase = true)
        val target = (if (connect) parseAuthority(parts[1]) else parseHttpUrl(parts[1]))
            ?: return

        val upstream = Socket()
        upstream.tcpNoDelay = true
        upstream.connect(InetSocketAddress(Prefs.LOOPBACK, socks.port), CONNECT_TIMEOUT_MS)
        socksConnect(upstream, target.first, target.second)

        val output = client.getOutputStream()
        if (connect) {
            output.write(HTTP_OK)
            output.flush()
        } else {
            // Absolute-form request: the core resolves the name, so only the
            // origin-form path is forwarded.
            val forwarded = ByteArrayOutputStream().apply {
                write("${parts[0]} ${pathOf(parts[1])} ${parts[2]}\r\n".toByteArray())
                headers.forEach { write("${it}\r\n".toByteArray()) }
                write("\r\n".toByteArray())
            }
            upstream.getOutputStream().write(forwarded.toByteArray())
            upstream.getOutputStream().flush()
        }
        val toCore = pump(input, upstream.getOutputStream(), upstream)
        val toClient = pump(upstream.getInputStream(), output, client)
        toCore.join()
        toClient.join()
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private fun socksConnect(socket: Socket, host: String, port: Int) {
        val out = socket.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greeting = readExactly(socket.getInputStream(), 2)
        if (greeting.size != 2 || greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            error("SOCKS handshake failed")
        }
        val request = ByteArrayOutputStream()
        val dotted = IPV4.matches(host)
        request.write(
            if (dotted) byteArrayOf(0x05, 0x01, 0x00, 0x01)
            else byteArrayOf(0x05, 0x01, 0x00, 0x03)
        )
        if (dotted) {
            host.split('.').forEach { request.write(it.toInt()) }
        } else {
            val name = host.toByteArray()
            request.write(name.size)
            request.write(name)
        }
        request.write((port shr 8) and 0xFF)
        request.write(port and 0xFF)
        out.write(request.toByteArray())
        out.flush()

        val reply = readExactly(socket.getInputStream(), 4)
        if (reply.size != 4 || reply[1] != 0x00.toByte()) {
            error("SOCKS refused ${host}:${port} (${reply.getOrNull(1)})")
        }
        when (reply[3].toInt()) {
            0x01 -> readExactly(socket.getInputStream(), 4)
            0x04 -> readExactly(socket.getInputStream(), 16)
            0x03 -> {
                val length = readExactly(socket.getInputStream(), 1).first().toInt() and 0xFF
                readExactly(socket.getInputStream(), length)
            }
        }
        readExactly(socket.getInputStream(), 2)
    }

    private fun parseAuthority(value: String): Pair<String, Int>? {
        val host = value.substringBeforeLast(':', value)
        val port = value.substringAfterLast(':', "").toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return host.removeSurrounding("[", "]") to port
    }

    private fun parseHttpUrl(value: String): Pair<String, Int>? {
        val authority = value.substringAfter("://", value).substringBefore('/')
        val portPart = authority.substringAfterLast(':', "")
        return when {
            authority.isBlank() -> null
            portPart.isEmpty() -> authority.removeSurrounding("[", "]") to 80
            portPart.toIntOrNull() == null -> null
            else -> authority.substringBeforeLast(':').removeSurrounding("[", "]") to portPart.toInt()
        }
    }

    private fun pathOf(value: String): String {
        val withoutScheme = value.substringAfter("://", value)
        val path = withoutScheme.substringAfter('/')
        return if (path == withoutScheme) "/" else "/$path"
    }

    private fun parseAddress(address: String): InetSocketAddress? {
        val host = address.substringBeforeLast(':', Prefs.LOOPBACK).ifBlank { Prefs.LOOPBACK }
        val port = address.substringAfterLast(':', "").toIntOrNull() ?: return null
        return InetSocketAddress(host.removeSurrounding("[", "]"), port)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString().trim()
            if (value == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.write(value)
        }
    }

    private fun readHeaders(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (lines.size < 100) lines += line
        }
        return lines
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val step = input.read(bytes, read, count - read)
            if (step < 0) break
            read += step
        }
        return if (read == count) bytes else ByteArray(0)
    }

    private fun pump(input: InputStream, output: OutputStream, sink: Socket): Thread = Thread {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { output.flush() }
            runCatching { sink.shutdownOutput() }
        }
    }.apply {
        isDaemon = true
        start()
    }

    private const val CONNECT_TIMEOUT_MS = 8000
    private val HTTP_OK = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()
}
