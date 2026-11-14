package com.ffvpn.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.service.ProxyService
import com.ffvpn.client.service.TunnelVpnService
import com.ffvpn.client.ui.HomeScreen
import com.ffvpn.client.ui.NiloofarTheme
import com.ffvpn.client.ui.SettingsSheet
import com.ffvpn.client.ui.ToolsScreen
import com.ffvpn.client.vpn.Store

class MainActivity : ComponentActivity() {
    private var pendingConnect = false

    /** The system VPN consent dialog; the tunnel only starts on RESULT_OK. */
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == RESULT_OK
        val state = AppState.state
        if (granted && pendingConnect && !state.disconnecting && !state.running && !state.busy) {
            TunnelVpnService.start(this, state.coreId)
        }
        pendingConnect = false
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        AppLocale.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Says which way the screen is laid out, so a language that did not take
        // effect shows up in the log instead of only on the screen.
        AppState.log(
            "ui lang=${AppLocale.current(this)}" +
                " dir=${if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"}"
        )

        setContent {
            NiloofarTheme {
                var state by remember { mutableStateOf(AppState.state) }
                var settingsOpen by remember { mutableStateOf(false) }
                var toolsOpen by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    val stop = AppState.listen { state = it }
                    onDispose { stop() }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF06080E)) {
                    if (toolsOpen) {
                        ToolsScreen(onBack = { toolsOpen = false })
                    } else {
                        HomeScreen(
                            state = state,
                            onToggle = { toggle { settingsOpen = true } },
                            onOpenSettings = { settingsOpen = true },
                            onOpenTools = { toolsOpen = true }
                        )
                    }
                }

                if (settingsOpen) {
                    SettingsSheet(
                        state = state,
                        onDismiss = { settingsOpen = false },
                        onOpenTools = {
                            settingsOpen = false
                            toolsOpen = true
                        },
                        onSignedIn = { },
                        onSignedOut = {
                            val currentState = AppState.state
                            if (currentState.connected || currentState.running) stopSession()
                        }
                    )
                }
            }
        }
    }

    private fun toggle(openSettings: () -> Unit) {
        val state = AppState.state
        if (state.disconnecting) return
        if (state.connected || state.running || state.busy) {
            stopSession()
            return
        }
        val coreId = state.coreId
        // The Firefox core rides on the user's account; the anonymous cores do not.
        if (coreId == "firefox" && !Store.hasSession(this)) {
            openSettings()
            return
        }
        askNotifications()
        if (Prefs.mode == AppState.Mode.PROXY) {
            ProxyService.start(this, coreId)
            return
        }
        // Proxy mode never touches the system VPN, so it needs no consent.
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConnect = true
            consent.launch(intent)
        } else {
            TunnelVpnService.start(this, coreId)
        }
    }

    private fun stopSession() {
        val state = AppState.state
        if (state.disconnecting) return
        if (state.mode == AppState.Mode.PROXY) {
            ProxyService.stop(this)
        } else {
            TunnelVpnService.stop(this)
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
