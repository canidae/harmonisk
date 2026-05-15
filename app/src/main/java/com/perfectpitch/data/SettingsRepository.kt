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
        val AUDIO_LATENCY_MS = intPreferencesKey("audio_latency_ms")
        val MELODY_AUDIO_DELAY_MS = intPreferencesKey("melody_audio_delay_ms")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            concertPitch = prefs[CONCERT_PITCH] ?: 440f,
            yinThreshold = prefs[YIN_THRESHOLD] ?: 0.15f,
            inTuneCents = prefs[IN_TUNE_CENTS] ?: 10,
            darkTheme = prefs[DARK_THEME] ?: true,
            silenceThresholdDb = prefs[SILENCE_THRESHOLD_DB] ?: -50f,
            audioLatencyMs = prefs[AUDIO_LATENCY_MS] ?: 236,
            melodyAudioDelayMs = prefs[MELODY_AUDIO_DELAY_MS] ?: 150
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

    suspend fun updateDarkTheme(dark: Boolean) {
        context.dataStore.edit { it[DARK_THEME] = dark }
    }

    suspend fun updateSilenceThreshold(db: Float) {
        context.dataStore.edit { it[SILENCE_THRESHOLD_DB] = db }
    }

    suspend fun updateAudioLatency(ms: Int) {
        context.dataStore.edit { it[AUDIO_LATENCY_MS] = ms }
    }

    suspend fun updateMelodyAudioDelay(ms: Int) {
        context.dataStore.edit { it[MELODY_AUDIO_DELAY_MS] = ms }
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
    val audioLatencyMs: Int = 236,
    val melodyAudioDelayMs: Int = 150
)
