package net.exent.harmonisk.ui.freeplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEventsTest {

    private fun note(startMs: Long, endMs: Long?, midi: Int = 67, id: String = "n$startMs") = PlayedNote(
        noteName = "G",
        octave = 4,
        midiNumber = midi,
        startTimeMs = startMs,
        endTimeMs = endMs,
        id = id
    )

    @Test
    fun noteThatAlreadyStartedBeforeSeekIsNotRetriggered() {
        val events = ReplayEvents.notes(listOf(note(0, 2000)), startTimeMs = 500, loopEndMs = 4000)
        assertTrue("a note already sounding at the seek point must not be started again", events.isEmpty())
    }

    @Test
    fun noteStartingExactlyAtSeekIsPlayed() {
        val events = ReplayEvents.notes(listOf(note(500, 900)), startTimeMs = 500, loopEndMs = 4000)
        assertEquals(
            listOf(
                ReplayEvent(500, 67, true),
                ReplayEvent(900, 67, false)
            ),
            events
        )
    }

    @Test
    fun noteStartingAfterSeekIsPlayed() {
        val events = ReplayEvents.notes(listOf(note(600, 900)), startTimeMs = 500, loopEndMs = 4000)
        assertEquals(
            listOf(
                ReplayEvent(600, 67, true),
                ReplayEvent(900, 67, false)
            ),
            events
        )
    }

    @Test
    fun noteEndingBeforeSeekIsSkipped() {
        val events = ReplayEvents.notes(listOf(note(100, 400)), startTimeMs = 500, loopEndMs = 4000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun noteAtOrAfterLoopEndIsSkipped() {
        val events = ReplayEvents.notes(listOf(note(4000, 4500)), startTimeMs = 500, loopEndMs = 4000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun noteEndPastLoopEndIsClampedToTheLoop() {
        val events = ReplayEvents.notes(listOf(note(600, 5000)), startTimeMs = 500, loopEndMs = 2000)
        assertEquals(
            listOf(
                ReplayEvent(600, 67, true),
                ReplayEvent(2000, 67, false)
            ),
            events
        )
    }

    @Test
    fun unfinishedNoteIsIgnored() {
        val events = ReplayEvents.notes(listOf(note(600, null)), startTimeMs = 500, loopEndMs = 4000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun eventsAreOrderedByNoteStart() {
        val events = ReplayEvents.notes(
            listOf(note(1500, 1800, id = "b"), note(600, 900, id = "a")),
            startTimeMs = 500,
            loopEndMs = 4000
        )
        assertEquals(listOf(600L, 900L, 1500L, 1800L), events.map { it.timeMs })
    }
}
