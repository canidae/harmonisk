package net.exent.harmonisk.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

internal object SubharmonicCorrector {

    private const val HARMONICS = 6

    private const val AMPLITUDE_FLOOR = 1e-3

    private const val SCORE_MARGIN = 0.5

    private const val ODD_HARMONIC_RATIO = 0.01

    private const val LOWEST_DIVISOR = 2

    private const val HIGHEST_DIVISOR = 3

    private val window = DoubleArray(PitchResampler.TARGET_SAMPLES) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / (PitchResampler.TARGET_SAMPLES - 1))
    }

    private val windowSum = window.sum()

    fun fundamentalFrequency(
        samples: FloatArray,
        frequency: Float,
        lowestFrequency: Float
    ): Float {
        if (frequency <= 0f) return frequency
        val rawScore = score(samples, frequency)
        val rawAmplitude = amplitude(samples, frequency)
        var bestFrequency = frequency
        var bestScore = rawScore
        for (divisor in LOWEST_DIVISOR..HIGHEST_DIVISOR) {
            val candidate = frequency / divisor
            if (candidate < lowestFrequency) continue
            val oddHarmonic = if (divisor == 2) candidate * 3 else candidate * 5
            if (amplitude(samples, oddHarmonic) < rawAmplitude * ODD_HARMONIC_RATIO) continue
            val candidateScore = score(samples, candidate)
            if (candidateScore > bestScore) {
                bestFrequency = candidate
                bestScore = candidateScore
            }
        }
        return if (bestScore > rawScore + SCORE_MARGIN) bestFrequency else frequency
    }

    private fun score(samples: FloatArray, frequency: Float): Double {
        var score = 0.0
        for (harmonic in 1..HARMONICS) {
            score += ln(amplitude(samples, frequency * harmonic) + AMPLITUDE_FLOOR)
        }
        return score
    }

    private fun amplitude(samples: FloatArray, frequency: Float): Double {
        if (frequency <= 0f || frequency >= PitchResampler.TARGET_RATE / 2f) return 0.0
        val coefficient = 2.0 * cos(2.0 * PI * frequency / PitchResampler.TARGET_RATE)
        var previous = 0.0
        var previousPrevious = 0.0
        for (i in samples.indices) {
            val current = samples[i] * window[i] + coefficient * previous - previousPrevious
            previousPrevious = previous
            previous = current
        }
        val power = previous * previous + previousPrevious * previousPrevious -
            coefficient * previous * previousPrevious
        return sqrt(max(power, 0.0)) / windowSum
    }
}
