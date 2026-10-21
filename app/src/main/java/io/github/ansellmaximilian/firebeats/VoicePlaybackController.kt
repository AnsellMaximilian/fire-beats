package io.github.ansellmaximilian.firebeats

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

internal enum class VoicePlaybackStatus {
    UNAVAILABLE,
    READY,
    PLAYING,
    PAUSED,
    FINISHED
}

internal class VoicePlaybackController(context: Context) {
    private var onPlay: () -> Unit = {}
    private var onPause: () -> Unit = {}

    private val session = MediaSession(context, "FireBeatsPlayback").apply {
        setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.i(TAG, "MediaSession play requested")
                    onPlay.invoke()
                }

                override fun onPause() {
                    Log.i(TAG, "MediaSession pause requested")
                    onPause.invoke()
                }

                override fun onStop() {
                    Log.i(TAG, "MediaSession stop treated as pause")
                    onPause.invoke()
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    fun setCallbacks(onPlay: () -> Unit, onPause: () -> Unit) {
        this.onPlay = onPlay
        this.onPause = onPause
    }

    fun update(
        track: BeatTrack?,
        positionSeconds: Float,
        status: VoicePlaybackStatus
    ) {
        session.isActive = status != VoicePlaybackStatus.UNAVAILABLE
        session.setMetadata(
            track?.let {
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, it.name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Fire Beats")
                    .putLong(
                        MediaMetadata.METADATA_KEY_DURATION,
                        (it.duration.coerceAtLeast(0f) * 1_000).toLong()
                    )
                    .build()
            }
        )

        val playbackState = when (status) {
            VoicePlaybackStatus.UNAVAILABLE -> PlaybackState.STATE_NONE
            VoicePlaybackStatus.READY -> PlaybackState.STATE_PAUSED
            VoicePlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            VoicePlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            VoicePlaybackStatus.FINISHED -> PlaybackState.STATE_STOPPED
        }
        val actions = when (status) {
            VoicePlaybackStatus.READY -> PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE
            VoicePlaybackStatus.PAUSED -> PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PLAY_PAUSE
            VoicePlaybackStatus.PLAYING -> PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE
            VoicePlaybackStatus.UNAVAILABLE,
            VoicePlaybackStatus.FINISHED -> 0L
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    playbackState,
                    (positionSeconds.coerceAtLeast(0f) * 1_000).toLong(),
                    if (status == VoicePlaybackStatus.PLAYING) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private companion object {
        const val TAG = "FireTV"
    }
}
