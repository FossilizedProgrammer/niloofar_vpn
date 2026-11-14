package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Engine for open or lightly filtered networks. A bundled tunnel process scans
 * for a reachable route on its own and serves a local SOCKS5 proxy; TUN traffic
 * is handed to it by the userspace tunnel bridge.
 */
class AetherCore : VpnCore {
    override val id = "aether"
    override val proxyAddress get() = Prefs.socksAddress

    @Volatile
    private var context: Context? = null

    @Volatile
    private var listener: ((Int, String, String) -> Unit)? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopping = true

    @Volatile
    private var vpnStarted = false

    @Volatile
    private var lastLine = ""

    @Volatile
    private var serving = false

    override fun init(context: Context) {
        this.context = context.applicationContext
        workDir.mkdirs()
        AppState.log("engine $id binary=${binary.exists()} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
    }

    override fun setSocketProtector(protector: ((Int) -> Boolean)?) = Unit

    override fun setStatusListener(listener: ((Int, String, String) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(): String? {
        val executable = binary
        if (!executable.exists()) return Texts.engineMissing
        workDir.mkdirs()
        stopping = false
        stopVpn()

        emit(1002, "http2 scan", "")
        val first = runStage(executable, http2Args(), HTTP2_STAGE_MS)
        if (stopping) return null
        if (first == null) {
            emit(1004, "ready", "")
            return null
        }
        AppState.log("engine $id http2 stage: $first")

        emit(1002, "automatic scan", "")
        val second = runStage(executable, automaticArgs(), AUTO_STAGE_MS)
        if (stopping) return null
        if (second == null) {
            emit(1004, "ready", "")
            return null
        }
        AppState.log("engine $id automatic stage: $second")
        return Texts.endpointNotFound
    }

    override fun disconnect() {
        stopping = true
        stopVpn()
        destroyProcess()
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

    override fun isRunning(): Boolean {
        val alive = process?.isAlive == true
        return alive || (vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false))
    }

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    private fun runStage(executable: File, args: List<String>, timeoutMs: Long): String? {
        destroyProcess()
        serving = false
        val startedAt = SystemClock.elapsedRealtime()
        val child = try {
            ProcessBuilder(listOf(executable.absolutePath) + args)
                .directory(workDir)
                .redirectErrorStream(true)
                .also { CaBundle.applyTo(it) }
                .start()
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }
        process = child
        pumpLogs(child)
        while (!stopping) {
            if (!child.isAlive) {
                val code = runCatching { child.exitValue() }.getOrNull()
                return "process exited ($code) ${lastLine.take(160)}".trim()
            }
            if (serving) return null
            if (SystemClock.elapsedRealtime() - startedAt >= timeoutMs) {
                destroyProcess()
                return "scan timed out after ${timeoutMs / 1000}s"
            }
            try {
                Thread.sleep(300L)
            } catch (_: InterruptedException) {
                break
            }
        }
        destroyProcess()
        return null
    }

    private fun pumpLogs(child: Process) {
        EngineProc.attach(
            child,
            Thread {
                runCatching {
                    // Not `use`d: the stream is closed by EngineProc, once the
                    // child is dead and can no longer write into it.
                    val reader = child.inputStream.bufferedReader()
                    while (true) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        lastLine = line.trim()
                        if (lastLine.contains(SERVING_MARKER, ignoreCase = true)) {
                            serving = true
                        }
                        AppState.log("engine $id: $lastLine")
                    }
                }
            }.apply {
                isDaemon = true
                name = "aether-log"
                start()
            }
        )
    }

    private fun destroyProcess() {
        val child = process ?: return
        process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "aether")
        lastLine = ""
        serving = false
    }

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    private fun http2Args(): List<String> = commonArgs() + listOf("--masque", "--h2")

    /** Default carrier and scan, chosen automatically by the engine. */
    private fun automaticArgs(): List<String> = commonArgs() + listOf("--masque", "--h3")

    private fun commonArgs(): List<String> = listOf(
        "--bind", Prefs.socksAddress,
        "--config", File(workDir, IDENTITY_FILE).absolutePath,
        "--log-level", "info",
        "--scan", "balanced"
    )

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
            misc:
              task-stack-size: 32768
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    private val appContext: Context
        get() = context ?: error("AetherCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "aether")

    private val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libaether.so")

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val IDENTITY_FILE = "identity.toml"
        const val TUNNEL_FILE = "tunnel.yml"
        const val SERVING_MARKER = "socks5 server listening"
        const val HTTP2_STAGE_MS = 90_000L
        const val AUTO_STAGE_MS = 240_000L
    }
}
