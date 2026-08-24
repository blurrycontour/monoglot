package io.blurrycontour.monoglot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.update.UpdateState
import io.blurrycontour.monoglot.ui.util.formatBytesShort

/**
 * Checks for a newer build on launch and shows a dialog if one exists. Silent
 * when up to date: an update check that congratulates you on every launch is
 * noise.
 */
@Composable
fun UpdateGate() {
    val updater = Graph.updater
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var checked by remember { mutableStateOf(false) }
    // Dismissed by hand while the download runs. Reset by the updater going
    // back to idle, which is what happens once the install prompt is answered.
    var hidden by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        val settings = Graph.repository.settings
        if (!settings.autoUpdateFlow.first()) return@LaunchedEffect
        updater.check(settings.serverUrl(), silent = true)
    }

    val version = when (val s = state) {
        is UpdateState.Available -> s.version
        is UpdateState.Downloading -> s.version
        is UpdateState.ReadyToInstall -> s.version
        is UpdateState.Installing -> s.version
        else -> null
    } ?: run {
        hidden = false
        return
    }
    if (hidden) return

    AlertDialog(
        onDismissRequest = {
            // Not dismissible mid-download: clicking away is exactly what the
            // progress display exists to prevent.
            if (state is UpdateState.Available) updater.reset()
        },
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text(if (state is UpdateState.Available) "Update available" else "Updating") },
        text = {
            // Full width in every state. The progress bar that appears once
            // downloading starts is the only thing that filled the dialog, so
            // the dialog used to grow the moment Update was tapped.
            Column(Modifier.fillMaxWidth()) {
                Text(
                    version.versionName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Installed: ${updater.currentVersionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                when (val s = state) {
                    is UpdateState.Available -> Text(
                        "Download size ${formatBytesShort(version.sizeBytes)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${formatBytesShort(s.bytes)} of ${formatBytesShort(version.sizeBytes)}" +
                                "  ·  ${(s.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is UpdateState.ReadyToInstall, is UpdateState.Installing -> Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Installing…", style = MaterialTheme.typography.bodySmall)
                    }

                else -> Unit
                }
            }
        },
        // Both slots always have content. An AlertDialog still reserves its
        // button row when the slots render nothing, which is where the empty
        // band under the progress bar came from.
        confirmButton = {
            if (state is UpdateState.Available) {
                TextButton(onClick = {
                    scope.launch {
                        val settings = Graph.repository.settings
                        if (!updater.canInstall()) {
                            context.startActivity(updater.installPermissionIntent())
                            return@launch
                        }
                        updater.downloadAndInstall(settings.serverUrl(), version)
                    }
                }) { Text(if (updater.canInstall()) "Update" else "Allow installs") }
            } else {
                // The download runs in the updater, not in this dialog, so
                // getting out of the way does not cancel it.
                TextButton(onClick = { hidden = true }) { Text("Hide") }
            }
        },
        dismissButton = {
            if (state is UpdateState.Available) {
                TextButton(onClick = { updater.reset() }) { Text("Later") }
            }
        },
    )
}

/** Manual check, for the Settings screen. */
@Composable
fun UpdateSection() {
    val updater = Graph.updater
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Installed version", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${updater.currentVersionName}  (build ${updater.currentVersionCode})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (val s = state) {
            is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking…", style = MaterialTheme.typography.bodySmall)
            }

            is UpdateState.UpToDate -> Text(
                "Up to date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            is UpdateState.Failed -> Text(
                s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            else -> Unit
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    updater.check(Graph.repository.settings.serverUrl(), silent = false)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Check for updates") }

        if (!updater.canInstall()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Android needs permission to install apps from Monoglot before an " +
                    "update can be applied.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { context.startActivity(updater.installPermissionIntent()) }) {
                Text("Grant permission")
            }
        }
    }
}
