package com.ffvpn.client.core

object CoreRegistry {
    private val cores = LinkedHashMap<String, VpnCore>()

    fun register(core: VpnCore) {
        cores[core.id] = core
    }

    fun all(): List<VpnCore> = cores.values.toList()

    fun get(id: String?): VpnCore = cores[id] ?: cores.values.firstOrNull()
        ?: error("no core registered")

    fun default(): VpnCore = get(null)
}
