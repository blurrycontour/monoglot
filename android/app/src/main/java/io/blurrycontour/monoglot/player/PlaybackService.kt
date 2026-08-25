package io.blurrycontour.monoglot.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.blurrycontour.monoglot.MainActivity

/**
 * Background audio. The user listens while running and lifting, so playback
 * must survive the screen locking; a MediaSessionService is what gives us
 * lock-screen controls and headphone-button handling.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var retries = 0

    /**
     * Recovery from a failed load.
     *
     * Nothing handled player errors at all, so any failure was terminal:
     * playback stopped, the session went idle and the service dropped its
     * notification, which is exactly what a seek looked like from the
     * notification's own progress bar. Dragging outside the buffered region
     * makes ExoPlayer open a fresh ranged request against the homelab server,
     * and a single failed request — a dropped wifi association, a server that
     * took too long — took the whole session down with it, silently.
     *
     * prepare() re-attempts the load from the current position, so a recovered
     * error costs a moment of buffering rather than the session. Bounded,
     * because retrying a genuinely missing file forever would spin.
     */
    private val recovery = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val player = mediaSession?.player ?: return
            if (retries >= MAX_RETRIES) return
            retries++
            // Position survives prepare(), so this resumes where it failed
            // rather than restarting the episode.
            player.prepare()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // A successful load clears the budget for the next failure.
            if (playbackState == Player.STATE_READY) retries = 0
        }
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Speech: pausing on a transient duck is less jarring than ducking.
            .setHandleAudioBecomingNoisy(true)
            // These two are what put rewind and forward buttons in the
            // notification at all: the default provider builds its layout from
            // the player's available commands, and SEEK_BACK / SEEK_FORWARD
            // are only available once an increment is declared. Without them
            // the notification offered a single previous-track button, which
            // on a one-item playlist does nothing useful.
            //
            // Five seconds, to match the in-app transport.
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(5_000)
            .build()

        player.addListener(recovery)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Swiping the app away while paused should not leave a dead
        // notification behind; while playing, keep going.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    private companion object {
        /** Enough to ride out a blip, few enough that a missing file gives up. */
        const val MAX_RETRIES = 3
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
