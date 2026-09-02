package io.blurrycontour.monoglot.ui.screens

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.data.ItemSummary
import io.blurrycontour.monoglot.data.PipelineItem
import io.blurrycontour.monoglot.data.PipelineStatus
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.blurrycontour.monoglot.data.SourceRow
import io.blurrycontour.monoglot.ui.theme.sourceColor
import io.blurrycontour.monoglot.player.PlaybackHolder
import io.blurrycontour.monoglot.ui.util.Dates
import io.blurrycontour.monoglot.ui.util.formatBytesShort
import io.blurrycontour.monoglot.ui.util.RefreshWhenVisible
import io.blurrycontour.monoglot.ui.util.rememberIsForeground
import io.blurrycontour.monoglot.ui.util.formatDuration
import io.blurrycontour.monoglot.ui.util.remainingLabel

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
    val sources: List<SourceRow> = emptyList(),
    /** How many items were in flight when this batch started, so progress can
     *  be shown as a fraction rather than a spinner that never moves. */
    val batchTotal: Int = 0,
    /** Items known to the server but never fetched, revealed by "Show more".
     *  Held per source filter, since the list is source-specific. */
    val archived: List<ItemSummary> = emptyList(),
    val loadingMore: Boolean = false,
    val moreExhausted: Boolean = false,
    val fetchingIds: Set<Int> = emptySet(),
    val cancellingIds: Set<Int> = emptySet(),
    /** Whether the current source has any never-fetched items at all. */
    val hasArchive: Boolean = false,
    /** Episodes published after this instant are marked new. Captured once per
     *  launch, from when the Listen tab was last opened. */
    val newSince: Long = 0,
)

/** How long the pull-to-refresh indicator stays up at minimum. A LAN round
 *  trip is faster than the eye, and an indicator that never appears reads as a
 *  gesture that did not register. */
private const val MIN_REFRESH_MS = 550L

/** Poll interval while the queue is visibly moving, and the ceiling it backs
 *  off to when nothing has changed. */
private const val FAST_POLL_MS = 6_000L
private const val SLOW_POLL_MS = 60_000L

/** How tall the queue sheet may grow. Beyond this it scrolls. */
private val QUEUE_SHEET_MAX = 520.dp

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repository
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            // Read first, write second. Written as one expression, the state
            // snapshot is taken before the DataStore read suspends, so the
            // write puts back everything as it was when the coroutine started
            // — and the first DataStore read of a launch is slow enough that
            // the source chips had already landed and were wiped by it.
            val since = repo.settings.takeListenSeenAt()
            _state.value = _state.value.copy(newSince = since)
        }
        refresh(initial = true)
        // Everything held here belongs to one server; start over when it
        // changes rather than showing the old instance's data.
        viewModelScope.launch {
            repo.settings.serverEpochFlow.drop(1).collect {
                // The cutoff belongs to the reader, not the server.
                _state.value = LibraryState(newSince = _state.value.newSince)
                refresh(initial = true)
            }
        }
    }

    fun refresh(initial: Boolean = false) {
        viewModelScope.launch {
            // The indicator has to be on screen long enough to read as one.
            // On a LAN the fetch finishes in well under a frame, so the
            // gesture completed and nothing appeared to happen.
            val started = System.currentTimeMillis()
            _state.value = _state.value.copy(
                loading = initial && _state.value.items.isEmpty(),
                refreshing = !initial,
                error = null,
            )
            // Concurrent with the episode load, not behind it: the chips and
            // the queue banner describe the same list and should arrive with
            // it, and neither depends on what the item fetch returns.
            val meta = launch { refreshMeta() }
            repo.syncProgress()
            val result = repo.items(_state.value.sourceFilter)
            val downloaded = repo.offline.downloads.all().map { it.itemId }.toSet()
            _state.value = result.fold(
                onSuccess = {
                    _state.value.copy(loading = false,
                        items = it, downloadedIds = downloaded, error = null)
                },
                onFailure = {
                    _state.value.copy(loading = false,
                        error = it.message ?: "Cannot reach server")
                },
            )
            meta.join()
            if (!initial) {
                val elapsed = System.currentTimeMillis() - started
                if (elapsed < MIN_REFRESH_MS) delay(MIN_REFRESH_MS - elapsed)
                _state.value = _state.value.copy(refreshing = false)
            }
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

    /**
     * The source filter chips, and nothing else.
     *
     * These used to come out of /api/system, which samples container CPU: two
     * seconds, spent so the chips could show a per-source count. /api/sources
     * is the one query they actually need.
     */
    private suspend fun loadSources() {
        runCatching { repo.api.sources() }.onSuccess {
            _state.value = _state.value.copy(sources = it)
        }
    }

    private suspend fun refreshMeta() {
        loadSources()
        val status = runCatching { repo.api.status(_state.value.sourceFilter) }.getOrNull() ?: return
        // Both reads finish before the write. This one runs beside the episode
        // load now, and probing the archive inside the copy() would take its
        // state snapshot before the probe and put it back after — reverting
        // whatever the item fetch had landed in the meantime.
        val hasArchive = _state.value.hasArchive || probeArchived(_state.value.sourceFilter)
        _state.value = _state.value.copy(
            status = status,
            batchTotal = batchTotalFor(status),
            hasArchive = hasArchive,
        )
        if (status.processing > 0 || status.ingestRunning) startPolling() else stopPolling()
    }

    /** Grows to the high-water mark of a batch and resets once it drains. */
    private fun batchTotalFor(status: PipelineStatus): Int =
        if (status.processing == 0) 0
        else maxOf(_state.value.batchTotal, status.processing)

    /**
     * Polls while the server has work in flight — and only while this screen is
     * the one on the phone.
     *
     * It used to poll every six seconds for as long as the process lived,
     * regardless of what was on screen, and only stopped when the queue
     * emptied. With a stalled pipeline the queue never emptied, so the app sat
     * in the background making a request every six seconds indefinitely. That
     * was the second half of the battery problem.
     *
     * The interval backs off as well: a queue that has not moved in minutes is
     * not going to move in the next six seconds.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var interval = FAST_POLL_MS
            var signature = _state.value.status?.let(::pipelineSignature)
            while (visible) {
                delay(interval)
                if (!visible) break
                val status = runCatching { repo.api.status(_state.value.sourceFilter) }.getOrNull() ?: continue
                val before = _state.value.status?.ready ?: 0
                _state.value = _state.value.copy(
                    status = status, batchTotal = batchTotalFor(status),
                )
                if (status.ready != before) {
                    repo.items(_state.value.sourceFilter).onSuccess {
                        _state.value = _state.value.copy(items = it)
                    }
                }
                // Back off on a queue that is not moving — but "moving" means
                // any stage change, not just an episode becoming playable.
                // Keyed on `ready` alone, the interval sat at its 60s ceiling
                // for the whole of a transcription, so an item that had since
                // been downloaded still read as "waiting to download" until
                // the app was reopened and refreshed from scratch.
                val now = pipelineSignature(status)
                interval = when {
                    queueOpen -> FAST_POLL_MS
                    now != signature -> FAST_POLL_MS
                    else -> (interval * 3 / 2).coerceAtMost(SLOW_POLL_MS)
                }
                signature = now
                if (status.processing == 0 && !status.ingestRunning) break
            }
            pollJob = null
        }
    }

    /** Everything the queue view renders, in one comparable value. Two polls
     *  with the same signature genuinely showed the same thing. */
    private fun pipelineSignature(status: PipelineStatus): String =
        buildString {
            append(status.ready).append('/').append(status.processing)
            append('/').append(status.failed).append('/').append(status.archived)
            status.items.forEach { append('|').append(it.id).append(it.status).append(it.attempts) }
        }

    /** The queue sheet is the one place stage changes are read closely, so
     *  polling stays at its fast interval while it is open — and restarts at
     *  once rather than waiting out a backed-off delay. */
    fun setQueueOpen(open: Boolean) {
        queueOpen = open
        if (!open) return
        viewModelScope.launch {
            refreshMeta()
            stopPolling()
            startPolling()
        }
    }

    private var queueOpen = false

    private fun stopPolling() {
        pollJob?.cancel(); pollJob = null
    }

    /** Whether the Listen tab is the one being looked at. Polling is only ever
     *  worth doing for a screen someone can see. */
    private var visible = false

    fun setVisible(value: Boolean) {
        visible = value
        if (!value) stopPolling()
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
            // The high-water mark belongs to the previous scope. Keeping it
            // made the banner read "27 / 36" for a source with nine items.
            batchTotal = 0, status = null,
        )
        viewModelScope.launch {
            // The banner is scoped to the chip, so it has to be refetched here
            // rather than left showing every source's queue under one source.
            runCatching { repo.api.status(slug) }.onSuccess {
                _state.value = _state.value.copy(
                    status = it, batchTotal = batchTotalFor(it),
                )
            }
            // Read first, write second: see the note in init. A refresh in
            // flight while the chip is tapped would otherwise be undone here.
            val hasArchive = probeArchived(slug)
            _state.value = _state.value.copy(hasArchive = hasArchive)
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

    /** Frees the server's copy. The item stays in the library, in its own date
     *  section, and can be fetched again; nothing is permanently lost. */
    fun archive(item: ItemSummary) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyIds = _state.value.busyIds + item.id)
            runCatching { repo.api.archiveItem(item.id) }
            runCatching { repo.removeDownload(item.id) }
            // A removed item comes back as archived in the same list, kept in
            // place by the refresh; it no longer drops into the back catalogue.
            refresh()
            _state.value = _state.value.copy(busyIds = _state.value.busyIds - item.id)
        }
    }

    /**
     * Asks the server to look for new episodes.
     *
     * Offered from the empty library because that is where the need arises;
     * the System tab has the same action beside the figures that justify it.
     */
    fun triggerIngest() {
        viewModelScope.launch {
            runCatching { repo.api.triggerIngest() }
            refreshMeta()
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

    /** Hides the revealed archive again. Nothing is undone on the server: the
     *  list was only ever a view of what was already there. */
    fun showLess() {
        _state.value = _state.value.copy(archived = emptyList(), moreExhausted = false)
    }

    /** Re-reads the part of the archive already on screen, so an item that has
     *  just been cancelled reappears there — that is where it can be fetched
     *  again — and one that has just been queued drops out. */
    private suspend fun reloadArchived(extra: Int = 0) {
        val shown = _state.value.archived.size
        if (shown == 0) return
        // [extra] is how many items have just joined the archive. Without it
        // the page is re-read at its old size, and the arrival at the top
        // pushes the oldest visible item off the bottom.
        val limit = maxOf(shown + extra, 10)
        val page = runCatching {
            repo.api.archivedItems(_state.value.sourceFilter, offset = 0, limit = limit)
        }.getOrNull() ?: return
        _state.value = _state.value.copy(archived = page, moreExhausted = page.size < limit)
    }

    /** Whether this source has anything left to reveal. Checked once per
     *  source so the button is not offered where it would do nothing. */
    private suspend fun probeArchived(slug: String?): Boolean =
        runCatching { repo.api.archivedItems(slug, offset = 0, limit = 1).isNotEmpty() }
            .getOrDefault(false)

    /** Takes an episode out of the pipeline. It returns to the archive rather
     *  than being deleted, so "Show more" can fetch it again. */
    fun cancel(itemId: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                cancellingIds = _state.value.cancellingIds + itemId,
            )
            runCatching { repo.api.cancelItem(itemId) }
            runCatching { repo.removeDownload(itemId) }
            _state.value = _state.value.copy(
                // A cancelled episode leaves the batch, it does not complete
                // it. Without shrinking the high-water mark the banner kept
                // the old total and counted the cancellation as progress.
                batchTotal = (_state.value.batchTotal - 1).coerceAtLeast(0),
                // It is back to archived now, so it must stop looking like a
                // fetch still in flight when its card reappears — inline in its
                // date section if it was fetched before, else the back catalogue.
                fetchingIds = _state.value.fetchingIds - itemId,
            )
            refreshMeta()
            repo.items(_state.value.sourceFilter).onSuccess {
                _state.value = _state.value.copy(items = it)
            }
            reloadArchived(extra = 1)
            _state.value = _state.value.copy(
                cancellingIds = _state.value.cancellingIds - itemId,
            )
        }
    }

    /** Queues a never-fetched item for download and transcription. */
    fun fetch(item: ItemSummary) {
        viewModelScope.launch {
            _state.value = _state.value.copy(fetchingIds = _state.value.fetchingIds + item.id)
            val queued = runCatching { repo.api.restoreItem(item.id) }.isSuccess
            _state.value = _state.value.copy(
                // Once queued the item belongs to the pipeline, not to the
                // archive: leaving it in the revealed list spun its indicator
                // for the life of the screen, including after a cancel put it
                // back. The banner and the queue sheet track it from here.
                archived = if (queued) _state.value.archived.filterNot { it.id == item.id }
                           else _state.value.archived,
                // Same for a removed item fetched from its own date section:
                // once queued it is no longer archived, so drop the in-place
                // card and let the queue banner carry it until it is ready.
                items = if (queued) _state.value.items.filterNot { it.id == item.id }
                        else _state.value.items,
                fetchingIds = _state.value.fetchingIds - item.id,
            )
            refreshMetaPublic()
        }
    }

    suspend fun refreshMetaPublic() = refreshMeta()

    /**
     * Marks an episode unheard.
     *
     * If it is the one loaded in the player, that has to be reset too. This
     * screen only ever cleared the stored position, so clearing an episode the
     * mini player was holding left it sitting there at the position just
     * erased — and playing on from it wrote the whole thing straight back.
     * The maximised player had always done this; the list had not.
     */
    fun clearProgress(item: ItemSummary) {
        viewModelScope.launch {
            if (PlaybackHolder.now.value.itemId == item.id) {
                PlaybackHolder.pause()
                PlaybackHolder.seekTo(0)
                PlaybackHolder.forgetSavedPosition()
            }
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
    val barBehavior = rememberTabBarBehavior()

    // Returning from the player must show the progress just made, without
    // requiring a manual pull to refresh.
    RefreshWhenVisible(visible) { vm.refreshQuietly() }
    // Backgrounding the app or swiping to another tab stops the status poll.
    val active = visible && rememberIsForeground()
    DisposableEffect(active) {
        vm.setVisible(active)
        onDispose { vm.setVisible(false) }
    }
    val nowPlaying by PlaybackHolder.now.collectAsState()
    LaunchedEffect(nowPlaying.itemId) { vm.refreshQuietly() }

    var filterSheet by remember { mutableStateOf(false) }
    var queueSheet by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<ItemSummary?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(barBehavior.nestedScrollConnection),
        topBar = {
            MonoglotTopBar(title = "Listen", scrollBehavior = barBehavior) {
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
            // Named for what it is: `visible` here used to shadow the
            // screen's own `visible` parameter, which means something else
            // entirely.
            val shown = vm.visible(state)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                state.status?.let { status ->
                    if (status.processing > 0 || status.failed > 0) {
                        item {
                            ProcessingBanner(
                                status = status,
                                batchTotal = state.batchTotal,
                                onClick = { queueSheet = true },
                            )
                        }
                    }
                }

                when {
                    state.loading -> items(5) { SkeletonCard() }

                    state.error != null && shown.isEmpty() -> item {
                        ServerErrorState(state.error!!, onRetry = { vm.refresh() })
                    }

                    else -> {
                        // An empty library is not a dead end when the source
                        // has a back catalogue: the archive and its Show more
                        // button used to live in this branch's sibling, so
                        // they were unreachable in exactly the situation that
                        // most needs them — nothing fetched yet, and the only
                        // way out being a round trip to the System tab in the
                        // hope that ingestion turns something up.
                        if (shown.isEmpty()) {
                            item(key = "empty") {
                                EmptyState(
                                    filter = state.filter,
                                    hasArchive = state.hasArchive,
                                    ingesting = state.status?.ingestRunning == true,
                                    onFetchNew = { vm.triggerIngest() },
                                )
                            }
                        }

                        Dates.groupItems(shown).forEach { (header, groupItems) ->
                            stickyHeader(key = "h-$header") { SectionHeader(header, groupItems.size) }
                            items(groupItems, key = { it.id }) { item ->
                                // A removed episode stays in its own date
                                // section, shown as re-fetchable in place rather
                                // than being exiled to the back catalogue.
                                if (item.status == "archived") {
                                    ArchivedCard(
                                        item = item,
                                        busy = item.id in state.fetchingIds,
                                        onFetch = { vm.fetch(item) },
                                        modifier = Modifier.animateItem(),
                                    )
                                } else EpisodeCard(
                                    item = item,
                                    group = header,
                                    sourceFiltered = state.sourceFilter != null,
                                    isNew = isNew(item, state.newSince),
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
                                SectionHeader("Back catalogue", state.archived.size)
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

                        if (state.hasArchive && !state.moreExhausted ||
                            state.archived.isNotEmpty()
                        ) {
                            item(key = "showmore") {
                                ShowMoreButton(
                                    loading = state.loadingMore,
                                    canShowMore = state.hasArchive && !state.moreExhausted,
                                    canShowLess = state.archived.isNotEmpty(),
                                    onMore = { vm.showMore() },
                                    onLess = { vm.showLess() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    }

    if (queueSheet) {
        QueueSheet(
            items = state.status?.items.orEmpty(),
            busy = state.cancellingIds,
            onCancel = { vm.cancel(it) },
            onDismiss = { queueSheet = false },
        )
        DisposableEffect(Unit) {
            vm.setQueueOpen(true)
            onDispose { vm.setQueueOpen(false) }
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
        // Same ratios as the launcher icon and /api/app/icon.svg.
        val heights = listOf(1f / 3, 2f / 3, 1f, 2f / 3, 1f / 3)
        val slot = size.width / barCount
        val barWidth = slot * 0.5f
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
    val all = state.sources.sumOf { it.itemCount }
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
                label = { Text("${source.name}  ${source.itemCount}", maxLines = 1) },
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
 * One episode.
 *
 * The identifying field is the loudest element, because Klartext titles are all
 * identical. Which field that is depends on the section: under "TODAY" the date
 * is already established by the header, and every card headlined "Today" told
 * the reader nothing — there the source is what separates them, since a source
 * publishes at most once a day. Elsewhere the date is the discriminator and
 * leads, as before.
 */
@Composable
private fun EpisodeCard(
    item: ItemSummary,
    group: String,
    sourceFiltered: Boolean,
    isNew: Boolean,
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
        val published = Dates.parse(item.publishedAt)
        val source = item.sourceName.ifBlank { item.sourceSlug }
        // Under a header that already names the day, the day is not worth
        // saying again: the source is what tells one of today's episodes from
        // another. Everywhere else the date is still the discriminator.
        // Only worth doing when the list mixes sources. Filtered to one
        // source there is a single episode per day, so the source names
        // nothing the chip above has not already said, and the date goes back
        // to leading.
        val dayIsKnown = Dates.groupNamesTheDay(group) && !sourceFiltered
        val headline = if (dayIsKnown) source else Dates.label(published)
        val airtime = Dates.time(published)

        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dayIsKnown) {
                            SourceDot(item.sourceSlug)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            headline,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                        if (isNew) {
                            Spacer(Modifier.width(8.dp))
                            NewBadge()
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!dayIsKnown) {
                            SourceDot(item.sourceSlug)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            buildList {
                                // The source has moved up into the headline
                                // when the day is known; the airtime takes its
                                // place, and separates two of today's episodes
                                // from the same source if there ever are any.
                                if (dayIsKnown) {
                                    if (airtime.isNotEmpty()) add(airtime)
                                } else {
                                    add(source)
                                }
                                add(formatDuration(item.durationMs))
                            }.joinToString("  ·  "),
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

/**
 * Whether an episode arrived since the Listen tab was last opened.
 *
 * Measured from when the server discovered it, not when the broadcaster aired
 * it. Those differ by hours: 8 Sidor publishes at 09:44 and the nightly fetch
 * picks it up the next morning, so keying off the publish stamp silently
 * dropped the badge from everything that aired before your last visit.
 *
 * Something already started is never new, whatever its date: the mark answers
 * "what showed up while I was away", and an episode you are part-way through
 * is not that.
 */
private fun isNew(item: ItemSummary, since: Long): Boolean {
    if (since <= 0L || item.positionMs > 0 || item.completed) return false
    val discovered = Dates.parse(item.discoveredAt) ?: return false
    return discovered.toInstant().toEpochMilli() > since
}

@Composable
private fun NewBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            "NEW",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** Sources are colour-coded so the eye can separate them without reading. */
@Composable
private fun SourceDot(slug: String) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(sourceColor(slug)))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Dates.label(Dates.parse(item.publishedAt)),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Removing an episode from the server does not unhear it.
                    // The archive is where a re-fetch is decided, so it is the
                    // one place that has to say the episode was already done.
                    if (item.completed) {
                        Spacer(Modifier.width(7.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Finished",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else if (item.positionMs > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Started",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
private fun ShowMoreButton(
    loading: Boolean,
    canShowMore: Boolean,
    canShowLess: Boolean,
    onMore: () -> Unit,
    onLess: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        if (canShowMore) {
            OutlinedButton(onClick = onMore, enabled = !loading) {
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
        // Revealing the back catalogue was one-way: the only way to put a
        // hundred old episodes away again was to switch source and come back.
        if (canShowLess) {
            if (canShowMore) Spacer(Modifier.width(10.dp))
            TextButton(onClick = onLess, enabled = !loading) {
                Icon(Icons.Default.ExpandLess, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show less")
            }
        }
    }
}

@Composable
private fun ProcessingBanner(
    status: PipelineStatus,
    batchTotal: Int,
    onClick: () -> Unit,
) {
    val done = (batchTotal - status.processing).coerceAtLeast(0)
    val fraction = if (batchTotal > 0) done.toFloat() / batchTotal else 0f
    val animated by animateFloatAsState(fraction, tween(500), label = "batch")

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
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
                        if (status.transcribing > 0) add("${status.transcribing} transcribing")
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
private fun EmptyState(
    filter: LibraryFilter,
    hasArchive: Boolean,
    ingesting: Boolean,
    onFetchNew: () -> Unit,
) {
    val filtered = filter != LibraryFilter.ALL
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(50.dp))
        Icon(Icons.Default.Headphones, null, Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                filtered -> "Nothing matches this filter"
                hasArchive -> "Nothing fetched yet"
                else -> "No episodes yet"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                filtered -> "Try a different filter, or show everything."
                // The button is directly below, so say so rather than sending
                // the reader to another tab.
                hasArchive -> "This source has episodes the server has not " +
                    "fetched. Show more lists them, and each one can be " +
                    "fetched on its own."
                else -> "Pull down to refresh, or fetch new episodes from the System tab."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Nothing here and nothing in the archive either: the only thing that
        // can help is ingestion, so offer it here rather than describing where
        // else to go and find it.
        if (!filtered && !hasArchive) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onFetchNew, enabled = !ingesting) {
                if (ingesting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Looking…")
                } else {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fetch new episodes")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}


/**
 * What the pipeline is actually working on.
 *
 * The banner only ever gave a count, so a queue that had stopped moving was
 * indistinguishable from one that was working — and when something had failed,
 * the reason was only in the server's logs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    items: List<PipelineItem>,
    busy: Set<Int>,
    onCancel: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // The sheet is sized by its content, so removing a row used to shrink it —
    // undoing a drag the user had just made to see more of the queue.
    // Cancelling an episode is precisely when that happened. The height only
    // ever grows while the sheet is open, and resets when it is reopened.
    val density = LocalDensity.current
    var tallest by remember { mutableStateOf(0.dp) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = tallest, max = QUEUE_SHEET_MAX)
                .onSizeChanged {
                    with(density) {
                        val h = it.height.toDp()
                        if (h > tallest) tallest = h.coerceAtMost(QUEUE_SHEET_MAX)
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            Text(
                "Queue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Transcription runs on the server, about a minute per four " +
                    "minutes of audio. Failures are retried automatically. " +
                    "Cancelling puts an episode back in the archive — it is " +
                    "still there under Show more, and can be fetched again.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            if (items.isEmpty()) {
                Text(
                    "Nothing in the queue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    StageDot(item.status)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val date = Dates.label(Dates.parse(item.publishedAt))
                        Text(
                            if (date == "—") item.title.ifBlank { "Episode ${item.id}" }
                            else date,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (date != "—" && item.title.isNotBlank()) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            stageLabel(item),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.status == "failed")
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Real progress for both slow stages: bytes as the
                        // audio arrives, then audio-seconds as the model
                        // consumes it. A spinner only says "something is
                        // happening", which is no help on a big episode.
                        val active = item.status == "downloading" ||
                            item.status == "transcribing"
                        if (active && (item.progress > 0f || item.bytesDone > 0)) {
                            Spacer(Modifier.height(5.dp))
                            if (item.progress > 0f) {
                                LinearProgressIndicator(
                                    progress = { item.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape),
                                )
                            } else {
                                // No Content-Length: how much has arrived is
                                // known, how much is left is not.
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape),
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                progressLabel(item),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (item.error.isNotBlank()) {
                            Text(
                                item.error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Cancelling puts the episode back in the archive: it is
                    // not lost, and Show more will fetch it again.
                    if (item.id in busy) {
                        // Same box the button occupies, or the spinner lands
                        // somewhere the button never was.
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(
                            onClick = { onCancel(item.id) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Downloads are measured in bytes, transcription in how much of the audio has
 *  been heard; saying "42%" for both would hide which stage is slow. */
private fun progressLabel(item: PipelineItem): String {
    val elapsed = "${item.elapsedSeconds.toInt()}s"
    return if (item.status == "downloading") {
        val done = formatBytesShort(item.bytesDone)
        if (item.bytesTotal > 0) {
            "$done of ${formatBytesShort(item.bytesTotal)} · " +
                "${(item.progress * 100).toInt()}% · $elapsed"
        } else {
            "$done · $elapsed"
        }
    } else {
        "${(item.progress * 100).toInt()}% of the audio · $elapsed"
    }
}

private fun stageLabel(item: PipelineItem): String = when (item.status) {
    "new" -> "waiting to download"
    "downloading" -> "downloading audio"
    "downloaded" -> "waiting for transcription"
    "transcribing" -> "transcribing now"
    "failed" -> "failed, attempt ${item.attempts} of 3"
    else -> item.status
}

@Composable
private fun StageDot(status: String) {
    val color = when (status) {
        "transcribing", "downloading" -> MaterialTheme.colorScheme.primary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
}
