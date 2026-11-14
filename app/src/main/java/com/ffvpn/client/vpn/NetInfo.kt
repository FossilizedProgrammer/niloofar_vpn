package com.ffvpn.client.vpn

import com.ffvpn.client.core.Prefs
import com.ffvpn.client.service.LanShare
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

    /** "192.168.43.1:1081" for LAN clients, or null when there is no LAN. */
    fun shareAddress(): String? = lanAddress()?.let { "$it:${LanShare.PORT}" }

    /** "192.168.43.1:8080", the HTTP front that binds every interface while sharing. */
    fun shareHttpAddress(): String? = lanAddress()?.let { "$it:${Prefs.httpPort}" }
}
