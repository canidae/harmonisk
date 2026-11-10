package net.exent.harmonisk.ui.melody

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.harmonisk.audio.AudioConstants
import net.exent.harmonisk.audio.MelodyPlayer
import net.exent.harmonisk.audio.SoundFontSynthesizer
import net.exent.harmonisk.audio.NotePlayer
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.data.AudioDelayRepository
import net.exent.harmonisk.data.DevModeState
import net.exent.harmonisk.data.MelodyRepository
import net.exent.harmonisk.data.MelodySettingsRepository
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.data.createProfileWithInstrument
import net.exent.harmonisk.data.ScoreHistoryRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.midi.MidiDeviceManager
import net.exent.harmonisk.midi.MidiInputReceiver
import net.exent.harmonisk.midi.MidiNoteEvent
import net.exent.harmonisk.midi.MidiOutputSender
import net.exent.harmonisk.midi.NoteEvent
import net.exent.harmonisk.music.*
import net.exent.harmonisk.ui.MAX_FALL_SPEED
import net.exent.harmonisk.ui.MAX_MIDI
import net.exent.harmonisk.ui.MIN_FALL_SPEED
import net.exent.harmonisk.ui.MIN_MIDI
import net.exent.harmonisk.ui.RANGE_PADDING
import net.exent.harmonisk.ui.celebration.CelebrationLevel
import net.exent.harmonisk.ui.adventure.AdventureSessionRepository
import net.exent.harmonisk.ui.adventure.AdventureTurn
import net.exent.harmonisk.ui.adventure.AdventureTurnResult
import kotlinx.coroutines.delay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.exent.harmonisk.BuildConfig
import javax.inject.Inject
import kotlin.math.abs

data class MelodyScoreSummary(val level: Int, val score: Float?)

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
    private val midiDeviceManager: MidiDeviceManager,
    private val midiInputReceiver: MidiInputReceiver,
    private val midiOutputSender: MidiOutputSender,
    private val scoreHistoryRepository: ScoreHistoryRepository,
    private val profileRepository: ProfileRepository,
    private val bowedAlgorithm: BowedAlgorithm,
    private val beginnerKey: BeginnerKey,
    private val devModeState: DevModeState,
    private val adventureSession: AdventureSessionRepository
) : ViewModel() {

    companion object {
        private val TAG = "MelodyViewModel"
        private const val PREVIEW_CUE_MS = 3000L
        private const val YOUR_TURN_CUE_MS = 3000L
    }

    private val _gameState = MutableStateFlow(MelodyGameState())
    val gameState: StateFlow<MelodyGameState> = _gameState.asStateFlow()

    private val _viewState = MutableStateFlow(MelodyViewState())
    val viewState: StateFlow<MelodyViewState> = _viewState.asStateFlow()

    private val _deviceState = MutableStateFlow(MelodyDeviceState())
    val deviceState: StateFlow<MelodyDeviceState> = _deviceState.asStateFlow()

    val devModeEnabled: StateFlow<Boolean> = devModeState.enabled

    val profiles: StateFlow<List<ProfileRepository.Profile>> = profileRepository.profiles
    val activeProfile: StateFlow<ProfileRepository.Profile?> = profileRepository.activeProfile

    val availableMelodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        melodyRepository.importedMelodies
            .map { imported -> melodyRepository.getBundledMelodies() + imported }
            .stateIn(viewModelScope, SharingStarted.Eagerly, melodyRepository.getBundledMelodies())

    private val _currentScoreHistory = MutableStateFlow<List<ScoreHistoryRepository.ScoreEntry>>(emptyList())
    val currentScoreHistory: StateFlow<List<ScoreHistoryRepository.ScoreEntry>> = _currentScoreHistory.asStateFlow()

    private val _personalBestScore = MutableStateFlow<Float?>(null)
    val personalBestScore: StateFlow<Float?> = _personalBestScore.asStateFlow()

    private val _allMelodyScores = MutableStateFlow<Map<String, MelodyScoreSummary>>(emptyMap())
    val allMelodyScores: StateFlow<Map<String, MelodyScoreSummary>> = _allMelodyScores.asStateFlow()

    private val lastPlayedAt: StateFlow<Map<String, Long>> = scoreHistoryRepository.lastPlayed
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val sortedMelodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        combine(availableMelodies, lastPlayedAt) { melodies, lastPlayed ->
            melodies.sortedWith(
                compareByDescending<MelodyRepository.MelodyInfo> { lastPlayed[it.id] ?: Long.MIN_VALUE }
                    .thenBy { it.name }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _allMelodyDifficulties = MutableStateFlow<Map<String, Float>>(emptyMap())
    val allMelodyDifficulties: StateFlow<Map<String, Float>> = _allMelodyDifficulties.asStateFlow()

    private val _celebrationLevel = MutableStateFlow(CelebrationLevel.NONE)
    val celebrationLevel: StateFlow<CelebrationLevel> = _celebrationLevel.asStateFlow()

    private var playbackJob: Job? = null
    private var pitchJob: Job? = null
    private var melodySettingsJob: Job? = null
    private var scoreHistoryJob: Job? = null
    private var previewClockJob: Job? = null
    private var previewSavedOffset: Double? = null
    private var runStartJob: Job? = null
    private var adventureStartJob: Job? = null
    private var playbackStartWallMs = 0L
    private var pauseOffset = 0.0

    private var waitStartWallMs = 0L
    private var waitMs = 0L

    private val noteTracking = NoteTrackingState()
    private var latestPitchResult: PitchResult = PitchResult.SILENT

    private var currentNoteDetectedCorrectly = false
    private var currentChordIndices: List<Int> = emptyList()

    private var originalMelodyTracks: List<MelodyTrack> = emptyList()
    private var currentMelodyId: String? = null
    private var manualTransposeSemitones = 0

    private val _currentInstrument = MutableStateFlow(Instrument.VIOLIN.name)
    private val currentInstrumentFlow: StateFlow<String> = _currentInstrument.asStateFlow()

    val currentInstrument: StateFlow<Instrument> = currentInstrumentFlow
        .map { Instrument.fromName(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Instrument.VIOLIN)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val instrumentFlow: Flow<Instrument> =
        adventureSession.turn.flatMapLatest { turn ->
            if (turn != null) settingsRepository.instrument(turn.playerId)
            else settingsRepository.settings.map { Instrument.fromName(it.instrumentName) }
        }

    private var synthOpenJob: Job? = null
    private var inputDelayMs: Int = 0
    private var outputDelayMs: Int = 0
    private var referenceA4: Float = NoteUtils.DEFAULT_CONCERT_PITCH

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                referenceA4 = settings.concertPitchA4
                pitchProcessor.setReferenceA4(settings.concertPitchA4)
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                _gameState.update { it.copy(metronomeEnabled = settings.melodyMetronomeEnabled) }
            }
        }
        viewModelScope.launch {
            instrumentFlow.collect { instrument ->
                val newInstrument = instrument.name
                val prevInstrument = _currentInstrument.value
                _currentInstrument.value = newInstrument
                pitchProcessor.setInstrument(instrument)
                if (newInstrument != prevInstrument && soundFontSynthesizer.isReady) {
                    soundFontSynthesizer.setInstrument(instrument.midiBank, instrument.midiProgram)
                }
                updateBowedPositions()
                val melodyId = currentMelodyId
                if (melodyId != null) {
                    loadMelodySettingsAndHistory(melodyId, newInstrument)
                }
            }
        }
        viewModelScope.launch {
            audioDelayRepository.activeInputDelay.collect { delayMs ->
                inputDelayMs = delayMs
            }
        }
        viewModelScope.launch {
            audioDelayRepository.activeOutputDelay.collect { delayMs ->
                outputDelayMs = delayMs
            }
        }
        viewModelScope.launch {
            midiDeviceManager.isMidiInputAvailable.collect { available ->
                _deviceState.update { it.copy(midiAvailable = available) }
            }
        }
        viewModelScope.launch {
            midiDeviceManager.effectiveMidiInputEnabled.collect { enabled ->
                _deviceState.update { it.copy(midiInputEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            midiDeviceManager.effectiveMidiOutputEnabled.collect { enabled ->
                _deviceState.update { it.copy(midiOutputEnabled = enabled) }
                val player: NotePlayer = if (enabled && midiOutputSender.isReady) midiOutputSender else soundFontSynthesizer
                melodyPlayer.setNotePlayer(player)
            }
        }
        loadAllMelodyScores()
        loadAllMelodyDifficulties()
        viewModelScope.launch {
            profileRepository.activeProfile
                .map { it?.id }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (adventureSession.turn.value != null) return@collect
                    if (currentMelodyId != null) {
                        stop()
                        enterReadyPhase()
                    }
                }
        }
    }

    val activeInputDelay: StateFlow<Int> = audioDelayRepository.activeInputDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
        current == true -> null
        effective -> false
        else -> true
    }

    fun selectProfile(id: String) {
        profileRepository.selectProfile(id)
    }

    fun createProfile(name: String, emoji: String, instrument: Instrument): Boolean =
        createProfileWithInstrument(viewModelScope, profileRepository, settingsRepository, name, emoji, instrument)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadAllMelodyScores() {
        viewModelScope.launch {
            combine(availableMelodies, currentInstrumentFlow) { melodies, instrument ->
                melodies to instrument
            }.flatMapLatest { (melodies, instrument) ->
                if (melodies.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    val flows = melodies.map { info ->
                        combine(
                            melodySettingsRepository.getSettings(info.id, instrument),
                            scoreHistoryRepository.getScores(info.id, instrument)
                        ) { settings, scores ->
                            val level = settings.level.coerceIn(1, 5)
                            val summary = if (scores.isEmpty()) null else MelodyScoreSummary(
                                level = level,
                                score = scores.filter { it.level == level }.maxOfOrNull { it.overallScore }
                            )
                            info.id to summary
                        }
                    }
                    combine(flows) { pairs ->
                        pairs.mapNotNull { (id, summary) -> summary?.let { id to it } }.toMap()
                    }
                }
            }.collect { result ->
                _allMelodyScores.value = result
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

    private fun assignChannels(tracks: List<MelodyTrack>): List<MelodyTrack> =
        tracks.mapIndexed { index, track ->
            if (track.notes.isEmpty()) track
            else {
                val channel = if (index >= SoundFontSynthesizer.DRUM_CHANNEL) (index + 1).coerceAtMost(15) else index
                MelodyTrack(
                    name = track.name,
                    notes = track.notes,
                    tempoChanges = track.tempoChanges,
                    timeSignatures = track.timeSignatures,
                    ticksPerQuarterNote = track.ticksPerQuarterNote,
                    channel = channel,
                    keySignature = track.keySignature
                )
            }
        }

    private fun current48thNoteMs(): Long {
        val bpm = currentMelodyBpm()
        return if (bpm > 0f) (5000.0 / bpm).toLong().coerceAtLeast(1L) else 1L
    }

    fun loadMelody(info: MelodyRepository.MelodyInfo): Boolean {
        stopPreview()
        runStartJob?.cancel()
        adventureStartJob?.cancel()
        val melodyTracks = assignChannels(melodyRepository.loadMultiTrack(info).filter { it.notes.isNotEmpty() })
        if (melodyTracks.isNotEmpty()) {
            originalMelodyTracks = melodyTracks
            currentMelodyId = info.id
            val melodyName = info.name
            currentChordIndices = emptyList()
            val primary = melodyTracks.first()
            val metronomeTrack = generateMetronomeTrack(primary)
            val (viewMin, viewMax) = viewRangeFor(primary)
            manualTransposeSemitones = 0
            _gameState.update { it.withLoadedMelody(primary, melodyTracks, metronomeTrack, melodyName) }
            _viewState.update {
                it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax, songTimeOffsetSeconds = 0.0)
            }
            updateBowedPositions()
            loadMelodySettingsAndHistory(info.id)
            return true
        }
        return false
    }

    suspend fun importFile(uri: android.net.Uri): MelodyRepository.ImportResult? = melodyRepository.importFile(uri)

    suspend fun importFromUrl(url: String): MelodyRepository.ImportResult? = melodyRepository.importFromUrl(url)

    private fun MelodyGameState.withLoadedMelody(
        primary: MelodyTrack,
        melodyTracks: List<MelodyTrack>,
        metronomeTrack: MelodyTrack?,
        name: String
    ): MelodyGameState = copy(
        melodyTrack = primary,
        melodyTracks = melodyTracks,
        metronomeTrack = metronomeTrack,
        melodyName = name,
        activeVoiceIndex = 0,
        accompanimentMode = accompanimentMode,
        phase = PlayPhase.NOT_STARTED,
        transposeSemitones = 0,
        currentTimeSeconds = -twoBarLeadInSeconds(primary),
        previewTimeSeconds = null,
        noteResults = emptyMap(),
        currentNoteIndex = -1,
        melodyScore = null,
        newlyUnlockedLevel = null,
        noteCentsHistory = emptyMap(),
        noteCentsPositions = emptyMap(),
        notePlayProgress = emptyMap(),
        noteFirstDetectionStart = emptyMap(),
        countdownResumeTarget = 0.0,
        cue = null,
        settingsLoaded = false
    )

    private fun loadMelodySettingsAndHistory(melodyId: String, instrument: String = _currentInstrument.value) {
        val adventureTurn = adventureSession.turn.value
        val profileId = adventureTurn?.playerId ?: profileRepository.activeProfileId ?: return
        melodySettingsJob?.cancel()
        scoreHistoryJob?.cancel()
        _currentScoreHistory.value = emptyList()
        _personalBestScore.value = null
        melodySettingsJob = viewModelScope.launch {
            melodySettingsRepository.getSettings(profileId, melodyId, instrument).first().let { saved ->
                val level = (adventureTurn?.level ?: saved.level).coerceIn(1, 5)
                val unlocked = maxOf(saved.unlockedLevel.coerceIn(1, 5), level)
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
                        unlockedLevel = unlocked,
                        playedLevel = level,
                        newlyUnlockedLevel = null,
                        hideNoteBars = hnb,
                        settingsLoaded = true,
                        activeVoiceIndex = saved.activeVoiceIndex,
                        accompanimentMode = accompanimentMode,
                        preferOpenStrings = saved.preferOpenStrings
                    )
                }
                _viewState.update { it.copy(fallSpeedPxPerSec = migrateFallSpeed(saved.fallSpeedPxPerSec)) }
                val tracks = _gameState.value.melodyTracks
                if (tracks.isNotEmpty()) {
                    val idx = saved.activeVoiceIndex.coerceIn(0, tracks.lastIndex)
                    setActiveVoice(idx)
                }
                manualTransposeSemitones = saved.transposeSemitones
                val auto = saved.autoTranspose
                applyTranspose(if (auto) autoOffset() else saved.transposeSemitones, auto)
            }
        }
        scoreHistoryJob = viewModelScope.launch {
            scoreHistoryRepository.getScores(profileId, melodyId, instrument).collect { scores ->
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
                unlockedLevel = maxOf(it.unlockedLevel, clamped),
                waitMode = wm,
                hideNoteBars = hnb
            )
        }
        saveMelodySettings()
    }

    fun retryLevel(level: Int) {
        if (level > _gameState.value.unlockedLevel) return
        setLevel(level)
        startPlaying()
    }

    fun setTranspose(semitones: Int) {
        manualTransposeSemitones = semitones.coerceIn(-12, 12)
        applyTranspose(manualTransposeSemitones, auto = false)
    }

    fun setAutoTranspose(enabled: Boolean) {
        applyTranspose(if (enabled) autoOffset() else manualTransposeSemitones, enabled)
    }

    fun setPreferOpenStrings(preferOpenStrings: Boolean) {
        if (_gameState.value.preferOpenStrings == preferOpenStrings) return
        _gameState.update { it.copy(preferOpenStrings = preferOpenStrings) }
        applyTranspose(
            if (_gameState.value.autoTranspose) autoOffset() else manualTransposeSemitones,
            _gameState.value.autoTranspose
        )
    }

    private fun autoOffset(): Int {
        val instrument = Instrument.fromName(_currentInstrument.value)
        val track = originalMelodyTracks.getOrNull(_gameState.value.activeVoiceIndex)
            ?: originalMelodyTracks.firstOrNull()
            ?: return 0
        return beginnerKey.offset(
            track.notes,
            track.keySignature,
            instrument,
            _gameState.value.preferOpenStrings
        )
    }

    private fun applyTranspose(semitones: Int, auto: Boolean) {
        val clamped = semitones.coerceIn(-12, 12)
        if (originalMelodyTracks.isEmpty()) return
        val melodyTracks = originalMelodyTracks.map { it.transpose(clamped) }
        val activeIdx = _gameState.value.activeVoiceIndex.coerceAtMost(melodyTracks.lastIndex)
        val primary = melodyTracks[activeIdx]
        val (viewMin, viewMax) = viewRangeFor(primary)
        _gameState.update {
            it.copy(
                melodyTrack = primary,
                melodyTracks = melodyTracks,
                transposeSemitones = clamped,
                autoTranspose = auto
            )
        }
        _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
        updateBowedPositions()
        saveMelodySettings()
    }

    private fun viewRangeFor(track: MelodyTrack): Pair<Float, Float> {
        val range = track.noteRange
        return Pair(
            (range.first - RANGE_PADDING).toFloat().coerceAtLeast(MIN_MIDI),
            (range.last + RANGE_PADDING).toFloat().coerceAtMost(MAX_MIDI)
        )
    }

    private fun updateBowedPositions() {
        val track = _gameState.value.melodyTrack
        val instrument = Instrument.fromName(_currentInstrument.value)
        val positions = if (track != null) {
            bowedAlgorithm.recommendPositions(
                instrument,
                track.notes.map { it.noteNumber },
                track.keySignature,
                _gameState.value.preferOpenStrings
            )
        } else {
            emptyList()
        }
        _viewState.update { it.copy(bowedPositions = positions) }
    }

    private fun migrateFallSpeed(saved: Float): Float {
        return if (saved == 150f) 75f else saved
    }

    private fun saveMelodySettings() {
        if (adventureSession.turn.value != null) return
        val id = currentMelodyId ?: return
        val instrument = _currentInstrument.value
        val g = _gameState.value
        val v = _viewState.value
        viewModelScope.launch {
            melodySettingsRepository.saveSettings(id, instrument, MelodySettingsRepository.MelodySettings(
                waitMode = g.waitMode,
                autoTranspose = g.autoTranspose,
                transposeSemitones = manualTransposeSemitones,
                preferOpenStrings = g.preferOpenStrings,
                fallSpeedPxPerSec = v.fallSpeedPxPerSec,
                level = g.currentLevel,
                unlockedLevel = g.unlockedLevel,
                activeVoiceIndex = g.activeVoiceIndex,
                accompanimentMode = g.accompanimentMode.name
            ))
        }
    }


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

    fun previewMelody() {
        val state = _gameState.value
        val tracks = state.melodyTracks
        if (tracks.isEmpty() || !soundFontSynthesizer.isReady) return
        stopPreview()

        val paused = state.phase == PlayPhase.PAUSED
        val countIn = !paused
        val leadIn = if (countIn) twoBarLeadInSeconds() else 0.0
        val start = if (paused) previewStartSeconds(state.melodyTrack ?: return, state.currentTimeSeconds) else 0.0
        val followsCanvas = state.phase == PlayPhase.READY || state.phase == PlayPhase.PAUSED ||
                state.phase == PlayPhase.FINISHED
        if (followsCanvas) previewSavedOffset = _viewState.value.songTimeOffsetSeconds
        melodyPlayer.playWithTiming(
            if (countIn) tracks + leadInMetronome(state.metronomeTrack) else tracks,
            viewModelScope,
            leadInSeconds = leadIn,
            startSeconds = start
        )
        if (followsCanvas) startPreviewClock(state.melodyTrack, leadIn, start)
    }

    fun previewMelody(info: MelodyRepository.MelodyInfo) {
        val tracks = melodyRepository.loadMultiTrack(info)
        if (tracks.isEmpty() || !soundFontSynthesizer.isReady) return
        melodyPlayer.playWithTiming(tracks, viewModelScope)
    }

    fun stopPreview() {
        melodyPlayer.stop()
        endPreviewClock()
        previewSavedOffset = null
    }

    fun stopPreviewAndReturn() {
        melodyPlayer.stop()
        finishPreview()
    }

    val isPreviewPlaying: StateFlow<Boolean> = melodyPlayer.isPlaying

    private fun leadInMetronome(metronome: MelodyTrack?): List<MelodyTrack> {
        val clicks = metronome?.notes?.filter { it.startTick < 0 } ?: return emptyList()
        if (clicks.isEmpty()) return emptyList()
        return listOf(
            MelodyTrack(
                name = metronome.name,
                notes = clicks,
                tempoChanges = metronome.tempoChanges,
                timeSignatures = metronome.timeSignatures,
                ticksPerQuarterNote = metronome.ticksPerQuarterNote,
                channel = metronome.channel
            )
        )
    }

    private fun startPreviewClock(track: MelodyTrack?, leadInSeconds: Double, startSeconds: Double) {
        previewClockJob?.cancel()
        previewClockJob = null
        if (track == null) return
        _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }
        _gameState.update { it.copy(previewTimeSeconds = startSeconds - leadInSeconds - outputDelayMs / 1000.0) }
        previewClockJob = viewModelScope.launch {
            val startTime = SystemClock.elapsedRealtime()
            while (isActive && melodyPlayer.isPlaying.value) {
                val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000.0
                _gameState.update {
                    it.copy(previewTimeSeconds = previewClockSeconds(startSeconds, leadInSeconds, elapsed, outputDelayMs))
                }
                delay(16)
            }
            if (isActive) finishPreview()
        }
    }

    private fun finishPreview() {
        endPreviewClock()
        val offset = previewSavedOffset ?: return
        previewSavedOffset = null
        _viewState.update { it.copy(songTimeOffsetSeconds = offset) }
    }

    private fun endPreviewClock() {
        previewClockJob?.cancel()
        previewClockJob = null
        if (_gameState.value.previewTimeSeconds != null) {
            _gameState.update { it.copy(previewTimeSeconds = null) }
        }
    }

    fun setActiveVoice(index: Int) {
        val tracks = _gameState.value.melodyTracks
        if (index < 0 || index >= tracks.size) return
        val selectedTrack = tracks[index]
        val (viewMin, viewMax) = viewRangeFor(selectedTrack)
        _gameState.update {
            it.copy(
                activeVoiceIndex = index,
                melodyTrack = selectedTrack
            )
        }
        _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
        updateBowedPositions()
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

    private fun adjustedNowSeconds(phase: PlayPhase, currentTimeSeconds: Double): Double =
        currentTimeSeconds + nowLineLeadSeconds(phase, inputDelayMs)

    private fun twoBarLeadInSeconds(track: MelodyTrack? = _gameState.value.melodyTrack): Double {
        track ?: return 2.0 * 4 * (60.0 / 120.0)
        val timeSig = track.timeSignatureAt(0)
        val bpm = track.bpmAt(0)
        val beatsPerBar = timeSig.numerator
        return 2.0 * beatsPerBar * (60.0 / bpm)
    }

    private fun generateMetronomeTrack(track: MelodyTrack): MelodyTrack? {
        val endTick = track.notes.maxOfOrNull { it.endTick } ?: return null
        val ppq = track.ticksPerQuarterNote

        val notes = mutableListOf<NoteEvent>()
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
            channel = SoundFontSynthesizer.DRUM_CHANNEL
        )
    }

    fun startRun() {
        if (runStartJob?.isActive == true) return
        val state = _gameState.value
        if (state.phase != PlayPhase.READY) return
        if (state.currentLevel != 1) {
            startPlaying()
            return
        }
        runStartJob = viewModelScope.launch {
            _gameState.update { it.copy(cue = PlayCue.PREVIEW) }
            delay(PREVIEW_CUE_MS)
            if (_gameState.value.phase != PlayPhase.READY) return@launch
            if (!awaitSynthesizer()) {
                _gameState.update { it.copy(cue = null) }
                startPlaying()
                return@launch
            }
            previewMelody()
            _gameState.update { it.copy(cue = null) }
            if (melodyPlayer.isPlaying.value) melodyPlayer.isPlaying.first { !it }
            if (_gameState.value.phase != PlayPhase.READY) return@launch
            _gameState.update { it.copy(cue = PlayCue.YOUR_TURN) }
            delay(YOUR_TURN_CUE_MS)
            _gameState.update { it.copy(cue = null) }
            if (_gameState.value.phase == PlayPhase.READY) startPlaying()
        }
    }

    fun startAdventurePlay() {
        if (adventureStartJob?.isActive == true) return
        adventureStartJob = viewModelScope.launch {
            awaitSettingsLoaded()
            if (_gameState.value.phase == PlayPhase.READY) startRun()
        }
    }

    private suspend fun awaitSettingsLoaded(timeoutMs: Long = 1500): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!_gameState.value.settingsLoaded && SystemClock.elapsedRealtime() < deadline) delay(20)
        return _gameState.value.settingsLoaded
    }

    private suspend fun awaitSynthesizer(timeoutMs: Long = 2000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!soundFontSynthesizer.isReady && SystemClock.elapsedRealtime() < deadline) delay(50)
        return soundFontSynthesizer.isReady
    }

    fun enterReadyPhase() {
        if (_gameState.value.phase == PlayPhase.NOT_STARTED) {
            _gameState.update {
                it.copy(phase = PlayPhase.READY, currentTimeSeconds = -twoBarLeadInSeconds())
            }
        }
    }

    fun startPlaying() {
        val track = _gameState.value.melodyTrack ?: return
        if (_gameState.value.phase == PlayPhase.PLAYING || _gameState.value.phase == PlayPhase.WAITING_FOR_NOTE) return

        val leadIn = twoBarLeadInSeconds()
        val startTime = playbackStartTime(leadIn)

        _gameState.update {
            it.copy(
                phase = PlayPhase.PLAYING,
                currentTimeSeconds = startTime,
                noteResults = emptyMap(),
                noteScores = emptyList(),
                currentNoteIndex = -1,
                melodyScore = null,
                noteCentsHistory = emptyMap(),
                noteCentsPositions = emptyMap(),
                noteFirstDetectionStart = emptyMap(),
                countdownResumeTarget = 0.0,
                playedLevel = it.currentLevel,
                newlyUnlockedLevel = null
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }

        noteTracking.reset()
        latestPitchResult = PitchResult.SILENT
        currentChordIndices = emptyList()
        currentNoteDetectedCorrectly = false
        waitStartWallMs = 0L
        waitMs = 0L

        stopPreview()
        melodyPlayer.resetGameSync()

        startPitchDetection()

        pauseOffset = startTime
        startPlaybackTimer()
    }

    private fun playbackStartTime(leadInSeconds: Double): Double =
        -leadInSeconds - audioLeadSeconds(inputDelayMs, outputDelayMs)

    fun pause() {
        pauseOffset = _gameState.value.currentTimeSeconds
        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        noteTracking.wasWaitingForNote = noteTracking.isWaitingForNote
        noteTracking.isWaitingForNote = false
        endWait()
        _gameState.update { it.copy(phase = PlayPhase.PAUSED) }
    }

    fun resume() {
        val currentGameState = _gameState.value
        val currentViewState = _viewState.value

        if (currentViewState.songTimeOffsetSeconds != 0.0) {
            _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }
            noteTracking.isWaitingForNote = false
            endWait()
            noteTracking.wasWaitingForNote = false
        } else if (noteTracking.wasWaitingForNote) {
            noteTracking.isWaitingForNote = true
            beginWait()
            noteTracking.wasWaitingForNote = false
            _gameState.update { it.copy(phase = PlayPhase.WAITING_FOR_NOTE) }
            startPlaybackTimer()
            return
        }

        val targetTime = when {
            currentGameState.countdownResumeTarget > 0.0 -> currentGameState.countdownResumeTarget
            currentGameState.currentTimeSeconds >= 0.0 -> currentGameState.currentTimeSeconds
            else -> 0.0
        }

        val track = _gameState.value.melodyTrack
        val countInStart = playbackStartTime(twoBarLeadInSeconds())
        val rewindTo = if (track != null) {
            val tick = track.secondsToTick(targetTime)
            val timeSig = track.timeSignatureAt(tick)
            val ticksPerBar = track.ticksPerQuarterNote.toLong() * 4 * timeSig.numerator / timeSig.denominator
            if (ticksPerBar > 0) {
                val currentBarTick = (tick / ticksPerBar) * ticksPerBar
                val startBarTick = currentBarTick - 2 * ticksPerBar
                if (startBarTick < 0) countInStart else track.tickToSeconds(startBarTick)
            } else countInStart
        } else countInStart

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
        runStartJob?.cancel()
        runStartJob = null
        stopPreview()
        _gameState.update { it.copy(cue = null) }
        pauseOffset = 0.0
        noteTracking.isWaitingForNote = false
        noteTracking.wasWaitingForNote = false
        endWait()
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

    fun removeImportedFolder(folder: String) {
        viewModelScope.launch {
            melodyRepository.removeImportedFolder(folder).forEach { scoreHistoryRepository.resetAllScores(it) }
        }
    }


    fun onVerticalZoom(scaleFactor: Float) {
        _viewState.update { current ->
            val newSpeed = (current.fallSpeedPxPerSec * scaleFactor)
                .coerceIn(MIN_FALL_SPEED, MAX_FALL_SPEED)
            current.copy(fallSpeedPxPerSec = newSpeed)
        }
        saveMelodySettings()
    }

    fun onVerticalScroll(deltaSeconds: Double) {
        if (_gameState.value.previewTimeSeconds != null) return
        val phase = _gameState.value.phase
        if (phase == PlayPhase.PLAYING || phase == PlayPhase.WAITING_FOR_NOTE) return
        val adjustedNow = adjustedNowSeconds(phase, _gameState.value.currentTimeSeconds)
        val currentOffset = _viewState.value.songTimeOffsetSeconds
        seekToViewTime(adjustedNow + currentOffset + deltaSeconds)
    }

    fun seekToViewTime(viewTimeSeconds: Double) {
        if (_gameState.value.previewTimeSeconds != null) return
        val phase = _gameState.value.phase
        if (phase == PlayPhase.PLAYING || phase == PlayPhase.WAITING_FOR_NOTE) return
        val gameSnapshot = _gameState.value
        val track = gameSnapshot.melodyTrack ?: return
        val currentTimeSeconds = gameSnapshot.currentTimeSeconds
        val adjustedNow = adjustedNowSeconds(phase, currentTimeSeconds)
        val timeSig = track.timeSignatureAt(0L)
        val firstTempoUs = track.tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L
        val barDurationSeconds = timeSig.numerator * firstTempoUs / 1_000_000.0
        val minOffset = -2.0 * barDurationSeconds - currentTimeSeconds
        val newOffset = (viewTimeSeconds - adjustedNow)
            .coerceIn(minOffset, track.durationSeconds - currentTimeSeconds + 2.0)
        _viewState.update { it.copy(songTimeOffsetSeconds = newOffset) }
    }

    fun scrollToBeginning() {
        val track = _gameState.value.melodyTrack ?: return
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val currentTimeSeconds = _gameState.value.currentTimeSeconds
        _viewState.update { it.copy(songTimeOffsetSeconds = firstNoteStart - currentTimeSeconds) }
    }


    private fun beginWait() {
        if (waitStartWallMs == 0L) waitStartWallMs = SystemClock.elapsedRealtime()
    }

    private fun endWait() {
        if (waitStartWallMs == 0L) return
        waitMs += SystemClock.elapsedRealtime() - waitStartWallMs
        waitStartWallMs = 0L
    }

    private fun startPlaybackTimer() {
        if (!devModeState.isEnabled) melodyPlayer.stop()
        playbackStartWallMs = SystemClock.elapsedRealtime()
        playbackJob = viewModelScope.launch {
            val track = _gameState.value.melodyTrack ?: return@launch
            val pitchStartThreshold = _gameState.value.countdownResumeTarget.let { if (it > 0.0) it else 0.0 }
            var pitchDetectionStarted = false
            var lastStateUpdate = 0L

            while (isActive) {
                if (noteTracking.isWaitingForNote) {
                    beginWait()
                    delay(16)
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - playbackStartWallMs) / 1000.0
                val currentTime = pauseOffset + elapsed

                if (!pitchDetectionStarted && currentTime >= pitchStartThreshold) {
                    pitchDetectionStarted = true
                    _gameState.update { it.copy(countdownResumeTarget = 0.0) }
                    startPitchDetection()
                }

                if (currentTime >= track.durationSeconds + 1.0) {
                    finishPlaying()
                    break
                }

                if (now - lastStateUpdate >= 33L) {
                    lastStateUpdate = now
                    _gameState.update { it.copy(currentTimeSeconds = currentTime) }
                }

                if (pitchDetectionStarted) {
                    updateCurrentNote(currentTime)
                }

                val gs = _gameState.value

                val playTracks = buildList {
                    addAll(
                        audibleMelodyTracks(
                            melodyTracks = gs.melodyTracks,
                            activeVoiceIndex = gs.activeVoiceIndex,
                            accompanimentMode = gs.accompanimentMode,
                            devModeEnabled = devModeState.isEnabled
                        )
                    )
                    val mt = gs.metronomeTrack
                    if (mt != null && gs.metronomeEnabled &&
                        (!gs.waitMode || !pitchDetectionStarted)) {
                        add(mt)
                    }
                }
                if (soundFontSynthesizer.isReady) {
                    melodyPlayer.updateGameTime(currentTime + audioLeadSeconds(inputDelayMs, outputDelayMs), playTracks)
                }

                delay(16)
            }
        }
    }

    private fun pitchResultFromMidi(midiNote: Int): PitchResult {
        val resolution = NoteUtils.resolveMidiNote(midiNote)
        return PitchResult(
            frequency = NoteUtils.midiToFrequency(midiNote, referenceA4),
            noteName = resolution.noteName,
            octave = resolution.octave,
            centsOff = 0f,
            midiNoteNumber = midiNote,
            isSilent = false
        )
    }

    private fun startPitchDetection() {
        if (pitchJob?.isActive == true) return
        val previous = pitchJob
        pitchJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            if (_deviceState.value.midiInputEnabled) {
                val heldNotes = mutableSetOf<Int>()
                midiInputReceiver.midiEventFlow().collect { event ->
                    when (event) {
                        is MidiNoteEvent.NoteOn -> {
                            heldNotes.add(event.noteNumber)
                            val result = pitchResultFromMidi(event.noteNumber)
                            latestPitchResult = result
                            _gameState.update { it.copy(pitchResult = result) }
                            for (idx in currentChordIndices) {
                                updateNoteProgress(idx, result.centsOff, result.frequency)
                            }
                            evaluateNote(result)
                        }
                        is MidiNoteEvent.NoteOff -> {
                            heldNotes.remove(event.noteNumber)
                            if (heldNotes.isEmpty()) {
                                latestPitchResult = PitchResult.SILENT
                                _gameState.update { it.copy(pitchResult = PitchResult.SILENT) }
                                noteTracking.wasPauseSinceLastWrong = true
                            } else {
                                val result = pitchResultFromMidi(heldNotes.last())
                                latestPitchResult = result
                                _gameState.update { it.copy(pitchResult = result) }
                            }
                        }
                    }
                }
            } else {
                var lastPitchUiUpdate = 0L
                pitchProcessor.pitchFlow().collect { result ->
                    latestPitchResult = result
                    val now = SystemClock.elapsedRealtime()
                    val updateUi = now - lastPitchUiUpdate >= 33L
                    if (updateUi) {
                        lastPitchUiUpdate = now
                        _gameState.update { it.copy(pitchResult = result) }
                    }
                    if (result.isValid) {
                        if (noteTracking.hasSeenSilenceSinceLastValid) {
                            noteTracking.wasPauseSinceLastWrong = true
                            noteTracking.hasSeenSilenceSinceLastValid = false
                        }
                        if (updateUi && result.sounding) {
                            for (idx in currentChordIndices) {
                                updateNoteProgress(idx, result.centsOff, result.frequency)
                            }
                        }
                        evaluateNote(result)
                    } else {
                        noteTracking.hasSeenSilenceSinceLastValid = true
                        noteTracking.lastPitchAtMs = 0L
                        noteTracking.lastCorrectAtMs = 0L
                    }
                }
            }
        }
    }

    private fun updateCurrentNote(currentTime: Double) {
        val gs = _gameState.value
        val track = gs.melodyTrack ?: return
        val adjustedTime = currentTime

        val activeIndices = track.activeNoteIndicesAt(adjustedTime)

        if (activeIndices != currentChordIndices) {
            finalizePreviousNote()

            val results = _gameState.value.noteResults.toMutableMap()
            for (idx in activeIndices) results[idx] = NoteResultState.ACTIVE
            _gameState.update {
                it.copy(
                    currentNoteIndex = activeIndices.firstOrNull() ?: -1,
                    noteResults = results
                )
            }
            currentChordIndices = activeIndices
            currentNoteDetectedCorrectly = false
            noteTracking.resetForNewNote()

            if (activeIndices.isNotEmpty() && _gameState.value.waitMode) {
                val noteStart = activeIndices.minOf { track.noteStartSeconds(track.notes[it]) }
                noteTracking.isWaitingForNote = true
                beginWait()
                pauseOffset = noteStart
                playbackStartWallMs = SystemClock.elapsedRealtime()
                _gameState.update {
                    it.copy(currentTimeSeconds = noteStart, phase = PlayPhase.WAITING_FOR_NOTE)
                }
            }
        }
    }

    private fun evaluateNote(result: PitchResult) {
        val track = _gameState.value.melodyTrack ?: return
        if (currentChordIndices.isEmpty()) return
        val expectedNotes = currentChordIndices.mapNotNull { track.notes.getOrNull(it) }
        if (expectedNotes.isEmpty()) return
        val primaryIdx = currentChordIndices.first()
        val expectedNote = track.notes[primaryIdx]
        val centsOff = expectedNotes.minOf { note ->
            abs(noteUtils.frequencyToCents(result.frequency, NoteUtils.midiToFrequency(note.noteNumber, referenceA4)))
        }
        val isCorrectNote = abs(centsOff) <= NoteUtils.MAX_CENTS_OFF
        val isMidi = _deviceState.value.midiInputEnabled
        val frameDurationMs = AudioConstants.FRAME_DURATION_MS

        if (result.isValid) {
            noteTracking.detectedPitchFrameCount++
            if (result.sounding) {
                val now = SystemClock.elapsedRealtime()
                noteTracking.detectedPitchMs += if (noteTracking.lastPitchAtMs == 0L) {
                    frameDurationMs
                } else {
                    (now - noteTracking.lastPitchAtMs).coerceIn(frameDurationMs, 150L)
                }
                noteTracking.lastPitchAtMs = now
                if (isCorrectNote) {
                    noteTracking.centsOffSum += abs(centsOff)
                    noteTracking.centsOffCount++
                    noteTracking.correctPitchFrameCount++
                    noteTracking.correctPitchMs += if (noteTracking.lastCorrectAtMs == 0L) {
                        frameDurationMs
                    } else {
                        (now - noteTracking.lastCorrectAtMs).coerceIn(frameDurationMs, 150L)
                    }
                    noteTracking.lastCorrectAtMs = now
                } else {
                    noteTracking.lastCorrectAtMs = 0L
                }
            } else {
                noteTracking.lastPitchAtMs = 0L
                noteTracking.lastCorrectAtMs = 0L
            }
        }

        val isNoise = !isMidi && noteTracking.detectedPitchMs < current48thNoteMs()

        if (noteTracking.isWaitingForNote) {
            if (isCorrectNote) {
                currentNoteDetectedCorrectly = true
                noteTracking.isWaitingForNote = false
                endWait()
                noteTracking.lastWrongMidiNote = -1
                noteTracking.wasPauseSinceLastWrong = false
                pauseOffset = _gameState.value.currentTimeSeconds
                playbackStartWallMs = SystemClock.elapsedRealtime()
                _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
            } else if (_gameState.value.currentLevel >= 3 && !isNoise) {
                val isSameHeldWrong = result.midiNoteNumber == noteTracking.lastWrongMidiNote
                        && !noteTracking.wasPauseSinceLastWrong
                if (!isSameHeldWrong) {
                    scoreCurrentNote(expectedNote, primaryIdx)
                    noteTracking.isWaitingForNote = false
                    endWait()
                    noteTracking.lastWrongMidiNote = result.midiNoteNumber
                    noteTracking.wasPauseSinceLastWrong = false
                    pauseOffset = _gameState.value.currentTimeSeconds
                    playbackStartWallMs = SystemClock.elapsedRealtime()
                    _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
                }
            }
        } else {
            if (isCorrectNote && !currentNoteDetectedCorrectly) {
                currentNoteDetectedCorrectly = true
            }
        }
    }

    private fun finalizePreviousNote() {
        if (currentChordIndices.isEmpty()) return
        val track = _gameState.value.melodyTrack ?: return
        for (noteIndex in currentChordIndices) {
            if (noteIndex !in track.notes.indices) continue

            val existingResult = _gameState.value.noteResults[noteIndex]
            if (existingResult != null && existingResult != NoteResultState.ACTIVE) continue

            val expectedNote = track.notes[noteIndex]
            scoreCurrentNote(expectedNote, noteIndex)
        }
    }

    private fun updateNoteProgress(noteIndex: Int, centsOff: Float, frequency: Float) {
        _gameState.update { current ->
            val track = current.melodyTrack ?: return@update current
            if (noteIndex < 0 || noteIndex >= track.notes.size) return@update current
            val note = track.notes[noteIndex]
            val noteDurationSeconds = track.noteDurationSeconds(note)
            if (noteDurationSeconds <= 0.0) return@update current
            val noteStartSeconds = track.noteStartSeconds(note)
            val expectedFreq = NoteUtils.midiToFrequency(note.noteNumber, referenceA4)
            if (abs(noteUtils.frequencyToCents(frequency, expectedFreq)) > NoteUtils.MAX_CENTS_OFF) {
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

    private fun scoreCurrentNote(expectedNote: NoteEvent, noteIndex: Int) {
        val track = _gameState.value.melodyTrack ?: return
        val level = _gameState.value.currentLevel
        val expectedDurationMs = (track.noteDurationSeconds(expectedNote) * 1000).toLong()
        val isMidi = _deviceState.value.midiInputEnabled
        val frameDurationMs = AudioConstants.FRAME_DURATION_MS
        val detectedDurationMs = if (isMidi) noteTracking.detectedPitchFrameCount * frameDurationMs else noteTracking.detectedPitchMs
        val correctDurationMs = if (isMidi) noteTracking.correctPitchFrameCount * frameDurationMs else noteTracking.correctPitchMs

        val score = if (level <= 2) {
            val actualDurationMs = detectedDurationMs
            val durationScore = scoringEngine.scoreDuration(actualDurationMs, expectedDurationMs)
            NoteScore(
                pitchScore = 100f,
                durationScore = durationScore,
                overallScore = durationScore,
                pitchAccuracy = PitchAccuracy.PERFECT
            )
        } else {
            val actualDurationMs = correctDurationMs
            val effectiveCentsOff = if (noteTracking.centsOffCount > 0) noteTracking.centsOffSum / noteTracking.centsOffCount else 100f
            val performance = NotePerformance(
                expectedMidiNote = expectedNote.noteNumber,
                detectedMidiNote = latestPitchResult.midiNoteNumber,
                centsOff = effectiveCentsOff,
                expectedDurationMs = expectedDurationMs,
                actualDurationMs = actualDurationMs
            )
            scoringEngine.scoreNote(performance)
        }

        val actualDurationMs = correctDurationMs.coerceAtLeast(1L)
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
        finalizePreviousNote()

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
        endWait()

        val noteScores = _gameState.value.noteScores
        val melodyScore = scoringEngine.calculateMelodyScore(noteScores)

        val currentLevel = _gameState.value.currentLevel
        val timeRatio = songTimeRatio(track, waitMs)
        val timeScore = ScoringEngine.scoreTime(timeRatio, currentLevel)

        val totalNotes = noteScores.size
        val wrongNoteCount = totalNotes - melodyScore.notesHit
        val wrongNoteScore = if (totalNotes > 0) (melodyScore.notesHit.toFloat() / totalNotes) * 100f else 100f

        val adjustedScore = ScoringEngine.combineScores(
            noteScore = melodyScore.overallScore,
            timeScore = timeScore,
            wrongNoteScore = wrongNoteScore,
            level = currentLevel
        )
        val adjustedMelodyScore = melodyScore.copy(
            overallScore = adjustedScore,
            timeScore = timeScore,
            wrongNoteScore = wrongNoteScore,
            wrongNoteCount = wrongNoteCount
        )

        val unlockedLevel = _gameState.value.unlockedLevel
        val previousUnlocked = unlockedLevel
        val newUnlockedLevel = ScoringEngine.unlockedLevelAfterRun(currentLevel, unlockedLevel, adjustedScore)
        val nextLevel = if (newUnlockedLevel > unlockedLevel) newUnlockedLevel else currentLevel
        val (waitMode, hideNoteBars) = levelToSettings(nextLevel)

        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val currentTimeSeconds = _gameState.value.currentTimeSeconds

        _gameState.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                melodyScore = adjustedMelodyScore,
                playedLevel = currentLevel,
                currentLevel = nextLevel,
                unlockedLevel = newUnlockedLevel,
                newlyUnlockedLevel = newUnlockedLevel.takeIf { unlocked -> unlocked > unlockedLevel },
                waitMode = waitMode,
                hideNoteBars = hideNoteBars
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = firstNoteStart - currentTimeSeconds) }

        val melodyId = currentMelodyId
        val adventureTurn = adventureSession.turn.value
        if (adventureTurn != null && melodyId != null) {
            persistAdventureRun(
                turn = adventureTurn,
                melodyId = melodyId,
                rawScore = adjustedScore,
                melodyScore = adjustedMelodyScore,
                level = currentLevel,
                unlockedLevel = newUnlockedLevel,
                nextLevel = nextLevel,
                previousUnlocked = previousUnlocked
            )
            return
        }

        saveMelodySettings()

        if (melodyId != null) {
            val existing = currentScoreHistory.value
            val previousBestScore = ScoringEngine.bestScoreForLevel(
                existing.map { it.level to it.overallScore },
                currentLevel
            )
            val scoreImproved = adjustedScore > previousBestScore
            _personalBestScore.value = maxOf(previousBestScore, adjustedScore)
            _celebrationLevel.value = if (scoreImproved) CelebrationLevel.fromScore(adjustedScore)
            else CelebrationLevel.NONE

            viewModelScope.launch {
                delay(4000)
                _celebrationLevel.value = CelebrationLevel.NONE
            }

            viewModelScope.launch {
                scoreHistoryRepository.saveScore(
                    melodyId,
                    ScoreHistoryRepository.ScoreEntry.from(
                        score = adjustedMelodyScore,
                        level = currentLevel,
                        instrument = currentInstrumentFlow.value
                    )
                )
            }
        }
    }

    private fun persistAdventureRun(
        turn: AdventureTurn,
        melodyId: String,
        rawScore: Float,
        melodyScore: MelodyScore,
        level: Int,
        unlockedLevel: Int,
        nextLevel: Int,
        previousUnlocked: Int
    ) {
        val instrument = currentInstrumentFlow.value
        viewModelScope.launch {
            scoreHistoryRepository.saveScore(
                turn.playerId,
                melodyId,
                ScoreHistoryRepository.ScoreEntry.from(
                    score = melodyScore,
                    overallScore = rawScore,
                    level = level,
                    instrument = instrument
                )
            )
            val settings = melodySettingsRepository.getSettings(turn.playerId, melodyId, instrument).first()
            melodySettingsRepository.saveSettings(
                turn.playerId,
                melodyId,
                instrument,
                settings.copy(level = nextLevel, unlockedLevel = unlockedLevel)
            )
            adventureSession.complete(
                AdventureTurnResult(
                    turn = turn,
                    rawScore = rawScore,
                    unlockedLevel = unlockedLevel,
                    newlyUnlockedLevel = unlockedLevel.takeIf { it > previousUnlocked }
                )
            )
        }
    }

    override fun onCleared() {
        playbackJob?.cancel()
        pitchJob?.cancel()
        runStartJob?.cancel()
        adventureStartJob?.cancel()
        melodyPlayer.stop()
        soundFontSynthesizer.close()
    }
}

private class NoteTrackingState {
    var centsOffSum = 0f
    var centsOffCount = 0
    var correctPitchFrameCount = 0
    var detectedPitchFrameCount = 0
    var correctPitchMs = 0L
    var detectedPitchMs = 0L
    var lastPitchAtMs = 0L
    var lastCorrectAtMs = 0L
    var isWaitingForNote = false
    var wasWaitingForNote = false

    var lastWrongMidiNote = -1
    var wasPauseSinceLastWrong = false

    var hasSeenSilenceSinceLastValid = false

    fun reset() {
        resetPitchAccumulators()
        isWaitingForNote = false
        wasWaitingForNote = false
        lastWrongMidiNote = -1
        wasPauseSinceLastWrong = false
        hasSeenSilenceSinceLastValid = false
    }

    fun resetForNewNote() = resetPitchAccumulators()

    private fun resetPitchAccumulators() {
        centsOffSum = 0f
        centsOffCount = 0
        correctPitchFrameCount = 0
        detectedPitchFrameCount = 0
        correctPitchMs = 0L
        detectedPitchMs = 0L
        lastPitchAtMs = 0L
        lastCorrectAtMs = 0L
    }
}
