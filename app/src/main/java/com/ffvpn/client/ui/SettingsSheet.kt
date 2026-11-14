package com.ffvpn.client.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ffvpn.client.AppLocale
import com.ffvpn.client.BuildConfig
import com.ffvpn.client.R
import com.ffvpn.client.Texts
import com.ffvpn.client.WebSignInActivity
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.CoreRegistry
import com.ffvpn.client.core.GithubUpdates
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.TorBridges
import com.ffvpn.client.vpn.LogLine
import com.ffvpn.client.vpn.Logs
import com.ffvpn.client.vpn.NetInfo
import com.ffvpn.client.vpn.ServerList
import com.ffvpn.client.vpn.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: AppState.State,
    onDismiss: () -> Unit,
    onOpenTools: () -> Unit,
    onSignedIn: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf(Store.accounts(context)) }
    var language by remember { mutableStateOf(AppLocale.current(context)) }
    var shareLan by remember { mutableStateOf(Prefs.shareLan) }
    var connectSound by remember { mutableStateOf(Prefs.connectSound) }
    var autoRetry by remember { mutableStateOf(Prefs.autoRetry) }
    var torBridges by remember { mutableStateOf(Prefs.torUseBridges) }
    var psiphonConduit by remember { mutableStateOf(Prefs.psiphonConduit) }
    var coreId by remember { mutableStateOf(state.coreId.ifBlank { CoreRegistry.default().id }) }
    var socksPort by remember { mutableStateOf(Prefs.socksPort.toString()) }
    var httpPort by remember { mutableStateOf(Prefs.httpPort.toString()) }
    var portsError by remember { mutableStateOf<String?>(null) }
    var torStatus by remember { mutableStateOf("") }
    var torRunning by remember { mutableStateOf(false) }
    var customBridges by remember {
        mutableStateOf(TorBridges.custom(context).joinToString("\n") { it.line })
    }
    var bridgeCount by remember { mutableStateOf(TorBridges.all(context).size) }
    // Lines the last scan found reachable, so they can be pinned to the user's
    // own list — a later scan replaces its own file but never that one.
    var scannedBridges by remember { mutableStateOf(emptyList<String>()) }
    val torStop = remember { AtomicBoolean(false) }
    val running = state.running || state.connected || state.busy || state.disconnecting

    /**
     * The field is the only place a typed bridge exists until it is written
     * down, so turning the switch on, or leaving the sheet, keeps it. Without
     * this the line was silently dropped and Tor kept using the scanned list.
     */
    fun keepTypedBridges(): Boolean {
        val typed = customBridges.trim()
        if (typed.isEmpty()) return false
        if (typed == TorBridges.custom(context).joinToString("\n") { it.line }.trim()) return false
        val result = TorBridges.saveCustom(context, typed)
        torStatus = Texts.bridgesSaved(result.accepted, result.rejected)
        bridgeCount = TorBridges.all(context).size
        return result.accepted > 0
    }

    // Validates a token with the engine: it proves the session against the
    // auth server and caches it in one go (`--session-token-file`).
    fun verify(session: String, mail: String) {
        scope.launch {
            busy = true
            error = null
            var quota = ""
            var problem: String? = null
            var attempt = 0
            while (attempt < VERIFY_ATTEMPTS) {
                attempt++
                problem = withContext(Dispatchers.IO) {
                    val binary = Store.binary(context)
                    if (!binary.exists()) {
                        context.getString(R.string.error_engine_missing)
                    } else {
                        ServerList.verifyToken(
                            binary = binary,
                            workDir = Store.workDir(context),
                            token = session
                        ) { line ->
                            Regex("""Bandwidth:\s*(.+)$""")
                                .find(line)?.groupValues?.get(1)?.trim()
                                ?.let { quota = it }
                        }
                    }
                }
                if (problem == null || !looksUnconfirmed(problem.orEmpty())) break
                delay(VERIFY_RETRY_MS)
            }
            busy = false
            if (problem == null) {
                if (mail.isNotBlank()) Store.saveAccount(context, mail.trim(), session, quota)
                accounts = Store.accounts(context)
                Logs.add("account", "saved ${mail.trim()}")
                onSignedIn()
            } else {
                Logs.add("account", "verify failed: ${problem.take(160)}")
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
        onDismissRequest = {
            keepTypedBridges()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        ProvideAppTextToolbar {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            Text(
                text = Texts.settings,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Section(stringResource(R.string.language_title), stringResource(R.string.language_desc)) {
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
                        AppLocale.apply(context, tag)
                    }
                }
            }

            Section(Texts.core, Texts.coreHint) {
                CoreRegistry.all().forEach { core ->
                    ChoiceRow(
                        label = Texts.coreName(core.id),
                        selected = coreId == core.id,
                        enabled = !running
                    ) {
                        coreId = core.id
                        Prefs.coreId = core.id
                        AppState.update { it.copy(coreId = core.id) }
                    }
                }
                if (running) {
                    Text(
                        text = stringResource(R.string.settings_next_connect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section(Texts.mode, Texts.modeHint) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeOption(
                        title = Texts.modeShort(AppState.Mode.VPN),
                        subtitle = stringResource(R.string.mode_vpn_desc),
                        selected = state.mode == AppState.Mode.VPN,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Prefs.mode = AppState.Mode.VPN
                        AppState.update { it.copy(mode = AppState.Mode.VPN) }
                    }
                    ModeOption(
                        title = Texts.modeShort(AppState.Mode.PROXY),
                        subtitle = stringResource(R.string.mode_proxy_desc),
                        selected = state.mode == AppState.Mode.PROXY,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Prefs.mode = AppState.Mode.PROXY
                        AppState.update { it.copy(mode = AppState.Mode.PROXY) }
                    }
                }
            }

            Section(Texts.ports, Texts.portsHint) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = socksPort,
                        onValueChange = { socksPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(Texts.socksPortLabel) },
                        enabled = !running,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = httpPort,
                        onValueChange = { httpPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(Texts.httpPortLabel) },
                        enabled = !running,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    portsError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    } ?: Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !running,
                        onClick = {
                            val socks = socksPort.toIntOrNull()
                            val http = httpPort.toIntOrNull()
                            if (socks == null || http == null || socks == http ||
                                socks !in Prefs.MIN_PORT..Prefs.MAX_PORT ||
                                http !in Prefs.MIN_PORT..Prefs.MAX_PORT
                            ) {
                                portsError = Texts.portInvalid
                            } else {
                                Prefs.socksPort = socks
                                Prefs.httpPort = http
                                portsError = null
                            }
                        }
                    ) { Text(Texts.continueLabel) }
                }
            }

            Section(Texts.share, Texts.shareHint) {
                SwitchRow(Texts.shareSwitch, Texts.shareHint, shareLan) {
                    shareLan = it
                    Prefs.shareLan = it
                    AppState.update { copy -> copy.copy(shareLan = it) }
                }
                if (shareLan) {
                    val socks = NetInfo.shareAddress()
                    val http = NetInfo.shareHttpAddress()
                    Text(
                        text = if (socks != null && http != null) {
                            Texts.shareAddress(socks) + "\n" + Texts.shareHttpAddress(http) + "\n" +
                                Texts.proxyDetail(Prefs.socksAddress, Prefs.httpAddress)
                        } else {
                            stringResource(R.string.share_no_network)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (running) {
                    Text(
                        text = stringResource(R.string.settings_next_connect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section(Texts.alerts, null) {
                SwitchRow(Texts.soundSwitch, Texts.soundHint, connectSound) {
                    connectSound = it
                    Prefs.connectSound = it
                }
                SwitchRow(Texts.autoRetry, Texts.autoRetryHint, autoRetry) {
                    autoRetry = it
                    Prefs.autoRetry = it
                }
            }

            Section(Texts.psiphonConduit, Texts.psiphonConduitHint) {
                SwitchRow(
                    Texts.psiphonConduitSwitch,
                    Texts.psiphonConduitSwitchHint,
                    psiphonConduit
                ) {
                    psiphonConduit = it
                    Prefs.psiphonConduit = it
                }
                if (running) {
                    Text(
                        text = stringResource(R.string.settings_next_connect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section(Texts.torBridges, Texts.torBridgesHint) {
                SwitchRow(Texts.torBridgesSwitch, Texts.torBridgesSwitchHint, torBridges) {
                    torBridges = it
                    Prefs.torUseBridges = it
                    // Tor only reads its bridges at startup, so the entered
                    // lines have to be on disk before the switch can matter.
                    if (it) keepTypedBridges()
                }
                if (running) {
                    Text(
                        text = stringResource(R.string.settings_next_connect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = Texts.bridgeCount(bridgeCount, TorBridges.custom(context).size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!torRunning) {
                                torStop.set(false)
                                torRunning = true
                                torStatus = Texts.scanningBridges
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        TorBridges.scan(context, { torStop.get() }) { Logs.add("tor", it) }
                                    }
                                    val stored = TorBridges.all(context).size
                                    torStatus = Texts.bridgeScanStored(
                                        Texts.bridgeScanResult(result.reachable.size, result.total),
                                        stored
                                    )
                                    bridgeCount = stored
                                    scannedBridges = result.reachable.map { it.line }
                                    torRunning = false
                                }
                            } else {
                                torStop.set(true)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (torRunning) Texts.stopScan else Texts.scanBridges)
                    }
                }
                if (torStatus.isNotBlank()) {
                    Text(
                        text = torStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                OutlinedTextField(
                    value = customBridges,
                    onValueChange = { customBridges = it },
                    label = { Text(Texts.customBridges) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = Texts.customBridgesHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        val raw = customBridges.ifBlank { scannedBridges.joinToString("\n") }
                        val result = TorBridges.saveCustom(context, raw)
                        torStatus = Texts.bridgesSaved(result.accepted, result.rejected)
                        bridgeCount = TorBridges.all(context).size
                        if (customBridges.isBlank()) scannedBridges = emptyList()
                    },
                    enabled = customBridges.isNotBlank() || scannedBridges.isNotEmpty()
                ) { Text(Texts.saveBridges) }
            }

            Section(stringResource(R.string.account_title), stringResource(R.string.account_hint)) {
                if (accounts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.account_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val active = Store.activeAccount(context)?.email
                accounts.forEach { saved ->
                    AccountRow(
                        email = saved.email,
                        quota = saved.quota,
                        active = saved.email.equals(active, ignoreCase = true) && Store.hasSession(context),
                        enabled = !busy,
                        onUse = {
                            Store.activateAccount(context, saved.email)
                            error = null
                            accounts = Store.accounts(context)
                            onSignedIn()
                        },
                        onRemove = {
                            Store.removeAccount(context, saved.email)
                            error = null
                            accounts = Store.accounts(context)
                            if (Store.accounts(context).isEmpty()) onSignedOut() else onSignedIn()
                        }
                    )
                }
                if (accounts.isNotEmpty() && running) {
                    Text(
                        text = stringResource(R.string.account_switch_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                        browser.launch(Intent(context, WebSignInActivity::class.java))
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
                            when {
                                busy -> R.string.sign_in_checking
                                accounts.isEmpty() -> R.string.sign_in_with_firefox
                                else -> R.string.account_add
                            }
                        )
                    )
                }
            }

            Section(Texts.tools, Texts.toolsBody) {
                Button(onClick = onOpenTools, modifier = Modifier.fillMaxWidth()) {
                    Text(Texts.tools)
                }
            }

            Section(stringResource(R.string.log_title), stringResource(R.string.log_desc)) {
                val lines by Logs.lines.collectAsState()
                Text(
                    text = if (lines.isEmpty()) {
                        stringResource(R.string.log_empty)
                    } else {
                        stringResource(R.string.log_size, lines.size, logSize(lines))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            copyLog(context)
                            Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show()
                        },
                        enabled = lines.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.log_copy)) }
                    TextButton(
                        onClick = { Logs.clear() },
                        enabled = lines.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.log_clear)) }
                }
            }

            Section(stringResource(R.string.about_title), null) {
                Text(
                    text = Texts.aboutBody,
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
                Text(
                    text = Texts.aboutHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                UpdateCheck()
            }
            }
        }
    }
}

@Composable
private fun UpdateCheck() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<GithubUpdates.Release?>(null) }
    var failed by remember { mutableStateOf(false) }
    val newer = release?.let { GithubUpdates.compareVersions(it.version, BuildConfig.VERSION_NAME) > 0 } == true

    OutlinedButton(
        enabled = !checking,
        onClick = {
            scope.launch {
                checking = true
                failed = false
                release = null
                try {
                    release = GithubUpdates.check()
                    GithubUpdates.publish(release!!)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    checking = false
                }
            }
        }
    ) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(stringResource(if (checking) R.string.update_checking else R.string.update_check))
    }
    if (failed) {
        Text(stringResource(R.string.update_failed), color = MaterialTheme.colorScheme.error)
    } else if (release != null) {
        Text(
            text = if (newer) {
                stringResource(R.string.update_available, release!!.version)
            } else {
                stringResource(R.string.update_current, BuildConfig.VERSION_NAME)
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
    TextButton(onClick = {
        val page = if (newer) release!!.page else GithubUpdates.RELEASES_PAGE
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(page)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show()
        }
    }) {
        Text(stringResource(R.string.update_get))
    }
}

/** A titled card; every settings group sits in one. */
@Composable
private fun Section(
    title: String,
    subtitle: String?,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The log's size, in the units a file size reads in. */
private fun logSize(lines: List<LogLine>): String {
    val bytes = lines.sumOf { it.toString().toByteArray().size + 1 }
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

/** Puts the whole log on the clipboard, for pasting into a message. */
private fun copyLog(context: Context) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.log_title), Logs.text()))
}

/** One row of a settings picker; the check marks the active choice. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                selected -> accent
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(text = "\u2713", color = accent, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * One saved account: tapping the row makes it the account the engine uses,
 * and Remove drops it from the device.
 */
@Composable
private fun AccountRow(
    email: String,
    quota: String,
    active: Boolean,
    enabled: Boolean,
    onUse: () -> Unit,
    onRemove: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) accent.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !active, onClick = onUse)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (active) {
                    Text(
                        text = stringResource(R.string.account_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
            }
            if (quota.isNotBlank()) {
                Text(
                    text = stringResource(R.string.quota_label) + ": " + quota,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Text(
                text = stringResource(R.string.account_remove),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.clickable(enabled = enabled, onClick = onRemove)
            )
        }
    }
}

/** A selectable mode card; the border and wash carry the selection. */
@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.secondary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Whether the engine refused the session only because Mozilla has not confirmed
 * it yet: the sign-in page reports the session right after the password, before
 * the code or the emailed link it still asks for.
 */
private fun looksUnconfirmed(problem: String): Boolean =
    problem.contains("errno 138") ||
        problem.contains("errno 201") ||
        problem.contains("unconfirmed", ignoreCase = true) ||
        problem.contains("unverified", ignoreCase = true) ||
        problem.contains("not verified", ignoreCase = true)

/** How long a session that is waiting for confirmation is retried. */
private const val VERIFY_ATTEMPTS = 40
private const val VERIFY_RETRY_MS = 4_000L
