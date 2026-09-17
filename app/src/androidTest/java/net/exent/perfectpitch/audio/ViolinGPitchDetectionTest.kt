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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The violin G recording: the open G string's fundamental is far weaker than its 2nd harmonic in
 * this capture, so the detector used to report G4. Most of the sustained note must come out G3.
 */
@RunWith(AndroidJUnit4::class)
class ViolinGPitchDetectionTest {

    companion object {
        private const val TAG = "ViolinGTest"
        private const val ASSET = "audio/violin_g.wav"
        private const val G3_MIDI = 55
        private const val G4_MIDI = 67
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun detectsViolinGAsG3() {
        val target = instrumentation.targetContext
        val audio = WavTestAudio.load(instrumentation.context, ASSET)

        val processor = PitchProcessor(
            AudioCaptureService(target),
            CrepePitchDetector(target),
            NoteUtils()
        )
        val chunks = TunerAudioFixture.chunks(audio)
        val flow: Flow<FloatArray> = flowOf(*chunks.toTypedArray())
        val results: List<PitchResult> = runBlocking { processor.pitchFlow(flow).toList() }

        val counts = mutableMapOf<Int, Int>()
        results.forEachIndexed { i, result ->
            if (!result.isValid) return@forEachIndexed
            counts[result.midiNoteNumber] = (counts[result.midiNoteNumber] ?: 0) + 1
            Log.i(
                TAG, "%.3f  %8.2f Hz  %s%d  midi=%d  cents=%6.1f  conf=%.3f".format(
                    i * AudioConstants.FRAME_DURATION_MS / 1000.0,
                    result.frequency, result.noteName, result.octave,
                    result.midiNoteNumber, result.centsOff, result.confidence
                )
            )
        }
        Log.i(TAG, "counts: " + counts.entries.sortedByDescending { it.value }
            .joinToString { "${NoteUtils().midiToNoteName(it.key)}=${it.value}" })

        val g3 = counts[G3_MIDI] ?: 0
        val g4 = counts[G4_MIDI] ?: 0
        assertTrue("Expected G3 frames, got $counts", g3 > 0)
        assertTrue("G3 ($g3) must dominate G4 ($g4), got $counts", g3 > g4 * 4)
    }
}
