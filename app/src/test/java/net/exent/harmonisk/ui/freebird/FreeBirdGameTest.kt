package net.exent.harmonisk.ui.freebird

import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeBirdGameTest {

    @Test
    fun `points fall with distance from the note`() {
        assertEquals(5, pointsForCents(0f))
        assertEquals(5, pointsForCents(10f))
        assertEquals(4, pointsForCents(20f))
        assertEquals(3, pointsForCents(30f))
        assertEquals(2, pointsForCents(50f))
        assertEquals(1, pointsForCents(74f))
        assertEquals(0, pointsForCents(76f))
    }

    @Test
    fun `target cents include the distance to the nearest note`() {
        assertEquals(60f, centsFromTarget(pitch(70, -40f), 69), 0.01f)
        assertEquals(-60f, centsFromTarget(pitch(68, 40f), 69), 0.01f)
        assertEquals(0f, centsFromTarget(pitch(69, 0f), 69), 0.01f)
        assertEquals(-200f, centsFromTarget(pitch(67, 0f), 69), 0.01f)
    }

    @Test
    fun `open strings are the first level`() {
        val notes = notesForLevel(Instrument.VIOLIN, 1)
        assertEquals(listOf(55, 62, 69, 76), notes.map { it.midi })
        assertTrue(notes.all { it.finger == 0 })
    }

    @Test
    fun `a finger level contains only that finger`() {
        val notes = notesForLevel(Instrument.VIOLIN, 2)
        assertEquals(listOf(57, 64, 71, 78), notes.map { it.midi })
        assertTrue(notes.all { it.finger == 1 })
    }

    @Test
    fun `the final level includes all fingers`() {
        val notes = notesForLevel(Instrument.VIOLIN, 6)
        assertTrue(notes.any { it.finger == 0 })
        assertTrue(notes.any { it.finger == 4 })
        assertTrue(notes.size > notesForLevel(Instrument.VIOLIN, 1).size)
    }

    private fun pitch(midi: Int, cents: Float) = PitchResult(
        frequency = 440f,
        noteName = "-",
        octave = 4,
        centsOff = cents,
        midiNoteNumber = midi,
        isSilent = false
    )
}
