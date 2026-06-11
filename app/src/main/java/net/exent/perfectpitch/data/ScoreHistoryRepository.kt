package net.exent.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer

private val Context.scoreHistoryStore: DataStore<Preferences> by preferencesDataStore(
    name = "score_history"
)

class ScoreHistoryRepository(
    context: Context
) {
    companion object {
        private const val RECENT_COUNT = 5
    }

    @Serializable
    data class ScoreEntry(
        val timestamp: Long,
        val overallScore: Float,
        val totalNotes: Int,
        val notesHit: Int,
        val perfectNotes: Int,
        val greatNotes: Int,
        val longestStreak: Int,
        val avgPitchScore: Float,
        val avgDurationScore: Float
    )

    private val store = JsonPreferenceStore<String, List<ScoreEntry>>(
        dataStore = context.scoreHistoryStore,
        keyName = "scores_json"
    )

    fun getScores(melodyId: String): Flow<List<ScoreEntry>> {
        return store.flow.map { it[melodyId] ?: emptyList() }
    }

    fun getRecentAverage(melodyId: String): Flow<Float?> {
        return store.flow.map { scoresMap ->
            val scores = scoresMap[melodyId] ?: return@map null
            val recent = scores.takeLast(RECENT_COUNT)
            if (recent.isEmpty()) null
            else recent.map { it.overallScore }.average().toFloat()
        }
    }

    suspend fun saveScore(melodyId: String, entry: ScoreEntry) {
        store.update(melodyId) { existing ->
            val list = existing?.toMutableList() ?: mutableListOf()
            list.add(entry)
            list
        }
    }
}
