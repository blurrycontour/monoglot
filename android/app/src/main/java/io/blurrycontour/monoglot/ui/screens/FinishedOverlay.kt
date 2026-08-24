package io.blurrycontour.monoglot.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.blurrycontour.monoglot.data.EpisodeSummary
import kotlin.math.sin
import kotlin.random.Random

/**
 * End of an episode.
 *
 * Reaching the end used to leave the last sentence sitting there with the
 * audio stopped, which reads as a stall rather than as finishing something.
 * The debrief is deliberately not a score: the honest measure of a listening
 * session is which words you had to look up, because those are the ones the
 * episode actually taught you.
 */
@Composable
fun FinishedOverlay(
    summary: EpisodeSummary?,
    onReplay: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center,
    ) {
        Confetti()

        Column(
            Modifier.padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🎉", fontSize = 44.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Episode finished",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))

            when {
                summary == null -> CircularProgressIndicator(
                    Modifier.size(20.dp), strokeWidth = 2.dp,
                )

                summary.uniqueWords == 0 -> Text(
                    "You got through it without reaching for a single definition.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                else -> {
                    Text(
                        "${summary.uniqueWords} word${if (summary.uniqueWords == 1) "" else "s"} " +
                            "looked up" +
                            if (summary.lookups > summary.uniqueWords) {
                                " · ${summary.lookups} taps"
                            } else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(14.dp))
                    // A single scrolling row rather than a wrapped block: this
                    // is a glance at what you missed, not a study list — that
                    // is what the Words tab is for.
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        summary.words.take(12).forEach { word ->
                            AssistChip(onClick = {}, label = { Text(word) })
                        }
                    }
                    if (summary.words.size > 12) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "and ${summary.words.size - 12} more, in Words",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onReplay, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.Default.Replay, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Listen again")
                }
                Button(onClick = onDone, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Done")
                }
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDismiss) { Text("Stay here") }
        }
    }
}

private data class Piece(
    val x: Float,
    val delay: Float,
    val speed: Float,
    val drift: Float,
    val size: Float,
    val color: Color,
)

/**
 * One-shot confetti, drawn on a Canvas. No library: a couple of dozen
 * rectangles falling on sine paths is the whole effect, and pulling in a
 * dependency for it would outweigh it several times over.
 */
@Composable
private fun Confetti() {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primaryContainer,
    )
    // Seeded so a recomposition does not reshuffle mid-fall.
    val pieces = remember {
        val rng = Random(7)
        List(28) {
            Piece(
                x = rng.nextFloat(),
                delay = rng.nextFloat() * 0.35f,
                speed = 0.75f + rng.nextFloat() * 0.5f,
                drift = (rng.nextFloat() - 0.5f) * 90f,
                size = 7f + rng.nextFloat() * 9f,
                color = palette[rng.nextInt(palette.size)],
            )
        }
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(2600, easing = LinearEasing))
    }

    Canvas(Modifier.fillMaxSize()) {
        pieces.forEach { p ->
            val t = ((progress.value - p.delay) * p.speed).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach
            val y = t * size.height * 1.15f - p.size
            val x = p.x * size.width + sin(t * 7f) * p.drift
            drawRect(
                color = p.color.copy(alpha = (1f - t).coerceIn(0f, 1f)),
                topLeft = Offset(x, y),
                size = Size(p.size, p.size * 0.55f),
            )
        }
    }
}
