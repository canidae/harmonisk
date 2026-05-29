package com.perfectpitch.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// YIN pitch detection with FFT-based O(n log n) autocorrelation.
data class PitchDetectionResult(
    val frequency: Float,
    val confidence: Float
) {
    companion object {
        val NONE = PitchDetectionResult(-1f, 0f)
    }
}

class PitchDetector {

    companion object {
        const val DEFAULT_THRESHOLD = 0.15f
        const val DEFAULT_SAMPLE_RATE = 44100
    }

    // Pre-allocated FFT work arrays, lazily sized on first call.
    private var fftWorkSize = 0
    private var workAre = DoubleArray(0)
    private var workAim = DoubleArray(0)
    private var workBre = DoubleArray(0)
    private var workBim = DoubleArray(0)
    private var workCre = DoubleArray(0)
    private var workCim = DoubleArray(0)
    private var workEnergyPrefix = DoubleArray(0)

    private fun ensureWorkArrays(bufferSize: Int) {
        val required = nextPowerOf2(2 * bufferSize)
        if (required != fftWorkSize) {
            fftWorkSize = required
            workAre = DoubleArray(required)
            workAim = DoubleArray(required)
            workBre = DoubleArray(required)
            workBim = DoubleArray(required)
            workCre = DoubleArray(required)
            workCim = DoubleArray(required)
            workEnergyPrefix = DoubleArray(bufferSize + 1)
        }
    }

    /**
     * Detect the fundamental frequency of the audio buffer.
     * @param threshold YIN threshold — lower is stricter (0.10–0.20 typical)
     * @param minFreq / maxFreq detectable frequency range in Hz
     */
    fun detectPitch(
        buffer: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        threshold: Float = DEFAULT_THRESHOLD,
        minFreq: Float = 65f,
        maxFreq: Float = 2100f
    ): PitchDetectionResult {
        if (buffer.size < 4) return PitchDetectionResult.NONE
        val halfLen = buffer.size / 2

        val tauMin = (sampleRate / maxFreq).toInt().coerceAtLeast(2)
        val tauMax = (sampleRate / minFreq).toInt().coerceAtMost(halfLen - 1)
        if (tauMin >= tauMax) return PitchDetectionResult.NONE

        val yinBuffer = FloatArray(halfLen)
        cumulativeMeanNormalizedDifference(buffer, yinBuffer)

        // Find first valley below threshold, then descend to its local minimum.
        var tauEstimate = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (yinBuffer[tau] < threshold) {
                while (tau + 1 <= tauMax && yinBuffer[tau + 1] < yinBuffer[tau]) tau++
                tauEstimate = tau
                break
            }
            tau++
        }

        if (tauEstimate == -1) return PitchDetectionResult.NONE

        // Octave correction: when the first valley below threshold is at 2τ₀ or 4τ₀
        // (because d'(τ₀) was marginally above threshold), step back to the shorter period.
        tauEstimate = correctOctaveDown(yinBuffer, tauEstimate, tauMin, threshold)

        val betterTau = parabolicInterpolation(yinBuffer, tauEstimate)
        val frequency = if (betterTau > 0f) sampleRate / betterTau else -1f
        if (frequency <= 0f) return PitchDetectionResult.NONE

        val confidence = (1f - yinBuffer[tauEstimate]).coerceIn(0f, 1f)
        return PitchDetectionResult(frequency, confidence)
    }

    /**
     * Correct "octave too low" errors by iteratively preferring the half-period if it
     * also has a local dip within a slightly loose tolerance.
     *
     * Safety: since the first-valley scan works left-to-right (smallest τ first), any τ/2
     * that was strictly below threshold would have been found *before* the current tauEstimate.
     * Therefore d'(τ/2) ≥ threshold whenever this function is called. We only accept τ/2
     * if it falls in the narrow gray zone [threshold, threshold + 0.05], i.e., it was a
     * real near-miss due to threshold strictness — not a spurious harmonic dip.
     */
    private fun correctOctaveDown(
        yinBuffer: FloatArray,
        tauEstimate: Int,
        tauMin: Int,
        threshold: Float
    ): Int {
        var tau = tauEstimate
        val looseTolerance = threshold + 0.05f
        for (i in 0 until 2) {  // up to 2 corrections: handles ×4 period (2 octaves down)
            val half = tau / 2
            if (half < tauMin) break
            val (localTau, localVal) = localMinimumNear(yinBuffer, half, tauMin)
            if (localVal < looseTolerance) {
                tau = localTau
            } else {
                break
            }
        }
        return tau
    }

    /** Find the local minimum within ±12.5% of [center], clamped to [tauMin, size-1]. */
    private fun localMinimumNear(yinBuffer: FloatArray, center: Int, tauMin: Int): Pair<Int, Float> {
        val window = maxOf(1, center / 8)
        val start = maxOf(tauMin, center - window)
        val end = minOf(yinBuffer.size - 1, center + window)
        var bestTau = center.coerceIn(start, end)
        var bestVal = yinBuffer[bestTau]
        for (t in start..end) {
            if (yinBuffer[t] < bestVal) {
                bestVal = yinBuffer[t]
                bestTau = t
            }
        }
        return Pair(bestTau, bestVal)
    }

    private fun cumulativeMeanNormalizedDifference(
        buffer: FloatArray,
        yinBuffer: FloatArray
    ) {
        val halfLen = yinBuffer.size
        val n = buffer.size

        ensureWorkArrays(n)
        val fftSize = fftWorkSize

        // Signal a: first half (zero-padded)
        workAre.fill(0.0)
        workAim.fill(0.0)
        for (i in 0 until halfLen) workAre[i] = buffer[i].toDouble()

        // Signal b: full buffer (zero-padded)
        workBre.fill(0.0)
        workBim.fill(0.0)
        for (i in 0 until n) workBre[i] = buffer[i].toDouble()

        fft(workAre, workAim, forward = true)
        fft(workBre, workBim, forward = true)

        // Cross-power spectrum: conj(A) · B
        for (i in 0 until fftSize) {
            workCre[i] = workAre[i] * workBre[i] + workAim[i] * workBim[i]
            workCim[i] = workAre[i] * workBim[i] - workAim[i] * workBre[i]
        }

        fft(workCre, workCim, forward = false)

        workEnergyPrefix[0] = 0.0
        for (i in 0 until n) {
            workEnergyPrefix[i + 1] = workEnergyPrefix[i] + buffer[i].toDouble() * buffer[i].toDouble()
        }

        yinBuffer[0] = 1.0f
        var runningSum = 0.0

        // energy0 is constant across all tau — compute once outside the loop.
        val energy0 = workEnergyPrefix[halfLen]
        for (tau in 1 until halfLen) {
            val energyTau = workEnergyPrefix[halfLen + tau] - workEnergyPrefix[tau]
            // Clamp to ≥ 0: in exact math this is always ≥ 0 (Cauchy-Schwarz), but
            // floating-point cross-correlation can produce tiny negative values.
            val diff = (energy0 + energyTau - 2.0 * workCre[tau]).coerceAtLeast(0.0)

            runningSum += diff
            yinBuffer[tau] = if (runningSum != 0.0) (diff * tau / runningSum).toFloat() else 1.0f
        }
    }

    // In-place radix-2 Cooley-Tukey FFT.
    private fun fft(re: DoubleArray, im: DoubleArray, forward: Boolean) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var temp = re[i]; re[i] = re[j]; re[j] = temp
                temp = im[i]; im[i] = im[j]; im[j] = temp
            }
        }

        // Cooley-Tukey butterfly
        val sign = if (forward) -1.0 else 1.0
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = sign * 2.0 * Math.PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)

            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until halfLen) {
                    val evenIdx = i + k
                    val oddIdx = i + k + halfLen
                    val tRe = curRe * re[oddIdx] - curIm * im[oddIdx]
                    val tIm = curRe * im[oddIdx] + curIm * re[oddIdx]
                    re[oddIdx] = re[evenIdx] - tRe
                    im[oddIdx] = im[evenIdx] - tIm
                    re[evenIdx] = re[evenIdx] + tRe
                    im[evenIdx] = im[evenIdx] + tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                i += len
            }
            len = len shl 1
        }

        // Normalize inverse FFT
        if (!forward) {
            val invN = 1.0 / n
            for (i in 0 until n) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }

    private fun nextPowerOf2(v: Int): Int {
        var n = v - 1
        n = n or (n shr 1)
        n = n or (n shr 2)
        n = n or (n shr 4)
        n = n or (n shr 8)
        n = n or (n shr 16)
        return n + 1
    }

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
}
