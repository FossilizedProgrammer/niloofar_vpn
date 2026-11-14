package com.ffvpn.client.core

import android.os.SystemClock
import com.ffvpn.client.Texts
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * A handful of plain probes run after a failed attempt so the screen can name
 * the likely cause instead of only reporting that something went wrong. The
 * probes never carry user data and never trust a certificate: they ask whether
 * a handshake completes, which is what censorship breaks.
 */
object Diagnostics {
    data class Report(
        val dnsOk: Boolean,
        val tcp443Ok: Boolean,
        val tcp80Ok: Boolean,
        val tlsSniOk: Boolean,
        val tlsPlainOk: Boolean
    )

    @Volatile
    private var lastRunAt = 0L

    /** Runs the probes on the calling thread; null when they were skipped. */
    fun explain(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRunAt < COOLDOWN_MS) return null
        lastRunAt = now
        val report = runCatching { probe() }.getOrNull() ?: return null
        AppState.log(
            "diagnostics dns=${report.dnsOk} tcp443=${report.tcp443Ok} " +
                "tcp80=${report.tcp80Ok} sni=${report.tlsSniOk} plain=${report.tlsPlainOk}"
        )
        return Texts.diagnosis(report)
    }

    private fun probe() = Report(
        dnsOk = dns(),
        tcp443Ok = tcp(PEER, 443),
        tcp80Ok = tcp(PEER, 80),
        tlsSniOk = tls(withSni = true),
        tlsPlainOk = tls(withSni = false)
    )

    private fun dns(): Boolean {
        val resolved = AtomicBoolean(false)
        val worker = Thread {
            resolved.set(
                HOSTS.any { host ->
                    runCatching { InetAddress.getAllByName(host).isNotEmpty() }.getOrDefault(false)
                }
            )
        }
        worker.isDaemon = true
        worker.start()
        // A poisoned or black-holed resolver never answers at all, so the wait
        // itself is the measurement and the thread is left to finish on its own.
        runCatching { worker.join(DNS_TIMEOUT_MS) }
        return resolved.get()
    }

    private fun tcp(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    private fun tls(withSni: Boolean): Boolean = runCatching {
        val raw = Socket()
        raw.connect(InetSocketAddress(PEER, 443), TIMEOUT_MS)
        val socket = unverified().socketFactory
            .createSocket(raw, SNI_HOST, 443, true) as SSLSocket
        socket.use {
            if (!withSni) {
                it.sslParameters = it.sslParameters.apply { serverNames = emptyList() }
            }
            it.soTimeout = TIMEOUT_MS
            it.startHandshake()
            it.handshakeSession != null
        }
    }.getOrDefault(false)

    private fun unverified(): SSLContext {
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(manager), java.security.SecureRandom())
        }
    }

    private const val COOLDOWN_MS = 60_000L
    private const val TIMEOUT_MS = 2_500
    private const val DNS_TIMEOUT_MS = 3_000L
    private const val PEER = "1.1.1.1"
    private const val SNI_HOST = "cloudflare.com"
    private val HOSTS = listOf("www.google.com", "cloudflare.com")
}
