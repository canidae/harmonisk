package net.exent.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.AudioInputDeviceType
import net.exent.perfectpitch.audio.AudioOutputDeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.audioDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_delays"
)

/**
 * Stores calibrated per-device output and input audio delays.
 */
class AudioDelayRepository(
    private val context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        private val PROFILES_KEY = stringPreferencesKey("delay_profiles_json")
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultAudioDelay(type: AudioOutputDeviceType): Int = when (type) {
            AudioOutputDeviceType.SPEAKER -> 0
            AudioOutputDeviceType.WIRED -> 0
            AudioOutputDeviceType.BLUETOOTH -> 150
        }

        fun defaultVisualDelay(type: AudioInputDeviceType): Int = when (type) {
            AudioInputDeviceType.BUILTIN -> 0
            AudioInputDeviceType.WIRED -> 0
            AudioInputDeviceType.BLUETOOTH -> 0
        }
    }

    @Serializable
    data class DelayProfile(
        val audioDelayMs: Int = 0,
        val visualDelayMs: Int = 0
    )

    private val profilesFlow: Flow<Map<String, DelayProfile>> =
        context.audioDelayStore.data.map { prefs ->
            parseProfiles(prefs[PROFILES_KEY])
        }

    val activeAudioDelay: Flow<Int> = kotlinx.coroutines.flow.combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.audioDelayMs ?: defaultAudioDelay(device.type)
    }

    val activeVisualDelay: Flow<Int> = kotlinx.coroutines.flow.combine(
        audioDeviceMonitor.currentInputDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.visualDelayMs ?: defaultVisualDelay(device.type)
    }

    val allProfiles: Flow<Map<String, DelayProfile>> = profilesFlow

    fun getProfile(deviceKey: String): Flow<DelayProfile?> =
        profilesFlow.map { it[deviceKey] }

    fun isCalibrated(deviceKey: String): Flow<Boolean> =
        profilesFlow.map { it.containsKey(deviceKey) }

    suspend fun saveCalibration(deviceKey: String, audioDelayMs: Int, visualDelayMs: Int) =
        updateProfile(deviceKey) {
            DelayProfile(
                audioDelayMs = audioDelayMs.coerceIn(0, 1000),
                visualDelayMs = visualDelayMs.coerceIn(0, 1000)
            )
        }

    suspend fun setAudioDelay(deviceKey: String, audioDelayMs: Int) =
        updateProfile(deviceKey) { existing ->
            DelayProfile(
                audioDelayMs = audioDelayMs.coerceIn(0, 1000),
                visualDelayMs = existing?.visualDelayMs ?: 0
            )
        }

    suspend fun setVisualDelay(deviceKey: String, visualDelayMs: Int) =
        updateProfile(deviceKey) { existing ->
            DelayProfile(
                audioDelayMs = existing?.audioDelayMs ?: 0,
                visualDelayMs = visualDelayMs.coerceIn(0, 1000)
            )
        }

    suspend fun setAudioDelayForCurrentDevices(audioDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setAudioDelay(outputKey, audioDelayMs)
        setAudioDelay(inputKey, audioDelayMs)
    }

    suspend fun setVisualDelayForCurrentDevices(visualDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setVisualDelay(outputKey, visualDelayMs)
        setVisualDelay(inputKey, visualDelayMs)
    }

    private suspend fun updateProfile(deviceKey: String, update: (DelayProfile?) -> DelayProfile) {
        context.audioDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            current[deviceKey] = update(current[deviceKey])
            prefs[PROFILES_KEY] = json.encodeToString(current)
        }
    }

    private fun parseProfiles(jsonStr: String?): Map<String, DelayProfile> {
        if (jsonStr.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(jsonStr)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
