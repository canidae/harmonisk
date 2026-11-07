package net.exent.harmonisk.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

class YinPitchDetectorTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val SAMPLES = 1024
        private const val ANALYSIS_WINDOW = 2822
        private const val SOURCE_RATE = 44100
        private val HARMONIC = doubleArrayOf(1.0, 0.7, 0.5, 0.35, 0.25, 0.18, 0.12, 0.08)
        private val WEAK_FUNDAMENTAL = doubleArrayOf(0.15, 1.0, 0.6, 0.4, 0.3, 0.2, 0.12, 0.06)
        private val MISSING_FUNDAMENTAL = doubleArrayOf(0.0, 1.0, 0.7, 0.5, 0.35, 0.25, 0.18, 0.12)
    }

    private val detector = YinPitchDetector()

    @Test
    fun detectsFundamentalsAcrossTheLowRange() {
        for (frequency in listOf(41.2, 65.41, 73.42, 98.0, 110.0, 130.81, 146.83, 196.0, 220.0, 293.66)) {
            assertTone(frequency, HARMONIC)
        }
    }

    @Test
    fun detectsWeakFundamentals() {
        for (frequency in listOf(41.2, 65.41, 98.0, 130.81, 196.0, 293.66)) {
            assertTone(frequency, WEAK_FUNDAMENTAL)
        }
    }

    @Test
    fun detectsMissingFundamentals() {
        for (frequency in listOf(41.2, 65.41, 98.0, 130.81, 196.0, 293.66)) {
            assertTone(frequency, MISSING_FUNDAMENTAL)
        }
    }

    @Test
    fun ignoresSilence() {
        val result = detector.detect(FloatArray(SAMPLES))
        assertTrue(result.frequency <= 0f)
    }

    @Test
    fun ignoresNoise() {
        val random = java.util.Random(7)
        repeat(10) {
            val noise = FloatArray(SAMPLES) { (random.nextFloat() - 0.5f) * 0.5f }
            assertTrue("Noise should not produce a pitch", detector.detect(noise).frequency <= 0f)
        }
    }

    @Test
    fun detectsRealCelloC2() {
        assertFixture("/audio/cello_c2.wav", 65.41)
    }

    @Test
    fun detectsRealCelloG2() {
        assertFixture("/audio/cello_g2.wav", 98.0)
    }

    @Test
    fun detectsRealViolaC3() {
        assertFixture("/audio/viola_c3.wav", 130.81)
    }

    @Test
    fun detectsRealViolinG3() {
        assertFixture("/audio/violin_g.wav", 196.0)
    }

    @Test
    fun keepsTheFundamentalOnSpeakerRecordedViolin() {
        assertFixture("/audio/yin_octave_down.wav", 369.99, 15.0, start = 0)
    }

    private fun assertFixture(
        resource: String,
        expected: Double,
        toleranceCents: Double = 5.0,
        start: Int = SOURCE_RATE
    ) {
        val audio = WavTestAudio.load(resource)
        val window = audio.copyOfRange(start, start + ANALYSIS_WINDOW)
        val resampled = FloatArray(PitchResampler.TARGET_SAMPLES)
        PitchResampler.resample(window, resampled)
        assertFrequency(expected, resampled, toleranceCents)
    }

    private fun assertTone(frequency: Double, harmonics: DoubleArray) {
        assertFrequency(frequency, tone(frequency, harmonics))
    }

    private fun assertFrequency(expected: Double, samples: FloatArray, toleranceCents: Double = 5.0) {
        val result = detector.detect(samples)
        val cents = if (result.frequency <= 0f) Double.MAX_VALUE else
            1200.0 * ln(result.frequency / expected) / ln(2.0)
        assertTrue(
            "Expected ${"%.2f".format(expected)} Hz, got ${"%.2f".format(result.frequency)} Hz (${"%.1f".format(cents)} cents)",
            abs(cents) < toleranceCents
        )
    }

    private fun tone(frequency: Double, harmonics: DoubleArray): FloatArray {
        val out = FloatArray(SAMPLES)
        var peak = 0.0
        for (i in out.indices) {
            val time = i.toDouble() / SAMPLE_RATE
            var value = 0.0
            for ((index, amplitude) in harmonics.withIndex()) {
                value += amplitude * sin(2.0 * PI * frequency * (index + 1) * time + 0.3 * (index + 1))
            }
            out[i] = value.toFloat()
            peak = maxOf(peak, abs(value))
        }
        val scale = (0.5 / peak).toFloat()
        for (i in out.indices) out[i] *= scale
        return out
    }
}
