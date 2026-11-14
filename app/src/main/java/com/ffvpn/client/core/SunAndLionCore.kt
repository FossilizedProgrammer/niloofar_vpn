package com.ffvpn.client.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.SunAndLionNative
import com.multicore.vpn.tunnel.Tun2Socks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * Filtering-resistant engine. Before connecting it discovers working SNIs and
 * CDN edge addresses in the background, then dials fronted meek through those
 * candidates. A saved route is tried for a short window first; when it no
 * longer works the scan runs again and replaces it.
 */
class SunAndLionCore : VpnCore {
    override val id = "sunlion"
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

    private lateinit var store: SunAndLionStore

    /**
     * Bumped by every connect and every disconnect. A native start cannot be
     * cancelled, so it can return a tunnel for a session that is already gone -
     * and `stopping` alone does not show that, because the next attempt clears it.
     */
    private val epoch = AtomicInteger(0)

    override fun init(context: Context) {
        this.context = context.applicationContext
        store = SunAndLionStore(context.applicationContext)
        workDir.mkdirs()
        seedServerList()
        AppState.log("engine $id binary=${binary.exists()} abi=${Build.SUPPORTED_ABIS.firstOrNull()}")
    }

    /**
     * The engine's Go runtime reserves roughly a sixth of the address space of a
     * 32-bit device, so its library is mapped only once the core is actually used:
     * a session that dials neither Sun and Lion nor Psiphon must not pay for either.
     */
    private fun ensureLibrary(): Boolean {
        if (!libraryLoaded && binary.exists()) {
            libraryLoaded = runCatching { SunAndLionNative.load(binary.absolutePath) }
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
        seedServerList()
        stopVpn()
        stopping = false
        connected = false
        val mine = epoch.incrementAndGet()
        emit(1002, "connecting", "")
        SunAndLionNative.exclusive {
            if (stopping || epoch.get() != mine) return null
            return openTunnel(mine)
        }
    }

    private fun openTunnel(mine: Int): String? {
        val saved = store.load()
        if (saved != null && saved.ips.isNotEmpty()) {
            val result = attempt(saved, CACHED_BUDGET_SECONDS, mine)
            if (result is Attempt.Ok || result is Attempt.Stopped || stopping) {
                return finish(result)
            }
            AppState.log(
                "engine sunlion saved route failed (${result.describe()}), " +
                    "dialing the engine edge corpus"
            )
        }
        return finish(attempt(null, FULL_BUDGET_SECONDS, mine))
    }

    private fun attempt(candidates: SunAndLionCandidates?, budgetSeconds: Int, mine: Int): Attempt {
        if (stopping || epoch.get() != mine) return Attempt.Stopped
        val bootstrap = PsiphonEngineData.needsServerDatabase(workDir)
        val timeout = if (bootstrap) FULL_BUDGET_SECONDS else budgetSeconds
        if (candidates == null) {
            AppState.log("engine sunlion dialing with built-in candidate corpus")
        } else {
            AppState.log(
                "engine sunlion dialing ${candidates.ips.size} IP(s) x " +
                    "${candidates.snis.size} SNI(s), budget=${timeout}s"
            )
        }
        if (bootstrap) {
            AppState.log("engine $id first run: downloading the server list while dialling")
            emit(1002, Texts.STAGE_SERVER_DATABASE, "")
        }
        val noticesOffset = noticesLength()
        val raw = try {
            SunAndLionNative.start(
                config(candidates, timeout).toString(),
                "",
                workDir.absolutePath,
                clientPlatform(),
                null,
                timeout
            )
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            AppState.log("engine $id start threw: $message")
            runCatching { SunAndLionNative.stop() }
            return Attempt.Error(message)
        }
        // Still inside the gate: a tunnel this session no longer owns has to be
        // dropped before the next attempt gets its turn.
        if (stopping || epoch.get() != mine) {
            runCatching { SunAndLionNative.stop() }
            return Attempt.Stopped
        }

        val result = runCatching { JSONObject(raw) }.getOrNull()
        return when (result?.optInt("Code", -1)) {
            0 -> {
                // The listener belongs to whichever session the service installed
                // last, so a late success must not announce itself as that one's.
                if (stopping || epoch.get() != mine) {
                    runCatching { SunAndLionNative.stop() }
                    return Attempt.Stopped
                }
                connected = true
                AppState.log(
                    "engine $id tunnel ready in ${result.optLong("ConnectTimeMS")}ms " +
                        "socks=${result.optInt("SOCKSProxyPort", Prefs.socksPort)}"
                )
                recordRouteFromNotices(noticesOffset)
                emit(1004, "ready", "")
                Attempt.Ok
            }
            1 -> {
                AppState.log("engine $id attempt timed out after ${timeout}s")
                Attempt.Timeout
            }
            else -> {
                val detail = result?.optString("Error").orEmpty()
                    .ifBlank { raw.ifBlank { "start failed" } }
                AppState.log("engine $id start error: $detail")
                runCatching { SunAndLionNative.stop() }
                Attempt.Error(detail)
            }
        }
    }

    private fun finish(result: Attempt): String? = when (result) {
        Attempt.Ok, Attempt.Stopped -> null
        Attempt.Timeout -> Texts.endpointNotFound
        is Attempt.Error -> result.message
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
            runCatching { SunAndLionNative.stop() }
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
            SunAndLionNative.exclusive {
                if (!connected || stopping) return@exclusive
                val mine = epoch.get()
                AppState.log("engine $id network changed, reconnecting")
                connected = false
                emit(1002, "connecting", "")
                runCatching { SunAndLionNative.stop() }
                val error = openTunnel(mine)
                if (stopping || epoch.get() != mine) return@exclusive
                if (!error.isNullOrBlank()) {
                    AppState.log("engine $id reconnect failed: $error")
                    emit(2106, "reconnect failed", error)
                }
            }
        }, "sunlion-reconnect").apply { isDaemon = true }.start()
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

    private fun config(candidates: SunAndLionCandidates?, budgetSeconds: Int): JSONObject =
        JSONObject().apply {
            put("ClientVersion", "45")
            put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
            put("SponsorId", SPONSOR_ID)
            put("RemoteServerListUrl", SERVER_LIST_URL)
            put("RemoteServerListSignaturePublicKey", SERVER_LIST_PUBLIC_KEY)
            put("LocalSocksProxyPort", Prefs.socksPort)
            put("DataRootDirectory", workDir.absolutePath)
            put("UseIndistinguishableTLS", true)
            put("AggressiveEstablishment", true)
            put("DisableTactics", true)
            put("ConnectionWorkerPoolSize", 4)
            put("TunnelPoolSize", 1)
            put("EmitDiagnosticNotices", true)
            put("EstablishTunnelTimeoutSeconds", budgetSeconds)
            put("LimitTunnelProtocols", JSONArray().apply {
                put("FRONTED-MEEK-CDN-OSSH")
                put("FRONTED-MEEK-CDN-HTTP-OSSH")
                put("FRONTED-MEEK-CDN-QUIC-OSSH")
            })
            // The engine's own edge corpus always stays in the rotation: it walks
            // hundreds of IP/SNI pairs in Go with 30 workers and remembers what
            // worked on this network. Measured here, that corpus reached a tunnel
            // in 7s while the same attempt pinned to an unproven tool list burned
            // 90s before it got a turn.
            put("FrontedMeekCDNScanUseBuiltInSpec", true)
            if (candidates != null) {
                put("FrontedMeekCDNScanSpec", JSONObject().apply {
                    put("IPCandidates", JSONArray(candidates.ips))
                    put("SNIServerNames", JSONArray(candidates.snis))
                })
            }
            // Only an edge that carried a tunnel with a real SNI may pin the dial.
            // The third fronting parameter is the HTTP `Host:` the meek request
            // carries, deliberately a different name from the TLS SNI: without it
            // the handshake speaks as itself and a filter that watches only the SNI
            // sees the mismatch fronting was meant to hide. Measured here, pinning
            // an unproven list cost 4x to 8x the time to a tunnel, so a list from
            // the scanner only enters the engine's own candidate rotation.
            val route = store.lastRoute()
            if (route != null && route.second.isNotBlank()) {
                val host = candidates?.hosts?.firstOrNull().orEmpty()
                put("FrontedMeekDialOverridesProbability", 1.0)
                put("FrontedMeekDialOverrides", JSONArray().apply {
                    put(JSONObject().apply {
                        put("OverrideID", "app-fronting")
                        put("MatchDialAddressRegexes", JSONArray(listOf(".*")))
                        put(
                            "DialAddresses",
                            JSONArray(
                                listOf(route.first) +
                                    (candidates?.ips ?: emptyList()).filter { it != route.first }.take(4)
                            )
                        )
                        put("SNIServerName", route.second)
                        put(
                            "VerifyServerNames",
                            JSONArray(listOf(route.second, host).filter { it.isNotBlank() }.distinct())
                        )
                        put("ALPNProtocols", JSONArray(listOf("h2", "http/1.1")))
                        put("TLSProfile", "Chrome-83")
                    })
                })
                AppState.log(
                    "engine $id pinning the last route ${route.first} with SNI ${route.second}, " +
                        "host ${host.ifBlank { "the entry's own" }}"
                )
            }
        }

    private fun seedServerList() {
        val target = File(workDir, SERVER_LIST_FILE)
        if (target.exists() && target.length() > 0) return
        runCatching {
            appContext.assets.open(SERVER_LIST_ASSET).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AppState.log("engine $id seeded server list ${target.length()} bytes")
        }.onFailure {
            AppState.log("engine $id server list seed failed: ${it.message}")
        }
    }

    private fun noticesFile(): File =
        File(workDir, "$PSIPHON_DATA_DIR/notices")

    private fun noticesLength(): Long =
        runCatching { noticesFile().length() }.getOrDefault(0L)

    /**
     * Reads the engine's diagnostic notices written since [offset] and keeps
     * the last fronted route that actually carried a tunnel. The notice file
     * writer flushes asynchronously, so the read is retried briefly.
     */
    private fun recordRouteFromNotices(offset: Long) {
        val file = noticesFile()
        if (!file.exists()) return
        var match: MatchResult? = null
        repeat(4) { attempt ->
            if (match == null) {
                val text = runCatching { readNoticesSince(file, offset) }.getOrDefault("")
                match = ROUTE_NOTICE.findAll(text).lastOrNull()
                if (match == null && attempt < 3) Thread.sleep(400L)
            }
        }
        val found = match ?: return
        val ip = found.groupValues[1].replace("\\", "")
        // The engine names the edge it actually used; "none" means the winning
        // dial omitted the SNI extension, which the override spells as "".
        val sni = found.groupValues[2].replace("\\", "").let { if (it == "none") "" else it }
        if (ip.isBlank()) return
        store.recordSuccess(ip, sni)
        AppState.log("engine $id saved route ip=$ip sni=${sni.ifBlank { "none" }}")
    }

    private fun readNoticesSince(file: File, offset: Long): String {
        val length = file.length()
        if (length <= offset) return ""
        val windowStart = maxOf(offset, length - 256 * 1024)
        return RandomAccessFile(file, "r").use { reader ->
            reader.seek(windowStart)
            val size = (length - windowStart).toInt().coerceAtLeast(0)
            val bytes = ByteArray(size)
            reader.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
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

    private sealed class Attempt {
        object Ok : Attempt()
        object Timeout : Attempt()
        object Stopped : Attempt()
        data class Error(val message: String) : Attempt()

        fun describe(): String = when (this) {
            Ok -> "ok"
            Timeout -> "timed out"
            Stopped -> "stopped"
            is Error -> message
        }
    }

    private val appContext: Context
        get() = context ?: error("SunAndLionCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "sunlion")

    private val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libsunlion.so")

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAPPED_DNS = "9.9.9.9"
        const val TUNNEL_FILE = "tunnel.yml"
        const val SERVER_LIST_FILE = "remote_server_list"
        const val SERVER_LIST_ASSET = "psiphon_servers.bin"
        const val PSIPHON_DATA_DIR = "ca.psiphon.PsiphonTunnel.tunnel-core"
        const val SERVER_LIST_URL =
            "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/server_list_compressed"
        const val SERVER_LIST_PUBLIC_KEY =
            "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
        const val PROPAGATION_CHANNEL_ID = "92AACC5BABE0944C"
        const val SPONSOR_ID = "1BC527D3D09985CF"
        const val CACHED_BUDGET_SECONDS = 120
        const val FULL_BUDGET_SECONDS = 300
        val ROUTE_NOTICE = Regex(
            """cdn fronting scan found \(ip:\s*([^,\s)]+),\s*sni:\s*([^)\s]+)\)"""
        )
    }
}
