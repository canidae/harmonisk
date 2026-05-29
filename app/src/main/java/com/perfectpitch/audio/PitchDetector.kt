package com.perfectpitch.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * McLeod Pitch Method (MPM) — FFT-accelerated NSDF pitch detection.
 *
 * Algorithm: McLeod & Wyvill (2005) "A Smarter Way to Find Pitch", ICMC 2005.
 *
 * The Normalized Square Difference Function (NSDF) replaces YIN's Cumulative Mean Normalized
 * Difference Function. Key advantages:
 *   - Locally normalized: NSDF(τ) ∈ [-1, 1] — relative threshold adapts to signal level
 *   - Peak-picking (maxima at period multiples) vs valley-picking (YIN): first peak above
 *     k × global_max naturally prefers the fundamental over sub-harmonics
 *   - No octave-correction post-processing needed for most signals
 *
 * NSDF(τ) = 2·r'(τ) / m'(τ)
 *   r'(τ) = Σ_{j=0}^{W-1-τ} x[j]·x[j+τ]           (shrinking-window autocorrelation)
 *   m'(τ) = Σ_{j=0}^{W-1-τ} (x[j]² + x[j+τ]²)     (per-lag energy, shrinking window)
 *
 * r'(τ) is computed via FFT autocorrelation of just the first W samples (zero-padded to 2W).
 * m'(τ) is updated incrementally: m'(τ) = m'(τ-1) - x[τ-1]² - x[W-τ]²
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
        /** MPM relative threshold k — first key maximum above k × global_max is accepted. */
        const val DEFAULT_THRESHOLD = 0.93f
        const val DEFAULT_SAMPLE_RATE = 44100

        /**
         * Absolute floor for the NSDF global maximum.
         * If no peak exceeds this, the frame is considered too aperiodic (noise/silence/transition).
         */
        private const val SMALL_CUTOFF = 0.45f
    }

    // Pre-allocated FFT work arrays, lazily sized on first call.
    private var fftWorkSize = 0
    private var workAre = DoubleArray(0)
    private var workAim = DoubleArray(0)
    private var workCre = DoubleArray(0)
    private var workCim = DoubleArray(0)

    private fun ensureWorkArrays(halfLen: Int) {
        val required = nextPowerOf2(2 * halfLen)
        if (required != fftWorkSize) {
            fftWorkSize = required
            workAre = DoubleArray(required)
            workAim = DoubleArray(required)
            workCre = DoubleArray(required)
            workCim = DoubleArray(required)
        }
    }

    /**
     * Detect the fundamental frequency using the McLeod Pitch Method (MPM).
     *
     * @param threshold MPM relative threshold k ∈ (0, 1) — first key maximum ≥ k × global_max
     *                  is accepted. Default 0.93. Lower = more conservative (fewer octave errors).
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

        val nsdfBuffer = FloatArray(halfLen)
        normalizedSquareDifference(buffer, nsdfBuffer)

        val tauEstimate = pickPeakMPM(nsdfBuffer, tauMin, tauMax, threshold)
        if (tauEstimate == -1) return PitchDetectionResult.NONE

        val betterTau = parabolicInterpolation(nsdfBuffer, tauEstimate)
        val frequency = if (betterTau > 0f) sampleRate / betterTau else -1f
        if (frequency <= 0f) return PitchDetectionResult.NONE

        val confidence = nsdfBuffer[tauEstimate].coerceIn(0f, 1f)
        return PitchDetectionResult(frequency, confidence)
    }

    /**
     * Compute the Normalized Square Difference Function (NSDF) via FFT.
     *
     * r'(τ) is the linear autocorrelation of x[0..W-1] computed as:
     *   IFFT(|FFT(x[0..W-1])|²)[τ]   (zero-padded to 2W to prevent circular aliasing)
     *
     * m'(τ) is the per-lag energy sum, updated incrementally per the McLeod paper, Section 6:
     *   m'(0) = 2·r'(0)
     *   m'(τ) = m'(τ-1) − x[τ-1]² − x[W-τ]²
     *
     * NSDF(τ) = 2·r'(τ) / m'(τ)  ∈ [-1, 1]
     */
    private fun normalizedSquareDifference(buffer: FloatArray, nsdfBuffer: FloatArray) {
        val W = nsdfBuffer.size  // = halfLen = buffer.size / 2
        ensureWorkArrays(W)

        // Forward FFT of x[0..W-1] zero-padded to fftWorkSize (≥ 2W)
        workAre.fill(0.0)
        workAim.fill(0.0)
        for (i in 0 until W) workAre[i] = buffer[i].toDouble()
        fft(workAre, workAim, forward = true)

        // Power spectrum: |FFT(x)|²
        for (i in 0 until fftWorkSize) {
            workCre[i] = workAre[i] * workAre[i] + workAim[i] * workAim[i]
            workCim[i] = 0.0
        }

        // IFFT → r'(τ) = linear autocorrelation of x[0..W-1] for τ = 0..W-1
        fft(workCre, workCim, forward = false)
        // workCre[τ] = r'(τ) = Σ_{j=0}^{W-1-τ} x[j]·x[j+τ]

        // NSDF with incremental m'(τ)
        nsdfBuffer[0] = 1.0f  // NSDF(0) = 2·r'(0) / 2·r'(0) = 1 by definition
        var m = 2.0 * workCre[0]   // m'(0) = 2·r'(0) = 2·Σ x[j]²
        for (tau in 1 until W) {
            // m'(τ) = m'(τ-1) − x[τ-1]² − x[W-τ]²
            val xtm1 = buffer[tau - 1].toDouble()
            val xWmt = buffer[W - tau].toDouble()
            m -= xtm1 * xtm1 + xWmt * xWmt
            nsdfBuffer[tau] = if (m > 0.0) (2.0 * workCre[tau] / m).toFloat() else 0.0f
        }
    }

    /**
     * MPM key-maximum peak picker.
     *
     * A "key maximum" is the highest NSDF value within each positive run (sign changes from
     * negative to positive and back). Only key maxima above k × global_max are accepted.
     * The FIRST such key maximum (smallest τ = highest frequency) is the fundamental.
     *
     * If the global key maximum is below SMALL_CUTOFF, the signal is too aperiodic (noise
     * or a note-transition frame) and we return -1 (no pitch).
     *
     * This naturally handles octave ambiguity: T₁ = τ₀ (fundamental) and T₂ = 2τ₀ both
     * produce NSDF peaks of similar height. By scanning from small τ upward, T₁ is found first.
     * YIN's CMND-based valley-picking can accidentally pick 2τ₀ or 4τ₀ if d'(τ₀) is just
     * above the absolute threshold; MPM's relative threshold avoids this bias.
     */
    private fun pickPeakMPM(
        nsdfBuffer: FloatArray,
        tauMin: Int,
        tauMax: Int,
        relThreshold: Float
    ): Int {
        // Collect all key maxima (peak of each positive run)
        val kmTaus = IntArray(128)
        val kmVals = FloatArray(128)
        var kmCount = 0
        var globalKM = 0f

        var i = tauMin
        while (i <= tauMax && kmCount < 128) {
            if (nsdfBuffer[i] > 0f) {
                var peakTau = i
                var peakVal = nsdfBuffer[i]
                while (i + 1 <= tauMax && nsdfBuffer[i + 1] > 0f) {
                    i++
                    if (nsdfBuffer[i] > peakVal) {
                        peakVal = nsdfBuffer[i]
                        peakTau = i
                    }
                }
                kmTaus[kmCount] = peakTau
                kmVals[kmCount] = peakVal
                if (peakVal > globalKM) globalKM = peakVal
                kmCount++
            }
            i++
        }

        if (kmCount == 0 || globalKM < SMALL_CUTOFF) return -1

        val k = relThreshold * globalKM
        for (j in 0 until kmCount) {
            if (kmVals[j] >= k) return kmTaus[j]
        }
        return kmTaus[0]  // fallback: best key maximum
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
