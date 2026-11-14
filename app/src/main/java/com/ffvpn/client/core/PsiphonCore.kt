package com.ffvpn.client.core

import android.content.Context
import android.os.Build
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.PsiphonNative
import com.multicore.vpn.tunnel.Tun2Socks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine for networks where filtering still lets a tunnel through. The bundled
 * C library discovers reachable servers and serves a local SOCKS5 proxy; TUN
 * traffic is handed to it by the userspace tunnel bridge.
 */
class PsiphonCore : VpnCore {
    override val id = "psiphon"
    override val proxyAddress get() = Prefs.socksAddress

    @Volatile
    private var context: Context? = null

    @Volatile
    private var listener: ((Int, String, String) -> Unit)? = null

    @Volatile
    private var stopping = true

    @Volatile
    private var vpnStarted = false

    @Volatile
    private var connected = false

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var embedded: String? = null

    /**
     * Bumped by every connect and every disconnect. A native start cannot be
     * cancelled, so it can return a tunnel for a session that is already gone -
     * and `stopping` alone does not show that, because the next attempt clears it.
     */
    private val epoch = AtomicInteger(0)

    override fun init(context: Context) {
        val app = context.applicationContext
        this.context = app
        PsiphonNative.init(app)
        workDir.mkdirs()
        AppState.log("engine $id official AAR abi=${Build.SUPPORTED_ABIS.firstOrNull()}")
    }

    /**
     * The engine's Go runtime reserves roughly a sixth of the address space of a
     * 32-bit device, so its library is mapped only once the core is actually used:
     * a session that dials neither Psiphon nor Sun and Lion must not pay for either.
     */
    private fun ensureLibrary(): Boolean {
        if (!libraryLoaded) {
            libraryLoaded = runCatching { PsiphonNative.load() }
                .onFailure { AppState.log("engine $id library: ${it.message ?: it.javaClass.simpleName}") }
                .getOrDefault(false)
        }
        return libraryLoaded
    }

    override fun setSocketProtector(protector: ((Int) -> Boolean)?) = Unit

    override fun setStatusListener(listener: ((Int, String, String) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(): String? {
        if (!ensureLibrary()) return Texts.engineMissing
        workDir.mkdirs()
        stopVpn()
        stopping = false
        connected = false
        val mine = epoch.incrementAndGet()
        val bootstrap = PsiphonEngineData.needsServerDatabase(workDir)
        if (bootstrap) {
            AppState.log("engine $id first run: downloading the server list before dialling")
            emit(1002, Texts.STAGE_SERVER_DATABASE, "")
        } else {
            emit(1002, "connecting", "")
        }
        val timeoutSeconds =
            if (bootstrap) BOOTSTRAP_TIMEOUT_SECONDS else (ATTEMPT_TIMEOUT_MS / 1000L).toInt()

        val raw = try {
            PsiphonNative.exclusive {
                if (stopping || epoch.get() != mine) return null
                val started = PsiphonNative.start(
                    config().toString(),
                    embeddedEntries(),
                    workDir.absolutePath,
                    clientPlatform(),
                    null,
                    timeoutSeconds
                )
                // Checked inside the gate: the next attempt is queued on it, so a
                // tunnel this session no longer owns has to be dropped before that
                // attempt gets its turn.
                if (stopping || epoch.get() != mine) {
                    runCatching { PsiphonNative.stop() }
                    return null
                }
                started
            }
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            AppState.log("engine $id start threw: $message")
            runCatching { PsiphonNative.stop() }
            return message
        }
        // The gate is released, so a disconnect and the next attempt can both have
        // landed while this thread was descheduled. Without the epoch the error
        // branch below would stop the tunnel that attempt is already using.
        if (stopping || epoch.get() != mine) {
            runCatching { PsiphonNative.stop() }
            return null
        }

        val result = runCatching { JSONObject(raw) }.getOrNull()
        return when (result?.optInt("Code", -1)) {
            0 -> {
                // The listener belongs to whichever session the service installed
                // last, so a late success must not announce itself as that one's.
                if (stopping || epoch.get() != mine) {
                    runCatching { PsiphonNative.stop() }
                    return null
                }
                connected = true
                AppState.log(
                    "engine $id tunnel ready in ${result.optLong("ConnectTimeMS")}ms " +
                        "socks=${result.optInt("SOCKSProxyPort", Prefs.socksPort)}"
                )
                emit(1004, "ready", "")
                null
            }
            1 -> {
                AppState.log("engine $id no tunnel within ${timeoutSeconds}s")
                Texts.endpointNotFound
            }
            else -> {
                val detail = result?.optString("Error").orEmpty()
                    .ifBlank { raw.ifBlank { "start failed" } }
                AppState.log("engine $id start error: $detail")
                runCatching { PsiphonNative.stop() }
                detail
            }
        }
    }

    override fun disconnect() {
        stopping = true
        epoch.incrementAndGet()
        connected = false
        stopVpn()
        // Serialised with the start: a stop cannot cancel an in-flight one, so it
        // waits for the start to return. The service lets that wait happen on a
        // worker instead of holding the connection ring on "disconnecting".
        if (libraryLoaded) {
            runCatching { PsiphonNative.stop() }
                .onFailure { AppState.log("engine $id stop: ${it.message}") }
        }
    }

    override fun startVpn(fd: Int): String? {
        val config = writeTunnelConfig()
        val started = try {
            Tun2Socks.TProxyStartService(config.absolutePath, fd)
        } catch (t: Throwable) {
            AppState.log("engine $id tunnel bridge: ${t.message ?: t.javaClass.simpleName}")
            false
        }
        if (!started) {
            return Texts.tunnelFailed
        }
        vpnStarted = true
        repeat(15) {
            Thread.sleep(100L)
            if (runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)) {
                AppState.log("engine $id tunnel bridge started")
                return null
            }
        }
        stopVpn()
        return Texts.tunnelFailed
    }

    override fun stopVpn() {
        if (!vpnStarted) return
        vpnStarted = false
        runCatching { Tun2Socks.TProxyStopService() }
            .onFailure { AppState.log("engine $id tunnel stop: ${it.message}") }
    }

    override fun onNetworkTransition() {
        if (!connected || stopping) return
        Thread({
            PsiphonNative.exclusive {
                if (!connected || stopping) return@exclusive
                val mine = epoch.get()
                AppState.log("engine $id network changed, reconnecting")
                connected = false
                emit(1002, "connecting", "")
                runCatching { PsiphonNative.stop() }
                val raw = runCatching {
                    PsiphonNative.start(
                        config().toString(),
                        embeddedEntries(),
                        workDir.absolutePath,
                        clientPlatform(),
                        null,
                        RECONNECT_TIMEOUT_SECONDS
                    )
                }.getOrElse {
                    AppState.log("engine $id reconnect threw: ${it.message}")
                    null
                }
                if (stopping || epoch.get() != mine) {
                    runCatching { PsiphonNative.stop() }
                    return@exclusive
                }
                val result = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (result?.optInt("Code", -1) == 0) {
                    if (stopping || epoch.get() != mine) {
                        runCatching { PsiphonNative.stop() }
                        return@exclusive
                    }
                    connected = true
                    AppState.log("engine $id tunnel ready after network change")
                    emit(1004, "ready", "")
                } else {
                    AppState.log(
                        "engine $id reconnect failed: " +
                            (result?.optString("Error").orEmpty().ifBlank { raw.orEmpty() })
                    )
                    emit(2106, "reconnect failed", raw.orEmpty())
                }
            }
        }, "psiphon-reconnect").apply { isDaemon = true }.start()
    }

    override fun isRunning(): Boolean = connected || vpnStarted

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    private fun clientPlatform(): String {
        val version = Build.VERSION.RELEASE.ifBlank { "unknown" }
        return "Android_${version}_${appContext.packageName}"
    }

    private fun config(): JSONObject = JSONObject().apply {
        put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
        put("SponsorId", SPONSOR_ID)
        put("RemoteServerListUrl", SERVER_LIST_URL)
        put("RemoteServerListSignaturePublicKey", SERVER_LIST_PUBLIC_KEY)
        put("LocalSocksProxyPort", Prefs.socksPort)
        put("DataRootDirectory", workDir.absolutePath)
        put("ConnectionWorkerPoolSize", 4)
        put("TunnelPoolSize", 1)
        put("EmitDiagnosticNotices", true)
        // The same shape the reference client uses, which is what connects in
        // seconds on these links: one tunnel, no whole-device flag, and a real
        // establishment window instead of an open-ended one.
        put("UseIndistinguishableTLS", true)
        put("TunnelWholeDevice", false)
        put("EstablishTunnelTimeoutSeconds", 120)
        put("EmitBytesTransferred", true)
        if (Prefs.psiphonConduit) applyConduit() else applyDirect()
    }

    /**
     * Direct: Psiphon's own servers. Tactics are skipped and establishment is
     * aggressive exactly as in the reference client, instead of waiting on a
     * tactics round trip that a filtered link may never complete.
     */
    private fun JSONObject.applyDirect() {
        put("DisableTactics", true)
        put("AggressiveEstablishment", true)
    }

    /**
     * Conduit: the engine's WebRTC inproxy client, reaching the network through
     * volunteer relays instead of Psiphon's own entry points. The protocol list
     * is narrowed to the inproxy transports, which is what makes the engine take
     * that path at all; without it the same servers are tried first and the
     * setting would change nothing on a link that blocks them.
     */
    private fun JSONObject.applyConduit() {
        put("ClientVersion", CONDUIT_CLIENT_VERSION)
        // Same as the desktop client that works on these links: the inproxy
        // client must name its region, or broker selection falls back to the
        // generic pool that the filter already knows.
        put("DeviceRegion", DEVICE_REGION)
        // Explicitly a JSON array: the engine's Config field is []string and
        // rejects anything it reads as a single string.
        val protocols = JSONArray()
        CONDUIT_PROTOCOLS.forEach { protocols.put(it) }
        put("LimitTunnelProtocols", protocols)
        put("InproxyEnabled", true)
        put("InproxyAllowClient", true)
        put("DisableTactics", false)
        put("AggressiveEstablishment", false)
        put("UseIndistinguishableTLS", true)
        put("ServerEntrySignaturePublicKey", SERVER_ENTRY_PUBLIC_KEY)
        AppState.log("engine $id conduit: inproxy client enabled, ${CONDUIT_PROTOCOLS.size} protocols")
    }

    /**
     * The signed bootstrap list, in the encoded server entry form the engine
     * imports. Handing it over directly is what lets the first attempt dial
     * servers at all: the remote list download runs in parallel and takes tens
     * of seconds, and until it lands the engine has no candidates.
     */
    private fun embeddedEntries(): String {
        embedded?.let { return it }
        val text = runCatching {
            appContext.assets.open(EMBEDDED_ENTRIES).bufferedReader().use { it.readText() }
        }.onFailure {
            AppState.log("engine $id embedded entries: ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull().orEmpty()
        if (text.isNotBlank()) {
            embedded = text
            AppState.log("engine $id embedded entries ${text.count { c -> c == '\n' }} servers")
        }
        return text
    }

    private fun writeTunnelConfig(): File {
        val file = File(workDir, TUNNEL_FILE)
        file.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 172.19.0.2
              ipv6: 'fd00::2'
              icmp: 'reply'
            socks5:
              address: $LOOPBACK
              port: ${Prefs.socksPort}
              udp: 'udp'
            mapdns:
              address: $MAPPED_DNS
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              task-stack-size: 32768
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    // The engine's local proxy is TCP-only, so DNS is answered from the
    // bridge's mapped cache and resolved by the exit server on CONNECT.
    override val vpnDnsServers: List<String> = listOf(MAPPED_DNS)

    private val appContext: Context
        get() = context ?: error("PsiphonCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "psiphon")


    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TUNNEL_FILE = "tunnel.yml"
        const val EMBEDDED_ENTRIES = "psiphon_embedded_entries.txt"
        const val SERVER_LIST_URL =
            "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/server_list_compressed"
        const val SERVER_LIST_PUBLIC_KEY =
            "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
        const val CONDUIT_CLIENT_VERSION = "45"
        const val DEVICE_REGION = "IR"

        /** The inproxy transports, in the order the reference client lists them. */
        val CONDUIT_PROTOCOLS = listOf(
            "INPROXY-WEBRTC-SSH",
            "INPROXY-WEBRTC-OSSH",
            "INPROXY-WEBRTC-TLS-OSSH",
            "INPROXY-WEBRTC-UNFRONTED-MEEK-OSSH",
            "INPROXY-WEBRTC-UNFRONTED-MEEK-HTTPS-OSSH",
            "INPROXY-WEBRTC-UNFRONTED-MEEK-SESSION-TICKET-OSSH",
            "INPROXY-WEBRTC-FRONTED-MEEK-OSSH",
            "INPROXY-WEBRTC-FRONTED-MEEK-HTTP-OSSH",
            "INPROXY-WEBRTC-QUIC-OSSH",
            "INPROXY-WEBRTC-FRONTED-MEEK-QUIC-OSSH",
            "INPROXY-WEBRTC-SHADOWSOCKS-OSSH"
        )

        const val SERVER_ENTRY_PUBLIC_KEY = "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="

        const val PROPAGATION_CHANNEL_ID = "92AACC5BABE0944C"
        const val SPONSOR_ID = "1BC527D3D09985CF"
        const val ATTEMPT_TIMEOUT_MS = 120_000L
        const val BOOTSTRAP_TIMEOUT_SECONDS = 300
        const val RECONNECT_TIMEOUT_SECONDS = 90
    }
}
