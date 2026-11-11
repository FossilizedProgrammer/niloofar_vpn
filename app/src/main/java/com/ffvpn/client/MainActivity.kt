package com.ffvpn.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ffvpn.client.ui.HomeScreen
import com.ffvpn.client.ui.SettingsSheet
import com.ffvpn.client.ui.FirefoxVpnTheme
import com.ffvpn.client.vpn.FirefoxVpnService
import com.ffvpn.client.vpn.Mode
import com.ffvpn.client.vpn.Phase
import com.ffvpn.client.vpn.Store
import com.ffvpn.client.vpn.VpnState
import com.ffvpn.client.vpn.VpnUi

class MainActivity : ComponentActivity() {
    private var pendingConnect = false

    /** The system VPN consent dialog; the tunnel only starts on RESULT_OK. */
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == RESULT_OK
        if (granted && pendingConnect) FirefoxVpnService.start(this)
        pendingConnect = false
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        VpnState.syncAccount(this)

        setContent {
            FirefoxVpnTheme {
                // collectAsState (not the lifecycle-aware variant): the latter reads
                // androidx.lifecycle.compose.LocalLifecycleOwner, which Compose 1.6
                // does not provide and R8 builds crash on.
                val ui by VpnState.ui.collectAsState()
                var settingsOpen by remember { mutableStateOf(false) }

                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0B10)) {
                    HomeScreen(
                        ui = ui,
                        onToggle = { toggle(ui) { settingsOpen = true } },
                        onOpenSettings = { settingsOpen = true }
                    )
                }

                if (settingsOpen) {
                    SettingsSheet(
                        ui = ui,
                        onDismiss = { settingsOpen = false },
                        onSignedIn = { VpnState.syncAccount(this) },
                        onSignedOut = {
                            if (ui.phase != Phase.Off) FirefoxVpnService.stop(this)
                            VpnState.syncAccount(this)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        VpnState.syncAccount(this)
        // A service the system killed leaves the UI showing a tunnel that is gone.
        if (!FirefoxVpnService.alive) {
            VpnState.update {
                if (it.phase == Phase.On || it.phase == Phase.Connecting) {
                    it.copy(phase = Phase.Off, step = "", detail = "", connectedAt = 0L)
                } else {
                    it
                }
            }
        }
    }

    private fun toggle(ui: VpnUi, openSettings: () -> Unit) {
        when (ui.phase) {
            Phase.On, Phase.Connecting -> FirefoxVpnService.stop(this)
            Phase.Off, Phase.Error -> {
                if (!Store.hasSession(this)) {
                    openSettings()
                    return
                }
                askNotifications()
                // Proxy mode never touches the system VPN, so it needs no consent.
                if (Store.mode(this) == Mode.Proxy) {
                    FirefoxVpnService.start(this)
                    return
                }
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    pendingConnect = true
                    consent.launch(intent)
                } else {
                    FirefoxVpnService.start(this)
                }
            }
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
