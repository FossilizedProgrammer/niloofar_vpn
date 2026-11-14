package com.multicore.vpn.tunnel

/**
 * The JNI surface of the Sun and Lion engine.
 *
 * Deliberately private to this file: the only way in is [SunAndLionNative],
 * which serialises every call. The class name is part of the exported JNI
 * symbols (Java_com_multicore_vpn_tunnel_SunAndLionBridge_*), so it must not be
 * renamed or moved, and proguard-rules.pro keeps it.
 */
private object SunAndLionBridge {
    init {
        System.loadLibrary("sunlionbridge")
    }

    external fun nativeLoad(path: String): Boolean

    /**
     * Blocking. Returns the engine's JSON result: {"Code":0,...} on success,
     * {"Code":1,...} on timeout, {"Code":2,...} on error.
     */
    external fun nativeStart(
        config: String,
        embeddedServers: String,
        dataRoot: String,
        clientPlatform: String,
        networkId: String?,
        timeoutSeconds: Int
    ): String

    external fun nativeStop()
}

/**
 * The only caller of [SunAndLionBridge]. One instance process-wide, because the
 * library's state is process-wide: start, stop and load all share one gate.
 */
object SunAndLionNative {
    @PublishedApi
    internal val gate = NativeGate()

    fun load(path: String): Boolean = gate.call { SunAndLionBridge.nativeLoad(path) }

    fun start(
        config: String,
        embeddedServers: String,
        dataRoot: String,
        clientPlatform: String,
        networkId: String?,
        timeoutSeconds: Int
    ): String = gate.call {
        SunAndLionBridge.nativeStart(
            config,
            embeddedServers,
            dataRoot,
            clientPlatform,
            networkId,
            timeoutSeconds
        )
    }

    /**
     * Stops the established tunnel. A start that is still running cannot be
     * cancelled, so this waits for it: stopping underneath it would free the
     * buffer it is about to return and re-arm the library's single-start guard.
     */
    fun stop() = gate.call { SunAndLionBridge.nativeStop() }

    /** For sequences that must not interleave with another call (stop + start). */
    inline fun <T> exclusive(block: () -> T): T = gate.call(block)
}
