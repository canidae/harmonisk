package com.perfectpitch.data

import android.content.Context
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
        val CONCERT_PITCH = floatPreferencesKey("concert_pitch")
        val YIN_THRESHOLD = floatPreferencesKey("yin_threshold")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SILENCE_THRESHOLD_DB = floatPreferencesKey("silence_threshold_db")
        val CONTINUOUS_PITCH_LINE = booleanPreferencesKey("continuous_pitch_line")
        val FREEPLAY_FALL_SPEED = floatPreferencesKey("freeplay_fall_speed")
        val FREEPLAY_BEATS_PER_BAR = intPreferencesKey("freeplay_beats_per_bar")
        val FREEPLAY_BPM = floatPreferencesKey("freeplay_bpm")
        val FREEPLAY_MIN_NOTE_DIVISION = intPreferencesKey("freeplay_min_note_division")
        val FREEPLAY_SNAP_ENABLED = booleanPreferencesKey("freeplay_snap_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            concertPitch = prefs[CONCERT_PITCH] ?: 440f,
            yinThreshold = prefs[YIN_THRESHOLD] ?: 0.15f,
            darkTheme = prefs[DARK_THEME] ?: true,
            silenceThresholdDb = prefs[SILENCE_THRESHOLD_DB] ?: -50f,
            continuousPitchLine = prefs[CONTINUOUS_PITCH_LINE] ?: true,
            freeplayFallSpeed = prefs[FREEPLAY_FALL_SPEED] ?: 150f,
            freeplayBeatsPerBar = prefs[FREEPLAY_BEATS_PER_BAR] ?: 4,
            freeplayBpm = prefs[FREEPLAY_BPM] ?: 120f,
            freeplayMinNoteDivision = prefs[FREEPLAY_MIN_NOTE_DIVISION] ?: 16,
            freeplaySnapEnabled = prefs[FREEPLAY_SNAP_ENABLED] ?: true
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

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}

data class AppSettings(
    val concertPitch: Float = 440f,
    val yinThreshold: Float = 0.15f,
    val darkTheme: Boolean = true,
    val silenceThresholdDb: Float = -50f,
    val continuousPitchLine: Boolean = true,
    val freeplayFallSpeed: Float = 150f,
    val freeplayBeatsPerBar: Int = 4,
    val freeplayBpm: Float = 120f,
    val freeplayMinNoteDivision: Int = 16,
    val freeplaySnapEnabled: Boolean = true
)
