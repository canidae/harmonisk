package com.perfectpitch.music

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
            centsOff = 5f,
            expectedDurationMs = 500,
            actualDurationMs = 500,
            timingOffsetMs = 0
        ))
        assertEquals(100f, result.pitchScore, 0.01f)
        assertEquals(PitchAccuracy.PERFECT, result.pitchAccuracy)
    }

    @Test
    fun `good pitch gives 75 points`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 20f,
            expectedDurationMs = 500,
            actualDurationMs = 500,
            timingOffsetMs = 0
        ))
        assertEquals(75f, result.pitchScore, 0.01f)
        assertEquals(PitchAccuracy.GOOD, result.pitchAccuracy)
    }

    @Test
    fun `fair pitch gives 50 points`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 69,
            centsOff = 40f,
            expectedDurationMs = 500,
            actualDurationMs = 500,
            timingOffsetMs = 0
        ))
        assertEquals(50f, result.pitchScore, 0.01f)
        assertEquals(PitchAccuracy.FAIR, result.pitchAccuracy)
    }

    @Test
    fun `missed pitch gives 0 points`() {
        val result = engine.scoreNote(NotePerformance(
            expectedMidiNote = 69,
            detectedMidiNote = 70,
            centsOff = 80f,
            expectedDurationMs = 500,
            actualDurationMs = 500,
            timingOffsetMs = 0
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
            actualDurationMs = 500,
            timingOffsetMs = 0
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
            actualDurationMs = 250,
            timingOffsetMs = 0
        ))
        assertEquals(50f, result.durationScore, 0.01f)
    }

    @Test
    fun `melody score calculation with all perfect`() {
        val scores = List(10) {
            NoteScore(100f, 100f, 100f, 100f, PitchAccuracy.PERFECT)
        }
        val melodyScore = engine.calculateMelodyScore(scores)
        assertEquals(100f, melodyScore.overallScore, 0.01f)
        assertEquals(LetterGrade.S, melodyScore.grade)
        assertEquals(10, melodyScore.notesHit)
        assertEquals(10, melodyScore.perfectNotes)
        assertEquals(10, melodyScore.longestStreak)
    }

    @Test
    fun `melody score with mixed results`() {
        val scores = listOf(
            NoteScore(100f, 100f, 100f, 100f, PitchAccuracy.PERFECT),
            NoteScore(75f, 80f, 100f, 83f, PitchAccuracy.GOOD),
            NoteScore(0f, 50f, 50f, 30f, PitchAccuracy.MISS),
            NoteScore(100f, 90f, 75f, 89f, PitchAccuracy.PERFECT)
        )
        val melodyScore = engine.calculateMelodyScore(scores)
        assertEquals(3, melodyScore.notesHit)
        assertEquals(2, melodyScore.perfectNotes)
        assertEquals(2, melodyScore.longestStreak) // first two
    }

    @Test
    fun `empty melody score`() {
        val melodyScore = engine.calculateMelodyScore(emptyList())
        assertEquals(MelodyScore.EMPTY, melodyScore)
    }

    @Test
    fun `grade boundaries are correct`() {
        val makeScores = { score: Float -> listOf(NoteScore(score, score, score, score, PitchAccuracy.PERFECT)) }

        assertEquals(LetterGrade.S, engine.calculateMelodyScore(makeScores(95f)).grade)
        assertEquals(LetterGrade.A, engine.calculateMelodyScore(makeScores(85f)).grade)
        assertEquals(LetterGrade.B, engine.calculateMelodyScore(makeScores(75f)).grade)
        assertEquals(LetterGrade.C, engine.calculateMelodyScore(makeScores(60f)).grade)
        assertEquals(LetterGrade.D, engine.calculateMelodyScore(makeScores(40f)).grade)
        assertEquals(LetterGrade.F, engine.calculateMelodyScore(makeScores(30f)).grade)
    }
}
