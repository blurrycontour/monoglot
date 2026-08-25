package io.blurrycontour.monoglot.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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
import io.blurrycontour.monoglot.ui.util.Dates

/** What is loaded right now, for anything that wants to show playback state. */
data class NowPlaying(
    val itemId: Int = -1,
    val title: String = "",
    val source: String = "",
    /** ISO publish date. Klartext titles are identical every day, so this is
     *  the field that actually identifies the episode. */
    val publishedAt: String? = null,
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
                persist(pos)
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
        // Pausing is exactly when the position is worth having exactly right,
        // and it is the last chance before the app may be swiped away.
        flush()
    }

    /** A single position read, for the transitions the ticker does not cover. */
    private fun syncPosition() {
        val c = controller ?: return
        val pos = c.currentPosition.toInt()
        val dur = if (c.duration > 0) c.duration.toInt() else _now.value.durationMs
        // A controller still applying a resume seek reports 0. Taking that at
        // face value would drag the scrubber back to the start of an episode
        // that was resumed halfway through.
        if (pos == 0 && _now.value.positionMs > 0 && !c.isPlaying) {
            _now.value = _now.value.copy(durationMs = dur)
            return
        }
        _now.value = _now.value.copy(positionMs = pos, durationMs = dur)
        onPositionChanged?.invoke(pos)
        persist(pos)
    }

    /**
     * The player screen registers here to drive word highlighting.
     *
     * The current position is delivered immediately rather than at the next
     * tick: the ticker only runs while playing, so a screen attaching to a
     * paused player used to see nothing at all and painted its scrubber at
     * zero until playback started.
     */
    fun observePosition(block: ((Int) -> Unit)?) {
        onPositionChanged = block
        if (block != null) block(_now.value.positionMs)
    }

    // ---- Progress persistence -------------------------------------------
    //
    // Owned here, not by the player screen's view model. The view model is
    // cleared the moment that screen is popped, and playback carries on in the
    // mini player: everything listened to from the bar was written nowhere.

    /** Which 5-second bucket was last written, so a position is persisted
     *  roughly every five seconds rather than ten times a second. */
    private var lastSavedBucket = -1

    /** Resets the bucket when the loaded item changes, so the new item's first
     *  save is not skipped as a duplicate of the old one's last. */
    private fun resetPersistence(resumeMs: Int) {
        lastSavedBucket = resumeMs / SAVE_BUCKET_MS
    }

    /** For "clear progress": the next position is worth writing whatever
     *  bucket it lands in. */
    fun forgetSavedPosition() { lastSavedBucket = -1 }

    private const val SAVE_BUCKET_MS = 5000

    private fun persist(positionMs: Int) {
        val itemId = _now.value.itemId
        if (itemId <= 0) return
        val dur = _now.value.durationMs
        // A position of zero is never worth writing, and is usually a lie: a
        // controller that is still preparing, or one whose media items have
        // just been cleared, reports 0, and saving it wipes the real position
        // both locally and on the server. Clearing progress has its own path
        // through Repository.clearLocalProgress.
        if (dur <= 0 || positionMs <= 0) return

        val bucket = positionMs / SAVE_BUCKET_MS
        if (bucket == lastSavedBucket) return
        lastSavedBucket = bucket
        save(itemId, positionMs, dur)
    }

    /** Writes a position immediately, for the transitions the bucket misses. */
    private fun flush() {
        val c = controller ?: return
        val itemId = _now.value.itemId
        val dur = _now.value.durationMs
        val pos = c.currentPosition.toInt()
        if (itemId <= 0 || dur <= 0 || pos <= 0) return
        save(itemId, pos, dur)
    }

    private fun save(itemId: Int, positionMs: Int, durationMs: Int) {
        scope.launch {
            runCatching {
                io.blurrycontour.monoglot.data.Graph.repository.saveProgress(
                    itemId, positionMs,
                    completed = positionMs > durationMs - 5000,
                )
            }
        }
    }

    private var appContext: Context? = null

    /** Key for the sentence boundaries carried in the item's metadata. */
    const val EXTRA_SEGMENT_STARTS = "io.blurrycontour.monoglot.SEGMENT_STARTS"

    /** What the notification and lock screen call this episode. */
    private fun notificationTitle(title: String, publishedAt: String?): String {
        val date = Dates.label(Dates.parse(publishedAt))
        return if (date == "—") title.ifBlank { "Episode" } else date
    }

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
        publishedAt: String? = null,
        segmentStartsMs: IntArray = IntArray(0),
    ) {
        val c = controller ?: return
        val switching = c.currentMediaItem?.mediaId != itemId.toString()
        if (switching) {
            // The persistence guards belong to the item, not to the session.
            resetPersistence(resumeMs)
            val item = MediaItem.Builder()
                .setMediaId(itemId.toString())
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        // Sentence boundaries ride along with the item, so the
                        // service can offer sentence skip without holding a
                        // transcript, and cannot end up navigating one episode
                        // by another's boundaries.
                        .setExtras(Bundle().apply {
                            putIntArray(EXTRA_SEGMENT_STARTS, segmentStartsMs)
                        })
                        // The date, not the headline: every Klartext episode
                        // shares one title, and the notification is where you
                        // are least able to work out which one is playing.
                        .setTitle(notificationTitle(title, publishedAt))
                        .setArtist(source)
                        // Shown on the lock screen and in the notification.
                        .setArtworkUri(artworkUri(context))
                        .build()
                )
                .build()

            // The start position goes on setMediaItem rather than a seekTo
            // after prepare(). Across a MediaController the seek is a separate
            // asynchronous command that races the timeline becoming
            // available, and it was being dropped: the scrubber showed the
            // resumed position because this object published it, while the
            // player itself sat at zero and started there — then wrote zeros
            // over the saved position as it went.
            c.setMediaItem(item, resumeMs.toLong().coerceAtLeast(0L))
            c.prepare()
        }
        c.playbackParameters = PlaybackParameters(speed)
        _now.value = _now.value.copy(
            itemId = itemId, title = title, source = source,
            publishedAt = publishedAt ?: _now.value.publishedAt,
            durationMs = durationMs, speed = speed, isPlaying = c.isPlaying,
            // The seek above does not report itself anywhere: the ticker is
            // asleep while paused, so without this the scrubber sat at 0:00
            // until playback started, whatever the episode had been resumed at.
            positionMs = if (switching) resumeMs else _now.value.positionMs,
        )
        // Deliberately not syncPosition(): the seek above is asynchronous, and
        // a controller still applying it reports 0, which would put the
        // scrubber straight back where this is trying to move it away from.
        onPositionChanged?.invoke(_now.value.positionMs)
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
        val target = ms.coerceAtLeast(0)
        controller?.seekTo(target.toLong())
        // Reported from the requested target rather than read back: the ticker
        // is asleep while paused, and a controller mid-seek answers with the
        // position it is leaving, not the one it is going to.
        publishPosition(target)
    }

    fun skip(deltaMs: Int) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0).toInt()
        c.seekTo(target.toLong())
        publishPosition(target)
    }

    /** Announces a position this object asked for, as opposed to one read back
     *  from the controller. */
    private fun publishPosition(positionMs: Int) {
        _now.value = _now.value.copy(positionMs = positionMs)
        onPositionChanged?.invoke(positionMs)
        persist(positionMs)
    }

    fun setSpeed(speed: Float) {
        controller?.playbackParameters = PlaybackParameters(speed)
        _now.value = _now.value.copy(speed = speed)
    }

    /** Clears playback entirely, dismissing the mini player. */
    fun stop() {
        // Before the media items go: clearing them makes the controller report
        // position 0, so anything not written by now is gone. Closing the bar
        // must not be the one way to lose a listening session.
        flush()
        controller?.run { pause(); clearMediaItems() }
        _now.value = NowPlaying()
        lastSavedBucket = -1
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
                        publishedAt = bundle.item.publishedAt,
                        segmentStartsMs = bundle.segments.map { it.startMs }.toIntArray(),
                    )
                }
            }
        }
    }
}
