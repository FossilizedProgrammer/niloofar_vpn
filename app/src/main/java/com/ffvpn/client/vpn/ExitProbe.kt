package com.ffvpn.client.vpn

import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.Prefs
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Asks an echo service which address the tunnel hands to the outside world and
 * where it lives. The request goes through the core's own SOCKS5 port: the tun
 * builder keeps this app off the tunnel, so a direct connection would report
 * the provider's address instead of the exit's. The hostname travels to the
 * proxy unresolved, so the DNS lookup happens on the far side too.
 */
object ExitProbe {
    private const val HOST = "ip-api.com"
    private const val PATH = "/json/?fields=status,query,country,city"
    private const val ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 4_000L
    private const val TIMEOUT_MS = 10_000

    /** Every start and every stop bumps this, so a late answer cannot land in a newer session. */
    @Volatile
    private var generation = 0

    fun start() {
        val token = ++generation
        AppState.update { it.copy(exitIp = "", exitPlace = "") }
        Thread({ probe(token) }, "exit-probe").apply { isDaemon = true }.start()
    }

    fun cancel() {
        generation++
    }

    private fun probe(token: Int) {
        repeat(ATTEMPTS) { attempt ->
            if (token != generation) return
            if (attempt > 0) {
                runCatching { Thread.sleep(RETRY_DELAY_MS) }
                if (token != generation) return
            }
            // The tunnel may be reported up before its first packet goes through.
            val found = runCatching { fetch() }.getOrNull() ?: return@repeat
            if (token != generation) return
            AppState.update { it.copy(exitIp = found.first, exitPlace = found.second) }
            return
        }
    }

    private fun fetch(): Pair<String, String> {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(Prefs.LOOPBACK, Prefs.socksPort))
        Socket(proxy).use { sock ->
            sock.connect(InetSocketAddress.createUnresolved(HOST, 80), TIMEOUT_MS)
            sock.soTimeout = TIMEOUT_MS
            val request = "GET $PATH HTTP/1.0\r\nHost: $HOST\r\nUser-Agent: NiloofarVPN\r\n" +
                "Accept: application/json\r\nConnection: close\r\n\r\n"
            sock.outputStream.write(request.toByteArray())
            sock.outputStream.flush()
            val answer = String(sock.getInputStream().readBytes(), Charsets.UTF_8)
            val json = JSONObject(answer.substringAfter("\r\n\r\n", answer))
            check(json.optString("status") == "success")
            val ip = json.optString("query")
            check(ip.isNotBlank())
            val place = listOf(json.optString("city"), json.optString("country"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            return ip to place
        }
    }
}
