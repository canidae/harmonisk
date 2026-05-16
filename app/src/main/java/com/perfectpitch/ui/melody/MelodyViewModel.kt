package com.perfectpitch.ui.melody

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.MelodyPlayer
import com.perfectpitch.audio.MelodySynthesizer
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.AudioDelayProfileRepository
import com.perfectpitch.data.AudioInputDelayRepository
import com.perfectpitch.data.MelodyRepository
import com.perfectpitch.data.MelodySettingsRepository
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
    private val melodySettingsRepository: MelodySettingsRepository,
    private val settingsRepository: SettingsRepository,
    private val scoringEngine: ScoringEngine,
    private val noteUtils: NoteUtils,
    private val melodySynthesizer: MelodySynthesizer,
    private val melodyPlayer: MelodyPlayer,
    private val audioDelayProfileRepository: AudioDelayProfileRepository,
    private val audioInputDelayRepository: AudioInputDelayRepository
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
    private var currentNoteDetectedCorrectly = false
    private var centsOffSum = 0f
    private var centsOffCount = 0
    private var correctPitchFrameCount = 0
    private var isWaitingForNote = false
    private var wasWaitingForNote = false

    private var originalMelodyTrack: MelodyTrack? = null
    private var currentMelodyId: String? = null

    // Melody audio output delay
    private var synthOpenJob: Job? = null
    // Input delay for backdating pitch detection timestamps
    private var audioInputDelayMs: Int = 0

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
            }
        }
        // Observe per-device audio output delay and update the melody player
        viewModelScope.launch {
            audioDelayProfileRepository.activeDelay.collect { delayMs ->
                melodyPlayer.setAudioOutputDelay(delayMs.toLong())
            }
        }
        // Observe per-device audio input delay for pitch timestamp correction
        viewModelScope.launch {
            audioInputDelayRepository.activeDelay.collect { delayMs ->
                audioInputDelayMs = delayMs
            }
        }
    }

    val continuousPitchLine: StateFlow<Boolean> = settingsRepository.settings
        .map { it.continuousPitchLine }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun getAvailableMelodies(): List<MelodyRepository.MelodyInfo> {
        return melodyRepository.getAvailableMelodies()
    }

    fun loadMelody(info: MelodyRepository.MelodyInfo): Boolean {
        val track = melodyRepository.loadMelody(info)
        if (track != null) {
            originalMelodyTrack = track
            currentMelodyId = info.id
            val range = track.noteRange
            val viewMin = (range.first - NOTE_RANGE_PADDING).toFloat().coerceAtLeast(21f)
            val viewMax = (range.last + NOTE_RANGE_PADDING).toFloat().coerceAtMost(108f)
            _state.update {
                it.copy(
                    melodyTrack = track,
                    phase = PlayPhase.NOT_STARTED,
                    viewMinMidi = viewMin,
                    viewMaxMidi = viewMax,
                    transposeSemitones = 0
                )
            }
            // Load per-melody settings
            viewModelScope.launch {
                melodySettingsRepository.getSettings(info.id).first().let { saved ->
                    _state.update {
                        it.copy(
                            waitMode = saved.waitMode,
                            allowWrongNotes = saved.allowWrongNotes,
                            tempoMultiplier = saved.tempoMultiplier,
                            playMelodyAudio = saved.playMelodyAudio
                        )
                    }
                    if (saved.transposeSemitones != 0) {
                        setTranspose(saved.transposeSemitones)
                    }
                }
            }
            return true
        }
        return false
    }

    fun loadMelodyFromUri(uri: android.net.Uri): Boolean {
        val track = melodyRepository.loadFromUri(uri, "Imported MIDI")
        if (track != null) {
            originalMelodyTrack = track
            val range = track.noteRange
            val viewMin = (range.first - NOTE_RANGE_PADDING).toFloat().coerceAtLeast(21f)
            val viewMax = (range.last + NOTE_RANGE_PADDING).toFloat().coerceAtMost(108f)
            _state.update {
                it.copy(
                    melodyTrack = track,
                    phase = PlayPhase.NOT_STARTED,
                    viewMinMidi = viewMin,
                    viewMaxMidi = viewMax,
                    transposeSemitones = 0
                )
            }
            return true
        }
        return false
    }

    fun setWaitMode(enabled: Boolean) {
        _state.update { it.copy(waitMode = enabled) }
        saveMelodySettings()
    }

    fun setAllowWrongNotes(enabled: Boolean) {
        _state.update { it.copy(allowWrongNotes = enabled) }
        saveMelodySettings()
    }

    fun setTempoMultiplier(multiplier: Float) {
        _state.update { it.copy(tempoMultiplier = multiplier.coerceIn(0.5f, 1.5f)) }
        saveMelodySettings()
    }

    fun setTranspose(semitones: Int) {
        val clamped = semitones.coerceIn(-36, 36)
        val base = originalMelodyTrack ?: return
        val transposed = base.transpose(clamped)
        val range = transposed.noteRange
        val viewMin = (range.first - NOTE_RANGE_PADDING).toFloat().coerceAtLeast(21f)
        val viewMax = (range.last + NOTE_RANGE_PADDING).toFloat().coerceAtMost(108f)
        _state.update {
            it.copy(
                melodyTrack = transposed,
                transposeSemitones = clamped,
                viewMinMidi = viewMin,
                viewMaxMidi = viewMax
            )
        }
        saveMelodySettings()
    }

    private fun saveMelodySettings() {
        val id = currentMelodyId ?: return
        val s = _state.value
        viewModelScope.launch {
            melodySettingsRepository.saveSettings(id, MelodySettingsRepository.MelodySettings(
                waitMode = s.waitMode,
                allowWrongNotes = s.allowWrongNotes,
                tempoMultiplier = s.tempoMultiplier,
                transposeSemitones = s.transposeSemitones,
                playMelodyAudio = s.playMelodyAudio
            ))
        }
    }

    // --- Melody preview / listen ---

    /**
     * Open the synthesizer with the provided context.
     * Call this from the Composable layer where context is available.
     */
    fun initSynthesizer(context: android.content.Context) {
        if (melodySynthesizer.isReady || synthOpenJob?.isActive == true) return
        synthOpenJob = viewModelScope.launch {
            try {
                melodySynthesizer.open(context)
            } catch (e: Exception) {
                android.util.Log.w("MelodyViewModel", "Synth init failed", e)
            }
        }
    }

    /** Preview the loaded melody (audio only, standalone playback). */
    fun previewMelody() {
        val track = _state.value.melodyTrack ?: return
        if (!melodySynthesizer.isReady) return
        melodyPlayer.playWithTiming(track, viewModelScope, _state.value.tempoMultiplier)
    }

    /** Preview a specific melody info (loads and plays). */
    fun previewMelody(info: MelodyRepository.MelodyInfo) {
        val track = melodyRepository.loadMelody(info) ?: return
        if (!melodySynthesizer.isReady) return
        val tempo = _state.value.tempoMultiplier
        melodyPlayer.playWithTiming(track, viewModelScope, tempo)
    }

    /** Stop any ongoing melody preview. */
    fun stopPreview() {
        melodyPlayer.stop()
    }

    /** Whether a melody preview is currently playing. */
    val isPreviewPlaying: StateFlow<Boolean> = melodyPlayer.isPlaying

    /** Toggle in-game melody audio playback. */
    fun setPlayMelodyAudio(enabled: Boolean) {
        _state.update { it.copy(playMelodyAudio = enabled) }
        if (!enabled) {
            melodyPlayer.resetGameSync()
        }
        saveMelodySettings()
    }

    /** Enter READY phase — melody visible but not playing. */
    fun enterReadyPhase() {
        if (_state.value.phase == PlayPhase.NOT_STARTED) {
            _state.update { it.copy(phase = PlayPhase.READY) }
        }
    }

    fun startPlaying() {
        val track = _state.value.melodyTrack ?: return
        if (_state.value.phase == PlayPhase.COUNTDOWN || _state.value.phase == PlayPhase.PLAYING) return

        val countdownDuration = _state.value.countdownDurationSeconds

        _state.update {
            it.copy(
                phase = PlayPhase.COUNTDOWN,
                currentTimeSeconds = -countdownDuration,
                noteResults = emptyMap(),
                noteScores = emptyList(),
                currentNoteIndex = -1,
                songTimeOffsetSeconds = 0.0,
                melodyScore = null,
                pitchTrace = emptyList()
            )
        }

        isWaitingForNote = false
        wasWaitingForNote = false
        centsOffSum = 0f
        centsOffCount = 0
        correctPitchFrameCount = 0
        currentNoteDetectedCorrectly = false

        // Stop any preview playback and reset game sync
        melodyPlayer.stop()
        melodyPlayer.resetGameSync()

        // Start the playback timer immediately — bars begin falling during countdown.
        // Time counts from -countdownDuration toward 0.0.
        // Pitch detection starts when currentTimeSeconds reaches 0.0 (transition to PLAYING).
        pauseOffset = -countdownDuration
        startPlaybackTimer()
    }

    fun pause() {
        pauseOffset = _state.value.currentTimeSeconds
        playbackJob?.cancel()
        pitchJob?.cancel()
        // Let currently playing notes finish naturally (no allNotesOff)
        wasWaitingForNote = isWaitingForNote
        isWaitingForNote = false
        _state.update { it.copy(phase = PlayPhase.PAUSED, isListening = false) }
    }

    fun resume() {
        if (wasWaitingForNote) {
            isWaitingForNote = true
            wasWaitingForNote = false
            _state.update { it.copy(phase = PlayPhase.WAITING_FOR_NOTE) }
        } else if (_state.value.currentTimeSeconds < 0.0) {
            // Still in countdown period — resume countdown
            _state.update { it.copy(phase = PlayPhase.COUNTDOWN) }
        } else {
            _state.update { it.copy(phase = PlayPhase.PLAYING) }
        }
        if (_state.value.currentTimeSeconds >= 0.0) {
            startPitchDetection()
        }
        startPlaybackTimer()
    }

    fun stop() {
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        pauseOffset = 0.0
        isWaitingForNote = false
        wasWaitingForNote = false
        _state.update {
            it.copy(
                phase = PlayPhase.NOT_STARTED,
                currentTimeSeconds = 0.0,
                isListening = false,
                noteResults = emptyMap(),
                pitchTrace = emptyList()
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

    fun scrollToBeginning() {
        val track = _state.value.melodyTrack ?: return
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        _state.update {
            it.copy(songTimeOffsetSeconds = firstNoteStart - it.currentTimeSeconds)
        }
    }

    // --- Playback ---

    private fun startPlaybackTimer() {
        playbackStartTime = SystemClock.elapsedRealtime()
        playbackJob = viewModelScope.launch {
            val track = _state.value.melodyTrack ?: return@launch
            val tempo = _state.value.tempoMultiplier
            var pitchDetectionStarted = _state.value.phase == PlayPhase.PLAYING

            while (isActive) {
                if (isWaitingForNote) {
                    delay(16)
                    continue
                }

                val elapsed = (SystemClock.elapsedRealtime() - playbackStartTime) / 1000.0 * tempo
                val currentTime = pauseOffset + elapsed

                // Transition from COUNTDOWN to PLAYING when time reaches 0.0
                if (!pitchDetectionStarted && currentTime >= 0.0) {
                    pitchDetectionStarted = true
                    _state.update { it.copy(phase = PlayPhase.PLAYING) }
                    startPitchDetection()
                }

                if (currentTime >= track.durationSeconds + 1.0) {
                    finishPlaying()
                    break
                }

                _state.update { it.copy(currentTimeSeconds = currentTime) }

                // Only track notes once we're past the countdown
                if (currentTime >= 0.0) {
                    updateCurrentNote(currentTime)

                    // Feed melody audio to synthesizer if enabled
                    if (_state.value.playMelodyAudio && melodySynthesizer.isReady) {
                        melodyPlayer.updateGameTime(currentTime, track)
                    }
                }

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
                    recordPitchFrame(result)
                    evaluateNote(result)
                }
            }
        }
    }

    private fun recordPitchFrame(result: PitchResult) {
        val songTime = _state.value.currentTimeSeconds
        if (_state.value.phase != PlayPhase.PLAYING && _state.value.phase != PlayPhase.WAITING_FOR_NOTE) return

        // Backdate timestamp by input delay to align with when user actually sang
        val adjustedTime = songTime - (audioInputDelayMs / 1000.0)

        val frame = PitchFrame(
            timeSeconds = adjustedTime,
            midiNote = result.midiNoteNumber,
            centsOff = result.centsOff,
            frequency = result.frequency
        )
        _state.update { it.copy(pitchTrace = it.pitchTrace + frame) }
    }

    private fun updateCurrentNote(currentTime: Double) {
        val track = _state.value.melodyTrack ?: return
        val adjustedTime = currentTime

        // Find the note that should be active at the latency-adjusted time
        val noteIndex = track.notes.indexOfFirst { note ->
            val start = track.noteStartSeconds(note)
            val duration = track.noteDurationSeconds(note)
            adjustedTime >= start && adjustedTime < start + duration
        }

        if (noteIndex != _state.value.currentNoteIndex && noteIndex >= 0) {
            // Finalize previous note if it wasn't scored
            finalizePreviousNote()

            // New note entered
            val results = _state.value.noteResults.toMutableMap()
            results[noteIndex] = NoteResultState.ACTIVE
            _state.update { it.copy(currentNoteIndex = noteIndex, noteResults = results) }
            currentNoteDetectedCorrectly = false
            centsOffSum = 0f
            centsOffCount = 0
            correctPitchFrameCount = 0

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

        // Accumulate cents-off when player is on the correct MIDI note
        if (detectedMidi == expectedMidi) {
            centsOffSum += abs(centsOff)
            centsOffCount++
            // Count frames where pitch is within non-MISS range for duration scoring
            if (abs(centsOff) <= 50f) {
                correctPitchFrameCount++
            }
        }

        val isCorrectNote = detectedMidi == expectedMidi && abs(centsOff) <= 50f

        if (isWaitingForNote) {
            if (isCorrectNote) {
                // Correct note played — unfreeze
                currentNoteDetectedCorrectly = true
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
            if (isCorrectNote && !currentNoteDetectedCorrectly) {
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
        // Duration = time player held correct pitch (each frame ≈ 93ms)
        val actualDurationMs = correctPitchFrameCount * 93L

        val effectiveCentsOff = if (centsOffCount > 0) centsOffSum / centsOffCount else 100f

        val performance = NotePerformance(
            expectedMidiNote = expectedNote.noteNumber,
            detectedMidiNote = _state.value.pitchResult.midiNoteNumber,
            centsOff = effectiveCentsOff,
            expectedDurationMs = expectedDurationMs,
            actualDurationMs = actualDurationMs
        )

        val score = scoringEngine.scoreNote(performance)
        val resultState = when (score.pitchAccuracy) {
            PitchAccuracy.PERFECT -> NoteResultState.PERFECT
            PitchAccuracy.GREAT -> NoteResultState.GREAT
            PitchAccuracy.GOOD -> NoteResultState.GOOD
            PitchAccuracy.OK -> NoteResultState.OK
            PitchAccuracy.MISS -> NoteResultState.MISS
        }

        _state.update { current ->
            val results = current.noteResults.toMutableMap()
            results[noteIndex] = resultState
            current.copy(noteResults = results, noteScores = current.noteScores + score)
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
                    actualDurationMs = 0
                )
                val score = scoringEngine.scoreNote(performance)
                _state.update { current ->
                    val results = current.noteResults.toMutableMap()
                    results[i] = NoteResultState.MISS
                    current.copy(
                        noteResults = results,
                        noteScores = current.noteScores + score
                    )
                }
            }
        }

        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        pauseOffset = 0.0
        isWaitingForNote = false

        val noteScores = _state.value.noteScores
        val melodyScore = scoringEngine.calculateMelodyScore(noteScores)

        // Auto-zoom to fit entire melody and scroll to beginning
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val songDuration = track.durationSeconds
        val fitSpeed = if (songDuration > 0) (700.0 / songDuration).toFloat() else MIN_FALL_SPEED
        val clampedSpeed = fitSpeed.coerceIn(MIN_FALL_SPEED, MAX_FALL_SPEED)

        _state.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                melodyScore = melodyScore,
                isListening = false,
                fallSpeedPxPerSec = clampedSpeed,
                songTimeOffsetSeconds = firstNoteStart - it.currentTimeSeconds
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.stop()
        melodySynthesizer.close()
    }
}
