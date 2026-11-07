package net.exent.harmonisk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import net.exent.harmonisk.music.MelodyScore

private val Context.scoreHistoryStore: DataStore<Preferences> by preferencesDataStore(
    name = "score_history"
)

class ScoreHistoryRepository(
    context: Context,
    private val profileRepository: ProfileRepository
) {
    private val store = JsonPreferenceStore<String, List<ScoreEntry>>(
        dataStore = context.scoreHistoryStore,
        keyName = "scores_json"
    )

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
        val avgDurationScore: Float,
        val level: Int = 1,
        val instrument: String = "PIANO",
        val wrongNoteCount: Int = 0,
        val wrongNoteScore: Float = 100f
    ) {
        companion object {
            fun from(
                score: MelodyScore,
                overallScore: Float = score.overallScore,
                level: Int,
                instrument: String
            ) = ScoreEntry(
                timestamp = System.currentTimeMillis(),
                overallScore = overallScore,
                totalNotes = score.totalNotes,
                notesHit = score.notesHit,
                perfectNotes = score.perfectNotes,
                greatNotes = score.greatNotes,
                longestStreak = score.longestStreak,
                avgPitchScore = score.avgPitchScore,
                avgDurationScore = score.avgDurationScore,
                level = level,
                instrument = instrument,
                wrongNoteCount = score.wrongNoteCount,
                wrongNoteScore = score.wrongNoteScore
            )
        }
    }

    /** Timestamp of the most recent playthrough of each melody, across instruments. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lastPlayed: Flow<Map<String, Long>> = profileRepository.activeProfile.flatMapLatest { profile ->
        if (profile == null) {
            flowOf(emptyMap())
        } else {
            store.flow.map { scores ->
                scores.mapNotNull { (key, entries) ->
                    val melodyId = ProfileRepository.unscopedKey(profile.id, key) ?: return@mapNotNull null
                    entries.maxOfOrNull { it.timestamp }?.let { melodyId to it }
                }.toMap()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getScores(melodyId: String, instrument: String): Flow<List<ScoreEntry>> =
        profileRepository.activeProfile.flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList())
            else getScores(profile.id, melodyId, instrument)
        }

    fun getScores(profileId: String, melodyId: String, instrument: String): Flow<List<ScoreEntry>> =
        store.flow.map { scores ->
            val key = ProfileRepository.scopedKey(profileId, melodyId)
            (scores[key] ?: emptyList()).filter { entry -> entry.instrument == instrument }
        }

    suspend fun saveScore(melodyId: String, entry: ScoreEntry) {
        val profileId = profileRepository.activeProfileId ?: return
        saveScore(profileId, melodyId, entry)
    }

    suspend fun saveScore(profileId: String, melodyId: String, entry: ScoreEntry) {
        val key = ProfileRepository.scopedKey(profileId, melodyId)
        store.update(key) { existing ->
            val list = existing?.toMutableList() ?: mutableListOf()
            list.add(entry)
            list
        }
    }

    suspend fun resetScores(melodyId: String, instrument: String) {
        val profileId = profileRepository.activeProfileId ?: return
        store.update(ProfileRepository.scopedKey(profileId, melodyId)) { existing ->
            existing?.filter { it.instrument != instrument } ?: emptyList()
        }
    }

    suspend fun resetAllScores(melodyId: String) {
        profileRepository.profiles.value.forEach { profile ->
            store.remove(ProfileRepository.scopedKey(profile.id, melodyId))
        }
    }

    suspend fun deleteProfileData(profileId: String) {
        store.removeWhere { ProfileRepository.unscopedKey(profileId, it) != null }
    }
}
