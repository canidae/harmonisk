package com.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioOutputDeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.audioDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_delay_profiles"
)

/**
 * Stores per-device audio delay profiles.
 *
 * Each profile is keyed by the device key from [AudioDeviceMonitor] (e.g.
 * "speaker", "wired", "bluetooth_AirPods Pro") and maps to a delay in ms.
 *
 * Smart defaults: 0 ms for speaker and wired, 150 ms for Bluetooth.
 */
class AudioDelayProfileRepository(
    private val context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        private val PROFILES_KEY = stringPreferencesKey("delay_profiles_json")

        fun defaultDelay(type: AudioOutputDeviceType): Int = when (type) {
            AudioOutputDeviceType.SPEAKER -> 0
            AudioOutputDeviceType.WIRED -> 0
            AudioOutputDeviceType.BLUETOOTH -> 150
        }
    }

    private val profilesFlow: Flow<Map<String, Int>> = context.audioDelayStore.data.map { prefs ->
        parseProfiles(prefs[PROFILES_KEY])
    }

    /**
     * Emits the delay (ms) for the currently active audio output device.
     * Automatically switches when the device changes.
     */
    val activeDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey] ?: defaultDelay(device.type)
    }

    /** Get the stored delay for a specific device key, or the smart default. */
    fun getDelayForDevice(deviceKey: String, deviceType: AudioOutputDeviceType): Flow<Int> {
        return profilesFlow.map { profiles ->
            profiles[deviceKey] ?: defaultDelay(deviceType)
        }
    }

    /** Store the delay for a specific device key. */
    suspend fun setDelayForDevice(deviceKey: String, delayMs: Int) {
        context.audioDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            current[deviceKey] = delayMs.coerceIn(-200, 500)
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    /** Get all stored profiles as a snapshot. */
    val allProfiles: Flow<Map<String, Int>> = profilesFlow

    private fun parseProfiles(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Int>()
            for (key in obj.keys()) {
                map[key] = obj.getInt(key)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeProfiles(profiles: Map<String, Int>): String {
        val obj = JSONObject()
        for ((key, value) in profiles) {
            obj.put(key, value)
        }
        return obj.toString()
    }
}
