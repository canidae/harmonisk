package net.exent.perfectpitch.ui.melody

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelAndScoreTextTest {

    @Test
    fun `level alone before the song has been played`() {
        assertEquals("Lv.1", levelAndScoreText(1, null))
        assertEquals("Lv.4", levelAndScoreText(4, null))
    }

    @Test
    fun `level and score once a take has been scored`() {
        assertEquals("Lv.3  Score: 87", levelAndScoreText(3, "87"))
        assertEquals("Lv.5  Score: 100", levelAndScoreText(5, "100"))
    }
}
