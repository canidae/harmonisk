package net.exent.harmonisk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The violin board is four blocks of semitone slots, one block per string. */
class ViolinBoardLayoutTest {

    // 29 slots of 10 px (the E string carries one more than the rest) and three 5 px gaps.
    private val gapPx = 5f
    private val boardWidth = 29 * 10f + 3 * gapPx

    private fun slotX(midi: Int): Float = violinSlotCenterX(midi, boardWidth, gapPx)

    @Test
    fun eachOpenStringSitsInTheFirstSlotOfItsBlock() {
        assertEquals(5f, slotX(55), 0.01f)   // G3, the first slot
        assertEquals(80f, slotX(62), 0.01f)  // D4, one slot after the first gap
        assertEquals(155f, slotX(69), 0.01f) // A4
        assertEquals(230f, slotX(76), 0.01f) // E5
    }

    @Test
    fun everySemitoneHasItsOwnColumnRunningLeftToRight() {
        assertEquals(15f, slotX(56), 0.01f)
        assertEquals(65f, slotX(61), 0.01f) // C#4, last slot of the G block
        assertEquals(280f, slotX(81), 0.01f)
        assertEquals(290f, slotX(82), 0.01f) // A#5
        assertEquals(300f, slotX(83), 0.01f) // B5, the E block's eighth slot
    }

    @Test
    fun theBlocksSitSevenSlotsApartAndTheFirstStartsAtTheEdge() {
        assertEquals(0f, violinBlockLeft(0, boardWidth, gapPx), 0.01f)
        assertEquals(75f, violinBlockLeft(1, boardWidth, gapPx), 0.01f)
        assertEquals(225f, violinBlockLeft(3, boardWidth, gapPx), 0.01f)
        assertEquals(10f, violinSlotWidth(boardWidth, gapPx), 0.01f)
    }

    @Test
    fun pitchesBeyondTheBoardStopAtTheOuterColumns() {
        assertEquals(5f, slotX(54), 0.01f)
        assertEquals(5f, slotX(0), 0.01f)
        assertEquals(300f, slotX(84), 0.01f)
        assertEquals(300f, slotX(127), 0.01f)
    }
}
