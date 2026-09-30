package net.exent.perfectpitch.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DelaySplitTest {

    @Test
    fun `rounds to the nearest slider step`() {
        assertEquals(0, roundToStep(0))
        assertEquals(0, roundToStep(12))
        assertEquals(25, roundToStep(13))
        assertEquals(100, roundToStep(100))
        assertEquals(350, roundToStep(348))
    }

    @Test
    fun `output delay is the round trip minus the input delay`() {
        val roundTrip = roundToStep(348)
        val input = roundToStep(127)
        assertEquals(225, roundToStep(roundTrip - input))
    }

    @Test
    fun `output delay never goes negative when input exceeds the round trip`() {
        val roundTrip = roundToStep(150)
        val input = roundToStep(400)
        assertEquals(0, roundToStep(roundTrip - input).coerceAtLeast(0))
    }
}
