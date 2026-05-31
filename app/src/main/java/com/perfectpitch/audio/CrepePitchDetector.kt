package com.perfectpitch.audio

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.ln

/**
 * Shared result type for pitch detection.
 */
data class PitchDetectionResult(
    val frequency: Float,
    val confidence: Float
) {
    companion object {
        val NONE = PitchDetectionResult(-1f, 0f)
    }
}

/**
 * CREPE pitch detector backed by a TFLite model.
 *
 * Algorithm: Kim et al. (2018) "CREPE: A Convolutional Representation for Pitch Estimation",
 * ICASSP 2018. The small-capacity model (crepe_small_f16.tflite, ~3.2 MB in assets) is used.
 *
 * Input pipeline per inference:
 *   1. Downsample the 44100 Hz analysis window to 16000 Hz using linear interpolation.
 *   2. Crop/pad to exactly 1024 samples (= 64 ms at 16 kHz) — CREPE's required input size.
 *   3. Normalise: subtract mean, divide by std dev (prevents DC offset / amplitude sensitivity).
 *   4. Run TFLite inference → 360-element sigmoid vector.
 *   5. Weighted argmax around the peak bin → frequency in Hz (sub-cent precision).
 *
 * Frequency mapping (from crepe.core source):
 *   bin_cents[i] = linspace(0, 7180, 360)[i] + 1997.3794084376191
 *   freq_hz[i]   = 10 * 2^(bin_cents[i] / 1200)
 *   Range: ~31.7 Hz (bin 0) to ~2005.5 Hz (bin 359)
 *
 * Silence gate: if no activation bin exceeds CONFIDENCE_THRESHOLD, the frame is considered
 * unvoiced and NONE is returned. This is more reliable than an NSDF energy floor because
 * CREPE's confidence directly reflects periodicity learned from training data.
 */
class CrepePitchDetector(context: Context) {

    companion object {
        private const val MODEL_ASSET = "crepe_small_f16.tflite"

        /** CREPE input: exactly 1024 samples at 16 kHz */
        const val CREPE_INPUT_SAMPLES = 1024
        const val CREPE_SAMPLE_RATE = 16000

        /** Number of output bins and their frequency mapping */
        private const val N_BINS = 360
        private val BIN_CENTS = FloatArray(N_BINS) { i ->
            (i.toFloat() / (N_BINS - 1) * 7180f) + 1997.3794f
        }
        private val BIN_FREQS = FloatArray(N_BINS) { i ->
            (10.0 * Math.pow(2.0, BIN_CENTS[i] / 1200.0)).toFloat()
        }

        /**
         * Minimum confidence (max activation value) to report a pitch.
         * CREPE is trained to output high confidence for voiced frames; silence/noise
         * typically scores < 0.5 even if some bin wins. 0.45 is intentionally slightly below
         * the midpoint to avoid over-rejecting soft singing/playing.
         */
        private const val CONFIDENCE_THRESHOLD = 0.45f
    }

    private val interpreter: Interpreter by lazy { loadInterpreter(context) }

    // Pre-allocated buffers (reused per inference to avoid GC pressure)
    private val inputBuffer = ByteBuffer
        .allocateDirect(1 * CREPE_INPUT_SAMPLES * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputArray = Array(1) { FloatArray(N_BINS) }

    // Reusable downsampled buffer
    private val downsampledBuf = FloatArray(CREPE_INPUT_SAMPLES)

    private fun loadInterpreter(context: Context): Interpreter {
        val afd = context.assets.openFd(MODEL_ASSET)
        val fis = afd.createInputStream()
        val channel = fis.channel
        val mappedBuf = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        channel.close()
        fis.close()
        return Interpreter(mappedBuf, Interpreter.Options().apply { numThreads = 2 })
    }

    /**
     * Detect pitch from a 44100 Hz analysis window.
     *
     * @param buffer   Raw 44100 Hz samples (any length ≥ 1; larger windows are downsampled
     *                 to 1024 samples at 16 kHz — only the last 64 ms worth is used).
     * @param hintFreq Frequency of the previously confirmed note (Hz). When the CREPE output
     *                 is ambiguous between octave candidates, the candidate closest in
     *                 log-frequency to [hintFreq] is preferred. Pass 0f if no prior context.
     */
    fun detectPitch(buffer: FloatArray, hintFreq: Float = 0f): PitchDetectionResult {
        downsample44100to16k(buffer, downsampledBuf)
        normalise(downsampledBuf)

        // Fill input ByteBuffer
        inputBuffer.rewind()
        for (s in downsampledBuf) inputBuffer.putFloat(s)

        // Run inference
        interpreter.run(inputBuffer, outputArray)
        val activation = outputArray[0]

        val peakBin = activation.indices.maxByOrNull { activation[it] } ?: 0
        val confidence = activation[peakBin]
        if (confidence < CONFIDENCE_THRESHOLD) return PitchDetectionResult.NONE

        val freq = weightedFrequency(activation, peakBin, hintFreq)
        if (freq <= 0f) return PitchDetectionResult.NONE

        return PitchDetectionResult(freq, confidence)
    }

    /**
     * Downsample a 44100 Hz buffer to exactly 1024 samples at 16 kHz.
     *
     * The ratio 44100/16000 = 2.75625 samples per output sample.
     * We read the MOST RECENT 1024 * 2.75625 = 2822 samples from the end of [src].
     * Linear interpolation between adjacent src samples gives adequate quality for
     * pitch detection (no high-frequency content above 8 kHz matters for CREPE).
     */
    private fun downsample44100to16k(src: FloatArray, dst: FloatArray) {
        val ratio = 44100.0 / CREPE_SAMPLE_RATE   // = 2.75625
        val srcNeeded = (CREPE_INPUT_SAMPLES * ratio).toInt() + 2
        val srcOffset = (src.size - srcNeeded).coerceAtLeast(0)
        val availableSrc = src.size - srcOffset

        for (i in 0 until CREPE_INPUT_SAMPLES) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()
            val s0 = if (srcIdx < availableSrc) src[srcOffset + srcIdx] else 0f
            val s1 = if (srcIdx + 1 < availableSrc) src[srcOffset + srcIdx + 1] else s0
            dst[i] = s0 + frac * (s1 - s0)
        }
    }

    /** Zero-mean / unit-variance normalisation in place. */
    private fun normalise(buf: FloatArray) {
        var mean = 0.0
        for (s in buf) mean += s
        mean /= buf.size
        var variance = 0.0
        for (s in buf) { val d = s - mean; variance += d * d }
        variance /= buf.size
        val invStd = if (variance > 1e-10) (1.0 / Math.sqrt(variance)).toFloat() else 1f
        for (i in buf.indices) buf[i] = ((buf[i] - mean) * invStd).toFloat()
    }

    /**
     * Compute the weighted-average frequency around the peak bin.
     *
     * When [hintFreq] > 0 and there is a secondary peak at an octave interval whose weighted
     * frequency is closer to [hintFreq], that candidate is preferred.  This handles the rare
     * case where CREPE's sigmoid outputs two similarly strong peaks (fundamental + 2nd harmonic).
     */
    private fun weightedFrequency(activation: FloatArray, peakBin: Int, hintFreq: Float): Float {
        val primary = localWeightedFreq(activation, peakBin)

        // Look for an octave-related secondary candidate only if we have a hint
        if (hintFreq <= 0f) return primary

        // Find all local maxima above 0.5 × peak that are not within 3 bins of the primary
        val candidates = mutableListOf(primary)
        val threshold = activation[peakBin] * 0.5f
        var localPeak = -1
        var localMax = 0f
        for (i in activation.indices) {
            val v = activation[i]
            if (v >= threshold && abs(i - peakBin) > 3) {
                if (v > localMax) { localMax = v; localPeak = i }
            } else if (localPeak >= 0 && (i - localPeak > 5 || i == activation.size - 1)) {
                candidates.add(localWeightedFreq(activation, localPeak))
                localPeak = -1; localMax = 0f
            }
        }

        if (candidates.size == 1) return primary

        // Pick the candidate closest to hintFreq in log-frequency distance
        return candidates.minByOrNull { f ->
            if (f <= 0f) Float.MAX_VALUE else abs(ln(f.toDouble() / hintFreq)).toFloat()
        } ?: primary
    }

    private fun localWeightedFreq(activation: FloatArray, center: Int): Float {
        val start = maxOf(0, center - 4)
        val end = minOf(N_BINS, center + 5)
        var weightedSum = 0.0
        var totalWeight = 0.0
        for (i in start until end) {
            val w = activation[i].toDouble()
            weightedSum += BIN_FREQS[i] * w
            totalWeight += w
        }
        return if (totalWeight < 1e-10) BIN_FREQS[center] else (weightedSum / totalWeight).toFloat()
    }
}
