package net.exent.perfectpitch.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pitch marker shows how far off a played note is by moving across the strings. */
class ViolinPitchMarkerTest {

    // 33 slots of 10 px: six either side of the four strings, seven between each pair.
    private val boardWidth = 330f

    private fun markerX(expectedCenterX: Float, semitonesOff: Int): Float =
        violinPitchMarkerX(expectedCenterX, boardWidth, semitonesOff)

    @Test
    fun theStringsSitSevenSlotsApartWithSixSlotsEitherSide() {
        // 60 = six slots in from the left, 70 = the seven slots between strings, 330 - 270 = six in.
        assertEquals(60f, violinStringX(0, boardWidth), 0.01f)
        assertEquals(130f, violinStringX(1, boardWidth), 0.01f)
        assertEquals(200f, violinStringX(2, boardWidth), 0.01f)
        assertEquals(270f, violinStringX(3, boardWidth), 0.01f)
    }

    @Test
    fun aNoteOnPitchSitsOnTheExpectedString() {
        assertEquals(130f, markerX(expectedCenterX = 130f, semitonesOff = 0), 0.01f)
    }

    @Test
    fun aStringPlayedInsteadOfItsNeighbourSitsOnThatString() {
        // Expected D (string line at 130), played open G: a fifth below, one string gap of 7 slots.
        assertEquals(60f, markerX(expectedCenterX = 130f, semitonesOff = -7), 0.01f)
        assertEquals(200f, markerX(expectedCenterX = 130f, semitonesOff = 7), 0.01f)
    }

    @Test
    fun theSixSemitonesBetweenStringsAreTheSixSlotsBetweenThemLines() {
        assertEquals(120f, markerX(expectedCenterX = 130f, semitonesOff = -1), 0.01f)
        assertEquals(190f, markerX(expectedCenterX = 130f, semitonesOff = 6), 0.01f)
    }

    @Test
    fun aPitchFurtherOffStaysOnTheBoard() {
        assertEquals(5f, markerX(expectedCenterX = 130f, semitonesOff = -20), 0.01f)
        assertEquals(325f, markerX(expectedCenterX = 130f, semitonesOff = 20), 0.01f)
    }
}
