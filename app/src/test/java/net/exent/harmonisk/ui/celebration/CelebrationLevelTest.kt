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
    fun `confetti starts as a drizzle and grows at 60 and 90 points`() {
        assertEquals(0, CelebrationLevel.confettiCount(0f, 1))
        assertEquals(20, CelebrationLevel.confettiCount(1f, 1))
        assertEquals(20, CelebrationLevel.confettiCount(59.9f, 1))
        assertEquals(45, CelebrationLevel.confettiCount(60f, 1))
        assertEquals(45, CelebrationLevel.confettiCount(89.9f, 1))
        assertEquals(90, CelebrationLevel.confettiCount(90f, 1))
        assertEquals(90, CelebrationLevel.confettiCount(100f, 1))
    }

    @Test
    fun `the song level multiplies the confetti`() {
        for (level in 1..5) {
            assertEquals(90 * level, CelebrationLevel.confettiCount(100f, level))
            assertEquals(
                90 * level,
                CelebrationLevel.ECSTATIC.toPayloads(100f, level)
                    .filterIsInstance<CelebrationPayload.Confetti>().single().count
            )
        }
        assertEquals(90, CelebrationLevel.confettiCount(100f, 0))
        assertEquals(450, CelebrationLevel.confettiCount(100f, 6))
    }

    @Test
    fun `celebration tiers stay ordered`() {
        val levels = CelebrationLevel.entries.filter { it != CelebrationLevel.NONE }
        assertEquals(levels.sortedBy { it.scoreThreshold }, levels)
        assertTrue(levels.zipWithNext().all { (lower, higher) -> lower.musicNoteCount <= higher.musicNoteCount })
    }
}
