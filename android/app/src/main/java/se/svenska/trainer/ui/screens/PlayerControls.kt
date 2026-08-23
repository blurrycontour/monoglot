package se.svenska.trainer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

            Spacer(Modifier.height(10.dp))
            TranscriptModeRow(state.transcriptMode) { vm.setTranscriptMode(it) }
            Spacer(Modifier.height(8.dp))
            SpeedSelector(state.speed) { vm.setSpeed(it) }
        }
    }
}

/**
 * Transcript visibility, in the transport bar rather than buried in the app
 * bar: switching between listening blind and reading along is a thing you do
 * constantly while playing, not a settings decision.
 */
@Composable
private fun TranscriptModeRow(current: TranscriptMode, onSelect: (TranscriptMode) -> Unit) {
    val options = listOf(
        Triple(TranscriptMode.HIDDEN, Icons.Default.VisibilityOff, "Hidden"),
        Triple(TranscriptMode.REVEAL, Icons.Default.Visibility, "Reveal"),
        Triple(TranscriptMode.FULL, Icons.Default.Article, "Full"),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (mode, icon, label) ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 13.sp)
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

/**
 * Nine speeds do not fit across a phone, so this scrolls and auto-centres the
 * active one. Kept as discrete chips rather than a slider: you want to return
 * to exactly 0.75x, not approximately.
 */
@Composable
private fun SpeedSelector(current: Float, onSelect: (Float) -> Unit) {
    val listState = rememberLazyListState()
    val currentIndex = SPEEDS.indexOfFirst { kotlin.math.abs(current - it) < 0.01f }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex.coerceAtLeast(0), scrollOffset = -160)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Speed,
            contentDescription = "Playback speed",
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(SPEEDS.size) { i ->
                val speed = SPEEDS[i]
                val selected = kotlin.math.abs(current - speed) < 0.01f
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(speed) },
                    label = { Text("${trimSpeed(speed)}×", fontSize = 13.sp) },
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}

fun formatTime(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
