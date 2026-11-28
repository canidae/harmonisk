package net.exent.harmonisk.ui.freebird

import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FreeBirdGameTest {

    @Test
    fun `points fall with distance from the note`() {
        assertEquals(5, pointsForCents(0f))
        assertEquals(5, pointsForCents(10f))
        assertEquals(4, pointsForCents(20f))
        assertEquals(3, pointsForCents(30f))
        assertEquals(2, pointsForCents(50f))
        assertEquals(1, pointsForCents(74f))
        assertEquals(0, pointsForCents(76f))
    }

    @Test
    fun `target cents include the distance to the nearest note`() {
        assertEquals(60f, centsFromTarget(pitch(70, -40f), 69), 0.01f)
        assertEquals(-60f, centsFromTarget(pitch(68, 40f), 69), 0.01f)
        assertEquals(0f, centsFromTarget(pitch(69, 0f), 69), 0.01f)
        assertEquals(-200f, centsFromTarget(pitch(67, 0f), 69), 0.01f)
    }

    @Test
    fun `levels walk up the string one finger at a time and back`() {
        assertEquals(listOf(0), freeBirdPattern(1))
        assertEquals(listOf(0, 1, 0), freeBirdPattern(2))
        assertEquals(listOf(0, 1, 2, 1, 0), freeBirdPattern(3))
        assertEquals(listOf(0, 1, 2, 3, 2, 1, 0), freeBirdPattern(4))
        assertEquals(listOf(0, 1, 2, 3, 4, 3, 2, 1, 0), freeBirdPattern(5))
        assertEquals(listOf(0, 1, 2, 3, 4, 3, 2, 1, 0), freeBirdPattern(9))
    }

    @Test
    fun `the first level is a single open string`() {
        val phrase = freeBirdPhrase(Instrument.VIOLIN, 1, Random(3))
        assertEquals(1, phrase.size)
        assertEquals(0, phrase.first().finger)
        assertTrue(phrase.first().midi in Instrument.VIOLIN.tuning)
    }

    @Test
    fun `bowed phrases use the first position fingers`() {
        val phrase = freeBirdPhrase(Instrument.VIOLIN, 5, Random(7))
        val open = phrase.first().midi
        assertEquals(listOf(0, 1, 2, 3, 4, 3, 2, 1, 0), phrase.map { it.finger })
        assertEquals(listOf(0, 2, 4, 5, 7, 5, 4, 2, 0), phrase.map { it.midi - open })
    }

    @Test
    fun `cello phrases use the cello fingers`() {
        val phrase = freeBirdPhrase(Instrument.CELLO, 5, Random(7))
        val open = phrase.first().midi
        assertEquals(listOf(0, 1, 2, 3, 4, 3, 2, 1, 0), phrase.map { it.finger })
        assertEquals(listOf(0, 2, 3, 4, 5, 4, 3, 2, 0), phrase.map { it.midi - open })
    }

    @Test
    fun `all instruments are written in C major`() {
        assertEquals(0, FreeBirdKey.fifths)
    }

    @Test
    fun `accidentals are spelled against C major`() {
        assertEquals(FreeBirdSpelling(65, 1), freeBirdSpelling(66, FreeBirdKey))
        assertEquals(FreeBirdSpelling(60, 1), freeBirdSpelling(61, FreeBirdKey))
        assertEquals(FreeBirdSpelling(67, 1), freeBirdSpelling(68, FreeBirdKey))
        assertEquals(FreeBirdSpelling(62, 1), freeBirdSpelling(63, FreeBirdKey))
        assertEquals(FreeBirdSpelling(64, 0), freeBirdSpelling(64, FreeBirdKey))
    }

    @Test
    fun `the hit window starts at the beak and lasts 0_3 seconds`() {
        val beak = BIRD_BEAK_X + FREEBIRD_NOTE_RADIUS
        assertTrue(freeBirdInHitWindow(beak))
        assertTrue(freeBirdInHitWindow(beak - FREEBIRD_HIT_WINDOW))
        assertTrue(!freeBirdInHitWindow(beak + 0.01f))
        assertTrue(!freeBirdInHitWindow(beak - FREEBIRD_HIT_WINDOW - 0.01f))
    }

    @Test
    fun `notes fade out after the hit window closes`() {
        assertEquals(1f, freeBirdNoteAlpha(FREEBIRD_SPAWN_X), 0.001f)
        assertEquals(1f, freeBirdNoteAlpha(FREEBIRD_FADE_START_X), 0.001f)
        assertEquals(0.5f, freeBirdNoteAlpha((FREEBIRD_FADE_START_X + FREEBIRD_FADE_END_X) / 2f), 0.01f)
        assertEquals(0f, freeBirdNoteAlpha(FREEBIRD_FADE_END_X), 0.001f)
        assertEquals(0f, freeBirdNoteAlpha(FREEBIRD_FADE_END_X - 0.05f), 0.001f)
    }

    @Test
    fun `the count in is two bars before the first note`() {
        val firstNoteAt = FREEBIRD_NOTE_INTERVAL_MS + FREEBIRD_BEAK_LEAD_MS.toLong()
        assertEquals(8 * FREEBIRD_NOTE_INTERVAL_MS, firstNoteAt - freeBirdFirstBeatMs())
        assertEquals(8, FREEBIRD_COUNT_IN_BEATS)
        assertTrue(freeBirdFirstBeatMs() > 0L)
    }

    @Test
    fun `every pattern starts on a bar downbeat`() {
        val firstBeat = freeBirdFirstNoteBeatMs()
        listOf(0L to 2_000L, 1_000L to 4_000L, 2_000L to 4_000L, 3_000L to 6_000L, 4_000L to 6_000L)
            .forEach { (lastOffset, nextOffset) ->
                val next = freeBirdNextPhraseBeatMs(firstBeat + lastOffset, firstBeat)
                assertEquals(firstBeat + nextOffset, next)
                assertEquals(0L, (next - firstBeat) % FREEBIRD_BAR_MS)
            }
    }

    @Test
    fun `the fade begins when the note leaves the hit window`() {
        assertTrue(freeBirdInHitWindow(FREEBIRD_FADE_START_X))
        assertTrue(!freeBirdInHitWindow(FREEBIRD_FADE_START_X - 0.01f))
        assertTrue(FREEBIRD_FADE_END_X > 0f)
    }

    @Test
    fun `perfect hits refill the note spacing and the pattern gap`() {
        assertEquals(500L, FREEBIRD_NOTE_INTERVAL_MS)
        assertEquals(2_000L, FREEBIRD_PHRASE_GAP_MS)
        assertEquals(25_000L, timeAfterHit(25_000L - FREEBIRD_NOTE_INTERVAL_MS, FREEBIRD_NOTE_INTERVAL_MS, 5))
        assertEquals(25_000L, timeAfterHit(25_000L - FREEBIRD_PHRASE_GAP_MS, FREEBIRD_PHRASE_GAP_MS, 5))
    }

    @Test
    fun `a perfectly pitched note refills the time since the previous note`() {
        assertEquals(30_000L, timeAfterHit(28_500L, 1_500L, 5))
        assertEquals(29_400L, timeAfterHit(28_500L, 1_500L, 3))
        assertEquals(28_800L, timeAfterHit(28_500L, 1_500L, 1))
    }

    @Test
    fun `time never rises above the starting time`() {
        assertEquals(30_000L, timeAfterHit(29_000L, 9_000L, 5))
    }

    @Test
    fun `distance grows with the time flown`() {
        assertEquals(4f, freeBirdDistanceMeters(1_000), 0.001f)
        assertEquals(120f, freeBirdDistanceMeters(30_000), 0.001f)
    }

    @Test
    fun `the final frame does not add distance past the timer`() {
        assertEquals(30_000L, freeBirdFlightTimeMs(30_033L, -33L))
        assertEquals(30_000L, freeBirdFlightTimeMs(30_000L, 33L))
        assertEquals(120f, freeBirdDistanceMeters(freeBirdFlightTimeMs(30_033L, -33L)), 0.001f)
    }

    @Test
    fun `the best marker moves at the same speed as the notes`() {
        val width = 1_080f
        val atBest = freeBirdBestLineX(width, 2_510, 251f)
        assertEquals(width * BIRD_PERCH_X, atBest, 0.01f)
        val later = freeBirdBestLineX(width, 2_510, 251f + freeBirdDistanceMeters(1_000))
        val noteSpeed = (FREEBIRD_SPAWN_X - BIRD_PERCH_X) * width / (FREEBIRD_NOTE_TRAVEL_MS / 1_000f)
        assertEquals(noteSpeed, atBest - later, 0.01f)
    }

    @Test
    fun `the best marker sits ahead of the bird until the record is passed`() {
        val width = 1_080f
        val ahead = freeBirdBestLineX(width, 1_000, 90f)
        val atBest = freeBirdBestLineX(width, 1_000, 100f)
        val behind = freeBirdBestLineX(width, 1_000, 110f)

        assertTrue(ahead > atBest)
        assertEquals(width * BIRD_PERCH_X, atBest, 0.01f)
        assertTrue(behind < atBest)
    }

    private fun pitch(midi: Int, cents: Float) = PitchResult(
        frequency = 440f,
        noteName = "-",
        octave = 4,
        centsOff = cents,
        midiNoteNumber = midi,
        isSilent = false
    )
}
