package io.blurrycontour.monoglot.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
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
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.data.SourceRow
import io.blurrycontour.monoglot.player.PlaybackHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import io.blurrycontour.monoglot.data.TranscriptMode
import io.blurrycontour.monoglot.ui.theme.ACCENTS
import io.blurrycontour.monoglot.ui.theme.ALL_THEMES
import io.blurrycontour.monoglot.ui.theme.AppTheme
import io.blurrycontour.monoglot.ui.theme.themeById
import io.blurrycontour.monoglot.ui.util.RefreshWhenVisible
import io.blurrycontour.monoglot.ui.util.formatBytesShort

data class SettingsState(
    val serverUrl: String = "",
    val authToken: String = "",
    val speed: Float = 1.0f,
    val transcriptMode: TranscriptMode = TranscriptMode.HIDDEN,
    val sources: List<SourceRow> = emptyList(),
    val message: String? = null,
    val connection: Connection = Connection.UNKNOWN,
    val offlineBytes: Long = 0,
    val offlineCount: Int = 0,
)

/** Result of the last connection test, shown on the button itself: a snackbar
 *  that has already faded cannot answer "is this address right?". */
enum class Connection { UNKNOWN, TESTING, OK, FAILED }

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
                offlineBytes = repo.offline.totalBytes(),
                offlineCount = repo.offline.downloads.all().size,
            )
            runCatching { repo.api.sources() }.onSuccess {
                _state.value = _state.value.copy(sources = it)
            }
        }
    }

    fun saveServer(url: String, token: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(connection = Connection.TESTING)
            val changed = repo.settings.setServer(url, token)

            // A different server means everything held locally belongs to
            // somebody else: item ids, saved positions and downloaded audio
            // are all per-instance, and reusing them would play one server's
            // episode under another's title.
            if (changed) {
                PlaybackHolder.stop()
                repo.offline.clearForServerChange()
            }

            val ok = repo.api.health()
            _state.value = _state.value.copy(
                serverUrl = url,
                authToken = token,
                connection = if (ok) Connection.OK else Connection.FAILED,
                message = when {
                    ok && changed -> "Connected. Local downloads and positions cleared."
                    ok -> "Connected"
                    else -> "Saved, but the server did not respond"
                },
            )
            load()
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

    /** Downloaded audio lives on this phone, so it is this screen's to clear. */
    fun clearDownloads() {
        viewModelScope.launch {
            repo.offline.clearAll()
            _state.value = _state.value.copy(message = "Offline downloads cleared")
            load()
        }
    }

    fun invalidateConnection() {
        if (_state.value.connection != Connection.TESTING) {
            _state.value = _state.value.copy(connection = Connection.UNKNOWN)
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(visible: Boolean = true) {
    val vm: SettingsViewModel = viewModel()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val barBehavior = rememberTabBarBehavior()

    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var token by remember(state.authToken) { mutableStateOf(state.authToken) }
    // The verdict belongs to the address that was tested, not to the field.
    LaunchedEffect(url, token) { vm.invalidateConnection() }

    RefreshWhenVisible(visible) { vm.load() }

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
        topBar = {
            MonoglotTopBar(title = "Settings", scrollBehavior = barBehavior)
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
            SectionCard("Server") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.1.10:8080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Auth token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.saveServer(url, token) },
                    enabled = state.connection != Connection.TESTING,
                    modifier = Modifier.fillMaxWidth(),
                    colors = when (state.connection) {
                        Connection.OK -> ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        )
                        Connection.FAILED -> ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        else -> ButtonDefaults.buttonColors()
                    },
                ) {
                    when (state.connection) {
                        Connection.TESTING -> {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Testing…")
                        }
                        Connection.OK -> {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connected")
                        }
                        Connection.FAILED -> {
                            Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("No response — check the address")
                        }
                        Connection.UNKNOWN -> Text("Save and test connection")
                    }
                }
            }

            SectionCard("Appearance") { ThemePicker() }

            SectionCard("Playback") {
                Text("Default speed", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                val speeds = listOf(0.5f, 0.75f, 0.85f, 1.0f, 1.25f, 1.5f, 2.0f)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(speeds.size) { i ->
                        val sp = speeds[i]
                        FilterChip(
                            selected = kotlin.math.abs(state.speed - sp) < 0.01f,
                            onClick = { vm.setSpeed(sp) },
                            label = { Text("${trimSpeed(sp)}×") },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("Default transcript mode", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow {
                    TranscriptMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = state.transcriptMode == m,
                            onClick = { vm.setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, TranscriptMode.entries.size),
                        ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hidden is the default on purpose: the app works by making you listen first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Downloaded audio is on this phone, so it is configured here
            // rather than under System, which reports on the server.
            SectionCard("On this phone") {
                StatRow("Downloaded episodes", "${state.offlineCount}")
                StatRow("Storage used", formatBytesShort(state.offlineBytes))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { vm.clearDownloads() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear offline downloads") }
            }

            SectionCard("Reminders") { RemindersSection() }

            SectionCard("App updates") { UpdateSection() }

            AboutSection()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Theme picker. Each swatch previews its own background and accent, because a
 * theme name says nothing about how it will feel.
 */
@Composable
private fun ThemePicker() {
    val settings = Graph.repository.settings
    val scope = rememberCoroutineScope()
    val currentTheme by settings.themeFlow.collectAsState(initial = "black")
    val currentAccent by settings.accentFlow.collectAsState(initial = "default")

    Text("Theme", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))

    val light = ALL_THEMES.filter { !it.dark }
    val dark = ALL_THEMES.filter { it.dark }

    listOf("Light" to light, "Dark" to dark).forEach { (group, themes) ->
        Text(
            group,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(themes.size) { i ->
                val theme = themes[i]
                ThemeSwatch(
                    theme = theme,
                    selected = theme.id == currentTheme,
                    onClick = { scope.launch { settings.setTheme(theme.id) } },
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Accent", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ACCENTS.size) { i ->
            val accent = ACCENTS[i]
            val theme = themeById(currentTheme)
            val swatchColor = when {
                accent.id == "default" -> theme.scheme.primary
                theme.dark -> accent.dark
                else -> accent.light
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(swatchColor)
                    .border(
                        width = if (accent.id == currentAccent) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onBackground,
                        shape = CircleShape,
                    )
                    .clickable { scope.launch { settings.setAccent(accent.id) } },
                contentAlignment = Alignment.Center,
            ) {
                if (accent.id == "default") {
                    Text("A", color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 62.dp, height = 84.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(theme.scheme.background)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onClick),
        ) {
            Column(Modifier.padding(8.dp)) {
                Box(
                    Modifier
                        .size(width = 30.dp, height = 7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.scheme.primary)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.scheme.surface)
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .size(width = 22.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(theme.scheme.secondary)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            theme.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            AppMark(Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Monoglot", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Text(
                "Listening trainer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
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
