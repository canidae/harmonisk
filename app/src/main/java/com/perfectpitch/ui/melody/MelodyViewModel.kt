package com.perfectpitch.ui.melody

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.MelodyPlayer
import com.perfectpitch.audio.MelodySynthesizer
import com.perfectpitch.audio.NotePlayer
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.AudioDelayRepository
import com.perfectpitch.data.MelodyRepository
import com.perfectpitch.data.MelodySettingsRepository
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.midi.MidiDeviceManager
import com.perfectpitch.midi.MidiInputReceiver
import com.perfectpitch.midi.MidiNoteEvent
import com.perfectpitch.midi.MidiOutputSender
import com.perfectpitch.music.*
import com.perfectpitch.ui.components.PitchFrame
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
    private val audioDelayRepository: AudioDelayRepository,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val midiDeviceManager: MidiDeviceManager,
    private val midiInputReceiver: MidiInputReceiver,
    private val midiOutputSender: MidiOutputSender
) : ViewModel() {

    companion object {
        const val MIN_FALL_SPEED = 30f
        const val MAX_FALL_SPEED = 600f
        const val NOTE_RANGE_PADDING = 3
        /** Extra grace time beyond calibrated input delay — musically acceptable lateness. */
        const val GRACE_PERIOD_EXTRA_MS = 150L
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
    // Grace period: time continues advancing for inputDelay + extra before freezing
    private var waitGraceStartTime = 0L
    private var waitGraceDurationMs = 0L

    private var originalMelodyTrack: MelodyTrack? = null
    private var currentMelodyId: String? = null

    // Melody audio output delay
    private var synthOpenJob: Job? = null
    // Input delay for backdating pitch detection timestamps
    private var audioInputDelayMs: Int = 0
    // Output delay for firing melody audio early enough to compensate for speaker latency
    private var audioOutputDelayMs: Int = 0

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
            }
        }
        // Observe per-device audio output delay and update the melody player
        viewModelScope.launch {
            audioDelayRepository.activeOutputDelay.collect { delayMs ->
                audioOutputDelayMs = delayMs
                melodyPlayer.setAudioOutputDelay(delayMs.toLong())
            }
        }
        // Observe per-device audio input delay for pitch timestamp correction
        viewModelScope.launch {
            audioDelayRepository.activeInputDelay.collect { delayMs ->
                audioInputDelayMs = delayMs
            }
        }
        // Observe MIDI availability
        viewModelScope.launch {
            midiDeviceManager.isMidiInputAvailable.collect { available ->
                _state.update { it.copy(midiAvailable = available) }
            }
        }
        // Observe effective MIDI input enabled state
        viewModelScope.launch {
            midiDeviceManager.effectiveMidiInputEnabled.collect { enabled ->
                _state.update { it.copy(midiInputEnabled = enabled) }
            }
        }
        // Observe effective MIDI output enabled state — switch NotePlayer accordingly
        viewModelScope.launch {
            midiDeviceManager.effectiveMidiOutputEnabled.collect { enabled ->
                _state.update { it.copy(midiOutputEnabled = enabled) }
                val player: NotePlayer = if (enabled && midiOutputSender.isReady) midiOutputSender else melodySynthesizer
                melodyPlayer.setNotePlayer(player)
            }
        }
    }

    val continuousPitchLine: StateFlow<Boolean> = settingsRepository.settings
        .map { it.continuousPitchLine }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Exposed for the in-menu audio delay split slider
    val activeSplitPercent: StateFlow<Float> = audioDelayRepository.activeSplitPercent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioDelayRepository.DEFAULT_OUTPUT_SPLIT)

    val activeOutputDelay: StateFlow<Int> = audioDelayRepository.activeOutputDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeInputDelay: StateFlow<Int> = audioDelayRepository.activeInputDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Emits a device name when an uncalibrated device is detected, null otherwise. */
    val uncalibratedDevicePrompt: StateFlow<String?> = combine(
        audioDeviceMonitor.currentInputDevice,
        audioDeviceMonitor.currentDevice,
        audioDelayRepository.allProfiles
    ) { inputDevice, outputDevice, profiles ->
        // Check if the current input device has a stored calibration
        if (!profiles.containsKey(inputDevice.deviceKey)) {
            return@combine inputDevice.name
        }
        // Check if the current output device has a stored calibration
        if (!profiles.containsKey(outputDevice.deviceKey)) {
            return@combine outputDevice.name
        }
        null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var calibrationPromptDismissed = false

    fun dismissCalibrationPrompt() {
        calibrationPromptDismissed = true
    }

    fun shouldShowCalibrationPrompt(): Boolean = !calibrationPromptDismissed

    fun toggleMidiInput() {
        val current = midiDeviceManager.inputOverride.value
        val effective = _state.value.midiInputEnabled
        // If currently auto-enabled, force off; if off (auto or forced), force on; if force-on, return to auto
        val newOverride: Boolean? = when {
            current == true -> null  // force-on → back to auto
            effective -> false       // auto-on → force off
            else -> true             // off → force on
        }
        midiDeviceManager.setInputOverride(newOverride)
    }

    fun toggleMidiOutput() {
        val current = midiDeviceManager.outputOverride.value
        val effective = _state.value.midiOutputEnabled
        val newOverride: Boolean? = when {
            current == true -> null
            effective -> false
            else -> true
        }
        midiDeviceManager.setOutputOverride(newOverride)
    }

    fun saveSplitPercent(percent: Float) {
        val outputDevice = audioDeviceMonitor.currentDevice.value
        val inputDevice = audioDeviceMonitor.currentInputDevice.value
        viewModelScope.launch {
            audioDelayRepository.setSplitPercent(outputDevice.deviceKey, percent)
            audioDelayRepository.setSplitPercent(inputDevice.deviceKey, percent)
        }
    }

    fun getAvailableMelodies(): List<MelodyRepository.MelodyInfo> {
        return melodyRepository.getAvailableMelodies()
    }

    fun loadMelody(info: MelodyRepository.MelodyInfo): Boolean {
        val track = melodyRepository.loadMelody(info)
        if (track != null) {
            originalMelodyTrack = track
            currentMelodyId = info.id
            val (viewMin, viewMax) = viewRangeFor(track)
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
                            playMelodyAudio = saved.playMelodyAudio,
                            fallSpeedPxPerSec = saved.fallSpeedPxPerSec
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
            val (viewMin, viewMax) = viewRangeFor(track)
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
        val clamped = semitones.coerceIn(-24, 24)
        val base = originalMelodyTrack ?: return
        val transposed = base.transpose(clamped)
        val (viewMin, viewMax) = viewRangeFor(transposed)
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

    private fun viewRangeFor(track: MelodyTrack): Pair<Float, Float> {
        val range = track.noteRange
        return Pair(
            (range.first - NOTE_RANGE_PADDING).toFloat().coerceAtLeast(21f),
            (range.last + NOTE_RANGE_PADDING).toFloat().coerceAtMost(108f)
        )
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
                playMelodyAudio = s.playMelodyAudio,
                fallSpeedPxPerSec = s.fallSpeedPxPerSec
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

    /** Enter READY phase — melody visible but not playing. Bars pre-positioned at countdown offset. */
    fun enterReadyPhase() {
        if (_state.value.phase == PlayPhase.NOT_STARTED) {
            val countdown = _state.value.countdownDurationSeconds
            _state.update { it.copy(phase = PlayPhase.READY, currentTimeSeconds = -countdown) }
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
        melodyPlayer.resetGameSync()
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
        saveMelodySettings()
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
                // Grace period: continue advancing time for a while after entering wait mode
                if (isWaitingForNote) {
                    val graceElapsed = SystemClock.elapsedRealtime() - waitGraceStartTime
                    if (graceElapsed >= waitGraceDurationMs) {
                        // Grace expired — actually freeze time
                        delay(16)
                        continue
                    }
                    // Still in grace period — keep time moving (fall through)
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
                }

                // Feed melody audio earlier than t=0 to compensate for output latency.
                // updateGameTime uses lookahead internally, so we must call it
                // starting at currentTime = -outputDelay so the first note fires on time.
                if (_state.value.playMelodyAudio && melodySynthesizer.isReady &&
                    currentTime >= -(audioOutputDelayMs / 1000.0)) {
                    melodyPlayer.updateGameTime(currentTime, track)
                }

                delay(16)
            }
        }
    }

    private fun pitchResultFromMidi(midiNote: Int): PitchResult {
        val noteIndex = ((midiNote % 12) + 12) % 12
        val octave = (midiNote / 12) - 1
        return PitchResult(
            frequency = noteUtils.midiToFrequency(midiNote),
            noteName = NoteUtils.NOTE_NAMES[noteIndex],
            octave = octave,
            centsOff = 0f,
            midiNoteNumber = midiNote,
            confidence = 1.0f,
            rmsDb = -20f,
            isSilent = false
        )
    }

    private fun startPitchDetection() {
        pitchJob = viewModelScope.launch {
            _state.update { it.copy(isListening = true) }
            if (_state.value.midiInputEnabled) {
                // MIDI input: convert note events to PitchResult
                val heldNotes = mutableSetOf<Int>()
                midiInputReceiver.midiEventFlow().collect { event ->
                    when (event) {
                        is MidiNoteEvent.NoteOn -> {
                            heldNotes.add(event.midiNote)
                            val result = pitchResultFromMidi(event.midiNote)
                            _state.update { it.copy(pitchResult = result) }
                            recordPitchFrame(result)
                            evaluateNote(result)
                        }
                        is MidiNoteEvent.NoteOff -> {
                            heldNotes.remove(event.midiNote)
                            if (heldNotes.isEmpty()) {
                                _state.update { it.copy(pitchResult = PitchResult.SILENT) }
                            } else {
                                val result = pitchResultFromMidi(heldNotes.last())
                                _state.update { it.copy(pitchResult = result) }
                            }
                        }
                    }
                }
            } else {
                pitchProcessor.pitchFlow().collect { result ->
                    _state.update { it.copy(pitchResult = result) }
                    if (result.isValid) {
                        recordPitchFrame(result)
                        evaluateNote(result)
                    }
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
            timeMs = (adjustedTime * 1000).toLong(),
            midiNote = result.midiNoteNumber,
            centsOff = result.centsOff,
            frequency = result.frequency
        )
        _state.update { it.copy(pitchTrace = it.pitchTrace + frame) }
    }

    private fun updateCurrentNote(currentTime: Double) {
        val track = _state.value.melodyTrack ?: return
        // Backdate by input delay to match the audio being evaluated right now
        val adjustedTime = currentTime - audioInputDelayMs / 1000.0

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

            // In wait mode, start grace period before freezing
            if (_state.value.waitMode) {
                isWaitingForNote = true
                waitGraceStartTime = SystemClock.elapsedRealtime()
                waitGraceDurationMs = audioInputDelayMs.toLong() + GRACE_PERIOD_EXTRA_MS
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

        val expectedFreq = noteUtils.midiToFrequency(expectedMidi)
        val centsOff = noteUtils.frequencyToCents(result.frequency, expectedFreq)

        // Count frames where the detected frequency is within the acceptance window of the
        // expected note. Using centsOff (relative to the expected note) as the sole gate means
        // vibrato peaks that round to a neighbouring MIDI number are still counted correctly,
        // instead of being dropped by a strict MIDI equality check.
        if (abs(centsOff) <= 50f) {
            centsOffSum += abs(centsOff)
            centsOffCount++
            correctPitchFrameCount++
        }

        val isCorrectNote = abs(centsOff) <= 50f

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
        // Duration = time player held correct pitch, derived from the actual audio chunk size
        val frameDurationMs = com.perfectpitch.audio.AudioCaptureService.BUFFER_SIZE_SAMPLES * 1000L /
            com.perfectpitch.audio.AudioCaptureService.SAMPLE_RATE
        val actualDurationMs = correctPitchFrameCount * frameDurationMs

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

        // Scroll to beginning without changing zoom level
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0

        _state.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                melodyScore = melodyScore,
                isListening = false,
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
