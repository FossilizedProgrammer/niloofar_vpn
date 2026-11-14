package com.ffvpn.client.service

import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.Prefs
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Exposes the core's loopback SOCKS5 port to the local network.
 * Clients on the same Wi-Fi/hotspot point their proxy at <device-ip>:1081;
 * the HTTP front ProxyFront opens on the user's HTTP port is announced too,
 * because that listener binds every interface while sharing is on.
 */
object LanShare {
    const val PORT = 1081
    private const val DEFAULT_TARGET = "127.0.0.1:1080"

    /** Interfaces clients can actually reach; tunnels and carrier links are not shareable. */
    private val SHAREABLE_INTERFACES = listOf("wlan", "ap", "swlan", "eth", "rndis", "usb")

    private val workers = Executors.newCachedThreadPool()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    fun start(target: String = DEFAULT_TARGET) {
        val targetAddress = parseTarget(target)
        synchronized(this) {
            if (running) return
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", PORT))
                }
            } catch (t: Throwable) {
                AppState.log("LAN share failed: ${t.message ?: t.javaClass.simpleName}")
                publish(null)
                return
            }
            server = socket
            running = true
            workers.execute { acceptLoop(socket, targetAddress) }
        }
        val host = localAddress()
        if (host == null) {
            AppState.log("LAN share: port $PORT open, no LAN interface to advertise")
            publish(null)
        } else {
            AppState.log("LAN share: $host:$PORT -> ${targetAddress.hostString}:${targetAddress.port}")
            publish(host)
        }
    }

    /** Re-publishes the address after the device joined another network. */
    fun refresh() {
        if (!running) return
        val host = localAddress() ?: return
        val next = "$host:$PORT"
        if (AppState.state.lan == next && AppState.state.lanHttp.isNotBlank()) return
        AppState.log("LAN share address: $next")
        publish(host)
    }

    fun stop() {
        synchronized(this) {
            if (!running && server == null) {
                publish(null)
                return
            }
            running = false
            runCatching { server?.close() }
            server = null
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        publish(null)
        AppState.log("LAN share stopped")
    }

    /** Announces both LAN endpoints: the SOCKS relay here and the HTTP front. */
    private fun publish(host: String?) {
        val socks = host?.let { "$it:$PORT" }.orEmpty()
        val http = host?.let { "$it:${Prefs.httpPort}" }.orEmpty()
        AppState.update { it.copy(lan = socks, lanHttp = http) }
    }

    private fun acceptLoop(socket: ServerSocket, target: InetSocketAddress) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                break
            }
            clients += client
            workers.execute { bridge(client, target) }
        }
    }

    private fun bridge(client: Socket, target: InetSocketAddress) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val remote = Socket()
            upstream = remote
            remote.tcpNoDelay = true
            remote.connect(target, 5000)
            val toTarget = pump(client.getInputStream(), remote.getOutputStream(), remote)
            val toClient = pump(remote.getInputStream(), client.getOutputStream(), client)
            toTarget.join()
            toClient.join()
        } catch (_: Throwable) {
        } finally {
            clients -= client
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
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

    private fun parseTarget(target: String): InetSocketAddress {
        val host = target.substringBeforeLast(':', "127.0.0.1").ifBlank { "127.0.0.1" }
        val port = target.substringAfterLast(':', "1080").toIntOrNull() ?: 1080
        return InetSocketAddress(host, port)
    }

    private fun localAddress(): String? {
        val candidates = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback && SHAREABLE_INTERFACES.any { p -> it.name.startsWith(p) } }
                .flatMap { nic -> Collections.list(nic.inetAddresses).map { nic.name to it } }
        }.getOrDefault(emptyList())
        return candidates.firstOrNull { (_, address) -> address is Inet4Address }?.second?.hostAddress
    }
}
