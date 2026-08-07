package net.exent.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.melodySettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "melody_settings"
)

class MelodySettingsRepository(
    context: Context
) {
    @Serializable
    data class MelodySettings(
        val waitMode: Boolean = true,
        val transposeSemitones: Int = 0,
        val fallSpeedPxPerSec: Float = 75f,
        val level: Int = 1,
        val activeVoiceIndex: Int = 0,
        val accompanimentMode: String = "ALL"
    )

    private val store = JsonPreferenceStore<String, MelodySettings>(
        dataStore = context.melodySettingsStore,
        keyName = "melody_settings_json"
    )

    fun getSettings(melodyId: String, instrument: String): Flow<MelodySettings> {
        val key = "${melodyId}:${instrument}"
        return store.flow.map { it[key] ?: MelodySettings() }
    }

    suspend fun saveSettings(melodyId: String, instrument: String, settings: MelodySettings) {
        val key = "${melodyId}:${instrument}"
        store.set(key, settings)
    }
}
