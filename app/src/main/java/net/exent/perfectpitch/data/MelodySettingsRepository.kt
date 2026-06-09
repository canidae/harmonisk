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

private val Context.melodySettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "melody_settings"
)

/**
 * Stores per-melody settings (tempo, transpose, wait mode, etc.).
 * Each melody is identified by its [MelodyRepository.MelodyInfo.id].
 */
class MelodySettingsRepository(
    private val context: Context
) {
    companion object {
        private val SETTINGS_KEY = stringPreferencesKey("melody_settings_json")
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    data class MelodySettings(
        val waitMode: Boolean = true,
        val allowWrongNotes: Boolean = false,
        val tempoMultiplier: Float = 1.0f,
        val transposeSemitones: Int = 0,
        val playMelodyAudio: Boolean = false,
        val fallSpeedPxPerSec: Float = 150f
    )

    private val allSettingsFlow: Flow<Map<String, MelodySettings>> =
        context.melodySettingsStore.data.map { prefs ->
            parseAll(prefs[SETTINGS_KEY])
        }

    /** Get settings for a specific melody. Returns defaults if none stored. */
    fun getSettings(melodyId: String): Flow<MelodySettings> {
        return allSettingsFlow.map { it[melodyId] ?: MelodySettings() }
    }

    /** Save settings for a specific melody. */
    suspend fun saveSettings(melodyId: String, settings: MelodySettings) {
        context.melodySettingsStore.edit { prefs ->
            val current = parseAll(prefs[SETTINGS_KEY]).toMutableMap()
            current[melodyId] = settings
            prefs[SETTINGS_KEY] = json.encodeToString(current)
        }
    }

    private fun parseAll(jsonStr: String?): Map<String, MelodySettings> {
        if (jsonStr.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(jsonStr)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
