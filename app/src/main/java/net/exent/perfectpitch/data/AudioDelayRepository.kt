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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.audioDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_delays"
)

/**
 * Stores calibrated audio round-trip delay and output/input split per device pair.
 * Output and input delays are derived: `output = total * split`, `input = total * (1 - split)`.
 */
class AudioDelayRepository(
    private val context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        private val PROFILES_KEY = stringPreferencesKey("delay_profiles_json")
        private val json = Json { ignoreUnknownKeys = true }

        const val DEFAULT_OUTPUT_SPLIT = 0.9f

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
        val totalDelayMs: Int,
        val splitPercent: Float
    ) {
        val outputDelayMs: Int get() = (totalDelayMs * splitPercent).toInt()
        val inputDelayMs: Int get() = totalDelayMs - outputDelayMs
    }

    private val profilesFlow: Flow<Map<String, DelayProfile>> =
        context.audioDelayStore.data.map { prefs ->
            parseProfiles(prefs[PROFILES_KEY])
        }

    val activeOutputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.outputDelayMs ?: defaultOutputDelay(device.type)
    }

    val activeInputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentInputDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.inputDelayMs ?: defaultInputDelay(device.type)
    }

    val activeTotalDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.totalDelayMs ?: 0
    }

    val activeSplitPercent: Flow<Float> = combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.splitPercent ?: DEFAULT_OUTPUT_SPLIT
    }

    val allProfiles: Flow<Map<String, DelayProfile>> = profilesFlow

    fun getProfile(deviceKey: String): Flow<DelayProfile?> =
        profilesFlow.map { it[deviceKey] }

    fun isCalibrated(deviceKey: String): Flow<Boolean> =
        profilesFlow.map { it.containsKey(deviceKey) }

    suspend fun saveCalibration(deviceKey: String, totalDelayMs: Int, splitPercent: Float) =
        updateProfile(deviceKey) {
            DelayProfile(
                totalDelayMs = totalDelayMs.coerceIn(0, 1000),
                splitPercent = splitPercent.coerceIn(0f, 1f)
            )
        }

    suspend fun setTotalDelay(deviceKey: String, totalDelayMs: Int) =
        updateProfile(deviceKey) { existing ->
            DelayProfile(
                totalDelayMs = totalDelayMs.coerceIn(0, 1000),
                splitPercent = existing?.splitPercent ?: DEFAULT_OUTPUT_SPLIT
            )
        }

    suspend fun setSplitPercent(deviceKey: String, splitPercent: Float) =
        updateProfile(deviceKey) { existing ->
            DelayProfile(
                totalDelayMs = existing?.totalDelayMs ?: 0,
                splitPercent = splitPercent.coerceIn(0f, 1f)
            )
        }

    suspend fun setTotalDelayForCurrentDevices(totalDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setTotalDelay(outputKey, totalDelayMs)
        setTotalDelay(inputKey, totalDelayMs)
    }

    suspend fun setSplitPercentForCurrentDevices(splitPercent: Float) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setSplitPercent(outputKey, splitPercent)
        setSplitPercent(inputKey, splitPercent)
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
