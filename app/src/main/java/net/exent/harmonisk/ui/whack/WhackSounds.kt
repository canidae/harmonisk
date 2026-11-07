package net.exent.harmonisk.ui.whack

import android.content.Context
import androidx.annotation.RawRes
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.components.SoundPoolPlayer

enum class WhackSfx(@param:RawRes val resource: Int) {
    Thud(R.raw.sfx_whack_thud),
    Level(R.raw.sfx_whack_level)
}

class WhackSounds(context: Context) {
    private val player = SoundPoolPlayer(
        context,
        6,
        WhackEmoji.entries.flatMap { it.sounds.toList() } + WhackSfx.entries.map { it.resource }
    )

    fun play(@RawRes resource: Int, volume: Float) = player.play(resource, volume)

    fun play(emoji: WhackEmoji, volume: Float) = play(emoji.sounds.random(), volume)

    fun play(sfx: WhackSfx, volume: Float) = play(sfx.resource, volume)

    fun release() = player.release()
}
