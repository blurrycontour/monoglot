package se.svenska.trainer.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.svenska.trainer.data.Bundle
import se.svenska.trainer.data.Candidate
import se.svenska.trainer.data.Graph
import se.svenska.trainer.data.Token
import se.svenska.trainer.data.TranscriptMode

data class WordPopup(
    val token: Token,
    val candidates: List<Candidate>,
    val loading: Boolean = false,
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
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Graph.repository

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var index: TokenIndex? = null
    private var itemId: Int = -1
    private var lastSavedBucket = -1

    fun load(itemId: Int) {
        if (this.itemId == itemId && _state.value.bundle != null) return
        this.itemId = itemId

        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val bundle = repo.bundle(itemId)
                index = TokenIndex(bundle.tokens, bundle.segments)
                val mode = repo.settings.transcriptModeFlow.first()
                val speed = repo.settings.speedFlow.first()
                _state.value = _state.value.copy(
                    loading = false,
                    bundle = bundle,
                    durationMs = bundle.item.durationMs,
                    transcriptMode = mode,
                    speed = speed,
                    isDownloaded = repo.isDownloaded(itemId),
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

        // Persist roughly every 5 seconds rather than every tick.
        if (positionMs / 5000 != lastSavedBucket) {
            lastSavedBucket = positionMs / 5000
            val dur = _state.value.durationMs
            viewModelScope.launch {
                repo.saveProgress(itemId, positionMs,
                    completed = dur > 0 && positionMs > dur - 5000)
            }
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
                TranscriptMode.HIDDEN -> TranscriptMode.REVEAL
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
     * Playback is deliberately not paused.
     */
    fun onWordTapped(token: Token) {
        if (!token.isWord) return
        val bundle = _state.value.bundle
        val inline = bundle?.definitions?.get(token.normalized)
        if (!inline.isNullOrEmpty()) {
            _state.value = _state.value.copy(popup = WordPopup(token, inline))
            return
        }
        _state.value = _state.value.copy(popup = WordPopup(token, emptyList(), loading = true))
        viewModelScope.launch {
            val candidates = repo.lookup(bundle, token.normalized, itemId)
            if (_state.value.popup?.token?.id == token.id) {
                _state.value = _state.value.copy(popup = WordPopup(token, candidates))
            }
        }
    }

    fun dismissPopup() {
        _state.value = _state.value.copy(popup = null)
    }

    fun setWordStatus(lemma: String, status: String) {
        viewModelScope.launch { repo.setWordStatus(lemma, status) }
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
