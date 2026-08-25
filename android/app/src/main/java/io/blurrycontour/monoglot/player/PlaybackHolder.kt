package io.blurrycontour.monoglot.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What is loaded right now, for anything that wants to show playback state. */
data class NowPlaying(
    val itemId: Int = -1,
    val title: String = "",
    val source: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speed: Float = 1.0f,
) {
    val active: Boolean get() = itemId > 0
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Process-wide owner of the MediaController.
 *
 * The mini player has to keep showing what is playing after the player screen
 * is popped, so playback state cannot live in a screen-scoped ViewModel. This
 * holds the single controller connection; the player screen layers transcript
 * state on top of it.
 */
object PlaybackHolder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var onPositionChanged: ((Int) -> Unit)? = null

    /** 10Hz while playing. A word is never shorter than ~100ms, and 60Hz would
     *  burn battery for no perceptible gain. */
    private const val TICK_MS = 100L

    fun connect(context: Context, onReady: (MediaController) -> Unit = {}) {
        controller?.let { onReady(it); return }

        appContext = context.applicationContext
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context.applicationContext, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            syncFromController()
            // Only if something is already playing: connecting is not itself a
            // reason to start polling.
            if (c.isPlaying) startTicker() else syncPosition()
            onReady(c)
        }, MoreExecutors.directExecutor())
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _now.value = _now.value.copy(isPlaying = isPlaying)
            if (isPlaying) startTicker() else stopTicker()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromController()
        }
    }

    private fun syncFromController() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId?.toIntOrNull() ?: -1
        _now.value = _now.value.copy(
            itemId = id,
            title = c.mediaMetadata.title?.toString().orEmpty(),
            source = c.mediaMetadata.artist?.toString().orEmpty(),
            isPlaying = c.isPlaying,
            durationMs = if (c.duration > 0) c.duration.toInt() else _now.value.durationMs,
            speed = c.playbackParameters.speed,
        )
    }

    /**
     * Position polling, running only while audio is actually playing.
     *
     * It used to start on connect and never stop: ten binder round trips a
     * second to the playback service, for the whole life of the process,
     * whether or not anything was playing. Connecting happens on every launch,
     * so the app spent its entire time in the background waking up 10 times a
     * second to ask a paused player where it was. That was most of a 24%
     * battery figure over ten hours.
     */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val c = controller
                if (c == null || !c.isPlaying) break
                val pos = c.currentPosition.toInt()
                val dur = if (c.duration > 0) c.duration.toInt() else _now.value.durationMs
                if (pos != _now.value.positionMs || dur != _now.value.durationMs) {
                    _now.value = _now.value.copy(positionMs = pos, durationMs = dur)
                }
                onPositionChanged?.invoke(pos)
                delay(TICK_MS)
            }
            ticker = null
            // One last read, so a pause leaves the position exact rather than
            // up to a tick stale.
            syncPosition()
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        syncPosition()
    }

    /** A single position read, for the transitions the ticker does not cover. */
    private fun syncPosition() {
        val c = controller ?: return
        val pos = c.currentPosition.toInt()
        val dur = if (c.duration > 0) c.duration.toInt() else _now.value.durationMs
        _now.value = _now.value.copy(positionMs = pos, durationMs = dur)
        onPositionChanged?.invoke(pos)
    }

    /** The player screen registers here to drive word highlighting. */
    fun observePosition(block: ((Int) -> Unit)?) {
        onPositionChanged = block
    }

    private var appContext: Context? = null

    private fun artworkUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/drawable/media_art")

    fun prepare(
        context: Context,
        itemId: Int,
        uri: String,
        title: String,
        source: String,
        durationMs: Int,
        resumeMs: Int,
        speed: Float,
    ) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId != itemId.toString()) {
            c.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(itemId.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(source)
                            // Shown on the lock screen and in the notification.
                            .setArtworkUri(artworkUri(context))
                            .build()
                    )
                    .build()
            )
            c.prepare()
            if (resumeMs > 1000) c.seekTo(resumeMs.toLong())
        }
        c.playbackParameters = PlaybackParameters(speed)
        _now.value = _now.value.copy(
            itemId = itemId, title = title, source = source,
            durationMs = durationMs, speed = speed, isPlaying = c.isPlaying,
        )
        scope.launch {
            io.blurrycontour.monoglot.data.Graph.repository.settings.setLastItem(itemId)
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun play() { controller?.play() }
    fun pause() { controller?.pause() }

    fun seekTo(ms: Int) {
        controller?.seekTo(ms.toLong().coerceAtLeast(0))
        // The ticker is asleep while paused, so nothing else would report the
        // new position.
        syncPosition()
    }

    fun skip(deltaMs: Int) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
        syncPosition()
    }

    fun setSpeed(speed: Float) {
        controller?.playbackParameters = PlaybackParameters(speed)
        _now.value = _now.value.copy(speed = speed)
    }

    /** Clears playback entirely, dismissing the mini player. */
    fun stop() {
        controller?.run { pause(); clearMediaItems() }
        _now.value = NowPlaying()
        // And forget which episode it was, or restoreLastIfIdle brings the bar
        // straight back on the next cold start — closing it has to mean closed.
        // The listening position is kept: it lives per item, not here.
        scope.launch {
            runCatching { io.blurrycontour.monoglot.data.Graph.repository.settings.setLastItem(0) }
        }
    }

    fun position(): Int = controller?.currentPosition?.toInt() ?: 0

    /**
     * Reinstates the last played item, paused and seeked to where it was left,
     * when the process starts with no active media. Without this the mini
     * player is missing after every cold start, even though the app knows
     * exactly what you were listening to.
     */
    fun restoreLastIfIdle(context: Context) {
        scope.launch {
            val repo = io.blurrycontour.monoglot.data.Graph.repository
            val itemId = repo.settings.lastItemFlow.first()
            if (itemId <= 0) return@launch

            connect(context) {
                if (_now.value.active) return@connect
                scope.launch {
                    // Show the bar immediately from the local record, then fill
                    // in details. Waiting for the bundle meant several seconds
                    // of blank space on every cold start.
                    val cached = repo.offline.downloads.byId(itemId)
                    if (cached != null) {
                        _now.value = _now.value.copy(
                            itemId = itemId,
                            title = cached.title,
                            source = cached.sourceSlug,
                            durationMs = cached.durationMs,
                            positionMs = repo.localProgress(itemId),
                        )
                    }
                    val bundle = runCatching { repo.bundle(itemId) }.getOrNull() ?: return@launch
                    prepare(
                        context = context,
                        itemId = itemId,
                        uri = repo.mediaUri(itemId),
                        title = bundle.item.title,
                        source = bundle.item.sourceName,
                        durationMs = bundle.item.durationMs,
                        resumeMs = maxOf(repo.localProgress(itemId), bundle.item.positionMs),
                        speed = repo.settings.speedFlow.first(),
                    )
                }
            }
        }
    }
}
