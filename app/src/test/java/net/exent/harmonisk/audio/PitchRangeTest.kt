package net.exent.harmonisk.audio

import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchRangeTest {

    @Test
    fun `a standard tuned open G is inside the violin range`() {
        val floor = PitchProcessor.lowestDetectableFrequency(Instrument.VIOLIN, 442f)
        assertTrue(NoteUtils.midiToFrequency(55, 440f) >= floor)
    }

    @Test
    fun `an octave error below the open G is outside the violin range`() {
        val floor = PitchProcessor.lowestDetectableFrequency(Instrument.VIOLIN, 442f)
        assertFalse(NoteUtils.midiToFrequency(43, 442f) >= floor)
    }

    @Test
    fun `a flat open string stays inside the cello range`() {
        val floor = PitchProcessor.lowestDetectableFrequency(Instrument.CELLO, 442f)
        assertTrue(NoteUtils.midiToFrequency(36, 438f) >= floor)
        assertFalse(NoteUtils.midiToFrequency(24, 442f) >= floor)
    }
}
