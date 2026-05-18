package com.perfectpitch.ui.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.music.PitchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TunerViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        const val HOLD_DURATION_MS = 10_000L
        const val NOTE_LOCK_FRAMES = 3
        const val EMA_ALPHA = 0.3f
    }

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var holdJob: Job? = null

    // Note lock state
    private var lockedNoteName: String? = null
    private var lockedOctave: Int = 0
    private var candidateNoteName: String? = null
    private var candidateOctave: Int = 0
    private var candidateCount: Int = 0

    // EMA state
    private var smoothedCents: Float = 0f

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setYinThreshold(settings.yinThreshold)
                _state.update {
                    it.copy(
                        referenceFrequency = settings.concertPitch
                    )
                }
            }
        }
    }

    fun startListening() {
        if (listeningJob?.isActive == true) return
        _state.update { it.copy(isListening = true) }
        listeningJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { result ->
                processResult(result)
            }
        }
    }

    private fun processResult(result: PitchResult) {
        _state.update { it.copy(pitchResult = result) }

        if (result.isValid) {
            // Cancel any hold timeout — we have active audio
            holdJob?.cancel()
            holdJob = null

            // Apply note lock hysteresis
            val displayNote = applyNoteLock(result)

            // Apply EMA smoothing on cents
            smoothedCents = if (lockedNoteName == null) {
                result.centsOff
            } else {
                EMA_ALPHA * result.centsOff + (1f - EMA_ALPHA) * smoothedCents
            }

            _state.update {
                it.copy(
                    displayedResult = displayNote,
                    smoothedCentsOff = smoothedCents,
                    isHeld = false
                )
            }
        } else {
            // Silence detected — start hold timer if we have a last valid note
            if (_state.value.displayedResult.isValid && holdJob?.isActive != true) {
                _state.update { it.copy(isHeld = true) }
                holdJob = viewModelScope.launch {
                    delay(HOLD_DURATION_MS)
                    // Hold expired — clear display
                    _state.update {
                        it.copy(
                            displayedResult = PitchResult.SILENT,
                            smoothedCentsOff = 0f,
                            isHeld = false
                        )
                    }
                    lockedNoteName = null
                    candidateNoteName = null
                    candidateCount = 0
                }
            }
        }
    }

    private fun applyNoteLock(result: PitchResult): PitchResult {
        val newNote = result.noteName
        val newOctave = result.octave

        if (lockedNoteName == null) {
            // First detection — lock immediately
            lockedNoteName = newNote
            lockedOctave = newOctave
            candidateNoteName = null
            candidateCount = 0
            return result
        }

        if (newNote == lockedNoteName && newOctave == lockedOctave) {
            // Same note — reset candidate counter
            candidateNoteName = null
            candidateCount = 0
            return result
        }

        // Different note detected
        if (newNote == candidateNoteName && newOctave == candidateOctave) {
            candidateCount++
        } else {
            candidateNoteName = newNote
            candidateOctave = newOctave
            candidateCount = 1
        }

        return if (candidateCount >= NOTE_LOCK_FRAMES) {
            // Switch to new note
            lockedNoteName = newNote
            lockedOctave = newOctave
            candidateNoteName = null
            candidateCount = 0
            smoothedCents = result.centsOff
            result
        } else {
            // Keep showing locked note but with current cents relative to locked note
            _state.value.displayedResult
        }
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        holdJob?.cancel()
        holdJob = null
        _state.update { it.copy(isListening = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}
