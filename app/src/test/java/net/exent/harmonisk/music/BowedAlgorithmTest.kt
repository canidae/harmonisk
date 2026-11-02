package net.exent.harmonisk.music

import net.exent.harmonisk.midi.KeySignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BowedAlgorithmTest {

    private lateinit var algorithm: BowedAlgorithm

    @Before
    fun setUp() {
        algorithm = BowedAlgorithm()
    }

    private fun violinPosition(midi: Int, key: KeySignature? = null, preferOpenStrings: Boolean = true) =
        algorithm.findRecommendedPosition(Instrument.VIOLIN, midi, null, key, preferOpenStrings)

    @Test
    fun `open string preferred with no context`() {
        assertEquals(BowedPosition(2, 0), violinPosition(69))
        assertEquals(BowedPosition(1, 0), violinPosition(62))
        assertEquals(BowedPosition(3, 0), violinPosition(76))
        assertEquals(BowedPosition(0, 0), violinPosition(55))
    }

    @Test
    fun `each instrument uses its own open strings`() {
        val viola = Instrument.VIOLA
        assertEquals(BowedPosition(0, 0), algorithm.findRecommendedPosition(viola, 48, null))
        assertEquals(BowedPosition(1, 0), algorithm.findRecommendedPosition(viola, 55, null))
        assertEquals(BowedPosition(2, 0), algorithm.findRecommendedPosition(viola, 62, null))
        assertEquals(BowedPosition(3, 0), algorithm.findRecommendedPosition(viola, 69, null))

        val cello = Instrument.CELLO
        assertEquals(BowedPosition(0, 0), algorithm.findRecommendedPosition(cello, 36, null))
        assertEquals(BowedPosition(1, 0), algorithm.findRecommendedPosition(cello, 43, null))
        assertEquals(BowedPosition(2, 0), algorithm.findRecommendedPosition(cello, 50, null))
        assertEquals(BowedPosition(3, 0), algorithm.findRecommendedPosition(cello, 57, null))
    }

    @Test
    fun `cello uses its own first position fingering`() {
        assertEquals(BowedPosition(0, 1), algorithm.findRecommendedPosition(Instrument.CELLO, 38, null))
        assertEquals(BowedPosition(0, 2), algorithm.findRecommendedPosition(Instrument.CELLO, 39, null))
        assertEquals(BowedPosition(0, 3), algorithm.findRecommendedPosition(Instrument.CELLO, 40, null))
        assertEquals(BowedPosition(0, 4), algorithm.findRecommendedPosition(Instrument.CELLO, 41, null))
        assertEquals(BowedPosition(1, 0), algorithm.findRecommendedPosition(Instrument.CELLO, 43, null))
    }

    @Test
    fun `pinky finger penalized when open string alternative exists`() {
        assertEquals(BowedPosition(2, 0), violinPosition(69))
        assertEquals(BowedPosition(3, 0), violinPosition(76))
    }

    @Test
    fun `same string continuity is preferred`() {
        assertEquals(
            BowedPosition(2, 1),
            algorithm.findRecommendedPosition(Instrument.VIOLIN, 71, BowedPosition(2, 0))
        )
        assertEquals(
            BowedPosition(0, 2),
            algorithm.findRecommendedPosition(Instrument.VIOLIN, 59, BowedPosition(0, 1))
        )
    }

    @Test
    fun `string crossing costs more than finger movement`() {
        val pos = algorithm.findRecommendedPosition(Instrument.VIOLIN, 62, BowedPosition(2, 1))
        assertNotNull(pos)
        assertEquals(BowedPosition(1, 0), pos)
    }

    @Test
    fun `chromatic notes map to low and high finger variants`() {
        assertEquals(BowedPosition(0, 3), violinPosition(60))
        assertEquals(BowedPosition(0, 3), violinPosition(61, KeySignature(2, false)))
        assertEquals(BowedPosition(1, 2), violinPosition(65))
        assertEquals(BowedPosition(1, 2), violinPosition(66))
    }

    @Test
    fun `flat spellings below the next open string use the low fourth finger`() {
        val ebMajor = KeySignature(-3, false)
        assertEquals(BowedPosition(0, 4), violinPosition(61, ebMajor)) // Db
        assertEquals(BowedPosition(1, 4), violinPosition(68, ebMajor)) // Ab
        assertEquals(BowedPosition(2, 4), violinPosition(75, ebMajor)) // Eb
        assertEquals(BowedPosition(3, 4), violinPosition(82, ebMajor)) // Bb
        assertEquals(-1, BowedAlgorithm.fingerOffsetDeviation(68, 62, 4, Instrument.VIOLIN))
    }

    @Test
    fun `sharp spellings below the next open string keep the high third finger`() {
        assertEquals(BowedPosition(0, 3), violinPosition(61, KeySignature(2, false))) // C# in D major
        assertEquals(BowedPosition(1, 3), violinPosition(68, KeySignature(2, false))) // G# in D major
        assertEquals(BowedPosition(2, 3), violinPosition(75, KeySignature(4, false))) // D# in E major
        assertEquals(BowedPosition(3, 3), violinPosition(82, KeySignature(5, false))) // A# in B major
    }

    @Test
    fun `without a key signature the low fourth finger is preferred`() {
        assertEquals(BowedPosition(2, 4), violinPosition(75))
        assertEquals(BowedPosition(1, 4), violinPosition(68))
    }

    @Test
    fun `every instrument resolves its full first position range`() {
        for (instrument in Instrument.entries) {
            val highestOffset = if (instrument == Instrument.CELLO) 5 else 7
            for (open in instrument.tuning) {
                for (midi in open..open + highestOffset) {
                    assertNotNull(
                        "${instrument.name} MIDI $midi should be playable in first position",
                        algorithm.findRecommendedPosition(instrument, midi, null)
                    )
                }
            }
        }
    }

    @Test
    fun `finger placements stay within one semitone of the natural line`() {
        for (midi in 55..83) {
            val pos = algorithm.findRecommendedPosition(Instrument.VIOLIN, midi, null) ?: continue
            val deviation = BowedAlgorithm.fingerOffsetDeviation(midi, 55 + pos.stringIndex * 7, pos.finger, Instrument.VIOLIN) ?: 0
            assertTrue("MIDI $midi deviation $deviation out of range", deviation in -1..1)
        }
        assertEquals(1, BowedAlgorithm.fingerOffsetDeviation(61, 55, 3, Instrument.VIOLIN))
        assertEquals(1, BowedAlgorithm.fingerOffsetDeviation(75, 69, 3, Instrument.VIOLIN))
    }

    @Test
    fun `out of first position range returns null`() {
        assertNull(violinPosition(54))
        assertNull(violinPosition(84))
        assertNull(algorithm.findRecommendedPosition(Instrument.CELLO, 35, null))
        assertNull(algorithm.findRecommendedPosition(Instrument.CELLO, 42, null))
        assertNull(algorithm.findRecommendedPosition(Instrument.CELLO, 63, null))
        assertNull(algorithm.findRecommendedPosition(Instrument.CELLO, 65, null))
        assertNull(algorithm.findRecommendedPosition(Instrument.VIOLA, 47, null))
        assertNull(algorithm.findRecommendedPosition(Instrument.VIOLA, 77, null))
        assertNull(violinPosition(0))
        assertNull(violinPosition(127))
    }

    @Test
    fun `preferring open strings forces every boundary note open`() {
        val boundary = listOf(62 to 1, 69 to 2, 76 to 3)
        for ((midi, stringIndex) in boundary) {
            assertEquals(
                BowedPosition(stringIndex, 0),
                algorithm.findRecommendedPosition(Instrument.VIOLIN, midi, BowedPosition(1, 4))
            )
        }
        assertEquals(
            listOf(BowedPosition(1, 0), BowedPosition(2, 0), BowedPosition(3, 0)),
            algorithm.recommendPositions(Instrument.VIOLIN, listOf(62, 69, 76))
        )
    }

    @Test
    fun `avoiding open strings puts every boundary note on the previous string`() {
        val boundary = listOf(62 to 0, 69 to 1, 76 to 2)
        for ((midi, stringIndex) in boundary) {
            assertEquals(
                BowedPosition(stringIndex, 4),
                algorithm.findRecommendedPosition(Instrument.VIOLIN, midi, null, preferOpenStrings = false)
            )
        }
        assertEquals(
            listOf(BowedPosition(0, 4), BowedPosition(1, 4), BowedPosition(2, 4)),
            algorithm.recommendPositions(Instrument.VIOLIN, listOf(62, 69, 76), preferOpenStrings = false)
        )
    }

    @Test
    fun `every first position note resolves without open strings too`() {
        for (instrument in Instrument.entries) {
            val highestOffset = if (instrument == Instrument.CELLO) 5 else 7
            for (open in instrument.tuning) {
                for (midi in open..open + highestOffset) {
                    assertNotNull(
                        "${instrument.name} MIDI $midi should be playable in first position",
                        algorithm.findRecommendedPosition(instrument, midi, null, preferOpenStrings = false)
                    )
                }
            }
        }
    }

    @Test
    fun `sequence keeps fingerings smooth and considers upcoming notes`() {
        val positions = algorithm.recommendPositions(Instrument.VIOLIN, listOf(69, 71, 73))
        assertEquals(BowedPosition(2, 0), positions[0])
        assertEquals(BowedPosition(2, 1), positions[1])
        assertEquals(BowedPosition(2, 2), positions[2])
    }

    @Test
    fun `sequence chooses open string over shift when equal`() {
        val positions = algorithm.recommendPositions(Instrument.VIOLIN, listOf(69, 62))
        assertEquals(BowedPosition(2, 0), positions[0])
        assertEquals(BowedPosition(1, 0), positions[1])
    }

    @Test
    fun `out of range notes map to null without breaking neighbours`() {
        val positions = algorithm.recommendPositions(Instrument.VIOLIN, listOf(71, 84, 69))
        assertEquals(BowedPosition(2, 1), positions[0])
        assertNull(positions[1])
        assertEquals(BowedPosition(2, 0), positions[2])
    }

    @Test
    fun `empty sequence returns empty`() {
        assertTrue(algorithm.recommendPositions(Instrument.VIOLIN, emptyList()).isEmpty())
    }

    @Test
    fun `all out of range returns all null`() {
        assertEquals(
            listOf<BowedPosition?>(null, null),
            algorithm.recommendPositions(Instrument.VIOLIN, listOf(30, 100))
        )
    }

    @Test
    fun `default finger offsets are the natural placements`() {
        assertEquals(0, BowedAlgorithm.defaultFingerOffset(Instrument.VIOLIN, 0))
        assertEquals(2, BowedAlgorithm.defaultFingerOffset(Instrument.VIOLIN, 1))
        assertEquals(4, BowedAlgorithm.defaultFingerOffset(Instrument.VIOLIN, 2))
        assertEquals(5, BowedAlgorithm.defaultFingerOffset(Instrument.VIOLIN, 3))
        assertEquals(7, BowedAlgorithm.defaultFingerOffset(Instrument.VIOLIN, 4))
        assertEquals(3, BowedAlgorithm.defaultFingerOffset(Instrument.CELLO, 2))
        assertEquals(4, BowedAlgorithm.defaultFingerOffset(Instrument.CELLO, 3))
        assertEquals(5, BowedAlgorithm.defaultFingerOffset(Instrument.CELLO, 4))
        assertNull(BowedAlgorithm.defaultFingerOffset(Instrument.VIOLIN, 5))
        assertNull(BowedAlgorithm.defaultFingerOffset(Instrument.VIOLIN, -1))
    }

    @Test
    fun `natural notes have zero deviation from the line`() {
        assertEquals(0, BowedAlgorithm.fingerOffsetDeviation(57, 55, 1, Instrument.VIOLIN))
        assertEquals(0, BowedAlgorithm.fingerOffsetDeviation(59, 55, 2, Instrument.VIOLIN))
        assertEquals(0, BowedAlgorithm.fingerOffsetDeviation(60, 55, 3, Instrument.VIOLIN))
        assertEquals(0, BowedAlgorithm.fingerOffsetDeviation(55, 55, 0, Instrument.VIOLIN))
        assertEquals(0, BowedAlgorithm.fingerOffsetDeviation(62, 55, 4, Instrument.VIOLIN))
    }

    @Test
    fun `low finger variants deviate from the natural line`() {
        assertEquals(-1, BowedAlgorithm.fingerOffsetDeviation(56, 55, 1, Instrument.VIOLIN))
        assertEquals(-1, BowedAlgorithm.fingerOffsetDeviation(58, 55, 2, Instrument.VIOLIN))
        assertEquals(0, BowedAlgorithm.fingerOffsetDeviation(60, 55, 3, Instrument.VIOLIN))
    }
}
