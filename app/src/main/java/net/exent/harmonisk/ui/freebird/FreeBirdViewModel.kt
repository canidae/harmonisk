package net.exent.harmonisk.ui.freebird

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import javax.inject.Inject

@HiltViewModel
class FreeBirdViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val pitchProcessor: PitchProcessor
) : ViewModel() {
    private val _instrument = MutableStateFlow(Instrument.VIOLIN)
    val instrument: StateFlow<Instrument> = _instrument.asStateFlow()

    private val _pitch = MutableStateFlow(PitchResult.SILENT)
    val pitch: StateFlow<PitchResult> = _pitch.asStateFlow()

    private var listenJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val instrument = Instrument.fromName(settings.instrumentName)
                _instrument.value = instrument
                pitchProcessor.setInstrument(instrument)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setReferenceA4(settings.concertPitchA4)
            }
        }
    }

    fun startListening() {
        if (listenJob != null) return
        listenJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { _pitch.value = it }
        }
    }

    override fun onCleared() {
        listenJob?.cancel()
        listenJob = null
    }
}
