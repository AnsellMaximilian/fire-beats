package com.example.firelab

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

internal class PopSoundPlayer(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    @Volatile
    private var loadedSoundId = 0

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSoundId = sampleId
        }
        runCatching {
            val soundFile = File(context.cacheDir, "firelab-bubble-pop-v1.wav")
            if (!soundFile.exists() || soundFile.length() == 0L) {
                writeBubblePop(soundFile)
            }
            soundPool.load(soundFile.absolutePath, 1)
        }.onFailure { error ->
            Log.e("FireTV", "Could not prepare bubble pop sound", error)
        }
    }

    fun play() {
        val soundId = loadedSoundId
        if (soundId == 0) return
        soundPool.play(
            soundId,
            0.8f,
            0.8f,
            1,
            0,
            0.94f + Random.nextFloat() * 0.12f
        )
    }

    fun release() {
        soundPool.release()
    }

    private fun writeBubblePop(file: File) {
        val sampleRate = 22_050
        val durationSeconds = 0.13
        val sampleCount = (sampleRate * durationSeconds).toInt()
        val pcmSize = sampleCount * Short.SIZE_BYTES
        val wave = ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN)

        wave.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        wave.putInt(36 + pcmSize)
        wave.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        wave.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        wave.putInt(16)
        wave.putShort(1.toShort())
        wave.putShort(1.toShort())
        wave.putInt(sampleRate)
        wave.putInt(sampleRate * Short.SIZE_BYTES)
        wave.putShort(Short.SIZE_BYTES.toShort())
        wave.putShort(16.toShort())
        wave.put("data".toByteArray(StandardCharsets.US_ASCII))
        wave.putInt(pcmSize)

        for (sampleIndex in 0 until sampleCount) {
            val time = sampleIndex.toDouble() / sampleRate
            val progress = sampleIndex.toDouble() / (sampleCount - 1)
            val attack = (progress / 0.025).coerceAtMost(1.0)
            val decay = 1.0 - progress
            val envelope = attack * decay * decay * decay
            val startFrequency = 1_050.0
            val endFrequency = 280.0
            val frequencyDrop = startFrequency - endFrequency
            val phase = 2.0 * PI * (
                startFrequency * time -
                    frequencyDrop * time * time / (2.0 * durationSeconds)
                )
            val tone = sin(phase) + 0.22 * sin(phase * 0.52)
            val sample = (tone * envelope * Short.MAX_VALUE * 0.62)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            wave.putShort(sample.toShort())
        }

        FileOutputStream(file).use { output ->
            output.write(wave.array())
        }
    }
}
