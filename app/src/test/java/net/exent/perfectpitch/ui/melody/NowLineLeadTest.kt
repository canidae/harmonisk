package net.exent.perfectpitch.ui.melody

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How far the bars run ahead of the game clock.
 *
 * The lead exists so that a note played as a bar reaches the now line can still be scored against
 * that note once the microphone and the detector have caught up with it. A song that stops for the
 * player does not need it, and with the lead the note the player is asked to play would come to rest
 * past the now line.
 */
class NowLineLeadTest {

    private companion object {
        /** Measured input delay of the device under test. */
        const val INPUT_DELAY_MS = 100
    }

    @Test
    fun theBarsLeadTheBeatByTheInputDelayWhileTheSongRuns() {
        assertEquals(
            0.1,
            nowLineLeadSeconds(waitMode = false, phase = PlayPhase.PLAYING, inputDelayMs = INPUT_DELAY_MS),
            1e-9
        )
    }

    @Test
    fun aSongThatStopsForThePlayerDrawsTheNoteOnTheNowLine() {
        assertEquals(
            0.0,
            nowLineLeadSeconds(waitMode = true, phase = PlayPhase.PLAYING, inputDelayMs = INPUT_DELAY_MS),
            1e-9
        )
        assertEquals(
            0.0,
            nowLineLeadSeconds(waitMode = true, phase = PlayPhase.WAITING_FOR_NOTE, inputDelayMs = INPUT_DELAY_MS),
            1e-9
        )
    }

    @Test
    fun aSongThatHasNotStartedIsDrawnOnTheNowLine() {
        assertEquals(
            0.0,
            nowLineLeadSeconds(waitMode = false, phase = PlayPhase.READY, inputDelayMs = INPUT_DELAY_MS),
            1e-9
        )
    }

    @Test
    fun aCalibratedInputDelayOfZeroLeavesNothingToLeadWith() {
        assertEquals(
            0.0,
            nowLineLeadSeconds(waitMode = false, phase = PlayPhase.PLAYING, inputDelayMs = 0),
            1e-9
        )
    }
}
