package net.exent.harmonisk.ui.whack

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import net.exent.harmonisk.R

enum class WhackSfx(@RawRes val resource: Int) {
    Thud(R.raw.sfx_whack_thud),
    Level(R.raw.sfx_whack_level)
}

class WhackSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val samples = (WhackEmoji.entries.flatMap { it.sounds.toList() } + WhackSfx.entries.map { it.resource })
        .associateWith { pool.load(context, it, 1) }

    fun play(@RawRes resource: Int, volume: Float) {
        samples[resource]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }

    fun play(emoji: WhackEmoji, volume: Float) = play(emoji.sounds.random(), volume)

    fun play(sfx: WhackSfx, volume: Float) = play(sfx.resource, volume)

    fun release() = pool.release()
}
