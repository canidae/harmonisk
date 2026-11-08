package net.exent.harmonisk.ui.whack

import net.exent.harmonisk.audio.SynthesizedPlayer
import kotlin.math.PI
import kotlin.math.sin

class WhistlePlayer : SynthesizedPlayer(1.2f, 0.55f) {

    override fun sample(time: Float): Float {
        val attack = (time / 0.03f).coerceAtMost(1f)
        val release = ((1.2f - time) / 0.25f).coerceIn(0f, 1f)
        val envelope = attack * release
        val frequency = 2350f + sin(2.0 * PI * 26.0 * time).toFloat() * 90f
        val phase = 2.0 * PI * frequency * time
        val tremolo = 0.86f + 0.14f * sin(2.0 * PI * 8.0 * time).toFloat()
        val tone = (
            sin(phase) * 0.78 +
                sin(phase * 2.0) * 0.16 +
                sin(phase * 3.0) * 0.06
            ).toFloat()
        return tone * envelope * tremolo * 0.8f
    }
}
