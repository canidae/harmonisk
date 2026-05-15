package com.perfectpitch.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * YIN pitch detection algorithm implementation.
 *
 * Based on: "YIN, a fundamental frequency estimator for speech and music"
 * by Alain de Cheveigné and Hideki Kawahara (2002).
 */
@Singleton
class PitchDetector @Inject constructor() {

    companion object {
        const val DEFAULT_THRESHOLD = 0.15f
        const val DEFAULT_SAMPLE_RATE = 44100
    }

    /**
     * Detect the fundamental frequency of the audio buffer.
     *
     * @param buffer Audio samples (float, -1.0 to 1.0)
     * @param sampleRate Sample rate in Hz
     * @param threshold YIN threshold (lower = stricter, typically 0.10-0.20)
     * @return Detected frequency in Hz, or -1f if no pitch detected
     */
    fun detectPitch(
        buffer: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        threshold: Float = DEFAULT_THRESHOLD
    ): Float {
        if (buffer.size < 4) return -1f
        val halfLen = buffer.size / 2

        // Step 1 & 2: Difference function + Cumulative mean normalized difference
        val yinBuffer = FloatArray(halfLen)
        cumulativeMeanNormalizedDifference(buffer, yinBuffer)

        // Step 3: Absolute threshold - find first valley below threshold
        var tauEstimate = -1
        var tau = 2
        while (tau < halfLen) {
            if (yinBuffer[tau] < threshold) {
                // Walk to the local minimum
                while (tau + 1 < halfLen && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    tau++
                }
                tauEstimate = tau
                break
            }
            tau++
        }

        if (tauEstimate == -1) return -1f

        // Step 4: Parabolic interpolation for sub-sample accuracy
        val betterTau = parabolicInterpolation(yinBuffer, tauEstimate)

        return if (betterTau > 0f) sampleRate / betterTau else -1f
    }

    /**
     * Compute cumulative mean normalized difference function (CMND).
     * Combines YIN steps 1 and 2 for efficiency.
     */
    private fun cumulativeMeanNormalizedDifference(
        buffer: FloatArray,
        yinBuffer: FloatArray
    ) {
        val halfLen = yinBuffer.size
        yinBuffer[0] = 1.0f
        var runningSum = 0.0f

        for (tau in 1 until halfLen) {
            // Difference function (step 1)
            var diff = 0.0f
            for (i in 0 until halfLen) {
                val delta = buffer[i] - buffer[i + tau]
                diff += delta * delta
            }

            // Cumulative mean normalized difference (step 2)
            runningSum += diff
            yinBuffer[tau] = if (runningSum != 0f) {
                diff * tau / runningSum
            } else {
                1.0f
            }
        }
    }

    /**
     * Parabolic interpolation to refine the tau estimate.
     * Fits a parabola through (tau-1, tau, tau+1) and finds the minimum.
     */
    private fun parabolicInterpolation(yinBuffer: FloatArray, tau: Int): Float {
        if (tau <= 0 || tau >= yinBuffer.size - 1) {
            return tau.toFloat()
        }

        val s0 = yinBuffer[tau - 1]
        val s1 = yinBuffer[tau]
        val s2 = yinBuffer[tau + 1]

        val denominator = 2.0f * (2.0f * s1 - s2 - s0)
        return if (abs(denominator) < 1e-10f) {
            tau.toFloat()
        } else {
            tau + (s2 - s0) / denominator
        }
    }

    /**
     * Calculate the RMS (Root Mean Square) of the buffer.
     * Used to detect silence.
     */
    fun rms(buffer: FloatArray): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0.0f
        for (sample in buffer) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / buffer.size)
    }

    /**
     * Convert RMS to decibels.
     */
    fun rmsToDb(rms: Float): Float {
        return if (rms <= 0f) -100f
        else 20f * kotlin.math.log10(rms)
    }
}
