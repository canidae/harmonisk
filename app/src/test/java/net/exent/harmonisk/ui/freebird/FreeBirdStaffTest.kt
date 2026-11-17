package net.exent.harmonisk.ui.freebird

import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeBirdStaffTest {

    @Test
    fun `diatonic steps match the grand staff`() {
        assertEquals(32, diatonic(55))
        assertEquals(35, diatonic(60))
        assertEquals(36, diatonic(62))
        assertEquals(37, diatonic(64))
        assertEquals(40, diatonic(69))
        assertEquals(44, diatonic(76))
        assertEquals(48, diatonic(83))
    }

    @Test
    fun `bird pitch follows the diatonic staff position`() {
        val layout = FreeBirdStaffLayout(step = 10f, middleC = 500f, clefX = 0f, staffEnd = 100f)
        assertEquals(500f, freeBirdPitchY(layout, pitch(60), 1_000f), 0.01f)
        assertEquals(490f, freeBirdPitchY(layout, pitch(62), 1_000f), 0.01f)
        assertEquals(480f, freeBirdPitchY(layout, pitch(64), 1_000f), 0.01f)
        assertEquals(450f, freeBirdPitchY(layout, pitch(69), 1_000f), 0.01f)
    }

    @Test
    fun `bird pitch follows cents within a step`() {
        val layout = FreeBirdStaffLayout(step = 10f, middleC = 500f, clefX = 0f, staffEnd = 100f)
        assertEquals(497.5f, freeBirdPitchY(layout, pitch(60, 50f), 1_000f), 0.01f)
        assertEquals(502.5f, freeBirdPitchY(layout, pitch(60, -50f), 1_000f), 0.01f)
    }

    @Test
    fun `violin level ranges grow with the available fingers`() {
        assertEquals(55..76, levelRange(Instrument.VIOLIN, 1))
        assertEquals(57..78, levelRange(Instrument.VIOLIN, 2))
        assertEquals(62..83, levelRange(Instrument.VIOLIN, 5))
        assertEquals(55..83, levelRange(Instrument.VIOLIN, 99))
    }

    @Test
    fun `cello level ranges include the low open strings`() {
        assertEquals(36..57, levelRange(Instrument.CELLO, 1))
        assertEquals(41..62, levelRange(Instrument.CELLO, 5))
        assertEquals(36..62, levelRange(Instrument.CELLO, 99))
    }

    private fun pitch(midi: Int, cents: Float = 0f) = PitchResult(
        frequency = 440f,
        noteName = "-",
        octave = 4,
        centsOff = cents,
        midiNoteNumber = midi,
        isSilent = false
    )
}
