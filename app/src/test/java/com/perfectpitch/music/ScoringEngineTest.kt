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
            centsOff = 8f,
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
            centsOff = 20f,
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
