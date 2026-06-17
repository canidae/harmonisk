package net.exent.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

private val Context.difficultyStore: DataStore<Preferences> by preferencesDataStore(
    name = "difficulty_cache"
)

class DifficultyRepository(context: Context) {
    private val store = JsonPreferenceStore<String, DifficultyData>(
        dataStore = context.difficultyStore,
        keyName = "difficulties"
    )

    @Serializable
    data class DifficultyData(
        val difficulty: Float,
        val algorithmVersion: Int
    )

    suspend fun get(melodyId: String): DifficultyData? {
        return store.flow.first()[melodyId]
    }

    suspend fun set(melodyId: String, data: DifficultyData) {
        store.set(melodyId, data)
    }
}
