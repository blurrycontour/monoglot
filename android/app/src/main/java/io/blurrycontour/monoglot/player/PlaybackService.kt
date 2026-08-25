package io.blurrycontour.monoglot.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import io.blurrycontour.monoglot.R
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
            // Holds a wifi lock as well as a wake lock while playing.
            //
            // Audio already buffered keeps playing with the screen off, so
            // playback looked fine — but a seek past the buffer opens a new
            // ranged request, and by then the radio may have dropped the wifi
            // association it no longer appeared to need. Against a homelab
            // server on the LAN that request cannot be served over mobile
            // data at all: it hung until it timed out, the load failed, and
            // the session went down with it. WAKE_LOCK is already declared in
            // the manifest for exactly this and was never used.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(recovery)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            // The default layout is previous / play-pause / next, and on a
            // single-item playlist the outer two collapse to one button that
            // restarts the episode — which is all the notification offered.
            // Skipping five seconds is what this app actually does, so those
            // are the buttons it gets, matching the in-app transport.
            .setCustomLayout(
                ImmutableList.of(
                    CommandButton.Builder()
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setIconResId(R.drawable.ic_notif_rewind)
                        .setDisplayName("Back 5 seconds")
                        .build(),
                    CommandButton.Builder()
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setIconResId(R.drawable.ic_notif_forward)
                        .setDisplayName("Forward 5 seconds")
                        .build(),
                )
            )
            .build()

        // The status-bar glyph was Media3's generic play icon, so a Monoglot
        // notification looked like it came from nothing in particular. The
        // app's own mark already existed for the reminder notifications.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_monoglot)
            }
        )
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
