package io.blurrycontour.monoglot.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.blurrycontour.monoglot.data.BootstrapStatus
import io.blurrycontour.monoglot.data.ContainerStat
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.data.SourceStats
import io.blurrycontour.monoglot.data.SystemInfo
import io.blurrycontour.monoglot.ui.util.RefreshWhenVisible
import io.blurrycontour.monoglot.ui.util.formatBytesShort

data class SystemState(
    val loading: Boolean = true,
    val info: SystemInfo? = null,
    val bootstrap: BootstrapStatus = BootstrapStatus(),
    val error: String? = null,
    val offlineBytes: Long = 0,
    val offlineCount: Int = 0,
    val message: String? = null,
    val busy: Boolean = false,
)

class SystemViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repository
    private val _state = MutableStateFlow(SystemState())
    val state = _state.asStateFlow()

    init {
        load()
        // Everything held here belongs to one server; start over when it
        // changes rather than showing the old instance's data.
        viewModelScope.launch {
            repo.settings.serverEpochFlow.drop(1).collect { load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.info == null)
            val offlineBytes = repo.offline.totalBytes()
            val offlineCount = repo.offline.downloads.all().size
            runCatching { repo.api.status() }.onSuccess {
                _state.value = _state.value.copy(bootstrap = it.bootstrap)
                // A first start runs for minutes. Poll while it does, so the
                // screen shows it finishing rather than needing a manual
                // refresh to find out.
                if (it.bootstrap.running) pollBootstrap()
            }
            runCatching { repo.api.system() }
                .onSuccess {
                    _state.value = _state.value.copy(
                        loading = false, info = it, error = null,
                        offlineBytes = offlineBytes, offlineCount = offlineCount,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false, error = it.message ?: "Cannot reach server",
                        offlineBytes = offlineBytes, offlineCount = offlineCount,
                    )
                }
        }
    }

    private var polling: Job? = null

    private fun pollBootstrap() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (true) {
                delay(4_000)
                val status = runCatching { repo.api.status() }.getOrNull() ?: continue
                _state.value = _state.value.copy(bootstrap = status.bootstrap)
                if (!status.bootstrap.running) {
                    load()
                    break
                }
            }
        }
    }

    override fun onCleared() {
        polling?.cancel()
        super.onCleared()
    }

    fun cleanup(days: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val n = runCatching { repo.api.cleanup(days) }.getOrDefault(0)
            _state.value = _state.value.copy(
                busy = false,
                message = if (n == 0) "Nothing older than $days days to free"
                          else "Freed $n episode${if (n == 1) "" else "s"}",
            )
            load()
        }
    }

    fun clearDownloads() {
        viewModelScope.launch {
            repo.offline.clearAll()
            _state.value = _state.value.copy(message = "Offline downloads cleared")
            load()
        }
    }

    fun triggerIngest() {
        viewModelScope.launch {
            val ok = runCatching { repo.api.triggerIngest() }.isSuccess
            _state.value = _state.value.copy(
                message = if (ok) "Fetching new episodes" else "Could not start"
            )
            load()
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(visible: Boolean = true) {
    val vm: SystemViewModel = viewModel()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var cleanupDialog by remember { mutableStateOf(false) }

    // Finished counts and container figures both go stale the moment you leave
    // this tab; reload whenever it is the one on screen.
    RefreshWhenVisible(visible) { vm.load() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        // contentColorFor(Transparent) is Unspecified, which leaves
        // LocalContentColor at its black default. Every piece of unstyled text
        // on the screen would otherwise be black regardless of theme.
        containerColor = Color.Transparent,
        // The tab pager already sits above the bottom bar, so the Scaffold must
        // not reserve the navigation-bar inset a second time: that left a dead
        // strip that clipped the last row of content short of the bar.
        contentWindowInsets = WindowInsets(0),
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { MonoglotTopBar(title = "System") },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val info = state.info

            if (state.bootstrap.running || state.bootstrap.error.isNotBlank()) {
                BootstrapCard(state.bootstrap)
            }

            if (state.error != null && info == null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Cannot reach server", fontWeight = FontWeight.Medium)
                        Text(state.error!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            info?.let { sys ->
                // Listening progress, the number that actually reflects the point
                // of the app.
                SectionCard("Progress") {
                    StatRow("Episodes finished", "${sys.items.completed} of ${sys.items.ready}")
                    StatRow("In progress", "${sys.items.started}")
                    StatRow("Time listened", formatHours(sys.listenedMs))
                    StatRow("Words looked up", "${sys.vocabulary.lookups}")
                    StatRow("Known", "${sys.vocabulary.known}")
                    StatRow("Learning", "${sys.vocabulary.learning}")
                }

                SectionCard("Sources") {
                    sys.sources.forEach { source ->
                        SourceBlock(source)
                        if (source !== sys.sources.last()) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        }
                    }
                }

                SectionCard("Server storage") {
                    StatRow("Audio", formatBytesShort(sys.storage.audioBytes))
                    StatRow("Transcripts (raw)", formatBytesShort(sys.storage.rawBytes))
                    StatRow("Dictionary downloads", formatBytesShort(sys.storage.cacheBytes))
                    StatRow("Database", formatBytesShort(sys.storage.databaseBytes))
                    StatRow("App package", formatBytesShort(sys.storage.apkBytes))
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    // The sum, so the figure that matters is not left to be
                    // added up from five rows.
                    StatRow(
                        "Total used",
                        formatBytesShort(sys.storage.totalBytes + sys.storage.databaseBytes),
                        emphasise = true,
                    )
                    StatRow("Free on disk", formatBytesShort(sys.storage.diskFree))

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { cleanupDialog = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Free up space from old episodes")
                    }
                    Text(
                        "Removes audio and transcripts from the server. Episodes stay in " +
                            "the library and can be fetched again. Anything you have started " +
                            "is skipped.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            SectionCard("On this phone") {
                StatRow("Downloaded episodes", "${state.offlineCount}")
                StatRow("Storage used", formatBytesShort(state.offlineBytes))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { vm.clearDownloads() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear offline downloads") }
            }

            info?.let { sys ->
                if (sys.containers.isNotEmpty()) {
                    SectionCard("Containers") {
                        sys.containers.forEachIndexed { i, c ->
                            if (i > 0) Spacer(Modifier.height(10.dp))
                            ContainerRow(c)
                        }
                        sys.containers.firstOrNull { it.memLimit > 0 }?.let {
                            Spacer(Modifier.height(10.dp))
                            StatRow("Memory available", formatBytesShort(it.memLimit))
                        }
                        Text(
                            "From the Docker socket, mounted read-only. Whisper is the " +
                                "memory: the worker holds the model until it idles out.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                SectionCard("Dictionary") {
                    StatRow("Definitions", "%,d".format(sys.lexicon.lexemes))
                    StatRow("Word forms", "%,d".format(sys.lexicon.forms))
                    sys.languages.forEach {
                        StatRow("Language", "${it.name} (${it.nativeName})")
                    }
                }

                Button(
                    onClick = { vm.triggerIngest() },
                    enabled = !sys.ingestRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (sys.ingestRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Fetching…")
                    } else {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Fetch new episodes")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (cleanupDialog) {
        CleanupDialog(
            onDismiss = { cleanupDialog = false },
            onConfirm = { days -> cleanupDialog = false; vm.cleanup(days) },
        )
    }
}

@Composable
private fun CleanupDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var days by remember { mutableIntStateOf(30) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Free up space") },
        text = {
            Column {
                Text("Remove audio and transcripts for episodes older than:")
                Spacer(Modifier.height(14.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(7, 14, 30, 90).forEachIndexed { i, d ->
                        SegmentedButton(
                            selected = days == d,
                            onClick = { days = d },
                            shape = SegmentedButtonDefaults.itemShape(i, 4),
                        ) { Text("${d}d") }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Episodes you have started are never removed, and anything removed " +
                        "can be fetched again later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(days) }) { Text("Free up") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SourceBlock(source: SourceStats) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(source.name, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (!source.enabled) {
                Text("off", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(7.dp))

        val done = source.completed
        val total = source.ready.coerceAtLeast(1)
        LinearProgressIndicator(
            progress = { done.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "$done finished · ${source.started} started · ${source.ready} available" +
                if (source.archived > 0) " · ${source.archived} freed" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatBytesShort(source.audioBytes) + " of audio",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, emphasise: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasise) FontWeight.SemiBold else FontWeight.Medium,
            color = if (emphasise) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatHours(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes min" else "%d h %02d min".format(minutes / 60, minutes % 60)
}


/** One container's live figures. The name is the compose service, which is
 *  what you would type to look at its logs. */
@Composable
private fun ContainerRow(c: ContainerStat) {
    val running = c.state == "running"
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                c.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (running) "%.0f%% CPU · %s".format(c.cpuPercent, formatBytesShort(c.memBytes))
                else c.status.ifBlank { c.state },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (running && c.memLimit > 0) {
            Spacer(Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { (c.memPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                drawStopIndicator = {},
            )
        }
    }
}


/**
 * First-run progress. A new server downloads a dictionary and a million word
 * forms before it can define anything, and without this the app just looks
 * broken for several minutes.
 */
@Composable
private fun BootstrapCard(status: BootstrapStatus) {
    val failed = status.error.isNotBlank()
    SectionCard(if (failed) "Setup failed" else "Setting up") {
        if (failed) {
            Text(status.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "The server retries on its own, and again whenever it restarts. " +
                    "Nothing already imported is lost.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    status.step.ifBlank { "starting" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Elapsed rather than a percentage: the importer knows how many
            // rows it has written, not how many are left.
            StatRow("Running for", formatElapsed(status.elapsedSeconds))
            if (status.attempt > 1) StatRow("Attempt", "${status.attempt} of 3")
        }
    }
}

private fun formatElapsed(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    else -> "${seconds / 60}m ${seconds % 60}s"
}
