package net.exent.harmonisk.audio

import kotlin.math.log2
import kotlin.math.roundToInt

internal object SubharmonicCorrector {

    private const val BINS_PER_OCTAVE = 60

    private const val SEARCH_RADIUS_BINS = 3

    private const val OCTAVE_PEAK_RATIO = 0.3f

    private const val THIRD_PEAK_RATIO = 0.05f

    private val DIVISORS = intArrayOf(2, 3)

    fun fundamentalBin(activation: FloatArray, peakBin: Int): Int {
        var bestBin = peakBin
        var bestValue = 0f
        for (divisor in DIVISORS) {
            val offset = (BINS_PER_OCTAVE * log2(divisor.toDouble())).roundToInt()
            val candidate = localPeakNear(activation, peakBin - offset) ?: continue
            val threshold = if (divisor == 2) OCTAVE_PEAK_RATIO else THIRD_PEAK_RATIO
            val value = activation[candidate]
            if (value >= activation[peakBin] * threshold && value > bestValue) {
                bestBin = candidate
                bestValue = value
            }
        }
        return bestBin
    }

    private fun localPeakNear(activation: FloatArray, center: Int): Int? {
        if (center < 1 || center > activation.size - 2) return null
        var best = -1
        var bestValue = 0f
        val start = maxOf(1, center - SEARCH_RADIUS_BINS)
        val end = minOf(activation.size - 2, center + SEARCH_RADIUS_BINS)
        for (i in start..end) {
            val value = activation[i]
            if (value > activation[i - 1] && value >= activation[i + 1] && value > bestValue) {
                best = i
                bestValue = value
            }
        }
        return if (best >= 0) best else null
    }
}
