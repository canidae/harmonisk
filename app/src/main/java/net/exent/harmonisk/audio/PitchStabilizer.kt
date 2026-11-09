package net.exent.harmonisk.audio

import kotlin.math.abs
import kotlin.math.exp

class PitchStabilizer {

    companion object {
        const val MEDIAN_WINDOW = 7

        const val TIME_CONSTANT_S = 0.22

        const val OUTPUT_DEADBAND_SEMITONES = 0.010

        private const val CONFIDENCE_FLOOR = 0.55f

        private const val CONFIDENCE_CEILING = 0.92f

        private const val MIN_WEIGHT = 0.25

        private const val SNAP_SEMITONES = 0.40

        private const val SNAP_TIME_S = 0.055

        private const val FINE_SNAP_SEMITONES = 0.09

        private const val FINE_SNAP_TIME_S = 0.12

        private const val MIN_DT_S = 0.004
        private const val MAX_DT_S = 0.12
    }

    private val window = DoubleArray(MEDIAN_WINDOW)
    private val scratch = DoubleArray(MEDIAN_WINDOW)
    private var windowSize = 0
    private var windowNext = 0

    private var smoothed = 0.0
    private var shown = 0.0
    private var started = false

    private var deviationSeconds = 0.0
    private var deviationSign = 0

    fun accept(semitones: Double, confidence: Float, dtSeconds: Double): Double {
        push(semitones)
        val median = median()

        if (!started) {
            smoothed = median
            shown = median
            started = true
            return shown
        }

        val dt = dtSeconds.coerceIn(MIN_DT_S, MAX_DT_S)
        val delta = median - smoothed
        val magnitude = abs(delta)
        val sign = if (delta >= 0) 1 else -1
        if (magnitude > FINE_SNAP_SEMITONES && sign == deviationSign) {
            deviationSeconds += dt
        } else {
            deviationSeconds = 0.0
            deviationSign = sign
        }

        if (deviationSeconds >= if (magnitude > SNAP_SEMITONES) SNAP_TIME_S else FINE_SNAP_TIME_S) {
            smoothed = median
            shown = median
            deviationSeconds = 0.0
            return shown
        }

        val alpha = (1.0 - exp(-dt / TIME_CONSTANT_S)) * confidenceWeight(confidence)
        smoothed += alpha * delta
        if (abs(smoothed - shown) >= OUTPUT_DEADBAND_SEMITONES) shown = smoothed
        return shown
    }

    fun reset() {
        windowSize = 0
        windowNext = 0
        smoothed = 0.0
        shown = 0.0
        started = false
        deviationSeconds = 0.0
        deviationSign = 0
    }

    private fun push(value: Double) {
        window[windowNext] = value
        windowNext = (windowNext + 1) % MEDIAN_WINDOW
        if (windowSize < MEDIAN_WINDOW) windowSize++
    }

    private fun median(): Double {
        System.arraycopy(window, 0, scratch, 0, windowSize)
        for (i in 1 until windowSize) {
            val value = scratch[i]
            var j = i - 1
            while (j >= 0 && scratch[j] > value) {
                scratch[j + 1] = scratch[j]
                j--
            }
            scratch[j + 1] = value
        }
        return scratch[windowSize / 2]
    }

    private fun confidenceWeight(confidence: Float): Double {
        val span = (CONFIDENCE_CEILING - CONFIDENCE_FLOOR).toDouble()
        val t = ((confidence - CONFIDENCE_FLOOR) / span).coerceIn(0.0, 1.0)
        return MIN_WEIGHT + (1.0 - MIN_WEIGHT) * t
    }
}
