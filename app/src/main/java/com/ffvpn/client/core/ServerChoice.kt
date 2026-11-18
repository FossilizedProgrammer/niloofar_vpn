package com.ffvpn.client.core

import android.content.Context
import com.ffvpn.client.vpn.Health
import com.ffvpn.client.vpn.Server
import com.ffvpn.client.vpn.ServerList
import com.ffvpn.client.vpn.Store
import java.util.concurrent.ConcurrentHashMap

/** One selectable server: [key] is what the core dials, the rest is shown. */
data class ServerOption(
    val key: String,
    val label: String,
    val detail: String,
    val reachable: Boolean? = null,
    /** Measured round-trip in ms; -1 until a test has measured it. */
    val latencyMs: Int = -1
)

/**
 * Manual server choice for the cores whose servers are a named list: Firefox's
 * free exits, Lantern's radiance relays and SSTP's VPN Gate relays. The choice
 * is kept per core in [Prefs]; an empty key means Auto — scan, rank, dial.
 */
object ServerChoice {
    private val SELECTABLE = setOf("firefox", "lantern", "sstp", "openrung", "fluxcore")

    // The last test per core, kept beyond the picker's own lifetime so reopening
    // it shows the same order, speeds and reachable set instead of the raw list.
    private val testedCache = ConcurrentHashMap<String, List<ServerOption>>()
    private val testedSource = ConcurrentHashMap<String, List<String>>()

    fun supports(coreId: String): Boolean = coreId in SELECTABLE

    /**
     * The cached test result, but only while the underlying list is unchanged;
     * a freshly installed relay list must not be hidden behind an old test.
     */
    fun lastTested(context: Context, coreId: String): List<ServerOption>? {
        if (testedSource[coreId] != options(context, coreId).map { it.key }) return null
        return testedCache[coreId]
    }

    /** The last list this core produced, without running anything. */
    fun options(context: Context, coreId: String): List<ServerOption> {
        val list = when (coreId) {
            "firefox" -> ServerList.readCache(Store.cacheFile(context)).map { firefoxOption(it) }
            "lantern" -> LanternServers.readCache(LanternServers.workDir(context)).map { lanternOption(it) }
            "openrung" -> OpenRungServers.readCache(OpenRungServers.workDir(context)).map { openRungOption(it) }
            "fluxcore" -> FluxcoreServers.readCache(FluxcoreServers.workDir(context)).map { fluxcoreOption(it) }
            "sstp" -> sstpOptions(context)
            else -> emptyList()
        }
        val unique = list.distinctBy { it.key }
        // Fluxcore's rows arrive ordered by the fleet's own load figures; that
        // ranking is what the picker shows, so it is not re-sorted by name.
        if (coreId == "fluxcore") return unique
        return unique.sortedWith(compareBy({ it.label.lowercase() }, { it.key }))
    }

    /**
     * Re-reads the list from the core's own source. SSTP has no fetch of its
     * own: its list is the packaged file or whatever the VPN Gate card applied.
     */
    fun refresh(
        context: Context,
        coreId: String,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<ServerOption> {
        when (coreId) {
            "firefox" -> ServerList.fetch(Store.binary(context), Store.cacheFile(context), stop)
            "lantern" -> LanternServers.fetch(
                LanternServers.binary(context), LanternServers.workDir(context), stop, log
            )
            "openrung" -> OpenRungServers.fetch(
                OpenRungServers.binary(context), OpenRungServers.workDir(context), stop, log
            )
            "fluxcore" -> FluxcoreServers.fetch(
                FluxcoreServers.binary(context), FluxcoreServers.workDir(context), stop, log
            )
        }
        return options(context, coreId)
    }

    /**
     * The refresh the picker runs: freshest list, then a reachability test of
     * every entry. Only servers that answered are kept, fastest first. What
     * counts as answered is what the core itself needs: a TLS handshake for
     * Firefox and SSTP, a TCP connect for Lantern's mixed protocols.
     */
    fun tested(
        context: Context,
        coreId: String,
        stop: () -> Boolean,
        log: (String) -> Unit
    ): List<ServerOption> {
        refresh(context, coreId, stop, log)
        val list = when (coreId) {
            "firefox" -> ServerList.probe(ServerList.readCache(Store.cacheFile(context)), stop)
                .filter { it.health == Health.Alive }
                .map { firefoxOption(it.server, it.latencyMs) }
            "lantern" -> LanternServers.probe(
                LanternServers.readCache(LanternServers.workDir(context)), stop, log
            )
                .filter { it.latencyMs >= 0 }
                .map { lanternOption(it.server, it.latencyMs) }
            "openrung" -> OpenRungServers.probe(
                OpenRungServers.readCache(OpenRungServers.workDir(context)), stop, log
            )
                .filter { it.latencyMs >= 0 }
                .map { openRungOption(it.relay, it.latencyMs) }
            // The engine measures every gateway while it lists them, so the
            // refreshed list is already the tested one.
            "fluxcore" -> options(context, coreId)
            "sstp" -> {
                val countries = VpnGate.loadCache(context).associateBy({ it.key }, { it.country })
                SstpScanner.scan(SstpScanner.loadEndpoints(context), stop, log)
                    .filter { it.health == SstpHealth.Alive }
                    .map { sstpOption(it.endpoint, countries[it.endpoint.toString()].orEmpty(), it.latencyMs) }
            }
            else -> emptyList()
        }
        val result = if (coreId == "fluxcore") {
            list.distinctBy { it.key }
        } else {
            list.distinctBy { it.key }
                .sortedWith(compareBy({ it.latencyMs }, { it.label.lowercase() }, { it.key }))
        }
        testedSource[coreId] = options(context, coreId).map { it.key }
        testedCache[coreId] = result
        return result
    }

    /** The exit the user pinned for Firefox; null means Auto. */
    fun pinnedFirefox(context: Context): Server? {
        val key = Prefs.server("firefox")
        if (key.isBlank()) return null
        ServerList.readCache(Store.cacheFile(context)).firstOrNull { it.address == key }?.let { return it }
        // The address is dialled as-is even when the cached list no longer names
        // it; the label was kept with the choice so the UI can still show it.
        return Server("", Prefs.serverLabel("firefox").ifBlank { key }, "", key)
    }

    /** The relay the user pinned for Lantern; null means Auto. */
    fun pinnedLantern(context: Context): RadianceServer? {
        val key = Prefs.server("lantern")
        if (key.isBlank()) return null
        return LanternServers.readCache(LanternServers.workDir(context)).firstOrNull { it.tag == key }
            ?: RadianceServer(key, "", "", "", null)
    }

    /** The relay the user pinned for OpenRung; null means Auto. */
    fun pinnedOpenRung(context: Context): OpenRungRelay? {
        val key = Prefs.server("openrung")
        if (key.isBlank()) return null
        return OpenRungServers.readCache(OpenRungServers.workDir(context)).firstOrNull { it.id == key }
            ?: OpenRungRelay(key, Prefs.serverLabel("openrung"), "", "", "", "", null)
    }

    /** The relay the user pinned for SSTP; null means Auto. */
    fun pinnedSstp(): SstpEndpoint? {
        val key = Prefs.server("sstp")
        if (key.isBlank()) return null
        return SstpEndpoint.parse(key)
    }

    /** The selector the user pinned for Fluxcore; null means Auto. */
    fun pinnedFluxcore(): String? = Prefs.server("fluxcore").ifBlank { null }

    private fun fluxcoreOption(gateway: FluxcoreGateway) = ServerOption(
        gateway.id,
        if (gateway.city.isBlank()) gateway.name else "${gateway.name} · ${gateway.city}",
        buildString {
            append("${gateway.loadPct}% load")
            if (gateway.nodes > 1) append(" · ${gateway.nodes} nodes")
        },
        reachable = true,
        latencyMs = gateway.latencyMs
    )

    private fun firefoxOption(server: Server, latencyMs: Int = -1) =
        ServerOption(server.address, server.label, server.address, latencyMs = latencyMs)

    private fun lanternOption(server: RadianceServer, latencyMs: Int = -1) = ServerOption(
        server.tag,
        lanternLabel(server),
        "${server.protocol} · ${server.tag}",
        server.reachable,
        latencyMs
    )

    private fun sstpOption(endpoint: SstpEndpoint, country: String, latencyMs: Int = -1) = ServerOption(
        endpoint.toString(),
        if (country.isBlank()) endpoint.toString() else "$country · $endpoint",
        "",
        latencyMs = latencyMs
    )

    private fun lanternLabel(server: RadianceServer): String {
        val country = server.country.ifBlank { server.tag }
        return if (server.protocol.isBlank()) country else "$country · ${server.protocol}"
    }

    private fun openRungOption(relay: OpenRungRelay, latencyMs: Int = -1) = ServerOption(
        relay.id,
        openRungLabel(relay),
        "${relay.protocol} · ${relay.label}",
        relay.reachable,
        latencyMs
    )

    private fun openRungLabel(relay: OpenRungRelay): String {
        val country = relay.country.ifBlank { relay.label.ifBlank { relay.id } }
        val place = relay.city.ifBlank { relay.label }
        return if (place.isBlank()) country else "$country · $place"
    }

    private fun sstpOptions(context: Context): List<ServerOption> {
        val countries = VpnGate.loadCache(context).associateBy({ it.key }, { it.country })
        return SstpScanner.loadEndpoints(context).map { sstpOption(it, countries[it.toString()].orEmpty()) }
    }
}