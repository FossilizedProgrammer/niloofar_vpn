package com.multicore.vpn.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.ffvpn.client.core.AppState
import org.json.JSONObject
import psi.Psi
import psi.PsiphonProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The official Psiphon engine, through the gomobile shim Psiphon ships in its
 * Android AAR ([psi.Psi] runs a [psiphon.Controller] directly).
 *
 * The engine this replaces was built on `ClientLibrary/clientlib`, whose
 * `StartTunnel` closes its "connected" channel from the notice callback every
 * time a tunnel registers:
 *
 * ```
 * } else if event.Type == "Tunnels" {
 *     if count > 0 { close(connectedSignal) }
 * }
 * ```
 *
 * The first tunnel closes it and the call returns; when that tunnel later dies
 * and the engine registers a replacement, the same notice closes it again and
 * the Go runtime aborts the process with `panic: close of closed channel`.
 * Nothing in Java can catch that. The controller API has no such channel, so a
 * replacement tunnel is just another notice here.
 */
object PsiphonNative {
    @PublishedApi
    internal val gate = NativeGate()

    @Volatile
    private var context: Context? = null

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Loads the AAR's Go library. The library is a normal Android JNI library, so
     * there is no path to hand over: the class initialiser of [Psi] loads it.
     */
    fun load(): Boolean = gate.call { ensureLoaded() }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = runCatching { Psi.touch() }
            .onFailure {
                AppState.log("engine psiphon library: ${it.message ?: it.javaClass.simpleName}")
            }
            .isSuccess
        return loaded
    }

    /**
     * Blocking. Returns the engine's JSON result: `{"Code":0,...}` once a tunnel
     * is up, `{"Code":1,...}` on timeout, `{"Code":2,...}` on error.
     */
    fun start(
        config: String,
        embeddedServers: String,
        dataRoot: String,
        clientPlatform: String,
        networkId: String?,
        timeoutSeconds: Int
    ): String = gate.call {
        if (!ensureLoaded()) return "{\"Code\":2,\"Error\":\"engine library not loaded\"}"

        val started = SystemClock.elapsedRealtime()
        val session = Session(started)
        val provider = Provider(context, networkId.orEmpty(), session::onNotice)
        val json = runCatching { JSONObject(config) }.getOrElse {
            return errorJson("bad config: ${it.message ?: it.javaClass.simpleName}")
        }
        json.put("ClientPlatform", clientPlatform)
        if (!networkId.isNullOrBlank()) json.put("NetworkID", networkId)
        json.put("DataRootDirectory", dataRoot)
        json.put("EstablishTunnelTimeoutSeconds", timeoutSeconds)

        try {
            // The second argument is the embedded bootstrap list. Handing it over
            // is what lets the first attempt dial at all: the remote list download
            // runs in parallel and takes tens of seconds.
            Psi.start(json.toString(), embeddedServers, "", provider, false, false, true)
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            AppState.log("engine psiphon start threw: $message")
            return errorJson(message)
        }
        AppState.log("engine psiphon started in ${SystemClock.elapsedRealtime() - started}ms")

        if (!session.connected.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            runCatching { Psi.stop() }
            return "{\"Code\":1,\"Error\":\"no tunnel within ${timeoutSeconds}s\"}"
        }
        session.error.get()?.let { message ->
            runCatching { Psi.stop() }
            return errorJson(message)
        }

        JSONObject()
            .put("Code", 0)
            .put("ConnectTimeMS", session.connectMillis)
            .put("SOCKSProxyPort", session.socksPort.get())
            .toString()
    }

    /** For sequences that must not interleave with another call (stop + start). */
    inline fun <T> exclusive(block: () -> T): T = gate.call(block)

    fun stop() = gate.call {
        if (loaded) {
            runCatching { Psi.stop() }
                .onFailure { AppState.log("engine psiphon stop: ${it.message}") }
        }
    }

    private fun errorJson(message: String): String =
        JSONObject().put("Code", 2).put("Error", message).toString()

    /** Folds the engine's notices into the one thing the caller waits for. */
    private class Session(private val started: Long) {
        val connected = CountDownLatch(1)
        val socksPort = AtomicInteger(0)
        val error = AtomicReference<String?>(null)
        var connectMillis = 0L
            private set

        fun onNotice(event: JSONObject) {
            val data = event.optJSONObject("data")
            when (event.optString("noticeType")) {
                "ListeningSocksProxyPort" -> {
                    socksPort.set(data?.optInt("port") ?: 0)
                    AppState.log("engine psiphon local socks port $socksPort")
                }
                "Tunnels" -> {
                    val count = data?.optInt("count") ?: 0
                    AppState.log("engine psiphon tunnels=$count")
                    if (count > 0 && connected.count > 0) {
                        connectMillis = SystemClock.elapsedRealtime() - started
                        connected.countDown()
                    }
                }
                "EstablishTunnelTimeout" -> {
                    error.compareAndSet(null, "engine reported tunnel timeout")
                    connected.countDown()
                }
                "Error" ->
                    AppState.log("engine psiphon error ${data?.optString("message")?.take(300).orEmpty()}")
            }
        }
    }

    /** What the engine asks the platform while it is running. */
    private class Provider(
        private val context: Context?,
        private val networkId: String,
        private val onNotice: (JSONObject) -> Unit
    ) : PsiphonProvider {
        override fun getNetworkID(): String = networkId.ifBlank { "UNKNOWN" }

        override fun hasIPv6Route(): Long = 0L

        override fun hasNetworkConnectivity(): Long = runCatching {
            val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
            val network = manager?.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            if (capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) 1L else 0L
        }.getOrDefault(1L)

        override fun iPv6Synthesize(iPv4Addr: String): String = iPv4Addr

        override fun bindToDevice(fileDescriptor: Long): String? = null

        override fun getDNSServersAsString(): String = runCatching {
            val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
            val network = manager?.activeNetwork
            val servers = network?.let { manager.getLinkProperties(it)?.dnsServers }
            servers.orEmpty().joinToString(",") { it.hostAddress.orEmpty() }
        }.getOrDefault("")

        override fun onAccessToken(token: String) = Unit

        override fun notice(noticeJSON: String) {
            val event = runCatching { JSONObject(noticeJSON) }.getOrNull() ?: return
            onNotice(event)
        }
    }
}
