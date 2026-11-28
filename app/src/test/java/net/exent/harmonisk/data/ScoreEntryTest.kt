package net.exent.harmonisk.data

import kotlinx.serialization.json.Json
import net.exent.harmonisk.data.ScoreHistoryRepository.ScoreEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreEntryTest {

    @Test
    fun decodesEntriesWithRemovedLegacyFields() {
        val json = Json { ignoreUnknownKeys = true }
        val entry = json.decodeFromString<ScoreEntry>(
            """
            {
                "timestamp": 1,
                "overallScore": 80.0,
                "totalNotes": 10,
                "notesHit": 8,
                "perfectNotes": 5,
                "greatNotes": 2,
                "longestStreak": 4,
                "avgPitchScore": 82.0,
                "avgDurationScore": 78.0,
                "level": 2,
                "instrument": "VIOLIN",
                "wrongNoteCount": 2,
                "wrongNoteScore": 80.0
            }
            """.trimIndent()
        )

        assertEquals(1L, entry.timestamp)
        assertEquals(80f, entry.overallScore, 0.01f)
        assertEquals(82f, entry.avgPitchScore, 0.01f)
        assertEquals(78f, entry.avgDurationScore, 0.01f)
        assertEquals(2, entry.level)
        assertEquals("VIOLIN", entry.instrument)
    }
}
