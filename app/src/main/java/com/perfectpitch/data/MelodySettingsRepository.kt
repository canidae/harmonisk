package com.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

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
    }

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
            prefs[SETTINGS_KEY] = serializeAll(current)
        }
    }

    private fun parseAll(json: String?): Map<String, MelodySettings> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            val map = mutableMapOf<String, MelodySettings>()
            for (key in root.keys()) {
                val obj = root.getJSONObject(key)
                map[key] = MelodySettings(
                    waitMode = obj.optBoolean("waitMode", true),
                    allowWrongNotes = obj.optBoolean("allowWrongNotes", false),
                    tempoMultiplier = obj.optDouble("tempoMultiplier", 1.0).toFloat(),
                    transposeSemitones = obj.optInt("transposeSemitones", 0),
                    playMelodyAudio = obj.optBoolean("playMelodyAudio", false),
                    fallSpeedPxPerSec = obj.optDouble("fallSpeedPxPerSec", 150.0).toFloat()
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeAll(all: Map<String, MelodySettings>): String {
        val root = JSONObject()
        for ((id, settings) in all) {
            val obj = JSONObject()
            obj.put("waitMode", settings.waitMode)
            obj.put("allowWrongNotes", settings.allowWrongNotes)
            obj.put("tempoMultiplier", settings.tempoMultiplier.toDouble())
            obj.put("transposeSemitones", settings.transposeSemitones)
            obj.put("playMelodyAudio", settings.playMelodyAudio)
            obj.put("fallSpeedPxPerSec", settings.fallSpeedPxPerSec.toDouble())
            root.put(id, obj)
        }
        return root.toString()
    }
}
