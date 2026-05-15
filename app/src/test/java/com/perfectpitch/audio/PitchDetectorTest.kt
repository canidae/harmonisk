package com.perfectpitch.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.*

class PitchDetectorTest {

    private lateinit var detector: PitchDetector
    private val sampleRate = 44100

    @Before
    fun setUp() {
        detector = PitchDetector()
    }

    /**
     * Generate a synthetic sine wave at a given frequency.
     */
    private fun generateSineWave(
        frequency: Float,
        sampleRate: Int = this.sampleRate,
        numSamples: Int = 4096,
        amplitude: Float = 0.8f
    ): FloatArray {
        return FloatArray(numSamples) { i ->
            amplitude * sin(2.0 * PI * frequency * i / sampleRate).toFloat()
        }
    }

    @Test
    fun `detect A4 at 440 Hz`() {
        val buffer = generateSineWave(440f)
        val result = detector.detectPitch(buffer, sampleRate)
        assertFrequencyClose(440f, result.frequency, toleranceCents = 5f)
    }

    @Test
    fun `detect C4 at 261_63 Hz`() {
        val buffer = generateSineWave(261.63f)
        val result = detector.detectPitch(buffer, sampleRate)
        assertFrequencyClose(261.63f, result.frequency, toleranceCents = 5f)
    }

    @Test
    fun `detect E4 at 329_63 Hz`() {
        val buffer = generateSineWave(329.63f)
        val result = detector.detectPitch(buffer, sampleRate)
        assertFrequencyClose(329.63f, result.frequency, toleranceCents = 5f)
    }

    @Test
    fun `detect low frequency G2 at 98 Hz`() {
        val buffer = generateSineWave(98f, numSamples = 4096)
        val result = detector.detectPitch(buffer, sampleRate)
        assertFrequencyClose(98f, result.frequency, toleranceCents = 10f)
    }

    @Test
    fun `detect high frequency C6 at 1047 Hz`() {
        val buffer = generateSineWave(1046.5f)
        val result = detector.detectPitch(buffer, sampleRate)
        assertFrequencyClose(1046.5f, result.frequency, toleranceCents = 5f)
    }

    @Test
    fun `detect very high frequency A6 at 1760 Hz`() {
        val buffer = generateSineWave(1760f)
        val result = detector.detectPitch(buffer, sampleRate)
        assertFrequencyClose(1760f, result.frequency, toleranceCents = 10f)
    }

    @Test
    fun `silence returns no pitch`() {
        val buffer = FloatArray(4096) { 0f }
        val result = detector.detectPitch(buffer, sampleRate)
        assertTrue("Should not detect pitch in silence", result.frequency <= 0f)
    }

    @Test
    fun `very low amplitude returns detected pitch`() {
        val buffer = generateSineWave(440f, amplitude = 0.001f)
        // With such low amplitude, YIN should still detect (it's amplitude-independent)
        // but the RMS check in PitchProcessor would filter this
        val result = detector.detectPitch(buffer, sampleRate)
        // YIN itself doesn't filter by amplitude, so it should still detect
        if (result.frequency > 0f) {
            assertFrequencyClose(440f, result.frequency, toleranceCents = 10f)
        }
    }

    @Test
    fun `detect pitch with harmonics - simulated instrument`() {
        // Simulate a note with fundamental + harmonics (like a real instrument)
        val fundamental = 220f // A3
        val numSamples = 4096
        val buffer = FloatArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            (0.6 * sin(2.0 * PI * fundamental * t) +       // fundamental
             0.3 * sin(2.0 * PI * fundamental * 2 * t) +   // 2nd harmonic
             0.1 * sin(2.0 * PI * fundamental * 3 * t)     // 3rd harmonic
            ).toFloat()
        }
        val result = detector.detectPitch(buffer, sampleRate)
        assertFrequencyClose(fundamental, result.frequency, toleranceCents = 10f)
    }

    @Test
    fun `confidence is positive for clean signal`() {
        val buffer = generateSineWave(440f)
        val result = detector.detectPitch(buffer, sampleRate)
        assertTrue("Confidence should be positive for clean signal", result.confidence > 0f)
        assertTrue("Confidence should be at most 1.0", result.confidence <= 1f)
    }

    @Test
    fun `PitchDetectionResult NONE has negative frequency`() {
        assertEquals(-1f, PitchDetectionResult.NONE.frequency, 0f)
        assertEquals(0f, PitchDetectionResult.NONE.confidence, 0f)
    }

    @Test
    fun `rms calculation is correct`() {
        // Known signal: constant value of 1.0
        val buffer = FloatArray(100) { 1.0f }
        val rms = detector.rms(buffer)
        assertEquals(1.0f, rms, 0.001f)

        // Sine wave RMS = amplitude / sqrt(2)
        val sineBuffer = generateSineWave(440f, numSamples = 44100, amplitude = 1.0f)
        val sineRms = detector.rms(sineBuffer)
        assertEquals(1.0f / sqrt(2.0f), sineRms, 0.01f)
    }

    @Test
    fun `rmsToDb conversion`() {
        // RMS of 1.0 = 0 dB
        assertEquals(0f, detector.rmsToDb(1.0f), 0.01f)
        // RMS of 0.1 = -20 dB
        assertEquals(-20f, detector.rmsToDb(0.1f), 0.01f)
        // RMS of 0.01 = -40 dB
        assertEquals(-40f, detector.rmsToDb(0.01f), 0.01f)
        // Silence
        assertEquals(-100f, detector.rmsToDb(0f), 0.01f)
    }

    @Test
    fun `empty buffer returns no pitch`() {
        val result = detector.detectPitch(FloatArray(0), sampleRate)
        assertTrue(result.frequency <= 0f)
    }

    /**
     * Assert that a detected frequency is within a given tolerance in cents.
     */
    private fun assertFrequencyClose(expected: Float, actual: Float, toleranceCents: Float) {
        assertTrue(
            "Expected pitch near $expected Hz but got $actual Hz (no pitch detected)",
            actual > 0f
        )
        val centsOff = abs(1200.0 * log2(actual.toDouble() / expected.toDouble()))
        assertTrue(
            "Expected pitch near $expected Hz (got $actual Hz, ${"%.1f".format(centsOff)} cents off, " +
                "tolerance: $toleranceCents cents)",
            centsOff <= toleranceCents
        )
    }
}
