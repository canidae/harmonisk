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
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class MelodyViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val melodyRepository: MelodyRepository,
    private val settingsRepository: SettingsRepository,
    private val scoringEngine: ScoringEngine,
    private val noteUtils: NoteUtils
) : ViewModel() {

    companion object {
        const val MIN_FALL_SPEED = 30f
        const val MAX_FALL_SPEED = 600f
        const val NOTE_RANGE_PADDING = 3
    }

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
    private var isWaitingForNote = false

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

    fun loadMelody(info: MelodyRepository.MelodyInfo): Boolean {
        val track = melodyRepository.loadMelody(info)
        if (track != null) {
            val range = track.noteRange
            val viewMin = (range.first - NOTE_RANGE_PADDING).toFloat().coerceAtLeast(21f)
            val viewMax = (range.last + NOTE_RANGE_PADDING).toFloat().coerceAtMost(108f)
            _state.update {
                it.copy(
                    melodyTrack = track,
                    phase = PlayPhase.NOT_STARTED,
                    viewMinMidi = viewMin,
                    viewMaxMidi = viewMax
                )
            }
            return true
        }
        return false
    }

    fun loadMelodyFromUri(uri: android.net.Uri): Boolean {
        val track = melodyRepository.loadFromUri(uri, "Imported MIDI")
        if (track != null) {
            val range = track.noteRange
            val viewMin = (range.first - NOTE_RANGE_PADDING).toFloat().coerceAtLeast(21f)
            val viewMax = (range.last + NOTE_RANGE_PADDING).toFloat().coerceAtMost(108f)
            _state.update {
                it.copy(
                    melodyTrack = track,
                    phase = PlayPhase.NOT_STARTED,
                    viewMinMidi = viewMin,
                    viewMaxMidi = viewMax
                )
            }
            return true
        }
        return false
    }

    fun setWaitMode(enabled: Boolean) {
        _state.update { it.copy(waitMode = enabled) }
    }

    fun setAllowWrongNotes(enabled: Boolean) {
        _state.update { it.copy(allowWrongNotes = enabled) }
    }

    fun setTempoMultiplier(multiplier: Float) {
        _state.update { it.copy(tempoMultiplier = multiplier.coerceIn(0.5f, 1.5f)) }
    }

    fun startPlaying() {
        val track = _state.value.melodyTrack ?: return
        if (_state.value.phase == PlayPhase.COUNTDOWN || _state.value.phase == PlayPhase.PLAYING) return

        _state.update {
            it.copy(
                phase = PlayPhase.COUNTDOWN,
                countdownValue = 3,
                currentTimeSeconds = 0.0,
                noteResults = emptyMap(),
                noteScores = emptyList(),
                noteAccuracyOverlays = emptyMap(),
                currentNoteIndex = -1,
                songTimeOffsetSeconds = 0.0,
                melodyScore = null
            )
        }

        isWaitingForNote = false
        bestCentsOff = Float.MAX_VALUE
        currentNoteDetectedCorrectly = false

        viewModelScope.launch {
            for (i in 3 downTo 1) {
                _state.update { it.copy(countdownValue = i) }
                delay(1000)
            }

            _state.update { it.copy(phase = PlayPhase.PLAYING) }

            startPitchDetection()
            startPlaybackTimer()
        }
    }

    fun pause() {
        pauseOffset = _state.value.currentTimeSeconds
        playbackJob?.cancel()
        pitchJob?.cancel()
        isWaitingForNote = false
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
        isWaitingForNote = false
        _state.update {
            it.copy(
                phase = PlayPhase.NOT_STARTED,
                currentTimeSeconds = 0.0,
                isListening = false,
                noteResults = emptyMap(),
                noteAccuracyOverlays = emptyMap()
            )
        }
    }

    // --- Gesture callbacks ---

    fun onVerticalZoom(scaleFactor: Float) {
        _state.update { current ->
            val newSpeed = (current.fallSpeedPxPerSec * scaleFactor)
                .coerceIn(MIN_FALL_SPEED, MAX_FALL_SPEED)
            current.copy(fallSpeedPxPerSec = newSpeed)
        }
    }

    fun onVerticalScroll(deltaSeconds: Double) {
        _state.update { current ->
            val track = current.melodyTrack ?: return@update current
            val newOffset = (current.songTimeOffsetSeconds + deltaSeconds)
                .coerceIn(-current.currentTimeSeconds, track.durationSeconds - current.currentTimeSeconds + 2.0)
            current.copy(songTimeOffsetSeconds = newOffset)
        }
    }

    fun scrollToLive() {
        _state.update { it.copy(songTimeOffsetSeconds = 0.0) }
    }

    // --- Playback ---

    private fun startPlaybackTimer() {
        playbackStartTime = SystemClock.elapsedRealtime()
        playbackJob = viewModelScope.launch {
            val track = _state.value.melodyTrack ?: return@launch
            val tempo = _state.value.tempoMultiplier

            while (isActive) {
                if (isWaitingForNote) {
                    // Freeze time progression while waiting for correct note
                    delay(16)
                    continue
                }

                val elapsed = (SystemClock.elapsedRealtime() - playbackStartTime) / 1000.0 * tempo
                val currentTime = pauseOffset + elapsed

                if (currentTime >= track.durationSeconds + 1.0) {
                    finishPlaying()
                    break
                }

                _state.update { it.copy(currentTimeSeconds = currentTime) }
                updateCurrentNote(currentTime)
                delay(16)
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

        // Find the note that should be active at this time
        val noteIndex = track.notes.indexOfFirst { note ->
            val start = track.noteStartSeconds(note)
            val duration = track.noteDurationSeconds(note)
            currentTime >= start && currentTime < start + duration
        }

        if (noteIndex != _state.value.currentNoteIndex && noteIndex >= 0) {
            // Finalize previous note if it wasn't scored
            finalizePreviousNote()

            // New note entered
            val results = _state.value.noteResults.toMutableMap()
            results[noteIndex] = NoteResultState.ACTIVE
            _state.update { it.copy(currentNoteIndex = noteIndex, noteResults = results) }
            currentNoteStartTime = SystemClock.elapsedRealtime()
            currentNoteDetectedCorrectly = false
            bestCentsOff = Float.MAX_VALUE

            // In wait mode, freeze time until correct note is played
            if (_state.value.waitMode) {
                isWaitingForNote = true
                _state.update { it.copy(phase = PlayPhase.WAITING_FOR_NOTE) }
            }
        } else if (noteIndex < 0 && _state.value.currentNoteIndex >= 0) {
            // We've passed the current note without a new one
            finalizePreviousNote()
            _state.update { it.copy(currentNoteIndex = -1) }
        }
    }

    private fun evaluateNote(result: PitchResult) {
        val track = _state.value.melodyTrack ?: return
        val noteIndex = _state.value.currentNoteIndex
        if (noteIndex < 0 || noteIndex >= track.notes.size) return

        val expectedNote = track.notes[noteIndex]
        val expectedMidi = expectedNote.noteNumber
        val detectedMidi = result.midiNoteNumber

        val expectedFreq = noteUtils.midiToFrequency(expectedMidi)
        val centsOff = noteUtils.frequencyToCents(result.frequency, expectedFreq)

        if (abs(centsOff) < abs(bestCentsOff)) {
            bestCentsOff = centsOff
        }

        val isCorrectNote = detectedMidi == expectedMidi && abs(centsOff) <= 50f

        if (isWaitingForNote) {
            if (isCorrectNote) {
                // Correct note played — unfreeze
                currentNoteDetectedCorrectly = true
                currentNoteStartTime = SystemClock.elapsedRealtime()
                isWaitingForNote = false
                // Recalculate playback offset so time resumes from current position
                pauseOffset = _state.value.currentTimeSeconds
                playbackStartTime = SystemClock.elapsedRealtime()
                _state.update { it.copy(phase = PlayPhase.PLAYING) }
            } else if (_state.value.allowWrongNotes) {
                // Wrong note while waiting — score as miss and move on
                scoreCurrentNote(expectedNote, noteIndex)
                isWaitingForNote = false
                pauseOffset = _state.value.currentTimeSeconds
                playbackStartTime = SystemClock.elapsedRealtime()
                _state.update { it.copy(phase = PlayPhase.PLAYING) }
            }
            // If !allowWrongNotes && !isCorrectNote, ignore the input
        } else {
            // Continuous mode (not waiting)
            if (isCorrectNote) {
                currentNoteDetectedCorrectly = true
            }
        }
    }

    private fun finalizePreviousNote() {
        val noteIndex = _state.value.currentNoteIndex
        if (noteIndex < 0) return
        val track = _state.value.melodyTrack ?: return
        if (noteIndex >= track.notes.size) return

        // Skip if already scored
        val existingResult = _state.value.noteResults[noteIndex]
        if (existingResult != null && existingResult != NoteResultState.ACTIVE) return

        val expectedNote = track.notes[noteIndex]
        scoreCurrentNote(expectedNote, noteIndex)
    }

    private fun scoreCurrentNote(expectedNote: com.perfectpitch.midi.NoteEvent, noteIndex: Int) {
        val track = _state.value.melodyTrack ?: return
        val expectedDurationMs = (track.noteDurationSeconds(expectedNote) * 1000).toLong()
        val actualDurationMs = SystemClock.elapsedRealtime() - currentNoteStartTime

        val effectiveCentsOff = if (bestCentsOff == Float.MAX_VALUE) 100f else bestCentsOff

        val performance = NotePerformance(
            expectedMidiNote = expectedNote.noteNumber,
            detectedMidiNote = _state.value.pitchResult.midiNoteNumber,
            centsOff = effectiveCentsOff,
            expectedDurationMs = expectedDurationMs,
            actualDurationMs = actualDurationMs,
            timingOffsetMs = 0
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
            val overlays = current.noteAccuracyOverlays.toMutableMap()
            overlays[noteIndex] = NoteAccuracyOverlay(score.pitchAccuracy, effectiveCentsOff)
            val scores = current.noteScores + score
            current.copy(noteResults = results, noteScores = scores, noteAccuracyOverlays = overlays)
        }
    }

    private fun finishPlaying() {
        // Finalize any remaining note
        finalizePreviousNote()

        // Score any notes that were never reached
        val track = _state.value.melodyTrack ?: return
        val currentResults = _state.value.noteResults
        for (i in track.notes.indices) {
            if (i !in currentResults) {
                val note = track.notes[i]
                val expectedDurationMs = (track.noteDurationSeconds(note) * 1000).toLong()
                val performance = NotePerformance(
                    expectedMidiNote = note.noteNumber,
                    detectedMidiNote = 0,
                    centsOff = 100f,
                    expectedDurationMs = expectedDurationMs,
                    actualDurationMs = 0,
                    timingOffsetMs = expectedDurationMs
                )
                val score = scoringEngine.scoreNote(performance)
                _state.update { current ->
                    val results = current.noteResults.toMutableMap()
                    results[i] = NoteResultState.MISS
                    val overlays = current.noteAccuracyOverlays.toMutableMap()
                    overlays[i] = NoteAccuracyOverlay(PitchAccuracy.MISS, 100f)
                    current.copy(
                        noteResults = results,
                        noteScores = current.noteScores + score,
                        noteAccuracyOverlays = overlays
                    )
                }
            }
        }

        playbackJob?.cancel()
        pitchJob?.cancel()
        pauseOffset = 0.0
        isWaitingForNote = false

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
