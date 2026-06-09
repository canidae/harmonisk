package net.exent.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.scoreHistoryStore: DataStore<Preferences> by preferencesDataStore(
    name = "score_history"
)

class ScoreHistoryRepository(
    private val context: Context
) {
    companion object {
        private val SCORES_KEY = stringPreferencesKey("scores_json")
        private const val RECENT_COUNT = 5
        private val json = Json { ignoreUnknownKeys = true }
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

    private val allScoresFlow: Flow<Map<String, List<ScoreEntry>>> =
        context.scoreHistoryStore.data.map { prefs ->
            parseAll(prefs[SCORES_KEY])
        }

    fun getScores(melodyId: String): Flow<List<ScoreEntry>> {
        return allScoresFlow.map { it[melodyId] ?: emptyList() }
    }

    fun getRecentAverage(melodyId: String): Flow<Float?> {
        return allScoresFlow.map { scoresMap ->
            val scores = scoresMap[melodyId] ?: return@map null
            val recent = scores.takeLast(RECENT_COUNT)
            if (recent.isEmpty()) null
            else recent.map { it.overallScore }.average().toFloat()
        }
    }

    fun getLatestScore(melodyId: String): Flow<Float?> {
        return allScoresFlow.map { scoresMap ->
            val scores = scoresMap[melodyId] ?: return@map null
            scores.lastOrNull()?.overallScore
        }
    }

    suspend fun saveScore(melodyId: String, entry: ScoreEntry) {
        context.scoreHistoryStore.edit { prefs ->
            val current = parseAll(prefs[SCORES_KEY]).toMutableMap()
            val scores = current[melodyId]?.toMutableList() ?: mutableListOf()
            scores.add(entry)
            current[melodyId] = scores
            prefs[SCORES_KEY] = json.encodeToString(current)
        }
    }

    private fun parseAll(jsonStr: String?): Map<String, List<ScoreEntry>> {
        if (jsonStr.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(jsonStr)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
