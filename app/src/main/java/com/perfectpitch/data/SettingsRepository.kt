package com.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val CONCERT_PITCH = floatPreferencesKey("concert_pitch")
        val YIN_THRESHOLD = floatPreferencesKey("yin_threshold")
        val IN_TUNE_CENTS = intPreferencesKey("in_tune_cents")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SILENCE_THRESHOLD_DB = floatPreferencesKey("silence_threshold_db")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            concertPitch = prefs[CONCERT_PITCH] ?: 440f,
            yinThreshold = prefs[YIN_THRESHOLD] ?: 0.15f,
            inTuneCents = prefs[IN_TUNE_CENTS] ?: 10,
            darkTheme = prefs[DARK_THEME] ?: true,
            silenceThresholdDb = prefs[SILENCE_THRESHOLD_DB] ?: -40f
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

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}

data class AppSettings(
    val concertPitch: Float = 440f,
    val yinThreshold: Float = 0.15f,
    val inTuneCents: Int = 10,
    val darkTheme: Boolean = true,
    val silenceThresholdDb: Float = -40f
)
