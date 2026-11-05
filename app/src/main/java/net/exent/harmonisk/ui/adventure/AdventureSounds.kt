package net.exent.harmonisk.ui.adventure

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import net.exent.harmonisk.R

enum class AdventureSound(@RawRes val resource: Int) {
    Step(R.raw.sfx_step),
    Lever(R.raw.sfx_lever),
    Collect(R.raw.sfx_collect),
    Bonus(R.raw.sfx_bonus),
    Tick(R.raw.sfx_tick),
    Pouch(R.raw.sfx_pouch),
    River(R.raw.ambience_river),
    Wind(R.raw.ambience_wind),
    Birds(R.raw.ambience_birds),
    Crickets(R.raw.ambience_crickets)
}

class AdventureSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val samples = AdventureSound.entries.associateWith { pool.load(context, it.resource, 1) }

    fun play(sound: AdventureSound, volume: Float) {
        samples[sound]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }

    fun loop(sound: AdventureSound, volume: Float): Int =
        samples[sound]?.let { pool.play(it, volume, volume, 0, -1, 1f) } ?: 0

    fun stop(streamId: Int) {
        if (streamId != 0) pool.stop(streamId)
    }

    fun release() = pool.release()
}
