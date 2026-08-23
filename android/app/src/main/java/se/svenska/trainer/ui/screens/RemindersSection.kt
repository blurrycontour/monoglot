package se.svenska.trainer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import se.svenska.trainer.reminders.Reminder
import se.svenska.trainer.reminders.ReminderScheduler
import se.svenska.trainer.reminders.ReminderStore
import se.svenska.trainer.reminders.Repeat
import java.time.DayOfWeek
import java.time.LocalDate
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

@Composable
private fun ReminderEditor(
    existing: Reminder?,
    onDismiss: () -> Unit,
    onSave: (Reminder) -> Unit,
) {
    var hour by remember { mutableIntStateOf(existing?.hour ?: 19) }
    var minute by remember { mutableIntStateOf(existing?.minute ?: 0) }
    var mode by remember {
        mutableStateOf(if (existing?.repeat is Repeat.EveryNDays) 1 else 0)
    }
    var days by remember {
        mutableStateOf((existing?.repeat as? Repeat.Weekdays)?.days ?: setOf(1, 2, 3, 4, 5))
    }
    var interval by remember {
        mutableIntStateOf((existing?.repeat as? Repeat.EveryNDays)?.n ?: 2)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New reminder" else "Edit reminder") },
        text = {
            Column {
                Text("Time", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberStepper(hour, 0, 23) { hour = it }
                    Text(" : ", style = MaterialTheme.typography.titleLarge)
                    NumberStepper(minute, 0, 55, step = 5) { minute = it }
                }

                Spacer(Modifier.height(16.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Weekdays", "Interval").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = mode == i,
                            onClick = { mode = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (mode == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (1..7).forEach { d ->
                            val on = d in days
                            FilterChip(
                                selected = on,
                                onClick = {
                                    days = if (on) days - d else days + d
                                },
                                label = {
                                    Text(
                                        DayOfWeek.of(d).name.take(1),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.size(38.dp),
                            )
                        }
                    }
                    if (days.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Pick at least one day.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every ")
                        NumberStepper(interval, 1, 30) { interval = it }
                        Text(" day${if (interval == 1) "" else "s"}")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Counted from today.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mode == 1 || days.isNotEmpty(),
                onClick = {
                    val repeat = if (mode == 0) {
                        Repeat.Weekdays(days)
                    } else {
                        Repeat.EveryNDays(interval, LocalDate.now().toEpochDay())
                    }
                    onSave(
                        Reminder(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            hour = hour,
                            minute = minute,
                            repeat = repeat,
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

@Composable
private fun NumberStepper(value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onChange(if (value - step < min) max else value - step) },
            modifier = Modifier.size(32.dp),
        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(
            "%02d".format(value),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(
            onClick = { onChange(if (value + step > max) min else value + step) },
            modifier = Modifier.size(32.dp),
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}
