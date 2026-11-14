package com.ffvpn.client.core

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/**
 * One SSTP relay as VPN Gate publishes it. The hostname is kept because the
 * page prints it next to the address and it is what tells a relay apart from a
 * stale row; the core only ever dials [endpoint].
 */
data class VpnGateServer(
    val ip: String,
    val port: Int,
    val host: String,
    val country: String,
    val countryCode: String,
    val pingMs: Int,
    val speedMbps: Int
) {
    val endpoint: SstpEndpoint get() = SstpEndpoint(ip, port)
    val key: String get() = "$ip:$port"

    override fun toString(): String = key
}

/**
 * Reads the SSTP relays straight off vpngate.net, the way the reference
 * client's scraper does: one GET with a desktop user agent, the third
 * `vg_hosts_table_id` table, one row per relay, deduped by `ip:port`.
 *
 * The page itself is the list — VPN Gate rotates its servers daily, which is
 * why the app used to ship a frozen copy of it. Reading it needs a route that
 * is not blocked, so the last good fetch is cached for the day the page is
 * unreachable.
 */
object VpnGate {
    private const val HOST = "www.vpngate.net"
    private const val PATH = "/en/"
    private const val PORT = 443
    private const val PAGE = "https://$HOST$PATH"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 25_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val MAX_BODY_BYTES = 8 * 1024 * 1024

    private val TABLE = Regex(
        "<table[^>]*id\\s*=\\s*[\"']?vg_hosts_table_id[\"']?[^>]*>([\\s\\S]*?)</table>",
        RegexOption.IGNORE_CASE
    )
    private val ROW = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    private val SSTP_HOST = Regex(
        "SSTP\\s+Hostname\\s*:[\\s\\S]{0,120}?" +
            "([a-zA-Z0-9][a-zA-Z0-9.\\-]{2,60}\\.opengw\\.net)(?::(\\d{2,5}))?",
        RegexOption.IGNORE_CASE
    )
    private val IPV4 = Regex("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b")
    // The row prints `flags/US.png` and the country name right after the image;
    // older copies of the page carried the name in a title attribute instead.
    private val FLAG = Regex(
        "flags/(?:\\d{1,2}/)?([a-z]{2})\\.(?:gif|png|jpg)[^>]*>(?:<br\\s*/?>)?\\s*([^<]*)",
        RegexOption.IGNORE_CASE
    )
    private val FLAG_TITLE = Regex(
        "flags/(?:\\d{1,2}/)?[a-z]{2}\\.(?:gif|png|jpg)[^>]*title=\"([^\"]*)\"",
        RegexOption.IGNORE_CASE
    )
    private val PING = Regex("(\\d+)\\s*ms", RegexOption.IGNORE_CASE)
    private val SPEED = Regex("([\\d.,]+)\\s*Mbps", RegexOption.IGNORE_CASE)

    /** The four countries the screen can be told to skip; a relay there is not a way out. */
    val restrictedCountries = setOf("IR", "RU", "CN", "KP")

    fun isRestricted(server: VpnGateServer): Boolean = server.countryCode in restrictedCountries

    /** Null when the page could not be read at all — a different thing than an empty list. */
    fun fetch(log: (String) -> Unit): List<VpnGateServer>? {
        val html = fetchDirect(log) ?: fetchThroughTunnel(log)
        if (html == null) return null
        val servers = parse(html)
        log("tools vpngate read ${html.length} chars: ${servers.size} SSTP relay(s)")
        return servers
    }

    private fun fetchDirect(log: (String) -> Unit): String? = runCatching {
        val connection = URL(PAGE).openConnection() as HttpsURLConnection
        CaBundle.applyTo(connection)
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", UA)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        val status = connection.responseCode
        if (status != 200) error("HTTP $status")
        connection.inputStream.bufferedReader().use { it.readText() }
    }.onFailure {
        log("tools vpngate direct: ${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

    /**
     * The fallback the reference client uses (`curl --socks5-hostname`): VPN
     * Gate answers with a private address on the networks this app is used
     * from, so the name has to be resolved by the exit instead of the phone.
     * The request goes to the app's own SOCKS5 port, which is the engine itself
     * or the relay in front of it, and carries the hostname rather than an
     * address. Only reachable while a core is connected.
     */
    private fun fetchThroughTunnel(log: (String) -> Unit): String? = runCatching {
        val bridge = Socket()
        try {
            bridge.tcpNoDelay = true
            bridge.connect(InetSocketAddress(Prefs.LOOPBACK, Prefs.socksPort), TIMEOUT_MS)
            bridge.soTimeout = TIMEOUT_MS
            socksConnect(bridge, HOST, PORT)
            log("tools vpngate: the tunnel carried the name, handshake now")
            val socket = CaBundle.socketFactory()
                .createSocket(bridge, HOST, PORT, true) as SSLSocket
            socket.use { plain ->
                plain.soTimeout = READ_TIMEOUT_MS
                runCatching { plain.startHandshake() }
                    .onFailure { log("tools vpngate tls: ${it.javaClass.simpleName} ${it.message}") }
                    .getOrThrow()
                log("tools vpngate tls ok, reading the page")
                readResponse(plain.getOutputStream(), plain.getInputStream(), log)
            }
        } catch (t: Throwable) {
            runCatching { bridge.close() }
            throw t
        }
    }.onFailure {
        log("tools vpngate through the tunnel: ${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

    /** SOCKS5 CONNECT that keeps the name unresolved so the far end resolves it. */
    private fun socksConnect(socket: Socket, host: String, port: Int) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greeting = readN(input, 2)
        if (greeting.size < 2 || greeting[1] != 0x00.toByte()) error("SOCKS5 greeting failed")
        val name = host.toByteArray()
        val request = byteArrayOf(0x05, 0x01, 0x00, 0x03, name.size.toByte()) + name +
            byteArrayOf((port shr 8).toByte(), port.toByte())
        output.write(request)
        output.flush()
        val reply = readN(input, 4)
        if (reply.size < 4 || reply[1] != 0x00.toByte()) {
            error("SOCKS5 refused $host:$port (code ${reply.getOrNull(1)})")
        }
        when (reply[3].toInt() and 0xFF) {
            0x01 -> readN(input, 4)
            0x03 -> readN(input, readN(input, 1).firstOrNull()?.toInt()?.and(0xFF) ?: 0)
            0x04 -> readN(input, 16)
        }
        readN(input, 2)
    }

    private fun readResponse(
        output: OutputStream,
        input: InputStream,
        log: (String) -> Unit
    ): String {
        output.write(
            (
                "GET $PATH HTTP/1.1\r\n" +
                    "Host: $HOST\r\n" +
                    "User-Agent: $UA\r\n" +
                    "Accept: text/html,application/xhtml+xml\r\n" +
                    "Accept-Language: en\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
        )
        output.flush()
        val status = readLine(input) ?: error("no response from $HOST")
        val code = status.split(' ').getOrNull(1)
        if (code != "200") error("HTTP ${code ?: status}")
        var chunked = false
        var declared = -1
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("transfer-encoding:")) chunked = lower.contains("chunked")
            if (lower.startsWith("content-length:")) {
                declared = lower.substringAfter(':').trim().toIntOrNull() ?: -1
            }
        }
        log(
            "tools vpngate HTTP $code, " +
                when {
                    chunked -> "chunked body"
                    declared > 0 -> "$declared bytes"
                    else -> "body until close"
                }
        )
        val body = if (chunked) readChunked(input) else readBody(input, declared, log)
        return String(body, Charsets.UTF_8)
    }

    /** Reads with a progress line, because a stalled tunnel looks the same as a slow one. */
    private fun readBody(input: InputStream, expected: Int, log: (String) -> Unit): ByteArray {
        val limit = if (expected > 0) expected.coerceAtMost(MAX_BODY_BYTES) else MAX_BODY_BYTES
        val body = ByteArrayOutputStream(minOf(limit, 256 * 1024))
        val chunk = ByteArray(32 * 1024)
        var mark = 256 * 1024
        while (body.size() < limit) {
            val read = input.read(chunk, 0, minOf(chunk.size, limit - body.size()))
            if (read < 0) break
            body.write(chunk, 0, read)
            if (body.size() >= mark) {
                log("tools vpngate read ${body.size()} of $limit bytes")
                mark += 256 * 1024
            }
        }
        return body.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        while (body.size() < MAX_BODY_BYTES) {
            val size = readLine(input)?.trim()?.substringBefore(';')?.toIntOrNull(16) ?: break
            if (size <= 0) break
            body.write(readN(input, size))
            readLine(input)
        }
        return body.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream(128)
        var read = 0
        while (true) {
            val value = input.read()
            if (value < 0) return if (read == 0) null else line.toString("US-ASCII")
            read++
            if (value == '\n'.code) return line.toString("US-ASCII").trimEnd('\r')
            if (value != '\r'.code) line.write(value)
        }
    }

    private fun readN(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count.coerceAtMost(MAX_BODY_BYTES))
        var read = 0
        while (read < buffer.size) {
            val step = input.read(buffer, read, buffer.size - read)
            if (step < 0) break
            read += step
        }
        return buffer.copyOf(read)
    }

    fun parse(html: String): List<VpnGateServer> {
        val tables = TABLE.findAll(html).map { it.groupValues[1] }.toList()
        if (tables.isEmpty()) return wholePage(html)
        val rows = ROW.findAll(tables.getOrElse(2) { tables.last() }).map { it.groupValues[1] }.toList()
        val servers = rows.mapNotNull(::parseRow).distinctBy { it.key }
        return if (servers.isEmpty()) wholePage(html) else servers
    }

    /**
     * For the day the table markup moves: every SSTP hostname on the page, with
     * the address taken from the text just before it — the row's own IPv4 sits
     * ahead of its hostname, and the page repeats the pattern per relay.
     */
    private fun wholePage(html: String): List<VpnGateServer> {
        val servers = SSTP_HOST.findAll(html).mapNotNull { match ->
            val host = match.groupValues[1].trim()
            if (host.isEmpty()) return@mapNotNull null
            val port = match.groupValues[2].toIntOrNull() ?: 443
            if (port !in 1..65535) return@mapNotNull null
            val window = html.substring(
                (match.range.first - 2_000).coerceAtLeast(0),
                match.range.last.coerceAtMost(html.length)
            )
            val ip = IPV4.findAll(window).map { it.groupValues[1] }
                .lastOrNull(::looksLikeIp) ?: return@mapNotNull null
            val (code, name) = flagOf(window)
            VpnGateServer(ip, port, host, name, code, 0, 0)
        }.toList()
        return servers.distinctBy { it.key }
    }

    private fun parseRow(row: String): VpnGateServer? {
        val lower = row.lowercase()
        if (lower.contains("country") && lower.contains("physical location")) return null
        val match = SSTP_HOST.find(row) ?: return null
        val host = match.groupValues[1].trim()
        if (host.isEmpty()) return null
        val port = match.groupValues[2].toIntOrNull() ?: 443
        if (port !in 1..65535) return null
        // VPN Gate prints the IPv4 of the relay in the same row; the first one
        // that is a real address is the relay, the rest are banner counts.
        val ip = IPV4.find(row)?.groupValues?.get(1)?.takeIf(::looksLikeIp) ?: return null
        val (code, name) = flagOf(row)
        return VpnGateServer(
            ip = ip,
            port = port,
            host = host,
            country = name,
            countryCode = code,
            pingMs = PING.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            speedMbps = SPEED.find(row)?.groupValues?.get(1)
                ?.replace(",", "")?.toFloatOrNull()?.toInt() ?: 0
        )
    }

    /** The flag's two-letter code and the country name printed next to it. */
    private fun flagOf(row: String): Pair<String, String> {
        val flag = FLAG.find(row) ?: return "" to ""
        val code = flag.groupValues[1].uppercase()
        val name = flag.groupValues[2].trim()
            .ifEmpty { FLAG_TITLE.find(row)?.groupValues?.get(1)?.trim().orEmpty() }
        return code to name.ifEmpty { code }
    }    private fun looksLikeIp(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /** The last list this device managed to read, kept for the days the page is blocked. */
    fun cacheFile(context: Context): File =
        File(context.filesDir, "sstp/vpngate_last.txt")

    fun cache(context: Context, servers: List<VpnGateServer>) {
        runCatching {
            val file = cacheFile(context)
            file.parentFile?.mkdirs()
            file.writeText(
                servers.joinToString("\n") {
                    "${it.ip}:${it.port}|${it.country}|${it.pingMs}|${it.countryCode}"
                }
            )
        }.onFailure { AppState.log("tools vpngate cache: ${it.message}") }
    }

    fun loadCache(context: Context): List<VpnGateServer> = runCatching {
        cacheFile(context).readLines().mapNotNull { line ->
            val parts = line.split("|")
            val endpoint = SstpEndpoint.parse(parts[0]) ?: return@mapNotNull null
            val country = parts.getOrNull(1).orEmpty()
            // A cache written before the code was kept stores the code as the
            // country when the page had no name for it, so two letters mean both.
            val code = parts.getOrNull(3)?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: country.takeIf { it.length == 2 }?.uppercase().orEmpty()
            VpnGateServer(
                ip = endpoint.ip,
                port = endpoint.port,
                host = "",
                country = country,
                countryCode = code,
                pingMs = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                speedMbps = 0
            )
        }
    }.getOrDefault(emptyList())
}
