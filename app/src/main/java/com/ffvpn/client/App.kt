package com.ffvpn.client

import android.app.Application
import com.ffvpn.client.core.AetherCore
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.CaBundle
import com.ffvpn.client.core.CoreRegistry
import com.ffvpn.client.core.CrashGuard
import com.ffvpn.client.core.FirefoxCore
import com.ffvpn.client.core.GithubUpdates
import com.ffvpn.client.core.LanternCore
import com.ffvpn.client.core.LogRetention
import com.ffvpn.client.core.NetworkMonitor
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.PsiphonCore
import com.ffvpn.client.core.SstpCore
import com.ffvpn.client.core.SunAndLionCore
import com.ffvpn.client.core.TorCore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // A worker that dies on a dropped connection must not take the tunnel,
        // the notification or the process with it.
        CrashGuard.install()
        // Before any engine opens a log file for this run.
        LogRetention.sweep(filesDir)
        // Before the first TLS handshake: the device's root set is frozen at the
        // image build date, and the engines are handed this bundle instead.
        CaBundle.prepare(this)
        Prefs.init(this)
        // Firefox stays the default engine; the ported cores follow it in the picker.
        CoreRegistry.register(FirefoxCore())
        CoreRegistry.register(AetherCore())
        CoreRegistry.register(PsiphonCore())
        CoreRegistry.register(SunAndLionCore())
        CoreRegistry.register(SstpCore())
        CoreRegistry.register(LanternCore())
        CoreRegistry.register(TorCore())
        CoreRegistry.all().forEach { core ->
            runCatching { core.init(this) }
                .onFailure { AppState.log("core ${core.id} init failed: ${it.message ?: it.javaClass.simpleName}") }
        }
        restoreSettings()
        NetworkMonitor.start(this)
        GithubUpdates.autoCheck()
    }

    private fun restoreSettings() {
        val core = CoreRegistry.get(Prefs.coreId.ifBlank { null })
        if (Prefs.coreId != core.id) {
            Prefs.coreId = core.id
        }
        AppState.update {
            it.copy(
                mode = Prefs.mode,
                coreId = core.id,
                shareLan = Prefs.shareLan
            )
        }
    }
}
