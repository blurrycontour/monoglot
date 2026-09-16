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
import io.blurrycontour.monoglot.data.WordAudioSource

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
    /** App-wide volume multiplier; 1.0 is the source as recorded. */
    val globalVolume: Float = 1.0f,
    /** Per-episode volume trim, multiplied onto the global one. */
    val episodeVolume: Float = 1.0f,
    /** App-wide reading text size; 1.0 is the design size. */
    val globalTextScale: Float = 1.0f,
    /** Per-episode text-size trim, multiplied onto the global one. */
    val episodeTextScale: Float = 1.0f,
) {
    /** What actually reaches the player: the two trims multiplied, capped at
     *  the boost ceiling the service can deliver. */
    val effectiveVolume: Float get() = (globalVolume * episodeVolume).coerceIn(0f, 2f)

    /** Reading size actually applied, kept inside the legible band. */
    val effectiveTextScale: Float get() = (globalTextScale * episodeTextScale).coerceIn(0.8f, 1.6f)
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Graph.repository

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var index: TokenIndex? = null
    private var itemId: Int = -1
    private var pausedForPopup = false

    /** The episode's audio source, captured on load so the word-preview player
     *  and any later use do not each need a suspend round trip. */
    private var mediaUri: String? = null

    /** Plays a single word pulled straight from the episode audio, so a tap can
     *  be heard as well as read. Deliberately separate from the main session:
     *  it must not move the resume position or write anything down, and it is
     *  never saved. Built once, on first use. */
    private var previewPlayer: androidx.media3.exoplayer.ExoPlayer? = null

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
                mediaUri = repo.mediaUri(itemId)
                val globalVol = repo.settings.globalVolumeFlow.first()
                val episodeVol = repo.settings.episodeVolumeFlow(itemId).first()
                val globalText = repo.settings.textScaleFlow.first()
                val episodeText = repo.settings.episodeTextScaleFlow(itemId).first()
                _state.value = _state.value.copy(
                    loading = false,
                    bundle = bundle,
                    durationMs = bundle.item.durationMs,
                    transcriptMode = mode,
                    speed = speed,
                    isDownloaded = repo.isDownloaded(itemId),
                    completed = bundle.item.completed,
                    globalVolume = globalVol,
                    episodeVolume = episodeVol,
                    globalTextScale = globalText,
                    episodeTextScale = episodeText,
                )
                PlaybackHolder.setVolume(_state.value.effectiveVolume)

                PlaybackHolder.connect(getApplication()) {
                    viewModelScope.launch {
                        PlaybackHolder.prepare(
                            context = getApplication(),
                            itemId = itemId,
                            uri = mediaUri ?: repo.mediaUri(itemId),
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
        // Which audio a tap plays by default, kept live so a change in Settings
        // applies to the episode already open.
        viewModelScope.launch {
            repo.settings.wordTapSourceFlow.collect { wordTapUsesTts = it == WordAudioSource.SPOKEN }
        }
    }

    /** Default word-tap audio, from settings. */
    private var wordTapUsesTts = false

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

    fun setGlobalVolume(v: Float) {
        _state.value = _state.value.copy(globalVolume = v)
        PlaybackHolder.setVolume(_state.value.effectiveVolume)
        viewModelScope.launch { repo.settings.setGlobalVolume(v) }
    }

    fun setEpisodeVolume(v: Float) {
        _state.value = _state.value.copy(episodeVolume = v)
        PlaybackHolder.setVolume(_state.value.effectiveVolume)
        if (itemId > 0) viewModelScope.launch { repo.settings.setEpisodeVolume(itemId, v) }
    }

    fun setGlobalTextScale(v: Float) {
        _state.value = _state.value.copy(globalTextScale = v)
        viewModelScope.launch { repo.settings.setTextScale(v) }
    }

    fun setEpisodeTextScale(v: Float) {
        _state.value = _state.value.copy(episodeTextScale = v)
        if (itemId > 0) viewModelScope.launch { repo.settings.setEpisodeTextScale(itemId, v) }
    }

    /**
     * Hands the episode back starting at this word, from the lookup sheet.
     *
     * Unlike a tap-dismiss, which resumes wherever it paused, this resumes at
     * the word — the "I want to hear this bit again from here" action. The
     * sheet closes and the tap-pause flag is cleared so dismissal does not then
     * seek back.
     */
    fun playFromWord(token: Token) {
        pausedForPopup = false
        _state.value = _state.value.copy(popup = null)
        stopPreview()
        PlaybackHolder.seekTo(token.startMs)
        PlaybackHolder.play()
    }

    /**
     * Plays just this word, pulled straight from the episode audio.
     *
     * A clipped region on a throwaway player: it never touches the main
     * session's position and is never written down. The clip is kept prepared,
     * so tapping "Hear word" again replays it instantly rather than re-reading
     * and re-clipping the source.
     *
     * Three things keep the word clean: the clip is clamped so it can never
     * cross into the neighbouring words (the main cause of bleed on fast, short
     * words), a short volume fade softens each edge, and it plays a little below
     * speed so a brief word is easier to catch.
     */
    fun previewWord(token: Token) {
        val uri = mediaUri ?: return
        val player = previewPlayer ?: androidx.media3.exoplayer.ExoPlayer.Builder(getApplication())
            .build().also { previewPlayer = it }
        // Same word as last time: the clip is still loaded, so just replay it.
        if (token.id == lastPreviewTokenId && player.mediaItemCount > 0) {
            player.seekTo(0)
            player.volume = 0f
            player.play()
            runFadeEnvelope(player, lastPreviewClipMs)
            return
        }

        // Clamp to the neighbouring tokens so an adjacent word can never be
        // included, whatever the Whisper timestamps say — then pad outward
        // within that gap, so a slightly-early or -late boundary does not shear
        // the word's own onset or tail. The padding only ever eats the silence
        // between words, never the words themselves.
        // Clamp the start to the previous word so a preview does not open on
        // the tail of the one before, but leave the end deliberately loose: a
        // little of the next word slipping in matters far less than the tapped
        // word being cut short, which Whisper's early end-timestamps do
        // constantly — worst on long compounds like "kontantsystemet".
        val tokens = index?.tokens
        val i = tokens?.indexOfFirst { it.id == token.id } ?: -1
        val prevEnd = tokens?.getOrNull(i - 1)?.endMs ?: 0
        val rawEnd0 = if (token.endMs > token.startMs) token.endMs else token.startMs + 300
        val start = (token.startMs - CLIP_LEAD_MS).coerceAtLeast(prevEnd).coerceAtLeast(0)
        var end = maxOf(rawEnd0 + CLIP_TRAIL_MS, start + MIN_CLIP_MS)
        val dur = _state.value.durationMs
        if (dur > 0) end = end.coerceAtMost(dur)
        if (end <= start) end = start + 150

        val item = androidx.media3.common.MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(start.toLong())
                    .setEndPositionMs(end.toLong())
                    .build()
            )
            .build()
        lastPreviewTokenId = token.id
        lastPreviewClipMs = end - start
        player.setMediaItem(item)
        player.playbackParameters = androidx.media3.common.PlaybackParameters(PREVIEW_SPEED)
        player.volume = 0f
        player.prepare()
        player.play()
        runFadeEnvelope(player, lastPreviewClipMs)
    }

    /** The word currently loaded in the preview player, so a repeat tap can
     *  replay it without rebuilding the clip. */
    private var lastPreviewTokenId: Int = -1
    private var lastPreviewClipMs: Int = 0
    private var fadeJob: kotlinx.coroutines.Job? = null

    /** Ramps the preview volume up at the start and down before the end, so the
     *  word does not begin or end on an abrupt chop of a neighbour. */
    private fun runFadeEnvelope(player: androidx.media3.exoplayer.ExoPlayer, clipSourceMs: Int) {
        fadeJob?.cancel()
        fadeJob = viewModelScope.launch {
            val wall = (clipSourceMs / PREVIEW_SPEED).toLong().coerceAtLeast(1)
            val fade = minOf(30L, wall / 3)
            val steps = 6
            for (s in 0..steps) {
                player.volume = s / steps.toFloat()
                if (fade > 0) kotlinx.coroutines.delay(fade / steps)
            }
            player.volume = 1f
            kotlinx.coroutines.delay((wall - 2 * fade).coerceAtLeast(0))
            for (s in steps downTo 0) {
                player.volume = s / steps.toFloat()
                if (fade > 0) kotlinx.coroutines.delay(fade / steps)
            }
        }
    }

    private fun stopPreview() {
        fadeJob?.cancel()
        previewPlayer?.run { stop(); clearMediaItems() }
        lastPreviewTokenId = -1
    }

    /** Android's on-device TTS, for a clean reference pronunciation next to the
     *  real episode audio. Built on first use; Swedish voice data must be
     *  installed on the device, otherwise this is a silent no-op. */
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false

    private fun ensureTts() {
        if (tts != null) return
        tts = android.speech.tts.TextToSpeech(getApplication()) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale("sv", "SE")
                ttsReady = true
            }
        }
    }

    /** Speaks the tapped word with the system Swedish voice. The episode clip
     *  is what was actually said; this is the idealised citation form beside it. */
    fun speakWord(token: Token) {
        ensureTts()
        val text = token.surface.trim().ifBlank { return }
        viewModelScope.launch {
            var tries = 0
            while (!ttsReady && tries < 50) { kotlinx.coroutines.delay(20); tries++ }
            if (ttsReady) {
                tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "word-${token.id}")
            }
        }
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

        // Hear the word as well as read it: the episode audio as spoken, or a
        // synthetic reference, per the reader's default. Either is still one tap
        // away in the sheet.
        if (wordTapUsesTts) speakWord(token) else previewWord(token)

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

    /** Undo an accidental tap: every tap files the word automatically, so a
     *  misclick needs a way to drop it from the bank again. */
    fun removeWord(lemma: String) {
        statuses = statuses - lemma
        _state.value.popup?.let { popup ->
            _state.value = _state.value.copy(
                popup = popup.copy(statuses = popup.statuses - lemma))
        }
        viewModelScope.launch { repo.removeWord(lemma) }
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
        fadeJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        tts?.shutdown()
        tts = null
        super.onCleared()
    }

    private companion object {
        /** Word previews play a little under speed: a short word is easier to
         *  catch, and it is a single word so the lower pitch does not matter. */
        const val PREVIEW_SPEED = 0.85f

        /** Breathing room added to each edge of a word clip. The tail is much
         *  longer than the lead because Whisper ends words early far more than
         *  late, and a slip of the next word is preferable to a clipped word. */
        const val CLIP_LEAD_MS = 70
        const val CLIP_TRAIL_MS = 350

        /** No preview is shorter than this, so a very short word still gets a
         *  full, hearable clip rather than a clipped syllable. */
        const val MIN_CLIP_MS = 550
    }
}
