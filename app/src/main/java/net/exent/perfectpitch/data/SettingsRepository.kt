package net.exent.perfectpitch.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val context: Context
) {
    companion object {
        private val CONCERT_PITCH = floatPreferencesKey("concert_pitch")
        private val SILENCE_THRESHOLD_DB = floatPreferencesKey("silence_threshold_db")
        private val FREEPLAY_FALL_SPEED = floatPreferencesKey("freeplay_fall_speed")
        private val FREEPLAY_BEATS_PER_BAR = intPreferencesKey("freeplay_beats_per_bar")
        private val FREEPLAY_BPM = floatPreferencesKey("freeplay_bpm")
        private val FREEPLAY_MIN_NOTE_DIVISION = intPreferencesKey("freeplay_min_note_division")
        private val FREEPLAY_SNAP_ENABLED = booleanPreferencesKey("freeplay_snap_enabled")
        private val FREEPLAY_METRONOME_ENABLED = booleanPreferencesKey("freeplay_metronome_enabled")
        private val MELODY_METRONOME_ENABLED = booleanPreferencesKey("melody_metronome_enabled")
        private val LANGUAGE_CODE = stringPreferencesKey("language_code")

        private const val PREFS_NAME = "locale_prefs"
        private const val KEY_LANGUAGE = "language_code"
    }

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLanguageCodeSync(): String =
        sharedPrefs.getString(KEY_LANGUAGE, "system") ?: "system"

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            concertPitch = prefs[CONCERT_PITCH] ?: 440f,
            silenceThresholdDb = prefs[SILENCE_THRESHOLD_DB] ?: -50f,
            freeplayFallSpeed = prefs[FREEPLAY_FALL_SPEED] ?: 75f,
            freeplayBeatsPerBar = prefs[FREEPLAY_BEATS_PER_BAR] ?: 4,
            freeplayBpm = prefs[FREEPLAY_BPM] ?: 120f,
            freeplayMinNoteDivision = prefs[FREEPLAY_MIN_NOTE_DIVISION] ?: 16,
            freeplaySnapEnabled = prefs[FREEPLAY_SNAP_ENABLED] ?: true,
            freeplayMetronomeEnabled = prefs[FREEPLAY_METRONOME_ENABLED] ?: false,
            melodyMetronomeEnabled = prefs[MELODY_METRONOME_ENABLED] ?: false,
            languageCode = prefs[LANGUAGE_CODE] ?: "system"
        )
    }

    suspend fun updateConcertPitch(pitch: Float) {
        context.dataStore.edit { it[CONCERT_PITCH] = pitch }
    }

    suspend fun updateSilenceThreshold(db: Float) {
        context.dataStore.edit { it[SILENCE_THRESHOLD_DB] = db }
    }

    suspend fun updateFreeplayFallSpeed(speed: Float) {
        context.dataStore.edit { it[FREEPLAY_FALL_SPEED] = speed }
    }

    suspend fun updateFreeplayBeatsPerBar(beats: Int) {
        context.dataStore.edit { it[FREEPLAY_BEATS_PER_BAR] = beats }
    }

    suspend fun updateFreeplayBpm(bpm: Float) {
        context.dataStore.edit { it[FREEPLAY_BPM] = bpm }
    }

    suspend fun updateFreeplayMinNoteDivision(division: Int) {
        context.dataStore.edit { it[FREEPLAY_MIN_NOTE_DIVISION] = division }
    }

    suspend fun updateFreeplaySnapEnabled(enabled: Boolean) {
        context.dataStore.edit { it[FREEPLAY_SNAP_ENABLED] = enabled }
    }

    suspend fun updateFreeplayMetronomeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[FREEPLAY_METRONOME_ENABLED] = enabled }
    }

    suspend fun updateMelodyMetronomeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[MELODY_METRONOME_ENABLED] = enabled }
    }

    suspend fun updateLanguageCode(code: String) {
        context.dataStore.edit { it[LANGUAGE_CODE] = code }
        sharedPrefs.edit().putString(KEY_LANGUAGE, code).apply()
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
        sharedPrefs.edit().clear().apply()
    }
}

data class AppSettings(
    val concertPitch: Float = 440f,
    val silenceThresholdDb: Float = -50f,
    val freeplayFallSpeed: Float = 75f,
    val freeplayBeatsPerBar: Int = 4,
    val freeplayBpm: Float = 120f,
    val freeplayMinNoteDivision: Int = 16,
    val freeplaySnapEnabled: Boolean = true,
    val freeplayMetronomeEnabled: Boolean = false,
    val melodyMetronomeEnabled: Boolean = false,
    val languageCode: String = "system"
)
