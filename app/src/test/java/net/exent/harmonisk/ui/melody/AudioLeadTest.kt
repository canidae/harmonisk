package net.exent.harmonisk.ui.melody

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the app's own notes are handed over, and where a preview canvas stands.
 *
 * A note is heard one output delay after it is played, and its bar crosses the now line one input
 * delay before the song reaches it, so the app plays ahead of both. A preview has no player to wait
 * for and only stands one output delay behind the notes it schedules.
 */
class AudioLeadTest {

    @Test
    fun notesAreHandedOverTheInputAndOutputDelaysEarly() {
        assertEquals(0.375, audioLeadSeconds(inputDelayMs = 100, outputDelayMs = 275), 1e-9)
    }

    @Test
    fun anUncalibratedDeviceHandsNotesOverWithTheClock() {
        assertEquals(0.0, audioLeadSeconds(inputDelayMs = 0, outputDelayMs = 0), 1e-9)
    }

    @Test
    fun aPreviewStandingWhereANoteIsHeardShowsThatNotesTime() {
        assertEquals(4.0, previewClockSeconds(0.0, 0.0, 4.0 + 0.275, outputDelayMs = 275), 1e-9)
    }

    @Test
    fun aPreviewStartsAtTheTopOfItsCountIn() {
        assertEquals(-2.06, previewClockSeconds(0.0, 2.0, 0.0, outputDelayMs = 60), 1e-9)
    }
}
