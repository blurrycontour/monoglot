package io.blurrycontour.monoglot.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.blurrycontour.monoglot.data.Bundle
import io.blurrycontour.monoglot.data.Candidate
import io.blurrycontour.monoglot.data.EpisodeSummary
import io.blurrycontour.monoglot.data.Graph
import io.blurrycontour.monoglot.data.Token
import io.blurrycontour.monoglot.data.TranscriptMode

data class WordPopup(
    val token: Token,
    val candidates: List<Candidate>,
    val loading: Boolean = false,
    /** Current vocabulary status per lemma, so the chips can show which one is
     *  already true rather than offering both as if neither were. */
    val statuses: Map<String, String> = emptyMap(),
)

data class PlayerState(
    val loading: Boolean = true,
    val error: String? = null,
    val bundle: Bundle? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val activeTokenIdx: Int = -1,
    val activeSegmentIdx: Int = -1,
    val transcriptMode: TranscriptMode = TranscriptMode.HIDDEN,
    /** In REVEAL mode, the segment the user asked to see. Cleared when it ends. */
    val revealedSegmentIdx: Int = -1,
    val speed: Float = 1.0f,
    val popup: WordPopup? = null,
    val isDownloaded: Boolean = false,
    val completed: Boolean = false,
    /** Shown once the audio runs out, until dismissed. */
    val finished: EpisodeSummary? = null,
    val finishedVisible: Boolean = false,
    val busy: Boolean = false,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Graph.repository

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var index: TokenIndex? = null
    private var itemId: Int = -1
    private var pausedForPopup = false

    /** Whether playback has been somewhere other than the very end since this
     *  episode was opened. Without it, reopening a finished episode resumes at
     *  the end and the summary appears before a single word has played. */
    private var sawBeforeEnd = false

    /** Vocabulary status by lemma. Held for the episode: the word sheet has to
     *  say what a word already is the instant it opens, and a round trip per
     *  tap would put the network back in the path this app keeps it out of. */
    private var statuses: Map<String, String> = emptyMap()

    private fun loadStatuses() {
        viewModelScope.launch {
            statuses = runCatching { repo.api.words(null) }
                .getOrDefault(emptyList())
                .associate { it.lemma to it.status }
        }
    }

    /** Retry after a failure: load() short-circuits when the id is unchanged,
     *  which is exactly the case a retry button is for. */
    fun reload(itemId: Int) {
        this.itemId = -1
        load(itemId)
    }

    fun load(itemId: Int) {
        if (this.itemId == itemId && _state.value.bundle != null) return
        this.itemId = itemId
        sawBeforeEnd = false

        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val bundle = repo.bundle(itemId)
                index = TokenIndex(bundle.tokens, bundle.segments)
                loadStatuses()
                val mode = repo.settings.transcriptModeFlow.first()
                val speed = repo.settings.speedFlow.first()
                _state.value = _state.value.copy(
                    loading = false,
                    bundle = bundle,
                    durationMs = bundle.item.durationMs,
                    transcriptMode = mode,
                    speed = speed,
                    isDownloaded = repo.isDownloaded(itemId),
                    completed = bundle.item.completed,
                )

                PlaybackHolder.connect(getApplication()) {
                    viewModelScope.launch {
                        PlaybackHolder.prepare(
                            context = getApplication(),
                            itemId = itemId,
                            uri = repo.mediaUri(itemId),
                            title = bundle.item.title,
                            source = bundle.item.sourceName,
                            durationMs = bundle.item.durationMs,
                            // Local position wins: it is current even when the
                            // last session was offline.
                            resumeMs = maxOf(repo.localProgress(itemId), bundle.item.positionMs),
                            speed = speed,
                            publishedAt = bundle.item.publishedAt,
                            segmentStartsMs = bundle.segments
                                .map { it.startMs }.toIntArray(),
                        )
                        PlaybackHolder.observePosition { pos -> updatePosition(pos) }
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load")
            }
        }
    }

    init {
        // Mirror transport state owned by the holder into this screen's state.
        viewModelScope.launch {
            PlaybackHolder.now.collect { now ->
                _state.value = _state.value.copy(
                    isPlaying = now.isPlaying,
                    speed = now.speed,
                    durationMs = if (now.durationMs > 0) now.durationMs else _state.value.durationMs,
                )
            }
        }
    }

    private fun updatePosition(positionMs: Int) {
        val idx = index ?: return
        val tokenIdx = idx.tokenAt(positionMs)
        val segIdx = idx.segmentAt(positionMs)
        val s = _state.value

        // A revealed sentence re-hides once playback leaves it: the reveal is
        // meant to be a momentary crutch, not a creeping slide into full text.
        val revealed = if (s.revealedSegmentIdx >= 0 && segIdx != s.revealedSegmentIdx) -1
                       else s.revealedSegmentIdx

        if (tokenIdx != s.activeTokenIdx || segIdx != s.activeSegmentIdx ||
            positionMs != s.positionMs || revealed != s.revealedSegmentIdx
        ) {
            _state.value = s.copy(
                positionMs = positionMs,
                activeTokenIdx = tokenIdx,
                activeSegmentIdx = segIdx,
                revealedSegmentIdx = revealed,
            )
        }

        // Persisting the position is PlaybackHolder's job, not this screen's:
        // it owns the controller for the life of the process, and this view
        // model is cleared the moment the player is popped — while the audio
        // carries on in the mini player, unrecorded.

        // Reaching the end used to leave the last sentence frozen on screen,
        // which reads as a stall rather than an ending.
        val dur = _state.value.durationMs
        if (dur > 0 && positionMs < dur - 2000) sawBeforeEnd = true
        if (dur > 0 && sawBeforeEnd && positionMs >= dur - 1200 &&
            !_state.value.finishedVisible
        ) {
            showFinished()
        }
    }

    fun playPause() = PlaybackHolder.playPause()

    fun seekTo(ms: Int) = PlaybackHolder.seekTo(ms)

    fun skip(deltaMs: Int) = PlaybackHolder.skip(deltaMs)

    /** Replay the sentence being played. The most-used control after play. */
    fun replaySegment() {
        val idx = index ?: return
        val s = _state.value
        val segIdx = if (s.activeSegmentIdx >= 0) s.activeSegmentIdx else 0
        idx.segments.getOrNull(segIdx)?.let { seekTo(it.startMs) }
    }

    fun previousSegment() {
        val idx = index ?: return
        val s = _state.value
        val current = idx.segments.getOrNull(s.activeSegmentIdx)
        // If we are more than a moment into the current sentence, go to its
        // start; only jump back a whole sentence when already near the start.
        val target = if (current != null && s.positionMs - current.startMs > 1500) {
            s.activeSegmentIdx
        } else {
            s.activeSegmentIdx - 1
        }
        idx.segments.getOrNull(target.coerceAtLeast(0))?.let { seekTo(it.startMs) }
    }

    fun nextSegment() {
        val idx = index ?: return
        idx.segments.getOrNull(_state.value.activeSegmentIdx + 1)?.let { seekTo(it.startMs) }
    }

    fun setSpeed(speed: Float) {
        PlaybackHolder.setSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
        viewModelScope.launch { repo.settings.setSpeed(speed) }
    }

    fun cycleTranscriptMode() {
        setTranscriptMode(
            when (_state.value.transcriptMode) {
                TranscriptMode.HIDDEN -> TranscriptMode.LINE
                TranscriptMode.LINE -> TranscriptMode.REVEAL
                TranscriptMode.REVEAL -> TranscriptMode.FULL
                TranscriptMode.FULL -> TranscriptMode.HIDDEN
            }
        )
    }

    fun setTranscriptMode(mode: TranscriptMode) {
        _state.value = _state.value.copy(transcriptMode = mode, revealedSegmentIdx = -1)
    }

    fun revealCurrentSentence() {
        _state.value = _state.value.copy(revealedSegmentIdx = _state.value.activeSegmentIdx)
    }

    /**
     * Tap-to-define. Resolves from the bundle already in memory, so the popup
     * appears immediately; the network is only consulted on a miss.
     *
     * Playback pauses while the sheet is open and resumes on dismissal, but
     * only if the tap is what stopped it: reading a definition over the top of
     * continuing audio means missing the next sentence too.
     */
    fun onWordTapped(token: Token) {
        if (!token.isWord) return
        if (_state.value.isPlaying) {
            pausedForPopup = true
            PlaybackHolder.pause()
        }

        val bundle = _state.value.bundle
        val inline = bundle?.definitions?.get(token.normalized)
        if (!inline.isNullOrEmpty()) {
            _state.value = _state.value.copy(
                popup = WordPopup(token, inline, statuses = statusesFor(inline)))
            record(token, inline)
            return
        }
        _state.value = _state.value.copy(popup = WordPopup(token, emptyList(), loading = true))
        viewModelScope.launch {
            val candidates = repo.lookup(bundle, token.normalized, itemId)
            if (_state.value.popup?.token?.id == token.id) {
                _state.value = _state.value.copy(
                    popup = WordPopup(token, candidates, statuses = statusesFor(candidates)))
            }
            record(token, candidates)
        }
    }

    /** The tap is the event worth counting, not whether the network was
     *  consulted. The lemma is preferred so inflections collapse onto one row. */
    private fun record(token: Token, candidates: List<Candidate>) {
        val lemma = candidates.firstOrNull()?.lemma ?: token.normalized
        viewModelScope.launch { repo.recordLookup(lemma, itemId, token.id) }
    }

    fun dismissPopup() {
        _state.value = _state.value.copy(popup = null)
        if (pausedForPopup) {
            pausedForPopup = false
            PlaybackHolder.play()
        }
    }

    /** Save or remove the offline copy of the episode being played. */
    fun toggleDownload() {
        val id = itemId
        if (id <= 0) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching {
                if (_state.value.isDownloaded) repo.removeDownload(id) else repo.download(id)
            }
            _state.value = _state.value.copy(
                busy = false,
                isDownloaded = repo.isDownloaded(id),
            )
        }
    }

    /**
     * Back to the beginning, on the server and in the player both.
     *
     * Order matters here. This used to clear the stored position first and seek
     * afterwards, with two round trips in between — while the player carried on
     * and its ticker wrote the position it was still at, ten times a second,
     * straight back over the reset. Stopping first means nothing is in flight
     * to undo the work.
     */
    fun clearProgress() {
        val id = itemId
        if (id <= 0) return
        viewModelScope.launch {
            PlaybackHolder.pause()
            PlaybackHolder.seekTo(0)
            PlaybackHolder.forgetSavedPosition()

            runCatching { repo.api.resetProgress(id) }
            runCatching { repo.clearLocalProgress(id) }

            sawBeforeEnd = false
            _state.value = _state.value.copy(
                completed = false,
                positionMs = 0,
                activeTokenIdx = -1,
                activeSegmentIdx = -1,
                revealedSegmentIdx = -1,
                // Clearing a finished episode leaves nothing to celebrate.
                finishedVisible = false,
                finished = null,
            )
        }
    }

    /** Frees the server's copy. The episode stays in the library and can be
     *  fetched again, but there is nothing left to play here. */
    fun archive(onDone: () -> Unit) {
        val id = itemId
        if (id <= 0) return
        viewModelScope.launch {
            PlaybackHolder.stop()
            runCatching { repo.api.archiveItem(id) }
            runCatching { repo.removeDownload(id) }
            onDone()
        }
    }

    private fun statusesFor(candidates: List<Candidate>): Map<String, String> =
        candidates.mapNotNull { c -> statuses[c.lemma]?.let { c.lemma to it } }.toMap()

    fun setWordStatus(lemma: String, status: String) {
        // Locally first, so the chip reflects the tap immediately and the
        // sheet can stay open on the word that was just filed.
        statuses = statuses + (lemma to status)
        _state.value.popup?.let { popup ->
            _state.value = _state.value.copy(
                popup = popup.copy(statuses = popup.statuses + (lemma to status)))
        }
        viewModelScope.launch { repo.setWordStatus(lemma, status) }
    }

    private fun showFinished() {
        _state.value = _state.value.copy(finishedVisible = true, completed = true)
        viewModelScope.launch {
            repo.saveProgress(itemId, _state.value.durationMs, completed = true)
            val summary = repo.episodeSummary(itemId)
            if (_state.value.finishedVisible) {
                _state.value = _state.value.copy(finished = summary)
            }
        }
    }

    /**
     * Done, from the finished screen.
     *
     * The episode is over, so the mini player has nothing left to offer: it sat
     * there afterwards holding a finished episode at its last second, inviting
     * a resume that would replay the final breath and stop again.
     */
    fun closeFinished() {
        dismissFinished()
        PlaybackHolder.stop()
    }

    fun dismissFinished() {
        _state.value = _state.value.copy(finishedVisible = false, finished = null)
    }

    /** Start the episode again from the top, from the finished screen. */
    fun replayEpisode() {
        dismissFinished()
        sawBeforeEnd = false
        seekTo(0)
        PlaybackHolder.play()
    }

    fun tokenIndex(): TokenIndex? = index

    /** Flushes the exact position so the library is correct the moment the
     *  player is popped, rather than up to five seconds stale. */
    fun flushProgress() {
        val pos = PlaybackHolder.position()
        val dur = _state.value.durationMs
        if (itemId <= 0 || pos <= 0) return
        viewModelScope.launch {
            repo.saveProgress(itemId, pos, completed = dur > 0 && pos > dur - 5000)
        }
    }

    override fun onCleared() {
        flushProgress()
        // Only stop driving highlight updates. Playback itself keeps running:
        // the holder owns the controller so the mini player survives this
        // screen being popped.
        PlaybackHolder.observePosition(null)
        super.onCleared()
    }
}
