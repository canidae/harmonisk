package net.exent.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer

private val Context.melodySettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "melody_settings"
)

class MelodySettingsRepository(
    context: Context
) {
    @Serializable
    data class MelodySettings(
        val waitMode: Boolean = true,
        val allowWrongNotes: Boolean = false,
        val tempoMultiplier: Float = 1.0f,
        val transposeSemitones: Int = 0,
        val playMelodyAudio: Boolean = false,
        val fallSpeedPxPerSec: Float = 150f,
        val level: Int = 1
    )

    private val store = JsonPreferenceStore<String, MelodySettings>(
        dataStore = context.melodySettingsStore,
        keyName = "melody_settings_json"
    )

    fun getSettings(melodyId: String): Flow<MelodySettings> {
        return store.flow.map { it[melodyId] ?: MelodySettings() }
    }

    suspend fun saveSettings(melodyId: String, settings: MelodySettings) {
        store.set(melodyId, settings)
    }
}
