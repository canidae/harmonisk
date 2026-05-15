package com.perfectpitch.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * YIN pitch detection algorithm with FFT-based O(n log n) autocorrelation.
 *
 * Based on: "YIN, a fundamental frequency estimator for speech and music"
 * by Alain de Cheveigné and Hideki Kawahara (2002).
 *
 * The difference function is computed via FFT-based autocorrelation instead of
 * the naive O(n²) approach: d(τ) = r(0) + r_shifted(0) - 2·r(τ)
 */
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

    /**
     * Detect the fundamental frequency of the audio buffer.
     *
     * @param buffer Audio samples (float, -1.0 to 1.0)
     * @param sampleRate Sample rate in Hz
     * @param threshold YIN threshold (lower = stricter, typically 0.10-0.20)
     * @param minFreq Minimum detectable frequency in Hz (default C2 ≈ 65 Hz)
     * @param maxFreq Maximum detectable frequency in Hz (default ≈ C7 ≈ 2100 Hz)
     * @return PitchDetectionResult with frequency and confidence
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

        // Step 1 & 2: FFT-based difference function + cumulative mean normalization
        val yinBuffer = FloatArray(halfLen)
        cumulativeMeanNormalizedDifference(buffer, yinBuffer)

        // Step 3: Absolute threshold — find first valley below threshold in [tauMin, tauMax]
        var tauEstimate = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (yinBuffer[tau] < threshold) {
                while (tau + 1 <= tauMax && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    tau++
                }
                tauEstimate = tau
                break
            }
            tau++
        }

        if (tauEstimate == -1) return PitchDetectionResult.NONE

        // Step 4: Parabolic interpolation for sub-sample accuracy
        val betterTau = parabolicInterpolation(yinBuffer, tauEstimate)

        val frequency = if (betterTau > 0f) sampleRate / betterTau else -1f
        if (frequency <= 0f) return PitchDetectionResult.NONE

        val confidence = (1f - yinBuffer[tauEstimate]).coerceIn(0f, 1f)
        return PitchDetectionResult(frequency, confidence)
    }

    /**
     * Compute cumulative mean normalized difference via FFT-based cross-correlation.
     *
     * The difference function d(τ) = Σ_{i=0}^{W-1} (x[i] - x[i+τ])² expands to:
     *   d(τ) = energy_0 + energy_τ - 2·xcorr(τ)
     * where energy_0 = Σ_{i=0}^{W-1} x[i]², energy_τ = Σ_{i=0}^{W-1} x[i+τ]²,
     * and xcorr(τ) = Σ_{i=0}^{W-1} x[i]·x[i+τ].
     *
     * The cross-correlation is computed as IFFT(conj(FFT(a)) · FFT(b)) where
     * a = first W samples and b = full buffer (both zero-padded).
     */
    private fun cumulativeMeanNormalizedDifference(
        buffer: FloatArray,
        yinBuffer: FloatArray
    ) {
        val halfLen = yinBuffer.size
        val n = buffer.size

        // FFT size: next power of 2 >= 2 * bufferSize for linear cross-correlation
        val fftSize = nextPowerOf2(2 * n)

        // Signal a: first half of buffer (zero-padded)
        val aRe = DoubleArray(fftSize)
        val aIm = DoubleArray(fftSize)
        for (i in 0 until halfLen) {
            aRe[i] = buffer[i].toDouble()
        }

        // Signal b: full buffer (zero-padded)
        val bRe = DoubleArray(fftSize)
        val bIm = DoubleArray(fftSize)
        for (i in 0 until n) {
            bRe[i] = buffer[i].toDouble()
        }

        // Forward FFT both signals
        fft(aRe, aIm, forward = true)
        fft(bRe, bIm, forward = true)

        // Cross-power spectrum: conj(A) · B
        val cRe = DoubleArray(fftSize)
        val cIm = DoubleArray(fftSize)
        for (i in 0 until fftSize) {
            cRe[i] = aRe[i] * bRe[i] + aIm[i] * bIm[i]
            cIm[i] = aRe[i] * bIm[i] - aIm[i] * bRe[i]
        }

        // Inverse FFT to get cross-correlation
        fft(cRe, cIm, forward = false)
        // cRe[τ] = Σ_{i=0}^{W-1} x[i]·x[i+τ]

        // Precompute cumulative energy: energyPrefix[i] = Σ_{j=0}^{i-1} x[j]²
        val energyPrefix = DoubleArray(n + 1)
        for (i in 0 until n) {
            energyPrefix[i + 1] = energyPrefix[i] + buffer[i].toDouble() * buffer[i].toDouble()
        }

        yinBuffer[0] = 1.0f
        var runningSum = 0.0

        for (tau in 1 until halfLen) {
            val energy0 = energyPrefix[halfLen]
            val energyTau = energyPrefix[halfLen + tau] - energyPrefix[tau]
            val diff = energy0 + energyTau - 2.0 * cRe[tau]

            runningSum += diff
            yinBuffer[tau] = if (runningSum != 0.0) {
                (diff * tau / runningSum).toFloat()
            } else {
                1.0f
            }
        }
    }

    /**
     * In-place radix-2 Cooley-Tukey FFT (or inverse FFT).
     * @param re Real part array (length must be power of 2)
     * @param im Imaginary part array (same length)
     * @param forward true for forward FFT, false for inverse FFT
     */
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
