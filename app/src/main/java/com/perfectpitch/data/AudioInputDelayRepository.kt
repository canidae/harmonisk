package com.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.perfectpitch.audio.AudioCaptureService
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioInputDeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.audioInputDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_input_delay_profiles"
)

/**
 * Stores per-input-device audio delay profiles (microphone latency).
 *
 * For built-in mic: uses live measurements from [AudioCaptureService.measuredPipelineDelayMs]
 * plus a fixed algorithm offset. No calibration needed.
 *
 * For external devices (wired/BT): uses calibrated values from DataStore.
 */
class AudioInputDelayRepository(
    private val context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioCaptureService: AudioCaptureService
) {
    companion object {
        private val PROFILES_KEY = stringPreferencesKey("input_delay_profiles_json")
        private val CALIBRATION_VERSION_KEY = intPreferencesKey("calibration_version")
        private const val BASELINE_KEY = "__baseline_input_delay"
        /** Bump this when audio pipeline changes invalidate existing calibration. */
        private const val CURRENT_CALIBRATION_VERSION = 3

        fun defaultDelay(type: AudioInputDeviceType): Int = when (type) {
            AudioInputDeviceType.BUILTIN -> 0
            AudioInputDeviceType.WIRED -> 0
            AudioInputDeviceType.BLUETOOTH -> 200
        }
    }

    /**
     * Estimated output latency for the built-in speaker, derived from the device's
     * native audio buffer configuration. Kept for informational display in calibration.
     */
    val estimatedBuiltinOutputLatencyMs: Int
        get() = audioDeviceMonitor.estimatedOutputLatencyMs

    /**
     * Check calibration version and clear stale data if the audio pipeline has changed.
     */
    suspend fun migrateIfNeeded() {
        context.audioInputDelayStore.edit { prefs ->
            val stored = prefs[CALIBRATION_VERSION_KEY] ?: 0
            if (stored < CURRENT_CALIBRATION_VERSION) {
                val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
                current.remove(BASELINE_KEY)
                prefs[PROFILES_KEY] = serializeProfiles(current)
                prefs[CALIBRATION_VERSION_KEY] = CURRENT_CALIBRATION_VERSION
            }
        }
    }

    private val profilesFlow: Flow<Map<String, Int>> = context.audioInputDelayStore.data.map { prefs ->
        parseProfiles(prefs[PROFILES_KEY])
    }

    /**
     * Emits the delay (ms) for the currently active audio input device.
     *
     * For built-in mic: uses live measured pipeline delay + algorithm offset.
     * For external devices: uses calibrated/default values.
     */
    val activeDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentInputDevice,
        profilesFlow,
        audioCaptureService.measuredPipelineDelayMs
    ) { device, profiles, measuredDelay ->
        if (device.type == AudioInputDeviceType.BUILTIN && measuredDelay != null) {
            // Direct measurement: pipeline delay + algorithm offset for half read chunk
            (measuredDelay + AudioCaptureService.ALGORITHM_OFFSET_MS).toInt()
        } else {
            // External device or no measurement: use calibrated value
            profiles[device.deviceKey] ?: defaultDelay(device.type)
        }
    }

    /** Whether the baseline calibration (built-in speaker + built-in mic) has been completed. */
    val hasBaseline: Flow<Boolean> = profilesFlow.map { it.containsKey(BASELINE_KEY) }

    /** Get the stored baseline input delay (built-in mic latency). Returns null if not calibrated. */
    suspend fun getBaselineInputDelay(): Int? {
        val profiles = profilesFlow.first()
        return profiles[BASELINE_KEY]
    }

    /** Store the baseline input delay (measured with built-in speaker + built-in mic). */
    suspend fun setBaselineInputDelay(delayMs: Int) {
        context.audioInputDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            current[BASELINE_KEY] = delayMs.coerceIn(0, 500)
            current["builtin_mic"] = delayMs.coerceIn(0, 500)
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    /** Store the delay for a specific input device key. */
    suspend fun setDelayForDevice(deviceKey: String, delayMs: Int) {
        context.audioInputDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            current[deviceKey] = delayMs.coerceIn(-200, 500)
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    /** Clear baseline calibration data so the user is prompted to re-calibrate. */
    suspend fun clearBaseline() {
        context.audioInputDelayStore.edit { prefs ->
            val current = parseProfiles(prefs[PROFILES_KEY]).toMutableMap()
            current.remove(BASELINE_KEY)
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
