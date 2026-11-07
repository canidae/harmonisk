package net.exent.harmonisk.ui.adventure

import android.content.Context
import androidx.annotation.RawRes
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.components.SoundPoolPlayer

enum class AdventureSound(@param:RawRes val resource: Int) {
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
    private val player = SoundPoolPlayer(context, 8, AdventureSound.entries.map { it.resource })

    fun play(sound: AdventureSound, volume: Float) = player.play(sound.resource, volume)

    fun loop(sound: AdventureSound, volume: Float): Int = player.loop(sound.resource, volume)

    fun stop(streamId: Int) = player.stop(streamId)

    fun release() = player.release()
}
