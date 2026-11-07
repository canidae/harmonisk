package net.exent.harmonisk.audio

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PitchDetectionResult(
    val frequency: Float,
    val confidence: Float
) {
    companion object {
        val NONE = PitchDetectionResult(-1f, 0f)
    }
}

internal object PitchResampler {
    const val SOURCE_RATE = AudioConstants.SAMPLE_RATE
    const val TARGET_RATE = 16000
    const val TARGET_SAMPLES = 1024

    fun resample(source: FloatArray, target: FloatArray) {
        val ratio = SOURCE_RATE.toDouble() / TARGET_RATE
        val needed = (target.size * ratio).toInt() + 2
        val offset = (source.size - needed).coerceAtLeast(0)
        val available = source.size - offset
        for (i in target.indices) {
            val position = i * ratio
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val first = if (index < available) source[offset + index] else 0f
            val second = if (index + 1 < available) source[offset + index + 1] else first
            target[i] = first + fraction * (second - first)
        }
    }
}

internal object PitchFusion {
    const val CREPE_MIN_CONFIDENCE = 0.45f
    const val CORRECTION_MAX_FREQUENCY = 350f
    private const val YIN_MARGIN_CENTS = 50f

    fun combine(
        crepe: PitchDetectionResult,
        corrected: PitchDetectionResult,
        yin: PitchDetectionResult,
        trustLowerYin: Boolean = true
    ): PitchDetectionResult {
        if (crepe.frequency <= 0f || crepe.confidence < CREPE_MIN_CONFIDENCE) {
            return if (yin.frequency > 0f) yin else PitchDetectionResult.NONE
        }
        if (yin.frequency <= 0f) return corrected
        val yinIsLower = yin.frequency <
            corrected.frequency * 2.0.pow(-YIN_MARGIN_CENTS / 1200.0).toFloat()
        return if (corrected.frequency <= 0f || (yinIsLower && trustLowerYin)) yin else corrected
    }
}

class PitchDetector(context: Context) {

    companion object {
        private const val MODEL_ASSET = "crepe_small_f16.tflite"
        private const val N_BINS = 360
        private const val YIN_SUPPORT_RATIO = 0.03f

        private val BIN_CENTS = FloatArray(N_BINS) { i ->
            (i.toFloat() / (N_BINS - 1) * 7180f) + 1997.3794f
        }
        private val BIN_FREQS = FloatArray(N_BINS) { i ->
            (10.0 * 2.0.pow(BIN_CENTS[i] / 1200.0)).toFloat()
        }
    }

    private val interpreter: Interpreter by lazy { loadInterpreter(context) }

    private val inputBuffer = ByteBuffer
        .allocateDirect(PitchResampler.TARGET_SAMPLES * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputArray = Array(1) { FloatArray(N_BINS) }
    private val downsampledBuf = FloatArray(PitchResampler.TARGET_SAMPLES)
    private val yinDetector = YinPitchDetector()

    private fun loadInterpreter(context: Context): Interpreter {
        val afd = context.assets.openFd(MODEL_ASSET)
        val fis = afd.createInputStream()
        val channel = fis.channel
        val mappedBuf = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        channel.close()
        fis.close()
        return Interpreter(mappedBuf, Interpreter.Options().apply { numThreads = 2 })
    }

    fun detectPitch(buffer: FloatArray, lowestFrequency: Float): PitchDetectionResult {
        PitchResampler.resample(buffer, downsampledBuf)
        normalise(downsampledBuf)

        inputBuffer.rewind()
        for (sample in downsampledBuf) inputBuffer.putFloat(sample)
        interpreter.run(inputBuffer, outputArray)
        val activation = outputArray[0]

        val peakBin = activation.indices.maxByOrNull { activation[it] } ?: 0
        val confidence = activation[peakBin]
        val yin = yinDetector.detect(downsampledBuf)
        if (confidence < PitchFusion.CREPE_MIN_CONFIDENCE) {
            return PitchFusion.combine(PitchDetectionResult.NONE, PitchDetectionResult.NONE, yin)
        }

        val rawFrequency = localWeightedFrequency(activation, peakBin)
        var correctedFrequency = rawFrequency
        if (rawFrequency <= PitchFusion.CORRECTION_MAX_FREQUENCY) {
            val subharmonicBin = SubharmonicCorrector.fundamentalBin(activation, peakBin)
            if (subharmonicBin != peakBin) {
                val subharmonicFrequency = localWeightedFrequency(activation, subharmonicBin)
                if (subharmonicFrequency >= lowestFrequency) {
                    correctedFrequency = subharmonicFrequency
                }
            }
        }
        val corrected = if (correctedFrequency >= lowestFrequency) {
            PitchDetectionResult(correctedFrequency, confidence)
        } else {
            PitchDetectionResult.NONE
        }
        val yinSupported = activationPeakAt(activation, yin.frequency) >= confidence * YIN_SUPPORT_RATIO
        val fused = PitchFusion.combine(
            PitchDetectionResult(rawFrequency, confidence),
            corrected,
            yin,
            trustLowerYin = rawFrequency <= PitchFusion.CORRECTION_MAX_FREQUENCY || yinSupported
        )
        return if (fused.frequency >= lowestFrequency) fused else PitchDetectionResult.NONE
    }

    private fun activationPeakAt(activation: FloatArray, frequency: Float): Float {
        if (frequency <= 0f) return 0f
        val cents = 1200.0 * ln(frequency / 10.0) / ln(2.0)
        val center = ((cents - BIN_CENTS[0]) / (7180.0 / (N_BINS - 1))).roundToInt()
        if (center < 1 || center > N_BINS - 2) return 0f
        var peak = 0f
        val start = maxOf(1, center - 3)
        val end = minOf(N_BINS - 2, center + 3)
        for (i in start..end) {
            val value = activation[i]
            if (value > activation[i - 1] && value >= activation[i + 1] && value > peak) peak = value
        }
        return peak
    }

    private fun normalise(buffer: FloatArray) {
        var mean = 0.0
        for (sample in buffer) mean += sample
        mean /= buffer.size
        var variance = 0.0
        for (sample in buffer) {
            val delta = sample - mean
            variance += delta * delta
        }
        variance /= buffer.size
        val inverseStdDev = if (variance > 1e-10) (1.0 / sqrt(variance)).toFloat() else 1f
        for (i in buffer.indices) buffer[i] = ((buffer[i] - mean) * inverseStdDev).toFloat()
    }

    private fun localWeightedFrequency(activation: FloatArray, center: Int): Float {
        val start = maxOf(0, center - 4)
        val end = minOf(N_BINS, center + 5)
        var weightedSum = 0.0
        var totalWeight = 0.0
        for (i in start until end) {
            val weight = activation[i].toDouble()
            weightedSum += BIN_FREQS[i] * weight
            totalWeight += weight
        }
        return if (totalWeight < 1e-10) BIN_FREQS[center] else (weightedSum / totalWeight).toFloat()
    }
}
