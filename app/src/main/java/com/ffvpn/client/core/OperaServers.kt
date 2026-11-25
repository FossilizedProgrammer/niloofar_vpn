package com.ffvpn.client.core

import android.content.Context
import java.io.File

/** One exit region the Opera proxy can dial. */
data class OperaRegion(val key: String, val label: String, val detail: String)

/**
 * The Opera engine's choice is a region, not a server: the CLI registers the
 * device, ranks the endpoint feed itself and fails over between endpoints, so
 * a pinned endpoint would be undone by the next plan it tries. The picker
 * therefore offers the three regions the CLI accepts, and everything else —
 * endpoint cache, SNI learning, the saved session — lives in the one state file
 * that is passed with `-state`, because the default path is a directory
 * Android does not have.
 */
object OperaServers {
    fun workDir(context: Context): File =
        File(context.applicationContext.filesDir, "opera")

    fun binary(context: Context): File =
        File(context.applicationContext.applicationInfo.nativeLibraryDir, "libopera.so")

    fun stateFile(workDir: File): File = File(workDir, "state.json")

    /** `-country` values. Auto, the picker's empty key, is not one of them. */
    val regions = listOf(
        OperaRegion("eu", "Europe", "EU"),
        OperaRegion("us", "Americas", "AM"),
        OperaRegion("as", "Asia", "AS")
    )

    fun region(key: String): OperaRegion? = regions.firstOrNull { it.key == key }
}
