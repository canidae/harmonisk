package net.exent.harmonisk.music

import net.exent.harmonisk.midi.KeySignature
import net.exent.harmonisk.midi.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The beginner key should transpose melodies into instrument-friendly finger patterns. */
class BeginnerKeyTest {

    private val beginnerKey = BeginnerKey(BowedAlgorithm())

    private fun notes(vararg pitches: Int): List<NoteEvent> = pitches.mapIndexed { index, pitch ->
        NoteEvent(
            noteNumber = pitch,
            velocity = 100,
            startTick = index * 480L,
            durationTicks = 480L
        )
    }

    private fun offset(
        vararg pitches: Int,
        key: KeySignature?,
        instrument: Instrument = Instrument.VIOLIN
    ): Int = beginnerKey.offset(notes(*pitches), key, instrument)

    @Test
    fun emptyMelodyStaysPut() {
        assertEquals(0, beginnerKey.offset(emptyList(), KeySignature(0, false), Instrument.VIOLIN))
    }

    @Test
    fun cMajorScaleMovesToD() {
        val scale = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72)
        assertEquals(2, offset(*scale, key = KeySignature(0, false)))
    }

    @Test
    fun dMajorScaleStaysPut() {
        val scale = intArrayOf(62, 64, 66, 67, 69, 71, 73, 74)
        assertEquals(0, offset(*scale, key = KeySignature(2, false)))
    }

    @Test
    fun aMajorScaleStaysPut() {
        val scale = intArrayOf(69, 71, 73, 74, 76, 78, 80, 81)
        assertEquals(0, offset(*scale, key = KeySignature(3, false)))
    }

    @Test
    fun gMajorScaleMovesToD() {
        val scale = intArrayOf(67, 69, 71, 72, 74, 76, 78, 79)
        assertEquals(-5, offset(*scale, key = KeySignature(1, false)))
    }

    @Test
    fun bMinorScaleStaysPut() {
        val scale = intArrayOf(59, 61, 62, 64, 66, 67, 69, 71)
        assertEquals(0, offset(*scale, key = KeySignature(2, true)))
    }

    @Test
    fun missingKeyIsDetectedFromTheMelody() {
        val scale = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72, 60, 62, 64, 65, 67, 69, 71, 72)
        assertEquals(2, offset(*scale, key = null))
    }

    @Test
    fun lowMelodyIsRaisedIntoFirstPosition() {
        val melody = intArrayOf(49, 51, 52, 54, 56, 57, 59, 61)
        val offset = offset(*melody, key = KeySignature(-3, false))
        assertEquals(6, offset)
    }

    @Test
    fun eachInstrumentGetsAnOffsetWithinRange() {
        val melody = intArrayOf(62, 64, 66, 67, 69, 71, 73, 74)
        for (instrument in Instrument.entries) {
            assertTrue(offset(*melody, key = KeySignature(2, false), instrument = instrument) in -6..6)
        }
    }

    @Test
    fun shiftNeverExceedsASixth() {
        val melody = intArrayOf(20, 22, 24, 26, 28, 30, 32, 34)
        val offset = offset(*melody, key = KeySignature(0, false))
        assertTrue(offset in -6..6)
    }
}
