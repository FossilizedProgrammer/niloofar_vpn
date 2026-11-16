package com.ffvpn.client.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ffvpn.client.R
import com.ffvpn.client.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class VpnApp(val packageName: String, val label: String)

@Composable
fun SplitTunnelPicker(
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var selection by remember { mutableStateOf(initialSelection - context.packageName) }
    var search by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<VpnApp>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            val installed = manager.getInstalledPackages(0)
                .filter { pkg ->
                    pkg.packageName != context.packageName &&
                        (manager.checkPermission(Manifest.permission.INTERNET, pkg.packageName) ==
                            PackageManager.PERMISSION_GRANTED || pkg.packageName in initialSelection)
                }
                .map { pkg ->
                    VpnApp(
                        pkg.packageName,
                        pkg.applicationInfo?.loadLabel(manager)?.toString() ?: pkg.packageName
                    )
                }
            val missing = initialSelection - context.packageName - installed.map { it.packageName }.toSet()
            (installed + missing.map { VpnApp(it, it) }).sortedWith(
                compareBy({ it.label.lowercase(Locale.getDefault()) }, { it.packageName })
            )
        }
    }

    val shown = apps.orEmpty().filter {
        it.label.contains(search.trim(), ignoreCase = true) ||
            it.packageName.contains(search.trim(), ignoreCase = true)
    }

    // Material3 1.2.x sheets overlay the IME on older Android versions.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            ProvideAppTextToolbar {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = Texts.splitTunnel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text(Texts.splitTunnelSearch) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = Texts.splitTunnelCount(selection.size),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = selection.isNotEmpty(),
                            onClick = { selection = emptySet() }
                        ) { Text(Texts.splitTunnelClear) }
                    }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        item(key = "hint") {
                            Text(
                                text = Texts.splitTunnelHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = Texts.splitTunnelUidHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        when {
                            apps == null -> item(key = "loading") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator() }
                            }
                            shown.isEmpty() -> item(key = "empty") {
                                Text(Texts.splitTunnelEmpty, Modifier.padding(vertical = 16.dp))
                            }
                            else -> items(shown, key = { it.packageName }) { app ->
                                val checked = app.packageName in selection
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(checked, role = Role.Checkbox) { selected ->
                                            selection = if (selected) selection + app.packageName
                                                else selection - app.packageName
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = Texts.ui(app.label),
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = Texts.ui(app.packageName),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Checkbox(checked = checked, onCheckedChange = null)
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_next_connect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text(Texts.cancel) }
                        Button(onClick = { onSave(selection) }) { Text(Texts.splitTunnelSave) }
                    }
                }
            }
        }
    }
}
