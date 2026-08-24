package io.blurrycontour.monoglot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { onStatus(candidate.lemma, "known"); onDismiss() },
                                label = { Text("Known") },
                                leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                            )
                            AssistChip(
                                onClick = { onStatus(candidate.lemma, "learning"); onDismiss() },
                                label = { Text("Learning") },
                                leadingIcon = { Icon(Icons.Default.School, null, Modifier.size(16.dp)) },
                            )
                        }
                        HorizontalDivider(Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
    }
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
