package io.blurrycontour.monoglot.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
            .build()

        player.addListener(recovery)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(Callback())
            .setCustomLayout(customLayout())
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

    /**
     * The buttons either side of play.
     *
     * They have to be custom session commands rather than COMMAND_SEEK_BACK
     * and COMMAND_SEEK_FORWARD, which is what they were first written as and
     * why nothing appeared. Android's media controls do not lay themselves out
     * from the player's commands: the slots are filled from the session's
     * custom actions, and the standard rewind and fast-forward actions are not
     * among the ones it draws. The default previous/next pair is, which on a
     * one-item playlist collapses into the single restart button that was all
     * the notification ever showed.
     */
    private fun customLayout(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_PREV_SENTENCE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_notif_prev_sentence)
            .setDisplayName("Previous sentence")
            .build(),
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_BACK, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_notif_rewind)
            .setDisplayName("Back 5 seconds")
            .build(),
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_FORWARD, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_notif_forward)
            .setDisplayName("Forward 5 seconds")
            .build(),
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_NEXT_SENTENCE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_notif_next_sentence)
            .setDisplayName("Next sentence")
            .build(),
    )

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Every controller has to be told these commands exist, the
            // notification's included, or its buttons arrive disabled.
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_BACK, Bundle.EMPTY))
                .add(SessionCommand(CMD_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(CMD_PREV_SENTENCE, Bundle.EMPTY))
                .add(SessionCommand(CMD_NEXT_SENTENCE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                CMD_BACK -> player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0))
                CMD_FORWARD -> player.seekTo(player.currentPosition + SKIP_MS)
                CMD_PREV_SENTENCE -> seekSentence(player, back = true)
                CMD_NEXT_SENTENCE -> seekSentence(player, back = false)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * Sentence skip, from the boundaries the app put on the media item.
     *
     * An episode with no transcript — or one prepared before this existed —
     * carries no boundaries, and the button does nothing rather than guessing
     * at a jump.
     */
    private fun seekSentence(player: Player, back: Boolean) {
        val starts = player.currentMediaItem
            ?.mediaMetadata?.extras
            ?.getIntArray(PlaybackHolder.EXTRA_SEGMENT_STARTS)
            ?: return
        val position = player.currentPosition.toInt()
        val target = if (back) SegmentNav.previous(starts, position)
                     else SegmentNav.next(starts, position)
        if (target != SegmentNav.NONE) player.seekTo(target.toLong())
    }

    private companion object {
        /** Enough to ride out a blip, few enough that a missing file gives up. */
        const val MAX_RETRIES = 3

        /** Same five seconds as the in-app transport. */
        const val SKIP_MS = 5_000L

        const val CMD_BACK = "io.blurrycontour.monoglot.BACK_5"
        const val CMD_FORWARD = "io.blurrycontour.monoglot.FORWARD_5"
        const val CMD_PREV_SENTENCE = "io.blurrycontour.monoglot.PREV_SENTENCE"
        const val CMD_NEXT_SENTENCE = "io.blurrycontour.monoglot.NEXT_SENTENCE"
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
