package com.perfectpitch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.AudioInputDevice
import com.perfectpitch.audio.AudioOutputDevice
import com.perfectpitch.data.AppSettings
import com.perfectpitch.data.AudioDelayRepository
import com.perfectpitch.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val audioDelayRepository: AudioDelayRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val currentDevice: StateFlow<AudioOutputDevice?> = audioDeviceMonitor.currentDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentInputDevice: StateFlow<AudioInputDevice?> = audioDeviceMonitor.currentInputDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeOutputDelay: StateFlow<Int> = audioDelayRepository.activeOutputDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeInputDelay: StateFlow<Int> = audioDelayRepository.activeInputDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeTotalDelay: StateFlow<Int> = audioDelayRepository.activeTotalDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeSplitPercent: StateFlow<Float> = audioDelayRepository.activeSplitPercent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioDelayRepository.DEFAULT_OUTPUT_SPLIT)

    fun updateConcertPitch(pitch: Float) {
        viewModelScope.launch { settingsRepository.updateConcertPitch(pitch) }
    }

    fun updateSilenceThreshold(db: Float) {
        viewModelScope.launch { settingsRepository.updateSilenceThreshold(db) }
    }

    fun saveTotalDelay(ms: Int) {
        val outputDevice = audioDeviceMonitor.currentDevice.value
        val inputDevice = audioDeviceMonitor.currentInputDevice.value
        viewModelScope.launch {
            audioDelayRepository.setTotalDelay(outputDevice.deviceKey, ms)
            audioDelayRepository.setTotalDelay(inputDevice.deviceKey, ms)
        }
    }

    fun saveSplitPercent(percent: Float) {
        val outputDevice = audioDeviceMonitor.currentDevice.value
        val inputDevice = audioDeviceMonitor.currentInputDevice.value
        viewModelScope.launch {
            audioDelayRepository.setSplitPercent(outputDevice.deviceKey, percent)
            audioDelayRepository.setSplitPercent(inputDevice.deviceKey, percent)
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch { settingsRepository.resetToDefaults() }
    }
}
