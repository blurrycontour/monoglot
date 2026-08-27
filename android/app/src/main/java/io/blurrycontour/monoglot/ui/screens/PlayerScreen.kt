package io.blurrycontour.monoglot.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.blurrycontour.monoglot.data.Segment
import io.blurrycontour.monoglot.data.Token
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.data.TranscriptAnchor
import io.blurrycontour.monoglot.data.TranscriptMode
import io.blurrycontour.monoglot.player.PlayerViewModel
import io.blurrycontour.monoglot.ui.theme.TranscriptStyle
import io.blurrycontour.monoglot.ui.util.Dates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(itemId: Int, onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(itemId) { vm.load(itemId) }

    // Write the current position back before the library reappears.
    DisposableEffect(Unit) { onDispose { vm.flushProgress() } }

    Scaffold(
        // contentColorFor(Transparent) is Unspecified, which leaves
        // LocalContentColor at its black default. Every piece of unstyled text
        // on the screen would otherwise be black regardless of theme.
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    // Source names the screen, date identifies the episode
                    // beneath it. Never the episode headline: every Klartext
                    // episode carries the same one, so it says only which
                    // podcast this is — which the source already says, shorter.
                    val item = state.bundle?.item
                    val published = Dates.parse(item?.publishedAt)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    item == null -> "Loading…"
                                    item.sourceName.isNotBlank() -> item.sourceName
                                    else -> Dates.label(published)
                                },
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // The library marks finished episodes; opening one
                            // used to drop that entirely.
                            if (state.completed) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Finished",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        item?.let {
                            Text(
                                listOfNotNull(
                                    Dates.label(published).takeIf { d -> d != "—" },
                                    Dates.time(published).ifBlank { null },
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp,
                        )
                    }
                    EpisodeActionsMenu(
                        downloaded = state.isDownloaded,
                        hasProgress = state.positionMs > 0 || state.completed,
                        onToggleDownload = { vm.toggleDownload() },
                        onClearProgress = { vm.clearProgress() },
                        onArchive = { vm.archive(onBack) },
                    )
                },
            )
        },
    ) { padding ->
        // Only the top inset here: the controls apply the navigation bar inset
        // themselves, and applying it in both places left a band of empty
        // surface under the transport row.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    ServerErrorState(state.error!!, onRetry = { vm.reload(itemId) })
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TranscriptArea(vm, state)
                        // Covers the transcript only: the transport stays
                        // where it was, so scrubbing back into the episode
                        // does not mean dismissing anything first.
                        if (state.finishedVisible) {
                            FinishedOverlay(
                                summary = state.finished,
                                onReplay = { vm.replayEpisode() },
                                onDone = { vm.closeFinished(); onBack() },
                                onDismiss = { vm.dismissFinished() },
                            )
                        }
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
private fun TranscriptArea(vm: PlayerViewModel, state: io.blurrycontour.monoglot.player.PlayerState) {
    val idx = vm.tokenIndex() ?: return
    when (state.transcriptMode) {
        TranscriptMode.HIDDEN -> HiddenView(state.isPlaying)
        TranscriptMode.LINE -> LineView(vm, state)
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
            "Listening",
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

/**
 * The sentence playing now, always on screen, and nothing else. Unlike REVEAL
 * this needs no interaction, and unlike FULL it will not let the eye run ahead
 * of the audio.
 */
@Composable
private fun LineView(vm: PlayerViewModel, state: io.blurrycontour.monoglot.player.PlayerState) {
    val idx = vm.tokenIndex() ?: return
    val tokens = idx.tokensInSegment(state.activeSegmentIdx)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tokens.isEmpty()) {
            Text(
                "…",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SentenceText(
                tokens = tokens,
                activeTokenId = idx.tokens.getOrNull(state.activeTokenIdx)?.id,
                onWordTap = { vm.onWordTapped(it) },
            )
        }
    }
}

/** Blank until asked; then just the sentence playing now, which re-hides
 *  when playback moves on. */
@Composable
private fun RevealView(vm: PlayerViewModel, state: io.blurrycontour.monoglot.player.PlayerState) {
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullView(vm: PlayerViewModel, state: io.blurrycontour.monoglot.player.PlayerState) {
    val idx = vm.tokenIndex() ?: return
    val listState = rememberLazyListState()
    val activeSeg = state.activeSegmentIdx
    val haptics = LocalHapticFeedback.current

    // Auto-scroll yields to the reader.
    //
    // It used to follow the audio unconditionally, so scrolling back to
    // re-read a sentence was undone within seconds — by the one feature that
    // was supposed to help. A deliberate scroll turns following off; the pill
    // below turns it back on.
    var following by remember { mutableStateOf(true) }
    // Drag interactions specifically, not isScrollInProgress: that is equally
    // true of the auto-scroll below, which would then switch itself off the
    // first time it ran.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) following = false
        }
    }
    // Where the spoken line sits. Collected rather than read once, so changing
    // it in Settings takes effect on the episode already open.
    val anchor by Graph.repository.settings.transcriptAnchorFlow
        .collectAsState(initial = TranscriptAnchor.MIDDLE)

    LaunchedEffect(activeSeg, following, anchor) {
        if (following && activeSeg >= 0) {
            val target = activeSeg.coerceAtMost(idx.segments.lastIndex.coerceAtLeast(0))
            listState.animateScrollToItem(target, anchorOffset(listState, target, anchor))
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                        // Long-press to hear this sentence. The core loop is
                        // listen, fail, read, re-listen, and until now the last
                        // step only worked for the line already playing: a
                        // sentence you could see and had just decoded was not
                        // something you could ask to hear again.
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                following = true
                                vm.seekTo(seg.startMs)
                            },
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

        androidx.compose.animation.AnimatedVisibility(
            visible = !following,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            FilledTonalButton(onClick = { following = true }) {
                Icon(Icons.Default.VerticalAlignCenter, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Jump to current line")
            }
        }
    }
}

/**
 * Pixel offset that lands [index] at the anchor rather than at the top.
 *
 * animateScrollToItem measures from the top of the viewport and treats a
 * positive offset as scrolling further forward, so putting a line lower down
 * the screen means a negative one. The line's own height comes out of the sum,
 * or a tall sentence would be positioned by its first row and hang off the
 * bottom. When the line is not on screen its height is not yet known and zero
 * is close enough: it is about to be measured, and the next segment corrects
 * it. Scrolling is clamped at the ends of the list by LazyColumn itself, so
 * the first and last sentences simply sit where they can.
 */
private fun anchorOffset(state: LazyListState, index: Int, anchor: TranscriptAnchor): Int {
    if (anchor == TranscriptAnchor.TOP) return 0
    val info = state.layoutInfo
    // The viewport spans the list's own padding as well as its content, and
    // offset 0 already sits below the leading padding — so measuring against
    // the raw viewport pushed the line a full padding's worth too low, and at
    // Bottom that put it under the transport row.
    val usable = (info.viewportEndOffset - info.viewportStartOffset) -
        info.beforeContentPadding - info.afterContentPadding
    if (usable <= 0) return 0
    // A line being jumped to has not been measured yet, and treating it as
    // zero-height at Bottom aligned its top with the bottom edge — scrolling
    // it off the screen entirely. Neighbouring lines are the best estimate of
    // how tall it is.
    val visible = info.visibleItemsInfo
    val height = visible.firstOrNull { it.index == index }?.size
        ?: visible.map { it.size }.average().takeIf { !it.isNaN() }?.toInt()
        ?: 0
    val room = (usable - height).coerceAtLeast(0)
    return -(room * anchor.fraction).toInt()
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
            // Colour and a background chip, but never a weight change: bold
            // makes the glyphs measurably wider, which reflowed the whole line
            // as each word came up when the sentence sat near the wrap point.
            Text(
                text = token.surface,
                style = TranscriptStyle,
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
