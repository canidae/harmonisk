package net.exent.harmonisk.audio

import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchRangeTest {

    private val referenceA4 = 442f

    @Test
    fun `the low cutoff covers two notes below the lowest open string`() {
        assertEquals(53, lowestMidi(Instrument.VIOLIN))
        assertEquals(46, lowestMidi(Instrument.VIOLA))
        assertEquals(34, lowestMidi(Instrument.CELLO))
    }

    @Test
    fun `an octave error below the open string is outside the range`() {
        for (instrument in Instrument.entries) {
            val floor = PitchProcessor.lowestDetectableFrequency(instrument, referenceA4)
            assertFalse(
                "$instrument",
                NoteUtils.midiToFrequency(instrument.tuning.first() - 12, referenceA4) >= floor
            )
        }
    }

    @Test
    fun `a flat open string stays inside the range`() {
        for (instrument in Instrument.entries) {
            val floor = PitchProcessor.lowestDetectableFrequency(instrument, referenceA4)
            assertTrue(
                "$instrument",
                NoteUtils.midiToFrequency(instrument.tuning.first(), 438f) >= floor
            )
        }
    }

    @Test
    fun `the high cutoff covers two notes above the highest playable note`() {
        assertEquals(102, highestMidi(Instrument.VIOLIN))
        assertEquals(95, highestMidi(Instrument.VIOLA))
        assertEquals(86, highestMidi(Instrument.CELLO))
    }

    @Test
    fun `notes above the high cutoff are outside the range`() {
        for (instrument in Instrument.entries) {
            val ceiling = PitchProcessor.highestDetectableFrequency(instrument, referenceA4)
            assertTrue(
                "$instrument",
                NoteUtils.midiToFrequency(instrument.highestPlayableMidi, referenceA4) <= ceiling + 0.01f
            )
            assertFalse(
                "$instrument",
                NoteUtils.midiToFrequency(highestMidi(instrument) + 1, referenceA4) <= ceiling
            )
        }
    }

    private fun lowestMidi(instrument: Instrument): Int {
        val floor = PitchProcessor.lowestDetectableFrequency(instrument, referenceA4)
        var midi = instrument.tuning.first()
        while (NoteUtils.midiToFrequency(midi - 1, referenceA4) >= floor - 0.01f) midi--
        return midi
    }

    private fun highestMidi(instrument: Instrument): Int {
        val ceiling = PitchProcessor.highestDetectableFrequency(instrument, referenceA4)
        var midi = instrument.highestPlayableMidi
        while (NoteUtils.midiToFrequency(midi + 1, referenceA4) <= ceiling + 0.01f) midi++
        return midi
    }
}
