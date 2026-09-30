package net.exent.perfectpitch.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** A score reads the same in the result modal and in the song list. */
class ScoreFormatTest {

    @Test
    fun roundsAScoreToTheNearestWholeNumber() {
        assertEquals("88", formatScore(87.5f))
        assertEquals("88", formatScore(87.9f))
        assertEquals("87", formatScore(87.4f))
        assertEquals("100", formatScore(100f))
        assertEquals("0", formatScore(0f))
    }
}
