package net.exent.perfectpitch.ui.melody

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.exent.perfectpitch.data.DifficultyRepository
import net.exent.perfectpitch.data.MelodyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Opening a song stands the canvas at the top of the song's two bar count-in, and none of the
 * position a previous song left behind comes with it.
 */
@RunWith(AndroidJUnit4::class)
class MelodyReadyPositionDeviceTest {

    private companion object {
        private const val SHORTEST_MELODY = "jeg_kan_spille_fiolin"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun countInSeconds(viewModel: MelodyViewModel): Double {
        val track = viewModel.gameState.value.melodyTrack!!
        val timeSig = track.timeSignatureAt(0)
        return 2.0 * timeSig.numerator * 60.0 / track.bpmAt(0)
    }

    @Test
    fun openingASongStandsAtTheTopOfItsCountIn() {
        val viewModel = melodyViewModel(context)
        val melodies = MelodyRepository(context, DifficultyRepository(context)).getBundledMelodies()
        val first = melodies.first { it.id.contains(SHORTEST_MELODY) }
        assertTrue("the bundled melody must load", viewModel.loadMelody(first))
        viewModel.enterReadyPhase()
        assertEquals(
            "the song must open at the top of its two bar count-in",
            -countInSeconds(viewModel), viewModel.gameState.value.currentTimeSeconds, 0.01
        )

        // Move the canvas somewhere else, as a previous session would have left it.
        viewModel.seekToViewTime(4.0)
        assertTrue(
            "the canvas must have moved off the start",
            abs(viewModel.viewState.value.songTimeOffsetSeconds) > 0.001
        )

        val second = melodies.first { it.id != first.id }
        assertTrue("the next bundled melody must load", viewModel.loadMelody(second))
        viewModel.enterReadyPhase()
        assertEquals(
            "the previous session's scroll must not come with the next song",
            0.0, viewModel.viewState.value.songTimeOffsetSeconds, 0.001
        )
        assertEquals(
            "the next song must stand at the top of its own count-in",
            -countInSeconds(viewModel), viewModel.gameState.value.currentTimeSeconds, 0.01
        )
        viewModel.stop()
    }
}
