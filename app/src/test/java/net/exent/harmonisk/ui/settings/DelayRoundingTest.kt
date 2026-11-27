package net.exent.harmonisk.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DelayRoundingTest {

    @Test
    fun `rounds to the nearest slider step`() {
        assertEquals(0, roundToStep(0))
        assertEquals(0, roundToStep(12))
        assertEquals(25, roundToStep(13))
        assertEquals(100, roundToStep(100))
        assertEquals(350, roundToStep(348))
    }
}
