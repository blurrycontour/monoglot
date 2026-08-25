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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import io.blurrycontour.monoglot.ui.util.rememberIsForeground
import io.blurrycontour.monoglot.ui.util.formatBytesShort
import java.util.Locale

data class SystemState(
    val loading: Boolean = true,
    val info: SystemInfo? = null,
    val bootstrap: BootstrapStatus = BootstrapStatus(),
    val error: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val refreshing: Boolean = false,
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
            repo.settings.serverEpochFlow.drop(1).collect {
                // Same reasoning as the word list: figures from the previous
                // server must not sit there looking current.
                _state.value = SystemState()
                load()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.info == null)
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
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false, error = it.message ?: "Cannot reach server",
                        // Dropped along with the error: these numbers describe
                        // a server we can no longer reach.
                        info = null,
                    )
                }
        }
    }

    private var polling: Job? = null
    private var visible = false

    fun setVisible(value: Boolean) {
        visible = value
        if (!value) { polling?.cancel(); polling = null }
    }

    private fun pollBootstrap() {
        if (polling?.isActive == true || !visible) return
        polling = viewModelScope.launch {
            while (visible) {
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

    /** Pull-to-refresh, with the indicator held long enough to be seen: the
     *  fetch itself is a LAN round trip and finishes inside a frame. */
    fun refresh() {
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            _state.value = _state.value.copy(refreshing = true)
            load()
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 550L) delay(550L - elapsed)
            _state.value = _state.value.copy(refreshing = false)
        }
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

/**
 * The server: what it holds, what it is doing, and the actions that act on it.
 *
 * Everything belonging to this phone or to the reader — the server address,
 * appearance, playback defaults, reminders, offline downloads — lives in
 * Settings instead. One rule, so nothing has to be hunted for across two
 * screens that look alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(visible: Boolean = true) {
    val vm: SystemViewModel = viewModel()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var cleanupDialog by remember { mutableStateOf(false) }
    val barBehavior = rememberTabBarBehavior()

    // Finished counts and container figures both go stale the moment you leave
    // this tab; reload whenever it is the one on screen.
    RefreshWhenVisible(visible) { vm.load() }
    val active = visible && rememberIsForeground()
    DisposableEffect(active) {
        vm.setVisible(active)
        onDispose { vm.setVisible(false) }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(barBehavior.nestedScrollConnection),
        // contentColorFor(Transparent) is Unspecified, which leaves
        // LocalContentColor at its black default. Every piece of unstyled text
        // on the screen would otherwise be black regardless of theme.
        containerColor = Color.Transparent,
        // The tab pager already sits above the bottom bar, so the Scaffold must
        // not reserve the navigation-bar inset a second time: that left a dead
        // strip that clipped the last row of content short of the bar.
        contentWindowInsets = WindowInsets(0),
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { MonoglotTopBar(title = "System", scrollBehavior = barBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val info = state.info

                if (state.bootstrap.running || state.bootstrap.error.isNotBlank()) {
                    BootstrapCard(state.bootstrap)
                }

                if (state.error != null && info == null) {
                    ServerErrorState(state.error!!, onRetry = { vm.load() })
                }

                info?.let { sys ->
                    // Listening progress, the number that actually reflects the point
                    // of the app.
                    SectionCard("Progress") {
                        StatRow("Episodes finished", "${sys.items.completed}")
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
                        StatRow("Definitions", "%,d".format(Locale.ROOT, sys.lexicon.lexemes))
                        StatRow("Word forms", "%,d".format(Locale.ROOT, sys.lexicon.forms))
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

private fun formatHours(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes min" else "%d h %02d min".format(Locale.ROOT, minutes / 60, minutes % 60)
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
                if (running) "%.0f%% CPU · %s".format(Locale.ROOT, c.cpuPercent, formatBytesShort(c.memBytes))
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
