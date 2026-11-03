package net.exent.harmonisk.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class YinPitchDetector(
    private val sampleRate: Int = PitchResampler.TARGET_RATE,
    private val minFrequency: Float = 35f,
    private val maxFrequency: Float = 1500f,
    private val threshold: Float = 0.1f
) {
    private val tauMin = max(2, (sampleRate / maxFrequency).toInt())
    private val tauMax = max(tauMin + 1, (sampleRate / minFrequency).toInt())
    private val cmnd = FloatArray(tauMax + 2)

    fun detect(samples: FloatArray): PitchDetectionResult {
        val limitTau = min(tauMax, samples.size / 2)
        if (limitTau <= tauMin) return PitchDetectionResult.NONE

        var cumulative = 0.0
        for (tau in 1..limitTau) {
            var sum = 0.0
            val count = samples.size - tau
            var i = 0
            while (i < count) {
                val difference = (samples[i] - samples[i + tau]).toDouble()
                sum += difference * difference
                i++
            }
            cumulative += sum
            cmnd[tau] = if (cumulative > 1e-12) {
                (sum * tau / cumulative).toFloat()
            } else {
                1f
            }
        }

        var tau = tauMin
        while (tau < limitTau) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 < limitTau && cmnd[tau + 1] < cmnd[tau]) tau++
                val previous = cmnd[tau - 1]
                val current = cmnd[tau]
                val next = cmnd[tau + 1]
                val denominator = previous - 2f * current + next
                val shift = if (abs(denominator) > 1e-9f) {
                    0.5f * (previous - next) / denominator
                } else {
                    0f
                }
                val frequency = sampleRate / (tau + shift)
                if (frequency < minFrequency || frequency > maxFrequency) {
                    return PitchDetectionResult.NONE
                }
                return PitchDetectionResult(frequency, (1f - current).coerceIn(0f, 1f))
            }
            tau++
        }
        return PitchDetectionResult.NONE
    }
}
