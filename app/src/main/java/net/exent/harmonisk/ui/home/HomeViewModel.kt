package net.exent.harmonisk.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.exent.harmonisk.audio.SoundFontSynthesizer
import net.exent.harmonisk.data.DevModeState
import net.exent.harmonisk.data.MelodySettingsRepository
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.data.ScoreHistoryRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.ui.whack.WhackRepository
import net.exent.harmonisk.data.createProfileWithInstrument
import net.exent.harmonisk.music.Instrument
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val devModeState: DevModeState,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val melodySettingsRepository: MelodySettingsRepository,
    private val scoreHistoryRepository: ScoreHistoryRepository,
    private val whackRepository: WhackRepository,
    private val soundFontSynthesizer: SoundFontSynthesizer
) : ViewModel() {

    private val instrumentFlow = settingsRepository.settings
        .map { Instrument.fromName(it.instrumentName) }

    val instrument: StateFlow<Instrument> = instrumentFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Instrument.VIOLIN)

    val profiles: StateFlow<List<ProfileRepository.Profile>> = profileRepository.profiles
    val activeProfile: StateFlow<ProfileRepository.Profile?> = profileRepository.activeProfile
    val devModeEnabled: StateFlow<Boolean> = devModeState.enabled

    init {
        viewModelScope.launch {
            instrumentFlow
                .distinctUntilChanged()
                .collect { soundFontSynthesizer.setInstrument(it.midiBank, it.midiProgram) }
        }
    }

    fun setInstrument(instrument: Instrument) {
        viewModelScope.launch { settingsRepository.updateInstrument(instrument.name) }
    }

    fun createProfile(name: String, emoji: String, instrument: Instrument): Boolean =
        createProfileWithInstrument(viewModelScope, profileRepository, settingsRepository, name, emoji, instrument)

    fun updateProfile(id: String, name: String, emoji: String, instrument: Instrument): Boolean {
        if (!profileRepository.updateProfile(id, name, emoji)) return false
        viewModelScope.launch { settingsRepository.updateInstrument(id, instrument.name) }
        return true
    }

    fun instrument(profileId: String): Flow<Instrument> = settingsRepository.instrument(profileId)

    fun selectProfile(id: String) {
        profileRepository.selectProfile(id)
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            if (!profileRepository.deleteProfile(id)) return@launch
            settingsRepository.deleteProfileData(id)
            melodySettingsRepository.deleteProfileData(id)
            scoreHistoryRepository.deleteProfileData(id)
            whackRepository.deleteProfileData(id)
        }
    }

    fun toggleDevMode(): Boolean = devModeState.toggle()
}
