package net.exent.perfectpitch.ui.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.perfectpitch.audio.AudioConstants
import net.exent.perfectpitch.audio.PitchProcessor
import net.exent.perfectpitch.audio.PitchStabilizer
import net.exent.perfectpitch.data.SettingsRepository
import net.exent.perfectpitch.music.NoteInfo
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.music.PitchResult
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

        /**
         * Hysteresis band around a semitone boundary (in cents).
         * Once a note name is committed, the smoothed pitch must move MORE than
         * (50 + HYSTERESIS_CENTS) cents from the current note's centre before the
         * display switches to an adjacent semitone.  This prevents the name from
         * flickering when the pitch hovers near a boundary.
         */
        const val HYSTERESIS_CENTS = 15f

        /**
         * Fallback frame interval used for the very first frame, before a real interval has
         * been measured. Matches the 512-sample capture hop at 44100 Hz.
         */
        private const val NOMINAL_FRAME_S = AudioConstants.BUFFER_SIZE_SAMPLES.toDouble() /
            AudioConstants.SAMPLE_RATE
    }

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var holdJob: Job? = null

    private val stabilizer = PitchStabilizer()

    /** Timestamp of the previous valid frame, used to keep smoothing time-based rather than per-frame. */
    private var lastFrameNanos = 0L

    // Hysteresis: the MIDI note number currently committed to for display (-1 = none)
    private var displayedMidi: Int = -1

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
            }
        }
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

            // Smooth in semitone space — vibrato oscillates symmetrically, so the stabilised
            // value converges to the player's intended pitch regardless of its amplitude.
            val now = System.nanoTime()
            val dt = if (lastFrameNanos == 0L) NOMINAL_FRAME_S
            else ((now - lastFrameNanos) / 1e9).coerceIn(0.0, 0.25)
            lastFrameNanos = now

            val rawSemitones = 12.0 * log2(result.frequency / NoteUtils.DEFAULT_A4_FREQ.toDouble())
            val smoothedSemitones = stabilizer.accept(rawSemitones, result.confidence, dt)
            val smoothedFreq = (NoteUtils.DEFAULT_A4_FREQ * 2.0.pow(smoothedSemitones / 12.0)).toFloat()
            val rawNoteInfo = noteUtils.frequencyToNote(smoothedFreq)

            // Hysteresis: only switch displayed note name when the smoothed pitch has moved
            // clearly past the semitone boundary (> 50 + HYSTERESIS_CENTS from current centre).
            val noteInfo: NoteInfo = when {
                displayedMidi == -1 -> {
                    // First valid frame after silence — commit immediately.
                    displayedMidi = rawNoteInfo.midiNumber
                    rawNoteInfo
                }
                rawNoteInfo.midiNumber == displayedMidi -> rawNoteInfo
                else -> {
                    val displayedCenter = NoteUtils.midiToFrequency(displayedMidi)
                    val centsFromDisplayed = noteUtils.frequencyToCents(smoothedFreq, displayedCenter)
                    if (abs(centsFromDisplayed) > 50f + HYSTERESIS_CENTS) {
                        // Crossed decisively into the adjacent semitone — switch.
                        displayedMidi = rawNoteInfo.midiNumber
                        rawNoteInfo
                    } else {
                        // Still within the hysteresis band — keep the current note name but
                        // show the actual cents offset relative to the displayed note centre.
                        noteUtils.frequencyToNote(displayedCenter)
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
                it.copy(
                    displayedResult = displayResult,
                    smoothedCentsOff = noteInfo.centsOff
                )
            }
        } else {
            // Brief silence: don't reset the stabiliser — hold the current smoothed value so that
            // transient quiet moments (e.g., a breath) don't destabilise the display.
            if (_state.value.displayedResult.isValid && holdJob?.isActive != true) {
                holdJob = viewModelScope.launch {
                    delay(HOLD_DURATION_MS)
                    // Hold expired — player truly stopped. Reset so the next note starts fresh.
                    stabilizer.reset()
                    lastFrameNanos = 0L
                    displayedMidi = -1
                    _state.update {
                        it.copy(
                            displayedResult = PitchResult.SILENT,
                            smoothedCentsOff = 0f
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
        super.onCleared()
        stopListening()
    }
}
