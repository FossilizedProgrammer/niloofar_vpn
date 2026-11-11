package com.multicore.vpn.tunnel

/**
 * JNI bridge to the userspace tunnel in `libc2tun.so` (hev-socks5-tunnel).
 *
 * The package is dictated by the prebuilt library, not by this app: its
 * `JNI_OnLoad` looks up `com/multicore/vpn/tunnel/Tun2Socks` and registers the
 * four natives below on it. When the class is missing, `FindClass` leaves a
 * `ClassNotFoundException` pending and ART aborts the process with SIGABRT
 * while the library loads, so this class must keep this exact name (see the
 * `-keep` rule in proguard-rules.pro).
 */
object Tun2Socks {
    init {
        System.loadLibrary("c2tun")
    }

    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    /** Byte counters of the running tunnel; registered by JNI_OnLoad, unused here. */
    external fun TProxyGetStats(): LongArray
}
