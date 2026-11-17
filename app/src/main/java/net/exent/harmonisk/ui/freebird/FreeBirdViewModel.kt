package net.exent.harmonisk.ui.freebird

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.music.Instrument
import javax.inject.Inject

@HiltViewModel
class FreeBirdViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {
    val instrument: StateFlow<Instrument> = settingsRepository.settings
        .map { Instrument.fromName(it.instrumentName) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Instrument.VIOLIN)
}
