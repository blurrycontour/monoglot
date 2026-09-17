package io.blurrycontour.monoglot.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.blurrycontour.monoglot.data.CleanupPreview
import io.blurrycontour.monoglot.data.ContainerStat
import io.blurrycontour.monoglot.data.DayTotal
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.data.ModelStorageEntry
import io.blurrycontour.monoglot.data.Schedule
import io.blurrycontour.monoglot.data.ModelSettings
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
    val listening: List<DayTotal> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val schedules: List<Schedule> = emptyList(),
    val nextRun: String? = null,
    val model: ModelSettings = ModelSettings(),
    /** Set while a chosen model is being checked, and cleared by the answer.
     *  Validating is a round trip to the worker, so it needs saying. */
    val modelChecking: Boolean = false,
    val modelError: String? = null,
    /** Day threshold for the "old episodes" cleanup option, and what it would
     *  currently free — refetched every time the threshold changes. */
    val oldDays: Int = 30,
    val oldPreview: CleanupPreview? = null,
    val finishedPreview: CleanupPreview? = null,
    val downloadedModels: List<ModelStorageEntry> = emptyList(),
    val selectedModels: Set<String> = emptySet(),
    val modelsBusy: Boolean = false,
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
            // Flush anything the phone banked while offline before asking for
            // totals, or today's bar arrives one session out of date.
            runCatching { repo.syncListening() }
            runCatching { repo.listeningHistory() }.onSuccess {
                _state.value = _state.value.copy(listening = it)
            }
            runCatching { repo.api.schedules() }.onSuccess {
                _state.value = _state.value.copy(
                    schedules = it.schedules, nextRun = it.nextRun,
                )
            }
            runCatching { repo.api.transcriptionModel() }.onSuccess {
                _state.value = _state.value.copy(model = it)
            }
            loadCleanupPreviews()
            runCatching { repo.api.models() }.onSuccess {
                _state.value = _state.value.copy(
                    downloadedModels = it,
                    // A model that no longer exists cannot stay selected.
                    selectedModels = _state.value.selectedModels intersect it.map { m -> m.name }.toSet(),
                )
            }
            // This screen exists to show the container figures, and it always
            // has a spinner up while it loads, so it pays for a live sample
            // rather than showing the one taken before it was opened.
            runCatching { repo.api.system(fresh = true) }
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

    /**
     * Stores a transcription model, once the server has agreed it is loadable.
     *
     * The check happens server-side, against the worker, before anything is
     * written: an id that turns out to be a typo would otherwise not surface
     * until the pipeline ran, as a stalled transcription in the middle of the
     * night rather than as an answer to what was just typed.
     */
    fun setModel(id: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(modelChecking = true, modelError = null)
            val result = runCatching { repo.api.setTranscriptionModel(id.trim()) }
            _state.value = result.fold(
                onSuccess = {
                    _state.value.copy(
                        model = _state.value.model.copy(model = it.model),
                        modelChecking = false, modelError = null,
                        message = "Transcribing with ${it.model} from the next episode",
                    )
                },
                onFailure = {
                    _state.value.copy(modelChecking = false, modelError = readableError(it))
                },
            )
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

    /** Refetches the live count/size for both cleanup options, so the screen
     *  never asks the user to commit to a stale figure. */
    private suspend fun loadCleanupPreviews() {
        runCatching { repo.api.cleanupPreview("old", _state.value.oldDays) }.onSuccess {
            _state.value = _state.value.copy(oldPreview = it)
        }
        runCatching { repo.api.cleanupPreview("finished") }.onSuccess {
            _state.value = _state.value.copy(finishedPreview = it)
        }
    }

    /** Changes the day threshold for "old episodes" and refreshes its preview,
     *  without touching anything else on the screen. */
    fun setOldDays(days: Int) {
        _state.value = _state.value.copy(oldDays = days)
        viewModelScope.launch {
            runCatching { repo.api.cleanupPreview("old", days) }.onSuccess {
                _state.value = _state.value.copy(oldPreview = it)
            }
        }
    }

    fun cleanupOld() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = runCatching { repo.api.cleanup("old", _state.value.oldDays) }.getOrNull()
            _state.value = _state.value.copy(
                busy = false,
                message = if (result == null || result.count == 0) "Nothing older than ${_state.value.oldDays} days to free"
                          else "Freed ${result.count} episode${if (result.count == 1) "" else "s"} " +
                              "(${formatBytesShort(result.bytes)})",
            )
            load()
        }
    }

    fun cleanupFinished() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = runCatching { repo.api.cleanup("finished") }.getOrNull()
            _state.value = _state.value.copy(
                busy = false,
                message = if (result == null || result.count == 0) "No finished episodes to free"
                          else "Freed ${result.count} episode${if (result.count == 1) "" else "s"} " +
                              "(${formatBytesShort(result.bytes)})",
            )
            load()
        }
    }

    fun toggleModelSelected(name: String) {
        val current = _state.value.selectedModels
        _state.value = _state.value.copy(
            selectedModels = if (name in current) current - name else current + name,
        )
    }

    /** Deletes every selected model's cache directory on the worker, one at a
     *  time so a rejection (the active model) does not abort the rest. */
    fun deleteSelectedModels() {
        val names = _state.value.selectedModels.toList()
        if (names.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(modelsBusy = true)
            var freed = 0
            var failed = 0
            for (name in names) {
                runCatching { repo.api.deleteModel(name) }
                    .onSuccess { freed++ }
                    .onFailure { failed++ }
            }
            _state.value = _state.value.copy(
                modelsBusy = false,
                selectedModels = emptySet(),
                message = when {
                    failed == 0 -> "Deleted $freed model${if (freed == 1) "" else "s"}"
                    freed == 0 -> "Could not delete the selected model${if (failed == 1) "" else "s"}"
                    else -> "Deleted $freed, $failed could not be removed"
                },
            )
            runCatching { repo.api.models() }.onSuccess {
                _state.value = _state.value.copy(downloadedModels = it)
            }
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

    fun addSchedule(hour: Int, minute: Int) {
        viewModelScope.launch {
            runCatching { repo.api.addSchedule(hour, minute) }
                .onSuccess { reloadSchedules() }
                .onFailure {
                    _state.value = _state.value.copy(message = "Could not add the time")
                }
        }
    }

    fun deleteSchedule(id: Int) {
        viewModelScope.launch {
            runCatching { repo.api.deleteSchedule(id) }
                .onSuccess { reloadSchedules() }
                .onFailure {
                    _state.value = _state.value.copy(message = "Could not remove the time")
                }
        }
    }

    /** Re-reads only the schedule, so editing a time does not reload the whole
     *  screen and make every figure on it flicker. */
    private suspend fun reloadSchedules() {
        runCatching { repo.api.schedules() }.onSuccess {
            _state.value = _state.value.copy(
                schedules = it.schedules, nextRun = it.nextRun,
            )
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
    var cleanupOldDialog by remember { mutableStateOf(false) }
    var cleanupFinishedDialog by remember { mutableStateOf(false) }
    var deleteModelsDialog by remember { mutableStateOf(false) }
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

                    // Directly under the figures it expands on.
                    SectionCard("Listening") {
                        ListeningSection(state.listening)
                    }

                    SectionCard("Sources") {
                        sys.sources.forEach { source ->
                            SourceBlock(source)
                            if (source !== sys.sources.last()) {
                                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            }
                        }
                    }

                    // What gets fetched, when it gets fetched, and with what.
                    ModelCard(
                        settings = state.model,
                        checking = state.modelChecking,
                        error = state.modelError,
                        onChoose = { vm.setModel(it) },
                    )


                    ScheduleCard(
                        schedules = state.schedules,
                        nextRun = state.nextRun,
                        onAdd = { h, m -> vm.addSchedule(h, m) },
                        onDelete = { vm.deleteSchedule(it) },
                    )

                    SectionCard("Server storage") {
                        StatRow("Audio", formatBytesShort(sys.storage.audioBytes))
                        StatRow("Transcripts (raw)", formatBytesShort(sys.storage.rawBytes))
                        StatRow("Dictionary downloads", formatBytesShort(sys.storage.cacheBytes))
                        StatRow("Whisper models", formatBytesShort(sys.storage.modelBytes))
                        StatRow("Database", formatBytesShort(sys.storage.databaseBytes))
                        StatRow("App package", formatBytesShort(sys.storage.apkBytes))
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        // The sum, so the figure that matters is not left to be
                        // added up from the rows. The server already includes
                        // the database and model weights in this total.
                        StatRow(
                            "Total used",
                            formatBytesShort(sys.storage.totalBytes),
                            emphasise = true,
                        )
                        StatRow("Free on disk", formatBytesShort(sys.storage.diskFree))
                    }

                    SectionCard("Free up space") {
                        CleanupOptionRow(
                            title = "Old episodes",
                            description = describeCleanupPreview(state.oldPreview),
                            busy = state.busy,
                            enabled = (state.oldPreview?.count ?: 0) > 0,
                            onFreeUp = { cleanupOldDialog = true },
                        ) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(7, 14, 30, 90).forEachIndexed { i, d ->
                                    SegmentedButton(
                                        selected = state.oldDays == d,
                                        onClick = { vm.setOldDays(d) },
                                        shape = SegmentedButtonDefaults.itemShape(i, 4),
                                    ) { Text("${d}d") }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        CleanupOptionRow(
                            title = "Finished episodes",
                            description = describeCleanupPreview(state.finishedPreview),
                            busy = state.busy,
                            enabled = (state.finishedPreview?.count ?: 0) > 0,
                            onFreeUp = { cleanupFinishedDialog = true },
                        )
                        Text(
                            "Removes audio and transcripts from the server. Episodes stay in " +
                                "the library and can be fetched again. \"Old episodes\" skips " +
                                "anything you've started listening to.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                    SectionCard("Whisper models") {
                        if (state.downloadedModels.isEmpty()) {
                            Text(
                                "No models downloaded on the server yet.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.downloadedModels.forEachIndexed { i, m ->
                                ModelStorageRow(
                                    model = m,
                                    selected = m.name in state.selectedModels,
                                    onToggle = { vm.toggleModelSelected(m.name) },
                                )
                                if (i < state.downloadedModels.lastIndex) {
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                }
                            }
                            if (state.selectedModels.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(10.dp))
                                val selectedBytes = state.downloadedModels
                                    .filter { it.name in state.selectedModels }.sumOf { it.bytes }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${state.selectedModels.size} selected · " +
                                            formatBytesShort(selectedBytes),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(
                                        onClick = { deleteModelsDialog = true },
                                        enabled = !state.modelsBusy,
                                    ) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Delete")
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "The active transcription model can't be deleted here — change " +
                                    "it above first. A deleted model re-downloads automatically " +
                                    "the next time it's used.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

    if (cleanupOldDialog) {
        ConfirmDialog(
            title = "Free up old episodes?",
            message = "Removes audio and transcripts for ${describeCleanupPreview(state.oldPreview)} " +
                "older than ${state.oldDays} days. Episodes you have started are never removed, " +
                "and anything removed can be fetched again later.",
            confirmLabel = "Free up",
            onDismiss = { cleanupOldDialog = false },
            onConfirm = { cleanupOldDialog = false; vm.cleanupOld() },
        )
    }
    if (cleanupFinishedDialog) {
        ConfirmDialog(
            title = "Free up finished episodes?",
            message = "Removes audio and transcripts for ${describeCleanupPreview(state.finishedPreview)} " +
                "you've listened to the end. Anything removed can be fetched again later.",
            confirmLabel = "Free up",
            onDismiss = { cleanupFinishedDialog = false },
            onConfirm = { cleanupFinishedDialog = false; vm.cleanupFinished() },
        )
    }
    if (deleteModelsDialog) {
        val n = state.selectedModels.size
        ConfirmDialog(
            title = "Delete $n model${if (n == 1) "" else "s"}?",
            message = "Frees disk space now. A deleted model downloads again automatically " +
                "the next time it's used to transcribe.",
            confirmLabel = "Delete",
            onDismiss = { deleteModelsDialog = false },
            onConfirm = { deleteModelsDialog = false; vm.deleteSelectedModels() },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A cleanup option's live count and size, or a placeholder while it loads. */
private fun describeCleanupPreview(preview: CleanupPreview?): String = when {
    preview == null -> "checking…"
    preview.count == 0 -> "nothing to free"
    else -> "${preview.count} episode${if (preview.count == 1) "" else "s"} · " +
        formatBytesShort(preview.bytes)
}

/** One cleanup option: what it would do, and the button that does it. [extra]
 *  holds option-specific controls, such as the day-threshold chips. */
@Composable
private fun CleanupOptionRow(
    title: String,
    description: String,
    busy: Boolean,
    enabled: Boolean,
    onFreeUp: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onFreeUp, enabled = !busy && enabled) { Text("Free up") }
        }
        extra?.let {
            Spacer(Modifier.height(10.dp))
            it()
        }
    }
}

/** One downloaded model: its size, a checkbox, and an "active" badge in place
 *  of the checkbox when it is the one currently configured for transcription. */
@Composable
private fun ModelStorageRow(
    model: ModelStorageEntry,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !model.active, onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !model.active)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                formatBytesShort(model.bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (model.active) {
            AssistChip(onClick = {}, enabled = false, label = { Text("Active") })
        }
    }
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
