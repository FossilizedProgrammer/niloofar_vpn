package com.ffvpn.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ffvpn.client.Texts
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.ServerChoice
import com.ffvpn.client.core.ServerOption
import com.ffvpn.client.vpn.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The manual server choice under the core picker: a full-width card in the
 * secondary accent, unlike the core pills, with an arrow that says it opens a
 * list. Auto keeps the scan-and-rank behaviour; a named server is dialled on
 * its own. Only the cores with a fixed server list offer it.
 */
@Composable
fun ServerChip(coreId: String, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    var label by remember(coreId) { mutableStateOf(Prefs.serverLabel(coreId)) }
    val accent = MaterialTheme.colorScheme.secondary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = if (enabled) 0.14f else 0.07f),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.55f else 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { open = true }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Texts.serverLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = label.ifBlank { Texts.auto },
                    style = MaterialTheme.typography.bodyLarge,
                    color = accent.copy(alpha = if (enabled) 1f else 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = accent.copy(alpha = if (enabled) 1f else 0.6f)
            )
        }
    }
    if (open) {
        ServerPickerSheet(
            coreId = coreId,
            onDismiss = { open = false },
            onPick = { key, picked ->
                Prefs.setServer(coreId, key, picked)
                label = picked
                open = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPickerSheet(
    coreId: String,
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val restored = remember { ServerChoice.lastTested(context, coreId) }
    var options by remember { mutableStateOf(restored ?: ServerChoice.options(context, coreId)) }
    val selected = remember { Prefs.server(coreId) }
    var refreshing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(restored != null) }
    var status by remember {
        mutableStateOf(
            when {
                restored == null -> ""
                else -> testedStatus(coreId, restored.size)
            }
        )
    }
    val stop = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { stop.set(true) } }

    // A pinned server the list no longer names stays visible and selectable;
    // once a test has run and it did not survive, it is marked unreachable.
    val shown = if (selected.isNotBlank() && options.none { it.key == selected }) {
        listOf(
            ServerOption(
                selected,
                Prefs.serverLabel(coreId).ifBlank { selected },
                "",
                reachable = if (tested) false else null
            )
        ) + options
    } else {
        options
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = Texts.serverTitle(Texts.coreName(coreId)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (coreId == "sstp") Texts.serverHintSstp else Texts.serverHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                enabled = !refreshing,
                onClick = {
                    refreshing = true
                    status = Texts.serverRefreshing
                    stop.set(false)
                    scope.launch {
                        val list = withContext(Dispatchers.IO) {
                            ServerChoice.tested(context, coreId, { stop.get() }) {
                                Logs.add(coreId, it)
                            }
                        }
                        refreshing = false
                        tested = true
                        options = list
                        status = testedStatus(coreId, list.size)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(Texts.serverRefresh) }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                item(key = "") {
                    ServerRow(Texts.serverAuto, "", selected.isBlank()) { onPick("", "") }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = Texts.serverEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }
                items(shown, key = { it.key }) { option ->
                    ServerRow(
                        label = option.label,
                        detail = option.detail,
                        selected = option.key == selected,
                        reachable = option.reachable,
                        latencyMs = option.latencyMs
                    ) { onPick(option.key, option.label) }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    label: String,
    detail: String,
    selected: Boolean,
    reachable: Boolean? = null,
    latencyMs: Int = -1,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val line = when {
        latencyMs >= 0 && detail.isBlank() -> "$latencyMs ms"
        latencyMs >= 0 -> "$detail · $latencyMs ms"
        else -> detail
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface
            )
            if (line.isNotBlank()) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (reachable == false) {
            Text(
                text = Texts.serverUnreachable,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        if (selected) {
            Text(text = "✓", color = accent, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Fluxcore's rows come back in the fleet's own load order, not by latency. */
private fun testedStatus(coreId: String, count: Int): String = when {
    count == 0 -> Texts.serverNoneReachable
    coreId == "fluxcore" -> "$count ${Texts.serverTestedLoad}"
    else -> Texts.serverTested(count)
}