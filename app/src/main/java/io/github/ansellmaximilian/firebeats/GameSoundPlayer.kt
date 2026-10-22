package io.github.ansellmaximilian.firebeats

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import kotlin.random.Random

internal class GameSoundPlayer(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(5)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadedSoundIds = Collections.synchronizedSet(mutableSetOf<Int>())
    private val hitSoundId: Int
    private val comboSoundId: Int
    private var comboGeneration = 0
    private var comboStreamId = 0

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedSoundIds += sampleId
            } else {
                Log.e("FireTV", "Game sound failed to load; id=$sampleId status=$status")
            }
        }
        hitSoundId = soundPool.load(context, R.raw.hit_impact_plate, 1)
        comboSoundId = soundPool.load(context, R.raw.combo_fire_ignite, 1)
    }

    fun playHit() {
        if (hitSoundId !in loadedSoundIds) return
        soundPool.play(
            hitSoundId,
            HIT_VOLUME,
            HIT_VOLUME,
            1,
            0,
            0.96f + Random.nextFloat() * 0.08f
        )
    }

    fun playComboTier() {
        if (comboSoundId !in loadedSoundIds) return

        comboGeneration += 1
        val generation = comboGeneration
        if (comboStreamId != 0) soundPool.stop(comboStreamId)
        val streamId = soundPool.play(
            comboSoundId,
            COMBO_VOLUME,
            COMBO_VOLUME,
            2,
            0,
            1f
        )
        if (streamId == 0) return
        comboStreamId = streamId

        for (step in 1..COMBO_FADE_STEPS) {
            mainHandler.postDelayed(
                {
                    if (generation != comboGeneration || streamId != comboStreamId) {
                        return@postDelayed
                    }
                    val volume = COMBO_VOLUME *
                        (1f - step.toFloat() / COMBO_FADE_STEPS.toFloat())
                    soundPool.setVolume(streamId, volume, volume)
                    if (step == COMBO_FADE_STEPS) {
                        soundPool.stop(streamId)
                        comboStreamId = 0
                    }
                },
                COMBO_FADE_START_MS + step * COMBO_FADE_STEP_MS
            )
        }
    }

    fun release() {
        comboGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        soundPool.release()
    }

    private companion object {
        const val HIT_VOLUME = 0.78f
        const val COMBO_VOLUME = 0.52f
        const val COMBO_FADE_START_MS = 2_200L
        const val COMBO_FADE_STEP_MS = 100L
        const val COMBO_FADE_STEPS = 8
    }
}
