package net.exent.perfectpitch.ui.melody

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.perfectpitch.audio.AudioConstants
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.MelodyPlayer
import net.exent.perfectpitch.audio.SoundFontSynthesizer
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
import net.exent.perfectpitch.midi.NoteEvent
import net.exent.perfectpitch.midi.VoiceInfo
import net.exent.perfectpitch.music.*
import net.exent.perfectpitch.ui.MAX_FALL_SPEED
import net.exent.perfectpitch.ui.MAX_MIDI
import net.exent.perfectpitch.ui.MIN_FALL_SPEED
import net.exent.perfectpitch.ui.MIN_MIDI
import net.exent.perfectpitch.ui.RANGE_PADDING
import net.exent.perfectpitch.ui.celebration.CelebrationLevel
import kotlinx.coroutines.delay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.exent.perfectpitch.BuildConfig
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
    private val soundFontSynthesizer: SoundFontSynthesizer,
    private val melodyPlayer: MelodyPlayer,
    private val audioDelayRepository: AudioDelayRepository,
    private val audioDeviceMonitor: AudioDeviceMonitor,
    private val midiDeviceManager: MidiDeviceManager,
    private val midiInputReceiver: MidiInputReceiver,
    private val midiOutputSender: MidiOutputSender,
    private val scoreHistoryRepository: ScoreHistoryRepository
) : ViewModel() {

    companion object {
        private val TAG = "MelodyViewModel"
        const val GRACE_PERIOD_EXTRA_MS = 150L
        const val DRUM_CHANNEL = SoundFontSynthesizer.DRUM_CHANNEL
    }

    private val _gameState = MutableStateFlow(MelodyGameState())
    val gameState: StateFlow<MelodyGameState> = _gameState.asStateFlow()

    private val _viewState = MutableStateFlow(MelodyViewState())
    val viewState: StateFlow<MelodyViewState> = _viewState.asStateFlow()

    private val _deviceState = MutableStateFlow(MelodyDeviceState())
    val deviceState: StateFlow<MelodyDeviceState> = _deviceState.asStateFlow()

    val availableMelodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        melodyRepository.importedMelodies
            .map { imported -> melodyRepository.getBundledMelodies() + imported }
            .stateIn(viewModelScope, SharingStarted.Eagerly, melodyRepository.getBundledMelodies())

    private val _currentScoreHistory = MutableStateFlow<List<ScoreHistoryRepository.ScoreEntry>>(emptyList())
    val currentScoreHistory: StateFlow<List<ScoreHistoryRepository.ScoreEntry>> = _currentScoreHistory.asStateFlow()

    private val _allMelodyCombinedScores = MutableStateFlow<Map<String, Float>>(emptyMap())
    val allMelodyCombinedScores: StateFlow<Map<String, Float>> = _allMelodyCombinedScores.asStateFlow()

    val sortedMelodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        combine(availableMelodies, _allMelodyCombinedScores) { melodies, scores ->
            melodies.sortedWith(compareBy(
                { it.id !in scores },
                { it.name }
            ))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _allMelodyDifficulties = MutableStateFlow<Map<String, Float>>(emptyMap())
    val allMelodyDifficulties: StateFlow<Map<String, Float>> = _allMelodyDifficulties.asStateFlow()

    private val _canAdvanceLevel = MutableStateFlow(false)
    val canAdvanceLevel: StateFlow<Boolean> = _canAdvanceLevel.asStateFlow()

    private val _celebrationLevel = MutableStateFlow(CelebrationLevel.NONE)
    val celebrationLevel: StateFlow<CelebrationLevel> = _celebrationLevel.asStateFlow()

    private var playbackJob: Job? = null
    private var pitchJob: Job? = null
    private var playbackStartTime = 0L
    private var pauseOffset = 0.0

    // Wall-clock tracking for time-based scoring (excludes explicit pauses)
    private var sessionStartWallMs = 0L
    private var sessionTotalPausedMs = 0L
    private var sessionPauseStartMs = 0L
    private var leadInSeconds = 0.0

    // Per-note tracking state (encapsulated for a single reset() call)
    private val noteTracking = NoteTrackingState()
    private var currentNoteDetectedCorrectly = false

    private var originalMelodyTrack: MelodyTrack? = null
    private var originalMelodyTracks: List<MelodyTrack> = emptyList()
    private var originalVoiceInfos: List<VoiceInfo> = emptyList()
    private var currentMelodyId: String? = null

    private val _currentInstrument = MutableStateFlow("PIANO")
    private val currentInstrumentFlow: StateFlow<String> = _currentInstrument.asStateFlow()

    // Melody audio output delay
    private var synthOpenJob: Job? = null
    // Total round-trip audio delay (output + input), from calibration
    private var totalAudioDelayMs: Int = 0
    // Visual delay fine-tuning for input latency compensation
    private var visualDelayMs: Int = 0

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                _gameState.update { it.copy(metronomeEnabled = settings.melodyMetronomeEnabled) }
            }
        }
        // Observe instrument setting and apply to synthesizer
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val newInstrument = settings.instrumentName
                val prevInstrument = _currentInstrument.value
                _currentInstrument.value = newInstrument
                val instrument = Instrument.fromName(newInstrument)
                if (newInstrument != prevInstrument && soundFontSynthesizer.isReady) {
                    soundFontSynthesizer.setInstrument(instrument.midiBank, instrument.midiProgram)
                }
                val melodyId = currentMelodyId
                if (melodyId != null) {
                    loadMelodySettingsAndHistory(melodyId, newInstrument)
                }
            }
        }
        // Observe per-device audio delay and update the melody player
        viewModelScope.launch {
            audioDelayRepository.activeAudioDelay.collect { delayMs ->
                totalAudioDelayMs = delayMs
                melodyPlayer.setTotalAudioDelay(delayMs.toLong())
            }
        }
        // Observe per-device visual delay for canvas rendering offset
        viewModelScope.launch {
            audioDelayRepository.activeVisualDelay.collect { delayMs ->
                visualDelayMs = delayMs
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
                val player: NotePlayer = if (enabled && midiOutputSender.isReady) midiOutputSender else soundFontSynthesizer
                melodyPlayer.setNotePlayer(player)
            }
        }
        loadAllMelodyCombinedScores()
        loadAllMelodyDifficulties()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadAllMelodyCombinedScores() {
        viewModelScope.launch {
            combine(availableMelodies, currentInstrumentFlow) { melodies, instrument ->
                melodies to instrument
            }.flatMapLatest { (melodies, instrument) ->
                if (melodies.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    val flows = melodies.map { info ->
                        scoreHistoryRepository.getScores(info.id, instrument).map { scores ->
                            info.id to ScoringEngine.computeCombinedScore(
                                scores.map { it.level to it.overallScore }
                            )
                        }
                    }
                    combine(flows) { pairs ->
                        pairs.filter { it.second > 0f }.associate { it.first to it.second }
                    }
                }
            }.collect { result ->
                _allMelodyCombinedScores.value = result
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadAllMelodyDifficulties() {
        viewModelScope.launch {
            availableMelodies
                .flatMapLatest { melodies ->
                    flow {
                        val results = melodies.map { info ->
                            async { info.id to melodyRepository.getDifficulty(info) }
                        }
                        emit(results.awaitAll().toMap())
                    }
                }
                .collect { result ->
                    _allMelodyDifficulties.value = result
                }
        }
    }

    private fun levelToSettings(level: Int): Pair<Boolean, Boolean> {
        return when (level) {
            1 -> Pair(true, false)
            2 -> Pair(true, false)
            3 -> Pair(true, false)
            4 -> Pair(false, false)
            5 -> Pair(false, true)
            else -> Pair(true, false)
        }
    }

    /**
     * Split a list of MIDI tracks into individual voice lines.
     * Each track with simultaneous notes is decomposed into multiple
     * monophonic voice tracks.  Voices from the same original track
     * share [VoiceInfo.trackOrigin] (same sound/colour).
     */
    private fun buildVoiceInfos(tracks: List<MelodyTrack>): List<VoiceInfo> {
        val result = mutableListOf<VoiceInfo>()
        var globalIndex = 0
        for ((trackOrigin, track) in tracks.withIndex()) {
            // Assign channel based on original track index, but skip the drum channel
            val baseChannel = trackOrigin
            val channel = if (baseChannel >= MelodyViewModel.DRUM_CHANNEL) baseChannel + 1 else baseChannel
            for (voiceTrack in track.splitIntoVoices()) {
                if (voiceTrack.notes.isEmpty()) continue
                val channelTrack = MelodyTrack(
                    name = voiceTrack.name,
                    notes = voiceTrack.notes,
                    tempoChanges = voiceTrack.tempoChanges,
                    timeSignatures = voiceTrack.timeSignatures,
                    ticksPerQuarterNote = voiceTrack.ticksPerQuarterNote,
                    channel = channel
                )
                result.add(VoiceInfo(globalIndex, trackOrigin, channelTrack))
                globalIndex++
            }
        }
        return result
    }

    private fun current48thNoteMs(): Long {
        val bpm = currentMelodyBpm()
        return if (bpm > 0f) (5000.0 / bpm).toLong().coerceAtLeast(1L) else 1L
    }

    fun loadMelody(info: MelodyRepository.MelodyInfo): Boolean {
        val track = melodyRepository.loadMelody(info)
        if (track != null) {
            originalMelodyTrack = track
            originalMelodyTracks = listOf(track)
            val voiceInfos = buildVoiceInfos(listOf(track))
            originalVoiceInfos = voiceInfos
            currentMelodyId = info.id
            val primary = voiceInfos.first().melodyTrack
            val metronomeTrack = generateMetronomeTrack(primary)
            val (viewMin, viewMax) = viewRangeFor(primary)
            _gameState.update {
                it.copy(
                    melodyTrack = primary,
                    melodyTracks = voiceInfos.map { it.melodyTrack },
                    voiceInfos = voiceInfos,
                    metronomeTrack = metronomeTrack,
                    activeVoiceIndex = 0,
                    accompanimentMode = _gameState.value.accompanimentMode,
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
        val name = melodyRepository.resolveDisplayName(uri)
        val tracks = melodyRepository.loadMultiTrackFromUri(uri, name)
        if (tracks.isNotEmpty()) {
            val voiceInfos = buildVoiceInfos(tracks)
            originalMelodyTrack = tracks.first()
            originalMelodyTracks = tracks
            originalVoiceInfos = voiceInfos
            currentMelodyId = melodyRepository.uriMelodyId(uri)
            val primary = voiceInfos.first().melodyTrack
            val metronomeTrack = generateMetronomeTrack(tracks.first())
            val (viewMin, viewMax) = viewRangeFor(primary)
            _gameState.update {
                it.copy(
                    melodyTrack = primary,
                    melodyTracks = voiceInfos.map { vi -> vi.melodyTrack },
                    voiceInfos = voiceInfos,
                    metronomeTrack = metronomeTrack,
                    activeVoiceIndex = 0,
                    accompanimentMode = _gameState.value.accompanimentMode,
                    phase = PlayPhase.NOT_STARTED,
                    transposeSemitones = 0
                )
            }
            _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
            val id = currentMelodyId ?: return false
            loadMelodySettingsAndHistory(id)

            // Persist the imported melody and compute difficulty
            viewModelScope.launch {
                val localUri = melodyRepository.copyUriToLocal(uri, name)
                val persistUri = localUri ?: uri
                val info = MelodyRepository.MelodyInfo(
                    id = currentMelodyId ?: return@launch,
                    name = name,
                    isBundled = false,
                    uri = persistUri
                )
                melodyRepository.addImportedMelody(info)
                melodyRepository.getDifficulty(info)
            }
            return true
        }
        return false
    }

    private fun loadMelodySettingsAndHistory(melodyId: String, instrument: String = _currentInstrument.value) {
        viewModelScope.launch {
            melodySettingsRepository.getSettings(melodyId, instrument).first().let { saved ->
                val level = saved.level.coerceIn(1, 5)
                val (wm, hnb) = levelToSettings(level)
                val accompanimentMode = try {
                    AccompanimentMode.valueOf(saved.accompanimentMode)
                } catch (_: IllegalArgumentException) {
                    AccompanimentMode.ALL
                }
                _gameState.update {
                    it.copy(
                        waitMode = wm,
                        currentLevel = level,
                        hideNoteBars = hnb,
                        activeVoiceIndex = saved.activeVoiceIndex,
                        accompanimentMode = accompanimentMode
                    )
                }
                _viewState.update { it.copy(fallSpeedPxPerSec = saved.fallSpeedPxPerSec) }
                // Restore voice selection after state is built
                val vi = _gameState.value.voiceInfos
                if (vi.isNotEmpty()) {
                    val idx = saved.activeVoiceIndex.coerceIn(0, vi.lastIndex)
                    setActiveVoice(idx)
                }
                if (saved.transposeSemitones != 0) {
                    setTranspose(saved.transposeSemitones)
                }
            }
        }
        viewModelScope.launch {
            scoreHistoryRepository.getScores(melodyId, instrument).collect { scores ->
                _currentScoreHistory.value = scores
            }
        }
    }

    fun setLevel(level: Int) {
        val clamped = level.coerceIn(1, 5)
        val (wm, hnb) = levelToSettings(clamped)
        _gameState.update {
            it.copy(
                currentLevel = clamped,
                waitMode = wm,
                hideNoteBars = hnb
            )
        }
        saveMelodySettings()
    }

    fun advanceLevel() {
        val current = _gameState.value.currentLevel
        if (current < 5) setLevel(current + 1)
    }

    fun setTranspose(semitones: Int) {
        val clamped = semitones.coerceIn(-24, 24)
        if (originalVoiceInfos.isEmpty()) return
        val voiceInfos = originalVoiceInfos.map { vi ->
            vi.copy(melodyTrack = vi.melodyTrack.transpose(clamped))
        }
        val activeIdx = _gameState.value.activeVoiceIndex.coerceAtMost(voiceInfos.lastIndex)
        val primary = voiceInfos[activeIdx].melodyTrack
        val (viewMin, viewMax) = viewRangeFor(primary)
        _gameState.update {
            it.copy(
                melodyTrack = primary,
                melodyTracks = voiceInfos.map { vi -> vi.melodyTrack },
                voiceInfos = voiceInfos,
                transposeSemitones = clamped
            )
        }
        _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
        saveMelodySettings()
    }

    private fun viewRangeFor(track: MelodyTrack): Pair<Float, Float> {
        val range = track.noteRange
        return Pair(
            (range.first - RANGE_PADDING).toFloat().coerceAtLeast(MIN_MIDI),
            (range.last + RANGE_PADDING).toFloat().coerceAtMost(MAX_MIDI)
        )
    }

    private fun saveMelodySettings() {
        val id = currentMelodyId ?: return
        val instrument = _currentInstrument.value
        val g = _gameState.value
        val v = _viewState.value
        viewModelScope.launch {
            melodySettingsRepository.saveSettings(id, instrument, MelodySettingsRepository.MelodySettings(
                waitMode = g.waitMode,
                transposeSemitones = g.transposeSemitones,
                fallSpeedPxPerSec = v.fallSpeedPxPerSec,
                level = g.currentLevel,
                activeVoiceIndex = g.activeVoiceIndex,
                accompanimentMode = g.accompanimentMode.name
            ))
        }
    }

    // --- Melody preview / listen ---

    /**
     * Open the synthesizer. Call this before using audio playback.
     */
    fun initSynthesizer() {
        if (soundFontSynthesizer.isReady || synthOpenJob?.isActive == true) return
        synthOpenJob = viewModelScope.launch {
            try {
                soundFontSynthesizer.open()
                val instrument = Instrument.fromName(_currentInstrument.value)
                soundFontSynthesizer.setInstrument(instrument.midiBank, instrument.midiProgram)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Synth init failed", e)
            }
        }
    }

    /** Preview the loaded melody (audio only, standalone playback). */
    fun previewMelody() {
        val tracks = _gameState.value.melodyTracks
        if (tracks.isEmpty() || !soundFontSynthesizer.isReady) return
        melodyPlayer.playWithTiming(tracks, viewModelScope)
    }

    /** Preview a specific melody from its info (uses all voice tracks). */

    /** Preview a specific melody info (loads and plays). */
    fun previewMelody(info: MelodyRepository.MelodyInfo) {
        val track = melodyRepository.loadMelody(info) ?: return
        if (!soundFontSynthesizer.isReady) return
        melodyPlayer.playWithTiming(track, viewModelScope)
    }

    /** Stop any ongoing melody preview. */
    fun stopPreview() {
        melodyPlayer.stop()
    }

    /** Whether a melody preview is currently playing. */
    val isPreviewPlaying: StateFlow<Boolean> = melodyPlayer.isPlaying

    /** Toggle in-game melody audio playback. */
    fun setActiveVoice(index: Int) {
        val infos = _gameState.value.voiceInfos
        if (index < 0 || index >= infos.size) return
        val selectedTrack = infos[index].melodyTrack
        val (viewMin, viewMax) = viewRangeFor(selectedTrack)
        _gameState.update {
            it.copy(
                activeVoiceIndex = index,
                melodyTrack = selectedTrack
            )
        }
        _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
        saveMelodySettings()
    }

    fun setAccompanimentMode(mode: AccompanimentMode) {
        _gameState.update { it.copy(accompanimentMode = mode) }
        if (mode == AccompanimentMode.NONE) {
            melodyPlayer.resetGameSync()
        }
        saveMelodySettings()
    }

    fun toggleMetronome() {
        val newState = !_gameState.value.metronomeEnabled
        _gameState.update { it.copy(metronomeEnabled = newState) }
        viewModelScope.launch { settingsRepository.updateMelodyMetronomeEnabled(newState) }
    }

    private fun currentMelodyBpm(): Float {
        val state = _gameState.value
        val track = state.melodyTrack
        if (track != null) {
            val time = state.currentTimeSeconds.coerceAtLeast(0.0)
            val tick = track.secondsToTick(time)
            return track.bpmAt(tick).toFloat()
        }
        return 120f
    }

    /** Seconds of the 2-bar lead-in at the song's initial tempo/time signature. */
    private fun twoBarLeadInSeconds(): Double {
        val track = _gameState.value.melodyTrack ?: return 2.0 * 4 * (60.0 / 120.0)
        val timeSig = track.timeSignatureAt(0)
        val bpm = track.bpmAt(0)
        val beatsPerBar = timeSig.numerator
        return 2.0 * beatsPerBar * (60.0 / bpm)
    }

    /** Generate a hidden metronome track with bar/beat clicks on the drum channel. */
    private fun generateMetronomeTrack(track: MelodyTrack): MelodyTrack? {
        val endTick = track.notes.maxOfOrNull { it.endTick } ?: return null
        val ppq = track.ticksPerQuarterNote

        val notes = mutableListOf<NoteEvent>()
        // Lead-in: 2 bars before tick 0 using the first time signature
        val firstTimeSig = track.timeSignatureAt(0)
        val firstTicksPerBar = ppq * 4 * firstTimeSig.numerator / firstTimeSig.denominator
        if (firstTicksPerBar <= 0) return null

        var tick = -2L * firstTicksPerBar
        while (tick <= endTick) {
            val timeSig = track.timeSignatureAt(tick.coerceAtLeast(0))
            val beatsPerBar = timeSig.numerator
            val ticksPerBar = ppq * 4 * timeSig.numerator / timeSig.denominator
            if (ticksPerBar <= 0 || beatsPerBar <= 0) { tick += ppq * 4; continue }
            val ticksPerBeat = ticksPerBar / beatsPerBar
            if (ticksPerBeat <= 0) { tick += ppq * 4; continue }

            for (beat in 0 until beatsPerBar) {
                val beatTick = tick + beat * ticksPerBeat
                if (beatTick > endTick) break
                val isAccent = beat == 0
                val note = if (isAccent) 86 else 85
                val vel = if (isAccent) 120 else 100
                val clickDuration = (ppq / 10).coerceAtLeast(1).toLong()
                notes.add(NoteEvent(note, vel, beatTick, clickDuration))
            }
            tick += ticksPerBar
        }

        if (notes.isEmpty()) return null
        return MelodyTrack(
            name = "metronome",
            notes = notes,
            tempoChanges = track.tempoChanges,
            timeSignatures = track.timeSignatures,
            ticksPerQuarterNote = ppq,
            channel = MelodyViewModel.DRUM_CHANNEL,
            isMetronome = true
        )
    }

    /** Enter READY phase — melody visible but not playing. Bars pre-positioned at lead-in offset. */
    fun enterReadyPhase() {
        if (_gameState.value.phase == PlayPhase.NOT_STARTED) {
            val leadIn = twoBarLeadInSeconds()
            _gameState.update { it.copy(phase = PlayPhase.READY, currentTimeSeconds = -leadIn) }
        }
    }

    fun startPlaying() {
        val track = _gameState.value.melodyTrack ?: return
        if (_gameState.value.phase == PlayPhase.PLAYING || _gameState.value.phase == PlayPhase.WAITING_FOR_NOTE) return

        val leadIn = twoBarLeadInSeconds()

        _gameState.update {
            it.copy(
                phase = PlayPhase.PLAYING,
                currentTimeSeconds = -leadIn,
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
        leadInSeconds = leadIn
        sessionStartWallMs = SystemClock.elapsedRealtime()
        sessionTotalPausedMs = 0L
        sessionPauseStartMs = 0L

        // Stop any preview playback and reset game sync
        melodyPlayer.stop()
        melodyPlayer.resetGameSync()

        // Start pitch detection early so the audio pipeline is warm by the time
        // the first note starts.
        startPitchDetection()

        // Start the playback timer immediately — bars begin falling during lead-in.
        pauseOffset = -leadIn
        startPlaybackTimer()
    }

    fun pause() {
        pauseOffset = _gameState.value.currentTimeSeconds
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        noteTracking.wasWaitingForNote = noteTracking.isWaitingForNote
        noteTracking.isWaitingForNote = false
        sessionPauseStartMs = SystemClock.elapsedRealtime()
        _gameState.update { it.copy(phase = PlayPhase.PAUSED) }
    }

    fun resume() {
        if (sessionPauseStartMs > 0L) {
            sessionTotalPausedMs += SystemClock.elapsedRealtime() - sessionPauseStartMs
            sessionPauseStartMs = 0L
        }
        val currentGameState = _gameState.value
        val currentViewState = _viewState.value

        // Pan branch — reset the scrolled view offset and restart
        if (currentViewState.songTimeOffsetSeconds != 0.0) {
            _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }
            noteTracking.isWaitingForNote = false
            noteTracking.wasWaitingForNote = false
        } else if (noteTracking.wasWaitingForNote) {
            noteTracking.isWaitingForNote = true
            noteTracking.wasWaitingForNote = false
            _gameState.update { it.copy(phase = PlayPhase.WAITING_FOR_NOTE) }
            startPlaybackTimer()
            return
        }

        // Determine the target time that playback resumes to
        val targetTime = when {
            currentGameState.countdownResumeTarget > 0.0 -> currentGameState.countdownResumeTarget
            currentGameState.currentTimeSeconds >= 0.0 -> currentGameState.currentTimeSeconds
            else -> 0.0
        }

        // Rewind 2 bars before the target
        val track = _gameState.value.melodyTrack
        val rewindTo = if (track != null) {
            val tick = track.secondsToTick(targetTime)
            val timeSig = track.timeSignatureAt(tick)
            val ticksPerBar = track.ticksPerQuarterNote.toLong() * 4 * timeSig.numerator / timeSig.denominator
            if (ticksPerBar > 0) {
                val currentBarTick = (tick / ticksPerBar) * ticksPerBar
                val startBarTick = currentBarTick - 2 * ticksPerBar
                if (startBarTick < 0) -twoBarLeadInSeconds() else track.tickToSeconds(startBarTick)
            } else -twoBarLeadInSeconds()
        } else -twoBarLeadInSeconds()

        pauseOffset = rewindTo
        _gameState.update {
            it.copy(
                phase = PlayPhase.PLAYING,
                currentTimeSeconds = rewindTo,
                countdownResumeTarget = targetTime
            )
        }
        startPlaybackTimer()
    }

    fun stop() {
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.stop()
        pauseOffset = 0.0
        noteTracking.isWaitingForNote = false
        noteTracking.wasWaitingForNote = false
        sessionPauseStartMs = 0L
        _gameState.update {
            it.copy(
                phase = PlayPhase.NOT_STARTED,
                currentTimeSeconds = 0.0,
                noteResults = emptyMap(),
                noteCentsHistory = emptyMap(),
                noteCentsPositions = emptyMap(),
                noteFirstDetectionStart = emptyMap(),
                countdownResumeTarget = 0.0
            )
        }
    }

    fun resetScore(melodyId: String) {
        viewModelScope.launch {
            scoreHistoryRepository.resetScores(melodyId, currentInstrumentFlow.value)
        }
    }

    fun removeImportedMelody(melodyId: String) {
        viewModelScope.launch {
            melodyRepository.removeImportedMelody(melodyId)
            scoreHistoryRepository.resetAllScores(melodyId)
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
        if (phase == PlayPhase.PLAYING || phase == PlayPhase.WAITING_FOR_NOTE) return
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
            // For mid-song countdown resumes, pitch scoring starts at the resume target time;
            // for initial start, it starts at time 0.
            val pitchStartThreshold = _gameState.value.countdownResumeTarget.let { if (it > 0.0) it else 0.0 }
            var pitchDetectionStarted = false

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

                val elapsed = (SystemClock.elapsedRealtime() - playbackStartTime) / 1000.0
                val currentTime = pauseOffset + elapsed

                // Start pitch detection when time reaches the threshold
                if (!pitchDetectionStarted && currentTime >= pitchStartThreshold) {
                    pitchDetectionStarted = true
                    _gameState.update { it.copy(countdownResumeTarget = 0.0) }
                    startPitchDetection()
                }

                if (currentTime >= track.durationSeconds + 1.0) {
                    finishPlaying()
                    break
                }

                _gameState.update { it.copy(currentTimeSeconds = currentTime) }

                // Only track notes once pitch detection is active
                if (pitchDetectionStarted) {
                    updateCurrentNote(currentTime - totalAudioDelayMs / 1000.0)
                }

                // Audio plays at natural song time — visual offset handles sync.
                val gs = _gameState.value

                val playTracks = buildList {
                    addAll(when (gs.accompanimentMode) {
                        AccompanimentMode.ALL -> gs.melodyTracks
                        AccompanimentMode.EXCEPT -> {
                            val vi = gs.voiceInfos
                            vi.filterIndexed { i, _ -> i != gs.activeVoiceIndex }.map { it.melodyTrack }
                        }
                        AccompanimentMode.SELECTED_ONLY -> {
                            val vi = gs.voiceInfos
                            if (gs.activeVoiceIndex in vi.indices)
                                listOf(vi[gs.activeVoiceIndex].melodyTrack)
                            else emptyList()
                        }
                        AccompanimentMode.NONE -> emptyList()
                    })
                    // Conditionally include the metronome track
                    val mt = gs.metronomeTrack
                    if (mt != null && gs.metronomeEnabled &&
                        (!gs.waitMode || !pitchDetectionStarted)) {
                        add(mt)
                    }
                }
                if (playTracks.isNotEmpty() && soundFontSynthesizer.isReady) {
                    melodyPlayer.updateGameTime(currentTime, playTracks)
                }

                delay(16)
            }
        }
    }

    private fun pitchResultFromMidi(midiNote: Int): PitchResult {
        val resolution = noteUtils.resolveMidiNote(midiNote)
        return PitchResult(
            frequency = NoteUtils.midiToFrequency(midiNote),
            noteName = resolution.noteName,
            octave = resolution.octave,
            centsOff = 0f,
            midiNoteNumber = midiNote,
            isSilent = false
        )
    }

    private fun startPitchDetection() {
        if (pitchJob?.isActive == true) return
        pitchJob = viewModelScope.launch {
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
                                noteTracking.wasPauseSinceLastWrong = true
                                noteTracking.pendingRearticulation = false
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
                        if (noteTracking.hasSeenSilenceSinceLastValid) {
                            noteTracking.wasPauseSinceLastWrong = true
                            noteTracking.pendingRearticulation = false
                            noteTracking.hasSeenSilenceSinceLastValid = false
                        }
                        val micNoteIdx = _gameState.value.currentNoteIndex
                        if (micNoteIdx >= 0) {
                            updateNoteProgress(micNoteIdx, result.centsOff)
                        }
                        evaluateNote(result)
                    } else {
                        noteTracking.hasSeenSilenceSinceLastValid = true
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

            // Remember the previous note index before it is overwritten below
            val prevNoteIdx = _gameState.value.currentNoteIndex

            // New note entered
            val results = _gameState.value.noteResults.toMutableMap()
            results[noteIndex] = NoteResultState.ACTIVE
            _gameState.update { it.copy(currentNoteIndex = noteIndex, noteResults = results) }
            currentNoteDetectedCorrectly = false
            noteTracking.resetForNewNote()

            // Detect if the user held through a same-pitch transition
            if (prevNoteIdx >= 0 && prevNoteIdx < track.notes.size) {
                val prevNote = track.notes[prevNoteIdx]
                val newNote = track.notes[noteIndex]
                if (prevNote.noteNumber == newNote.noteNumber) {
                    val currentResult = _gameState.value.pitchResult
                    val newNoteFreq = NoteUtils.midiToFrequency(newNote.noteNumber)
                    if (currentResult.isValid && abs(
                            noteUtils.frequencyToCents(currentResult.frequency, newNoteFreq)
                        ) <= 50f) {
                        noteTracking.pendingRearticulation = true
                    }
                }
            }

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
        val expectedFreq = NoteUtils.midiToFrequency(expectedNote.noteNumber)
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
        val frameDurationMs = AudioConstants.FRAME_DURATION_MS
        val isNoise = !isMidi && noteTracking.detectedPitchFrameCount * frameDurationMs < current48thNoteMs()

        if (noteTracking.isWaitingForNote) {
            if (isCorrectNote) {
                if (noteTracking.pendingRearticulation) {
                    // Same-pitch note held through — need a pause + re-attack
                } else {
                    currentNoteDetectedCorrectly = true
                    noteTracking.isWaitingForNote = false
                    noteTracking.lastWrongMidiNote = -1
                    noteTracking.wasPauseSinceLastWrong = false
                    noteTracking.pendingRearticulation = false
                    pauseOffset = _gameState.value.currentTimeSeconds
                    playbackStartTime = SystemClock.elapsedRealtime()
                    _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
                }
            } else if (_gameState.value.currentLevel >= 3 && !isNoise) {
                val isSameHeldWrong = result.midiNoteNumber == noteTracking.lastWrongMidiNote
                        && !noteTracking.wasPauseSinceLastWrong
                if (!isSameHeldWrong) {
                    scoreCurrentNote(expectedNote, noteIndex)
                    noteTracking.isWaitingForNote = false
                    noteTracking.lastWrongMidiNote = result.midiNoteNumber
                    noteTracking.wasPauseSinceLastWrong = false
                    noteTracking.pendingRearticulation = false
                    pauseOffset = _gameState.value.currentTimeSeconds
                    playbackStartTime = SystemClock.elapsedRealtime()
                    _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
                }
                // If same held wrong pitch or isNoise: keep waiting
            }
        } else {
            // Continuous mode (not waiting)
            if (isCorrectNote && !currentNoteDetectedCorrectly) {
                if (noteTracking.pendingRearticulation) {
                    // Same-pitch note held through — need a pause + re-attack
                } else {
                    currentNoteDetectedCorrectly = true
                }
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
            val expectedFreq = NoteUtils.midiToFrequency(note.noteNumber)
            if (abs(noteUtils.frequencyToCents(current.pitchResult.frequency, expectedFreq)) > 50f) {
                return@update current
            }
            val pos = ((current.currentTimeSeconds - totalAudioDelayMs / 1000.0 - noteStartSeconds) / noteDurationSeconds).toFloat().coerceIn(0f, 1f)
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
        val frameDurationMs = AudioConstants.FRAME_DURATION_MS

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
        pauseOffset = 0.0
        noteTracking.isWaitingForNote = false

        val noteScores = _gameState.value.noteScores
        val melodyScore = scoringEngine.calculateMelodyScore(noteScores)

        // Apply time-based scoring modifier
        val currentLevel = _gameState.value.currentLevel
        val now = SystemClock.elapsedRealtime()
        val totalWallMs = now - sessionStartWallMs - sessionTotalPausedMs
        val leadInMs = (leadInSeconds * 1000).toLong()
        val effectivePlayMs = (totalWallMs - leadInMs).coerceAtLeast(0L)
        val totalPlaySec = effectivePlayMs / 1000.0
        val songDurationSec = track.durationSeconds
        val timeRatio = if (songDurationSec > 0.0) totalPlaySec / songDurationSec else 0.0
        val timeScore = ScoringEngine.scoreTime(timeRatio, currentLevel)

        // Wrong note count and score derived from notesHit/totalNotes
        val totalNotes = noteScores.size
        val wrongNoteCount = totalNotes - melodyScore.notesHit
        val wrongNoteScore = if (totalNotes > 0) (melodyScore.notesHit.toFloat() / totalNotes) * 100f else 100f

        // Apply as additional multiplier for levels >= 3
        var adjustedScore = melodyScore.overallScore * timeScore
        if (currentLevel >= 3) {
            adjustedScore *= (wrongNoteScore / 100f)
        }
        val adjustedMelodyScore = melodyScore.copy(
            overallScore = adjustedScore,
            timeScore = timeScore,
            wrongNoteScore = wrongNoteScore,
            wrongNoteCount = wrongNoteCount
        )

        // Check advancement eligibility
        _canAdvanceLevel.value = currentLevel < 5 && adjustedScore >= 90f

        // Scroll to beginning without changing zoom level
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val currentTimeSeconds = _gameState.value.currentTimeSeconds

        _gameState.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                melodyScore = adjustedMelodyScore
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = firstNoteStart - currentTimeSeconds) }

        // Persist the score
        val melodyId = currentMelodyId
        if (melodyId != null) {
            // Compute running cumulative combined score
            val existing = currentScoreHistory.value
            val previousBestCombinedScore = existing.maxOfOrNull { it.combinedScore } ?: 0f
            val combinedScore = ScoringEngine.computeCombinedScore(
                existing.map { it.level to it.overallScore } + (currentLevel to adjustedScore)
            )
            val scoreImproved = combinedScore > previousBestCombinedScore
            _celebrationLevel.value = if (scoreImproved) CelebrationLevel.fromScore(combinedScore)
            else CelebrationLevel.NONE

            viewModelScope.launch {
                delay(4000)
                _celebrationLevel.value = CelebrationLevel.NONE
            }

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
                    level = currentLevel,
                    instrument = currentInstrumentFlow.value,
                    wrongNoteCount = adjustedMelodyScore.wrongNoteCount,
                    wrongNoteScore = adjustedMelodyScore.wrongNoteScore
                ))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.stop()
        soundFontSynthesizer.close()
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

    // Wrong-note hold detection (persists across note boundaries)
    var lastWrongMidiNote = -1
    var wasPauseSinceLastWrong = false

    // Same-pitch rearticulation (per-note)
    var pendingRearticulation = false

    // Transient silence tracking between valid pitch frames
    var hasSeenSilenceSinceLastValid = false

    fun reset() {
        centsOffSum = 0f
        centsOffCount = 0
        correctPitchFrameCount = 0
        detectedPitchFrameCount = 0
        isWaitingForNote = false
        wasWaitingForNote = false
        waitGraceStartTime = 0L
        waitGraceDurationMs = 0L
        lastWrongMidiNote = -1
        wasPauseSinceLastWrong = false
        pendingRearticulation = false
        hasSeenSilenceSinceLastValid = false
    }

    /** Reset only the per-note pitch accumulation counters (called when a new note begins). */
    fun resetForNewNote() {
        centsOffSum = 0f
        centsOffCount = 0
        correctPitchFrameCount = 0
        detectedPitchFrameCount = 0
        pendingRearticulation = false
    }
}
