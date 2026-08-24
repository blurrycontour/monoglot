package se.svenska.trainer.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.svenska.trainer.data.Candidate
import se.svenska.trainer.data.Graph
import se.svenska.trainer.data.WordRow
import se.svenska.trainer.ui.util.RefreshWhenVisible

/**
 * Vocabulary has two states. A word you tapped is by definition one you did
 * not know, so it starts as Learning; the only judgement worth making later is
 * whether you now know it.
 */
enum class WordFilter(val label: String, val status: String?) {
    LEARNING("Learning", "learning"),
    KNOWN("Known", "known"),
    ALL("All", null),
}

class WordsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repository

    // The whole vocabulary is held and filtered on the client. It is a list of
    // hundreds, not thousands, and holding it is what lets the chips carry
    // totals and lets a status change show immediately instead of after a
    // round trip.
    private val _all = MutableStateFlow<List<WordRow>>(emptyList())

    private val _words = MutableStateFlow<List<WordRow>>(emptyList())
    val words = _words.asStateFlow()

    // Learning is the default view: it is the list you would actually work on.
    private val _filter = MutableStateFlow(WordFilter.LEARNING)
    val filter = _filter.asStateFlow()

    /** How many words each chip stands for. */
    private val _counts = MutableStateFlow<Map<WordFilter, Int>>(emptyMap())
    val counts = _counts.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

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
            _loading.value = true
            runCatching { repo.api.words(null) }
                .onSuccess {
                    // Server orders by last_seen already; keep that. Sorting by
                    // status would bury the words just looked up.
                    _all.value = it
                    _error.value = null
                    applyFilter()
                }
                .onFailure { _error.value = it.message ?: "Cannot reach server" }
            _loading.value = false
        }
    }

    private fun applyFilter() {
        val all = _all.value
        _words.value = all.filter { w -> _filter.value.status?.let { it == w.status } ?: true }
        _counts.value = WordFilter.entries.associateWith { f ->
            all.count { w -> f.status?.let { it == w.status } ?: true }
        }
    }

    fun setFilter(f: WordFilter) {
        _filter.value = f
        _selected.value = emptySet()
        applyFilter()
    }

    fun setStatus(lemma: String, status: String) {
        // Applied locally first: the round trip is not slow, but the list
        // visibly lagged the tap that caused it.
        _all.value = _all.value.map { if (it.lemma == lemma) it.copy(status = status) else it }
        applyFilter()
        viewModelScope.launch {
            repo.setWordStatus(lemma, status)
            load()
        }
    }

    fun toggleSelected(lemma: String) {
        _selected.value = if (lemma in _selected.value) _selected.value - lemma
                          else _selected.value + lemma
    }

    fun clearSelection() { _selected.value = emptySet() }
    fun selectAll() { _selected.value = _words.value.map { it.lemma }.toSet() }

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
            runCatching { repo.api.deleteAllWords(_filter.value.status) }
            _selected.value = emptySet()
            load()
        }
    }

    /** Full definitions for the detail sheet. Looked up rather than stored on
     *  the row so the list itself stays light. */
    suspend fun details(lemma: String): List<Candidate> =
        runCatching { repo.api.lookup(lemma, record = false).candidates }
            .getOrDefault(emptyList())

    suspend fun exportUrl(status: String): String {
        val base = repo.settings.serverUrl().trimEnd('/')
        val token = repo.settings.authToken()
        return "$base/api/export/anki?status=$status&token=$token"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WordsScreen(visible: Boolean = true) {
    val vm: WordsViewModel = viewModel()
    val words by vm.words.collectAsState()
    val filter by vm.filter.collectAsState()
    val error by vm.error.collectAsState()
    val loading by vm.loading.collectAsState()
    val selected by vm.selected.collectAsState()
    val counts by vm.counts.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<WordRow?>(null) }
    var practice by remember { mutableStateOf(false) }

    // Words tapped while listening land on the server; without this the list
    // only caught up when the app was restarted.
    RefreshWhenVisible(visible) { vm.load() }

    Scaffold(
        containerColor = Color.Transparent,
        // The tab pager already sits above the bottom bar, so the Scaffold must
        // not reserve the navigation-bar inset a second time: that left a dead
        // strip that clipped the last row of content short of the bar.
        contentWindowInsets = WindowInsets(0),
        contentColor = MaterialTheme.colorScheme.onBackground,
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
                            Icon(Icons.Default.Delete, "Remove selected")
                        }
                    },
                )
            } else {
                MonoglotTopBar(title = "Words", actions = {
                    IconButton(onClick = { confirmDelete = "all" }) {
                        Icon(Icons.Default.DeleteSweep, "Remove all shown")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val url = vm.exportUrl(filter.status ?: "all")
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }
                    }) { Icon(Icons.Default.Share, "Export for Anki") }
                })
            }
        },
        floatingActionButton = {
            // Practice is the point of keeping a word list at all, so it gets
            // a primary action rather than a menu entry.
            if (words.isNotEmpty() && filter != WordFilter.KNOWN) {
                ExtendedFloatingActionButton(
                    onClick = { practice = true },
                    icon = { Icon(Icons.Default.Psychology, null) },
                    text = { Text("Practice") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WordFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.setFilter(f) },
                        label = { Text("${f.label}  ${counts[f] ?: 0}") },
                    )
                }
            }

            when {
                loading && words.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                error != null && words.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                words.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            "Tap words while listening and they collect here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(words, key = { it.lemma }) { word ->
                        WordCard(
                            word = word,
                            selected = word.lemma in selected,
                            selectionActive = selected.isNotEmpty(),
                            onTap = {
                                if (selected.isNotEmpty()) vm.toggleSelected(word.lemma)
                                else detail = word
                            },
                            onLongPress = { vm.toggleSelected(word.lemma) },
                            onToggleKnown = {
                                vm.setStatus(
                                    word.lemma,
                                    if (word.status == "known") "learning" else "known",
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    detail?.let { word ->
        WordDetailSheet(
            word = word,
            loadDetails = { vm.details(word.lemma) },
            onDismiss = { detail = null },
            onStatus = { status -> vm.setStatus(word.lemma, status); detail = null },
        )
    }

    if (practice) {
        PracticeSheet(
            words = words.filter { it.status != "known" },
            loadDetails = { vm.details(it) },
            onKnown = { vm.setStatus(it, "known") },
            onDismiss = { practice = false; vm.load() },
        )
    }

    confirmDelete?.let { mode ->
        val count = if (mode == "selected") selected.size else words.size
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove $count word${if (count == 1) "" else "s"}?") },
            text = {
                Text(
                    "They are deleted from your vocabulary along with their lookup " +
                        "history. Tapping them again while listening records them afresh.",
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

/**
 * Compact row: the word, how often it was looked up, and a known toggle.
 * Meanings are deliberately not shown - seeing the translation for free is
 * what stops it being recall practice.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordCard(
    word: WordRow,
    selected: Boolean,
    selectionActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onToggleKnown: () -> Unit,
) {
    val known = word.status == "known"
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionActive) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                word.lemma,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (word.lookupCount > 1) {
                Text(
                    "${word.lookupCount}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onToggleKnown, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (known) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = if (known) "Mark as learning" else "Mark as known",
                    modifier = Modifier.size(20.dp),
                    tint = if (known) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * The same definition panel the player shows, reached from the word list.
 * Deliberately does not record a lookup: reviewing your own list is not the
 * same event as failing to parse a word while listening.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordDetailSheet(
    word: WordRow,
    loadDetails: suspend () -> List<Candidate>,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
) {
    var candidates by remember { mutableStateOf<List<Candidate>?>(null) }
    LaunchedEffect(word.lemma) { candidates = loadDetails() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(word.lemma, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Looked up ${word.lookupCount}×",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            DefinitionBody(candidates)

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (word.status != "known") {
                    Button(onClick = { onStatus("known") }) {
                        Icon(Icons.Default.Check, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("I know this")
                    }
                } else {
                    OutlinedButton(onClick = { onStatus("learning") }) {
                        Text("Move back to learning")
                    }
                }
            }
        }
    }
}

/** Shared rendering of candidate definitions. */
@Composable
private fun DefinitionBody(candidates: List<Candidate>?) {
    when {
        candidates == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Looking up…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        candidates.isEmpty() -> Text(
            "No definition stored for this word.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> candidates.forEach { candidate ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    candidate.lemma,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (candidate.pos.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        candidate.pos,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            candidate.definitions.take(6).forEach { def ->
                Spacer(Modifier.height(4.dp))
                Text("• ${def.translation}", fontSize = 15.sp)
                if (def.example.isNotBlank()) {
                    Text(
                        def.example,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/**
 * Recall practice over the learning list.
 *
 * Shows the Swedish word alone; the meaning is revealed only after you commit
 * to an answer, because seeing it for free turns recall into recognition.
 * "Know it" promotes; "Not yet" leaves it and reshuffles to the back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PracticeSheet(
    words: List<WordRow>,
    loadDetails: suspend (String) -> List<Candidate>,
    onKnown: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (words.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var queue by remember { mutableStateOf(words.shuffled()) }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<Candidate>?>(null) }
    var reviewed by remember { mutableIntStateOf(0) }

    val current = queue.getOrNull(index)
    LaunchedEffect(current?.lemma, revealed) {
        candidates = null
        if (revealed && current != null) candidates = loadDetails(current.lemma)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp)
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Practice",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$reviewed reviewed · ${queue.size} in rotation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Bounded and scrollable: a word with a dozen senses would
            // otherwise grow the sheet until the answer buttons fell off the
            // bottom, and expanding the sheet did not bring them back.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(28.dp))
                Text(
                    current?.lemma.orEmpty(),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                AnimatedContent(
                    targetState = revealed,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "reveal",
                ) { show ->
                    if (show) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 110.dp)) {
                            DefinitionBody(candidates)
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 110.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = { revealed = true }) {
                                Icon(Icons.Default.Visibility, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Show meaning")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        // Push to the back rather than dropping it, so a word
                        // you missed comes round again this session.
                        current?.let { queue = queue.filter { w -> w.lemma != it.lemma } + it }
                        revealed = false
                        reviewed++
                        if (index >= queue.size - 1) index = 0
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("Not yet") }

                Button(
                    onClick = {
                        current?.let {
                            onKnown(it.lemma)
                            queue = queue.filter { w -> w.lemma != it.lemma }
                        }
                        revealed = false
                        reviewed++
                        if (index >= queue.size - 1) index = 0
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Know it")
                }
            }
        }
    }
}
