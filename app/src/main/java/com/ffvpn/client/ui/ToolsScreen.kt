package com.ffvpn.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ffvpn.client.Texts
import com.ffvpn.client.core.EdgeHit
import com.ffvpn.client.core.EdgeMode
import com.ffvpn.client.core.EdgeScanner
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.SstpHealth
import com.ffvpn.client.core.SstpRelays
import com.ffvpn.client.core.SstpScanner
import com.ffvpn.client.core.SunAndLionCandidates
import com.ffvpn.client.core.SunAndLionScanner
import com.ffvpn.client.core.SunAndLionStore
import com.ffvpn.client.core.VpnGate
import com.ffvpn.client.core.VpnGateServer
import com.ffvpn.client.vpn.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two collectors behind the cores: the CDN edge scanner that feeds Sun and
 * Lion, and the VPN Gate list that feeds SSTP. Both run on the device and write
 * nothing until the user applies the results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val presets = remember { SunAndLionScanner.presets() }
    var preset by remember { mutableStateOf(0) }

    var ranges by remember {
        mutableStateOf(presets.firstOrNull()?.ranges?.joinToString("\n").orEmpty())
    }
    var snis by remember {
        mutableStateOf(presets.firstOrNull()?.snis?.joinToString("\n").orEmpty())
    }
    var hosts by remember {
        mutableStateOf(presets.firstOrNull()?.hosts?.joinToString("\n").orEmpty())
    }
    var mode by remember { mutableStateOf(EdgeMode.BALANCED) }
    var concurrency by remember { mutableStateOf(10) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<EdgeHit>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    val stop = remember { AtomicBoolean(false) }
    val store = remember { SunAndLionStore(context) }

    var gateServers by remember { mutableStateOf<List<VpnGateServer>>(emptyList()) }
    var gateStatus by remember { mutableStateOf("") }
    var gateBusy by remember { mutableStateOf(false) }
    var gateFilter by remember { mutableStateOf(Prefs.vpngateFilterCountries) }
    var gateReplace by remember { mutableStateOf(Prefs.replaceToolLists) }
    var health by remember { mutableStateOf<Map<String, SstpHealth>>(emptyMap()) }
    // Measured on this device by the scan above, so the saved order is the one
    // this link actually sees rather than the ping the gate reports.
    var latency by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var installedRelays by remember { mutableStateOf(SstpRelays.installedCount(context)) }

    fun usePreset(index: Int) {
        val choice = presets.getOrNull(index) ?: return
        preset = index
        ranges = choice.ranges.joinToString("\n")
        snis = choice.snis.joinToString("\n")
        hosts = choice.hosts.joinToString("\n")
    }

    fun clean(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun scout() {
        if (scanning) return
        scanning = true
        stop.set(false)
        status = ""
        progress = Texts.scoutingSnis
        scope.launch {
            val current = withContext(Dispatchers.IO) {
                val ips = EdgeScanner.expand(clean(ranges), mode)
                val sniList = clean(snis)
                val hostList = clean(hosts)
                if (ips.isEmpty() || sniList.isEmpty()) null
                else EdgeScanner.scout(ips, sniList, hostList, { stop.get() }) { Logs.add("tools", it) }
            }
            scanning = false
            progress = ""
            if (current == null) {
                status = Texts.scanNoHits
            } else {
                if (current.snis.isNotEmpty()) snis = current.snis.joinToString("\n")
                if (current.hosts.isNotEmpty()) hosts = current.hosts.joinToString("\n")
                status = if (current.snis.isEmpty()) {
                    Texts.scoutNoSni
                } else {
                    Texts.scoutResult(current.snis.size, current.hosts.size)
                }
            }
        }
    }

    fun scan() {
        if (scanning) {
            stop.set(true)
            return
        }
        scanning = true
        stop.set(false)
        hits = emptyList()
        status = ""
        progress = Texts.scanning
        scope.launch {
            val ips = withContext(Dispatchers.IO) { EdgeScanner.expand(clean(ranges), mode) }
            val result = withContext(Dispatchers.IO) {
                EdgeScanner.scan(
                    ips = ips,
                    snis = clean(snis),
                    hosts = clean(hosts),
                    concurrency = concurrency,
                    mode = mode,
                    stop = { stop.get() }
                ) { done, usable ->
                    main.post { progress = Texts.scanProgress(done, ips.size, usable) }
                }
            }
            scanning = false
            progress = ""
            hits = result
            status = if (result.isEmpty()) {
                Texts.scanNoHits
            } else {
                Texts.scanFinished(result.size, ips.size)
            }
        }
    }

    fun applyScan() {
        val ips = hits.map { it.ip }.distinct()
        val sniList = hits.map { it.sni }.distinct()
        val hostList = hits.map { it.host }.distinct()
        if (ips.isEmpty() || sniList.isEmpty()) return
        store.saveScan(SunAndLionCandidates(ips, sniList, hostList))
        status = Texts.scannerKept(true, ips.size, sniList.size, hostList.size)
    }

    fun copyHits() {
        val text = hits.joinToString("\n") { "${it.ip}\t${it.sni}\t${it.host}\t${it.latencyMs}ms" }
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText(Texts.ipScanner, text))
        status = Texts.copied
    }

    val shown = if (gateFilter) {
        gateServers.filterNot(VpnGate::isRestricted)
    } else {
        gateServers
    }

    fun fetchGate() {
        if (gateBusy) return
        gateBusy = true
        gateStatus = Texts.fetchingServers
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                VpnGate.fetch { Logs.add("vpngate", it) }
            }
            gateBusy = false
            if (list == null) {
                gateStatus = Texts.fetchFailed
            } else {
                gateServers = list
                health = emptyMap()
                latency = emptyMap()
                gateStatus = Texts.fetchedCount(list.size)
            }
        }
    }

    fun testGate() {
        if (gateBusy || shown.isEmpty()) return
        gateBusy = true
        gateStatus = Texts.testingHealth
        scope.launch {
            val candidates = withContext(Dispatchers.IO) {
                SstpScanner.scan(shown.map { it.endpoint }, { false }) { Logs.add("vpngate", it) }
            }
            val map = candidates.associate { it.endpoint.toString() to it.health }
            health = map
            latency = candidates.associate { it.endpoint.toString() to it.latencyMs }
            val alive = map.values.count { it == SstpHealth.Alive }
            val tcpOnly = map.values.count { it == SstpHealth.TcpOnly }
            val dead = shown.size - alive - tcpOnly
            gateBusy = false
            gateStatus = Texts.healthResult(alive, tcpOnly, dead)
        }
    }

    fun applyGate(top: Int) {
        val ordered = shown.sortedWith(
            compareBy(
                { health[it.endpoint.toString()]?.ordinal ?: SstpHealth.Dead.ordinal },
                { latency[it.endpoint.toString()] ?: it.pingMs }
            )
        ).take(top)
        if (ordered.isEmpty()) return
        val chosen = ordered.map { it.endpoint }
        val wanted = if (gateReplace) {
            chosen
        } else {
            (chosen + SstpRelays.installed(context)).distinct()
        }
        if (SstpRelays.apply(context, wanted)) {
            installedRelays = SstpRelays.installedCount(context)
            gateStatus = Texts.relaysInstalled(installedRelays)
        }
    }

    fun restoreGate() {
        SstpRelays.clear(context)
        installedRelays = 0
        gateStatus = Texts.builtInInUse
    }

    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize().background(Lotus.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            TopAppBar(
                title = { Text(Texts.tools, fontWeight = FontWeight.SemiBold) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = Texts.back)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.0f)
                )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = Texts.toolsBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ToolsCard(Texts.ipScanner, Texts.toolsHint) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEachIndexed { index, item ->
                            Chip(item.id, index == preset, scanning) { usePreset(index) }
                        }
                    }
                    OutlinedTextField(
                        value = ranges,
                        onValueChange = { ranges = it },
                        label = { Text(Texts.ipRanges) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = Texts.ipRangesHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = snis,
                        onValueChange = { snis = it },
                        label = { Text(Texts.sniValues) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = hosts,
                        onValueChange = { hosts = it },
                        label = { Text(Texts.httpHosts) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = Texts.httpHostsHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EdgeMode.values().forEach { item ->
                            Chip(
                                label = when (item) {
                                    EdgeMode.QUICK -> Texts.modeQuick
                                    EdgeMode.BALANCED -> Texts.modeBalanced
                                    EdgeMode.DEEP -> Texts.modeDeep
                                },
                                selected = item == mode,
                                enabled = !scanning
                            ) { mode = item }
                        }
                    }
                    Text(
                        text = Texts.modeDescription(mode.samplesPerCidr, mode.maxIps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = Texts.concurrency,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        listOf(6, 10, 15).forEach { value ->
                            Chip(value.toString(), concurrency == value, scanning) { concurrency = value }
                            Spacer(Modifier.padding(3.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = ::scout,
                            enabled = !scanning,
                            modifier = Modifier.weight(1f)
                        ) { Text(Texts.scoutSnis) }
                        Button(
                            onClick = ::scan,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (scanning) Texts.stopScan else Texts.startScan) }
                    }
                    if (progress.isNotBlank()) {
                        Text(
                            text = progress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (status.isNotBlank()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (hits.isNotEmpty()) {
                        hits.take(12).forEach { hit ->
                            Text(
                                text = Texts.edgeRow(
                                    hit.ip, hit.sni, hit.host, hit.latencyMs,
                                    hit.successes, hit.tries
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = ::applyScan, modifier = Modifier.weight(1f)) {
                                Text(Texts.useTop5)
                            }
                            OutlinedButton(onClick = ::copyHits, modifier = Modifier.weight(1f)) {
                                Text(Texts.copyAll)
                            }
                        }
                    }
                }

                ToolsCard(Texts.vpngate, Texts.vpngateHint) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = Texts.replaceLists,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = gateReplace,
                            onCheckedChange = {
                                gateReplace = it
                                Prefs.replaceToolLists = it
                            }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = Texts.vpngateFilter,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = gateFilter,
                            onCheckedChange = {
                                gateFilter = it
                                Prefs.vpngateFilterCountries = it
                            }
                        )
                    }
                    if (gateServers.isNotEmpty() && shown.size != gateServers.size) {
                        Text(
                            text = Texts.filterResult(gateServers.size - shown.size, shown.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = ::fetchGate,
                            enabled = !gateBusy,
                            modifier = Modifier.weight(1f)
                        ) { Text(Texts.fetchServers) }
                        OutlinedButton(
                            onClick = ::testGate,
                            enabled = !gateBusy && shown.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text(Texts.testHealth) }
                    }
                    if (gateStatus.isNotBlank()) {
                        Text(
                            text = gateStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    shown.take(20).forEach { server ->
                        val state = health[server.endpoint.toString()]
                        Text(
                            text = buildString {
                                append(server.ip).append(':').append(server.port)
                                if (server.country.isNotBlank()) append(" · ").append(server.country)
                                if (server.pingMs > 0) append(" · ").append(server.pingMs).append(" ms")
                                if (state != null) append(" · ").append(state.name)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (state) {
                                SstpHealth.Alive -> MaterialTheme.colorScheme.primary
                                SstpHealth.TcpOnly -> MaterialTheme.colorScheme.tertiary
                                SstpHealth.Dead -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { applyGate(20) },
                            enabled = shown.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text(Texts.useTop20) }
                        OutlinedButton(
                            onClick = { applyGate(5) },
                            enabled = shown.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text(Texts.useTop5) }
                    }
                    TextButton(
                        onClick = ::restoreGate,
                        enabled = installedRelays > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(Texts.restoreBuiltIn) }
                    Text(
                        text = if (installedRelays > 0) {
                            Texts.relaysInstalled(installedRelays)
                        } else {
                            Texts.builtInInUse
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolsCard(
    title: String,
    subtitle: String?,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
private fun Chip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
