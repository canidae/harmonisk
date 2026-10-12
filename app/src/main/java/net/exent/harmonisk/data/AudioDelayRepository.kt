package net.exent.harmonisk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import net.exent.harmonisk.audio.AudioDeviceMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable

private val Context.audioDelayStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_delays"
)

class AudioDelayRepository(
    context: Context,
    private val audioDeviceMonitor: AudioDeviceMonitor
) {
    companion object {
        /** What the reference device calibrates to, until this device has been calibrated. */
        const val DEFAULT_OUTPUT_DELAY_MS = 150

        /** Window (64 ms) + onset debounce (23 ms) + typical capture and inference latency. */
        const val DEFAULT_INPUT_DELAY_MS = 125
        const val MAX_DELAY_MS = 500
    }

    @Serializable
    data class DelayProfile(
        val outputDelayMs: Int = DEFAULT_OUTPUT_DELAY_MS,
        val inputDelayMs: Int = DEFAULT_INPUT_DELAY_MS
    )

    private val store = JsonPreferenceStore<String, DelayProfile>(
        dataStore = context.audioDelayStore,
        keyName = "delay_profiles_json_v5"
    )

    val activeOutputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        store.flow
    ) { device, profiles ->
        profiles[device.deviceKey]?.outputDelayMs ?: DEFAULT_OUTPUT_DELAY_MS
    }

    val activeInputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentInputDevice,
        store.flow
    ) { device, profiles ->
        profiles[device.deviceKey]?.inputDelayMs ?: DEFAULT_INPUT_DELAY_MS
    }

    val allProfiles: Flow<Map<String, DelayProfile>> = store.flow

    suspend fun setOutputDelay(deviceKey: String, outputDelayMs: Int) =
        store.update(deviceKey) { existing ->
            DelayProfile(
                outputDelayMs = outputDelayMs.coerceIn(0, MAX_DELAY_MS),
                inputDelayMs = existing?.inputDelayMs ?: DEFAULT_INPUT_DELAY_MS
            )
        }

    suspend fun setInputDelay(deviceKey: String, inputDelayMs: Int) =
        store.update(deviceKey) { existing ->
            DelayProfile(
                outputDelayMs = existing?.outputDelayMs ?: DEFAULT_OUTPUT_DELAY_MS,
                inputDelayMs = inputDelayMs.coerceIn(0, MAX_DELAY_MS)
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
}
