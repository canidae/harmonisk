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
        val IN_TUNE_CENTS = intPreferencesKey("in_tune_cents")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SILENCE_THRESHOLD_DB = floatPreferencesKey("silence_threshold_db")
        val CONTINUOUS_PITCH_LINE = booleanPreferencesKey("continuous_pitch_line")
        val TIME_PROGRESSION_ENABLED = booleanPreferencesKey("time_progression_enabled")
        val FREEPLAY_FALL_SPEED = floatPreferencesKey("freeplay_fall_speed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            concertPitch = prefs[CONCERT_PITCH] ?: 440f,
            yinThreshold = prefs[YIN_THRESHOLD] ?: 0.15f,
            inTuneCents = prefs[IN_TUNE_CENTS] ?: 10,
            darkTheme = prefs[DARK_THEME] ?: true,
            silenceThresholdDb = prefs[SILENCE_THRESHOLD_DB] ?: -50f,
            continuousPitchLine = prefs[CONTINUOUS_PITCH_LINE] ?: true,
            timeProgressionEnabled = prefs[TIME_PROGRESSION_ENABLED] ?: true,
            freeplayFallSpeed = prefs[FREEPLAY_FALL_SPEED] ?: 150f
        )
    }

    suspend fun updateConcertPitch(pitch: Float) {
        context.dataStore.edit { it[CONCERT_PITCH] = pitch }
    }

    suspend fun updateYinThreshold(threshold: Float) {
        context.dataStore.edit { it[YIN_THRESHOLD] = threshold }
    }

    suspend fun updateInTuneCents(cents: Int) {
        context.dataStore.edit { it[IN_TUNE_CENTS] = cents }
    }

    suspend fun updateSilenceThreshold(db: Float) {
        context.dataStore.edit { it[SILENCE_THRESHOLD_DB] = db }
    }

    suspend fun updateTimeProgressionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TIME_PROGRESSION_ENABLED] = enabled }
    }

    suspend fun updateFreeplayFallSpeed(speed: Float) {
        context.dataStore.edit { it[FREEPLAY_FALL_SPEED] = speed }
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}

data class AppSettings(
    val concertPitch: Float = 440f,
    val yinThreshold: Float = 0.15f,
    val inTuneCents: Int = 10,
    val darkTheme: Boolean = true,
    val silenceThresholdDb: Float = -50f,
    val continuousPitchLine: Boolean = true,
    val timeProgressionEnabled: Boolean = true,
    val freeplayFallSpeed: Float = 150f
)
