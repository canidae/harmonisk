package com.perfectpitch.ui.freeplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.music.PitchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class FreePlayViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        const val MIN_NOTE_DURATION_MS = 80L
        const val MIN_VISIBLE_KEYS = 5f
        const val MAX_VISIBLE_KEYS = 48f
        const val MIN_FALL_SPEED = 30f
        const val MAX_FALL_SPEED = 600f
        const val RANGE_PADDING = 2
        const val ABSOLUTE_MIN_MIDI = 21f  // A0
        const val ABSOLUTE_MAX_MIDI = 108f // A8
        const val MIN_AUTO_FIT_RANGE = 12f // One octave
    }

    private val _state = MutableStateFlow(FreePlayState())
    val state: StateFlow<FreePlayState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var currentNoteMidi: Int? = null
    private var currentNoteStartMs: Long = 0L
    private var centsSamples = mutableListOf<Float>()

    // Virtual time tracking: always used for note timestamps and canvas rendering.
    // When timeProgressionEnabled is true, virtual time advances every frame.
    // When false, virtual time only advances while a note is being detected.
    private var virtualNowMs: Long = System.currentTimeMillis()
    private var lastRealTimeMs: Long = virtualNowMs

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setYinThreshold(settings.yinThreshold)
            }
        }
    }

    /**
     * Called each animation frame from the screen composable.
     * Returns the effective "now" timestamp for the canvas, accounting for
     * the time-progression toggle (virtual time freezes during silence when disabled).
     */
    fun getEffectiveTime(realTimeMs: Long): Long {
        val delta = (realTimeMs - lastRealTimeMs).coerceAtLeast(0L)
        lastRealTimeMs = realTimeMs

        val shouldAdvance = _state.value.timeProgressionEnabled || currentNoteMidi != null
        if (shouldAdvance) {
            virtualNowMs += delta
        }
        return virtualNowMs
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

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        finalizeCurrentNote()
        _state.update { it.copy(isListening = false, activeNote = null) }
    }

    // --- Public actions ---

    fun toggleTimeProgression() {
        _state.update { it.copy(timeProgressionEnabled = !it.timeProgressionEnabled) }
    }

    fun scrollToTop() {
        _state.update { it.copy(timeOffsetMs = 0) }
    }

    fun autoFit() {
        _state.update { current ->
            val allNotes = current.playedNotes + listOfNotNull(current.activeNote)
            if (allNotes.isEmpty()) return@update current

            val minNote = allNotes.minOf { it.midiNumber }.toFloat() - RANGE_PADDING
            val maxNote = allNotes.maxOf { it.midiNumber }.toFloat() + RANGE_PADDING
            var range = maxNote - minNote

            var newMin: Float
            var newMax: Float

            if (range < MIN_AUTO_FIT_RANGE) {
                val center = (minNote + maxNote) / 2f
                newMin = center - MIN_AUTO_FIT_RANGE / 2f
                newMax = center + MIN_AUTO_FIT_RANGE / 2f
            } else {
                newMin = minNote
                newMax = maxNote
            }

            // Clamp to absolute piano range
            if (newMin < ABSOLUTE_MIN_MIDI) {
                newMax += (ABSOLUTE_MIN_MIDI - newMin)
                newMin = ABSOLUTE_MIN_MIDI
            }
            if (newMax > ABSOLUTE_MAX_MIDI) {
                newMin -= (newMax - ABSOLUTE_MAX_MIDI)
                newMax = ABSOLUTE_MAX_MIDI
            }
            newMin = newMin.coerceAtLeast(ABSOLUTE_MIN_MIDI)
            newMax = newMax.coerceAtMost(ABSOLUTE_MAX_MIDI)

            current.copy(viewMinMidi = newMin, viewMaxMidi = newMax)
        }
    }

    // --- Gesture callbacks ---

    fun onHorizontalZoom(scaleFactor: Float, focusFraction: Float) {
        _state.update { current ->
            val range = current.viewMaxMidi - current.viewMinMidi
            val newRange = (range / scaleFactor).coerceIn(MIN_VISIBLE_KEYS, MAX_VISIBLE_KEYS)
            val focusMidi = current.viewMinMidi + range * focusFraction
            var newMin = focusMidi - newRange * focusFraction
            var newMax = focusMidi + newRange * (1f - focusFraction)
            // Clamp to absolute piano range
            if (newMin < ABSOLUTE_MIN_MIDI) {
                newMin = ABSOLUTE_MIN_MIDI
                newMax = (newMin + newRange).coerceAtMost(ABSOLUTE_MAX_MIDI)
            }
            if (newMax > ABSOLUTE_MAX_MIDI) {
                newMax = ABSOLUTE_MAX_MIDI
                newMin = (newMax - newRange).coerceAtLeast(ABSOLUTE_MIN_MIDI)
            }
            current.copy(viewMinMidi = newMin, viewMaxMidi = newMax)
        }
    }

    fun onVerticalZoom(scaleFactor: Float) {
        _state.update { current ->
            val newSpeed = (current.fallSpeedPxPerSec * scaleFactor)
                .coerceIn(MIN_FALL_SPEED, MAX_FALL_SPEED)
            current.copy(fallSpeedPxPerSec = newSpeed)
        }
    }

    fun onHorizontalScroll(deltaMidi: Float) {
        _state.update { current ->
            val range = current.viewMaxMidi - current.viewMinMidi
            var newMin = current.viewMinMidi - deltaMidi
            var newMax = current.viewMaxMidi - deltaMidi
            // Clamp to absolute piano range
            if (newMin < ABSOLUTE_MIN_MIDI) {
                newMin = ABSOLUTE_MIN_MIDI
                newMax = newMin + range
            }
            if (newMax > ABSOLUTE_MAX_MIDI) {
                newMax = ABSOLUTE_MAX_MIDI
                newMin = newMax - range
            }
            current.copy(viewMinMidi = newMin, viewMaxMidi = newMax)
        }
    }

    fun onVerticalScroll(deltaMs: Long) {
        _state.update { current ->
            current.copy(timeOffsetMs = (current.timeOffsetMs + deltaMs).coerceAtLeast(0L))
        }
    }

    // --- Pitch processing ---

    private fun processResult(result: PitchResult) {
        if (result.isValid) {
            val midi = result.midiNoteNumber

            if (currentNoteMidi == null || midi != currentNoteMidi) {
                finalizeCurrentNote()
                currentNoteMidi = midi
                currentNoteStartMs = virtualNowMs
                centsSamples.clear()
                centsSamples.add(result.centsOff)

                expandRange(midi)

                val note = PlayedNote(
                    noteName = result.noteName,
                    octave = result.octave,
                    midiNumber = midi,
                    startTimeMs = virtualNowMs,
                    averageCentsOff = result.centsOff,
                    centsHistory = listOf(result.centsOff)
                )
                _state.update { it.copy(activeNote = note, pitchResult = result) }
            } else {
                centsSamples.add(result.centsOff)
                val avgCents = centsSamples.average().toFloat()
                _state.update { current ->
                    current.copy(
                        activeNote = current.activeNote?.copy(
                            averageCentsOff = avgCents,
                            centsHistory = current.activeNote.centsHistory + result.centsOff
                        ),
                        pitchResult = result
                    )
                }
            }
        } else {
            if (currentNoteMidi != null) {
                finalizeCurrentNote()
                _state.update { it.copy(activeNote = null, pitchResult = result) }
            }
        }
    }

    private fun finalizeCurrentNote() {
        val midi = currentNoteMidi ?: return
        val now = virtualNowMs
        val duration = now - currentNoteStartMs

        if (duration >= MIN_NOTE_DURATION_MS && centsSamples.isNotEmpty()) {
            val activeNote = _state.value.activeNote
            if (activeNote != null) {
                val finalNote = activeNote.copy(
                    endTimeMs = now,
                    averageCentsOff = centsSamples.average().toFloat()
                )
                _state.update { current ->
                    current.copy(
                        playedNotes = current.playedNotes + finalNote,
                        activeNote = null
                    )
                }
            }
        } else {
            _state.update { it.copy(activeNote = null) }
        }

        currentNoteMidi = null
        centsSamples.clear()
    }

    private fun expandRange(midi: Int) {
        _state.update { current ->
            val newDetectedMin = min(current.detectedMinMidi, midi - RANGE_PADDING)
            val newDetectedMax = max(current.detectedMaxMidi, midi + RANGE_PADDING)

            // Also expand view range if the note is outside current view (clamped to piano range)
            val newViewMin = min(current.viewMinMidi, midi.toFloat() - RANGE_PADDING)
                .coerceAtLeast(ABSOLUTE_MIN_MIDI)
            val newViewMax = max(current.viewMaxMidi, midi.toFloat() + RANGE_PADDING)
                .coerceAtMost(ABSOLUTE_MAX_MIDI)

            current.copy(
                detectedMinMidi = newDetectedMin,
                detectedMaxMidi = newDetectedMax,
                viewMinMidi = newViewMin,
                viewMaxMidi = newViewMax
            )
        }
    }

    fun resetSession() {
        finalizeCurrentNote()
        _state.update {
            FreePlayState(isListening = it.isListening)
        }
        virtualNowMs = System.currentTimeMillis()
        lastRealTimeMs = virtualNowMs
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}
