package net.exent.perfectpitch.data

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
        val level: Int = 1
    )

    fun getScores(melodyId: String): Flow<List<ScoreEntry>> {
        return store.flow.map { it[melodyId] ?: emptyList() }
    }

    suspend fun saveScore(melodyId: String, entry: ScoreEntry) {
        store.update(melodyId) { existing ->
            val list = existing?.toMutableList() ?: mutableListOf()
            list.add(entry)
            list
        }
    }

    suspend fun resetScores(melodyId: String) {
        store.remove(melodyId)
    }
}
