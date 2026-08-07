package net.exent.perfectpitch.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ViolinAlgorithmTest {

    private lateinit var algorithm: ViolinAlgorithm

    @Before
    fun setUp() {
        algorithm = ViolinAlgorithm()
    }

    @Test
    fun `open string preferred with no context`() {
        assertEquals(ViolinPosition(ViolinString.A, 0), algorithm.findRecommendedPosition(69, null))
        assertEquals(ViolinPosition(ViolinString.D, 0), algorithm.findRecommendedPosition(62, null))
        assertEquals(ViolinPosition(ViolinString.E, 0), algorithm.findRecommendedPosition(76, null))
        assertEquals(ViolinPosition(ViolinString.G, 0), algorithm.findRecommendedPosition(55, null))
    }

    @Test
    fun `pinky finger penalized when open string alternative exists`() {
        // A4 is open on A, but also 4th finger on D
        assertEquals(ViolinPosition(ViolinString.A, 0), algorithm.findRecommendedPosition(69, null))
        // E5 is open on E, but also 4th finger on A
        assertEquals(ViolinPosition(ViolinString.E, 0), algorithm.findRecommendedPosition(76, null))
    }

    @Test
    fun `same string continuity is preferred`() {
        assertEquals(ViolinPosition(ViolinString.A, 1), algorithm.findRecommendedPosition(71, ViolinPosition(ViolinString.A, 0)))
        assertEquals(ViolinPosition(ViolinString.G, 2), algorithm.findRecommendedPosition(59, ViolinPosition(ViolinString.G, 1)))
    }

    @Test
    fun `string crossing costs more than finger movement`() {
        // D4 after A4: crossing to D string (distance 3) loses to staying near A
        val pos = algorithm.findRecommendedPosition(62, ViolinPosition(ViolinString.A, 1))
        assertNotNull(pos)
        // Both candidates cost the same distance-wise from A1; open-string bias should win
        assertEquals(ViolinPosition(ViolinString.D, 0), pos)
    }

    @Test
    fun `chromatic notes map to high or low finger variants`() {
        // C4 = low 3rd finger on G, C#4 = high 3rd finger on G
        assertEquals(ViolinPosition(ViolinString.G, 3), algorithm.findRecommendedPosition(60, null))
        assertEquals(ViolinPosition(ViolinString.G, 3), algorithm.findRecommendedPosition(61, null))
        // F natural = low 2nd on D, F# = normal 2nd on D
        assertEquals(ViolinPosition(ViolinString.D, 2), algorithm.findRecommendedPosition(65, null))
        assertEquals(ViolinPosition(ViolinString.D, 2), algorithm.findRecommendedPosition(66, null))
    }

    @Test
    fun `every first position note resolves to a fingering`() {
        for (midi in 55..83) {
            assertNotNull("MIDI $midi should be playable in first position", algorithm.findRecommendedPosition(midi, null))
        }
    }

    @Test
    fun `out of first position range returns null`() {
        assertNull(algorithm.findRecommendedPosition(54, null)) // below G3
        assertNull(algorithm.findRecommendedPosition(84, null)) // above B5
        assertNull(algorithm.findRecommendedPosition(0, null))
        assertNull(algorithm.findRecommendedPosition(127, null))
    }

    @Test
    fun `sequence keeps fingerings smooth and considers upcoming notes`() {
        val positions = algorithm.recommendPositions(listOf(69, 71, 73))
        // A4 open -> B4 1st -> C#5 2nd, all on the A string
        assertEquals(ViolinPosition(ViolinString.A, 0), positions[0])
        assertEquals(ViolinPosition(ViolinString.A, 1), positions[1])
        assertEquals(ViolinPosition(ViolinString.A, 2), positions[2])
    }

    @Test
    fun `sequence chooses open string over shift when equal`() {
        val positions = algorithm.recommendPositions(listOf(69, 62))
        assertEquals(ViolinPosition(ViolinString.A, 0), positions[0])
        assertEquals(ViolinPosition(ViolinString.D, 0), positions[1])
    }

    @Test
    fun `out of range notes map to null without breaking neighbours`() {
        val positions = algorithm.recommendPositions(listOf(71, 84, 69))
        assertEquals(ViolinPosition(ViolinString.A, 1), positions[0])
        assertNull(positions[1])
        assertEquals(ViolinPosition(ViolinString.A, 0), positions[2])
    }

    @Test
    fun `empty sequence returns empty`() {
        assertTrue(algorithm.recommendPositions(emptyList()).isEmpty())
    }

    @Test
    fun `all out of range returns all null`() {
        assertEquals(listOf<ViolinPosition?>(null, null), algorithm.recommendPositions(listOf(30, 100)))
    }

    @Test
    fun `nearest string for out of range notes`() {
        assertEquals(ViolinString.G, ViolinAlgorithm.nearestString(54))
        assertEquals(ViolinString.E, ViolinAlgorithm.nearestString(84))
        assertEquals(ViolinString.G, ViolinAlgorithm.nearestString(0))
        assertEquals(ViolinString.E, ViolinAlgorithm.nearestString(127))
    }

    @Test
    fun `default finger offsets are the natural placements`() {
        assertEquals(0, ViolinAlgorithm.defaultFingerOffset(0))
        assertEquals(2, ViolinAlgorithm.defaultFingerOffset(1))
        assertEquals(4, ViolinAlgorithm.defaultFingerOffset(2))
        assertEquals(5, ViolinAlgorithm.defaultFingerOffset(3))
        assertEquals(7, ViolinAlgorithm.defaultFingerOffset(4))
        assertNull(ViolinAlgorithm.defaultFingerOffset(5))
        assertNull(ViolinAlgorithm.defaultFingerOffset(-1))
    }

    @Test
    fun `natural notes have zero deviation from the line`() {
        assertEquals(0, ViolinAlgorithm.fingerOffsetDeviation(57, ViolinString.G, 1)) // A3, 1st finger high
        assertEquals(0, ViolinAlgorithm.fingerOffsetDeviation(59, ViolinString.G, 2)) // B3, 2nd finger high
        assertEquals(0, ViolinAlgorithm.fingerOffsetDeviation(60, ViolinString.G, 3)) // C4, 3rd finger low
        assertEquals(0, ViolinAlgorithm.fingerOffsetDeviation(55, ViolinString.G, 0)) // G3 open
        assertEquals(0, ViolinAlgorithm.fingerOffsetDeviation(62, ViolinString.G, 4)) // D4, 4th finger
    }

    @Test
    fun `chromatic variants deviate below or above the line`() {
        assertEquals(-1, ViolinAlgorithm.fingerOffsetDeviation(56, ViolinString.G, 1)) // G#3 low 1 -> below
        assertEquals(-1, ViolinAlgorithm.fingerOffsetDeviation(58, ViolinString.G, 2)) // A#3 low 2 -> below
        assertEquals(1, ViolinAlgorithm.fingerOffsetDeviation(61, ViolinString.G, 3)) // C#4 high 3 -> above
    }
}
