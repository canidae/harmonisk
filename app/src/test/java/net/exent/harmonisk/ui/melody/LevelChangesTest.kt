package net.exent.harmonisk.ui.melody

import net.exent.harmonisk.data.ScoreHistoryRepository.ScoreEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelChangesTest {

    private fun run(number: Int, level: Int): ScoreEntry = ScoreEntry(
        timestamp = number.toLong(),
        overallScore = 50f,
        totalNotes = 10,
        notesHit = 5,
        perfectNotes = 5,
        greatNotes = 0,
        longestStreak = 1,
        avgPitchScore = 50f,
        avgDurationScore = 50f,
        level = level
    )

    @Test
    fun `no marker while every run was played at the same level`() {
        assertEquals(emptyList<LevelChange>(), levelChanges(listOf(run(1, 1), run(2, 1), run(3, 1))))
    }

    @Test
    fun `a marker on the first run at a new level`() {
        assertEquals(listOf(LevelChange(2, 2, 1)), levelChanges(listOf(run(1, 1), run(2, 1), run(3, 2))))
    }

    @Test
    fun `every level up is marked, a run on the same level again is not`() {
        val scores = listOf(run(1, 1), run(2, 2), run(3, 2), run(4, 3), run(5, 3), run(6, 4))
        assertEquals(
            listOf(LevelChange(1, 2, 1), LevelChange(3, 3, 2), LevelChange(5, 4, 3)),
            levelChanges(scores)
        )
    }

    @Test
    fun `going back to an older level is marked as a replay`() {
        val changes = levelChanges(listOf(run(1, 3), run(2, 1), run(3, 1), run(4, 3)))
        assertEquals(listOf(LevelChange(1, 1, 3), LevelChange(3, 3, 1)), changes)
        assertTrue(changes.first().isReplay)
        assertFalse(changes.last().isReplay)
    }

    @Test
    fun `a single run or no runs have nothing to mark`() {
        assertEquals(emptyList<LevelChange>(), levelChanges(listOf(run(1, 2))))
        assertEquals(emptyList<LevelChange>(), levelChanges(emptyList()))
    }
}
