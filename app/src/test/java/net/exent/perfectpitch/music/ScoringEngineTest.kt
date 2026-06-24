package net.exent.perfectpitch.music

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScoringEngineTest {

    private lateinit var engine: ScoringEngine

    @Before
    fun setUp() {
        engine = ScoringEngine()
    }

    @Test
    fun `perfect pitch gives 100 points`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 3f,
            expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        assertEquals(100f, result.pitchScore, 0.01f)
        assertEquals(PitchAccuracy.PERFECT, result.pitchAccuracy)
    }

    @Test
    fun `great pitch gives 80 points`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 15f,   // > PERFECT_CENTS (10) and <= GREAT_CENTS (20)
            expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        assertEquals(80f, result.pitchScore, 0.01f)
        assertEquals(PitchAccuracy.GREAT, result.pitchAccuracy)
    }

    @Test
    fun `good pitch gives 50 points`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 25f,   // > GREAT_CENTS (20) and <= GOOD_CENTS (30)
            expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        assertEquals(50f, result.pitchScore, 0.01f)
        assertEquals(PitchAccuracy.GOOD, result.pitchAccuracy)
    }

    @Test
    fun `ok pitch gives 25 points`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 40f,
            expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        assertEquals(25f, result.pitchScore, 0.01f)
        assertEquals(PitchAccuracy.OK, result.pitchAccuracy)
    }

    @Test
    fun `missed pitch gives 0 points`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 70,
            centsOff = 80f,
            expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        assertEquals(0f, result.pitchScore, 0.01f)
        assertEquals(PitchAccuracy.MISS, result.pitchAccuracy)
    }

    @Test
    fun `perfect duration gives 100`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 0f,
            expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        assertEquals(100f, result.durationScore, 0.01f)
    }

    @Test
    fun `half duration gives 50`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 0f,
            expectedDurationMs = 500,
            actualDurationMs = 250
        ))
        assertEquals(50f, result.durationScore, 0.01f)
    }

    @Test
    fun `duration at PERFECT_DURATION_RATIO boundary gives 100`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 0f,
            expectedDurationMs = 1000,
            actualDurationMs = 850
        ))
        assertEquals(100f, result.durationScore, 0.01f)
    }

    @Test
    fun `duration just below PERFECT_DURATION_RATIO gives ratio score`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 0f,
            expectedDurationMs = 1000,
            actualDurationMs = 849
        ))
        assertEquals(84.9f, result.durationScore, 0.01f)
    }

    @Test
    fun `duration overshoot within tolerance gives 100`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 0f,
            expectedDurationMs = 500,
            actualDurationMs = 588
        ))
        assertEquals(100f, result.durationScore, 0.01f)
    }

    @Test
    fun `melody score calculation with all perfect`() {
        val scores = List(10) {
            NoteScore(100f, 100f, 100f, PitchAccuracy.PERFECT)
        }
        val melodyScore = engine.calculateMelodyScore(scores)
        assertEquals(100f, melodyScore.overallScore, 0.01f)
        assertEquals(10, melodyScore.notesHit)
        assertEquals(10, melodyScore.perfectNotes)
        assertEquals(10, melodyScore.longestStreak)
    }

    @Test
    fun `melody score with mixed results`() {
        val scores = listOf(
            NoteScore(100f, 100f, 100f, PitchAccuracy.PERFECT),
            NoteScore(75f, 80f, 83f, PitchAccuracy.GREAT),
            NoteScore(0f, 50f, 30f, PitchAccuracy.MISS),
            NoteScore(100f, 90f, 89f, PitchAccuracy.PERFECT)
        )
        val melodyScore = engine.calculateMelodyScore(scores)
        assertEquals(3, melodyScore.notesHit)
        assertEquals(2, melodyScore.perfectNotes)
        assertEquals(1, melodyScore.greatNotes)
        assertEquals(2, melodyScore.longestStreak) // first two
    }

    @Test
    fun `empty melody score`() {
        val melodyScore = engine.calculateMelodyScore(emptyList())
        assertEquals(MelodyScore.EMPTY, melodyScore)
    }

    // Boundary-value tests for PitchAccuracy

    @Test
    fun `PitchAccuracy boundary at PERFECT_CENTS returns PERFECT`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = NoteUtils.PERFECT_CENTS,
            expectedDurationMs = 500, actualDurationMs = 500
        ))
        assertEquals(PitchAccuracy.PERFECT, result.pitchAccuracy)
    }

    @Test
    fun `PitchAccuracy just above PERFECT_CENTS returns GREAT`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = NoteUtils.PERFECT_CENTS + 0.01f,
            expectedDurationMs = 500, actualDurationMs = 500
        ))
        assertEquals(PitchAccuracy.GREAT, result.pitchAccuracy)
    }

    @Test
    fun `PitchAccuracy boundary at GREAT_CENTS returns GREAT`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = NoteUtils.GREAT_CENTS,
            expectedDurationMs = 500, actualDurationMs = 500
        ))
        assertEquals(PitchAccuracy.GREAT, result.pitchAccuracy)
    }

    @Test
    fun `PitchAccuracy just above GREAT_CENTS returns GOOD`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = NoteUtils.GREAT_CENTS + 0.01f,
            expectedDurationMs = 500, actualDurationMs = 500
        ))
        assertEquals(PitchAccuracy.GOOD, result.pitchAccuracy)
    }

    @Test
    fun `PitchAccuracy boundary at GOOD_CENTS returns GOOD`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = NoteUtils.GOOD_CENTS,
            expectedDurationMs = 500, actualDurationMs = 500
        ))
        assertEquals(PitchAccuracy.GOOD, result.pitchAccuracy)
    }

    @Test
    fun `PitchAccuracy just above GOOD_CENTS returns OK`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = NoteUtils.GOOD_CENTS + 0.01f,
            expectedDurationMs = 500, actualDurationMs = 500
        ))
        assertEquals(PitchAccuracy.OK, result.pitchAccuracy)
    }

    @Test
    fun `PitchAccuracy boundary at OK_CENTS returns OK`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = NoteUtils.OK_CENTS,
            expectedDurationMs = 500, actualDurationMs = 500
        ))
        assertEquals(PitchAccuracy.OK, result.pitchAccuracy)
    }

    @Test
    fun `PitchAccuracy just above OK_CENTS returns MISS`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = NoteUtils.OK_CENTS + 0.01f,
            expectedDurationMs = 500, actualDurationMs = 500
        ))
        assertEquals(PitchAccuracy.MISS, result.pitchAccuracy)
    }

    // Trailing-streak regression: a streak ending on the last note must be counted

    @Test
    fun `trailing streak at end of melody is counted`() {
        val scores = listOf(
            NoteScore(0f, 50f, 30f, PitchAccuracy.MISS),
            NoteScore(100f, 100f, 100f, PitchAccuracy.PERFECT),
            NoteScore(100f, 100f, 100f, PitchAccuracy.PERFECT),
            NoteScore(100f, 100f, 100f, PitchAccuracy.PERFECT)
        )
        val melodyScore = engine.calculateMelodyScore(scores)
        assertEquals(3, melodyScore.longestStreak)
    }

    @Test
    fun `all perfect notes streak equals note count`() {
        val noteCount = 7
        val scores = List(noteCount) { NoteScore(100f, 100f, 100f, PitchAccuracy.PERFECT) }
        val melodyScore = engine.calculateMelodyScore(scores)
        assertEquals(noteCount, melodyScore.longestStreak)
    }

    // All-miss tests

    @Test
    fun `all miss melody has zero streak and zero notes hit`() {
        val scores = List(5) { NoteScore(0f, 100f, 40f, PitchAccuracy.MISS) }
        val melodyScore = engine.calculateMelodyScore(scores)
        assertEquals(0, melodyScore.longestStreak)
        assertEquals(0, melodyScore.notesHit)
    }

    // Time scoring tests

    @Test
    fun `time score for level 1 returns 1 for ratio at full threshold`() {
        assertEquals(1f, ScoringEngine.scoreTime(3.0, 1), 0.01f)
    }

    @Test
    fun `time score for level 1 returns 0 for ratio at zero threshold`() {
        assertEquals(0f, ScoringEngine.scoreTime(5.0, 1), 0.01f)
    }

    @Test
    fun `time score for level 1 is linear between thresholds`() {
        assertEquals(0.5f, ScoringEngine.scoreTime(4.0, 1), 0.01f)
    }

    @Test
    fun `time score for level 1 returns 1 for ratio below full threshold`() {
        assertEquals(1f, ScoringEngine.scoreTime(2.0, 1), 0.01f)
    }

    @Test
    fun `time score for level 1 returns 0 for ratio above zero threshold`() {
        assertEquals(0f, ScoringEngine.scoreTime(6.0, 1), 0.01f)
    }

    @Test
    fun `time score for level 2 returns 1 for ratio at full threshold`() {
        assertEquals(1f, ScoringEngine.scoreTime(2.0, 2), 0.01f)
    }

    @Test
    fun `time score for level 2 returns 0 for ratio at zero threshold`() {
        assertEquals(0f, ScoringEngine.scoreTime(4.0, 2), 0.01f)
    }

    @Test
    fun `time score for level 2 is linear between thresholds`() {
        assertEquals(0.5f, ScoringEngine.scoreTime(3.0, 2), 0.01f)
    }

    @Test
    fun `time score for level 3 returns 1 for ratio at full threshold`() {
        assertEquals(1f, ScoringEngine.scoreTime(1.1, 3), 0.01f)
    }

    @Test
    fun `time score for level 3 returns 0 for ratio at zero threshold`() {
        assertEquals(0f, ScoringEngine.scoreTime(3.0, 3), 0.01f)
    }

    @Test
    fun `time score for level 3 is linear at midpoint`() {
        assertEquals(0.5f, ScoringEngine.scoreTime(2.05, 3), 0.01f)
    }

    @Test
    fun `time score for level 4 is always 1`() {
        assertEquals(1f, ScoringEngine.scoreTime(10.0, 4), 0.01f)
        assertEquals(1f, ScoringEngine.scoreTime(0.5, 4), 0.01f)
    }

    @Test
    fun `time score for level 5 is always 1`() {
        assertEquals(1f, ScoringEngine.scoreTime(10.0, 5), 0.01f)
        assertEquals(1f, ScoringEngine.scoreTime(0.5, 5), 0.01f)
    }

    @Test
    fun `overall score uses only pitch and duration weights`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69, detectedMidiNote = 69,
            centsOff = 0f, expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        // Perfect pitch (100) * 0.6 + perfect duration (100) * 0.4 = 100
        assertEquals(100f, result.overallScore, 0.01f)
    }
}
