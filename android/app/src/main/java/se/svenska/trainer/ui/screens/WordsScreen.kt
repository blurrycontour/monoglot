package se.svenska.trainer.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.svenska.trainer.data.Graph
import se.svenska.trainer.data.WordRow

class WordsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repository
    private val _words = MutableStateFlow<List<WordRow>>(emptyList())
    val words = _words.asStateFlow()
    private val _filter = MutableStateFlow<String?>(null)
    val filter = _filter.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            runCatching { repo.api.words(_filter.value) }
                .onSuccess { _words.value = it; _error.value = null }
                .onFailure { _error.value = it.message ?: "Cannot reach server" }
        }
    }

    fun setFilter(status: String?) {
        _filter.value = status
        load()
    }

    fun setStatus(lemma: String, status: String) {
        viewModelScope.launch {
            repo.setWordStatus(lemma, status)
            load()
        }
    }

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()

    fun toggleSelected(lemma: String) {
        _selected.value = if (lemma in _selected.value) _selected.value - lemma
                          else _selected.value + lemma
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun selectAll() { _selected.value = _words.value.map { it.lemma }.toSet() }

    /** Removes words outright. Marking a word known is a judgement about the
     *  word; deleting is for taps that should never have been recorded. */
    fun deleteSelected() {
        val lemmas = _selected.value.toList()
        if (lemmas.isEmpty()) return
        viewModelScope.launch {
            runCatching { repo.api.deleteWords(lemmas) }
            _selected.value = emptySet()
            load()
        }
    }

    fun deleteAllInView() {
        viewModelScope.launch {
            runCatching { repo.api.deleteAllWords(_filter.value) }
            _selected.value = emptySet()
            load()
        }
    }

    suspend fun exportUrl(status: String): String {
        val base = repo.settings.serverUrl().trimEnd('/')
        val token = repo.settings.authToken()
        return "$base/api/export/anki?status=$status&token=$token"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WordsScreen() {
    val vm: WordsViewModel = viewModel()
    val words by vm.words.collectAsState()
    val filter by vm.filter.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selected by vm.selected.collectAsState()
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size} selected", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Default.Close, "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(Icons.Default.SelectAll, "Select all")
                        }
                        IconButton(onClick = { confirmDelete = "selected" }) {
                            Icon(Icons.Default.Delete, "Delete selected")
                        }
                    },
                )
                return@Scaffold
            }
            TopAppBar(
                title = { Text("Words", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { confirmDelete = "all" }) {
                        Icon(Icons.Default.DeleteSweep, "Delete all shown")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            // Hand the CSV to the browser: Anki imports CSV
                            // natively and this avoids shipping a file picker.
                            val url = vm.exportUrl(filter ?: "learning")
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }
                    }) { Icon(Icons.Default.Share, "Export for Anki") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(null to "All", "learning" to "Learning", "known" to "Known", "unknown" to "Unknown")
                    .forEach { (status, label) ->
                        FilterChip(
                            selected = filter == status,
                            onClick = { vm.setFilter(status) },
                            label = { Text(label) },
                        )
                    }
            }

            if (error != null && words.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (words.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "No words yet. Tap words while listening and they show up here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(words) { word ->
                        val isSelected = word.lemma in selected
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) vm.toggleSelected(word.lemma)
                                    },
                                    onLongClick = { vm.toggleSelected(word.lemma) },
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle, null,
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(word.lemma, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${word.lookupCount}×",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val glosses = word.definitions.take(3).joinToString("; ") { it.translation }
                                if (glosses.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(glosses, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("unknown", "learning", "known").forEach { status ->
                                        FilterChip(
                                            selected = word.status == status,
                                            onClick = { vm.setStatus(word.lemma, status) },
                                            label = { Text(status.replaceFirstChar { it.uppercase() }) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { mode ->
        val count = if (mode == "selected") selected.size else words.size
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove $count word${if (count == 1) "" else "s"}?") },
            text = {
                Text(
                    "They are deleted from your vocabulary list along with their " +
                        "lookup history. Tapping them again while listening will " +
                        "record them afresh.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (mode == "selected") vm.deleteSelected() else vm.deleteAllInView()
                    confirmDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}
