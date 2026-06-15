package net.exent.perfectpitch.ui.melody

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.MelodyPlayer
import net.exent.perfectpitch.audio.MelodySynthesizer
import net.exent.perfectpitch.audio.MetronomePlayer
import net.exent.perfectpitch.audio.NotePlayer
import net.exent.perfectpitch.audio.PitchProcessor
import net.exent.perfectpitch.data.AudioDelayRepository
import net.exent.perfectpitch.data.MelodyRepository
import net.exent.perfectpitch.data.MelodySettingsRepository
import net.exent.perfectpitch.data.ScoreHistoryRepository
import net.exent.perfectpitch.data.SettingsRepository
import net.exent.perfectpitch.midi.MelodyTrack
import net.exent.perfectpitch.midi.MidiDeviceManager
import net.exent.perfectpitch.midi.MidiInputReceiver
import net.exent.perfectpitch.midi.MidiNoteEvent
import net.exent.perfectpitch.midi.MidiOutputSender
import net.exent.perfectpitch.music.*
import net.exent.perfectpitch.ui.MAX_FALL_SPEED
import net.exent.perfectpitch.ui.MIN_FALL_SPEED
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
    private val metronomePlayer: MetronomePlayer,
    private val audioDelayRepository: AudioDelayRepository,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val midiDeviceManager: MidiDeviceManager,
    private val midiInputReceiver: MidiInputReceiver,
    private val midiOutputSender: MidiOutputSender,
    private val scoreHistoryRepository: ScoreHistoryRepository
) : ViewModel() {

    companion object {
        const val NOTE_RANGE_PADDING = 3
        /** Grace time for wait-for-note mode — musically acceptable lateness. */
        const val GRACE_PERIOD_EXTRA_MS = 150L
    }

    private val _gameState = MutableStateFlow(MelodyGameState())
    val gameState: StateFlow<MelodyGameState> = _gameState.asStateFlow()

    private val _viewState = MutableStateFlow(MelodyViewState())
    val viewState: StateFlow<MelodyViewState> = _viewState.asStateFlow()

    private val _deviceState = MutableStateFlow(MelodyDeviceState())
    val deviceState: StateFlow<MelodyDeviceState> = _deviceState.asStateFlow()

    val availableMelodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        MutableStateFlow(melodyRepository.getAvailableMelodies()).asStateFlow()

    private val _currentScoreHistory = MutableStateFlow<List<ScoreHistoryRepository.ScoreEntry>>(emptyList())
    val currentScoreHistory: StateFlow<List<ScoreHistoryRepository.ScoreEntry>> = _currentScoreHistory.asStateFlow()

    private val _allMelodyCombinedScores = MutableStateFlow<Map<String, Float>>(emptyMap())
    val allMelodyCombinedScores: StateFlow<Map<String, Float>> = _allMelodyCombinedScores.asStateFlow()

    private val _canAdvanceLevel = MutableStateFlow(false)
    val canAdvanceLevel: StateFlow<Boolean> = _canAdvanceLevel.asStateFlow()

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
    // Audio delay for firing melody audio early enough to compensate for speaker latency
    private var audioOutputDelayMs: Int = 0
    // Visual delay for panning the canvas so bars/notes appear earlier than audio
    private var audioVisualDelayMs: Int = 0

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                if (settings.melodyMetronomeEnabled) {
                    metronomePlayer.open()
                    metronomePlayer.setArmed(true)
                    _gameState.update { it.copy(metronomeEnabled = true) }
                }
            }
        }
        // Observe per-device audio delay and update the melody player
        viewModelScope.launch {
            audioDelayRepository.activeAudioDelay.collect { delayMs ->
                audioOutputDelayMs = delayMs
                melodyPlayer.setAudioOutputDelay(delayMs.toLong())
            }
        }
        // Observe per-device visual delay for canvas rendering offset
        viewModelScope.launch {
            audioDelayRepository.activeVisualDelay.collect { delayMs ->
                audioVisualDelayMs = delayMs
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
        loadAllMelodyCombinedScores()
    }

    val activeAudioDelay: StateFlow<Int> = audioDelayRepository.activeAudioDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeVisualDelay: StateFlow<Int> = audioDelayRepository.activeVisualDelay
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

    fun getAvailableMelodies(): List<MelodyRepository.MelodyInfo> =
        melodyRepository.getAvailableMelodies()

    private fun loadAllMelodyCombinedScores() {
        viewModelScope.launch {
            melodyRepository.getAvailableMelodies().let { melodies ->
                val flows = melodies.map { info ->
                    scoreHistoryRepository.getScores(info.id).map { scores ->
                        info.id to ScoringEngine.computeCombinedScore(scores.map { it.level to it.overallScore })
                    }
                }
                combine(flows) { pairs ->
                    pairs.filter { it.second != null }.associate { it.first to it.second!! }
                }.collect { result ->
                    _allMelodyCombinedScores.value = result
                }
            }
        }
    }

    private fun levelToSettings(level: Int): Triple<Boolean, Boolean, Boolean> {
        return when (level) {
            1 -> Triple(true, false, false)
            2 -> Triple(true, false, false)
            3 -> Triple(true, false, false)
            4 -> Triple(false, false, false)
            5 -> Triple(false, false, true)
            else -> Triple(true, false, false)
        }
    }

    private fun current48thNoteMs(): Long {
        val bpm = currentMelodyBpm()
        return if (bpm > 0f) (5000.0 / bpm).toLong().coerceAtLeast(1L) else 1L
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
            loadMelodySettingsAndHistory(info.id)
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
            loadMelodySettingsAndHistory(currentMelodyId!!)
            return true
        }
        return false
    }

    private fun loadMelodySettingsAndHistory(melodyId: String) {
        viewModelScope.launch {
            melodySettingsRepository.getSettings(melodyId).first().let { saved ->
                val level = saved.level.coerceIn(1, 5)
                val (wm, awn, hnb) = levelToSettings(level)
                _gameState.update {
                    it.copy(
                        waitMode = wm,
                        allowWrongNotes = awn,
                        currentLevel = level,
                        hideNoteBars = hnb,
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
        viewModelScope.launch {
            scoreHistoryRepository.getScores(melodyId).collect { scores ->
                _currentScoreHistory.value = scores
            }
        }
    }

    fun setLevel(level: Int) {
        val clamped = level.coerceIn(1, 5)
        val (wm, awn, hnb) = levelToSettings(clamped)
        _gameState.update {
            it.copy(
                currentLevel = clamped,
                waitMode = wm,
                allowWrongNotes = awn,
                hideNoteBars = hnb
            )
        }
        saveMelodySettings()
    }

    fun advanceLevel() {
        val current = _gameState.value.currentLevel
        if (current < 5) setLevel(current + 1)
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
                fallSpeedPxPerSec = v.fallSpeedPxPerSec,
                level = g.currentLevel
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

    fun toggleMetronome() {
        val newState = !_gameState.value.metronomeEnabled
        _gameState.update { it.copy(metronomeEnabled = newState) }
        if (newState) {
            metronomePlayer.open()
            metronomePlayer.setArmed(true)
        } else {
            metronomePlayer.setArmed(false)
        }
        viewModelScope.launch { settingsRepository.updateMelodyMetronomeEnabled(newState) }
    }

    fun stopMetronome() {
        metronomePlayer.setArmed(false)
        _gameState.update { it.copy(metronomeEnabled = false) }
    }

    private fun currentMelodyBpm(): Float {
        val state = _gameState.value
        val track = state.melodyTrack
        if (track != null) {
            val time = state.currentTimeSeconds.coerceAtLeast(0.0)
            val tick = track.secondsToTick(time)
            return (track.bpmAt(tick) * state.tempoMultiplier).toFloat()
        }
        return 120f * state.tempoMultiplier
    }

    private fun currentBeatsPerBar(): Int {
        val track = _gameState.value.melodyTrack ?: return 4
        val time = _gameState.value.currentTimeSeconds.coerceAtLeast(0.0)
        val tick = track.secondsToTick(time)
        return track.timeSignatureAt(tick).numerator
    }

    private fun melodyPositionMs(): Long {
        val state = _gameState.value
        val multiplier = state.tempoMultiplier.coerceAtLeast(0.01f)
        return (state.currentTimeSeconds * 1000 / multiplier).toLong()
    }

    private fun countdownBars(): Int = COUNTDOWN_BARS

    /** For a mid-song [timeSeconds], returns the game-time of the downbeat exactly 2 bars
     *  before the current bar. Used as the entry point for the resume countdown. */
    private fun countdownStartTime(timeSeconds: Double): Double {
        val track = _gameState.value.melodyTrack ?: return timeSeconds - 2.0
        val tick = track.secondsToTick(timeSeconds)
        val timeSig = track.timeSignatureAt(tick)
        val ppq = track.ticksPerQuarterNote
        val numerator = timeSig.numerator
        val denominator = timeSig.denominator
        val ticksPerBar = ppq.toLong() * 4 * numerator / denominator
        if (ticksPerBar <= 0) return timeSeconds - 2.0
        val currentBarIndex = tick / ticksPerBar
        val countdownStartBar = currentBarIndex - 2
        return track.tickToSeconds(countdownStartBar * ticksPerBar)
    }

    private fun computeCountdownDuration(): Double {
        val state = _gameState.value
        val track = state.melodyTrack
        val bpm = if (track != null) {
            val time = state.currentTimeSeconds.coerceAtLeast(0.0)
            val tick = track.secondsToTick(time)
            track.bpmAt(tick)
        } else 120.0
        val beatsPerBar = currentBeatsPerBar()
        val beatInterval = 60.0 / bpm
        return countdownBars() * beatsPerBar * beatInterval + audioOutputDelayMs / 1000.0
    }

    /** Enter READY phase — melody visible but not playing. Bars pre-positioned at countdown offset. */
    fun enterReadyPhase() {
        if (_gameState.value.phase == PlayPhase.NOT_STARTED) {
            val countdown = computeCountdownDuration()
            _gameState.update { it.copy(phase = PlayPhase.READY, currentTimeSeconds = -countdown) }
        }
    }

    fun startPlaying() {
        val track = _gameState.value.melodyTrack ?: return
        if (_gameState.value.phase == PlayPhase.COUNTDOWN || _gameState.value.phase == PlayPhase.PLAYING) return

        val countdownDuration = computeCountdownDuration()

        _gameState.update {
            it.copy(
                phase = PlayPhase.COUNTDOWN,
                currentTimeSeconds = -countdownDuration,
                noteResults = emptyMap(),
                noteScores = emptyList(),
                currentNoteIndex = -1,
                melodyScore = null,
                noteCentsHistory = emptyMap(),
                noteCentsPositions = emptyMap(),
                noteFirstDetectionStart = emptyMap(),
                countdownResumeTarget = 0.0
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }

        noteTracking.reset()
        _canAdvanceLevel.value = false
        currentNoteDetectedCorrectly = false

        // Stop any preview playback and reset game sync
        melodyPlayer.stop()
        melodyPlayer.resetGameSync()

        // Start pitch detection early during countdown so the audio pipeline (AudioRecord +
        // CREPE sliding window) is warm by the time the first note starts, avoiding the
        // ~64-114ms cold-start delay that would otherwise push the pitch line into the bar.
        // The guard inside startPitchDetection() prevents duplicate launch when the timer
        // loop later transitions to PLAYING (or for resume scenarios).
        startPitchDetection()

        // Start the playback timer immediately — bars begin falling during countdown.
        // Time counts from -countdownDuration toward 0.0.
        pauseOffset = -countdownDuration
        if (_gameState.value.metronomeEnabled) {
            metronomePlayer.startSync(melodyPositionMs() + audioOutputDelayMs, currentMelodyBpm(), currentBeatsPerBar())
        }
        startPlaybackTimer()
    }

    fun pause() {
        pauseOffset = _gameState.value.currentTimeSeconds
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        metronomePlayer.stopSync()
        noteTracking.wasWaitingForNote = noteTracking.isWaitingForNote
        noteTracking.isWaitingForNote = false
        _gameState.update { it.copy(phase = PlayPhase.PAUSED, isListening = false) }
    }

    fun resume() {
        val currentGameState = _gameState.value
        val currentViewState = _viewState.value

        // Pan branch — reset the scrolled view offset and restart countdown.
        if (currentViewState.songTimeOffsetSeconds != 0.0) {
            _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }
            noteTracking.isWaitingForNote = false
            noteTracking.wasWaitingForNote = false
            // fall through to restart the countdown below
        } else if (noteTracking.wasWaitingForNote) {
            noteTracking.isWaitingForNote = true
            noteTracking.wasWaitingForNote = false
            _gameState.update { it.copy(phase = PlayPhase.WAITING_FOR_NOTE) }
            if (_gameState.value.metronomeEnabled) metronomePlayer.startSync(melodyPositionMs() + audioOutputDelayMs, currentMelodyBpm(), currentBeatsPerBar())
            startPlaybackTimer()
            return
        }

        // Determine the target time that the countdown should end at:
        //   - mid-song countdown pause → use the stored resume target
        //   - initial countdown pause (before song starts) → countdown to 0.0
        //   - active-play pause → use the time at pause
        val targetTime = when {
            currentGameState.countdownResumeTarget > 0.0 -> currentGameState.countdownResumeTarget
            currentGameState.currentTimeSeconds >= 0.0 -> currentGameState.currentTimeSeconds
            else -> 0.0
        }

        val startTime = countdownStartTime(targetTime)
        val delaySec = audioOutputDelayMs / 1000.0
        val rewindTo = startTime - delaySec
        val totalDuration = targetTime - rewindTo
        pauseOffset = rewindTo
        _gameState.update {
            it.copy(
                phase = PlayPhase.COUNTDOWN,
                currentTimeSeconds = rewindTo,
                countdownResumeTarget = targetTime
            )
        }
        if (_gameState.value.metronomeEnabled) metronomePlayer.startSync(melodyPositionMs() + audioOutputDelayMs, currentMelodyBpm(), currentBeatsPerBar())
        startPlaybackTimer()
    }

    fun stop() {
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        metronomePlayer.stopSync()
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
                noteCentsPositions = emptyMap(),
                noteFirstDetectionStart = emptyMap(),
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
                    // Stop metronome after countdown if wait-for-note is on;
                    // otherwise it continues for the full song.
                    if (_gameState.value.waitMode) {
                        metronomePlayer.stopSync()
                    }
                    startPitchDetection()
                }

                if (currentTime >= track.durationSeconds + 1.0) {
                    finishPlaying()
                    break
                }

                _gameState.update { it.copy(currentTimeSeconds = currentTime) }

                // Sync metronome with song position during active playback.
                // During initial COUNTDOWN (currentTime < 0) this doesn't fire;
                // during mid-song resume COUNTDOWN (currentTime >= 0) we skip it
                // so the downbeat-aligned startSync position is preserved.
                if (_gameState.value.metronomeEnabled && currentTime >= 0.0 &&
                    _gameState.value.phase != PlayPhase.COUNTDOWN) {
                    metronomePlayer.syncPosition(melodyPositionMs() + audioOutputDelayMs, currentMelodyBpm(), currentBeatsPerBar())
                }

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
        if (pitchJob?.isActive == true) return
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
        val adjustedTime = currentTime

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
                noteTracking.waitGraceDurationMs = GRACE_PERIOD_EXTRA_MS
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
        val expectedFreq = noteUtils.midiToFrequency(expectedNote.noteNumber)
        val centsOff = noteUtils.frequencyToCents(result.frequency, expectedFreq)

        if (result.isValid) {
            noteTracking.detectedPitchFrameCount++
        }

        if (abs(centsOff) <= 50f) {
            noteTracking.centsOffSum += abs(centsOff)
            noteTracking.centsOffCount++
            noteTracking.correctPitchFrameCount++
        }

        val isCorrectNote = abs(centsOff) <= 50f
        val isMidi = _deviceState.value.midiInputEnabled
        val frameDurationMs = net.exent.perfectpitch.audio.AudioCaptureService.BUFFER_SIZE_SAMPLES * 1000L /
            net.exent.perfectpitch.audio.AudioConstants.SAMPLE_RATE
        val isNoise = !isMidi && noteTracking.detectedPitchFrameCount * frameDurationMs < current48thNoteMs()

        if (noteTracking.isWaitingForNote) {
            if (isCorrectNote) {
                currentNoteDetectedCorrectly = true
                noteTracking.isWaitingForNote = false
                pauseOffset = _gameState.value.currentTimeSeconds
                playbackStartTime = SystemClock.elapsedRealtime()
                _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
            } else if (_gameState.value.currentLevel >= 3 && !isNoise) {
                scoreCurrentNote(expectedNote, noteIndex)
                noteTracking.isWaitingForNote = false
                pauseOffset = _gameState.value.currentTimeSeconds
                playbackStartTime = SystemClock.elapsedRealtime()
                _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
            }
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
            val note = track.notes[noteIndex]
            val noteDurationSeconds = track.noteDurationSeconds(note)
            if (noteDurationSeconds <= 0.0) return@update current
            val noteStartSeconds = track.noteStartSeconds(note)
            val expectedFreq = noteUtils.midiToFrequency(note.noteNumber)
            if (abs(noteUtils.frequencyToCents(current.pitchResult.frequency, expectedFreq)) > 50f) {
                return@update current
            }
            val pos = ((current.currentTimeSeconds - noteStartSeconds) / noteDurationSeconds).toFloat().coerceIn(0f, 1f)
            val existingHistory = current.noteCentsHistory[noteIndex] ?: emptyList()
            val existingPositions = current.noteCentsPositions[noteIndex] ?: emptyList()
            val updatedFirstDetection = if (!current.noteFirstDetectionStart.containsKey(noteIndex)) {
                current.noteFirstDetectionStart + (noteIndex to pos)
            } else {
                current.noteFirstDetectionStart
            }
            current.copy(
                noteCentsHistory = current.noteCentsHistory + (noteIndex to (existingHistory + centsOff)),
                noteCentsPositions = current.noteCentsPositions + (noteIndex to (existingPositions + pos)),
                notePlayProgress = current.notePlayProgress + (noteIndex to pos),
                noteFirstDetectionStart = updatedFirstDetection
            )
        }
    }

    private fun scoreCurrentNote(expectedNote: net.exent.perfectpitch.midi.NoteEvent, noteIndex: Int) {
        val track = _gameState.value.melodyTrack ?: return
        val level = _gameState.value.currentLevel
        val expectedDurationMs = (track.noteDurationSeconds(expectedNote) * 1000).toLong()
        // Duration = time player held correct pitch, derived from the actual audio chunk size
        val frameDurationMs = net.exent.perfectpitch.audio.AudioCaptureService.BUFFER_SIZE_SAMPLES * 1000L /
            net.exent.perfectpitch.audio.AudioConstants.SAMPLE_RATE

        val score = if (level == 1) {
            // Level 1: only duration matters, pitch is ignored
            val actualDurationMs = noteTracking.detectedPitchFrameCount * frameDurationMs
            val durationScore = scoringEngine.scoreDuration(actualDurationMs, expectedDurationMs)
            NoteScore(
                pitchScore = 100f,
                durationScore = durationScore,
                overallScore = durationScore,
                pitchAccuracy = PitchAccuracy.PERFECT
            )
        } else {
            val actualDurationMs = noteTracking.correctPitchFrameCount * frameDurationMs
            val effectiveCentsOff = if (noteTracking.centsOffCount > 0) noteTracking.centsOffSum / noteTracking.centsOffCount else 100f
            val performance = NotePerformance(
                expectedMidiNote = expectedNote.noteNumber,
                detectedMidiNote = _gameState.value.pitchResult.midiNoteNumber,
                centsOff = effectiveCentsOff,
                expectedDurationMs = expectedDurationMs,
                actualDurationMs = actualDurationMs
            )
            scoringEngine.scoreNote(performance)
        }

        val actualDurationMs = (noteTracking.correctPitchFrameCount * frameDurationMs).coerceAtLeast(1L)
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
        metronomePlayer.stopSync()
        pauseOffset = 0.0
        noteTracking.isWaitingForNote = false

        val noteScores = _gameState.value.noteScores
        val melodyScore = scoringEngine.calculateMelodyScore(noteScores)

        // Apply playback speed modifier
        val tempoPercent = (_gameState.value.tempoMultiplier * 100).toInt()
        val speedModifier = (100 - abs(100 - tempoPercent)) / 100f
        val adjustedScore = melodyScore.overallScore * speedModifier
        val adjustedMelodyScore = melodyScore.copy(overallScore = adjustedScore)

        // Check advancement eligibility (including this playthrough)
        val currentLevel = _gameState.value.currentLevel
        val pastPlaythroughs = currentScoreHistory.value.count { it.level == currentLevel }
        _canAdvanceLevel.value = currentLevel < 5 && adjustedScore >= 90f && (pastPlaythroughs + 1) >= 3

        // Scroll to beginning without changing zoom level
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val currentTimeSeconds = _gameState.value.currentTimeSeconds

        _gameState.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                melodyScore = adjustedMelodyScore,
                isListening = false
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = firstNoteStart - currentTimeSeconds) }

        // Persist the score
        val melodyId = currentMelodyId
        if (melodyId != null) {
            // Compute running cumulative combined score
            val existing = currentScoreHistory.value
            val combinedScore = ScoringEngine.computeCombinedScore(
                existing.map { it.level to it.overallScore } + (currentLevel to adjustedScore)
            )
            viewModelScope.launch {
                scoreHistoryRepository.saveScore(melodyId, ScoreHistoryRepository.ScoreEntry(
                    timestamp = System.currentTimeMillis(),
                    overallScore = adjustedScore,
                    combinedScore = combinedScore,
                    totalNotes = adjustedMelodyScore.totalNotes,
                    notesHit = adjustedMelodyScore.notesHit,
                    perfectNotes = adjustedMelodyScore.perfectNotes,
                    greatNotes = adjustedMelodyScore.greatNotes,
                    longestStreak = adjustedMelodyScore.longestStreak,
                    avgPitchScore = adjustedMelodyScore.avgPitchScore,
                    avgDurationScore = adjustedMelodyScore.avgDurationScore,
                    level = currentLevel
                ))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        pitchJob?.cancel()
        metronomePlayer.setArmed(false)
        metronomePlayer.close()
        melodyPlayer.stop()
        melodySynthesizer.close()
    }
}

/** Mutable note-tracking state encapsulated for a single reset() call. */
private class NoteTrackingState {
    var centsOffSum = 0f
    var centsOffCount = 0
    var correctPitchFrameCount = 0
    var detectedPitchFrameCount = 0
    var isWaitingForNote = false
    var wasWaitingForNote = false
    var waitGraceStartTime = 0L
    var waitGraceDurationMs = 0L

    fun reset() {
        centsOffSum = 0f
        centsOffCount = 0
        correctPitchFrameCount = 0
        detectedPitchFrameCount = 0
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
        detectedPitchFrameCount = 0
    }
}
