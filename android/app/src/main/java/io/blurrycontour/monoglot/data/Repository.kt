package io.blurrycontour.monoglot.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Single source of truth for the UI. Always prefers local data: the app is
 * built to work on a bus with no signal, so the network is the fallback, never
 * the assumption.
 */
class Repository(
    val api: ApiClient,
    val offline: OfflineStore,
    val settings: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Library listing. Falls back to whatever is downloaded when the server is
     * unreachable, so the library is never empty on a train.
     */
    suspend fun items(source: String? = null): Result<List<ItemSummary>> = runCatching {
        api.items(source)
    }.recoverCatching { networkError ->
        val local = offline.downloads.all().map {
            ItemSummary(
                id = it.itemId,
                title = it.title,
                sourceSlug = it.sourceSlug,
                durationMs = it.durationMs,
                status = "ready",
            )
        }
        if (local.isEmpty()) throw networkError else local
    }

    /** Bundle for playback: local copy first, network only on a miss. */
    suspend fun bundle(itemId: Int): Bundle {
        offline.bundle(itemId)?.let { return it }
        return api.bundle(itemId)
    }

    suspend fun isDownloaded(itemId: Int) = offline.isDownloaded(itemId)

    /** Downloads bundle + audio for offline use. */
    suspend fun download(itemId: Int, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        onProgress("Fetching transcript…")
        val bundle = api.bundle(itemId)
        val bundleJson = json.encodeToString(Bundle.serializer(), bundle)

        onProgress("Downloading audio…")
        val dest = offline.audioFile(itemId)
        api.downloadAudio(itemId, dest)

        offline.save(bundle, dest, bundleJson)
        onProgress("Ready offline")
    }

    suspend fun removeDownload(itemId: Int) = offline.remove(itemId)

    /**
     * Playback source for an item: the local file when downloaded, otherwise
     * the streaming URL.
     */
    suspend fun mediaUri(itemId: Int): String {
        val file = offline.audioFile(itemId)
        return if (file.exists()) file.toURI().toString() else api.audioUrl(itemId)
    }

    /**
     * Records position locally always, and pushes to the server when it can.
     * Unsynced positions are flushed by [syncProgress].
     */
    suspend fun saveProgress(itemId: Int, positionMs: Int, completed: Boolean) {
        offline.progress.upsert(
            ProgressEntity(itemId, positionMs, completed, System.currentTimeMillis(), synced = false)
        )
        runCatching {
            api.postProgress(itemId, positionMs, completed)
            offline.progress.markSynced(itemId)
        }
    }

    /** Clears the locally cached position so the library stops showing it. */
    suspend fun clearLocalProgress(itemId: Int) {
        offline.progress.upsert(
            ProgressEntity(itemId, 0, completed = false,
                updatedAt = System.currentTimeMillis(), synced = true)
        )
    }

    suspend fun localProgress(itemId: Int): Int =
        offline.progress.byId(itemId)?.positionMs ?: 0

    /** Flushes positions recorded while offline. Called when the app resumes. */
    suspend fun syncProgress() {
        offline.progress.unsynced().forEach { p ->
            runCatching {
                api.postProgress(p.itemId, p.positionMs, p.completed)
                offline.progress.markSynced(p.itemId)
            }
        }
    }

    suspend fun setWordStatus(lemma: String, status: String) = runCatching {
        api.setWordStatus(lemma, status)
    }

    /**
     * Tap-to-define. Resolves from the in-memory bundle first; the network is
     * only touched when the bundle has no entry, which is what keeps the tap
     * under the latency budget.
     */
    suspend fun lookup(bundle: Bundle?, normalized: String, itemId: Int?): List<Candidate> {
        bundle?.definitions?.get(normalized)?.let { if (it.isNotEmpty()) return it }
        return runCatching { api.lookup(normalized, itemId).candidates }.getOrDefault(emptyList())
    }

    /**
     * Logs the tap itself, always, whichever way the definition was found.
     *
     * The bundle answers most taps without touching the network, so counting
     * inside the lookup call meant a word only entered the vocabulary when the
     * dictionary had failed to inline it — the opposite of the intent.
     * Best-effort: a failure here must never disturb playback.
     */
    suspend fun recordLookup(
        lemma: String,
        itemId: Int?,
        tokenId: Int?,
        manual: Boolean = false,
    ) {
        runCatching { api.recordLookup(lemma, itemId, tokenId, manual) }
    }

    suspend fun episodeSummary(itemId: Int): EpisodeSummary? =
        runCatching { api.itemSummary(itemId) }.getOrNull()
}

/** Manual dependency container. A single-user app does not need Hilt. */
object Graph {
    lateinit var repository: Repository
        private set
    lateinit var updater: io.blurrycontour.monoglot.update.AppUpdater
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return
        val settings = SettingsStore(context.applicationContext)
        repository = Repository(
            api = ApiClient(settings),
            offline = OfflineStore(context.applicationContext),
            settings = settings,
        )
        updater = io.blurrycontour.monoglot.update.AppUpdater(context.applicationContext)
    }
}
