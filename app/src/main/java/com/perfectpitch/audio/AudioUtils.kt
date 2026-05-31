package com.perfectpitch.audio

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

object AudioUtils {

    private val hannWindowCache = mutableMapOf<Int, FloatArray>()

    private fun getHannWindow(size: Int): FloatArray =
        hannWindowCache.getOrPut(size) {
            FloatArray(size) { i ->
                0.5f * (1f - cos(2f * Math.PI.toFloat() * i / (size - 1)))
            }
        }

    fun applyHannWindow(buffer: FloatArray, output: FloatArray) {
        val window = getHannWindow(buffer.size)
        for (i in buffer.indices) output[i] = buffer[i] * window[i]
    }

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
