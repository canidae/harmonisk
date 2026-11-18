package net.exent.harmonisk.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreFormatTest {

    @Test
    fun roundsAScoreDownToTheNearestWholeNumber() {
        assertEquals("87", formatScore(87.5f))
        assertEquals("87", formatScore(87.9f))
        assertEquals("89", formatScore(89.9f))
        assertEquals("90", formatScore(90f))
        assertEquals("100", formatScore(100f))
        assertEquals("0", formatScore(0f))
    }
}
