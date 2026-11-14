package com.ffvpn.client.core

import android.content.Context
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.Tun2Socks
import com.ffvpn.client.vpn.FirefoxEngine
import com.ffvpn.client.vpn.Store
import java.io.File

/**
 * Niloofar's own Firefox proxy engine, wrapped in the same contract as every
 * ported core. The account, country and quota screens stay in this app; only
 * the transport is shared with the multi-core runtime.
 */
class FirefoxCore : VpnCore {
    override val id = "firefox"
    override val proxyAddress get() = Prefs.socksAddress

    /**
     * The engine answers DNS from the bridge's mapped cache at 9.9.9.9, so that
     * is the only server the system may be told about while this core is up.
     */
    override val vpnDnsServers: List<String> get() = listOf("9.9.9.9")

    override val trafficProbeTimeoutMs: Int = 10_000

    @Volatile
    private var context: Context? = null

    @Volatile
    private var listener: ((Int, String, String) -> Unit)? = null

    @Volatile
    private var engine: FirefoxEngine? = null

    @Volatile
    private var stopping = true

    @Volatile
    private var vpnStarted = false

    override fun init(context: Context) {
        this.context = context.applicationContext
        Store.ensureAccounts(this.context!!)
        AppState.log("engine $id binary=${Store.binary(this.context!!).exists()}")
    }

    override fun setSocketProtector(protector: ((Int) -> Boolean)?) = Unit

    override fun setStatusListener(listener: ((Int, String, String) -> Unit)?) {
        this.listener = listener
    }

    /** No session means the user has to sign in before another attempt helps. */
    override val blockedOnUser: Boolean
        get() = context?.let { !Store.hasSession(it) } ?: false

    override fun connect(): String? {
        val ctx = appContext
        if (!Store.binary(ctx).exists()) return Texts.engineMissing
        stopping = false
        disconnect()
        stopping = false
        val child = FirefoxEngine(ctx)
        engine = child
        emit(1002, "connecting", "")
        val error = child.connect { stage -> if (!stopping) emit(1002, stage, "") }
        if (stopping) return null
        if (!error.isNullOrBlank()) return error
        val chosen = child.selected
        AppState.update {
            it.copy(
                exit = chosen?.server?.label.orEmpty(),
                latencyMs = chosen?.latencyMs ?: 0,
                quota = Store.quota(ctx).orEmpty()
            )
        }
        emit(1004, "ready", "")
        return null
    }

    override fun disconnect() {
        stopping = true
        engine?.stop()
        engine = null
        stopVpn()
    }

    override fun startVpn(fd: Int): String? {
        emit(1002, "opening tunnel", "")
        val config = writeTunnelConfig()
        val started = try {
            Tun2Socks.TProxyStartService(config.absolutePath, fd)
        } catch (t: Throwable) {
            AppState.log("engine $id tunnel bridge: ${t.message ?: t.javaClass.simpleName}")
            false
        }
        if (!started) return Texts.tunnelFailed
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

    override fun isRunning(): Boolean =
        engine?.isAlive() == true ||
            (vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false))

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    /** hev-socks5-tunnel config; DNS is answered from the bridge's mapped cache. */
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
              address: 9.9.9.9
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

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    private val appContext: Context
        get() = context ?: error("FirefoxCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "ffvpn")

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TUNNEL_FILE = "tunnel.yml"
    }
}
