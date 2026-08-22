package se.svenska.trainer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import se.svenska.trainer.data.Segment
import se.svenska.trainer.data.Token
import se.svenska.trainer.data.TranscriptMode
import se.svenska.trainer.player.PlayerViewModel
import se.svenska.trainer.ui.theme.TranscriptStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(itemId: Int, onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(itemId) { vm.load(itemId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.bundle?.item?.title ?: "Loading…",
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.bundle?.item?.sourceName?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TranscriptModeButton(state.transcriptMode) { vm.cycleTranscriptMode() }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TranscriptArea(vm, state)
                    }
                    Controls(vm, state)
                }
            }

            state.popup?.let { popup ->
                WordSheet(
                    popup = popup,
                    onDismiss = { vm.dismissPopup() },
                    onStatus = { lemma, status -> vm.setWordStatus(lemma, status) },
                )
            }
        }
    }
}

@Composable
private fun TranscriptModeButton(mode: TranscriptMode, onClick: () -> Unit) {
    val (icon, label) = when (mode) {
        TranscriptMode.HIDDEN -> Icons.Default.VisibilityOff to "Hidden"
        TranscriptMode.REVEAL -> Icons.Default.Visibility to "Reveal"
        TranscriptMode.FULL -> Icons.Default.Article to "Full"
    }
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun TranscriptArea(vm: PlayerViewModel, state: se.svenska.trainer.player.PlayerState) {
    val idx = vm.tokenIndex() ?: return
    when (state.transcriptMode) {
        TranscriptMode.HIDDEN -> HiddenView(state.isPlaying)
        TranscriptMode.REVEAL -> RevealView(vm, state)
        TranscriptMode.FULL -> FullView(vm, state)
    }
}

/**
 * Default mode: no text at all. This is the whole point of the app - you
 * listen, and only reach for text when you have already failed to parse
 * something.
 */
@Composable
private fun HiddenView(isPlaying: Boolean) {
    val alpha by animateFloatAsState(if (isPlaying) 1f else 0.45f, label = "pulse")
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Lyssna",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Text is hidden. Reveal a sentence only when you could not parse it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Blank until asked; then just the sentence playing now, which re-hides
 *  when playback moves on. */
@Composable
private fun RevealView(vm: PlayerViewModel, state: se.svenska.trainer.player.PlayerState) {
    val idx = vm.tokenIndex() ?: return
    val revealed = state.revealedSegmentIdx >= 0

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(revealed, enter = fadeIn(), exit = fadeOut()) {
            val tokens = idx.tokensInSegment(state.revealedSegmentIdx)
            SentenceText(
                tokens = tokens,
                activeTokenId = idx.tokens.getOrNull(state.activeTokenIdx)?.id,
                onWordTap = { vm.onWordTapped(it) },
            )
        }
        if (!revealed) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { vm.revealCurrentSentence() },
                modifier = Modifier.height(56.dp),
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Show this sentence", fontSize = 16.sp)
            }
        }
    }
}

/** Whole transcript, current word highlighted. */
@Composable
private fun FullView(vm: PlayerViewModel, state: se.svenska.trainer.player.PlayerState) {
    val idx = vm.tokenIndex() ?: return
    val listState = rememberLazyListState()
    val activeSeg = state.activeSegmentIdx

    // Keep the sentence being spoken on screen without fighting the user:
    // only auto-scroll when the active sentence changes.
    LaunchedEffect(activeSeg) {
        if (activeSeg >= 0) {
            listState.animateScrollToItem(activeSeg.coerceAtMost(idx.segments.lastIndex.coerceAtLeast(0)))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(idx.segments) { i, seg ->
            val isActive = i == activeSeg
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) MaterialTheme.colorScheme.surfaceVariant
                        else Color.Transparent
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                SentenceText(
                    tokens = idx.tokensInSegment(i),
                    activeTokenId = idx.tokens.getOrNull(state.activeTokenIdx)?.id,
                    onWordTap = { vm.onWordTapped(it) },
                    dimmed = !isActive,
                )
            }
        }
    }
}

/** LazyColumn.itemsIndexed for a plain List, kept local to avoid an import
 *  clash with the foundation overload. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    segments: List<Segment>,
    content: @Composable (Int, Segment) -> Unit,
) = items(segments.size) { i -> content(i, segments[i]) }

/**
 * One sentence, laid out as a flow of tappable words with the spoken word
 * highlighted. FlowRow rather than an AnnotatedString: each word needs its own
 * touch target, and a popup must not shift the layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SentenceText(
    tokens: List<Token>,
    activeTokenId: Int?,
    onWordTap: (Token) -> Unit,
    dimmed: Boolean = false,
) {
    val base = if (dimmed) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tokens.forEach { token ->
            val isActive = token.id == activeTokenId
            Text(
                text = token.surface,
                style = TranscriptStyle,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.primary else base,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .clickable(enabled = token.isWord) { onWordTap(token) }
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}
