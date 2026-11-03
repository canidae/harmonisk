package net.exent.harmonisk.ui.celebration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelebrationLevelTest {

    @Test
    fun `a zero score does not celebrate`() {
        assertEquals(CelebrationLevel.NONE, CelebrationLevel.fromScore(0f))
        assertEquals(CelebrationLevel.NONE, CelebrationLevel.fromScore(0.9f))
    }

    @Test
    fun `celebration follows the score bands`() {
        assertEquals(CelebrationLevel.BASE, CelebrationLevel.fromScore(1f))
        assertEquals(CelebrationLevel.BASE, CelebrationLevel.fromScore(29.9f))
        assertEquals(CelebrationLevel.MODERATE, CelebrationLevel.fromScore(30f))
        assertEquals(CelebrationLevel.STRONG, CelebrationLevel.fromScore(50f))
        assertEquals(CelebrationLevel.INTENSE, CelebrationLevel.fromScore(65f))
        assertEquals(CelebrationLevel.EXTREME, CelebrationLevel.fromScore(80f))
        assertEquals(CelebrationLevel.ECSTATIC, CelebrationLevel.fromScore(95f))
        assertEquals(CelebrationLevel.ECSTATIC, CelebrationLevel.fromScore(100f))
    }

    @Test
    fun `confetti gets more intense as the score grows`() {
        val levels = CelebrationLevel.entries.filter { it != CelebrationLevel.NONE }
        assertEquals(levels.sortedBy { it.scoreThreshold }, levels)
        assertTrue(levels.all { it.confettiCount > 0 })
        assertTrue(levels.zipWithNext().all { (lower, higher) -> lower.confettiCount < higher.confettiCount })
    }
}
