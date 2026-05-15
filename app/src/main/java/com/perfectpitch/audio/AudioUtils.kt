package com.perfectpitch.audio

import kotlin.math.sqrt

/**
 * Audio utility functions for signal processing.
 */
object AudioUtils {

    /**
     * Apply a Hann window to the buffer to reduce spectral leakage.
     */
    fun applyHannWindow(buffer: FloatArray): FloatArray {
        val windowed = FloatArray(buffer.size)
        val n = buffer.size
        for (i in buffer.indices) {
            val multiplier = 0.5f * (1f - kotlin.math.cos(2f * Math.PI.toFloat() * i / (n - 1)))
            windowed[i] = buffer[i] * multiplier
        }
        return windowed
    }

    /**
     * Calculate RMS (Root Mean Square) of a signal buffer.
     */
    fun rms(buffer: FloatArray): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0.0f
        for (sample in buffer) {
            sum += sample * sample
        }
        return sqrt(sum / buffer.size)
    }

    /**
     * Convert linear amplitude to decibels.
     */
    fun linearToDb(amplitude: Float): Float {
        return if (amplitude <= 0f) -100f
        else 20f * kotlin.math.log10(amplitude)
    }

    /**
     * Convert decibels to linear amplitude.
     */
    fun dbToLinear(db: Float): Float {
        return Math.pow(10.0, db.toDouble() / 20.0).toFloat()
    }
}
