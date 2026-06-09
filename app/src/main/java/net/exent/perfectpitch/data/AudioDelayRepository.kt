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

        fun defaultOutputDelay(type: AudioOutputDeviceType): Int = when (type) {
            AudioOutputDeviceType.SPEAKER -> 0
            AudioOutputDeviceType.WIRED -> 0
            AudioOutputDeviceType.BLUETOOTH -> 150
        }

        fun defaultInputDelay(type: AudioInputDeviceType): Int = when (type) {
            AudioInputDeviceType.BUILTIN -> 0
            AudioInputDeviceType.WIRED -> 0
            AudioInputDeviceType.BLUETOOTH -> 200
        }
    }

    @Serializable
    data class DelayProfile(
        val outputDelayMs: Int,
        val inputDelayMs: Int
    )

    private val profilesFlow: Flow<Map<String, DelayProfile>> =
        context.audioDelayStore.data.map { prefs ->
            parseProfiles(prefs[PROFILES_KEY])
        }

    val activeOutputDelay: Flow<Int> = kotlinx.coroutines.flow.combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.outputDelayMs ?: defaultOutputDelay(device.type)
    }

    val activeInputDelay: Flow<Int> = kotlinx.coroutines.flow.combine(
        audioDeviceMonitor.currentInputDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.inputDelayMs ?: defaultInputDelay(device.type)
    }

    val allProfiles: Flow<Map<String, DelayProfile>> = profilesFlow

    fun getProfile(deviceKey: String): Flow<DelayProfile?> =
        profilesFlow.map { it[deviceKey] }

    fun isCalibrated(deviceKey: String): Flow<Boolean> =
        profilesFlow.map { it.containsKey(deviceKey) }

    suspend fun saveCalibration(deviceKey: String, outputDelayMs: Int, inputDelayMs: Int) =
        updateProfile(deviceKey) {
            DelayProfile(
                outputDelayMs = outputDelayMs.coerceIn(0, 1000),
                inputDelayMs = inputDelayMs.coerceIn(0, 1000)
            )
        }

    suspend fun setOutputDelay(deviceKey: String, outputDelayMs: Int) =
        updateProfile(deviceKey) { existing ->
            DelayProfile(
                outputDelayMs = outputDelayMs.coerceIn(0, 1000),
                inputDelayMs = existing?.inputDelayMs ?: 0
            )
        }

    suspend fun setInputDelay(deviceKey: String, inputDelayMs: Int) =
        updateProfile(deviceKey) { existing ->
            DelayProfile(
                outputDelayMs = existing?.outputDelayMs ?: 0,
                inputDelayMs = inputDelayMs.coerceIn(0, 1000)
            )
        }

    suspend fun setOutputDelayForCurrentDevices(outputDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setOutputDelay(outputKey, outputDelayMs)
        setOutputDelay(inputKey, outputDelayMs)
    }

    suspend fun setInputDelayForCurrentDevices(inputDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setInputDelay(outputKey, inputDelayMs)
        setInputDelay(inputKey, inputDelayMs)
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
