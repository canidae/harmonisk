package net.exent.perfectpitch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.AudioInputDeviceType
import net.exent.perfectpitch.audio.AudioOutputDeviceType
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
        fun defaultTotalDelay(type: AudioOutputDeviceType): Int = when (type) {
            AudioOutputDeviceType.SPEAKER -> 0
            AudioOutputDeviceType.WIRED -> 0
            AudioOutputDeviceType.BLUETOOTH -> 150
        }

        fun defaultInputDelay(type: AudioInputDeviceType): Int = 125
    }

    @Serializable
    data class DelayProfile(
        val totalDelayMs: Int = 0,
        val inputDelayMs: Int = 125
    )

    private val store = JsonPreferenceStore<String, DelayProfile>(
        dataStore = context.audioDelayStore,
        keyName = "delay_profiles_json_v3"
    )

    val activeTotalDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentDevice,
        store.flow
    ) { device, profiles ->
        profiles[device.deviceKey]?.totalDelayMs ?: defaultTotalDelay(device.type)
    }

    val activeInputDelay: Flow<Int> = combine(
        audioDeviceMonitor.currentInputDevice,
        store.flow
    ) { device, profiles ->
        profiles[device.deviceKey]?.inputDelayMs ?: defaultInputDelay(device.type)
    }

    val allProfiles: Flow<Map<String, DelayProfile>> = store.flow

    suspend fun setTotalDelay(deviceKey: String, totalDelayMs: Int) =
        store.update(deviceKey) { existing ->
            DelayProfile(
                totalDelayMs = totalDelayMs.coerceIn(0, 1000),
                inputDelayMs = existing?.inputDelayMs ?: 125
            )
        }

    suspend fun setInputDelay(deviceKey: String, inputDelayMs: Int) =
        store.update(deviceKey) { existing ->
            DelayProfile(
                totalDelayMs = existing?.totalDelayMs ?: 0,
                inputDelayMs = inputDelayMs.coerceIn(0, 1000)
            )
        }

    suspend fun setTotalDelayForCurrentDevices(totalDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setTotalDelay(outputKey, totalDelayMs)
        setTotalDelay(inputKey, totalDelayMs)
    }

    suspend fun setInputDelayForCurrentDevices(inputDelayMs: Int) {
        val outputKey = audioDeviceMonitor.currentDevice.value.deviceKey
        val inputKey = audioDeviceMonitor.currentInputDevice.value.deviceKey
        setInputDelay(outputKey, inputDelayMs)
        setInputDelay(inputKey, inputDelayMs)
    }
}
