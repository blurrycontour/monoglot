package io.blurrycontour.monoglot.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.blurrycontour.monoglot.player.WordPopup

/**
 * Tap-to-define. A bottom sheet rather than an anchored popover: it never
 * shifts the transcript, is reachable one-handed, and dismissing it is a
 * downward flick. Playback keeps running throughout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordSheet(
    popup: WordPopup,
    onDismiss: () -> Unit,
    onStatus: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onPlayFromHere: () -> Unit,
    onHearWord: () -> Unit,
    onSpeak: () -> Unit,
    hearing: Boolean,
    speaking: Boolean,
) {
    // Deliberately not skipPartiallyExpanded: at full height the sheet covered
    // the very sentence the word was tapped in, so the definition arrived with
    // its context hidden. Half height leaves the line on screen.
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                popup.token.surface.trim(),
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(12.dp))
            // The word plays itself the moment the sheet opens; the two icons
            // repeat it — from the episode audio, then a clean reference with
            // the system Swedish voice — and the button hands the episode back
            // starting from this word. Icons keep the whole row on one line.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = onHearWord) {
                    Icon(
                        Icons.Default.VolumeUp, "Hear word from the episode",
                        Modifier.size(20.dp).scale(pulse(hearing)),
                    )
                }
                FilledTonalIconButton(onClick = onSpeak) {
                    Icon(
                        Icons.Default.RecordVoiceOver, "Speak the word",
                        Modifier.size(20.dp).scale(pulse(speaking)),
                    )
                }
                FilledTonalButton(onClick = onPlayFromHere) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play from here")
                }
            }

            when {
                popup.loading -> {
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Looking up…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                popup.candidates.isEmpty() -> {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No definition found. This is often a compound — try reading it as two words.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    // Several candidates are stacked rather than guessed
                    // between: "får" really is both "sheep" and "may/gets",
                    // and context disambiguation is out of scope for v1.
                    popup.candidates.forEach { candidate ->
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                candidate.lemma,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (candidate.pos.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    posLabel(candidate.pos),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                )
                            }
                        }

                        candidate.definitions.take(6).forEach { def ->
                            Spacer(Modifier.height(6.dp))
                            Text("• ${def.translation}", fontSize = 16.sp)
                            if (def.comment.isNotBlank()) {
                                Text(
                                    def.comment,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 14.dp),
                                )
                            }
                            if (def.example.isNotBlank()) {
                                Text(
                                    def.example,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 14.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        // Filter chips, not assist chips: this is a state the
                        // word is already in, and offering both as identical
                        // buttons gave no way to tell whether a word had been
                        // filed already. Tapping no longer dismisses either —
                        // the sheet stays put so the change can be seen, and
                        // corrected if it was the wrong chip.
                        val current = popup.statuses[candidate.lemma]
                        val haptics = LocalHapticFeedback.current
                        // Removing files nothing on screen but the chips, which
                        // is too quiet to read as "done": a local flag flips the
                        // button to a disabled "Removed" the instant it is tapped,
                        // reset the moment the word is filed again.
                        var justRemoved by remember(candidate.lemma) { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = current == "known",
                                onClick = { justRemoved = false; onStatus(candidate.lemma, "known") },
                                label = { Text("Known") },
                                leadingIcon = {
                                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                },
                            )
                            FilterChip(
                                selected = current == "learning",
                                onClick = { justRemoved = false; onStatus(candidate.lemma, "learning") },
                                label = { Text("Learning") },
                                leadingIcon = {
                                    Icon(Icons.Default.School, null, Modifier.size(16.dp))
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            // Every tap files the word automatically, so a
                            // misclicked word needs a way back out. This drops
                            // the whole vocabulary row; tapping the word again
                            // would re-add it.
                            OutlinedButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRemove(candidate.lemma)
                                    justRemoved = true
                                },
                                enabled = !justRemoved,
                            ) {
                                Icon(
                                    if (justRemoved) Icons.Default.Check
                                    else Icons.Default.DeleteOutline,
                                    null,
                                    Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (justRemoved) "Removed" else "Remove")
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
    }
}

/** A gently pulsing scale while a sound is playing, so the icon that started it
 *  reads as active rather than a dead button. 1.0 when idle. */
@Composable
private fun pulse(active: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "scale",
    )
    return if (active) scale else 1f
}

/** Folkets uses compact Swedish word-class tags. */
private fun posLabel(pos: String): String = when (pos) {
    "nn" -> "noun"
    "vb" -> "verb"
    "jj" -> "adjective"
    "ab" -> "adverb"
    "pp" -> "preposition"
    "pn" -> "pronoun"
    "kn" -> "conjunction"
    "in" -> "interjection"
    "rg" -> "numeral"
    "article" -> "article"
    else -> pos
}
