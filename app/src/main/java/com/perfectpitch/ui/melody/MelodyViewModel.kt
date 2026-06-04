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
import com.perfectpitch.data.ScoreHistoryRepository
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.midi.MidiDeviceManager
import com.perfectpitch.midi.MidiInputReceiver
import com.perfectpitch.midi.MidiNoteEvent
import com.perfectpitch.midi.MidiOutputSender
import com.perfectpitch.music.*
import com.perfectpitch.ui.MAX_FALL_SPEED
import com.perfectpitch.ui.MIN_FALL_SPEED
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
    private val midiOutputSender: MidiOutputSender,
    private val scoreHistoryRepository: ScoreHistoryRepository
) : ViewModel() {

    companion object {
        const val NOTE_RANGE_PADDING = 3
        /** Extra grace time beyond calibrated input delay — musically acceptable lateness. */
        const val GRACE_PERIOD_EXTRA_MS = 150L
        val FRAME_DURATION_MS = com.perfectpitch.audio.AudioCaptureService.BUFFER_SIZE_SAMPLES * 1000f /
            com.perfectpitch.audio.AudioCaptureService.SAMPLE_RATE
    }

    private val _gameState = MutableStateFlow(MelodyGameState())
    val gameState: StateFlow<MelodyGameState> = _gameState.asStateFlow()

    private val _viewState = MutableStateFlow(MelodyViewState())
    val viewState: StateFlow<MelodyViewState> = _viewState.asStateFlow()

    private val _deviceState = MutableStateFlow(MelodyDeviceState())
    val deviceState: StateFlow<MelodyDeviceState> = _deviceState.asStateFlow()

    val availableMelodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        MutableStateFlow(melodyRepository.getAvailableMelodies()).asStateFlow()

    val currentScoreHistory: StateFlow<List<ScoreHistoryRepository.ScoreEntry>> =
        MutableStateFlow(emptyList())

    private val _allMelodyAverages = MutableStateFlow<Map<String, Float>>(emptyMap())
    val allMelodyAverages: StateFlow<Map<String, Float>> = _allMelodyAverages.asStateFlow()

    private var playbackJob: Job? = null
    private var pitchJob: Job? = null
    private var playbackStartTime = 0L
    private var pauseOffset = 0.0

    // Per-note tracking state (encapsulated for a single reset() call)
    private val noteTracking = NoteTrackingState()
    private var currentNoteDetectedCorrectly = false

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
                _deviceState.update { it.copy(midiAvailable = available) }
            }
        }
        // Observe effective MIDI input enabled state
        viewModelScope.launch {
            midiDeviceManager.effectiveMidiInputEnabled.collect { enabled ->
                _deviceState.update { it.copy(midiInputEnabled = enabled) }
            }
        }
        // Observe effective MIDI output enabled state — switch NotePlayer accordingly
        viewModelScope.launch {
            midiDeviceManager.effectiveMidiOutputEnabled.collect { enabled ->
                _deviceState.update { it.copy(midiOutputEnabled = enabled) }
                val player: NotePlayer = if (enabled && midiOutputSender.isReady) midiOutputSender else melodySynthesizer
                melodyPlayer.setNotePlayer(player)
            }
        }
        // Load score averages for all melodies
        loadAllMelodyAverages()
    }

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
        midiDeviceManager.setInputOverride(
            computeNextOverride(midiDeviceManager.inputOverride.value, _deviceState.value.midiInputEnabled)
        )
    }

    fun toggleMidiOutput() {
        midiDeviceManager.setOutputOverride(
            computeNextOverride(midiDeviceManager.outputOverride.value, _deviceState.value.midiOutputEnabled)
        )
    }

    private fun computeNextOverride(current: Boolean?, effective: Boolean): Boolean? = when {
        current == true -> null  // force-on → back to auto
        effective -> false       // auto-on → force off
        else -> true             // off → force on
    }

    fun saveSplitPercent(percent: Float) {
        viewModelScope.launch { audioDelayRepository.setSplitPercentForCurrentDevices(percent) }
    }

    fun getAvailableMelodies(): List<MelodyRepository.MelodyInfo> =
        melodyRepository.getAvailableMelodies()

    fun loadAllMelodyAverages() {
        viewModelScope.launch {
            melodyRepository.getAvailableMelodies().let { melodies ->
                val flows = melodies.map { info ->
                    scoreHistoryRepository.getRecentAverage(info.id).map { avg ->
                        info.id to avg
                    }
                }
                combine(flows) { pairs ->
                    pairs.filter { it.second != null }.associate { it.first to it.second!! }
                }.collect { result ->
                    _allMelodyAverages.value = result
                }
            }
        }
    }

    fun loadMelody(info: MelodyRepository.MelodyInfo): Boolean {
        val track = melodyRepository.loadMelody(info)
        if (track != null) {
            originalMelodyTrack = track
            currentMelodyId = info.id
            val (viewMin, viewMax) = viewRangeFor(track)
            _gameState.update {
                it.copy(
                    melodyTrack = track,
                    phase = PlayPhase.NOT_STARTED,
                    transposeSemitones = 0
                )
            }
            _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
            // Load per-melody settings
            viewModelScope.launch {
                melodySettingsRepository.getSettings(info.id).first().let { saved ->
                    _gameState.update {
                        it.copy(
                            waitMode = saved.waitMode,
                            allowWrongNotes = saved.allowWrongNotes,
                            tempoMultiplier = saved.tempoMultiplier,
                            playMelodyAudio = saved.playMelodyAudio
                        )
                    }
                    _viewState.update { it.copy(fallSpeedPxPerSec = saved.fallSpeedPxPerSec) }
                    if (saved.transposeSemitones != 0) {
                        setTranspose(saved.transposeSemitones)
                    }
                }
            }
            // Load score history for this melody
            viewModelScope.launch {
                scoreHistoryRepository.getScores(info.id).collect { scores ->
                    (currentScoreHistory as MutableStateFlow).value = scores
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
            currentMelodyId = melodyRepository.uriMelodyId(uri)
            val (viewMin, viewMax) = viewRangeFor(track)
            _gameState.update {
                it.copy(
                    melodyTrack = track,
                    phase = PlayPhase.NOT_STARTED,
                    transposeSemitones = 0
                )
            }
            _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
            // Load any previously saved per-melody settings for this URI
            viewModelScope.launch {
                melodySettingsRepository.getSettings(currentMelodyId!!).first().let { saved ->
                    _gameState.update {
                        it.copy(
                            waitMode = saved.waitMode,
                            allowWrongNotes = saved.allowWrongNotes,
                            tempoMultiplier = saved.tempoMultiplier,
                            playMelodyAudio = saved.playMelodyAudio
                        )
                    }
                    _viewState.update { it.copy(fallSpeedPxPerSec = saved.fallSpeedPxPerSec) }
                    if (saved.transposeSemitones != 0) {
                        setTranspose(saved.transposeSemitones)
                    }
                }
            }
            // Load score history for this melody
            viewModelScope.launch {
                scoreHistoryRepository.getScores(currentMelodyId!!).collect { scores ->
                    (currentScoreHistory as MutableStateFlow).value = scores
                }
            }
            return true
        }
        return false
    }

    fun setWaitMode(enabled: Boolean) {
        _gameState.update { it.copy(waitMode = enabled) }
        saveMelodySettings()
    }

    fun setAllowWrongNotes(enabled: Boolean) {
        _gameState.update { it.copy(allowWrongNotes = enabled) }
        saveMelodySettings()
    }

    fun setTempoMultiplier(multiplier: Float) {
        _gameState.update { it.copy(tempoMultiplier = multiplier.coerceIn(0.5f, 1.5f)) }
        saveMelodySettings()
    }

    fun setTranspose(semitones: Int) {
        val clamped = semitones.coerceIn(-24, 24)
        val base = originalMelodyTrack ?: return
        val transposed = base.transpose(clamped)
        val (viewMin, viewMax) = viewRangeFor(transposed)
        _gameState.update {
            it.copy(
                melodyTrack = transposed,
                transposeSemitones = clamped
            )
        }
        _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
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
        val g = _gameState.value
        val v = _viewState.value
        viewModelScope.launch {
            melodySettingsRepository.saveSettings(id, MelodySettingsRepository.MelodySettings(
                waitMode = g.waitMode,
                allowWrongNotes = g.allowWrongNotes,
                tempoMultiplier = g.tempoMultiplier,
                transposeSemitones = g.transposeSemitones,
                playMelodyAudio = g.playMelodyAudio,
                fallSpeedPxPerSec = v.fallSpeedPxPerSec
            ))
        }
    }

    // --- Melody preview / listen ---

    /**
     * Open the synthesizer. Call this before using audio playback.
     */
    fun initSynthesizer() {
        if (melodySynthesizer.isReady || synthOpenJob?.isActive == true) return
        synthOpenJob = viewModelScope.launch {
            try {
                melodySynthesizer.open()
            } catch (e: Exception) {
                android.util.Log.w("MelodyViewModel", "Synth init failed", e)
            }
        }
    }

    /** Preview the loaded melody (audio only, standalone playback). */
    fun previewMelody() {
        val track = _gameState.value.melodyTrack ?: return
        if (!melodySynthesizer.isReady) return
        melodyPlayer.playWithTiming(track, viewModelScope, _gameState.value.tempoMultiplier)
    }

    /** Preview a specific melody info (loads and plays). */
    fun previewMelody(info: MelodyRepository.MelodyInfo) {
        val track = melodyRepository.loadMelody(info) ?: return
        if (!melodySynthesizer.isReady) return
        val tempo = _gameState.value.tempoMultiplier
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
        _gameState.update { it.copy(playMelodyAudio = enabled) }
        if (!enabled) {
            melodyPlayer.resetGameSync()
        }
        saveMelodySettings()
    }

    /** Enter READY phase — melody visible but not playing. Bars pre-positioned at countdown offset. */
    fun enterReadyPhase() {
        if (_gameState.value.phase == PlayPhase.NOT_STARTED) {
            val countdown = _gameState.value.countdownDurationSeconds
            _gameState.update { it.copy(phase = PlayPhase.READY, currentTimeSeconds = -countdown) }
        }
    }

    fun startPlaying() {
        val track = _gameState.value.melodyTrack ?: return
        if (_gameState.value.phase == PlayPhase.COUNTDOWN || _gameState.value.phase == PlayPhase.PLAYING) return

        val countdownDuration = _gameState.value.countdownDurationSeconds

        _gameState.update {
            it.copy(
                phase = PlayPhase.COUNTDOWN,
                currentTimeSeconds = -countdownDuration,
                noteResults = emptyMap(),
                noteScores = emptyList(),
                currentNoteIndex = -1,
                melodyScore = null,
                noteCentsHistory = emptyMap(),
                countdownResumeTarget = 0.0
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }

        noteTracking.reset()
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
        pauseOffset = _gameState.value.currentTimeSeconds
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        noteTracking.wasWaitingForNote = noteTracking.isWaitingForNote
        noteTracking.isWaitingForNote = false
        _gameState.update { it.copy(phase = PlayPhase.PAUSED, isListening = false) }
    }

    fun resume() {
        val currentGameState = _gameState.value
        val currentViewState = _viewState.value

        // If the user panned while paused, scroll back to live and play a 3-second countdown
        // before resuming scoring — same visual effect as starting for the first time.
        if (currentViewState.songTimeOffsetSeconds != 0.0) {
            val targetTime = currentGameState.currentTimeSeconds
            val countdownDuration = currentGameState.countdownDurationSeconds
            val rewindTo = targetTime - countdownDuration
            pauseOffset = rewindTo
            noteTracking.isWaitingForNote = false
            noteTracking.wasWaitingForNote = false
            _gameState.update {
                it.copy(
                    phase = PlayPhase.COUNTDOWN,
                    currentTimeSeconds = rewindTo,
                    countdownResumeTarget = targetTime
                )
            }
            _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }
            startPlaybackTimer()
            return
        }

        if (noteTracking.wasWaitingForNote) {
            noteTracking.isWaitingForNote = true
            noteTracking.wasWaitingForNote = false
            _gameState.update { it.copy(phase = PlayPhase.WAITING_FOR_NOTE) }
        } else if (_gameState.value.currentTimeSeconds < 0.0) {
            // Still in countdown period — resume countdown
            _gameState.update { it.copy(phase = PlayPhase.COUNTDOWN) }
        } else {
            _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
        }
        if (_gameState.value.currentTimeSeconds >= 0.0) {
            startPitchDetection()
        }
        startPlaybackTimer()
    }

    fun stop() {
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        pauseOffset = 0.0
        noteTracking.isWaitingForNote = false
        noteTracking.wasWaitingForNote = false
        _gameState.update {
            it.copy(
                phase = PlayPhase.NOT_STARTED,
                currentTimeSeconds = 0.0,
                isListening = false,
                noteResults = emptyMap(),
                noteCentsHistory = emptyMap(),
                countdownResumeTarget = 0.0
            )
        }
    }

    // --- Gesture callbacks ---

    fun onVerticalZoom(scaleFactor: Float) {
        _viewState.update { current ->
            val newSpeed = (current.fallSpeedPxPerSec * scaleFactor)
                .coerceIn(MIN_FALL_SPEED, MAX_FALL_SPEED)
            current.copy(fallSpeedPxPerSec = newSpeed)
        }
        saveMelodySettings()
    }

    fun onVerticalScroll(deltaSeconds: Double) {
        val phase = _gameState.value.phase
        if (phase == PlayPhase.PLAYING || phase == PlayPhase.COUNTDOWN || phase == PlayPhase.WAITING_FOR_NOTE) return
        val gameSnapshot = _gameState.value
        val track = gameSnapshot.melodyTrack ?: return
        val currentTimeSeconds = gameSnapshot.currentTimeSeconds
        val timeSig = track.timeSignatureAt(0L)
        val firstTempoUs = track.tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L
        val barDurationSeconds = timeSig.numerator * firstTempoUs / 1_000_000.0
        val minOffset = -2.0 * barDurationSeconds - currentTimeSeconds
        _viewState.update { current ->
            val newOffset = (current.songTimeOffsetSeconds + deltaSeconds)
                .coerceIn(minOffset, track.durationSeconds - currentTimeSeconds + 2.0)
            current.copy(songTimeOffsetSeconds = newOffset)
        }
    }

    fun scrollToBeginning() {
        val track = _gameState.value.melodyTrack ?: return
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val currentTimeSeconds = _gameState.value.currentTimeSeconds
        _viewState.update { it.copy(songTimeOffsetSeconds = firstNoteStart - currentTimeSeconds) }
    }

    // --- Playback ---

    private fun startPlaybackTimer() {
        playbackStartTime = SystemClock.elapsedRealtime()
        playbackJob = viewModelScope.launch {
            val track = _gameState.value.melodyTrack ?: return@launch
            val tempo = _gameState.value.tempoMultiplier
            // For mid-song countdown resumes, pitch scoring starts at the resume target time;
            // for initial start, it starts at time 0.
            val pitchStartThreshold = _gameState.value.countdownResumeTarget.let { if (it > 0.0) it else 0.0 }
            var pitchDetectionStarted = _gameState.value.phase == PlayPhase.PLAYING ||
                    _gameState.value.phase == PlayPhase.WAITING_FOR_NOTE

            while (isActive) {
                // Grace period: continue advancing time for a while after entering wait mode
                if (noteTracking.isWaitingForNote) {
                    val graceElapsed = SystemClock.elapsedRealtime() - noteTracking.waitGraceStartTime
                    if (graceElapsed >= noteTracking.waitGraceDurationMs) {
                        // Grace expired — actually freeze time
                        delay(16)
                        continue
                    }
                    // Still in grace period — keep time moving (fall through)
                }

                val elapsed = (SystemClock.elapsedRealtime() - playbackStartTime) / 1000.0 * tempo
                val currentTime = pauseOffset + elapsed

                // Transition from COUNTDOWN to PLAYING when time reaches the threshold
                if (!pitchDetectionStarted && currentTime >= pitchStartThreshold) {
                    pitchDetectionStarted = true
                    _gameState.update { it.copy(phase = PlayPhase.PLAYING, countdownResumeTarget = 0.0) }
                    startPitchDetection()
                }

                if (currentTime >= track.durationSeconds + 1.0) {
                    finishPlaying()
                    break
                }

                _gameState.update { it.copy(currentTimeSeconds = currentTime) }

                // Only track notes once pitch detection is active (after countdown)
                if (pitchDetectionStarted) {
                    updateCurrentNote(currentTime)
                }

                // Feed melody audio earlier than t=0 to compensate for output latency.
                // updateGameTime uses lookahead internally, so we must call it
                // starting at currentTime = -outputDelay so the first note fires on time.
                if (_gameState.value.playMelodyAudio && melodySynthesizer.isReady &&
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
            _gameState.update { it.copy(isListening = true) }
            if (_deviceState.value.midiInputEnabled) {
                // MIDI input: convert note events to PitchResult
                val heldNotes = mutableSetOf<Int>()
                midiInputReceiver.midiEventFlow().collect { event ->
                    when (event) {
                        is MidiNoteEvent.NoteOn -> {
                            heldNotes.add(event.noteNumber)
                            val result = pitchResultFromMidi(event.noteNumber)
                            _gameState.update { it.copy(pitchResult = result) }
                            val midiNoteIdx = _gameState.value.currentNoteIndex
                            if (midiNoteIdx >= 0) {
                                updateNoteProgress(midiNoteIdx, result.centsOff)
                            }
                            evaluateNote(result)
                        }
                        is MidiNoteEvent.NoteOff -> {
                            heldNotes.remove(event.noteNumber)
                            if (heldNotes.isEmpty()) {
                                _gameState.update { it.copy(pitchResult = PitchResult.SILENT) }
                            } else {
                                val result = pitchResultFromMidi(heldNotes.last())
                                _gameState.update { it.copy(pitchResult = result) }
                            }
                        }
                    }
                }
            } else {
                pitchProcessor.pitchFlow().collect { result ->
                    _gameState.update { it.copy(pitchResult = result) }
                    if (result.isValid) {
                        val micNoteIdx = _gameState.value.currentNoteIndex
                        if (micNoteIdx >= 0) {
                            updateNoteProgress(micNoteIdx, result.centsOff)
                        }
                        evaluateNote(result)
                    }
                }
            }
        }
    }

    private fun updateCurrentNote(currentTime: Double) {
        val track = _gameState.value.melodyTrack ?: return
        // Backdate by input delay to match the audio being evaluated right now
        val adjustedTime = currentTime - audioInputDelayMs / 1000.0

        // Find the note that should be active at the latency-adjusted time
        val noteIndex = track.notes.indexOfFirst { note ->
            val start = track.noteStartSeconds(note)
            val duration = track.noteDurationSeconds(note)
            adjustedTime >= start && adjustedTime < start + duration
        }

        if (noteIndex != _gameState.value.currentNoteIndex && noteIndex >= 0) {
            // Finalize previous note if it wasn't scored
            finalizePreviousNote()

            // New note entered
            val results = _gameState.value.noteResults.toMutableMap()
            results[noteIndex] = NoteResultState.ACTIVE
            _gameState.update { it.copy(currentNoteIndex = noteIndex, noteResults = results) }
            currentNoteDetectedCorrectly = false
            noteTracking.resetForNewNote()

            // In wait mode, start grace period before freezing
            if (_gameState.value.waitMode) {
                noteTracking.isWaitingForNote = true
                noteTracking.waitGraceStartTime = SystemClock.elapsedRealtime()
                noteTracking.waitGraceDurationMs = audioInputDelayMs.toLong() + GRACE_PERIOD_EXTRA_MS
                _gameState.update { it.copy(phase = PlayPhase.WAITING_FOR_NOTE) }
            }
        } else if (noteIndex < 0 && _gameState.value.currentNoteIndex >= 0) {
            // We've passed the current note without a new one
            finalizePreviousNote()
            _gameState.update { it.copy(currentNoteIndex = -1) }
        }
    }

    private fun evaluateNote(result: PitchResult) {
        val track = _gameState.value.melodyTrack ?: return
        val noteIndex = _gameState.value.currentNoteIndex
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
            noteTracking.centsOffSum += abs(centsOff)
            noteTracking.centsOffCount++
            noteTracking.correctPitchFrameCount++
        }

        val isCorrectNote = abs(centsOff) <= 50f

        if (noteTracking.isWaitingForNote) {
            if (isCorrectNote) {
                // Correct note played — unfreeze
                currentNoteDetectedCorrectly = true
                noteTracking.isWaitingForNote = false
                // Recalculate playback offset so time resumes from current position
                pauseOffset = _gameState.value.currentTimeSeconds
                playbackStartTime = SystemClock.elapsedRealtime()
                _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
            } else if (_gameState.value.allowWrongNotes) {
                // Wrong note while waiting — score as miss and move on
                scoreCurrentNote(expectedNote, noteIndex)
                noteTracking.isWaitingForNote = false
                pauseOffset = _gameState.value.currentTimeSeconds
                playbackStartTime = SystemClock.elapsedRealtime()
                _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
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
        val noteIndex = _gameState.value.currentNoteIndex
        if (noteIndex < 0) return
        val track = _gameState.value.melodyTrack ?: return
        if (noteIndex >= track.notes.size) return

        // Skip if already scored
        val existingResult = _gameState.value.noteResults[noteIndex]
        if (existingResult != null && existingResult != NoteResultState.ACTIVE) return

        val expectedNote = track.notes[noteIndex]
        scoreCurrentNote(expectedNote, noteIndex)
    }

    private fun updateNoteProgress(noteIndex: Int, centsOff: Float) {
        _gameState.update { current ->
            val track = current.melodyTrack ?: return@update current
            if (noteIndex < 0 || noteIndex >= track.notes.size) return@update current
            val expectedDurationMs = (track.noteDurationSeconds(track.notes[noteIndex]) * 1000).toLong()
            if (expectedDurationMs <= 0) return@update current
            val existing = current.noteCentsHistory[noteIndex] ?: emptyList()
            val newHistory = existing + centsOff
            val progress = (newHistory.size * FRAME_DURATION_MS / expectedDurationMs.toFloat()).coerceIn(0f, 1f)
            current.copy(
                noteCentsHistory = current.noteCentsHistory + (noteIndex to newHistory),
                notePlayProgress = current.notePlayProgress + (noteIndex to progress)
            )
        }
    }

    private fun scoreCurrentNote(expectedNote: com.perfectpitch.midi.NoteEvent, noteIndex: Int) {
        val track = _gameState.value.melodyTrack ?: return
        val expectedDurationMs = (track.noteDurationSeconds(expectedNote) * 1000).toLong()
        // Duration = time player held correct pitch, derived from the actual audio chunk size
        val frameDurationMs = com.perfectpitch.audio.AudioCaptureService.BUFFER_SIZE_SAMPLES * 1000L /
            com.perfectpitch.audio.AudioCaptureService.SAMPLE_RATE
        val actualDurationMs = noteTracking.correctPitchFrameCount * frameDurationMs

        val effectiveCentsOff = if (noteTracking.centsOffCount > 0) noteTracking.centsOffSum / noteTracking.centsOffCount else 100f

        val performance = NotePerformance(
            expectedMidiNote = expectedNote.noteNumber,
            detectedMidiNote = _gameState.value.pitchResult.midiNoteNumber,
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

        _gameState.update { current ->
            val results = current.noteResults.toMutableMap()
            results[noteIndex] = resultState
            val finalProgress = (actualDurationMs.toFloat() / expectedDurationMs.toFloat()).coerceIn(0f, 1f)
            current.copy(
                noteResults = results,
                noteScores = current.noteScores + score,
                notePlayProgress = current.notePlayProgress + (noteIndex to finalProgress)
            )
        }
    }

    private fun finishPlaying() {
        // Finalize any remaining note
        finalizePreviousNote()

        // Score any notes that were never reached
        val track = _gameState.value.melodyTrack ?: return
        val currentResults = _gameState.value.noteResults
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
                _gameState.update { current ->
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
        noteTracking.isWaitingForNote = false

        val noteScores = _gameState.value.noteScores
        val melodyScore = scoringEngine.calculateMelodyScore(noteScores)

        // Scroll to beginning without changing zoom level
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val currentTimeSeconds = _gameState.value.currentTimeSeconds

        _gameState.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                melodyScore = melodyScore,
                isListening = false
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = firstNoteStart - currentTimeSeconds) }

        // Persist the score
        val melodyId = currentMelodyId
        if (melodyId != null) {
            viewModelScope.launch {
                scoreHistoryRepository.saveScore(melodyId, ScoreHistoryRepository.ScoreEntry(
                    timestamp = System.currentTimeMillis(),
                    overallScore = melodyScore.overallScore,
                    totalNotes = melodyScore.totalNotes,
                    notesHit = melodyScore.notesHit,
                    perfectNotes = melodyScore.perfectNotes,
                    greatNotes = melodyScore.greatNotes,
                    longestStreak = melodyScore.longestStreak,
                    avgPitchScore = melodyScore.avgPitchScore,
                    avgDurationScore = melodyScore.avgDurationScore
                ))
            }
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

/** Mutable note-tracking state encapsulated for a single reset() call. */
private class NoteTrackingState {
    var centsOffSum = 0f
    var centsOffCount = 0
    var correctPitchFrameCount = 0
    var isWaitingForNote = false
    var wasWaitingForNote = false
    var waitGraceStartTime = 0L
    var waitGraceDurationMs = 0L

    fun reset() {
        centsOffSum = 0f
        centsOffCount = 0
        correctPitchFrameCount = 0
        isWaitingForNote = false
        wasWaitingForNote = false
        waitGraceStartTime = 0L
        waitGraceDurationMs = 0L
    }

    /** Reset only the per-note pitch accumulation counters (called when a new note begins). */
    fun resetForNewNote() {
        centsOffSum = 0f
        centsOffCount = 0
        correctPitchFrameCount = 0
    }
}
