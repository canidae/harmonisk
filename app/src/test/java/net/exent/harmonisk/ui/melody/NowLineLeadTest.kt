package net.exent.harmonisk.ui.melody

import org.junit.Assert.assertEquals
import org.junit.Test

class NowLineLeadTest {

    private companion object {
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
