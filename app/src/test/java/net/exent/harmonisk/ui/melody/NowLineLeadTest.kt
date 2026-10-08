package net.exent.harmonisk.ui.melody

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How far the bars run ahead of the game clock.
 *
 * The lead exists so that a note played as a bar reaches the now line can still be scored against
 * that note once the microphone and the detector have caught up with it. A song that stops for the
 * player leads the same way, and comes to rest one input delay past the line, which is also the lag
 * its held time must not have taken off it again. Only a song that has not started is drawn still.
 */
class NowLineLeadTest {

    private companion object {
        /** Measured input delay of the device under test. */
        const val INPUT_DELAY_MS = 100
    }

    @Test
    fun theBarsLeadTheBeatByTheInputDelayWhileTheSongRuns() {
        assertEquals(0.1, nowLineLeadSeconds(PlayPhase.PLAYING, INPUT_DELAY_MS), 1e-9)
    }

    @Test
    fun aSongThatStopsForThePlayerStillLeadsByTheInputDelay() {
        assertEquals(0.1, nowLineLeadSeconds(PlayPhase.WAITING_FOR_NOTE, INPUT_DELAY_MS), 1e-9)
    }

    @Test
    fun aSongThatHasNotStartedIsDrawnOnTheNowLine() {
        assertEquals(0.0, nowLineLeadSeconds(PlayPhase.READY, INPUT_DELAY_MS), 1e-9)
    }

    @Test
    fun aCalibratedInputDelayOfZeroLeavesNothingToLeadWith() {
        assertEquals(0.0, nowLineLeadSeconds(PlayPhase.PLAYING, 0), 1e-9)
        assertEquals(0.0, nowLineLeadSeconds(PlayPhase.WAITING_FOR_NOTE, 0), 1e-9)
    }
}
