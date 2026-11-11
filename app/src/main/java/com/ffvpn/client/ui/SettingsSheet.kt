package com.ffvpn.client.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ffvpn.client.AppLocale
import com.ffvpn.client.BuildConfig
import com.ffvpn.client.R
import com.ffvpn.client.SignInActivity
import com.ffvpn.client.vpn.Mode
import com.ffvpn.client.vpn.NetInfo
import com.ffvpn.client.vpn.Phase
import com.ffvpn.client.vpn.Server
import com.ffvpn.client.vpn.ServerList
import com.ffvpn.client.vpn.Store
import com.ffvpn.client.vpn.VpnState
import com.ffvpn.client.vpn.VpnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    ui: VpnUi,
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(AppLocale.current(context)) }
    var country by remember { mutableStateOf(Store.country(context)) }
    var exits by remember { mutableStateOf<List<Server>>(emptyList()) }
    val countries = remember(exits) {
        exits.distinctBy { it.country }
            .sortedBy { it.countryName.lowercase() }
            .map { it.country to it.countryName }
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) {
            ServerList.readCache(Store.cacheFile(context))
        }
        if (cached.isNotEmpty()) exits = cached
        val binary = Store.binary(context)
        if (binary.exists()) {
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    ServerList.fetch(binary, Store.cacheFile(context)) { false }
                }.getOrDefault(emptyList())
            }
            if (fresh.isNotEmpty()) exits = fresh
        }
    }

    // Validates a token with the engine: it proves the session against the
    // auth server and caches it in one go (`--session-token-file`).
    fun verify(session: String, mail: String) {
        scope.launch {
            busy = true
            error = null
            val problem = withContext(Dispatchers.IO) {
                val binary = Store.binary(context)
                if (!binary.exists()) {
                    context.getString(R.string.error_engine_missing)
                } else {
                    ServerList.verifyToken(
                        binary = binary,
                        workDir = Store.workDir(context),
                        token = session
                    ) { line ->
                        val quota = Regex("""Bandwidth:\s*(.+)$""")
                            .find(line)?.groupValues?.get(1)?.trim()
                        if (quota != null) Store.setQuota(context, quota)
                    }
                }
            }
            busy = false
            if (problem == null) {
                if (mail.isNotBlank()) Store.setEmail(context, mail.trim())
                onSignedIn()
            } else {
                error = context.getString(R.string.sign_in_failed) + "\n" + problem
            }
        }
    }

    val browser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return@rememberLauncherForActivityResult
        val session = data.getStringExtra("token") ?: return@rememberLauncherForActivityResult
        val mail = data.getStringExtra("email")?.trim() ?: return@rememberLauncherForActivityResult
        verify(session, mail)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.language_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AppLocale.choices.forEach { (tag, label) ->
                        ChoiceRow(
                            label = if (tag == AppLocale.SYSTEM) {
                                stringResource(R.string.language_system)
                            } else {
                                label
                            },
                            selected = language == tag
                        ) {
                            language = tag
                            (context as? Activity)?.let { AppLocale.apply(it, tag) }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.server_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.server_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ChoiceRow(
                        label = stringResource(R.string.server_auto),
                        selected = country.isBlank()
                    ) {
                        country = ""
                        Store.setCountry(context, "")
                    }
                    countries.forEach { (code, name) ->
                        ChoiceRow(
                            label = name.ifBlank { code.uppercase() },
                            selected = country.equals(code, ignoreCase = true)
                        ) {
                            country = code
                            Store.setCountry(context, code)
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeOption(
                            title = R.string.mode_vpn,
                            subtitle = R.string.mode_vpn_desc,
                            selected = ui.mode == Mode.Vpn,
                            modifier = Modifier.weight(1f)
                        ) {
                            Store.setMode(context, Mode.Vpn)
                            VpnState.update { it.copy(mode = Mode.Vpn) }
                        }
                        ModeOption(
                            title = R.string.mode_proxy,
                            subtitle = R.string.mode_proxy_desc,
                            selected = ui.mode == Mode.Proxy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Store.setMode(context, Mode.Proxy)
                            VpnState.update { it.copy(mode = Mode.Proxy) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.share_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.share_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ui.share,
                            onCheckedChange = { enabled ->
                                Store.setShare(context, enabled)
                                VpnState.update { it.copy(share = enabled) }
                            }
                        )
                    }
                    if (ui.share) {
                        val address = NetInfo.shareAddress()
                        Text(
                            text = if (address != null) {
                                stringResource(R.string.share_ready, address) + " · " +
                                    stringResource(R.string.share_ports, "1080", "1081")
                            } else {
                                stringResource(R.string.share_no_network)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (ui.phase != Phase.Off) {
                        Text(
                            text = stringResource(R.string.settings_next_connect),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.account_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    if (ui.signedIn) {
                        Text(
                            text = stringResource(R.string.signed_in_as),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = ui.email.ifBlank { "—" },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (ui.quota.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.quota_label) + ": " + ui.quota,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                Store.clearSession(context)
                                error = null
                                onSignedOut()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_sign_out))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.account_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        error?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Button(
                            onClick = {
                                error = null
                                browser.launch(Intent(context, SignInActivity::class.java))
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.size(10.dp))
                            }
                            Text(
                                stringResource(
                                    if (busy) R.string.sign_in_checking
                                    else R.string.sign_in_with_firefox
                                )
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.about_core),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.about_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

}

/** One row of a settings picker; the check marks the active choice. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(text = "✓", color = accent, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A selectable mode card; the border and wash carry the selection. */
@Composable
private fun ModeOption(
    title: Int,
    subtitle: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
