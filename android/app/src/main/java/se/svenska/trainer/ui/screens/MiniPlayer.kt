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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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

    // Deliberately more present than the surrounding chrome: it is a live
    // control, and at surfaceVariant it read as part of the navigation bar.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            // Modest shadow: a large soft one reads as a pale band above the
            // bar and visually eats content behind it.
            .shadow(6.dp, RoundedCornerShape(14.dp), clip = false),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                drawStopIndicator = {},
            )
            Row(
                Modifier
                    .clickable(onClick = onExpand)
                    .padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    AppMark(Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title.ifBlank { "Now playing" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatDuration(now.positionMs)} / ${formatDuration(now.durationMs)}" +
                            if (now.speed != 1.0f) "  ·  ${trimSpeed(now.speed)}×" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { PlaybackHolder.skip(-5000) }) {
                    Icon(Icons.Default.Replay, "Back 5 seconds", Modifier.size(20.dp))
                }
                FilledIconButton(
                    onClick = { PlaybackHolder.playPause() },
                    modifier = Modifier.size(36.dp),
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
        // The host itself must not paint: AnimatedVisibility otherwise leaves a
        // full-width band behind the rounded mini player.
        Box(Modifier.fillMaxWidth()) {
            MiniPlayer(now = now, onExpand = onExpand)
        }
    }
}

fun trimSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}" else "%.2f".format(speed).trimEnd('0').trimEnd('.')

