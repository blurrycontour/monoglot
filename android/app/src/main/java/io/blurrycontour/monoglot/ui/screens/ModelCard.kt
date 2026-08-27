package io.blurrycontour.monoglot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.blurrycontour.monoglot.data.ModelSettings

/**
 * Which weights the server transcribes with.
 *
 * On the System tab rather than in Settings because it is a property of the
 * server, not of this phone — the same reasoning that put the ingest schedule
 * here. Choosing does not restart anything: the model id travels with each
 * transcription request, so the worker picks it up on the next episode and
 * swaps in memory.
 */
@Composable
fun ModelCard(
    settings: ModelSettings,
    checking: Boolean,
    error: String?,
    onChoose: (String) -> Unit,
) {
    SectionCard("Transcription model") {
        Text(
            "Bigger models hear more and cost more memory and time. The change " +
                "applies to the next episode transcribed; episodes already done " +
                "are not redone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        val current = settings.model.ifBlank { settings.default }
        settings.suggested.forEach { option ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = option.id == current,
                    onClick = { if (!checking) onChoose(option.id) },
                    enabled = !checking,
                )
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        option.id.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (option.id == current) FontWeight.SemiBold
                        else FontWeight.Normal,
                    )
                    if (option.note.isNotBlank()) {
                        Text(
                            option.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Anything the worker can load is allowed, not just the five above:
        // the suggestions exist so the common choice is not a typing exercise,
        // not to make this a whitelist.
        val unlisted = settings.suggested.none { it.id == current }
        // Seeded with the current id when it is not one of the suggestions,
        // so an already-custom model is shown rather than hidden behind a
        // button that reads as though nothing is set.
        var custom by remember(current) { mutableStateOf(if (unlisted) current else "") }
        var open by remember(unlisted) { mutableStateOf(unlisted) }

        Spacer(Modifier.height(4.dp))
        if (!open) {
            TextButton(onClick = { open = true }, enabled = !checking) {
                Text("Use another model")
            }
        } else {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("Hugging Face model id") },
                placeholder = { Text("KBLab/kb-whisper-medium") },
                singleLine = true,
                enabled = !checking,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { open = false; custom = "" }, enabled = !checking) {
                    Text("Cancel")
                }
                TextButton(
                    onClick = { onChoose(custom.trim()) },
                    enabled = !checking && custom.isNotBlank(),
                ) { Text("Check and use") }
            }
        }

        if (checking) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Checking the model exists…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                error,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The server's own reason, out of the transport's wrapper.
 *
 * A rejected model arrives as `HTTP 400: {"error":"…"}`. The part worth
 * showing is the message — "no CTranslate2 model.bin" tells you what to do
 * next, and "HTTP 400" does not.
 */
fun readableError(t: Throwable): String {
    val raw = t.message ?: return "Could not save that model"
    val at = raw.indexOf("\"error\":\"")
    if (at < 0) return raw
    val rest = raw.substring(at + 9)
    val end = rest.indexOf('"')
    val msg = if (end < 0) rest else rest.substring(0, end)
    return msg.ifBlank { raw }
}
