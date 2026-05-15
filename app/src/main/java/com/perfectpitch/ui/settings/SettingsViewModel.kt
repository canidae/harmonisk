package com.perfectpitch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.data.AppSettings
import com.perfectpitch.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun updateConcertPitch(pitch: Float) {
        viewModelScope.launch { settingsRepository.updateConcertPitch(pitch) }
    }

    fun updateInTuneCents(cents: Int) {
        viewModelScope.launch { settingsRepository.updateInTuneCents(cents) }
    }

    fun updateDarkTheme(dark: Boolean) {
        viewModelScope.launch { settingsRepository.updateDarkTheme(dark) }
    }

    fun updateSilenceThreshold(db: Float) {
        viewModelScope.launch { settingsRepository.updateSilenceThreshold(db) }
    }

    fun updateAudioLatency(ms: Int) {
        viewModelScope.launch { settingsRepository.updateAudioLatency(ms) }
    }

    fun updateMelodyAudioDelay(ms: Int) {
        viewModelScope.launch { settingsRepository.updateMelodyAudioDelay(ms) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { settingsRepository.resetToDefaults() }
    }
}
