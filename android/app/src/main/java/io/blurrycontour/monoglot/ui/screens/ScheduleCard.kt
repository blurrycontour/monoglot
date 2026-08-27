package io.blurrycontour.monoglot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.blurrycontour.monoglot.data.Schedule
import java.time.Duration
import java.time.OffsetDateTime

/**
 * When the server fetches and transcribes without being asked.
 *
 * This used to be two environment variables read once at boot, which made a
 * change a file edit and a container restart on a machine that otherwise needs
 * neither. A server with no times set runs nothing on its own — the honest
 * default for a personal server, and the reason the empty state says so
 * plainly rather than looking like something failed to load.
 */
@Composable
fun ScheduleCard(
    schedules: List<Schedule>,
    nextRun: String?,
    onAdd: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    SectionCard("Scheduled ingest") {
        if (schedules.isEmpty()) {
            Text(
                "Nothing runs on its own. Add a time and the server will fetch and " +
                    "transcribe new episodes then.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            schedules.forEach { s ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(s.label, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { onDelete(s.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove ${s.label}",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            nextRun?.let {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                StatRow("Next run", untilLabel(it))
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add a time")
        }
        Text(
            "The server's clock, in its own timezone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    if (picking) {
        TimeDialog(
            onDismiss = { picking = false },
            onConfirm = { h, m -> picking = false; onAdd(h, m) },
        )
    }
}

/**
 * How long until the next run, rather than the clock time it will happen at.
 *
 * A duration is the one form that is right in both timezones: the server names
 * an instant, and this phone may well not be in the same country as the server.
 * Printing its wall clock would be confidently wrong twice a year at minimum.
 */
private fun untilLabel(iso: String): String {
    val until = runCatching {
        Duration.between(OffsetDateTime.now(), OffsetDateTime.parse(iso))
    }.getOrNull() ?: return "—"

    val minutes = until.toMinutes()
    return when {
        minutes < 1 -> "any moment"
        minutes < 60 -> "in $minutes min"
        minutes < 120 -> "in 1h ${minutes % 60}m"
        else -> "in ${minutes / 60}h ${minutes % 60}m"
    }
}

/**
 * Typed entry rather than the dial: a scheduled time is an exact number
 * somebody already has in mind, and the dial is both wider than a dialog wants
 * to be and slower for a value like 03:30.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = 3, initialMinute = 30, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run at") },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
