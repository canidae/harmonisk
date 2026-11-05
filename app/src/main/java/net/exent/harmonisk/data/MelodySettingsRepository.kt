package net.exent.harmonisk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.melodySettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "melody_settings"
)

class MelodySettingsRepository(
    context: Context,
    private val profileRepository: ProfileRepository
) {
    @Serializable
    data class MelodySettings(
        val waitMode: Boolean = true,
        val autoTranspose: Boolean = true,
        val transposeSemitones: Int = 0,
        val preferOpenStrings: Boolean = true,
        val fallSpeedPxPerSec: Float = 75f,
        val level: Int = 1,
        val unlockedLevel: Int = 1,
        val activeVoiceIndex: Int = 0,
        val accompanimentMode: String = "ALL"
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
        store.flow.map { it[key(profileId, melodyId, instrument)] ?: MelodySettings() }

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

    suspend fun deleteProfileData(profileId: String) {
        store.removeWhere { ProfileRepository.unscopedKey(profileId, it) != null }
    }

    private fun key(profileId: String, melodyId: String, instrument: String): String =
        ProfileRepository.scopedKey(profileId, "$melodyId:$instrument")
}
