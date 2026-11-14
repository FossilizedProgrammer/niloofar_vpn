package com.ffvpn.client.core

import android.content.Context

/**
 * One tunnel engine. New engines (sing-box, xray, wireguard, ...) only need to
 * implement this interface and register in [CoreRegistry].
 */
interface VpnCore {
    val id: String

    /** Address the core exposes locally after connect (SOCKS5). */
    val proxyAddress: String

    /**
     * DNS servers advertised to the system while this core carries the VPN.
     * Cores whose local proxy is TCP-only must advertise the single address
     * their tunnel bridge answers from its mapped-DNS cache.
     */
    val vpnDnsServers: List<String>
        get() = DEFAULT_VPN_DNS

    /**
     * How long a watchdog probe of [proxyAddress] may take before it counts as a
     * missed beat. Cores that build a fresh path for every probe — a circuit is
     * picked and opened only when the CONNECT arrives — need a wider margin than
     * cores that keep a warm tunnel ready.
     */
    val trafficProbeTimeoutMs: Int
        get() = 6_000

    /**
     * True while another attempt cannot possibly help because the user has to
     * act first, such as signing in to the account the core runs on. The retry
     * ladder gives up immediately instead of burning its whole budget.
     */
    val blockedOnUser: Boolean
        get() = false

    fun init(context: Context)

    /** Called by the VpnService so outbound sockets bypass the tun. */
    fun setSocketProtector(protector: ((Int) -> Boolean)?)

    /** code, message, data — same contract as the gomobile StatusListener. */
    fun setStatusListener(listener: ((Int, String, String) -> Unit)?)

    /** Blocking. Returns null/blank on success, otherwise the error text. */
    fun connect(): String?

    fun disconnect()

    /**
     * Blocking. Returns null/blank on success. Takes ownership of fd, but is
     * not required to close it: the service releases the descriptor once this
     * core has stopped, so the TUN interface always goes away.
     */
    fun startVpn(fd: Int): String?

    fun stopVpn()

    /** Underlying default network changed (Wi-Fi <-> mobile, reconnect). */
    fun onNetworkTransition() = Unit

    fun isRunning(): Boolean
    fun isVpnRunning(): Boolean

    companion object {
        val DEFAULT_VPN_DNS = listOf(
            "9.9.9.9",
            "149.112.112.112",
            "2620:fe::fe",
            "2620:fe::9",
            "1.1.1.3",
            "1.0.0.3",
            "2606:4700:4700::1113",
            "2606:4700:4700::1003",
            "8.8.8.8",
            "8.8.4.4",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844"
        )
    }
}
