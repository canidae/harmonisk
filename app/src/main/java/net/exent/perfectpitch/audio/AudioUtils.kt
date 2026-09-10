package net.exent.perfectpitch.audio

import kotlin.math.log10
import kotlin.math.sqrt

object AudioUtils {

    fun rms(buffer: FloatArray): Float = rms(buffer, buffer.size)

    /** RMS of the first [length] samples, for a sliding window that is not filled yet. */
    fun rms(buffer: FloatArray, length: Int): Float {
        val count = length.coerceIn(0, buffer.size)
        if (count == 0) return 0f
        var sum = 0.0f
        for (i in 0 until count) {
            val sample = buffer[i]
            sum += sample * sample
        }
        return sqrt(sum / count)
    }

    fun linearToDb(amplitude: Float): Float =
        if (amplitude <= 0f) -100f
        else 20f * log10(amplitude)
}
