package net.exent.harmonisk.ui.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.harmonisk.audio.AudioConstants
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.audio.PitchStabilizer
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteInfo
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.PitchResult
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

        const val HYSTERESIS_CENTS = 15f

        private const val IN_TUNE_HYSTERESIS_CENTS = 2f

        private const val NOMINAL_FRAME_S = AudioConstants.BUFFER_SIZE_SAMPLES.toDouble() /
            AudioConstants.SAMPLE_RATE
    }

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var holdJob: Job? = null

    private val stabilizer = PitchStabilizer()

    private var lastFrameNanos = 0L

    private var displayedMidi: Int = -1

    private var referenceA4 = NoteUtils.DEFAULT_CONCERT_PITCH

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setInstrument(Instrument.fromName(settings.instrumentName))
                applyConcertPitch(settings.concertPitchA4)
            }
        }
    }

    fun setConcertPitch(frequency: Float) {
        applyConcertPitch(frequency.coerceIn(NoteUtils.MIN_CONCERT_PITCH, NoteUtils.MAX_CONCERT_PITCH))
    }

    fun commitConcertPitch() {
        viewModelScope.launch { settingsRepository.updateConcertPitch(referenceA4) }
    }

    private fun applyConcertPitch(frequency: Float) {
        val changed = frequency != referenceA4
        referenceA4 = frequency
        pitchProcessor.setReferenceA4(frequency)
        if (!changed) return
        stabilizer.reset()
        lastFrameNanos = 0L
        displayedMidi = -1
        _state.update { it.copy(concertPitchA4 = frequency) }
    }

    fun startListening() {
        if (listeningJob?.isActive == true) return
        listeningJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { result ->
                processResult(result)
            }
        }
    }

    private fun processResult(result: PitchResult) {
        if (result.isValid) {
            holdJob?.cancel()
            holdJob = null

            val now = System.nanoTime()
            val dt = if (lastFrameNanos == 0L) NOMINAL_FRAME_S
            else ((now - lastFrameNanos) / 1e9).coerceIn(0.0, 0.25)
            lastFrameNanos = now

            val rawSemitones = 12.0 * log2(result.frequency / referenceA4.toDouble())
            val smoothedSemitones = stabilizer.accept(rawSemitones, result.confidence, dt)
            val smoothedFreq = (referenceA4 * 2.0.pow(smoothedSemitones / 12.0)).toFloat()
            val rawNoteInfo = noteUtils.frequencyToNote(smoothedFreq, referenceA4)

            val noteInfo: NoteInfo = when {
                displayedMidi == -1 -> {
                    displayedMidi = rawNoteInfo.midiNumber
                    rawNoteInfo
                }
                rawNoteInfo.midiNumber == displayedMidi -> rawNoteInfo
                else -> {
                    val displayedCenter = NoteUtils.midiToFrequency(displayedMidi, referenceA4)
                    val centsFromDisplayed = noteUtils.frequencyToCents(smoothedFreq, displayedCenter)
                    if (abs(centsFromDisplayed) > NoteUtils.MAX_CENTS_OFF + HYSTERESIS_CENTS) {
                        displayedMidi = rawNoteInfo.midiNumber
                        rawNoteInfo
                    } else {
                        noteUtils.frequencyToNote(displayedCenter, referenceA4)
                            .copy(centsOff = centsFromDisplayed)
                    }
                }
            }

            val displayResult = result.copy(
                frequency = smoothedFreq,
                noteName = noteInfo.name,
                octave = noteInfo.octave,
                centsOff = noteInfo.centsOff,
                midiNoteNumber = noteInfo.midiNumber
            )

            _state.update {
                val isInTune = abs(noteInfo.centsOff) <=
                    NoteUtils.IN_TUNE_CENTS + if (it.isInTune) IN_TUNE_HYSTERESIS_CENTS else 0f
                it.copy(
                    displayedResult = displayResult,
                    smoothedCentsOff = noteInfo.centsOff,
                    isInTune = isInTune
                )
            }
        } else {
            if (_state.value.displayedResult.isValid && holdJob?.isActive != true) {
                holdJob = viewModelScope.launch {
                    delay(HOLD_DURATION_MS)
                    stabilizer.reset()
                    lastFrameNanos = 0L
                    displayedMidi = -1
                    _state.update {
                        it.copy(
                            displayedResult = PitchResult.SILENT,
                            smoothedCentsOff = 0f,
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
        stabilizer.reset()
        lastFrameNanos = 0L
        displayedMidi = -1
    }

    override fun onCleared() {
        stopListening()
    }
}
