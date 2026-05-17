package com.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioInputDeviceType
import com.perfectpitch.audio.AudioOutputDeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.audioDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_delays"
)

/**
 * Unified audio delay storage. Stores calibrated input and output delays per device key.
 *
 * Each device (identified by its [deviceKey]) has three values:
 * - **inputDelayMs**: delay from sound hitting the mic to pitch detection result
 * - **outputDelayMs**: delay from AudioTrack.write() to sound reaching the speaker
 * - **totalDelayMs**: the measured round-trip (input + output)
 *
 * The split between input and output is determined during calibration Phase 2
 * (user adjusts a slider until audio and visual are in sync).
 */
class AudioDelayRepository(
    private val context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        private val PROFILES_KEY = stringPreferencesKey("delay_profiles_json")

        /**
         * Default fraction of total round-trip delay attributed to output.
         * 0.5 = even split. Adjust if measurements suggest a different typical ratio.
         */
        const val DEFAULT_OUTPUT_SPLIT = 0.5f

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

    /**
     * Per-device delay profile.
     */
    data class DelayProfile(
        val inputDelayMs: Int,
        val outputDelayMs: Int,
        val totalDelayMs: Int
    )

    // --- Internal storage ---

    private val profilesFlow: Flow<Map<String, DelayProfile>> =
        context.audioDelayStore.data.map { prefs ->
            parseProfiles(prefs[PROFILES_KEY])
        }

    // --- Public flows ---

    /** Emits the output delay (ms) for the currently active audio output device. */
    val activeOutputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.outputDelayMs ?: defaultOutputDelay(device.type)
    }

    /** Emits the input delay (ms) for the currently active audio input device. */
    val activeInputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentInputDevice,
        profilesFlow
    ) { device, profiles ->
        profiles[device.deviceKey]?.inputDelayMs ?: defaultInputDelay(device.type)
    }

    /** All stored profiles (for checking if a device has been calibrated). */
    val allProfiles: Flow<Map<String, DelayProfile>> = profilesFlow

    // --- Queries ---

    /** Get the stored profile for a specific device key, or null if not calibrated. */
    fun getProfile(deviceKey: String): Flow<DelayProfile?> =
        profilesFlow.map { it[deviceKey] }

    /** Check if a specific device key has been calibrated. */
    fun isCalibrated(deviceKey: String): Flow<Boolean> =
        profilesFlow.map { it.containsKey(deviceKey) }

    // --- Mutations ---

    /**
     * Save calibration results for a device.
     * Called after the user completes the calibration wizard.
     */
    suspend fun saveCalibration(
        deviceKey: String,
        inputDelayMs: Int,
        outputDelayMs: Int,
        totalDelayMs: Int
    ) {
        context.audioDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            current[deviceKey] = DelayProfile(
                inputDelayMs = inputDelayMs.coerceIn(0, 500),
                outputDelayMs = outputDelayMs.coerceIn(0, 500),
                totalDelayMs = totalDelayMs.coerceIn(0, 1000)
            )
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    /**
     * Manually set individual delay values for a device (e.g. from Settings).
     * Updates the stored profile, adjusting total to match.
     */
    suspend fun setOutputDelay(deviceKey: String, outputDelayMs: Int) {
        context.audioDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            val existing = current[deviceKey]
            val inputDelay = existing?.inputDelayMs ?: 0
            val total = inputDelay + outputDelayMs
            current[deviceKey] = DelayProfile(
                inputDelayMs = inputDelay,
                outputDelayMs = outputDelayMs.coerceIn(0, 500),
                totalDelayMs = total.coerceIn(0, 1000)
            )
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    /**
     * Manually set individual delay values for a device (e.g. from Settings).
     */
    suspend fun setInputDelay(deviceKey: String, inputDelayMs: Int) {
        context.audioDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            val existing = current[deviceKey]
            val outputDelay = existing?.outputDelayMs ?: 0
            val total = outputDelay + inputDelayMs
            current[deviceKey] = DelayProfile(
                inputDelayMs = inputDelayMs.coerceIn(0, 500),
                outputDelayMs = outputDelay,
                totalDelayMs = total.coerceIn(0, 1000)
            )
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    // --- Serialization ---

    private fun parseProfiles(json: String?): Map<String, DelayProfile> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, DelayProfile>()
            for (key in obj.keys()) {
                val profile = obj.getJSONObject(key)
                map[key] = DelayProfile(
                    inputDelayMs = profile.optInt("inputDelayMs", 0),
                    outputDelayMs = profile.optInt("outputDelayMs", 0),
                    totalDelayMs = profile.optInt("totalDelayMs", 0)
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeProfiles(profiles: Map<String, DelayProfile>): String {
        val obj = JSONObject()
        for ((key, profile) in profiles) {
            val p = JSONObject()
            p.put("inputDelayMs", profile.inputDelayMs)
            p.put("outputDelayMs", profile.outputDelayMs)
            p.put("totalDelayMs", profile.totalDelayMs)
            obj.put(key, p)
        }
        return obj.toString()
    }
}
