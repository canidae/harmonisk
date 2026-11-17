package net.exent.harmonisk.ui.freebird

import net.exent.harmonisk.music.Instrument
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
    fun `violin level ranges grow with the available fingers`() {
        assertEquals(55..76, levelRange(Instrument.VIOLIN, 1))
        assertEquals(55..78, levelRange(Instrument.VIOLIN, 2))
        assertEquals(55..83, levelRange(Instrument.VIOLIN, 5))
        assertEquals(55..83, levelRange(Instrument.VIOLIN, 99))
    }

    @Test
    fun `cello level ranges include the low open strings`() {
        assertEquals(36..57, levelRange(Instrument.CELLO, 1))
        assertEquals(36..62, levelRange(Instrument.CELLO, 5))
        assertEquals(36..62, levelRange(Instrument.CELLO, 99))
    }
}
