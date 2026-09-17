package net.exent.perfectpitch.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.music.PitchResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Guards the octave of the production detector: a tone with a strong fundamental must keep its
 * octave, and a tone whose 2nd harmonic dominates (violin G string through a phone microphone)
 * must still be reported an octave below that harmonic.
 */
@RunWith(AndroidJUnit4::class)
class PitchOctaveRegressionTest {

    companion object {
        private const val TAG = "PitchOctaveTest"
        private val PITCHES = listOf(98.0, 130.81, 196.0, 261.63, 392.0, 440.0, 523.25)

        /** Ordinary harmonic stack: fundamental strongest. */
        private val HARMONIC = doubleArrayOf(1.0, 0.7, 0.5, 0.35, 0.25, 0.18, 0.12, 0.08)

        /** Fundamental far below the 2nd harmonic, as measured on the violin G recording. */
        private val WEAK_FUNDAMENTAL = doubleArrayOf(0.15, 1.0, 0.6, 0.4, 0.3, 0.2, 0.12, 0.06)
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun harmonicTonesKeepTheirOctave() {
        for (freq in PITCHES) {
            assertDetects(freq, HARMONIC)
        }
    }

    @Test
    fun weakFundamentalTonesRecoverTheirOctave() {
        for (freq in PITCHES) {
            assertDetects(freq, WEAK_FUNDAMENTAL)
        }
    }

    private fun assertDetects(freqHz: Double, harmonics: DoubleArray) {
        val detected = detectDominantMidi(tone(freqHz, harmonics))
        val expected = NoteUtils().frequencyToNote(freqHz.toFloat()).midiNumber
        Log.i(TAG, "%.2f Hz -> midi %d (expected %d)".format(freqHz, detected, expected))
        assertEquals("Detected octave for ${freqHz}Hz", expected, detected)
    }

    private fun detectDominantMidi(audio: FloatArray): Int {
        val target = instrumentation.targetContext
        val processor = PitchProcessor(
            AudioCaptureService(target),
            CrepePitchDetector(target),
            NoteUtils()
        )
        val chunks = TunerAudioFixture.chunks(audio)
        val flow: Flow<FloatArray> = flowOf(*chunks.toTypedArray())
        val results: List<PitchResult> = runBlocking { processor.pitchFlow(flow).toList() }
        return results.filter { it.isValid }
            .groupingBy { it.midiNoteNumber }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: -1
    }

    private fun tone(freqHz: Double, harmonics: DoubleArray, seconds: Double = 0.8): FloatArray {
        val sampleRate = 44100
        val samples = (seconds * sampleRate).toInt()
        val out = FloatArray(samples)
        var peak = 0.0
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            var value = 0.0
            for ((index, amplitude) in harmonics.withIndex()) {
                value += amplitude * sin(2 * PI * freqHz * (index + 1) * t)
            }
            out[i] = value.toFloat()
            peak = maxOf(peak, abs(value))
        }
        val inv = (0.5 / peak).toFloat()
        for (i in out.indices) out[i] *= inv
        return out
    }
}
