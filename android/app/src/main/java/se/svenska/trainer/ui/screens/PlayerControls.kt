package se.svenska.trainer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.svenska.trainer.data.TranscriptMode
import se.svenska.trainer.player.PlayerState
import se.svenska.trainer.player.PlayerViewModel

// 0.5x to 2.0x. The old 0.75/0.85/1.0 range was too narrow to hear: 0.85 to
// 1.0 is a 15% change, right at the threshold of perception for speech.
private val SPEEDS = listOf(0.5f, 0.6f, 0.75f, 0.85f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/**
 * Transport. Replay-sentence is the most-used control after play, so it holds
 * the centre of its row; speed and transcript visibility flank it as compact
 * affordances that open a sheet rather than occupying permanent rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Controls(vm: PlayerViewModel, state: PlayerState) {
    var speedSheet by remember { mutableStateOf(false) }
    var textSheet by remember { mutableStateOf(false) }

    // Opaque and flush to the bottom edge. It previously floated on a
    // transparent scaffold, leaving a gap above the gesture bar and letting the
    // transcript show through behind the controls.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Scrubber(state) { vm.seekTo(it) }
            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PillButton(
                    label = "${trimSpeed(state.speed)}×",
                    icon = Icons.Default.Speed,
                    contentDescription = "Playback speed",
                    onClick = { speedSheet = true },
                    // Non-default speed is a state worth seeing at a glance.
                    highlighted = kotlin.math.abs(state.speed - 1.0f) > 0.01f,
                )

                FilledTonalButton(
                    onClick = { vm.replaySegment() },
                    modifier = Modifier.weight(1f).height(56.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Replay line", fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                }

                PillButton(
                    label = state.transcriptMode.shortLabel(),
                    icon = state.transcriptMode.icon(),
                    contentDescription = "Transcript visibility",
                    onClick = { textSheet = true },
                    highlighted = state.transcriptMode != TranscriptMode.HIDDEN,
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.previousSegment() }, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous sentence", Modifier.size(26.dp))
                }
                IconButton(onClick = { vm.skip(-5000) }, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.Replay5, "Back 5 seconds", Modifier.size(26.dp))
                }
                FilledIconButton(
                    onClick = { vm.playPause() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = { vm.skip(5000) }, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.Forward5, "Forward 5 seconds", Modifier.size(26.dp))
                }
                IconButton(onClick = { vm.nextSegment() }, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.SkipNext, "Next sentence", Modifier.size(26.dp))
                }
            }
        }
    }

    if (speedSheet) {
        SpeedSheet(state.speed, onSelect = { vm.setSpeed(it) }, onDismiss = { speedSheet = false })
    }
    if (textSheet) {
        TranscriptSheet(
            state.transcriptMode,
            onSelect = { vm.setTranscriptMode(it); textSheet = false },
            onDismiss = { textSheet = false },
        )
    }
}

private fun TranscriptMode.shortLabel() = when (this) {
    TranscriptMode.HIDDEN -> "Off"
    TranscriptMode.LINE -> "Line"
    TranscriptMode.REVEAL -> "Tap"
    TranscriptMode.FULL -> "All"
}

private fun TranscriptMode.icon() = when (this) {
    TranscriptMode.HIDDEN -> Icons.Default.VisibilityOff
    TranscriptMode.LINE -> Icons.Default.Subtitles
    TranscriptMode.REVEAL -> Icons.Default.Visibility
    TranscriptMode.FULL -> Icons.Default.Article
}

@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    // Sized to match the Replay button and given a visible border: on
    // surfaceVariant against a dark surface these were nearly invisible.
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (highlighted) null
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.height(56.dp).widthIn(min = 76.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(19.dp),
                tint = fg,
            )
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, color = fg)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Text("Playback speed", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Slower speeds are the point: fluent Swedish is hard to parse at full pace.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(current - speed) < 0.01f,
                        onClick = { onSelect(speed) },
                        label = { Text("${trimSpeed(speed)}×") },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptSheet(
    current: TranscriptMode,
    onSelect: (TranscriptMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(TranscriptMode.HIDDEN, "Hidden", "No text. Listen first."),
        Triple(TranscriptMode.LINE, "One line, always",
            "The sentence playing now, always on screen."),
        Triple(TranscriptMode.REVEAL, "One line, on request",
            "Blank until you ask, then re-hides when the sentence ends."),
        Triple(TranscriptMode.FULL, "Full transcript",
            "Everything, with the spoken word highlighted."),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 30.dp)) {
            Text(
                "Transcript",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 22.dp, bottom = 8.dp),
            )
            options.forEach { (mode, title, subtitle) ->
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        RadioButton(selected = current == mode, onClick = { onSelect(mode) })
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun Scrubber(state: PlayerState, onSeek: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else state.positionMs.toFloat()

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value.coerceIn(0f, duration.toFloat()),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; onSeek(dragValue.toInt()) },
            valueRange = 0f..duration.toFloat(),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(value.toInt()), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}


fun formatTime(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
