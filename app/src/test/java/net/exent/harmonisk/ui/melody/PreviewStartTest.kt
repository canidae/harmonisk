package net.exent.harmonisk.ui.melody

import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.midi.NoteEvent
import net.exent.harmonisk.midi.TempoEvent
import net.exent.harmonisk.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewStartTest {

    private fun track(numerator: Int) = MelodyTrack(
        name = "test",
        notes = listOf(NoteEvent(60, 100, 0L, 480L)),
        tempoChanges = listOf(TempoEvent(0L, 500_000L)),
        timeSignatures = listOf(TimeSignatureEvent(0L, numerator, 4)),
        ticksPerQuarterNote = 480
    )

    @Test
    fun `before the music starts at the top`() {
        assertEquals(0.0, previewStartSeconds(track(4), -1.0), 1e-9)
        assertEquals(0.0, previewStartSeconds(track(4), 0.0), 1e-9)
    }

    @Test
    fun `opening bars fall back to the top of the song`() {
        assertEquals(0.0, previewStartSeconds(track(4), 1.0), 1e-9)
        assertEquals(0.0, previewStartSeconds(track(4), 3.0), 1e-9)
    }

    @Test
    fun `starts one bar before the bar the pause sits in`() {
        assertEquals(2.0, previewStartSeconds(track(4), 4.5), 1e-9)
        assertEquals(2.0, previewStartSeconds(track(4), 5.9), 1e-9)
        assertEquals(2.0, previewStartSeconds(track(4), 4.0), 1e-9)
    }

    @Test
    fun `bar length follows the time signature`() {
        assertEquals(1.5, previewStartSeconds(track(3), 3.0), 1e-9)
    }
}
