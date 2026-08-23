package se.svenska.trainer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.svenska.trainer.data.Graph
import se.svenska.trainer.data.ItemSummary
import se.svenska.trainer.data.PipelineStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

data class LibraryState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<ItemSummary> = emptyList(),
    val downloadedIds: Set<Int> = emptySet(),
    val busyIds: Set<Int> = emptySet(),
    val sourceFilter: String? = null,
    val status: PipelineStatus? = null,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repository
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()

    private var pollJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repo.syncProgress()
            val result = repo.items(_state.value.sourceFilter)
            val downloaded = repo.offline.downloads.all().map { it.itemId }.toSet()
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, items = it, downloadedIds = downloaded) },
                onFailure = { _state.value.copy(loading = false, error = it.message ?: "Cannot reach server") },
            )
            refreshStatus()
        }
    }

    private suspend fun refreshStatus() {
        val status = runCatching { repo.api.status() }.getOrNull() ?: return
        _state.value = _state.value.copy(status = status)
        // While the server is still transcribing, poll so newly finished
        // episodes appear on their own. A fresh install is otherwise an empty
        // screen for several minutes with no sign anything is happening.
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
                // Only refetch the list when something actually finished.
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
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    fun setFilter(slug: String?) {
        _state.value = _state.value.copy(sourceFilter = slug)
        refresh()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (Int) -> Unit, onWords: () -> Unit, onSettings: () -> Unit) {
    val vm: LibraryViewModel = viewModel()
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Svenska") },
                actions = {
                    IconButton(onClick = onWords) { Icon(Icons.Default.Style, "Words") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(null to "All", "klartext" to "Klartext", "8sidor" to "8 Sidor").forEach { (slug, label) ->
                    FilterChip(
                        selected = state.sourceFilter == slug,
                        onClick = { vm.setFilter(slug) },
                        label = { Text(label) },
                    )
                }
            }

            state.status?.let { ProcessingBanner(it) }

            when {
                state.loading && state.items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                state.error != null && state.items.isEmpty() ->
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.CloudOff, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.refresh() }) { Text("Retry") }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onSettings) { Text("Check server settings") }
                    }

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items) { item ->
                        ItemCard(
                            item = item,
                            downloaded = item.id in state.downloadedIds,
                            busy = item.id in state.busyIds,
                            onOpen = { onOpen(item.id) },
                            onToggleDownload = { vm.toggleDownload(item) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shows what the server is still working on. Transcription is slow, so without
 * this a fresh install looks broken rather than busy.
 */
@Composable
private fun ProcessingBanner(status: PipelineStatus) {
    if (status.processing == 0 && status.failed == 0) return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.processing > 0) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Preparing ${status.processing} episode${if (status.processing == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "They appear here as transcription finishes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
                Text(
                    "${status.failed} episode${if (status.failed == 1) "" else "s"} failed to process",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: ItemSummary,
    downloaded: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggleDownload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.sourceName.ifBlank { item.sourceSlug },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(item.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            item.publishedAt?.take(10)?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (item.positionMs > 0 && item.durationMs > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.completed) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Listened", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onToggleDownload) {
                        Icon(
                            if (downloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                            contentDescription = if (downloaded) "Remove download" else "Download for offline",
                            tint = if (downloaded) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
