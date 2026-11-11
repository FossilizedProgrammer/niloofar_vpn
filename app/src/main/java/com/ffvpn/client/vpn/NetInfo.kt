package com.ffvpn.client.vpn

import java.net.Inet4Address
import java.net.NetworkInterface

/** The addresses to show when the tunnel is offered to other devices. */
object NetInfo {
    /** This device's IPv4 on Wi-Fi or a hotspot, if it has one. */
    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** "192.168.43.1:1080" for LAN clients, or null when there is no LAN. */
    fun shareAddress(): String? = lanAddress()?.let { "$it:${PORTS.first()}" }

    val PORTS = listOf(1080, 1081)
}
