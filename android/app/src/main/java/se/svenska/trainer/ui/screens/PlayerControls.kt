package se.svenska.trainer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.svenska.trainer.player.PlayerState
import se.svenska.trainer.player.PlayerViewModel

private val SPEEDS = listOf(0.75f, 0.85f, 1.0f)

@Composable
fun Controls(vm: PlayerViewModel, state: PlayerState) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Scrubber(state) { vm.seekTo(it) }
            Spacer(Modifier.height(6.dp))

            // Replay-sentence is the most-used control after play, so it gets
            // a full-width target of its own rather than competing for space
            // in the transport row.
            FilledTonalButton(
                onClick = { vm.replaySegment() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Replay sentence", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.previousSegment() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous sentence", Modifier.size(28.dp))
                }
                IconButton(onClick = { vm.skip(-5000) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Replay5, "Back 5 seconds", Modifier.size(28.dp))
                }

                FilledIconButton(
                    onClick = { vm.playPause() },
                    modifier = Modifier.size(68.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(onClick = { vm.skip(5000) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Forward5, "Forward 5 seconds", Modifier.size(28.dp))
                }
                IconButton(onClick = { vm.nextSegment() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Next sentence", Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.height(6.dp))
            SpeedSelector(state.speed) { vm.setSpeed(it) }
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

@Composable
private fun SpeedSelector(current: Float, onSelect: (Float) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        SPEEDS.forEachIndexed { i, speed ->
            SegmentedButton(
                selected = kotlin.math.abs(current - speed) < 0.01f,
                onClick = { onSelect(speed) },
                shape = SegmentedButtonDefaults.itemShape(i, SPEEDS.size),
            ) {
                Text(if (speed == 1.0f) "1×" else "${speed}×")
            }
        }
    }
}

fun formatTime(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
