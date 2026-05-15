package com.perfectpitch.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors the active audio output device and emits changes.
 *
 * Device priority (highest first): Bluetooth A2DP → wired → built-in speaker.
 * Each device is identified by a key (e.g. "bluetooth_AirPods Pro", "speaker", "wired").
 */
class AudioDeviceMonitor(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _currentDevice = MutableStateFlow(resolveActiveDevice())
    val currentDevice: StateFlow<AudioOutputDevice> = _currentDevice.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            _currentDevice.value = resolveActiveDevice()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            _currentDevice.value = resolveActiveDevice()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun close() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun resolveActiveDevice(): AudioOutputDevice {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Pick the highest-priority connected output device
        val bluetooth = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (bluetooth != null) {
            val name = bluetooth.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            return AudioOutputDevice(
                type = AudioOutputDeviceType.BLUETOOTH,
                name = name,
                deviceKey = "bluetooth_$name"
            )
        }

        val wired = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (wired != null) {
            return AudioOutputDevice(
                type = AudioOutputDeviceType.WIRED,
                name = "Wired headphones",
                deviceKey = "wired"
            )
        }

        return AudioOutputDevice(
            type = AudioOutputDeviceType.SPEAKER,
            name = "Phone speaker",
            deviceKey = "speaker"
        )
    }
}

data class AudioOutputDevice(
    val type: AudioOutputDeviceType,
    val name: String,
    val deviceKey: String
)

enum class AudioOutputDeviceType {
    SPEAKER, WIRED, BLUETOOTH
}
