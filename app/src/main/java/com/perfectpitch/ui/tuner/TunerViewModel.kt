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

@HiltViewModel
class TunerViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository,
    private val noteUtils: NoteUtils
) : ViewModel() {

    companion object {
        const val HOLD_DURATION_MS = 10_000L
        const val EMA_ALPHA = 0.3f
        const val IN_TUNE_CENTS = 10

        /** Circular buffer size (~250ms at 23ms/frame). Median of this many frequency
         *  readings robustly identifies the center pitch even through wide vibrato. */
        const val VIBRATO_BUFFER_SIZE = 11

        /** Consecutive in-tune frames required before showing "✓ In Tune". */
        const val IN_TUNE_HOLD_FRAMES = 5
    }

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var holdJob: Job? = null

    // Vibrato-resistant frequency buffer
    private val freqBuffer = FloatArray(VIBRATO_BUFFER_SIZE)
    private var bufferWriteIdx = 0
    private var bufferCount = 0

    // EMA state
    private var smoothedCents: Float = 0f

    // Note-change detection (for EMA reset)
    private var lastDisplayedNote: String? = null
    private var lastDisplayedOctave: Int = 0

    // Stable "In Tune" counter
    private var inTuneConsecutiveCount = 0

    private var referenceA4 = NoteUtils.DEFAULT_A4_FREQ

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                referenceA4 = settings.concertPitch
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setYinThreshold(settings.yinThreshold)
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

            // Push frequency into circular buffer
            freqBuffer[bufferWriteIdx] = result.frequency
            bufferWriteIdx = (bufferWriteIdx + 1) % VIBRATO_BUFFER_SIZE
            if (bufferCount < VIBRATO_BUFFER_SIZE) bufferCount++

            // Derive note from median frequency (vibrato peaks cancel out)
            val medianFreq = medianFrequency()
            val noteInfo = noteUtils.frequencyToNote(medianFreq, referenceA4)

            // Reset EMA when the displayed note genuinely changes
            if (noteInfo.name != lastDisplayedNote || noteInfo.octave != lastDisplayedOctave) {
                smoothedCents = noteInfo.centsOff
                lastDisplayedNote = noteInfo.name
                lastDisplayedOctave = noteInfo.octave
            } else {
                smoothedCents = EMA_ALPHA * noteInfo.centsOff + (1f - EMA_ALPHA) * smoothedCents
            }

            // Stable "In Tune" — only light up after consecutive in-tune frames
            if (abs(smoothedCents) <= IN_TUNE_CENTS) {
                inTuneConsecutiveCount++
            } else {
                inTuneConsecutiveCount = 0
            }
            val isInTune = inTuneConsecutiveCount >= IN_TUNE_HOLD_FRAMES

            val displayResult = result.copy(
                noteName = noteInfo.name,
                octave = noteInfo.octave,
                centsOff = noteInfo.centsOff,
                midiNoteNumber = noteInfo.midiNumber
            )

            _state.update {
                it.copy(
                    displayedResult = displayResult,
                    smoothedCentsOff = smoothedCents,
                    isHeld = false,
                    isInTune = isInTune
                )
            }
        } else {
            // Silence — reset buffer so stale readings don't contaminate the next note
            resetBuffer()
            inTuneConsecutiveCount = 0

            if (_state.value.displayedResult.isValid && holdJob?.isActive != true) {
                _state.update { it.copy(isHeld = true, isInTune = false) }
                holdJob = viewModelScope.launch {
                    delay(HOLD_DURATION_MS)
                    _state.update {
                        it.copy(
                            displayedResult = PitchResult.SILENT,
                            smoothedCentsOff = 0f,
                            isHeld = false,
                            isInTune = false
                        )
                    }
                    lastDisplayedNote = null
                }
            }
        }
    }

    private fun medianFrequency(): Float {
        val count = bufferCount
        if (count == 1) return freqBuffer[0]
        val sorted = freqBuffer.copyOf(count).also { it.sort() }
        return sorted[count / 2]
    }

    private fun resetBuffer() {
        bufferWriteIdx = 0
        bufferCount = 0
        lastDisplayedNote = null
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
