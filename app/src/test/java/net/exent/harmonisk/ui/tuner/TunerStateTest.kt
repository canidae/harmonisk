package net.exent.harmonisk.ui.tuner

import net.exent.harmonisk.music.PitchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class TunerStateTest {

    private fun state(midi: Int, concertPitchA4: Float) = TunerState(
        displayedResult = PitchResult(0f, "", 0, 0f, midi, false),
        concertPitchA4 = concertPitchA4
    )

    @Test
    fun `target frequency follows the nearest note`() {
        assertEquals(442f, state(69, 442f).targetFrequency, 0.01f)
        assertEquals(221f, state(57, 442f).targetFrequency, 0.01f)
        assertEquals(248.1f, state(59, 442f).targetFrequency, 0.1f)
    }

    @Test
    fun `target frequency follows the reference pitch`() {
        assertEquals(415f, state(69, 415f).targetFrequency, 0.01f)
    }

    @Test
    fun `target frequency is zero without a note`() {
        assertEquals(0f, TunerState().targetFrequency, 0f)
    }
}
