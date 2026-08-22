package se.svenska.trainer.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.svenska.trainer.data.Graph
import se.svenska.trainer.data.SourceRow
import se.svenska.trainer.data.TranscriptMode

data class SettingsState(
    val serverUrl: String = "",
    val authToken: String = "",
    val speed: Float = 1.0f,
    val transcriptMode: TranscriptMode = TranscriptMode.HIDDEN,
    val sources: List<SourceRow> = emptyList(),
    val storageBytes: Long = 0,
    val downloadCount: Int = 0,
    val message: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repository
    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                serverUrl = repo.settings.serverUrl(),
                authToken = repo.settings.authToken(),
                speed = repo.settings.speedFlow.first(),
                transcriptMode = repo.settings.transcriptModeFlow.first(),
                storageBytes = repo.offline.totalBytes(),
                downloadCount = repo.offline.downloads.all().size,
            )
            runCatching { repo.api.sources() }.onSuccess {
                _state.value = _state.value.copy(sources = it)
            }
        }
    }

    fun saveServer(url: String, token: String) {
        viewModelScope.launch {
            repo.settings.setServer(url, token)
            val ok = repo.api.health()
            _state.value = _state.value.copy(
                serverUrl = url,
                authToken = token,
                message = if (ok) "Connected" else "Saved, but the server did not respond",
            )
            if (ok) load()
        }
    }

    fun setSpeed(v: Float) {
        viewModelScope.launch { repo.settings.setSpeed(v) }
        _state.value = _state.value.copy(speed = v)
    }

    fun setMode(m: TranscriptMode) {
        viewModelScope.launch { repo.settings.setTranscriptMode(m) }
        _state.value = _state.value.copy(transcriptMode = m)
    }

    fun toggleSource(source: SourceRow) {
        viewModelScope.launch {
            runCatching { repo.api.setSourceEnabled(source.id, !source.enabled) }
            load()
        }
    }

    fun triggerIngest() {
        viewModelScope.launch {
            val result = runCatching { repo.api.triggerIngest() }
            _state.value = _state.value.copy(
                message = if (result.isSuccess) "Ingestion started" else "Could not start ingestion"
            )
        }
    }

    fun clearDownloads() {
        viewModelScope.launch {
            repo.offline.clearAll()
            _state.value = _state.value.copy(message = "Downloads cleared")
            load()
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var token by remember(state.authToken) { mutableStateOf(state.authToken) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Server")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.10:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Auth token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.saveServer(url, token) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save and test connection")
            }

            HorizontalDivider()
            SectionTitle("Playback")
            Text("Default speed", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow {
                listOf(0.75f, 0.85f, 1.0f).forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = kotlin.math.abs(state.speed - s) < 0.01f,
                        onClick = { vm.setSpeed(s) },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                    ) { Text(if (s == 1.0f) "1×" else "${s}×") }
                }
            }

            Text("Default transcript mode", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow {
                TranscriptMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = state.transcriptMode == m,
                        onClick = { vm.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, TranscriptMode.entries.size),
                    ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }
            Text(
                "Hidden is the default on purpose: the app works by making you listen first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("Sources")
            state.sources.forEach { source ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name)
                        Text("${source.itemCount} ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = source.enabled, onCheckedChange = { vm.toggleSource(source) })
                }
            }
            OutlinedButton(onClick = { vm.triggerIngest() }, modifier = Modifier.fillMaxWidth()) {
                Text("Fetch new episodes now")
            }

            HorizontalDivider()
            SectionTitle("Offline")
            Text(
                "${state.downloadCount} downloaded · ${formatBytes(state.storageBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { vm.clearDownloads() }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear downloads")
            }

            HorizontalDivider()
            AboutSection()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
        color = MaterialTheme.colorScheme.primary)
}

/**
 * Attribution is a licence obligation, not a nicety: Folkets lexikon and SALDO
 * are CC BY-SA, and Sveriges Radio requires credit on its material.
 */
@Composable
private fun AboutSection() {
    SectionTitle("About")
    Text(
        "Svenska Listening Trainer — a personal tool for Swedish listening comprehension.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(10.dp))

    Attribution(
        "Sveriges Radio",
        "Klartext audio and episode metadata © Sveriges Radio. Used for personal listening only.",
    )
    Attribution(
        "8 Sidor",
        "Daily news podcast © 8 Sidor. Used for personal listening only.",
    )
    Attribution(
        "Folkets lexikon",
        "Swedish–English dictionary by KTH/CSC, licensed CC BY-SA 2.5.\nfolkets-lexikon.csc.kth.se",
    )
    Attribution(
        "SALDO",
        "Morphology from Språkbanken, University of Gothenburg, licensed CC BY-SA 2.5.",
    )
    Attribution(
        "KB-Whisper",
        "Swedish speech recognition by KBLab, National Library of Sweden.",
    )
}

@Composable
private fun Attribution(title: String, body: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        Text(body, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}
