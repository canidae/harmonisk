package com.perfectpitch.ui.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.music.NoteUtils
import com.perfectpitch.music.PitchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

@HiltViewModel
class TunerViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository,
    private val noteUtils: NoteUtils
) : ViewModel() {

    companion object {
        const val HOLD_DURATION_MS = 10_000L
        const val IN_TUNE_CENTS = 10

        /**
         * EMA alpha in semitone space.
         * α = 0.2 at ~43 fps gives:
         *   • 6 Hz vibrato attenuated to ~28% amplitude (±30 ct vibrato → ~±8.5 ct display)
         *   • Note name switches after ~72 ms; 95% settled in ~325 ms
         */
        const val EMA_ALPHA = 0.2f

        /** Consecutive in-tune frames before showing "✓ In Tune". */
        const val IN_TUNE_HOLD_FRAMES = 5
    }

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var holdJob: Job? = null

    // EMA state in semitone space (semitones relative to A4)
    private var smoothedSemitones: Double = 0.0
    private var isFirstValidFrame = true

    // Stable "In Tune" counter
    private var inTuneConsecutiveCount = 0

    private var referenceA4 = NoteUtils.DEFAULT_A4_FREQ

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                referenceA4 = settings.concertPitch
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                _state.update { it.copy(referenceFrequency = settings.concertPitch) }
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
            holdJob?.cancel()
            holdJob = null

            // Apply EMA in semitone space — vibrato oscillates symmetrically, so the
            // smoothed value converges to the player's intended pitch regardless of amplitude.
            val rawSemitones = 12.0 * log2(result.frequency / referenceA4.toDouble())
            if (isFirstValidFrame) {
                smoothedSemitones = rawSemitones
                isFirstValidFrame = false
            } else {
                smoothedSemitones = EMA_ALPHA * rawSemitones + (1.0 - EMA_ALPHA) * smoothedSemitones
            }

            val smoothedFreq = (referenceA4 * 2.0.pow(smoothedSemitones / 12.0)).toFloat()
            val noteInfo = noteUtils.frequencyToNote(smoothedFreq, referenceA4)

            // Count consecutive frames within the in-tune window for the stable indicator
            if (abs(noteInfo.centsOff) <= IN_TUNE_CENTS) {
                inTuneConsecutiveCount++
            } else {
                inTuneConsecutiveCount = 0
            }
            val isInTune = inTuneConsecutiveCount >= IN_TUNE_HOLD_FRAMES

            val displayResult = result.copy(
                frequency = smoothedFreq,
                noteName = noteInfo.name,
                octave = noteInfo.octave,
                centsOff = noteInfo.centsOff,
                midiNoteNumber = noteInfo.midiNumber
            )

            _state.update {
                it.copy(
                    displayedResult = displayResult,
                    smoothedCentsOff = noteInfo.centsOff,
                    isHeld = false,
                    isInTune = isInTune
                )
            }
        } else {
            // Brief silence: don't reset the EMA — hold the current smoothed value so that
            // transient quiet moments (e.g., a breath) don't destabilise the display.
            if (_state.value.displayedResult.isValid && holdJob?.isActive != true) {
                inTuneConsecutiveCount = 0
                _state.update { it.copy(isHeld = true, isInTune = false) }
                holdJob = viewModelScope.launch {
                    delay(HOLD_DURATION_MS)
                    // Hold expired — player truly stopped. Reset so the next note starts fresh.
                    isFirstValidFrame = true
                    _state.update {
                        it.copy(
                            displayedResult = PitchResult.SILENT,
                            smoothedCentsOff = 0f,
                            isHeld = false,
                            isInTune = false
                        )
                    }
                }
            }
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
