package se.svenska.trainer.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private var controller: MediaController? = null
    private var index: TokenIndex? = null
    private var tickerJob: Job? = null
    private var itemId: Int = -1

    /** Position updates run at 10Hz. 60Hz would burn battery for no
     *  perceptible gain: a word is never shorter than ~100ms. */
    private val tickIntervalMs = 100L

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
                connectController(itemId, bundle)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load")
            }
        }
    }

    private fun connectController(itemId: Int, bundle: Bundle) {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            viewModelScope.launch {
                val uri = repo.mediaUri(itemId)
                val currentTag = c.currentMediaItem?.mediaId
                if (currentTag != itemId.toString()) {
                    c.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(itemId.toString())
                            .setUri(uri)
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(bundle.item.title)
                                    .setArtist(bundle.item.sourceName)
                                    .build()
                            )
                            .build()
                    )
                    c.prepare()
                    // Resume where the user left off. Local position wins:
                    // it is current even when the last session was offline.
                    val resume = maxOf(repo.localProgress(itemId), bundle.item.positionMs)
                    if (resume > 1000) c.seekTo(resume.toLong())
                }
                c.playbackParameters = PlaybackParameters(_state.value.speed)
                c.addListener(playerListener)
                _state.value = _state.value.copy(isPlaying = c.isPlaying)
                startTicker()
            }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                val c = controller
                if (c != null) {
                    val pos = c.currentPosition.toInt()
                    val dur = if (c.duration > 0) c.duration.toInt() else _state.value.durationMs
                    updatePosition(pos, dur)
                }
                delay(tickIntervalMs)
            }
        }
    }

    private fun updatePosition(positionMs: Int, durationMs: Int) {
        val idx = index ?: return
        val tokenIdx = idx.tokenAt(positionMs)
        val segIdx = idx.segmentAt(positionMs)
        val s = _state.value

        // A revealed sentence re-hides once playback leaves it: the reveal is
        // meant to be a momentary crutch, not a creeping slide into full text.
        val revealed = if (s.revealedSegmentIdx >= 0 && segIdx != s.revealedSegmentIdx) {
            -1
        } else {
            s.revealedSegmentIdx
        }

        if (tokenIdx != s.activeTokenIdx || segIdx != s.activeSegmentIdx ||
            positionMs != s.positionMs || revealed != s.revealedSegmentIdx
        ) {
            _state.value = s.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                activeTokenIdx = tokenIdx,
                activeSegmentIdx = segIdx,
                revealedSegmentIdx = revealed,
            )
        }

        // Persist roughly every 5 seconds rather than every tick.
        if (positionMs / 5000 != lastSavedBucket) {
            lastSavedBucket = positionMs / 5000
            viewModelScope.launch {
                repo.saveProgress(itemId, positionMs, completed = durationMs > 0 && positionMs > durationMs - 5000)
            }
        }
    }

    private var lastSavedBucket = -1

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(ms: Int) {
        controller?.seekTo(ms.toLong().coerceAtLeast(0))
    }

    fun skip(deltaMs: Int) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }

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
        controller?.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
        viewModelScope.launch { repo.settings.setSpeed(speed) }
    }

    fun cycleTranscriptMode() {
        val next = when (_state.value.transcriptMode) {
            TranscriptMode.HIDDEN -> TranscriptMode.REVEAL
            TranscriptMode.REVEAL -> TranscriptMode.FULL
            TranscriptMode.FULL -> TranscriptMode.HIDDEN
        }
        _state.value = _state.value.copy(transcriptMode = next, revealedSegmentIdx = -1)
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

    override fun onCleared() {
        tickerJob?.cancel()
        controller?.removeListener(playerListener)
        // The controller is released, not the player: playback continues in
        // the service after the screen goes away.
        controller?.release()
        controller = null
        super.onCleared()
    }
}
