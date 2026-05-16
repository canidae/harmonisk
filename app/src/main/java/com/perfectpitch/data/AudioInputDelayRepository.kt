package com.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioInputDeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.audioInputDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_input_delay_profiles"
)

/**
 * Stores per-input-device audio delay profiles (microphone latency).
 *
 * Keyed by the input device key from [AudioDeviceMonitor]
 * (e.g. "builtin_mic", "wired_mic", "bt_mic_AirPods Pro").
 */
class AudioInputDelayRepository(
    private val context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        private val PROFILES_KEY = stringPreferencesKey("input_delay_profiles_json")

        fun defaultDelay(type: AudioInputDeviceType): Int = when (type) {
            AudioInputDeviceType.BUILTIN -> 0
            AudioInputDeviceType.WIRED -> 0
            AudioInputDeviceType.BLUETOOTH -> 200
        }
    }

    private val profilesFlow: Flow<Map<String, Int>> = context.audioInputDelayStore.data.map { prefs ->
        parseProfiles(prefs[PROFILES_KEY])
    }

    /**
     * Emits the delay (ms) for the currently active audio input device.
     * Automatically switches when the device changes.
     */
    val activeDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentInputDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey] ?: defaultDelay(device.type)
    }

    /** Store the delay for a specific input device key. */
    suspend fun setDelayForDevice(deviceKey: String, delayMs: Int) {
        context.audioInputDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            current[deviceKey] = delayMs.coerceIn(-200, 500)
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    /** Get all stored profiles. */
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
