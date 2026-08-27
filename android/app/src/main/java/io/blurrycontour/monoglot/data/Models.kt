package io.blurrycontour.monoglot.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ItemSummary(
    val id: Int,
    @SerialName("source_slug") val sourceSlug: String = "",
    @SerialName("source_name") val sourceName: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    /** When the server first saw the episode. What "new" is measured from. */
    @SerialName("discovered_at") val discoveredAt: String? = null,
    @SerialName("duration_ms") val durationMs: Int = 0,
    val status: String = "",
    @SerialName("position_ms") val positionMs: Int = 0,
    val completed: Boolean = false,
    @SerialName("listen_count") val listenCount: Int = 0,
)

@Serializable
data class ItemsResponse(val items: List<ItemSummary> = emptyList())

@Serializable
data class Segment(
    val id: Int,
    val idx: Int,
    @SerialName("start_ms") val startMs: Int,
    @SerialName("end_ms") val endMs: Int,
    val text: String,
)

@Serializable
data class Token(
    val id: Int,
    @SerialName("segment_id") val segmentId: Int,
    val idx: Int,
    val surface: String,
    val normalized: String,
    @SerialName("start_ms") val startMs: Int,
    @SerialName("end_ms") val endMs: Int,
    @SerialName("is_word") val isWord: Boolean = true,
    val lemma: String = "",
)

@Serializable
data class Definition(
    val translation: String = "",
    val comment: String = "",
    val example: String = "",
)

@Serializable
data class Candidate(
    val lemma: String = "",
    val pos: String = "",
    val origin: String = "",
    val definitions: List<Definition> = emptyList(),
)

@Serializable
data class LookupResult(
    val query: String = "",
    val normalized: String = "",
    val candidates: List<Candidate> = emptyList(),
)

/**
 * Everything needed to play an item with no network at all. Definitions for
 * every distinct word are inlined, which is what makes tap-to-define work on
 * the bus with the signal off.
 */
@Serializable
data class Bundle(
    val item: ItemSummary,
    val segments: List<Segment> = emptyList(),
    val tokens: List<Token> = emptyList(),
    val definitions: Map<String, List<Candidate>> = emptyMap(),
    val attribution: Map<String, String> = emptyMap(),
    val version: Int = 1,
)

@Serializable
data class WordRow(
    val lemma: String,
    val status: String = "unknown",
    @SerialName("lookup_count") val lookupCount: Int = 0,
    @SerialName("last_seen") val lastSeen: String = "",
    val definitions: List<Definition> = emptyList(),
    /** Typed into the Words screen rather than met in an episode. */
    @SerialName("added_manually") val addedManually: Boolean = false,
)

@Serializable
data class WordsResponse(val words: List<WordRow> = emptyList())

/** What the server is still working on, so a fresh install shows progress
 *  instead of an empty library while transcription runs. */
@Serializable
data class PipelineStatus(
    val counts: Map<String, Int> = emptyMap(),
    val ready: Int = 0,
    val processing: Int = 0,
    val failed: Int = 0,
    val archived: Int = 0,
    val queued: Int = 0,
    val transcribing: Int = 0,
    val downloading: Int = 0,
    @SerialName("ingest_running") val ingestRunning: Boolean = false,
    val bootstrap: BootstrapStatus = BootstrapStatus(),
    val items: List<PipelineItem> = emptyList(),
)

/** One episode that has not finished the pipeline, with whatever the last
 *  failure said. */
@Serializable
data class PipelineItem(
    val id: Int = 0,
    @SerialName("source_slug") val sourceSlug: String = "",
    val title: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val status: String = "",
    val attempts: Int = 0,
    val error: String = "",
    /** 0..1 while this episode is the one being transcribed. */
    val progress: Float = 0f,
    @SerialName("elapsed_seconds") val elapsedSeconds: Float = 0f,
    @SerialName("bytes_done") val bytesDone: Long = 0,
    @SerialName("bytes_total") val bytesTotal: Long = 0,
)

/** First-run state of the server: a new instance spends several minutes
 *  importing a dictionary before it can define anything. */
@Serializable
data class BootstrapStatus(
    val running: Boolean = false,
    val step: String = "",
    val error: String = "",
    @SerialName("elapsed_seconds") val elapsedSeconds: Int = 0,
    val attempt: Int = 0,
    val complete: Boolean = false,
)

@Serializable
data class SourceRow(
    val id: Int,
    val slug: String,
    val name: String,
    val kind: String = "",
    val enabled: Boolean = true,
    @SerialName("item_count") val itemCount: Int = 0,
)

@Serializable
data class SourcesResponse(val sources: List<SourceRow> = emptyList())

@Serializable
data class Language(
    val code: String,
    val name: String,
    @SerialName("native_name") val nativeName: String = "",
    @SerialName("asr_code") val asrCode: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class SourceStats(
    val id: Int,
    val slug: String,
    val name: String,
    @SerialName("language_code") val languageCode: String = "sv",
    val enabled: Boolean = true,
    @SerialName("last_fetched") val lastFetched: String? = null,
    val total: Int = 0,
    val ready: Int = 0,
    val processing: Int = 0,
    val failed: Int = 0,
    val completed: Int = 0,
    val started: Int = 0,
    val archived: Int = 0,
    @SerialName("audio_bytes") val audioBytes: Long = 0,
)

@Serializable
data class ItemCounts(
    val total: Int = 0,
    val ready: Int = 0,
    val processing: Int = 0,
    val failed: Int = 0,
    val completed: Int = 0,
    val started: Int = 0,
    val archived: Int = 0,
)

@Serializable
data class StorageInfo(
    @SerialName("audio_bytes") val audioBytes: Long = 0,
    @SerialName("raw_bytes") val rawBytes: Long = 0,
    @SerialName("cache_bytes") val cacheBytes: Long = 0,
    @SerialName("apk_bytes") val apkBytes: Long = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("disk_free_bytes") val diskFree: Long = 0,
    @SerialName("database_bytes") val databaseBytes: Long = 0,
)

@Serializable
data class LexiconInfo(val lexemes: Int = 0, val forms: Int = 0)

@Serializable
data class VocabularyInfo(
    val total: Int = 0,
    val known: Int = 0,
    val learning: Int = 0,
    val lookups: Int = 0,
)

@Serializable
data class ContainerStat(
    val name: String = "",
    val state: String = "",
    val status: String = "",
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerialName("mem_bytes") val memBytes: Long = 0,
    @SerialName("mem_limit") val memLimit: Long = 0,
    @SerialName("mem_percent") val memPercent: Double = 0.0,
)

/** End-of-episode debrief. Not a score: the only honest measure available is
 *  which words you had to look up. */
@Serializable
data class EpisodeSummary(
    @SerialName("item_id") val itemId: Int = 0,
    val title: String = "",
    @SerialName("duration_ms") val durationMs: Int = 0,
    val lookups: Int = 0,
    @SerialName("unique_words") val uniqueWords: Int = 0,
    val words: List<String> = emptyList(),
)

@Serializable
data class SystemInfo(
    val sources: List<SourceStats> = emptyList(),
    val items: ItemCounts = ItemCounts(),
    val storage: StorageInfo = StorageInfo(),
    val lexicon: LexiconInfo = LexiconInfo(),
    val vocabulary: VocabularyInfo = VocabularyInfo(),
    val languages: List<Language> = emptyList(),
    @SerialName("ingest_running") val ingestRunning: Boolean = false,
    @SerialName("listened_ms") val listenedMs: Long = 0,
    val containers: List<ContainerStat> = emptyList(),
)

/** One day's listening total, in milliseconds. */
@Serializable
data class DayTotal(
    val day: String = "",
    val ms: Long = 0,
)

@Serializable
data class ListeningResponse(val days: List<DayTotal> = emptyList())

/**
 * One time of day at which the server runs the pipeline by itself.
 *
 * The server's local clock, not the phone's: the times are typed against the
 * machine that will act on them, and a phone in another timezone must not
 * quietly reinterpret them.
 */
@Serializable
data class Schedule(
    val id: Int = 0,
    val hour: Int = 0,
    val minute: Int = 0,
) {
    /** 24h, zero-padded — the same way it was typed. */
    val label: String get() = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
}

@Serializable
data class SchedulesResponse(
    val schedules: List<Schedule> = emptyList(),
    @SerialName("next_run") val nextRun: String? = null,
)

@Serializable
data class ModelOption(val id: String = "", val note: String = "")

@Serializable
data class ModelSettings(
    val model: String = "",
    val default: String = "",
    val suggested: List<ModelOption> = emptyList(),
)
