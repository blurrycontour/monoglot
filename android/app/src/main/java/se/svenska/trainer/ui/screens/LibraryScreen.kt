package se.svenska.trainer.ui.screens

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.svenska.trainer.data.Graph
import se.svenska.trainer.data.ItemSummary
import se.svenska.trainer.data.PipelineStatus
import se.svenska.trainer.data.SourceStats
import se.svenska.trainer.ui.util.Dates
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
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repository
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()
    private var pollJob: Job? = null

    init { refresh(initial = true) }

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

    private suspend fun refreshMeta() {
        runCatching { repo.api.system() }.onSuccess {
            _state.value = _state.value.copy(sources = it.sources)
        }
        val status = runCatching { repo.api.status() }.getOrNull() ?: return
        _state.value = _state.value.copy(status = status)
        if (status.processing > 0 || status.ingestRunning) startPolling() else stopPolling()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                val status = runCatching { repo.api.status() }.getOrNull() ?: continue
                val before = _state.value.status?.ready ?: 0
                _state.value = _state.value.copy(status = status)
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

    fun setFilter(slug: String?) {
        _state.value = _state.value.copy(sourceFilter = slug)
        refresh()
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
fun LibraryScreen(onOpen: (Int) -> Unit) {
    val vm: LibraryViewModel = viewModel()
    val state by vm.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val pullState = rememberPullToRefreshState()

    var filterSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // A compact bar: the large variant spent nearly a quarter of the
            // screen on one word.
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppMark(Modifier.size(22.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("Lyssna", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    BadgedBox(
                        badge = {
                            if (state.filter != LibraryFilter.ALL) {
                                Badge(Modifier.size(7.dp))
                            }
                        }
                    ) {
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            filterSheet = true
                        }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filter episodes")
                        }
                    }
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh() },
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val visible = vm.visible(state)
            LaunchedEffect(state.filter) { /* re-compose on filter change */ }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    SourceFilterRow(state) { vm.setFilter(it) }
                }

                state.status?.let { status ->
                    if (status.processing > 0 || status.failed > 0) {
                        item { ProcessingBanner(status) }
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
                                    onOpen = { onOpen(item.id) },
                                    onToggleDownload = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.toggleDownload(item)
                                    },
                                    onArchive = { vm.archive(item) },
                                    modifier = Modifier.animateItem(),
                                )
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
    val counts = state.sources.associate { it.slug to it.ready }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val all = counts.values.sum()
        FilterChip(
            selected = state.sourceFilter == null,
            onClick = { onSelect(null) },
            label = { Text("All  $all") },
        )
        state.sources.forEach { source ->
            FilterChip(
                selected = state.sourceFilter == source.slug,
                onClick = { onSelect(source.slug) },
                label = { Text("${source.name}  ${source.ready}") },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            HorizontalDivider(Modifier.width(0.dp))
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
    onOpen: () -> Unit,
    onToggleDownload: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
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

                DownloadButton(downloaded, busy, onToggleDownload)

                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreVert, "More actions", Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Free up server space") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                            onClick = { menuOpen = false; onArchive() },
                        )
                    }
                }
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

@Composable
private fun ProcessingBanner(status: PipelineStatus) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.processing > 0) {
                CircularProgressIndicator(
                    Modifier.size(15.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Preparing ${status.processing} episode${if (status.processing == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "They appear here as transcription finishes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
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
