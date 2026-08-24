package se.svenska.trainer.ui.screens

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.svenska.trainer.data.Graph
import se.svenska.trainer.data.ItemSummary
import se.svenska.trainer.data.PipelineStatus
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import se.svenska.trainer.data.SourceStats
import se.svenska.trainer.player.PlaybackHolder
import se.svenska.trainer.ui.util.Dates
import se.svenska.trainer.ui.util.RefreshWhenVisible
import se.svenska.trainer.ui.util.formatDuration
import se.svenska.trainer.ui.util.remainingLabel

/**
 * Library filters. The previous single "hide finished" toggle looked broken
 * because nothing was finished yet, so it visibly did nothing.
 */
enum class LibraryFilter(val label: String) {
    ALL("All"),
    UNPLAYED("Not started"),
    IN_PROGRESS("In progress"),
    FINISHED("Finished"),
    DOWNLOADED("Downloaded"),
}

data class LibraryState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val items: List<ItemSummary> = emptyList(),
    val downloadedIds: Set<Int> = emptySet(),
    val busyIds: Set<Int> = emptySet(),
    val sourceFilter: String? = null,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val status: PipelineStatus? = null,
    val sources: List<SourceStats> = emptyList(),
    /** How many items were in flight when this batch started, so progress can
     *  be shown as a fraction rather than a spinner that never moves. */
    val batchTotal: Int = 0,
    /** Items known to the server but never fetched, revealed by "Show more".
     *  Held per source filter, since the list is source-specific. */
    val archived: List<ItemSummary> = emptyList(),
    val loadingMore: Boolean = false,
    val moreExhausted: Boolean = false,
    val fetchingIds: Set<Int> = emptySet(),
    /** Whether the current source has any never-fetched items at all. */
    val hasArchive: Boolean = false,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repository
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        refresh(initial = true)
        // Everything held here belongs to one server; start over when it
        // changes rather than showing the old instance's data.
        viewModelScope.launch {
            repo.settings.serverEpochFlow.drop(1).collect {
                _state.value = LibraryState()
                refresh(initial = true)
            }
        }
    }

    fun refresh(initial: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = initial && _state.value.items.isEmpty(),
                refreshing = !initial,
                error = null,
            )
            repo.syncProgress()
            val result = repo.items(_state.value.sourceFilter)
            val downloaded = repo.offline.downloads.all().map { it.itemId }.toSet()
            _state.value = result.fold(
                onSuccess = {
                    _state.value.copy(loading = false, refreshing = false,
                        items = it, downloadedIds = downloaded, error = null)
                },
                onFailure = {
                    _state.value.copy(loading = false, refreshing = false,
                        error = it.message ?: "Cannot reach server")
                },
            )
            refreshMeta()
        }
    }

    /** Refreshes without the spinner, for lifecycle-driven updates. */
    fun refreshQuietly() {
        viewModelScope.launch {
            repo.syncProgress()
            repo.items(_state.value.sourceFilter).onSuccess { items ->
                val downloaded = repo.offline.downloads.all().map { it.itemId }.toSet()
                _state.value = _state.value.copy(items = items, downloadedIds = downloaded)
            }
        }
    }

    private suspend fun refreshMeta() {
        runCatching { repo.api.system() }.onSuccess {
            _state.value = _state.value.copy(sources = it.sources)
        }
        val status = runCatching { repo.api.status() }.getOrNull() ?: return
        _state.value = _state.value.copy(
            status = status,
            batchTotal = batchTotalFor(status),
            hasArchive = _state.value.hasArchive || probeArchived(_state.value.sourceFilter),
        )
        if (status.processing > 0 || status.ingestRunning) startPolling() else stopPolling()
    }

    /** Grows to the high-water mark of a batch and resets once it drains. */
    private fun batchTotalFor(status: PipelineStatus): Int =
        if (status.processing == 0) 0
        else maxOf(_state.value.batchTotal, status.processing)

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(6_000)
                val status = runCatching { repo.api.status() }.getOrNull() ?: continue
                val before = _state.value.status?.ready ?: 0
                _state.value = _state.value.copy(
                    status = status, batchTotal = batchTotalFor(status),
                )
                if (status.ready != before) {
                    repo.items(_state.value.sourceFilter).onSuccess {
                        _state.value = _state.value.copy(items = it)
                    }
                }
                if (status.processing == 0 && !status.ingestRunning) break
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel(); pollJob = null
    }

    fun setSourceFilter(slug: String?) {
        // Deliberately not refresh(): that sets `refreshing`, which is what
        // PullToRefreshBox renders its indicator from, so tapping a chip
        // looked like a half-completed pull gesture.
        //
        // The revealed archive is reset too: it belongs to the previous
        // source, and leaving it in place showed one source's back catalogue
        // under another's heading.
        _state.value = _state.value.copy(
            sourceFilter = slug, loading = true,
            archived = emptyList(), moreExhausted = false,
        )
        viewModelScope.launch {
            _state.value = _state.value.copy(hasArchive = probeArchived(slug))
            repo.items(slug).onSuccess { items ->
                val downloaded = repo.offline.downloads.all().map { it.itemId }.toSet()
                _state.value = _state.value.copy(
                    items = items, downloadedIds = downloaded, loading = false,
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun setFilter(filter: LibraryFilter) {
        _state.value = _state.value.copy(filter = filter)
        viewModelScope.launch { repo.settings.setLibraryFilter(filter.name) }
    }

    fun toggleDownload(item: ItemSummary) {
        viewModelScope.launch {
            val s = _state.value
            _state.value = s.copy(busyIds = s.busyIds + item.id)
            runCatching {
                if (item.id in s.downloadedIds) repo.removeDownload(item.id)
                else repo.download(item.id)
            }
            val downloaded = repo.offline.downloads.all().map { it.itemId }.toSet()
            _state.value = _state.value.copy(
                downloadedIds = downloaded,
                busyIds = _state.value.busyIds - item.id,
            )
        }
    }

    /** Frees the server's copy. The item stays in the library and can be
     *  fetched again; nothing is permanently lost. */
    fun archive(item: ItemSummary) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyIds = _state.value.busyIds + item.id)
            runCatching { repo.api.archiveItem(item.id) }
            runCatching { repo.removeDownload(item.id) }
            refresh()
            _state.value = _state.value.copy(busyIds = _state.value.busyIds - item.id)
        }
    }

    /** Reveals another page of never-fetched items. They are listed but not
     *  downloaded: fetching one is an explicit choice, because it costs a
     *  download and a minute of transcription. */
    fun showMore() {
        if (_state.value.loadingMore) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            val offset = _state.value.archived.size
            val page = runCatching {
                repo.api.archivedItems(_state.value.sourceFilter, offset = offset, limit = 10)
            }.getOrDefault(emptyList())

            _state.value = _state.value.copy(
                archived = _state.value.archived + page,
                loadingMore = false,
                // A short page means the source has nothing further.
                moreExhausted = page.size < 10,
            )
        }
    }

    /** Whether this source has anything left to reveal. Checked once per
     *  source so the button is not offered where it would do nothing. */
    private suspend fun probeArchived(slug: String?): Boolean =
        runCatching { repo.api.archivedItems(slug, offset = 0, limit = 1).isNotEmpty() }
            .getOrDefault(false)

    /** Queues a never-fetched item for download and transcription. */
    fun fetch(item: ItemSummary) {
        viewModelScope.launch {
            _state.value = _state.value.copy(fetchingIds = _state.value.fetchingIds + item.id)
            runCatching { repo.api.restoreItem(item.id) }
            refreshMetaPublic()
        }
    }

    suspend fun refreshMetaPublic() = refreshMeta()

    fun clearProgress(item: ItemSummary) {
        viewModelScope.launch {
            runCatching { repo.api.resetProgress(item.id) }
            runCatching { repo.clearLocalProgress(item.id) }
            refresh()
        }
    }

    override fun onCleared() { stopPolling(); super.onCleared() }

    /** Visible list after client-side filters. */
    fun visible(s: LibraryState): List<ItemSummary> = when (s.filter) {
        LibraryFilter.ALL -> s.items
        LibraryFilter.UNPLAYED -> s.items.filter { it.positionMs == 0 && !it.completed }
        LibraryFilter.IN_PROGRESS -> s.items.filter { it.positionMs > 0 && !it.completed }
        LibraryFilter.FINISHED -> s.items.filter { it.completed }
        LibraryFilter.DOWNLOADED -> s.items.filter { it.id in s.downloadedIds }
    }

    /** How many items each filter would show, for the filter sheet. */
    fun counts(s: LibraryState): Map<LibraryFilter, Int> =
        LibraryFilter.entries.associateWith { f -> visible(s.copy(filter = f)).size }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onOpen: (Int) -> Unit, visible: Boolean = true) {
    val vm: LibraryViewModel = viewModel()
    val state by vm.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val pullState = rememberPullToRefreshState()

    // Returning from the player must show the progress just made, without
    // requiring a manual pull to refresh.
    RefreshWhenVisible(visible) { vm.refreshQuietly() }
    val nowPlaying by PlaybackHolder.now.collectAsState()
    LaunchedEffect(nowPlaying.itemId) { vm.refreshQuietly() }

    var filterSheet by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<ItemSummary?>(null) }

    Scaffold(
        topBar = {
            MonoglotTopBar(title = "Listen") {
                BadgedBox(
                    badge = {
                        if (state.filter != LibraryFilter.ALL) Badge(Modifier.size(7.dp))
                    }
                ) {
                    IconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        filterSheet = true
                    }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter episodes")
                    }
                }
            }
        },
        // contentColorFor(Transparent) is Unspecified, which leaves
        // LocalContentColor at its black default. Every piece of unstyled text
        // on the screen would otherwise be black regardless of theme.
        containerColor = Color.Transparent,
        // The tab pager already sits above the bottom bar, so the Scaffold must
        // not reserve the navigation-bar inset a second time: that left a dead
        // strip that clipped the last row of content short of the bar.
        contentWindowInsets = WindowInsets(0),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Outside the pull-to-refresh region on purpose: a horizontal swipe
            // across the chips used to arm the refresh gesture as well.
            SourceFilterRow(state) { vm.setSourceFilter(it) }

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { vm.refresh() },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
            val visible = vm.visible(state)
            LaunchedEffect(state.filter) { /* re-compose on filter change */ }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                state.status?.let { status ->
                    if (status.processing > 0 || status.failed > 0) {
                        item { ProcessingBanner(status, state.batchTotal) }
                    }
                }

                when {
                    state.loading -> items(5) { SkeletonCard() }

                    state.error != null && visible.isEmpty() -> item {
                        ErrorState(state.error!!) { vm.refresh() }
                    }

                    visible.isEmpty() -> item { EmptyState(state.filter) }

                    else -> {
                        Dates.groupItems(visible).forEach { (header, groupItems) ->
                            stickyHeader(key = "h-$header") { SectionHeader(header, groupItems.size) }
                            items(groupItems, key = { it.id }) { item ->
                                EpisodeCard(
                                    item = item,
                                    downloaded = item.id in state.downloadedIds,
                                    busy = item.id in state.busyIds,
                                    nowPlaying = item.id == nowPlaying.itemId,
                                    onOpen = { onOpen(item.id) },
                                    onToggleDownload = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.toggleDownload(item)
                                    },
                                    onArchive = { vm.archive(item) },
                                    onClearProgress = { confirmClear = item },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }

                        if (state.archived.isNotEmpty()) {
                            stickyHeader(key = "h-notfetched") {
                                SectionHeader("Not fetched", state.archived.size)
                            }
                            items(state.archived, key = { "a-${it.id}" }) { item ->
                                ArchivedCard(
                                    item = item,
                                    busy = item.id in state.fetchingIds,
                                    onFetch = { vm.fetch(item) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }

                        if (state.hasArchive && !state.moreExhausted) {
                            item(key = "showmore") {
                                ShowMoreButton(
                                    loading = state.loadingMore,
                                    onClick = { vm.showMore() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    }

    if (filterSheet) {
        FilterSheet(
            current = state.filter,
            counts = vm.counts(state),
            onSelect = { vm.setFilter(it); filterSheet = false },
            onDismiss = { filterSheet = false },
        )
    }

    confirmClear?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text("Clear progress?") },
            text = {
                Text(
                    "\"${Dates.label(Dates.parse(target.publishedAt))}\" will read as unheard " +
                        "and start from the beginning next time.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.clearProgress(target); confirmClear = null }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    current: LibraryFilter,
    counts: Map<LibraryFilter, Int>,
    onSelect: (LibraryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 28.dp)) {
            Text(
                "Show",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            LibraryFilter.entries.forEach { filter ->
                val count = counts[filter] ?: 0
                ListItem(
                    headlineContent = { Text(filter.label) },
                    trailingContent = {
                        Text(
                            "$count",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        RadioButton(selected = current == filter, onClick = { onSelect(filter) })
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSelect(filter) },
                )
            }
        }
    }
}

/** The launcher mark, reused in-app so the identity is visible inside the
 *  product and not only on the home screen. */
@Composable
fun AppMark(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    androidx.compose.foundation.Canvas(modifier) {
        val barCount = 5
        val heights = listOf(0.34f, 0.62f, 1f, 0.62f, 0.34f)
        val slot = size.width / barCount
        val barWidth = slot * 0.46f
        heights.forEachIndexed { i, h ->
            val barHeight = size.height * h
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = i * slot + (slot - barWidth) / 2f,
                    y = (size.height - barHeight) / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun SourceFilterRow(state: LibraryState, onSelect: (String?) -> Unit) {
    // Scrolls: with four sources the row overflowed and the last chip wrapped
    // its label one character per line.
    val all = state.sources.sumOf { it.ready }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.sourceFilter == null,
                onClick = { onSelect(null) },
                label = { Text("All  $all") },
            )
        }
        items(state.sources.size) { i ->
            val source = state.sources[i]
            FilterChip(
                selected = state.sourceFilter == source.slug,
                onClick = { onSelect(source.slug) },
                label = { Text("${source.name}  ${source.ready}", maxLines = 1) },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    // Centred, with a rule running out to each side: these are dividers in a
    // single column of cards, and left-aligning them read as another list item.
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HorizontalDivider(Modifier.weight(1f))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.8.sp,
            )
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.weight(1f))
        }
    }
}

/**
 * One episode. The date is the loudest element because Klartext titles are all
 * identical: the only way to tell episodes apart at a glance is when they aired.
 */
@Composable
private fun EpisodeCard(
    item: ItemSummary,
    downloaded: Boolean,
    busy: Boolean,
    nowPlaying: Boolean,
    onOpen: () -> Unit,
    onToggleDownload: () -> Unit,
    onArchive: () -> Unit,
    onClearProgress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (item.durationMs > 0) {
        (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(progress, tween(400), label = "progress")

    Card(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.completed)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface,
        ),
        // The episode the mini player is holding. A border rather than a fill:
        // it has to be findable in a scroll without shouting over the dates,
        // which are the field you actually read this list by.
        border = if (nowPlaying) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else null,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    // Date first and largest. This is the identifying field.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Dates.label(Dates.parse(item.publishedAt)),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (item.completed)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (item.completed) {
                            Spacer(Modifier.width(7.dp))
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Finished",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceDot(item.sourceSlug)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            item.sourceName.ifBlank { item.sourceSlug },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "  ·  ${formatDuration(item.durationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Downloaded state stays visible as a small marker; the action
                // itself lives in the menu to keep the card uncluttered.
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else if (downloaded) {
                    Icon(
                        Icons.Default.OfflinePin,
                        contentDescription = "Saved for offline",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }

                EpisodeActionsMenu(
                    downloaded = downloaded,
                    hasProgress = item.positionMs > 0 || item.completed,
                    onToggleDownload = onToggleDownload,
                    onClearProgress = onClearProgress,
                    onArchive = onArchive,
                )
            }

            // Headline is secondary: for Klartext it is the same every day, and
            // for 8 Sidor it repeats the date. Kept for the description it adds.
            // 8 Sidor podcast descriptions are often just the date as digits
            // ("260821"), which is noise next to the date already shown above.
            val description = item.description.trim()
            val meaningful = description.length > 8 && description.any { it.isLetter() }
            if (meaningful) {
                Spacer(Modifier.height(7.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.positionMs > 0 && !item.completed) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PlayCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        remainingLabel(item.positionMs, item.durationMs) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Sources are colour-coded so the eye can separate them without reading. */
@Composable
private fun SourceDot(slug: String) {
    val color = when (slug) {
        "klartext" -> MaterialTheme.colorScheme.primary
        "8sidor" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}

/**
 * Offline download. Previously an unlabelled arrow, which gave no clue what it
 * did; now it states its purpose and confirms the result.
 */
@Composable
private fun DownloadButton(downloaded: Boolean, busy: Boolean, onClick: () -> Unit) {
    val label = when {
        busy -> "Saving"
        downloaded -> "Offline"
        else -> "Save"
    }
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(20.dp),
        color = if (downloaded) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
        border = if (downloaded) null
                 else androidx.compose.foundation.BorderStroke(
                     1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.height(32.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (downloaded) Icons.Default.OfflinePin else Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (downloaded) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (downloaded) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * An episode the server knows about but has never downloaded. Shown greyed out
 * with an explicit Fetch action: pulling one costs a download and roughly a
 * minute of transcription, so it should never happen by accident.
 */
@Composable
private fun ArchivedCard(
    item: ItemSummary,
    busy: Boolean,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    Dates.label(Dates.parse(item.publishedAt)),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.title.take(60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                FilledTonalButton(onClick = onFetch, modifier = Modifier.height(36.dp)) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fetch", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ShowMoreButton(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = onClick, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Loading…")
            } else {
                Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show more")
            }
        }
    }
}

@Composable
private fun ProcessingBanner(status: PipelineStatus, batchTotal: Int) {
    val done = (batchTotal - status.processing).coerceAtLeast(0)
    val fraction = if (batchTotal > 0) done.toFloat() / batchTotal else 0f
    val animated by animateFloatAsState(fraction, tween(500), label = "batch")

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            if (status.processing > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Preparing episodes",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (batchTotal > 0) "$done / $batchTotal" else "${status.processing}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(7.dp))
                // Naming the stage makes a slow queue legible: transcription
                // takes about a minute per five-minute episode on CPU.
                Text(
                    buildList {
                        if (status.transcribing > 0) add("transcribing ${status.transcribing}")
                        if (status.queued > 0) add("${status.queued} queued")
                        if (status.downloading > 0) add("${status.downloading} downloading")
                    }.joinToString(" · ").ifEmpty { "working" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${status.failed} episode${if (status.failed == 1) "" else "s"} failed to process",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/** Skeletons rather than a spinner: the list keeps its shape while loading,
 *  so nothing jumps when content arrives. */
@Composable
private fun SkeletonCard() {
    val alpha by rememberInfiniteShimmer()
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            ShimmerBar(Modifier.fillMaxWidth(0.35f).height(18.dp), alpha)
            Spacer(Modifier.height(9.dp))
            ShimmerBar(Modifier.fillMaxWidth(0.55f).height(12.dp), alpha)
            Spacer(Modifier.height(11.dp))
            ShimmerBar(Modifier.fillMaxWidth(0.9f).height(10.dp), alpha)
        }
    }
}

@Composable
private fun ShimmerBar(modifier: Modifier, alpha: Float) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    )
}

@Composable
private fun rememberInfiniteShimmer(): State<Float> {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.13f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Default.CloudOff, null, Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text("Cannot reach the server", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyState(filter: LibraryFilter) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(50.dp))
        Icon(Icons.Default.Headphones, null, Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text(
            if (filter == LibraryFilter.ALL) "No episodes yet" else "Nothing matches this filter",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (filter == LibraryFilter.ALL) "Pull down to refresh, or fetch new episodes from the System tab."
            else "Try a different filter, or show everything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
