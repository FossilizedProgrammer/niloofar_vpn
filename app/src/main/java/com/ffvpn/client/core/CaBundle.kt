package com.ffvpn.client.core

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Root certificates shipped with the app.
 *
 * Old Android images freeze their system store at build time, so the Fastly
 * edges behind the proxy chain to roots the phone does not know about; the
 * failure looks exactly like a censored line. Go adds `SSL_CERT_FILE` to the
 * roots it reads, never replaces them, so handing it to the engine can only
 * help. Sockets the app dials itself get [socketFactory].
 */
object CaBundle {
    private const val ASSET = "ca_roots.pem"
    private const val FILE = "ca_roots.pem"
    private const val MIN_BYTES = 1024L
    private const val ENV = "SSL_CERT_FILE"

    @Volatile
    private var onDisk: File? = null

    private var factory: SSLSocketFactory? = null

    fun prepare(context: Context): Boolean {
        val target = File(File(context.filesDir, "tls"), FILE)
        if (target.length() <= MIN_BYTES) {
            runCatching {
                target.parentFile?.mkdirs()
                context.assets.open(ASSET).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        if (target.length() > MIN_BYTES) {
            onDisk = target
            runCatching { android.system.Os.setenv(ENV, target.absolutePath, true) }
        }
        return onDisk != null
    }

    /** Absolute path of the extracted bundle, or "none" before [prepare]. */
    fun path(): String = onDisk?.absolutePath ?: "none"

    /** Environment for an engine process. */
    fun env(): Map<String, String> =
        onDisk?.let { mapOf(ENV to it.absolutePath) } ?: emptyMap()

    fun applyTo(builder: ProcessBuilder) {
        env().forEach { (key, value) -> builder.environment()[key] = value }
    }

    /** The Kotlin side's trust store, for connections that verify. */
    fun applyTo(connection: HttpsURLConnection) {
        connection.sslSocketFactory = socketFactory()
    }

    fun socketFactory(): SSLSocketFactory =
        factory ?: runCatching { fromBundle() }
            .recoverCatching { SSLSocketFactory.getDefault() as SSLSocketFactory }
            .getOrThrow()
            .also { factory = it }

    private fun fromBundle(): SSLSocketFactory {
        val source = onDisk ?: throw IllegalStateException("no ca bundle on disk")
        val anchors = CertificateFactory.getInstance("X.509").generateCertificates(source.inputStream())
        val keys = KeyStore.getInstance(KeyStore.getDefaultType())
        keys.load(null, null)
        anchors.forEachIndexed { index, cert -> keys.setCertificateEntry("root$index", cert) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(keys)
        return SSLContext.getInstance("TLS")
            .apply { init(null, trust.trustManagers, null) }
            .socketFactory
    }
}
