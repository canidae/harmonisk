package net.exent.harmonisk.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import net.exent.harmonisk.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

class AudioDeviceMonitor(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _currentDevice = MutableStateFlow(resolveActiveOutputDevice())
    val currentDevice: StateFlow<AudioOutputDevice> = _currentDevice.asStateFlow()

    private val _currentInputDevice = MutableStateFlow(resolveActiveInputDevice())
    val currentInputDevice: StateFlow<AudioInputDevice> = _currentInputDevice.asStateFlow()

    private val outputListeners = CopyOnWriteArrayList<(AudioOutputDevice) -> Unit>()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
    }

    fun addOutputDeviceListener(listener: (AudioOutputDevice) -> Unit) {
        outputListeners.add(listener)
    }

    private fun refreshDevices() {
        val previousOutput = _currentDevice.value
        val output = resolveActiveOutputDevice()
        _currentDevice.value = output
        _currentInputDevice.value = resolveActiveInputDevice()
        if (output.deviceKey != previousOutput.deviceKey) {
            outputListeners.forEach { it(output) }
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }


    private fun resolveActiveOutputDevice(): AudioOutputDevice {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        firstOfTypes(outputs, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)?.let { bluetooth ->
            val name = bluetooth.productName?.toString()?.takeIf { it.isNotBlank() }
            return AudioOutputDevice(
                type = AudioOutputDeviceType.BLUETOOTH,
                name = name,
                nameRes = R.string.device_bluetooth,
                deviceKey = "bluetooth_${name ?: "Bluetooth"}"
            )
        }

        firstOfTypes(outputs, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)?.let {
            return AudioOutputDevice(
                type = AudioOutputDeviceType.WIRED,
                nameRes = R.string.device_wired_headphones,
                deviceKey = "wired"
            )
        }

        return AudioOutputDevice(
            type = AudioOutputDeviceType.SPEAKER,
            nameRes = R.string.device_phone_speaker,
            deviceKey = "speaker"
        )
    }

    private fun resolveActiveInputDevice(): AudioInputDevice {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        firstOfTypes(inputs, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)?.let { bluetooth ->
            val name = bluetooth.productName?.toString()?.takeIf { it.isNotBlank() }
            return AudioInputDevice(
                type = AudioInputDeviceType.BLUETOOTH,
                name = name,
                nameRes = R.string.device_bluetooth,
                deviceKey = "bt_mic_${name ?: "Bluetooth"}"
            )
        }

        firstOfTypes(inputs, AudioDeviceInfo.TYPE_WIRED_HEADSET)?.let {
            return AudioInputDevice(
                type = AudioInputDeviceType.WIRED,
                nameRes = R.string.device_wired_headset_mic,
                deviceKey = "wired_mic"
            )
        }

        return AudioInputDevice(
            type = AudioInputDeviceType.BUILTIN,
            nameRes = R.string.device_builtin_mic,
            deviceKey = "builtin_mic"
        )
    }

    private fun firstOfTypes(devices: Array<out AudioDeviceInfo>, vararg types: Int): AudioDeviceInfo? {
        for (type in types) {
            val found = devices.firstOrNull { it.type == type }
            if (found != null) return found
        }
        return null
    }
}

data class AudioOutputDevice(
    val type: AudioOutputDeviceType,
    val name: String? = null,
    val nameRes: Int = 0,
    val deviceKey: String
)

enum class AudioOutputDeviceType {
    SPEAKER, WIRED, BLUETOOTH
}

data class AudioInputDevice(
    val type: AudioInputDeviceType,
    val name: String? = null,
    val nameRes: Int = 0,
    val deviceKey: String
)

enum class AudioInputDeviceType {
    BUILTIN, WIRED, BLUETOOTH
}
