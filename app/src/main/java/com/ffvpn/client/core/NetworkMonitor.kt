package com.ffvpn.client.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.ffvpn.client.service.LanShare

/**
 * Feeds real default-network changes to the cores so they can rebuild their
 * sessions. A callback on its own is not a change: the stack re-announces the
 * network it already had all the time, and tearing down a working tunnel for
 * that costs the user a connection they never lost.
 */
object NetworkMonitor {
    @Volatile
    private var active: Network? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun start(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        active = manager.activeNetwork
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = settle(manager)
            override fun onLost(network: Network) = settle(manager)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                settle(manager)

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                runCatching { LanShare.refresh() }
                    .onFailure { CrashGuard.report("lan share refresh", it) }
            }
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
    }

    /** Waits out a flap, then acts only if the default network really is another one. */
    private fun settle(manager: ConnectivityManager) {
        pending?.let { handler.removeCallbacks(it) }
        // This runs on the main looper, so an exception here would close the app
        // rather than just miss one connectivity callback.
        val check = Runnable {
            runCatching {
                pending = null
                val current = manager.activeNetwork
                if (current == active) {
                    LanShare.refresh()
                    return@runCatching
                }
                val previous = active
                active = current
                LanShare.refresh()
                when {
                    current == null -> notifyCores("lost")
                    previous == null -> notifyCores("available")
                    else -> notifyCores("switched")
                }
            }.onFailure { CrashGuard.report("network callback", it) }
        }
        pending = check
        handler.postDelayed(check, SETTLE_MS)
    }

    private fun notifyCores(reason: String) {
        AppState.log("network $reason")
        CoreRegistry.all().forEach { core ->
            runCatching { core.onNetworkTransition() }
        }
    }

    private const val SETTLE_MS = 2_000L
}
