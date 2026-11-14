package com.ffvpn.client.core

import java.io.File

/**
 * The state a psiphon-based engine keeps inside its data root. Both engines built
 * on that core share the layout, including the server list whose absence makes the
 * very first attempt of an install slow: until it lands the engine only has the
 * embedded bootstrap entries and spends minutes counting and checking servers.
 */
object PsiphonEngineData {
    private const val DATA_DIR = "ca.psiphon.PsiphonTunnel.tunnel-core"
    private const val SERVER_LIST = "remote_server_list"

    /**
     * True when no engine finished downloading its server list into [workDir] yet.
     * Written atomically, so a partial download reads as still missing.
     */
    fun needsServerDatabase(workDir: File): Boolean {
        val file = File(File(workDir, DATA_DIR), SERVER_LIST)
        return !file.exists() || file.length() == 0L
    }
}
