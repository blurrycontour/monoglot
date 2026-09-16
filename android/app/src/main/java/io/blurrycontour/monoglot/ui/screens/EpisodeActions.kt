package io.blurrycontour.monoglot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.data.ItemMeta
import io.blurrycontour.monoglot.ui.util.formatBytesShort
import java.util.Locale

/**
 * The per-episode actions, shared by the library card and the player.
 *
 * One definition rather than two: the same episode offers the same three
 * things wherever you are looking at it, and having them only in the list
 * meant leaving the player to save the thing you were listening to.
 */
@Composable
fun EpisodeActionsMenu(
    itemId: Int,
    downloaded: Boolean,
    hasProgress: Boolean,
    onToggleDownload: () -> Unit,
    onClearProgress: () -> Unit,
    onArchive: () -> Unit,
    iconSize: Int = 20,
) {
    var open by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }

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
            DropdownMenuItem(
                text = { Text("Episode details") },
                leadingIcon = { Icon(Icons.Default.Info, null) },
                onClick = { open = false; details = true },
            )
            HorizontalDivider()
            DropdownMenuItem(
                // Named for what it does to this episode. "Free up server
                // space" is the System screen's bulk action, and reading the
                // same words here suggested this one did the same thing.
                text = { Text("Remove from server") },
                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                onClick = { open = false; onArchive() },
            )
        }
    }

    if (details) {
        EpisodeDetailsDialog(itemId = itemId, onDismiss = { details = false })
    }
}

/**
 * How and when this episode was made. Fetched on open rather than carried in
 * the list, so the library query stays lean; everything is best-effort and a
 * missing value reads as a dash, because episodes made before this was recorded
 * genuinely have none of it.
 */
@Composable
private fun EpisodeDetailsDialog(itemId: Int, onDismiss: () -> Unit) {
    var meta by remember(itemId) { mutableStateOf<ItemMeta?>(null) }
    var failed by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(itemId) {
        runCatching { Graph.repository.api.itemMeta(itemId) }
            .onSuccess { meta = it }
            .onFailure { failed = true }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Episode details") },
        text = {
            when {
                failed -> Text("Could not load details.")
                meta == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading…")
                }
                else -> DetailsBody(meta!!)
            }
        },
    )
}

@Composable
private fun DetailsBody(m: ItemMeta) {
    Column {
        DetailRow("Status", m.status.ifBlank { "—" })
        DetailRow("Transcribed", prettyDateTime(m.transcribedAt))
        DetailRow("Model", m.transcribeModel.ifBlank { "—" })
        DetailRow("Download time", fmtDuration(m.downloadMs))
        DetailRow("Transcribe time", fmtDuration(m.transcribeMs))
        DetailRow("Audio size", m.audioBytes?.let { formatBytesShort(it) } ?: "—")
        DetailRow("Length", if (m.durationMs > 0) fmtClock(m.durationMs) else "—")
        DetailRow("Segments", if (m.segmentCount > 0) "${m.segmentCount}" else "—")
        DetailRow("Words", if (m.wordCount > 0) "${m.wordCount}" else "—")
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** Server timestamps come as "YYYY-MM-DD HH:MM:SS"; drop the seconds. */
private fun prettyDateTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    val cleaned = raw.replace('T', ' ')
    return if (cleaned.length >= 16) cleaned.substring(0, 16) else cleaned
}

private fun fmtDuration(ms: Int?): String {
    if (ms == null || ms <= 0) return "—"
    return when {
        ms < 1000 -> "$ms ms"
        ms < 60_000 -> "%.1f s".format(Locale.ROOT, ms / 1000.0)
        else -> "%d min %02d s".format(Locale.ROOT, ms / 60_000, (ms % 60_000) / 1000)
    }
}

private fun fmtClock(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(Locale.ROOT, total / 60, total % 60)
}
