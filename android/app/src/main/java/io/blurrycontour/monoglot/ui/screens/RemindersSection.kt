package io.blurrycontour.monoglot.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.blurrycontour.monoglot.reminders.Reminder
import io.blurrycontour.monoglot.reminders.ReminderScheduler
import io.blurrycontour.monoglot.reminders.ReminderStore
import io.blurrycontour.monoglot.reminders.Repeat
import java.util.UUID

@Composable
fun RemindersSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ReminderStore(context.applicationContext) }
    val reminders by store.remindersFlow.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Column {
        if (reminders.isEmpty()) {
            Text(
                "No reminders. Add one to be nudged to listen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        reminders.forEach { reminder ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { editing = reminder; showEditor = true }
                ) {
                    Text(
                        reminder.describe(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (reminder.label.isNotBlank()) {
                        Text(
                            reminder.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = reminder.enabled,
                    onCheckedChange = { on ->
                        scope.launch {
                            store.setEnabled(reminder.id, on)
                            val updated = reminder.copy(enabled = on)
                            if (on) ReminderScheduler.schedule(context, updated)
                            else ReminderScheduler.cancel(context, updated)
                        }
                    },
                )
                IconButton(onClick = {
                    scope.launch {
                        ReminderScheduler.cancel(context, reminder)
                        store.delete(reminder.id)
                    }
                }) {
                    Icon(Icons.Default.Delete, "Delete reminder", Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { editing = null; showEditor = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add reminder")
        }
    }

    if (showEditor) {
        ReminderEditor(
            existing = editing,
            onDismiss = { showEditor = false },
            onSave = { reminder ->
                scope.launch {
                    store.upsert(reminder)
                    ReminderScheduler.schedule(context, reminder)
                }
                showEditor = false
            },
        )
    }
}

/**
 * Uses Android's own time picker rather than custom steppers: it is the
 * control people already know, handles 12/24 hour preference, and is reachable
 * with one gesture instead of up to twelve taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderEditor(
    existing: Reminder?,
    onDismiss: () -> Unit,
    onSave: (Reminder) -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = existing?.hour ?: 19,
        initialMinute = existing?.minute ?: 0,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    var days by remember {
        mutableStateOf(existing?.repeat?.days ?: setOf(1, 2, 3, 4, 5))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New reminder" else "Edit reminder") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = timeState)

                Spacer(Modifier.height(12.dp))
                Text(
                    "Repeat on",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.Start),
                )
                Spacer(Modifier.height(8.dp))

                // Evenly divided so the seventh day always has room; a fixed
                // Row previously clipped Sunday's label to nothing.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    (1..7).forEach { d ->
                        val on = d in days
                        Surface(
                            onClick = { days = if (on) days - d else days + d },
                            shape = CircleShape,
                            color = if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    Reminder.dayInitial(d),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                // Equal weights with single-line labels: as TextButtons these
                // wrapped, and "Weekends" broke across two lines on a narrow
                // screen.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "All" to (1..7).toSet(),
                        "Mon-Fri" to (1..5).toSet(),
                        "Sat-Sun" to setOf(6, 7),
                    ).forEach { (label, set) ->
                        OutlinedButton(
                            onClick = { days = set },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

                if (days.isEmpty()) {
                    Text(
                        "Pick at least one day.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = days.isNotEmpty(),
                onClick = {
                    onSave(
                        Reminder(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            hour = timeState.hour,
                            minute = timeState.minute,
                            repeat = Repeat(days),
                            enabled = true,
                            label = existing?.label.orEmpty(),
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
