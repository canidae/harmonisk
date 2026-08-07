package net.exent.perfectpitch.ui.melody

import net.exent.perfectpitch.midi.MelodyTrack
import net.exent.perfectpitch.midi.NoteEvent
import net.exent.perfectpitch.midi.TempoEvent
import net.exent.perfectpitch.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class MelodyTimelineTest {

    private val track = MelodyTrack(
        name = "test",
        notes = listOf(
            NoteEvent(60, 100, 0L, 480L),      // 0.0s - 0.5s
            NoteEvent(62, 100, 480L, 480L),    // 0.5s - 1.0s
            NoteEvent(64, 100, 960L, 480L),    // 1.0s - 1.5s
            NoteEvent(65, 100, 1920L, 480L)    // 2.0s - 2.5s
        ),
        tempoChanges = listOf(TempoEvent(0L, 500_000L)),
        timeSignatures = listOf(TimeSignatureEvent(0L, 4, 4)),
        ticksPerQuarterNote = 480
    )

    @Test
    fun `anchors to the active note start`() {
        assertEquals(0.0, nearestNoteStartSeconds(track, 0.2), 0.001)
        assertEquals(1.0, nearestNoteStartSeconds(track, 1.2), 0.001)
    }

    @Test
    fun `anchors to the next note when between notes`() {
        assertEquals(2.0, nearestNoteStartSeconds(track, 1.6), 0.001)
    }

    @Test
    fun `anchors to the first note before the melody starts`() {
        assertEquals(0.0, nearestNoteStartSeconds(track, -0.5), 0.001)
    }

    @Test
    fun `anchors to a note boundary exactly`() {
        assertEquals(0.5, nearestNoteStartSeconds(track, 0.5), 0.001)
    }

    @Test
    fun `returns the view time past the last note`() {
        assertEquals(10.0, nearestNoteStartSeconds(track, 10.0), 0.001)
    }

    @Test
    fun `empty track returns the view time`() {
        val empty = MelodyTrack(
            name = "empty",
            notes = emptyList(),
            tempoChanges = track.tempoChanges,
            timeSignatures = track.timeSignatures,
            ticksPerQuarterNote = track.ticksPerQuarterNote
        )
        assertEquals(3.0, nearestNoteStartSeconds(empty, 3.0), 0.001)
    }
}
