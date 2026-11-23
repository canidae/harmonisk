package net.exent.harmonisk.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SubharmonicCorrectorTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val SAMPLES = 1024
        private const val ANALYSIS_WINDOW = 2822
        private const val LOWEST_FREQUENCY = 175f
    }

    private fun tone(frequency: Double, vararg amplitudes: Double): FloatArray {
        val samples = FloatArray(SAMPLES)
        var mean = 0.0
        for (i in samples.indices) {
            val time = i.toDouble() / SAMPLE_RATE
            var value = 0.0
            for ((index, amplitude) in amplitudes.withIndex()) {
                value += amplitude * sin(2.0 * PI * frequency * (index + 1) * time + 0.3 * (index + 1))
            }
            samples[i] = value.toFloat()
            mean += value
        }
        mean /= SAMPLES
        var variance = 0.0
        for (sample in samples) {
            val delta = sample - mean
            variance += delta * delta
        }
        val deviation = kotlin.math.sqrt(variance / SAMPLES).toFloat()
        for (i in samples.indices) samples[i] = ((samples[i] - mean) / deviation).toFloat()
        return samples
    }

    private fun correct(samples: FloatArray, frequency: Float, lowestFrequency: Float = LOWEST_FREQUENCY): Float =
        SubharmonicCorrector.fundamentalFrequency(samples, frequency, lowestFrequency)

    @Test
    fun correctsAWeakFundamental() {
        val samples = tone(196.0, 0.15, 1.0, 0.6, 0.4, 0.3, 0.2)
        assertEquals(196f, correct(samples, 392f), 0.01f)
    }

    @Test
    fun correctsAThirdHarmonic() {
        val samples = tone(196.0, 0.15, 1.0, 0.6, 0.4, 0.3, 0.2)
        assertEquals(196f, correct(samples, 588f), 0.01f)
    }

    @Test
    fun keepsAHarmonicSeriesWithoutOddHarmonics() {
        val samples = tone(316.0, 0.0, 1.0, 0.0, 0.5)
        assertEquals(316f, correct(samples, 316f), 0.01f)
    }

    @Test
    fun keepsARealEvenHarmonicTone() {
        val audio = WavTestAudio.load("/audio/even_harmonics.wav")
        val samples = FloatArray(PitchResampler.TARGET_SAMPLES)
        PitchResampler.resample(audio.copyOfRange(0, ANALYSIS_WINDOW), samples)
        assertEquals(316f, correct(samples, 316f), 3f)
    }

    @Test
    fun keepsAPureSecondHarmonic() {
        assertEquals(392f, correct(tone(392.0, 1.0), 392f), 0.01f)
    }

    @Test
    fun keepsAPureHighNote() {
        assertEquals(1046.5f, correct(tone(1046.5, 1.0), 1046.5f), 0.01f)
    }

    @Test
    fun ignoresACandidateBelowTheRange() {
        val samples = tone(196.0, 0.15, 1.0, 0.6, 0.4, 0.3, 0.2)
        assertEquals(392f, correct(samples, 392f, lowestFrequency = 300f), 0.01f)
    }

    @Test
    fun correctsARealViolinGString() {
        val audio = WavTestAudio.load("/audio/violin_g_weak_fundamental.wav")
        val samples = FloatArray(PitchResampler.TARGET_SAMPLES)
        PitchResampler.resample(audio.copyOfRange(0, ANALYSIS_WINDOW), samples)
        assertEquals(196f, correct(samples, 392f), 1f)
    }
}
