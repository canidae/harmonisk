package com.perfectpitch.audio

import kotlin.math.sqrt

/**
 * Audio utility functions for signal processing.
 */
object AudioUtils {

    // Cached Hann window multipliers, keyed by buffer size.
    // Since buffer size is always ANALYSIS_WINDOW_SIZE (constant), this is effectively
    // a one-entry cache that avoids recomputing the cosine table on every call.
    private val hannWindowCache = HashMap<Int, FloatArray>()

    private fun getHannWindow(size: Int): FloatArray =
        hannWindowCache.getOrPut(size) {
            FloatArray(size) { i ->
                0.5f * (1f - kotlin.math.cos(2f * Math.PI.toFloat() * i / (size - 1)))
            }
        }

    /**
     * Apply a Hann window to [buffer], writing results into [output].
     * Both arrays must have the same size.
     * The window multipliers are cached after the first call for a given size.
     */
    fun applyHannWindow(buffer: FloatArray, output: FloatArray) {
        val window = getHannWindow(buffer.size)
        for (i in buffer.indices) {
            output[i] = buffer[i] * window[i]
        }
    }

    /**
     * Apply a Hann window and return a new array (convenience overload, allocates).
     */
    fun applyHannWindow(buffer: FloatArray): FloatArray {
        val out = FloatArray(buffer.size)
        applyHannWindow(buffer, out)
        return out
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
