package io.github.ansellmaximilian.firebeats

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

internal class BeatAudioPlayer {
    private var player: MediaPlayer? = null
    private var ready = false

    fun load(
        url: String,
        onReady: () -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ) {
        reset()
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener {
                ready = true
                Log.i("FireTV", "Beat track audio prepared")
                onReady()
            }
            setOnCompletionListener {
                Log.i("FireTV", "Beat track audio completed")
                onCompleted()
            }
            setOnErrorListener { _, what, extra ->
                ready = false
                val message = "Audio playback error ($what/$extra)"
                Log.e("FireTV", message)
                onError(message)
                true
            }
            setDataSource(url)
            prepareAsync()
        }
        player = mediaPlayer
    }

    fun playFromStart(): Boolean {
        val mediaPlayer = player ?: return false
        if (!ready) return false
        mediaPlayer.seekTo(0)
        mediaPlayer.start()
        return true
    }

    fun pause(): Boolean {
        val mediaPlayer = player ?: return false
        if (!ready || !mediaPlayer.isPlaying) return false
        mediaPlayer.pause()
        return true
    }

    fun resume(): Boolean {
        val mediaPlayer = player ?: return false
        if (!ready || mediaPlayer.isPlaying) return false
        mediaPlayer.start()
        return true
    }

    fun positionSeconds(): Float = if (ready) {
        (player?.currentPosition ?: 0) / 1_000f
    } else {
        0f
    }

    fun isPlaying(): Boolean = ready && player?.isPlaying == true

    fun reset() {
        ready = false
        player?.runCatching {
            stop()
        }
        player?.release()
        player = null
    }

    fun release() = reset()
}
