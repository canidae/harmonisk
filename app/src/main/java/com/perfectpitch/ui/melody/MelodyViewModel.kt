package com.perfectpitch.ui.melody

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.MelodyRepository
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.music.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class MelodyViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val melodyRepository: MelodyRepository,
    private val settingsRepository: SettingsRepository,
    private val scoringEngine: ScoringEngine,
    private val noteUtils: NoteUtils
) : ViewModel() {

    private val _state = MutableStateFlow(MelodyPlayState())
    val state: StateFlow<MelodyPlayState> = _state.asStateFlow()

    private var playbackJob: Job? = null
    private var pitchJob: Job? = null
    private var playbackStartTime = 0L
    private var pauseOffset = 0.0

    // Per-note tracking
    private var currentNoteStartTime = 0L
    private var currentNoteDetectedCorrectly = false
    private var bestCentsOff = Float.MAX_VALUE

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
            }
        }
    }

    fun getAvailableMelodies(): List<MelodyRepository.MelodyInfo> {
        return melodyRepository.getAvailableMelodies()
    }

    fun loadMelody(info: MelodyRepository.MelodyInfo) {
        val track = melodyRepository.loadMelody(info)
        _state.update { it.copy(melodyTrack = track, phase = PlayPhase.NOT_STARTED) }
    }

    fun loadMelodyTrack(track: MelodyTrack) {
        _state.update { it.copy(melodyTrack = track, phase = PlayPhase.NOT_STARTED) }
    }

    fun loadMelodyFromUri(uri: android.net.Uri) {
        val track = melodyRepository.loadFromUri(uri, "Imported MIDI")
        track?.let { _state.update { s -> s.copy(melodyTrack = it, phase = PlayPhase.NOT_STARTED) } }
    }

    fun setWaitMode(enabled: Boolean) {
        _state.update { it.copy(waitMode = enabled) }
    }

    fun setTempoMultiplier(multiplier: Float) {
        _state.update { it.copy(tempoMultiplier = multiplier.coerceIn(0.5f, 1.5f)) }
    }

    fun startPlaying() {
        val track = _state.value.melodyTrack ?: return
        _state.update { it.copy(phase = PlayPhase.COUNTDOWN, countdownValue = 3) }

        viewModelScope.launch {
            // Countdown
            for (i in 3 downTo 1) {
                _state.update { it.copy(countdownValue = i) }
                delay(1000)
            }

            // Start playback
            _state.update {
                it.copy(
                    phase = PlayPhase.PLAYING,
                    currentTimeSeconds = 0.0,
                    noteResults = emptyMap(),
                    noteScores = emptyList(),
                    currentNoteIndex = -1
                )
            }

            startPitchDetection()
            startPlaybackTimer()
        }
    }

    fun pause() {
        pauseOffset = _state.value.currentTimeSeconds
        playbackJob?.cancel()
        pitchJob?.cancel()
        _state.update { it.copy(phase = PlayPhase.PAUSED, isListening = false) }
    }

    fun resume() {
        _state.update { it.copy(phase = PlayPhase.PLAYING) }
        startPitchDetection()
        startPlaybackTimer()
    }

    fun stop() {
        playbackJob?.cancel()
        pitchJob?.cancel()
        pauseOffset = 0.0
        _state.update {
            it.copy(
                phase = PlayPhase.NOT_STARTED,
                currentTimeSeconds = 0.0,
                isListening = false,
                noteResults = emptyMap()
            )
        }
    }

    private fun startPlaybackTimer() {
        playbackStartTime = SystemClock.elapsedRealtime()
        playbackJob = viewModelScope.launch {
            val track = _state.value.melodyTrack ?: return@launch
            val tempo = _state.value.tempoMultiplier

            while (isActive) {
                val elapsed = (SystemClock.elapsedRealtime() - playbackStartTime) / 1000.0 * tempo
                val currentTime = pauseOffset + elapsed

                if (currentTime >= track.durationSeconds + 1.0) {
                    finishPlaying()
                    break
                }

                _state.update { it.copy(currentTimeSeconds = currentTime) }
                updateCurrentNote(currentTime)
                delay(16) // ~60fps update
            }
        }
    }

    private fun startPitchDetection() {
        pitchJob = viewModelScope.launch {
            _state.update { it.copy(isListening = true) }
            pitchProcessor.pitchFlow().collect { result ->
                _state.update { it.copy(pitchResult = result) }
                if (result.isValid) {
                    evaluateNote(result)
                }
            }
        }
    }

    private fun updateCurrentNote(currentTime: Double) {
        val track = _state.value.melodyTrack ?: return
        val noteIndex = track.notes.indexOfFirst { note ->
            val start = track.noteStartSeconds(note)
            val duration = track.noteDurationSeconds(note)
            currentTime >= start && currentTime < start + duration
        }

        if (noteIndex != _state.value.currentNoteIndex && noteIndex >= 0) {
            // New note entered
            val results = _state.value.noteResults.toMutableMap()
            results[noteIndex] = NoteResultState.ACTIVE
            _state.update { it.copy(currentNoteIndex = noteIndex, noteResults = results) }
            currentNoteStartTime = SystemClock.elapsedRealtime()
            currentNoteDetectedCorrectly = false
            bestCentsOff = Float.MAX_VALUE
        }
    }

    private fun evaluateNote(result: PitchResult) {
        val track = _state.value.melodyTrack ?: return
        val noteIndex = _state.value.currentNoteIndex
        if (noteIndex < 0 || noteIndex >= track.notes.size) return

        val expectedNote = track.notes[noteIndex]
        val expectedMidi = expectedNote.noteNumber
        val detectedMidi = result.midiNoteNumber

        // Calculate cents off from the expected note
        val expectedFreq = noteUtils.midiToFrequency(expectedMidi)
        val centsOff = noteUtils.frequencyToCents(result.frequency, expectedFreq)

        if (abs(centsOff) < abs(bestCentsOff)) {
            bestCentsOff = centsOff
        }

        // In wait mode: check if the correct note was hit
        if (_state.value.waitMode && detectedMidi == expectedMidi && abs(centsOff) <= 50f) {
            currentNoteDetectedCorrectly = true
            scoreCurrentNote(expectedNote, noteIndex)
        } else if (!_state.value.waitMode) {
            // In continuous mode, score when note finishes
            if (abs(centsOff) <= 50f) {
                currentNoteDetectedCorrectly = true
            }
        }
    }

    private fun scoreCurrentNote(expectedNote: com.perfectpitch.midi.NoteEvent, noteIndex: Int) {
        val track = _state.value.melodyTrack ?: return
        val expectedDurationMs = (track.noteDurationSeconds(expectedNote) * 1000).toLong()
        val actualDurationMs = SystemClock.elapsedRealtime() - currentNoteStartTime

        val performance = NotePerformance(
            expectedMidiNote = expectedNote.noteNumber,
            detectedMidiNote = _state.value.pitchResult.midiNoteNumber,
            centsOff = bestCentsOff,
            expectedDurationMs = expectedDurationMs,
            actualDurationMs = actualDurationMs,
            timingOffsetMs = 0 // simplified for now
        )

        val score = scoringEngine.scoreNote(performance)
        val resultState = when (score.pitchAccuracy) {
            PitchAccuracy.PERFECT -> NoteResultState.PERFECT
            PitchAccuracy.GOOD -> NoteResultState.GOOD
            PitchAccuracy.FAIR -> NoteResultState.FAIR
            PitchAccuracy.MISS -> NoteResultState.MISS
        }

        _state.update { current ->
            val results = current.noteResults.toMutableMap()
            results[noteIndex] = resultState
            val scores = current.noteScores + score
            current.copy(noteResults = results, noteScores = scores)
        }
    }

    private fun finishPlaying() {
        playbackJob?.cancel()
        pitchJob?.cancel()
        pauseOffset = 0.0

        val noteScores = _state.value.noteScores
        val melodyScore = scoringEngine.calculateMelodyScore(noteScores)

        _state.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                melodyScore = melodyScore,
                isListening = false
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        pitchJob?.cancel()
    }
}
