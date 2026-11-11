package com.ffvpn.client.vpn

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import com.ffvpn.client.R
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File

/**
 * Owns the session: asks the engine for a working exit, opens the TUN, hands it
 * to the tun2socks bridge, and watches the engine so a dead tunnel is rebuilt
 * or reported instead of leaving the VPN icon up over a black hole.
 */
class FirefoxVpnService : VpnService() {
    @Volatile
    private var engine: FirefoxEngine? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    /** Duplicate handed to the bridge; kept so the service can always release it. */
    @Volatile
    private var tunCore: ParcelFileDescriptor? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var monitor: Thread? = null

    @Volatile
    private var worker: Thread? = null

    private var drops = 0

    private var sessionMode = Mode.Vpn
    private var sessionShare = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ffvpn.client.AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        Notifications.ensureChannel(this)
        VpnState.syncAccount(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> startSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (worker?.isAlive == true) return
        stopping = false
        drops = 0
        sessionMode = Store.mode(this)
        sessionShare = Store.share(this)
        Logs.add("vpn", "connecting (${sessionMode.name.lowercase()})")
        notifyForeground(getString(R.string.notification_connecting))
        VpnState.update {
            it.copy(
                phase = Phase.Connecting,
                step = "",
                detail = "",
                country = "",
                latencyMs = 0,
                connectedAt = 0L
            )
        }
        worker = Thread({ runSession() }, "vpn-session").apply { start() }
    }

    /** Connects the engine, then brings the interface up on top of it. */
    private fun runSession() {
        val core = FirefoxEngine(this)
        engine = core
        val error = core.connect(sessionShare) { step, arg ->
            if (stopping) return@connect
            val text = if (arg != null) getString(step.label, arg) else getString(step.label)
            VpnState.update { it.copy(phase = Phase.Connecting, step = text, detail = "") }
            notifyForeground(text)
        }
        if (stopping) return
        if (error != null) {
            fail(error)
            return
        }
        // Proxy mode deliberately leaves the system VPN alone: apps opt in by
        // pointing at the local proxy, and sharing exposes it to the LAN.
        if (sessionMode == Mode.Vpn && !openTunnel()) return

        VpnState.update {
            it.copy(
                phase = Phase.On,
                step = "",
                detail = "",
                connectedAt = SystemClock.elapsedRealtime(),
                latencyMs = core.selected?.latencyMs ?: 0,
                country = core.selected?.server?.label.orEmpty(),
                quota = Store.quota(this).orEmpty(),
                mode = sessionMode,
                share = sessionShare,
                address = if (sessionShare) NetInfo.shareAddress().orEmpty() else ""
            )
        }
        notifyForeground(
            getString(
                if (sessionMode == Mode.Proxy) R.string.notification_proxy
                else R.string.notification_connected
            )
        )
        Log.i(TAG, "tunnel up")
        Logs.add("vpn", "tunnel up")
        watch(core)
    }

    /**
     * Wakes up when the engine dies, so a dropped exit is not left unnoticed,
     * and keeps the session honest: the engine process can stay up while the
     * exit behind it is gone, so the local proxy is asked for a connection now
     * and then and a session that stops answering is rebuilt.
     */
    private fun watch(core: FirefoxEngine) {
        monitor = Thread {
            var sinceProbe = 0
            var misses = 0
            while (!stopping) {
                if (!core.isAlive()) {
                    onDrop()
                    return@Thread
                }
                sinceProbe++
                if (sinceProbe >= HEALTH_EVERY_TICKS) {
                    sinceProbe = 0
                    if (stopping) return@Thread
                    if (Traffic.serves(SOCKS_PORT, HEALTH_TIMEOUT_MS)) {
                        if (misses > 0) Logs.add("vpn", "keep-alive probe answered again")
                        misses = 0
                    } else {
                        misses++
                        Logs.add("vpn", "keep-alive probe failed ($misses/$HEALTH_MAX_MISSES)")
                        if (misses >= HEALTH_MAX_MISSES) {
                            Logs.add("vpn", "the exit stopped answering; rebuilding the session")
                            onDrop()
                            return@Thread
                        }
                    }
                }
                try {
                    Thread.sleep(WATCH_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; name = "vpn-watch"; start() }
    }

    private fun onDrop() {
        if (stopping) return
        drops += 1
        Log.w(TAG, "engine exited (drop $drops)")
        Logs.add("vpn", "session dropped ($drops/$MAX_DROPS)")
        closeTunnel()
        engine?.stop()
        if (drops > MAX_DROPS) {
            fail(getString(R.string.error_dropped))
            return
        }
        VpnState.update {
            it.copy(
                phase = Phase.Connecting,
                step = getString(R.string.step_reconnecting),
                connectedAt = 0L
            )
        }
        Thread.sleep(RECONNECT_DELAY_MS)
        if (!stopping) runSession()
    }

    private fun openTunnel(): Boolean {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress("172.19.0.2", 24)
            .addAddress("fd00::2", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("9.9.9.9")
            .setMtu(1500)
            .setBlocking(false)

        // The engine runs under this UID, so it has to stay outside the tunnel
        // or its own dials would loop back into the interface.
        runCatching { builder.addDisallowedApplication(packageName) }

        val descriptor = try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish failed: ${t.message}")
            null
        }
        if (descriptor == null) {
            fail(getString(R.string.error_tunnel_failed))
            return false
        }
        tun = descriptor

        val core = try {
            ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        } catch (t: Throwable) {
            Log.e(TAG, "dup failed: ${t.message}")
            null
        }
        if (core == null) {
            fail(getString(R.string.error_tunnel_failed))
            return false
        }
        runCatching { Os.fcntlInt(core.fileDescriptor, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC) }
        tunCore = core

        VpnState.update { it.copy(step = getString(R.string.step_opening_tunnel)) }
        val config = writeConfig()
        val started = runCatching { Tun2Socks.TProxyStartService(config.absolutePath, core.fd) }
            .getOrDefault(false)
        if (!started) {
            fail(getString(R.string.error_tunnel_failed))
            return false
        }
        repeat(15) {
            Thread.sleep(100L)
            if (runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)) return true
        }
        fail(getString(R.string.error_tunnel_failed))
        return false
    }

    /** hev-socks5-tunnel config; DNS is answered from the bridge's mapped cache. */
    private fun writeConfig(): File {
        val file = Store.configFile(this)
        file.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 172.19.0.2
              ipv6: 'fd00::2'
              icmp: 'reply'
            socks5:
              address: 127.0.0.1
              port: 1080
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

    private fun closeTunnel() {
        runCatching { Tun2Socks.TProxyStopService() }
        val core = tunCore
        tunCore = null
        // A engine may have closed the fd it was handed and the number can be
        // reused; only close it while it still points at the tunnel.
        if (core != null && isStillTun(core.fd)) runCatching { core.close() }
        runCatching { tun?.close() }
        tun = null
    }

    private fun isStillTun(fd: Int): Boolean =
        runCatching { Os.readlink("/proc/self/fd/$fd") }.getOrNull() == TUN_LINK

    private fun fail(message: String) {
        if (stopping) return
        stopping = true
        closeTunnel()
        engine?.stop()
        VpnState.update {
            it.copy(
                phase = Phase.Error,
                step = "",
                detail = message,
                connectedAt = 0L
            )
        }
        Log.w(TAG, "session failed: $message")
        Logs.add("vpn", "failed: $message")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        monitor?.interrupt()
        closeTunnel()
        engine?.stop()
        VpnState.update { VpnUi(signedIn = it.signedIn, email = it.email, quota = it.quota) }
        Log.i(TAG, "session stopped by user")
        Logs.add("vpn", "stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        stopping = true
        monitor?.interrupt()
        closeTunnel()
        engine?.stop()
    }

    private fun notifyForeground(text: String) {
        val notification = Notifications.build(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifications.ID, notification)
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(Notifications.ID, notification)
    }

    companion object {
        const val ACTION_START = "com.ffvpn.client.vpn.START"
        const val ACTION_STOP = "com.ffvpn.client.vpn.STOP"

        private const val TAG = "ffvpn"
        private const val TUN_LINK = "/dev/tun"
        private const val WATCH_MS = 1_000L
        private const val RECONNECT_DELAY_MS = 1_500L
        private const val MAX_DROPS = 2

        // The keep-alive probe: a minute between checks, two misses in a row
        // (about two minutes) before the exit is called dead.
        private const val HEALTH_EVERY_TICKS = 60
        private const val HEALTH_TIMEOUT_MS = 8_000
        private const val HEALTH_MAX_MISSES = 2
        private const val SOCKS_PORT = 1080

        /** Lets the UI notice a service the system killed underneath it. */
        @Volatile
        var alive = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FirefoxVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FirefoxVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
