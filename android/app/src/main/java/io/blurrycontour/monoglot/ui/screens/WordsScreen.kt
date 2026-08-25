package io.blurrycontour.monoglot.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.blurrycontour.monoglot.data.Candidate
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.data.WordRow
import io.blurrycontour.monoglot.ui.util.RefreshWhenVisible

/**
 * Vocabulary has two states. A word you tapped is by definition one you did
 * not know, so it starts as Learning; the only judgement worth making later is
 * whether you now know it.
 */
/**
 * The two ways of drilling a list.
 *
 * Practice is recall over words you are still learning. Revise is the same
 * gesture over words you have already filed as known — the useful question
 * there is not "do you know it" but "do you still", so a miss demotes rather
 * than a hit promoting.
 */
enum class DrillMode(
    val title: String,
    val missLabel: String,
    val hitLabel: String,
    /** Status a miss files the word under. */
    val missStatus: String,
    /** Status a hit files the word under, or null to leave it alone. */
    val hitStatus: String?,
) {
    PRACTICE("Practice", "Not yet", "Know it", "learning", "known"),
    REVISE("Revise", "Forgot it", "Still know it", "learning", null),
}

/** How the list is ordered. Recency is the default: the words you have just
 *  met are the ones you are working on. */
enum class WordSort(val label: String) {
    RECENT("Recently seen"),
    ALPHABETICAL("A–Ö"),
    MOST_LOOKED_UP("Most looked up"),
}

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

    /** Free-text filter over the list already in memory. */
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _sort = MutableStateFlow(WordSort.RECENT)
    val sort = _sort.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    init {
        load()
        // Everything held here belongs to one server; start over when it
        // changes rather than showing the old instance's data.
        viewModelScope.launch {
            repo.settings.serverEpochFlow.drop(1).collect {
                // Cleared, not just reloaded: a word list from the old server
                // shown under the new one's address is worse than an error,
                // because nothing on screen says it is stale.
                _all.value = emptyList()
                _words.value = emptyList()
                _selected.value = emptySet()
                load()
            }
        }
    }

    /** Pull-to-refresh. Same fetch, but the indicator is held on screen long
     *  enough to be seen. */
    fun refresh() {
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            _refreshing.value = true
            fetch()
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 550L) delay(550L - elapsed)
            _refreshing.value = false
        }
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            fetch()
            _loading.value = false
        }
    }

    private suspend fun fetch() {
        runCatching { repo.api.words(null) }
            .onSuccess {
                // Server orders by last_seen already; keep that. Sorting by
                // status would bury the words just looked up.
                _all.value = it
                _error.value = null
                applyFilter()
            }
            .onFailure {
                _error.value = it.message ?: "Cannot reach server"
                _all.value = emptyList()
                applyFilter()
            }
    }

    private fun applyFilter() {
        val all = _all.value
        val q = _query.value.trim().lowercase()
        val byStatus = all.filter { w -> _filter.value.status?.let { it == w.status } ?: true }
        val matching = if (q.isEmpty()) byStatus
                       else byStatus.filter { it.lemma.lowercase().contains(q) }
        _words.value = when (_sort.value) {
            // The server orders by last_seen, which is the order words were
            // met in: keep it rather than re-deriving it.
            WordSort.RECENT -> matching
            WordSort.ALPHABETICAL -> matching.sortedBy { it.lemma.lowercase() }
            WordSort.MOST_LOOKED_UP -> matching.sortedByDescending { it.lookupCount }
        }
        // Counts describe the chips, which are about status alone: narrowing
        // them by the search box as well would make the totals move while
        // typing and say nothing useful.
        _counts.value = WordFilter.entries.associateWith { f ->
            all.count { w -> f.status?.let { it == w.status } ?: true }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        applyFilter()
    }

    fun setSort(s: WordSort) {
        _sort.value = s
        applyFilter()
    }

    /**
     * Adds a word you met away from the app.
     *
     * Recorded exactly as a tap while listening is, so it lands in the same
     * list with the same shape; the lemma is preferred over what was typed, so
     * an inflected form collapses onto the row it belongs to.
     */
    suspend fun addWord(raw: String): String? {
        val typed = raw.trim().lowercase()
        if (typed.isEmpty()) return null
        val candidates = runCatching { repo.api.lookup(typed, record = false).candidates }
            .getOrDefault(emptyList())
        val lemma = candidates.firstOrNull()?.lemma ?: typed
        repo.recordLookup(lemma, null, null, manual = true)
        load()
        return lemma
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
    val refreshing by vm.refreshing.collectAsState()
    val query by vm.query.collectAsState()
    val sort by vm.sort.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<WordRow?>(null) }
    var drill by remember { mutableStateOf<DrillMode?>(null) }
    var overflow by remember { mutableStateOf(false) }
    var sortSheet by remember { mutableStateOf(false) }
    var addWord by remember { mutableStateOf(false) }
    val barBehavior = rememberTabBarBehavior()

    // Words tapped while listening land on the server; without this the list
    // only caught up when the app was restarted.
    RefreshWhenVisible(visible) { vm.load() }

    Scaffold(
        modifier = Modifier.nestedScroll(barBehavior.nestedScrollConnection),
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
                MonoglotTopBar(title = "Words", scrollBehavior = barBehavior, actions = {
                    // Adding a word is the one thing you do here often enough
                    // to want in reach; exporting is a once-in-a-while errand,
                    // so they have swapped places.
                    IconButton(onClick = { addWord = true }) {
                        Icon(Icons.Default.Add, "Add a word")
                    }
                    // Anything rare or destructive has to be read before it
                    // can be chosen, rather than being one unlabelled glyph
                    // beside another.
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Icon(Icons.Default.MoreVert, "More actions")
                        }
                        DropdownMenu(
                            expanded = overflow,
                            onDismissRequest = { overflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sort: ${sort.label}") },
                                leadingIcon = { Icon(Icons.Default.SwapVert, null) },
                                onClick = { overflow = false; sortSheet = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Export for Anki") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    overflow = false
                                    scope.launch {
                                        val url = vm.exportUrl(filter.status ?: "all")
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, url.toUri()))
                                    }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Remove all shown") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                onClick = { overflow = false; confirmDelete = "all" },
                            )
                        }
                    }
                })
            }
        },
        floatingActionButton = {
            // Drilling is the point of keeping a word list at all, so it gets
            // a primary action rather than a menu entry. Which drill is on
            // offer follows the chip: Learning has words to practise, Known
            // has words to revise, and All has both.
            val hasLearning = words.any { it.status != "known" }
            val hasKnown = words.any { it.status == "known" }
            Column(horizontalAlignment = Alignment.End) {
                if (hasKnown && filter != WordFilter.LEARNING) {
                    if (filter == WordFilter.ALL && hasLearning) {
                        SmallFloatingActionButton(onClick = { drill = DrillMode.REVISE }) {
                            Icon(Icons.Default.History, "Revise known words")
                        }
                    } else {
                        ExtendedFloatingActionButton(
                            onClick = { drill = DrillMode.REVISE },
                            icon = { Icon(Icons.Default.History, null) },
                            text = { Text("Revise") },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (hasLearning && filter != WordFilter.KNOWN) {
                    ExtendedFloatingActionButton(
                        onClick = { drill = DrillMode.PRACTICE },
                        icon = { Icon(Icons.Default.Psychology, null) },
                        text = { Text("Practice") },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                placeholder = { Text("Search words") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Close, "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

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

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
            when {
                loading && words.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                error != null && words.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        ServerErrorState(error!!, onRetry = { vm.load() })
                    }

                words.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(
                            Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (query.isNotBlank()) "No word matches “$query”."
                                else "Tap words while listening and they collect here.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            if (query.isNotBlank()) {
                                Spacer(Modifier.height(14.dp))
                                TextButton(onClick = { addWord = true }) {
                                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Add a word")
                                }
                            }
                        }
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
    }

    detail?.let { word ->
        WordDetailSheet(
            word = word,
            loadDetails = { vm.details(word.lemma) },
            onDismiss = { detail = null },
            onStatus = { status -> vm.setStatus(word.lemma, status); detail = null },
        )
    }

    drill?.let { mode ->
        DrillSheet(
            mode = mode,
            words = when (mode) {
                DrillMode.PRACTICE -> words.filter { it.status != "known" }
                DrillMode.REVISE -> words.filter { it.status == "known" }
            },
            loadDetails = { vm.details(it) },
            onStatus = { lemma, status -> vm.setStatus(lemma, status) },
            onDismiss = { drill = null; vm.load() },
        )
    }

    if (sortSheet) {
        SortSheet(
            current = sort,
            onSelect = { vm.setSort(it); sortSheet = false },
            onDismiss = { sortSheet = false },
        )
    }

    if (addWord) {
        AddWordDialog(
            onDismiss = { addWord = false },
            onAdd = { typed ->
                addWord = false
                scope.launch {
                    val lemma = vm.addWord(typed)
                    if (lemma != null) vm.setQuery("")
                }
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    current: WordSort,
    onSelect: (WordSort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 28.dp)) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            WordSort.entries.forEach { option ->
                ListItem(
                    headlineContent = { Text(option.label) },
                    leadingContent = {
                        RadioButton(
                            selected = current == option,
                            onClick = { onSelect(option) },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSelect(option) },
                )
            }
        }
    }
}

/**
 * Adding a word met somewhere other than an episode — a sign, a conversation.
 *
 * It is looked up on the way in, so what lands in the list is the lemma and
 * not whatever inflected form was typed.
 */
@Composable
private fun AddWordDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a word") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Swedish word") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Looked up and filed under its dictionary form, exactly as a " +
                        "word tapped while listening would be.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(text) },
                enabled = text.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
            )
            // A hand-added word has no episode behind it: no sentence it came
            // from, no airing it belongs to. Worth saying, because otherwise
            // it looks like one you cannot remember hearing.
            if (word.addedManually) {
                Spacer(Modifier.width(7.dp))
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = "Added by hand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (word.lookupCount > 1) {
                Text(
                    "${word.lookupCount}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            // 48dp: the smallest target Material considers reliable.
            IconButton(onClick = onToggleKnown, modifier = Modifier.size(48.dp)) {
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
 * Recall drill over a list of words.
 *
 * Shows the Swedish word alone; the meaning is revealed only after you commit
 * to an answer, because seeing it for free turns recall into recognition. A
 * hit leaves the rotation, a miss goes to the back and comes round again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrillSheet(
    mode: DrillMode,
    words: List<WordRow>,
    loadDetails: suspend (String) -> List<Candidate>,
    onStatus: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (words.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    // The head of the queue is the current card: "Not yet" sends a word to the
    // back, "Know it" drops it. There was an index as well, but both branches
    // left it at zero and its one guard never fired.
    var queue by remember { mutableStateOf(words.shuffled()) }
    var revealed by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<Candidate>?>(null) }
    var reviewed by remember { mutableIntStateOf(0) }
    var promoted by remember { mutableIntStateOf(0) }

    val current = queue.firstOrNull()
    LaunchedEffect(current?.lemma, revealed) {
        candidates = null
        val word = current ?: return@LaunchedEffect
        if (revealed) candidates = loadDetails(word.lemma)
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
                    mode.title,
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

            // Promoting the last word emptied the queue and left an empty
            // headline sitting over a live "Show meaning" and two answer
            // buttons, with nothing to answer about.
            if (current == null) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nothing left to practise",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            promoted == 0 -> "You worked through the whole rotation."
                            mode == DrillMode.REVISE ->
                                "$promoted word${if (promoted == 1) "" else "s"} still known."
                            else ->
                                "$promoted word${if (promoted == 1) "" else "s"} moved to known."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.height(50.dp),
                    ) { Text("Done") }
                }
                return@Column
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
                    current.lemma,
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
                        // A miss under Revise demotes the word: that is the
                        // whole point of revising, and leaving it filed as
                        // known would mean never seeing it in Practice again.
                        onStatus(current.lemma, mode.missStatus)
                        // Back of the queue rather than dropped, so a word you
                        // missed comes round again this session.
                        current.let { queue = queue.filter { w -> w.lemma != it.lemma } + it }
                        revealed = false
                        reviewed++
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text(mode.missLabel) }

                Button(
                    onClick = {
                        mode.hitStatus?.let { onStatus(current.lemma, it) }
                        queue = queue.filter { w -> w.lemma != current.lemma }
                        revealed = false
                        reviewed++
                        promoted++
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(mode.hitLabel, maxLines = 1)
                }
            }
        }
    }
}
