package net.exent.harmonisk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

private val Context.melodySettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "melody_settings"
)

class MelodySettingsRepository(
    context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        const val OVERRIDE_OPEN_STRINGS = "openStrings"
        const val OVERRIDE_EASY_KEY = "easyKey"
        const val OVERRIDE_METRONOME = "metronome"
        val PREFERENCE_OVERRIDES = setOf(OVERRIDE_OPEN_STRINGS, OVERRIDE_EASY_KEY, OVERRIDE_METRONOME)

        fun resolve(stored: MelodySettings?, defaults: ProfileDefaults): MelodySettings {
            val overrides = stored?.overrides ?: if (stored == null) emptyList() else listOfNotNull(
                OVERRIDE_OPEN_STRINGS.takeIf { stored.preferOpenStrings != defaults.preferOpenStrings },
                OVERRIDE_EASY_KEY.takeIf { stored.autoTranspose != defaults.easyKey }
            )
            val base = stored ?: MelodySettings(level = defaults.startingLevel)
            return base.copy(
                preferOpenStrings = if (OVERRIDE_OPEN_STRINGS in overrides) base.preferOpenStrings else defaults.preferOpenStrings,
                autoTranspose = if (OVERRIDE_EASY_KEY in overrides) base.autoTranspose else defaults.easyKey,
                metronomeMode = if (OVERRIDE_METRONOME in overrides) base.metronomeMode else defaults.metronomeMode.name,
                overrides = overrides
            )
        }
    }

    @Serializable
    data class MelodySettings(
        val autoTranspose: Boolean = true,
        val transposeSemitones: Int = 0,
        val preferOpenStrings: Boolean = true,
        val fallSpeedPxPerSec: Float = 75f,
        val level: Int = 1,
        val unlockedLevel: Int = 1,
        val activeVoiceIndex: Int = 0,
        val accompanimentMode: String = "ALL",
        val metronomeMode: String = MetronomeMode.ON.name,
        val overrides: List<String>? = null
    )

    private val store = JsonPreferenceStore<String, MelodySettings>(
        dataStore = context.melodySettingsStore,
        keyName = "melody_settings_json"
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getSettings(melodyId: String, instrument: String): Flow<MelodySettings> =
        profileRepository.activeProfile.flatMapLatest { profile ->
            if (profile == null) flowOf(MelodySettings())
            else getSettings(profile.id, melodyId, instrument)
        }

    fun getSettings(profileId: String, melodyId: String, instrument: String): Flow<MelodySettings> =
        combine(store.flow, settingsRepository.profileDefaults(profileId)) { stored, defaults ->
            resolve(stored[key(profileId, melodyId, instrument)], defaults)
        }

    suspend fun saveSettings(melodyId: String, instrument: String, settings: MelodySettings) {
        val profileId = profileRepository.activeProfileId ?: return
        saveSettings(profileId, melodyId, instrument, settings)
    }

    suspend fun saveSettings(
        profileId: String,
        melodyId: String,
        instrument: String,
        settings: MelodySettings
    ) {
        store.set(key(profileId, melodyId, instrument), settings)
    }

    suspend fun clearPreferenceOverrides(melodyId: String, instrument: String) {
        val profileId = profileRepository.activeProfileId ?: return
        store.update(key(profileId, melodyId, instrument)) { stored ->
            val base = stored ?: MelodySettings()
            base.copy(overrides = base.overrides.orEmpty().filterNot(PREFERENCE_OVERRIDES::contains))
        }
    }

    suspend fun deleteProfileData(profileId: String) {
        store.removeWhere { ProfileRepository.unscopedKey(profileId, it) != null }
    }

    private fun key(profileId: String, melodyId: String, instrument: String): String =
        ProfileRepository.scopedKey(profileId, "$melodyId:$instrument")
}
