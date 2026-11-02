package net.exent.harmonisk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bowed board is four blocks of semitone slots, one block per string. */
class BowedBoardLayoutTest {

    // 29 slots of 10 px (the E string carries one more than the rest) and three 5 px gaps.
    private val gapPx = 5f
    private val boardWidth = 29 * 10f + 3 * gapPx

    private fun slotX(midi: Int): Float = bowedSlotCenterX(midi, boardWidth, gapPx, 55)
    private fun closedSlotX(midi: Int): Float =
        bowedSlotCenterX(midi, boardWidth, gapPx, 55, preferOpenStrings = false)

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
    fun eachInstrumentStartsAtItsLowestOpenString() {
        assertEquals(5f, bowedSlotCenterX(48, boardWidth, gapPx, 48), 0.01f)
        assertEquals(80f, bowedSlotCenterX(55, boardWidth, gapPx, 48), 0.01f)
        assertEquals(5f, bowedSlotCenterX(36, boardWidth, gapPx, 36), 0.01f)
        assertEquals(80f, bowedSlotCenterX(43, boardWidth, gapPx, 36), 0.01f)
    }

    @Test
    fun theBlocksSitSevenSlotsApartAndTheFirstStartsAtTheEdge() {
        assertEquals(0f, bowedBlockLeft(0, boardWidth, gapPx), 0.01f)
        assertEquals(75f, bowedBlockLeft(1, boardWidth, gapPx), 0.01f)
        assertEquals(225f, bowedBlockLeft(3, boardWidth, gapPx), 0.01f)
        assertEquals(10f, bowedSlotWidth(boardWidth, gapPx), 0.01f)
    }

    @Test
    fun withoutOpenStringsTheGBlockCarriesTheExtraSlot() {
        assertEquals(5f, closedSlotX(55), 0.01f)   // G3, still the first slot
        assertEquals(75f, closedSlotX(62), 0.01f)  // D4 is the fourth finger on G, the G block's last slot
        assertEquals(90f, closedSlotX(63), 0.01f)  // D#4 starts the D block
        assertEquals(150f, closedSlotX(69), 0.01f) // A4 is the fourth finger on D, the D block's last slot
        assertEquals(225f, closedSlotX(76), 0.01f) // E5 is the fourth finger on A, the A block's last slot
        assertEquals(240f, closedSlotX(77), 0.01f) // F5 starts the E block
    }

    @Test
    fun withoutOpenStringsTheBlocksSitAtTheirOffsetBoundaries() {
        assertEquals(0f, bowedBlockLeft(0, boardWidth, gapPx, preferOpenStrings = false), 0.01f)
        assertEquals(85f, bowedBlockLeft(1, boardWidth, gapPx, preferOpenStrings = false), 0.01f)
        assertEquals(160f, bowedBlockLeft(2, boardWidth, gapPx, preferOpenStrings = false), 0.01f)
        assertEquals(235f, bowedBlockLeft(3, boardWidth, gapPx, preferOpenStrings = false), 0.01f)
    }

    @Test
    fun pitchesBeyondTheBoardStopAtTheOuterColumns() {
        assertEquals(5f, slotX(54), 0.01f)
        assertEquals(5f, slotX(0), 0.01f)
        assertEquals(300f, slotX(84), 0.01f)
        assertEquals(300f, slotX(127), 0.01f)
        assertEquals(5f, closedSlotX(54), 0.01f)
        assertEquals(300f, closedSlotX(84), 0.01f)
    }
}
