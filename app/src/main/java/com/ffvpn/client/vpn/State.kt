package com.ffvpn.client.vpn

import android.content.Context
import androidx.annotation.StringRes
import com.ffvpn.client.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase { Off, Connecting, On, Error }

/** How traffic reaches the engine: a system tunnel, or a proxy apps point at. */
enum class Mode { Vpn, Proxy }

/** Everything the home screen draws, kept in one flow. */
data class VpnUi(
    val phase: Phase = Phase.Off,
    val step: String = "",
    val detail: String = "",
    val connectedAt: Long = 0L,
    val country: String = "",
    val latencyMs: Int = 0,
    val signedIn: Boolean = false,
    val email: String = "",
    val quota: String = "",
    val mode: Mode = Mode.Vpn,
    val share: Boolean = false,
    val address: String = ""
)

object VpnState {
    private val _ui = MutableStateFlow(VpnUi())
    val ui: StateFlow<VpnUi> = _ui.asStateFlow()

    fun update(block: (VpnUi) -> VpnUi) {
        _ui.value = block(_ui.value)
    }

    /** Re-reads the account bits from disk, e.g. after sign-in or on resume. */
    fun syncAccount(context: Context) {
        Store.ensureAccounts(context)
        val active = Store.activeAccount(context)
        _ui.value = _ui.value.copy(
            signedIn = Store.hasSession(context),
            email = active?.email ?: Store.email(context).orEmpty(),
            quota = active?.quota?.takeIf { it.isNotBlank() } ?: Store.quota(context).orEmpty(),
            mode = Store.mode(context),
            share = Store.share(context)
        )
    }
}

/** What the engine reports while it works; the service localises it. */
enum class Step(@StringRes val label: Int) {
    ReadingList(R.string.step_reading_list),
    Probing(R.string.step_probing),
    Dialing(R.string.step_dialing),
    OpeningTunnel(R.string.step_opening_tunnel),
    Reconnecting(R.string.step_reconnecting)
}
