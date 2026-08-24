package io.blurrycontour.monoglot.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The per-episode actions, shared by the library card and the player.
 *
 * One definition rather than two: the same episode offers the same three
 * things wherever you are looking at it, and having them only in the list
 * meant leaving the player to save the thing you were listening to.
 */
@Composable
fun EpisodeActionsMenu(
    downloaded: Boolean,
    hasProgress: Boolean,
    onToggleDownload: () -> Unit,
    onClearProgress: () -> Unit,
    onArchive: () -> Unit,
    iconSize: Int = 20,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.MoreVert, "More actions", Modifier.size(iconSize.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (downloaded) "Remove download" else "Save for offline") },
                leadingIcon = {
                    Icon(
                        if (downloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                        null,
                    )
                },
                onClick = { open = false; onToggleDownload() },
            )
            if (hasProgress) {
                DropdownMenuItem(
                    text = { Text("Clear progress") },
                    leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                    onClick = { open = false; onClearProgress() },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Free up server space") },
                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                onClick = { open = false; onArchive() },
            )
        }
    }
}
