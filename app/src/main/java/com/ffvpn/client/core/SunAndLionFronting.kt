package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class SunAndLionCandidates(
    val ips: List<String>,
    val snis: List<String>,
    /** HTTP `Host:` values that answer on those edges. Deliberately not the SNI. */
    val hosts: List<String> = emptyList()
)

/** A CDN family the scanner screen can be pointed at. */
data class EdgePreset(
    val id: String,
    val ranges: List<String>,
    val snis: List<String>,
    val hosts: List<String>,
    /** Edges the bundled server entries already front through. */
    val anchors: List<String>
)

/** The engine rejects raw addresses as SNI hostnames. */
private fun usableSni(value: String): Boolean =
    value.isNotBlank() && value.any { it.isLetter() } && value.none { it.isWhitespace() }

/**
 * Remembers the last SNI/IP scan and the last route that actually carried a
 * tunnel, so the next attempt can start from known-good values.
 */
class SunAndLionStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("sunlion_fronting", Context.MODE_PRIVATE)

    fun load(): SunAndLionCandidates? {
        val ips = readList(KEY_IPS)
        val snis = readList(KEY_SNIS).filter(::usableSni)
        if (ips.isEmpty() || snis.isEmpty()) return null
        return SunAndLionCandidates(ips, snis, readList(KEY_HOSTS).filter(::usableSni))
    }

    fun saveScan(candidates: SunAndLionCandidates) {
        val snis = candidates.snis.filter(::usableSni)
        if (candidates.ips.isEmpty() || snis.isEmpty()) return
        prefs.edit()
            .putString(KEY_IPS, JSONArray(candidates.ips).toString())
            .putString(KEY_SNIS, JSONArray(snis).toString())
            .putString(KEY_HOSTS, JSONArray(candidates.hosts.filter(::usableSni)).toString())
            .putLong(KEY_SCANNED_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordSuccess(ip: String, sni: String) {
        prefs.edit()
            .putString(KEY_LAST_IP, ip)
            .putString(KEY_LAST_SNI, sni)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
        if (sni.isNotBlank() && load() == null) {
            saveScan(SunAndLionCandidates(listOf(ip), listOf(sni)))
        }
    }

    /** Forgets the route that worked last, so a fresh list is walked from its top. */
    fun forgetSelection() {
        prefs.edit()
            .remove(KEY_LAST_IP)
            .remove(KEY_LAST_SNI)
            .remove(KEY_LAST_AT)
            .apply()
    }

    /**
     * The address and SNI that carried a tunnel last time. An empty SNI means the
     * winning dial omitted the extension; only the address is trusted to repeat it.
     */
    fun lastRoute(): Pair<String, String>? {
        val ip = prefs.getString(KEY_LAST_IP, null) ?: return null
        if (ip.isBlank()) return null
        return ip to prefs.getString(KEY_LAST_SNI, "").orEmpty()
    }

    private fun readList(key: String): List<String> = runCatching {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_IPS = "ips"
        const val KEY_SNIS = "snis"
        const val KEY_HOSTS = "hosts"
        const val KEY_SCANNED_AT = "scanned_at"
        const val KEY_LAST_IP = "last_ip"
        const val KEY_LAST_SNI = "last_sni"
        const val KEY_LAST_AT = "last_at"
    }
}

/**
 * The CDN families the bundled server list fronts through, with the edge
 * addresses and `Host:` names that already serve them. [probe] answers one
 * edge and [presets] feeds both the scanner screen and the engine's own
 * candidate list.
 */
object SunAndLionScanner {
    private const val PROBE_TIMEOUT_MS = 3500

    private data class Cdn(
        val id: String,
        val snis: List<String>,
        val ranges: List<String>,
        val anchors: List<String>,
        /**
         * `Host:` values to try with each SNI. The first is the fronting host the
         * bundled server entries already use; the rest are domains known to sit on
         * this family, which is what the reference client dials with. A fronted
         * request is only indistinguishable while the TLS name and the HTTP name
         * differ, so this list is deliberately not a copy of [snis].
         */
        val hosts: List<String>,
    )

    private val cdns = listOf(
        Cdn(
            id = "akamai",
            snis = listOf(
                "a.akamaized-staging.net", "a.akamaized.net",
                "a.akamaihd-staging.net", "a.akamaihd.net",
                "a248.e.akamai.net", "www.akamai.com",
                "alchzwwfzmrtgoaf-a.akamaihd.net",
                "yryirjrhqffsxwpg-a.akamaihd.net"
            ),
            ranges = listOf(
                "104.97.85.0/24", "130.14.29.0/24", "165.112.7.0/24",
                "23.215.0.0/24", "23.48.203.0/24", "23.62.230.0/24",
                "23.12.147.0/24", "23.73.207.0/24", "23.212.250.0/24",
                "92.123.102.0/24", "23.43.0.0/24", "23.58.0.0/24"
            ),
            anchors = listOf(
                "92.123.102.43", "23.12.147.13", "23.12.147.29",
                "23.73.207.8", "23.73.207.15", "23.212.250.78",
                "23.212.250.91", "23.215.0.203", "23.215.0.206"
            ),
            hosts = listOf(
                "www.emdealsfund.com", "aparat.com", "www.aparat.com",
                "snapp.ir", "www.snapp.ir", "microsoft.com"
            )
        ),
        Cdn(
            id = "fastly",
            snis = listOf(
                "prod.global.ssl.fastly.net", "quic.map.fastly.net",
                "www.fastly.com", "developer.mozilla.org"
            ),
            ranges = listOf(
                "151.101.0.0/16", "199.232.0.0/16", "146.75.0.0/16",
                "167.82.0.0/16", "140.248.0.0/16", "23.235.32.0/19",
                "104.156.80.0/20", "157.52.64.0/18"
            ),
            anchors = listOf("151.101.1.69", "151.101.65.69"),
            hosts = listOf("www.hbadventureamericafitness.com", "www.fastly.com")
        ),
        Cdn(
            id = "cloudflare",
            snis = listOf(
                "cdnjs.cloudflare.com", "cloudflare.com", "www.cloudflare.com"
            ),
            ranges = listOf(
                "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13",
                "173.245.48.0/20", "188.114.96.0/20", "190.93.240.0/20",
                "197.234.240.0/22", "198.41.128.0/17",
                "104.17.0.0/20", "104.18.0.0/20", "104.19.0.0/20",
                "104.21.0.0/20", "104.22.0.0/20", "172.67.0.0/16",
                "162.158.0.0/15", "172.69.0.0/16", "172.70.0.0/16",
                "172.71.0.0/16"
            ),
            anchors = listOf("104.18.168.46", "200.102.177.66", "200.102.177.67"),
            hosts = listOf(
                "filer-opers-tabyte.psiphon3.net", "www.cloudflare.com", "cloudflare.com"
            )
        ),
    )

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?
                    ) = Unit

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?
                    ) = Unit

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }),
                SecureRandom()
            )
        }
    }

    /**
     * The families the bundled server list fronts through. The scanner screen
     * offers them as presets, so a manual list is only needed for a new CDN.
     */
    fun presets(): List<EdgePreset> =
        cdns.map { EdgePreset(it.id, it.ranges, it.snis, it.hosts, it.anchors) }

    internal fun probe(
        ip: String,
        sni: String,
        host: String,
        timeoutMs: Int = PROBE_TIMEOUT_MS
    ): Int? {
        val started = SystemClock.elapsedRealtime()
        var raw: Socket? = null
        var tls: SSLSocket? = null
        return try {
            raw = Socket()
            raw.connect(InetSocketAddress(ip, 443), timeoutMs)
            raw.soTimeout = timeoutMs
            raw.tcpNoDelay = true

            tls = sslContext.socketFactory.createSocket(raw, sni, 443, true) as SSLSocket
            tls.soTimeout = timeoutMs
            tls.startHandshake()

            val body = "HEAD / HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36\r\n" +
                "Accept: */*\r\nConnection: close\r\n\r\n"
            tls.outputStream.apply {
                write(body.toByteArray(Charsets.ISO_8859_1))
                flush()
            }

            val buffer = ByteArray(64)
            val count = tls.inputStream.read(buffer)
            if (count <= 0) return null
            val head = String(buffer, 0, count, Charsets.ISO_8859_1)
            if (!head.startsWith("HTTP/")) return null
            (SystemClock.elapsedRealtime() - started).toInt()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { tls?.close() }
            runCatching { raw?.close() }
        }
    }
}
