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
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val context: Context,
    private val profileRepository: ProfileRepository
) {
    companion object {
        private const val SILENCE_THRESHOLD_DB = "silence_threshold_db"
        private const val FREEPLAY_FALL_SPEED = "freeplay_fall_speed"
        private const val FREEPLAY_BEATS_PER_BAR = "freeplay_beats_per_bar"
        private const val FREEPLAY_BPM = "freeplay_bpm"
        private const val FREEPLAY_MIN_NOTE_DIVISION = "freeplay_min_note_division"
        private const val FREEPLAY_SNAP_ENABLED = "freeplay_snap_enabled"
        private const val FREEPLAY_METRONOME_ENABLED = "freeplay_metronome_enabled"
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
            FREEPLAY_FALL_SPEED,
            FREEPLAY_BEATS_PER_BAR,
            FREEPLAY_BPM,
            FREEPLAY_MIN_NOTE_DIVISION,
            FREEPLAY_SNAP_ENABLED,
            FREEPLAY_METRONOME_ENABLED,
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
        silenceThresholdDb = readFloat(profileId, SILENCE_THRESHOLD_DB, -45f).coerceIn(-65f, -25f),
        freeplayFallSpeed = readFloat(profileId, FREEPLAY_FALL_SPEED, 75f),
        freeplayBeatsPerBar = readInt(profileId, FREEPLAY_BEATS_PER_BAR, 4),
        freeplayBpm = readFloat(profileId, FREEPLAY_BPM, 120f),
        freeplayMinNoteDivision = readInt(profileId, FREEPLAY_MIN_NOTE_DIVISION, 16),
        freeplaySnapEnabled = readBoolean(profileId, FREEPLAY_SNAP_ENABLED, true),
        freeplayMetronomeEnabled = readBoolean(profileId, FREEPLAY_METRONOME_ENABLED, true),
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

    suspend fun updateFreeplayFallSpeed(speed: Float) = setFloat(FREEPLAY_FALL_SPEED, speed)

    suspend fun updateFreeplayBeatsPerBar(beats: Int) = setInt(FREEPLAY_BEATS_PER_BAR, beats)

    suspend fun updateFreeplayBpm(bpm: Float) = setFloat(FREEPLAY_BPM, bpm)

    suspend fun updateFreeplayMinNoteDivision(division: Int) = setInt(FREEPLAY_MIN_NOTE_DIVISION, division)

    suspend fun updateFreeplaySnapEnabled(enabled: Boolean) = setBoolean(FREEPLAY_SNAP_ENABLED, enabled)

    suspend fun updateFreeplayMetronomeEnabled(enabled: Boolean) = setBoolean(FREEPLAY_METRONOME_ENABLED, enabled)

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
    val silenceThresholdDb: Float = -45f,
    val freeplayFallSpeed: Float = 75f,
    val freeplayBeatsPerBar: Int = 4,
    val freeplayBpm: Float = 120f,
    val freeplayMinNoteDivision: Int = 16,
    val freeplaySnapEnabled: Boolean = true,
    val freeplayMetronomeEnabled: Boolean = true,
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
