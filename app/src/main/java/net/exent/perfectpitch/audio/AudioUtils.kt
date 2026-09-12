package net.exent.perfectpitch.audio

import kotlin.math.log10
import kotlin.math.sqrt

object AudioUtils {

    fun rms(buffer: FloatArray): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0.0f
        for (sample in buffer) sum += sample * sample
        return sqrt(sum / buffer.size)
    }

    fun linearToDb(amplitude: Float): Float =
        if (amplitude <= 0f) -100f
        else 20f * log10(amplitude)
}
