package net.exent.harmonisk.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.content.edit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.exent.harmonisk.audio.AudioConstants
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val context: Context,
    private val profileRepository: ProfileRepository
) {
    companion object {
        private const val SILENCE_THRESHOLD_DB = "silence_threshold_db"
        private const val RECORD_FALL_SPEED = "freeplay_fall_speed"
        private const val RECORD_BEATS_PER_BAR = "freeplay_beats_per_bar"
        private const val RECORD_BPM = "freeplay_bpm"
        private const val RECORD_MIN_NOTE_DIVISION = "freeplay_min_note_division"
        private const val RECORD_SNAP_ENABLED = "freeplay_snap_enabled"
        private const val RECORD_METRONOME_ENABLED = "freeplay_metronome_enabled"
        private const val MELODY_METRONOME_ENABLED = "melody_metronome_enabled"
        private const val METRONOME_BPM = "metronome_bpm"
        private const val METRONOME_BEATS_PER_BAR = "metronome_beats_per_bar"
        private const val METRONOME_ACCENT_BEAT = "metronome_accent_beat"
        private const val METRONOME_SOUND_ENABLED = "metronome_sound_enabled"
        private const val METRONOME_MUTED_BEATS = "metronome_muted_beats"
        private const val LANGUAGE_CODE = "language_code"
        private const val INSTRUMENT_NAME = "instrument_name"
        private const val CONCERT_PITCH = "concert_pitch_a4"

        private const val PREFS_NAME = "locale_prefs"

        private val PROFILE_SETTING_KEYS = setOf(
            SILENCE_THRESHOLD_DB,
            RECORD_FALL_SPEED,
            RECORD_BEATS_PER_BAR,
            RECORD_BPM,
            RECORD_MIN_NOTE_DIVISION,
            RECORD_SNAP_ENABLED,
            RECORD_METRONOME_ENABLED,
            MELODY_METRONOME_ENABLED,
            METRONOME_BPM,
            METRONOME_BEATS_PER_BAR,
            METRONOME_ACCENT_BEAT,
            METRONOME_SOUND_ENABLED,
            METRONOME_MUTED_BEATS,
            INSTRUMENT_NAME,
            CONCERT_PITCH
        )

        fun getLanguageCodeSync(context: Context): String =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LANGUAGE_CODE, "system") ?: "system"
    }

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @OptIn(ExperimentalCoroutinesApi::class)
    val settings: Flow<AppSettings> = profileRepository.activeProfile.flatMapLatest { profile ->
        if (profile == null) {
            flowOf(AppSettings())
        } else {
            context.dataStore.data.map { prefs -> prefs.toSettings(profile.id) }
        }
    }

    private fun Preferences.toSettings(profileId: String) = AppSettings(
        silenceThresholdDb = readFloat(profileId, SILENCE_THRESHOLD_DB, AudioConstants.DEFAULT_SILENCE_THRESHOLD_DB).coerceIn(-65f, -25f),
        recordFallSpeed = readFloat(profileId, RECORD_FALL_SPEED, 75f),
        recordBeatsPerBar = readInt(profileId, RECORD_BEATS_PER_BAR, 4),
        recordBpm = readFloat(profileId, RECORD_BPM, 120f),
        recordMinNoteDivision = readInt(profileId, RECORD_MIN_NOTE_DIVISION, 16),
        recordSnapEnabled = readBoolean(profileId, RECORD_SNAP_ENABLED, true),
        recordMetronomeEnabled = readBoolean(profileId, RECORD_METRONOME_ENABLED, true),
        melodyMetronomeEnabled = readBoolean(profileId, MELODY_METRONOME_ENABLED, true),
        metronomeBpm = readInt(profileId, METRONOME_BPM, 100),
        metronomeBeatsPerBar = readInt(profileId, METRONOME_BEATS_PER_BAR, 4),
        metronomeAccentBeat = readInt(profileId, METRONOME_ACCENT_BEAT, 0),
        metronomeSoundEnabled = readBoolean(profileId, METRONOME_SOUND_ENABLED, true),
        metronomeMutedBeats = readStringSet(profileId, METRONOME_MUTED_BEATS).mapNotNull(String::toIntOrNull).toSet(),
        languageCode = this[stringPreferencesKey(LANGUAGE_CODE)] ?: "system",
        instrumentName = readString(profileId, INSTRUMENT_NAME, Instrument.VIOLIN.name),
        concertPitchA4 = readFloat(profileId, CONCERT_PITCH, NoteUtils.DEFAULT_CONCERT_PITCH)
    )

    private fun Preferences.readFloat(profileId: String, name: String, default: Float): Float =
        this[floatPreferencesKey(ProfileRepository.scopedKey(profileId, name))] ?: default

    private fun Preferences.readInt(profileId: String, name: String, default: Int): Int =
        this[intPreferencesKey(ProfileRepository.scopedKey(profileId, name))] ?: default

    private fun Preferences.readBoolean(profileId: String, name: String, default: Boolean): Boolean =
        this[booleanPreferencesKey(ProfileRepository.scopedKey(profileId, name))] ?: default

    private fun Preferences.readString(profileId: String, name: String, default: String): String =
        this[stringPreferencesKey(ProfileRepository.scopedKey(profileId, name))] ?: default

    private fun Preferences.readStringSet(profileId: String, name: String): Set<String> =
        this[stringSetPreferencesKey(ProfileRepository.scopedKey(profileId, name))].orEmpty()

    suspend fun updateSilenceThreshold(db: Float) = setFloat(SILENCE_THRESHOLD_DB, db)

    suspend fun updateRecordFallSpeed(speed: Float) = setFloat(RECORD_FALL_SPEED, speed)

    suspend fun updateRecordBeatsPerBar(beats: Int) = setInt(RECORD_BEATS_PER_BAR, beats)

    suspend fun updateRecordBpm(bpm: Float) = setFloat(RECORD_BPM, bpm)

    suspend fun updateRecordMinNoteDivision(division: Int) = setInt(RECORD_MIN_NOTE_DIVISION, division)

    suspend fun updateRecordSnapEnabled(enabled: Boolean) = setBoolean(RECORD_SNAP_ENABLED, enabled)

    suspend fun updateRecordMetronomeEnabled(enabled: Boolean) = setBoolean(RECORD_METRONOME_ENABLED, enabled)

    suspend fun updateMelodyMetronomeEnabled(enabled: Boolean) = setBoolean(MELODY_METRONOME_ENABLED, enabled)

    suspend fun updateMetronomeBpm(bpm: Int) = setInt(METRONOME_BPM, bpm)

    suspend fun updateMetronomeBeatsPerBar(beats: Int) = setInt(METRONOME_BEATS_PER_BAR, beats)

    suspend fun updateMetronomeAccentBeat(beat: Int) = setInt(METRONOME_ACCENT_BEAT, beat)

    suspend fun updateMetronomeSoundEnabled(enabled: Boolean) = setBoolean(METRONOME_SOUND_ENABLED, enabled)

    suspend fun updateMetronomeMutedBeats(beats: Set<Int>) = setStringSet(
        METRONOME_MUTED_BEATS,
        beats.map(Int::toString).toSet()
    )

    suspend fun updateInstrument(name: String) = setString(INSTRUMENT_NAME, name)

    suspend fun updateInstrument(profileId: String, name: String) =
        setStringFor(profileId, INSTRUMENT_NAME, name)

    fun instrument(profileId: String): Flow<Instrument> =
        context.dataStore.data.map { prefs ->
            Instrument.fromName(
                prefs[stringPreferencesKey(ProfileRepository.scopedKey(profileId, INSTRUMENT_NAME))]
                    ?: Instrument.VIOLIN.name
            )
        }

    suspend fun updateConcertPitch(frequency: Float) = setFloat(CONCERT_PITCH, frequency)

    suspend fun updateLanguageCode(code: String) {
        context.dataStore.edit { it[stringPreferencesKey(LANGUAGE_CODE)] = code }
        sharedPrefs.edit { putString(LANGUAGE_CODE, code) }
    }

    suspend fun resetToDefaults() {
        profileRepository.activeProfileId?.let { deleteProfileData(it) }
    }

    suspend fun deleteProfileData(profileId: String) {
        context.dataStore.edit { prefs ->
            prefs.asMap().keys.toList().forEach { key ->
                val name = ProfileRepository.unscopedKey(profileId, key.name)
                if (name != null && name in PROFILE_SETTING_KEYS) prefs.remove(key)
            }
        }
    }

    private suspend fun setFloat(name: String, value: Float) {
        val profileId = profileRepository.activeProfileId ?: return
        context.dataStore.edit { it[floatPreferencesKey(ProfileRepository.scopedKey(profileId, name))] = value }
    }

    private suspend fun setInt(name: String, value: Int) {
        val profileId = profileRepository.activeProfileId ?: return
        context.dataStore.edit { it[intPreferencesKey(ProfileRepository.scopedKey(profileId, name))] = value }
    }

    private suspend fun setBoolean(name: String, value: Boolean) {
        val profileId = profileRepository.activeProfileId ?: return
        context.dataStore.edit { it[booleanPreferencesKey(ProfileRepository.scopedKey(profileId, name))] = value }
    }

    private suspend fun setString(name: String, value: String) {
        val profileId = profileRepository.activeProfileId ?: return
        setStringFor(profileId, name, value)
    }

    private suspend fun setStringFor(profileId: String, name: String, value: String) {
        context.dataStore.edit { it[stringPreferencesKey(ProfileRepository.scopedKey(profileId, name))] = value }
    }

    private suspend fun setStringSet(name: String, value: Set<String>) {
        val profileId = profileRepository.activeProfileId ?: return
        context.dataStore.edit { it[stringSetPreferencesKey(ProfileRepository.scopedKey(profileId, name))] = value }
    }
}

data class AppSettings(
    val silenceThresholdDb: Float = AudioConstants.DEFAULT_SILENCE_THRESHOLD_DB,
    val recordFallSpeed: Float = 75f,
    val recordBeatsPerBar: Int = 4,
    val recordBpm: Float = 120f,
    val recordMinNoteDivision: Int = 16,
    val recordSnapEnabled: Boolean = true,
    val recordMetronomeEnabled: Boolean = true,
    val melodyMetronomeEnabled: Boolean = true,
    val metronomeBpm: Int = 100,
    val metronomeBeatsPerBar: Int = 4,
    val metronomeAccentBeat: Int = 0,
    val metronomeSoundEnabled: Boolean = true,
    val metronomeMutedBeats: Set<Int> = emptySet(),
    val languageCode: String = "system",
    val instrumentName: String = Instrument.VIOLIN.name,
    val concertPitchA4: Float = NoteUtils.DEFAULT_CONCERT_PITCH
)
