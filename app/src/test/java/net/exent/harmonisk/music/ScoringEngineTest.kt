package net.exent.harmonisk.music

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
    fun `pitch within the full-score range gives 100 points`() {
        assertEquals(100f, score(3f).pitchScore, 0.01f)
    }

    @Test
    fun `pitch score starts falling just beyond the full-score range`() {
        assertEquals(100f, score(NoteUtils.FULL_PITCH_SCORE_CENTS).pitchScore, 0.01f)
        assertTrue(score(NoteUtils.FULL_PITCH_SCORE_CENTS + 0.01f).pitchScore < 100f)
    }

    @Test
    fun `pitch score falls linearly toward zero`() {
        assertEquals(87.5f, score(15f, 5).pitchScore, 0.01f)
        assertEquals(62.5f, score(25f, 5).pitchScore, 0.01f)
        assertEquals(50f, score(30f, 5).pitchScore, 0.01f)
        assertEquals(50f, score(42.5f, 3).pitchScore, 0.01f)
    }

    @Test
    fun `pitch at and beyond the acceptance limit scores zero`() {
        assertEquals(0f, score(75f, 3).pitchScore, 0.01f)
        assertEquals(0f, score(75.01f, 3).pitchScore, 0.01f)
        assertEquals(0f, score(50f, 5).pitchScore, 0.01f)
        assertEquals(0f, score(150f, 5).pitchScore, 0.01f)
    }

    @Test
    fun `perfect duration gives 100`() {
        val result = engine.scoreNote(NotePerformance(
            centsOff = 0f,
            expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        assertEquals(100f, result.durationScore, 0.01f)
    }

    @Test
    fun `half duration gives half score on the strictest level`() {
        val result = engine.scoreNote(NotePerformance(
            centsOff = 0f,
            expectedDurationMs = 500,
            actualDurationMs = 250
        ), 5)
        assertEquals(52.63f, result.durationScore, 0.01f)
    }

    @Test
    fun `level one gives full score from seventy percent of the note`() {
        val result = engine.scoreNote(NotePerformance(
            centsOff = 0f,
            expectedDurationMs = 500,
            actualDurationMs = 350
        ), 1)
        assertEquals(100f, result.durationScore, 0.01f)
    }

    @Test
    fun `level one scores below its grace ratio proportionally`() {
        val result = engine.scoreNote(NotePerformance(
            centsOff = 0f,
            expectedDurationMs = 1000,
            actualDurationMs = 350
        ), 1)
        assertEquals(50f, result.durationScore, 0.01f)
    }

    @Test
    fun `lower levels are more forgiving than higher levels`() {
        assertTrue(engine.scoreDuration(500, 1000, 1) > engine.scoreDuration(500, 1000, 2))
        assertTrue(engine.scoreDuration(500, 1000, 2) > engine.scoreDuration(500, 1000, 3))
        assertTrue(engine.scoreDuration(500, 1000, 3) > engine.scoreDuration(500, 1000, 5))
    }

    @Test
    fun `duration overshoot within tolerance gives 100`() {
        val result = engine.scoreNote(NotePerformance(
            centsOff = 0f,
            expectedDurationMs = 500,
            actualDurationMs = 525
        ))
        assertEquals(100f, result.durationScore, 0.01f)
    }

    @Test
    fun `melody score averages every note`() {
        val melodyScore = engine.calculateMelodyScore(List(10) { NoteScore(100f, 100f, 100f) })
        assertEquals(100f, melodyScore.overallScore, 0.01f)
        assertEquals(100f, melodyScore.avgPitchScore, 0.01f)
        assertEquals(100f, melodyScore.avgDurationScore, 0.01f)
    }

    @Test
    fun `melody score with mixed results`() {
        val scores = listOf(
            NoteScore(100f, 100f, 100f),
            NoteScore(75f, 80f, 83f),
            NoteScore(0f, 50f, 30f),
            NoteScore(100f, 90f, 89f)
        )
        val melodyScore = engine.calculateMelodyScore(scores)
        assertEquals(75.5f, melodyScore.overallScore, 0.01f)
        assertEquals(68.75f, melodyScore.avgPitchScore, 0.01f)
        assertEquals(80f, melodyScore.avgDurationScore, 0.01f)
    }

    @Test
    fun `empty melody score`() {
        assertEquals(MelodyScore.EMPTY, engine.calculateMelodyScore(emptyList()))
    }


    @Test
    fun `time score is full marks when the song is played at its own duration`() {
        assertEquals(1f, ScoringEngine.scoreTime(1.0, 1), 0.01f)
    }

    @Test
    fun `time score is full marks at the level 1 grace boundary`() {
        assertEquals(1f, ScoringEngine.scoreTime(2.00, 1), 0.001f)
        assertEquals(0.99f, ScoringEngine.scoreTime(2.01, 1), 0.01f)
    }

    @Test
    fun `time score is full marks at the level 2 grace boundary`() {
        assertEquals(1f, ScoringEngine.scoreTime(1.20, 2), 0.001f)
        assertEquals(0.99f, ScoringEngine.scoreTime(1.21, 2), 0.01f)
    }

    @Test
    fun `time score is full marks at the level 3 grace boundary`() {
        assertEquals(1f, ScoringEngine.scoreTime(1.05, 3), 0.001f)
        assertEquals(0.99f, ScoringEngine.scoreTime(1.06, 3), 0.01f)
    }

    @Test
    fun `time score stays full when the song is played faster than its duration`() {
        assertEquals(1f, ScoringEngine.scoreTime(0.5, 1), 0.01f)
    }

    @Test
    fun `time score falls off with the ratio beyond the grace boundary`() {
        assertEquals(1f, ScoringEngine.scoreTime(2.0, 1), 0.001f)
        assertEquals(0.6f, ScoringEngine.scoreTime(2.0, 2), 0.001f)
        assertEquals(0.525f, ScoringEngine.scoreTime(2.0, 3), 0.001f)
        assertEquals(0.4f, ScoringEngine.scoreTime(5.0, 1), 0.001f)
        assertEquals(0.24f, ScoringEngine.scoreTime(5.0, 2), 0.001f)
        assertEquals(0.21f, ScoringEngine.scoreTime(5.0, 3), 0.001f)
    }

    @Test
    fun `time score still awards points for a very long play time`() {
        assertEquals(0.2f, ScoringEngine.scoreTime(10.0, 1), 0.001f)
        assertEquals(0.12f, ScoringEngine.scoreTime(10.0, 2), 0.001f)
        assertEquals(0.105f, ScoringEngine.scoreTime(10.0, 3), 0.001f)
        assertEquals(0.0105f, ScoringEngine.scoreTime(100.0, 3), 0.0001f)
        assertTrue(ScoringEngine.scoreTime(1000.0, 3) > 0f)
    }

    @Test
    fun `time score gives the lower levels more leeway`() {
        for (ratio in listOf(1.05, 1.1, 1.2, 1.5, 4.0, 250.0)) {
            val level1 = ScoringEngine.scoreTime(ratio, 1)
            val level2 = ScoringEngine.scoreTime(ratio, 2)
            val level3 = ScoringEngine.scoreTime(ratio, 3)
            assertTrue("level 1 must not be stricter than level 2 at $ratio", level1 >= level2)
            assertTrue("level 2 must not be stricter than level 3 at $ratio", level2 >= level3)
        }
        assertTrue(ScoringEngine.scoreTime(1.3, 1) > ScoringEngine.scoreTime(1.3, 2))
        assertTrue(ScoringEngine.scoreTime(1.3, 2) > ScoringEngine.scoreTime(1.3, 3))
    }

    @Test
    fun `time score with no song duration is full marks`() {
        assertEquals(1f, ScoringEngine.scoreTime(0.0, 1), 0.01f)
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
            centsOff = 0f, expectedDurationMs = 500,
            actualDurationMs = 500
        ))
        assertEquals(100f, result.overallScore, 0.01f)
    }


    @Test
    fun `total score is 100 when every category in play is perfect`() {
        for (level in 1..5) {
            assertEquals(100f, ScoringEngine.combineScores(100f, 1f, level), 0.01f)
        }
    }

    @Test
    fun `two half marks no longer compound into a quarter`() {
        assertEquals(50f, ScoringEngine.combineScores(50f, 1f, 4), 0.01f)
        assertEquals(50f, ScoringEngine.combineScores(50f, 0.5f, 1), 0.01f)
    }

    @Test
    fun `total score averages every category in play`() {
        assertEquals(75f, ScoringEngine.combineScores(100f, 0.5f, 2), 0.01f)
        assertEquals(75f, ScoringEngine.combineScores(100f, 0.5f, 3), 0.01f)
        assertEquals(50f, ScoringEngine.combineScores(100f, 0f, 3), 0.01f)
    }

    @Test
    fun `total score ignores the song duration on the highest levels`() {
        assertEquals(100f, ScoringEngine.combineScores(100f, 0f, 4), 0.01f)
        assertEquals(40f, ScoringEngine.combineScores(40f, 1f, 5), 0.01f)
    }

    @Test
    fun `best score for a level ignores runs at other levels`() {
        val scores = listOf(1 to 100f, 2 to 87f, 3 to 95f, 2 to 50f)
        assertEquals(87f, ScoringEngine.bestScoreForLevel(scores, 2), 0.01f)
    }

    @Test
    fun `best score for a level without runs is zero`() {
        assertEquals(0f, ScoringEngine.bestScoreForLevel(listOf(1 to 100f), 2), 0.01f)
    }

    @Test
    fun `reaching the threshold on the highest level unlocks the next one`() {
        assertEquals(2, ScoringEngine.unlockedLevelAfterRun(1, 1, 90f))
        assertEquals(5, ScoringEngine.unlockedLevelAfterRun(4, 4, 100f))
    }

    @Test
    fun `a run below the threshold keeps the unlocked level`() {
        assertEquals(1, ScoringEngine.unlockedLevelAfterRun(1, 1, 89.9f))
        assertEquals(3, ScoringEngine.unlockedLevelAfterRun(3, 3, 0f))
    }

    @Test
    fun `retrying an easier unlocked level does not lose the higher level`() {
        assertEquals(4, ScoringEngine.unlockedLevelAfterRun(1, 4, 100f))
        assertEquals(4, ScoringEngine.unlockedLevelAfterRun(2, 4, 50f))
    }

    @Test
    fun `the last level cannot unlock another`() {
        assertEquals(5, ScoringEngine.unlockedLevelAfterRun(5, 5, 100f))
    }

    private fun score(centsOff: Float, level: Int = 5) = engine.scoreNote(
        NotePerformance(
            centsOff = centsOff,
            expectedDurationMs = 500,
            actualDurationMs = 500
        ),
        level
    )

}
