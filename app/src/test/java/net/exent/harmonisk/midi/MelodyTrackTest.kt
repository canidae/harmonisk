package net.exent.harmonisk.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MelodyTrackTest {

    private val track = MelodyTrack(
        name = "test",
        notes = listOf(
            NoteEvent(60, 100, 0L, 480L),      // 0.0s - 0.5s
            NoteEvent(64, 100, 240L, 720L),    // 0.25s - 1.0s
            NoteEvent(67, 100, 480L, 480L)     // 0.5s - 1.0s
        ),
        tempoChanges = listOf(TempoEvent(0L, 500_000L)),
        timeSignatures = listOf(TimeSignatureEvent(0L, 4, 4)),
        ticksPerQuarterNote = 480
    )

    @Test
    fun `single note active mid-note`() {
        assertEquals(listOf(0), track.activeNoteIndicesAt(0.1))
    }

    @Test
    fun `overlapping chord returns all active notes`() {
        assertEquals(listOf(0, 1), track.activeNoteIndicesAt(0.3))
        assertEquals(listOf(1, 2), track.activeNoteIndicesAt(0.6))
    }

    @Test
    fun `exactly at a note boundary only the new note is active`() {
        assertEquals(listOf(1, 2), track.activeNoteIndicesAt(0.5))
    }

    @Test
    fun `no notes active between and around the melody`() {
        assertEquals(emptyList<Int>(), track.activeNoteIndicesAt(-0.1))
        assertEquals(emptyList<Int>(), track.activeNoteIndicesAt(1.2))
    }
}
