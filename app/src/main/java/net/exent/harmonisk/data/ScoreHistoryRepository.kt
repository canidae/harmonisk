package net.exent.harmonisk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.scoreHistoryStore: DataStore<Preferences> by preferencesDataStore(
    name = "score_history"
)

class ScoreHistoryRepository(
    context: Context
) {
    private val store = JsonPreferenceStore<String, List<ScoreEntry>>(
        dataStore = context.scoreHistoryStore,
        keyName = "scores_json"
    )

    @Serializable
    data class ScoreEntry(
        val timestamp: Long,
        val overallScore: Float,
        val combinedScore: Float = 0f,
        val totalNotes: Int,
        val notesHit: Int,
        val perfectNotes: Int,
        val greatNotes: Int,
        val longestStreak: Int,
        val avgPitchScore: Float,
        val avgDurationScore: Float,
        val level: Int = 1,
        val instrument: String = "PIANO",
        val wrongNoteCount: Int = 0,
        val wrongNoteScore: Float = 100f
    )

    /** Timestamp of the most recent playthrough of each melody, across instruments. */
    val lastPlayed: Flow<Map<String, Long>> = store.flow.map { scores ->
        scores.mapNotNull { (melodyId, entries) ->
            entries.maxOfOrNull { it.timestamp }?.let { melodyId to it }
        }.toMap()
    }

    fun getScores(melodyId: String, instrument: String): Flow<List<ScoreEntry>> {
        return store.flow.map {
            (it[melodyId] ?: emptyList()).filter { entry -> entry.instrument == instrument }
        }
    }

    suspend fun saveScore(melodyId: String, entry: ScoreEntry) {
        store.update(melodyId) { existing ->
            val list = existing?.toMutableList() ?: mutableListOf()
            list.add(entry)
            list
        }
    }

    suspend fun resetScores(melodyId: String, instrument: String) {
        store.update(melodyId) { existing ->
            existing?.filter { it.instrument != instrument } ?: emptyList()
        }
    }

    suspend fun resetAllScores(melodyId: String) {
        store.remove(melodyId)
    }
}
