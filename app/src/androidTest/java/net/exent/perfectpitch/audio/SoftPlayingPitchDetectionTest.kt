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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The violin G recording scaled down by [QUIET_DB] stands in for a softly played note. At the
 * default sensitivity it must still be heard: the silence gate is on the level of the input, so
 * its default decides how loud a player has to be.
 */
@RunWith(AndroidJUnit4::class)
class SoftPlayingPitchDetectionTest {

    companion object {
        private const val TAG = "SoftPitchTest"
        private const val ASSET = "audio/violin_g.wav"
        private const val G3_MIDI = 55

        /** How much quieter than the recording a player who is not playing out counts as. */
        private const val QUIET_DB = -10
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun hearsTheQuietViolinG() {
        val target = instrumentation.targetContext
        val audio = WavTestAudio.load(instrumentation.context, ASSET)
        val gain = Math.pow(10.0, QUIET_DB / 20.0).toFloat()
        val quiet = FloatArray(audio.size) { audio[it] * gain }

        val processor = PitchProcessor(
            AudioCaptureService(target),
            CrepePitchDetector(target),
            NoteUtils()
        )
        val flow: Flow<FloatArray> = flowOf(*TunerAudioFixture.chunks(quiet).toTypedArray())
        val results: List<PitchResult> = runBlocking { processor.pitchFlow(flow).toList() }

        val g3 = results.count { it.isValid && it.midiNoteNumber == G3_MIDI }
        Log.i(TAG, "gain=$QUIET_DB dB  heard=${results.count { it.isValid }}/${results.size}  G3=$g3")
        assertTrue("Expected the quiet G3 to be heard, got $g3 of ${results.size} frames", g3 > results.size / 3)
    }
}
