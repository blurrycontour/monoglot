package se.svenska.trainer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.svenska.trainer.player.NowPlaying
import se.svenska.trainer.player.PlaybackHolder
import se.svenska.trainer.ui.util.formatDuration

/**
 * Persistent mini player, in the manner of Spotify or YouTube: leaving the
 * player screen must not feel like stopping playback. Tapping it reopens the
 * full screen; the transport stays reachable without doing so.
 */
@Composable
fun MiniPlayer(
    now: NowPlaying,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(now.progress, tween(220), label = "miniProgress")

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {},
            )
            Row(
                Modifier
                    .clickable(onClick = onExpand)
                    .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title.ifBlank { "Now playing" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatDuration(now.positionMs)} / ${formatDuration(now.durationMs)}" +
                            if (now.speed != 1.0f) "  ·  ${trimSpeed(now.speed)}×" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { PlaybackHolder.skip(-5000) }) {
                    Icon(Icons.Default.Replay, "Back 5 seconds", Modifier.size(20.dp))
                }
                FilledIconButton(
                    onClick = { PlaybackHolder.playPause() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (now.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (now.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = { PlaybackHolder.stop() }) {
                    Icon(Icons.Default.Close, "Close player", Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun MiniPlayerHost(
    visible: Boolean,
    now: NowPlaying,
    onExpand: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible && now.active,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        MiniPlayer(now = now, onExpand = onExpand)
    }
}

fun trimSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}" else "%.2f".format(speed).trimEnd('0').trimEnd('.')

